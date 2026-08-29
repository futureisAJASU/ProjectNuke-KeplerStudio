#!/usr/bin/env python3
"""
N4 comparison: tiled Samsung FP16 NNC vs full-image PyTorch reference.

Consumes:
  - <fixtures-dir>/n4_fixtures_manifest.json       canonical inputs
  - <ref-dir>/reference/full/<name>_raw.f32le      full-image PyTorch reference (RAW FP32)
  - <device-dir>/assembled_<name>.f32le             tiled NNC output (raw FP32)
  - <device-dir>/tiles/<name>/tile_<i>_input.f32le  (optional) per-tile inputs for decomposition
  - <device-dir>/tiles/<name>/tile_<i>_output.f32le (optional) per-tile NNC raw outputs

Reports, per fixture:
  GLOBAL / SEAM / NON-SEAM / BORDER raw MAE/RMSE/P95/P99/MAX, seam/non-seam ratio,
  8-bit (canonical postprocess) parity, and — when per-tile data is present — the
  N4.14 error decomposition (compiler drift vs tiling/context drift).

This isolates tile geometry from compiler drift: total NNC-vs-full error is decomposed
into (a) local NNC-vs-PyTorch-per-tile compiler error and (b) PyTorch-tiled-vs-full
tiling/context error.
"""
import argparse
import json
import os
import struct
import sys
import zlib

import numpy as np
import torch

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, THIS_DIR)
sys.path.insert(0, os.path.join(THIS_DIR, "shim"))
sys.path.insert(0, os.path.join(THIS_DIR, "upstream"))

from halo_sweep import (  # noqa: E402
    build_model,
    plan_axis,
    SEAM_BAND,
    BORDER_BAND,
)
from checkpoint_identity import (  # noqa: E402
    GENERAL_CHECKPOINT_SHA256,
    GENERAL_CHECKPOINT_FILENAME,
)
from n4_preflight import (  # noqa: E402
    HALO,
    C,
    TILE,
    SCALE,
    DECOMPOSITION_FIXTURES,
    load_reference_identity,
    load_full_reference_f32le,
    require_assembled_device_output,
    require_decomposition_artifacts,
    require_canonical_corpus,
)


def load_f32le(path):
    return np.fromfile(path, dtype=np.float32)


def seam_border_masks(w, h):
    sx, ex = plan_axis(w, HALO)
    sy, ey = plan_axis(h, HALO)
    seam = np.zeros((h * SCALE, w * SCALE), dtype=bool)
    border = np.zeros_like(seam)
    for e in ex[1:-1]:
        seam[:, max(0, e - SEAM_BAND):min(seam.shape[1], e + SEAM_BAND)] = True
    for e in ey[1:-1]:
        seam[max(0, e - SEAM_BAND):min(seam.shape[0], e + SEAM_BAND), :] = True
    border[:BORDER_BAND, :] = True
    border[-BORDER_BAND:, :] = True
    border[:, :BORDER_BAND] = True
    border[:, -BORDER_BAND:] = True
    return seam, border


def region_metrics(ab, mask):
    sel = ab[:, mask]
    n = sel.size
    mse = float((sel * sel).mean())
    return {
        "count": int(n),
        "mae": float(sel.mean()),
        "rmse": float(np.sqrt(mse)),
        "p95": float(np.percentile(sel, 95)),
        "p99": float(np.percentile(sel, 99)),
        "max": float(sel.max()),
    }


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


def write_diff_image(path, diff, vmax=None):
    if vmax is None or vmax <= 0:
        vmax = diff.max() if diff.max() > 0 else 1.0
    img = np.clip(diff / vmax * 255.0, 0, 255).astype(np.uint8).transpose(1, 2, 0)
    _write_png_rgb8(path, img)


