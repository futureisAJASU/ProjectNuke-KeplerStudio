#!/usr/bin/env python3
"""
N4.6 host reference halo sweep.

Proves the tile-boundary receptive-field halo using the pinned upstream
SRVGGNetCompact architecture (tools/exynos_reference/upstream/srvgg_arch.py).

For several deterministic images larger than 128, run the model ONCE over the whole
image (full_reference) and separately simulate the fixed 128x128 overlap-and-crop tiling
(mirroring tools/../TilePlanner.kt) at a sweep of candidate halo values. Compare the
tiled assembly to the full reference on raw FP32 output.

This isolates TILE GEOMETRY from NPU numerical drift: the SAME PyTorch model produces
both references, so any residual seam error is purely the tile planner/context, not
compiler drift.

Weights: the pinned checkpoint files are not vendored in-repo. The receptive field is an
ARCHITECTURE property — independent of weights — so this sweep runs with deterministic
seeded weights by default (proving the geometry robustly). If `--weights-dir` is given and
contains `realesr-general-x4v3.pth`, the real GENERAL checkpoint is used instead.

Outputs (to `--out-dir`):
  halo_sweep.json    per-fixture x per-halo metric grid (global/seam/non-seam/border)
  heatmaps/          amplitude-normalized abs-diff PNGs for representative fixtures/halos
  halo_summary.csv   flattened table
"""
import argparse
import csv
import hashlib
import json
import os
import struct
import sys
import zlib

import numpy as np
import torch

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(THIS_DIR, "shim"))
sys.path.insert(0, os.path.join(THIS_DIR, "upstream"))

from srvgg_arch import SRVGGNetCompact  # noqa: E402

TILE = 128
SCALE = 4
OUT_TILE = TILE * SCALE
C = 3
SEED = 20260828
SEAM_BAND = 8   # output pixels around every internal ownership boundary
BORDER_BAND = 8  # output pixels around the outer image boundary
HALOS = [8, 16, 24, 32, 33, 34, 35, 36, 40]


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def build_model():
    return SRVGGNetCompact(
        num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4, act_type="prelu"
    )


def init_deterministic(model, seed=SEED):
    g = torch.Generator()
    g.manual_seed(seed)
    for name, p in model.named_parameters():
        if p.dim() >= 2:
            torch.nn.init.kaiming_uniform_(p, a=0.1, generator=g)
        elif "weight" in name:
            torch.nn.init.uniform_(p, 0.0, 1.0, generator=g)
        else:
            torch.nn.init.zeros_(p)
    model.eval()