def load_general_model(weights_dir):
    """Load the pinned GENERAL checkpoint, hashing it against the accepted SHA first."""
    if not weights_dir:
        raise SystemExit(
            "N4 device comparison requires --weights-dir pointing at the pinned GENERAL "
            "checkpoint; a deterministic seeded fallback is not permitted for device metrics"
        )
    ckpt_path = os.path.join(weights_dir, GENERAL_CHECKPOINT_FILENAME)
    if not os.path.exists(ckpt_path):
        raise SystemExit(
            f"--weights-dir {weights_dir!r} does not contain {GENERAL_CHECKPOINT_FILENAME}"
        )
    import hashlib
    h = hashlib.sha256()
    with open(ckpt_path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    actual = h.hexdigest()
    if actual != GENERAL_CHECKPOINT_SHA256:
        raise SystemExit(
            f"checkpoint identity mismatch: {GENERAL_CHECKPOINT_FILENAME} hashed to "
            f"{actual}, expected {GENERAL_CHECKPOINT_SHA256}; refusing to compare against "
            f"a non-pinned GENERAL checkpoint"
        )
    d = torch.load(ckpt_path, map_location="cpu", weights_only=True)
    key = "params_ema" if "params_ema" in d else "params"
    model = build_model()
    model.load_state_dict(d[key], strict=True)
    return model


def decompose(model, device_dir, name, w, h, full, ref_fixtures):
    """Returns compiler_drift_mae / tiling_drift_mae over validated tiles."""
    tiles_dir = os.path.join(device_dir, "tiles", name)
    if name in DECOMPOSITION_FIXTURES:
        # Designated fixtures MUST have complete, validated decomposition.
        require_decomposition_artifacts(device_dir, name, w, h)
    elif not os.path.isdir(tiles_dir):
        # Non-designated fixtures: optional decomposition (return empty)
        return {}

    input_files = sorted(f for f in os.listdir(tiles_dir) if f.endswith("_input.f32le"))
    if not input_files:
        return {}

    compiler = []
    tiling = []
    sx, ex = plan_axis(w, HALO)
    sy, ey = plan_axis(h, HALO)

    for inp in input_files:
        idx = inp[:-len("_input.f32le")]
        out_f = os.path.join(tiles_dir, f"{idx}_output.f32le")
        if not os.path.exists(out_f):
            # Should not happen for designated (already validated), but be strict anyway
            if name in DECOMPOSITION_FIXTURES:
                raise SystemExit(
                    f"decomposition fixture {name!r}: missing output for {idx} at {out_f}"
                )
            continue
        raw = open(os.path.join(tiles_dir, inp), "rb").read()
        arr = np.frombuffer(raw, dtype=np.float32).reshape(C, TILE, TILE).copy()
        x = torch.from_numpy(np.ascontiguousarray(arr)).unsqueeze(0)
        with torch.no_grad():
            pt_tile = model(x).squeeze(0).cpu().numpy().astype(np.float32)
        nnc_tile = load_f32le(out_f).reshape(C, TILE * SCALE, TILE * SCALE)

        ti = int(idx.split("_")[1])
        iy, ix = divmod(ti, len(sx))
        ox0, ox1 = ex[ix], ex[ix + 1]
        oy0, oy1 = ey[iy], ey[iy + 1]
        lx0, lx1 = ox0 - sx[ix] * SCALE, ox1 - sx[ix] * SCALE
        ly0, ly1 = oy0 - sy[iy] * SCALE, oy1 - sy[iy] * SCALE

        core_pt = pt_tile[:, ly0:ly1, lx0:lx1]
        core_nnc = nnc_tile[:, ly0:ly1, lx0:lx1]
        full_core = full[:, oy0:oy1, ox0:ox1]
        compiler.append(np.abs(core_pt.astype(np.float64) - core_nnc.astype(np.float64)).mean())
        tiling.append(np.abs(core_pt.astype(np.float64) - full_core.astype(np.float64)).mean())

    if name in DECOMPOSITION_FIXTURES:
        expected = DECOMPOSITION_FIXTURES[name]
        if len(compiler) != expected:
            raise SystemExit(
                f"decomposition fixture {name!r}: decomposed {len(compiler)} tiles "
                f"but expected {expected}"
            )

    return {
        "tiles_decomposed": len(compiler),
        "compiler_drift_mae": float(np.mean(compiler)) if compiler else None,
        "tiling_drift_mae": float(np.mean(tiling)) if tiling else None,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures-dir", required=True)
    ap.add_argument("--ref-dir", required=True)
    ap.add_argument("--device-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--weights-dir", default=None)
    args = ap.parse_args()

    os.makedirs(args.out_dir, exist_ok=True)

    ref_manifest = load_reference_identity(args.ref_dir)
    model = load_general_model(args.weights_dir)
    weights_note = "GENERAL checkpoint"

    manifest = json.load(open(os.path.join(args.fixtures_dir, "n4_fixtures_manifest.json")))
    require_canonical_corpus(manifest)

    summary = {
        "halo": HALO,
        "weights": weights_note,
        "reference_weights_identity": ref_manifest.get("weights_identity"),
        "reference_checkpoint_sha256": ref_manifest.get("general_checkpoint_sha256")
        or ref_manifest.get("checkpoint_sha256"),
        "fixtures": {},
    }
    rows = []

    compared_count = 0

    for f in manifest["fixtures"]:
        name = f["name"]
        w, h = f["width"], f["height"]
        # Load full reference .f32le and validate SHA/size/shape (fails closed)
        ref_path = os.path.join(args.ref_dir, "reference", "full", f"{name}_raw.f32le")
        expected_sha = ref_manifest["fixtures"][name]["full_raw_sha256"]
        full = load_full_reference_f32le(ref_path, expected_sha, name, w, h)

        require_assembled_device_output(args.device_dir, name, w, h)
        device_path = os.path.join(args.device_dir, f"assembled_{name}.f32le")
        nnc = load_f32le(device_path).reshape(C, h * SCALE, w * SCALE)

        diff = full.astype(np.float64) - nnc.astype(np.float64)
        ab = np.abs(diff)
        seam, border = seam_border_masks(w, h)
        m = {
            "global": region_metrics(ab, np.ones_like(seam, dtype=bool)),
            "seam": region_metrics(ab, seam & ~border) if (seam & ~border).any() else {"count": 0},
            "non_seam": region_metrics(ab, ~seam & ~border),
            "border": region_metrics(ab, border),
        }
        if m["seam"]["count"] and m["non_seam"]["mae"]:
            m["seam_vs_non_seam"] = m["seam"]["mae"] / m["non_seam"]["mae"]
        else:
            m["seam_vs_non_seam"] = None

        # 8-bit canonical postprocess parity + seam difference image
        r8 = np.rint(np.clip(full, 0, 1) * 255.0).astype(np.uint8)
        n8 = np.rint(np.clip(nnc, 0, 1) * 255.0).astype(np.uint8)
        d8 = r8.astype(np.int32) - n8.astype(np.int32)
        m["image_8bit"] = {
            "mae_levels": float(np.abs(d8.astype(np.float64)).mean()),
            "differing_fraction": float((d8 != 0).mean()),
            "max_levels": int(np.abs(d8).max()),
        }
        heatmap_dir = os.path.join(args.out_dir, "heatmaps")
        os.makedirs(heatmap_dir, exist_ok=True)
        write_diff_image(os.path.join(heatmap_dir, f"{name}_raw_abs.png"), ab)
        write_diff_image(os.path.join(heatmap_dir, f"{name}_8bit_abs.png"), np.abs(d8.astype(np.float64)))

        # N4.14 decomposition (fail-closed for designated fixtures)
        decomp = decompose(model, args.device_dir, name, w, h, full, ref_manifest["fixtures"])
        m["decomposition"] = decomp

        summary["fixtures"][name] = m
        rows.append(
            {
                "fixture": name,
                "global_mae": m["global"]["mae"],
                "global_rmse": m["global"]["rmse"],
                "global_max": m["global"]["max"],
                "seam_mae": m["seam"]["mae"] if m["seam"]["count"] else None,
                "non_seam_mae": m["non_seam"]["mae"],
                "border_mae": m["border"]["mae"],
                "seam_non_seam": m["seam_vs_non_seam"],
                "img_mae_levels": m["image_8bit"]["mae_levels"],
                "img_differ_frac": m["image_8bit"]["differing_fraction"],
                "compiler_drift_mae": decomp.get("compiler_drift_mae"),
                "tiling_drift_mae": decomp.get("tiling_drift_mae"),
            }
        )
        print(f"{name}: global_MAE {m['global']['mae']:.6g} seam_MAE "
              f"{m['seam']['mae'] if m['seam']['count'] else float('nan'):.6g} "
              f"non-seam_MAE {m['non_seam']['mae']:.6g} seam/non-seam {m['seam_vs_non_seam']}")
        compared_count += 1

    # Final assertion: must compare exactly 25 fixtures
    if compared_count != 25:
        raise SystemExit(
            f"N4 comparison incomplete: compared {compared_count} fixtures, expected 25"
        )

    with open(os.path.join(args.out_dir, "n4_comparison.json"), "w") as fp:
        json.dump(summary, fp, indent=2)
    import csv
    with open(os.path.join(args.out_dir, "n4_comparison.csv"), "w", newline="") as fp:
        if rows:
            wr = csv.DictWriter(fp, fieldnames=list(rows[0].keys()))
            wr.writeheader()
            for r in rows:
                wr.writerow(r)
    print(f"\ncomparison -> {args.out_dir}")


if __name__ == "__main__":
    main()