def make_fixture(name, w, h):
    rng = np.random.RandomState(SEED)
    if name == "smooth_2d_gradient":
        xs = np.arange(w, dtype=np.float64)
        ys = np.arange(h, dtype=np.float64)
        xg, yg = np.meshgrid(xs, ys)
        r = 127.5 + 127.5 * np.cos(2 * np.pi * xg / 64.0)
        g = 127.5 + 127.5 * np.cos(2 * np.pi * yg / 64.0)
        b = 127.5 + 127.5 * np.cos(2 * np.pi * (xg + yg) / 128.0)
    elif name == "rgb_ramps":
        xs = np.arange(w, dtype=np.float64)
        ys = np.arange(h, dtype=np.float64)
        xg, yg = np.meshgrid(xs, ys)
        r = xg * 255 / max(w - 1, 1)
        g = yg * 255 / max(h - 1, 1)
        b = (xg + yg) * 255 / max(w + h - 2, 1)
    elif name == "edges_crossing":
        r = np.zeros((h, w)); g = np.zeros((h, w)); b = np.zeros((h, w))
        for x in range(0, w, 37):
            r[:, x] = 255
        for y in range(0, h, 41):
            g[y, :] = 255
        b[:, w // 2] = 255
        b[h // 2, :] = 255
    elif name == "high_frequency_checker":
        xg, yg = np.meshgrid(np.arange(w), np.arange(h))
        on = ((xg // 3) + (yg // 3)) % 2 == 0
        r = np.where(on, 255, 0); g = np.where(on, 200, 40); b = np.where(on, 40, 200)
    elif name == "deterministic_noise":
        vals = rng.randint(0, 256, size=(h, w), dtype=np.int32)
        r = vals.astype(np.float64)
        g = rng.randint(0, 256, size=(h, w), dtype=np.int32).astype(np.float64)
        b = rng.randint(0, 256, size=(h, w), dtype=np.int32).astype(np.float64)
    elif name == "mixed_photo_like":
        xg, yg = np.meshgrid(np.arange(w), np.arange(h))
        base = 100.0 + 80.0 * np.sin(2 * np.pi * xg / 90.0) * np.cos(2 * np.pi * yg / 70.0)
        noise = rng.normal(0, 12, size=(h, w))
        r = np.clip(base + noise, 0, 255)
        g = np.clip(base + noise[:, ::-1], 0, 255)
        b = np.clip(170 - base + noise, 0, 255)
        r[:, w // 2] = 255; g[h // 2, :] = 255
    else:
        raise ValueError(name)
    grid = np.stack([r, g, b], axis=-1)
    return np.clip(grid, 0, 255).astype(np.uint8)


def plan_axis(dim, halo):
    step = TILE - 2 * halo
    assert step >= 1, "halo too large"
    starts = []
    pos = 0
    while True:
        starts.append(pos)
        if pos >= dim - TILE:
            break
        nxt = pos + step
        pos = nxt if nxt >= dim - TILE else nxt
        pos = dim - TILE if pos > dim - TILE else pos
    # strictly increasing, deduplicated
    starts = sorted(set(starts))
    edges = [0]
    for t in range(len(starts) - 1):
        left_valid_next = 0 if starts[t + 1] == 0 else starts[t + 1] + halo
        right_valid_cur = dim if starts[t] + TILE == dim else starts[t] + TILE - halo
        edges.append((left_valid_next + right_valid_cur) * 2)
    edges.append(dim * SCALE)
    return starts, edges


def tiled_assembly(model, x, halo, dtype=np.float32):
    """x: (1,3,H,W) float tensor [0,1]. Returns numpy (3, 4H, 4W) float32."""
    _, _, H, W = x.shape
    sx, ex = plan_axis(W, halo)
    sy, ey = plan_axis(H, halo)
    full = np.zeros((C, H * SCALE, W * SCALE), dtype=np.float32)
    for iy in range(len(sy)):
        for ix in range(len(sx)):
            x0, x1 = ex[ix], ex[ix + 1]
            y0, y1 = ey[iy], ey[iy + 1]
            tile = x[:, :, sy[iy]:sy[iy] + TILE, sx[ix]:sx[ix] + TILE]
            with torch.no_grad():
                out = model(tile).squeeze(0).cpu().numpy().astype(np.float32)
            lx0, lx1 = x0 - sx[ix] * SCALE, x1 - sx[ix] * SCALE
            ly0, ly1 = y0 - sy[iy] * SCALE, y1 - sy[iy] * SCALE
            full[:, y0:y1, x0:x1] = out[:, ly0:ly1, lx0:lx1]
    return full


def region_metrics(ab, mask):
    """ab: (3,H,W) |abs diff| array; mask: (H,W) boolean. Aggregates over all channels."""
    sel = ab[:, mask]
    n = sel.size
    mse = float((sel * sel).mean())
    return {
        "mae": float(sel.mean()),
        "rmse": float(np.sqrt(mse)),
        "max_abs": float(sel.max()),
        "exact_0": int((sel == 0).sum()),
        "near_exact_1e-6": int((sel < 1e-6).sum()),
        "count": n,
    }


def mask_for(plan, half):
    """Returns (seam_mask, border_mask) boolean (H,W) masks."""
    # seam: within SEAM_BAND of any internal boundary
    seam = np.zeros((half[0] * SCALE, half[1] * SCALE), dtype=bool)
    border = np.zeros_like(seam)
    sx, ex = plan["sx"], plan["ex"]
    sy, ey = plan["sy"], plan["ey"]
    for e in ex[1:-1]:
        lo = max(0, e - SEAM_BAND); hi = min(seam.shape[1], e + SEAM_BAND)
        seam[:, lo:hi] = True
    for e in ey[1:-1]:
        lo = max(0, e - SEAM_BAND); hi = min(seam.shape[0], e + SEAM_BAND)
        seam[lo:hi, :] = True
    border[:BORDER_BAND, :] = True
    border[-BORDER_BAND:, :] = True
    border[:, :BORDER_BAND] = True
    border[:, -BORDER_BAND:] = True
    return seam, border


def metrics_for_regions(ab, seam, border):
    seam_c = seam & ~border
    interior = ~seam & ~border
    result = {"global": region_metrics(ab, np.ones_like(seam, dtype=bool))}
    if seam_c.any():
        result["seam"] = region_metrics(ab, seam_c)
    else:
        result["seam"] = {"mae": 0.0, "rmse": 0.0, "max_abs": 0.0, "exact_0": 0, "near_exact_1e-6": 0, "count": 0}
    result["non_seam"] = region_metrics(ab, interior)
    result["border"] = region_metrics(ab, border)
    return result


def _write_png_rgb8(path, rgb):
    h, w, _ = rgb.shape
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        raw.extend(rgb[y].tobytes())

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        out += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return out

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--weights-dir", default=None)
    ap.add_argument("--seed", type=int, default=SEED)
    args = ap.parse_args()

    model = build_model()
    if args.weights_dir and os.path.exists(os.path.join(args.weights_dir, "realesr-general-x4v3.pth")):
        d = torch.load(os.path.join(args.weights_dir, "realesr-general-x4v3.pth"), map_location="cpu", weights_only=True)
        key = "params_ema" if "params_ema" in d else "params"
        model.load_state_dict(d[key], strict=True)
        weights_note = "GENERAL checkpoint"
    else:
        init_deterministic(model, args.seed)
        weights_note = f"deterministic seeded ({args.seed}) - architecture geometry proof"

    fixtures = [
        ("smooth_2d_gradient", 188, 188),
        ("rgb_ramps", 257, 191),
        ("edges_crossing", 191, 257),
        ("high_frequency_checker", 200, 180),
        ("deterministic_noise", 301, 227),
        ("mixed_photo_like", 256, 256),
    ]

    heatmap_dir = os.path.join(args.out_dir, "heatmaps")
    os.makedirs(heatmap_dir, exist_ok=True)

    summary = {
        "tile": TILE,
        "scale": SCALE,
        "halos": HALOS,
        "weights": weights_note,
        "seam_band": SEAM_BAND,
        "border_band": BORDER_BAND,
        "per_fixture": {},
    }

    rows = []
    for (name, w, h) in fixtures:
        grid = make_fixture(name, w, h)
        x = torch.from_numpy(np.ascontiguousarray(grid.transpose(2, 0, 1)).astype(np.float32) / 255.0).unsqueeze(0)
        with torch.no_grad():
            full_ref = model(x).squeeze(0).cpu().numpy().astype(np.float32)

        sx_full, ex_full = plan_axis(w, HALOS[0])
        sy_full, ey_full = plan_axis(h, HALOS[0])
        # placeholders replaced per halo below
        per_fixture = {"w": w, "h": h, "halos": {}}
        for halo in HALOS:
            sx, ex = plan_axis(w, halo)
            sy, ey = plan_axis(h, halo)
            tiled = tiled_assembly(model, x, halo)
            diff = full_ref.astype(np.float64) - tiled.astype(np.float64)
            ab = np.abs(diff)
            seam, border = mask_for({"sx": sx, "ex": ex, "sy": sy, "ey": ey}, (h, w))
            m = metrics_for_regions(ab, seam, border)
            m["tiles"] = len(sx) * len(sy)
            m["tiles_x"] = len(sx)
            m["tiles_y"] = len(sy)
            per_fixture["halos"][str(halo)] = m
            rows.append(
                {
                    "fixture": name,
                    "halo": halo,
                    "tiles": m["tiles"],
                    "crop_mae": m["global"]["mae"],
                    "crop_rmse": m["global"]["rmse"],
                    "crop_max": m["global"]["max_abs"],
                    "seam_mae": m["seam"]["mae"],
                    "seam_max": m["seam"]["max_abs"],
                    "non_seam_mae": m["non_seam"]["mae"],
                    "non_seam_max": m["non_seam"]["max_abs"],
                    "border_mae": m["border"]["mae"],
                    "border_max": m["border"]["max_abs"],
                    "exact_0": m["global"]["exact_0"],
                    "near_exact_1e-6": m["global"]["near_exact_1e-6"],
                }
            )
            if halo in (32, 34) and name in ("edges_crossing", "deterministic_noise"):
                vmax = ab.max() if ab.max() > 0 else 1.0
                img = np.clip(ab / vmax * 255.0, 0, 255).astype(np.uint8).transpose(1, 2, 0)
                _write_png_rgb8(os.path.join(heatmap_dir, f"{name}_halo{halo}.png"), img)
        summary["per_fixture"][name] = per_fixture

    with open(os.path.join(args.out_dir, "halo_sweep.json"), "w") as f:
        json.dump(summary, f, indent=2)

    fieldnames = [
        "fixture", "halo", "tiles", "crop_mae", "crop_rmse", "crop_max",
        "seam_mae", "seam_max", "non_seam_mae", "non_seam_max",
        "border_mae", "border_max", "exact_0", "near_exact_1e-6",
    ]
    with open(os.path.join(args.out_dir, "halo_summary.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in rows:
            w.writerow(r)

    # Console: per-halo aggregate MAE (mean over 6 fixtures).
    print(f"\n=== HOST HALO SWEEP ({weights_note}) ===")
    print(f"{'halo':>5} {'tiles':>6} {'global_MAE':>12} {'seam_MAE':>12} {'non-seam_MAE':>12} {'border_MAE':>12} {'MAX':>12}")
    for halo in HALOS:
        halo_rows = [r for r in rows if r["halo"] == halo]
        mt = np.mean([r["crop_mae"] for r in halo_rows])
        ms = np.mean([r["seam_mae"] for r in halo_rows])
        mn = np.mean([r["non_seam_mae"] for r in halo_rows])
        mb = np.mean([r["border_mae"] for r in halo_rows])
        mx = max(r["crop_max"] for r in halo_rows)
        tiles = max(r["tiles"] for r in halo_rows)
        print(f"{halo:>5} {tiles:>6} {mt:>12.6g} {ms:>12.6g} {mn:>12.6g} {mb:>12.6g} {mx:>12.6g}")

    print(f"\nSummary written to {args.out_dir}")


if __name__ == "__main__":
    main()