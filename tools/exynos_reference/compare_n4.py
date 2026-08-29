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
    WEIGHTS_IDENTITY_GENERAL,
)

HALO = 34
C = 3
TILE = 128
SCALE = 4

# Designated decomposition fixtures with expected tile counts.
DECOMPOSITION_FIXTURES = {
    "seam_stress_188x188": 4,
    "smooth_257x257": 16,
}

# Expected byte sizes
INPUT_TILE_BYTES = C * TILE * TILE * 4      # 3*128*128*4 = 196608
OUTPUT_TILE_BYTES = C * (TILE * SCALE) * (TILE * SCALE) * 4  # 3*512*512*4 = 3145728


def load_f32le(path):
    return np.fromfile(path, dtype=np.float32)


def load_ref_f32le(path, expected_sha256, fixture_name):
    """Load full reference .f32le and validate its SHA-256."""
    import hashlib
    with open(path, "rb") as f:
        data = f.read()
    actual = hashlib.sha256(data).hexdigest()
    if actual != expected_sha256:
        raise SystemExit(
            f"reference tensor SHA mismatch for {fixture_name}: "
            f"manifest={expected_sha256} actual={actual} path={path}"
        )
    return np.frombuffer(data, dtype=np.float32)


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


def load_reference_identity(ref_dir):
    """Read and validate the N4 reference manifest, failing closed.

    The real S24 device comparison may only run against the exact N3-accepted GENERAL
    checkpoint reference. A reference manifest whose stored tensors were produced with
    deterministic seeded weights (geometry-only) is NOT a valid reference for this
    comparison and must be refused, never silently mixed.

    Validation:
      - weights_identity == GENERAL
      - general_checkpoint_sha256 == pinned GENERAL SHA
      - checkpoint_sha256 == pinned GENERAL SHA AND non-null
      - Every fixture in manifest has full_raw_sha256 recorded (cryptographic binding)
    """
    manifest_path = os.path.join(ref_dir, "n4_reference_manifest.json")
    if not os.path.exists(manifest_path):
        raise SystemExit(
            f"reference manifest not found at {manifest_path}; run n4_reference.py "
            f"--weights-dir <dir> to generate a GENERAL reference first"
        )
    manifest = json.load(open(manifest_path))

    identity = manifest.get("weights_identity")
    if identity != WEIGHTS_IDENTITY_GENERAL:
        raise SystemExit(
            f"refusing N4 device comparison: reference manifest weights_identity is "
            f"{identity!r}, not {WEIGHTS_IDENTITY_GENERAL!r}; the stored full/tiled "
            f"tensors are unsuitable as a device reference. Regenerate with "
            f"n4_reference.py --weights-dir <dir> using the pinned GENERAL checkpoint."
        )

    general_sha = manifest.get("general_checkpoint_sha256")
    if general_sha != GENERAL_CHECKPOINT_SHA256:
        raise SystemExit(
            f"refusing N4 device comparison: reference manifest general_checkpoint_sha256 "
            f"{general_sha!r} does not match the pinned GENERAL checkpoint "
            f"{GENERAL_CHECKPOINT_SHA256!r}"
        )

    checkpoint_sha = manifest.get("checkpoint_sha256")
    if checkpoint_sha is None:
        raise SystemExit(
            f"refusing N4 device comparison: reference manifest checkpoint_sha256 is null; "
            f"must be the pinned GENERAL checkpoint SHA {GENERAL_CHECKPOINT_SHA256!r}"
        )
    if checkpoint_sha != GENERAL_CHECKPOINT_SHA256:
        raise SystemExit(
            f"refusing N4 device comparison: reference manifest checkpoint_sha256 "
            f"{checkpoint_sha!r} does not match the pinned GENERAL checkpoint "
            f"{GENERAL_CHECKPOINT_SHA256!r}"
        )

    # Ensure every fixture has a recorded full_raw_sha256 (cryptographic binding)
    fixtures = manifest.get("fixtures", {})
    for name, info in fixtures.items():
        if "full_raw_sha256" not in info:
            raise SystemExit(
                f"refusing N4 device comparison: fixture {name!r} missing full_raw_sha256 "
                f"in manifest; reference tensors not cryptographically bound"
            )

    return manifest


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


def sha256_file(path):
    import hashlib
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def expected_tile_indices(w, h):
    """Compute the TilePlanner-equivalent tile indices for a fixture.
    Returns (num_tiles_x, num_tiles_y, total_tiles).
    """
    sx, ex = plan_axis(w, HALO)
    sy, ey = plan_axis(h, HALO)
    return len(sx), len(sy), len(sx) * len(sy)


def validate_decomposition_fixture(device_dir, name, expected_tiles, w, h):
    """Validate all decomposition artifacts for a designated fixture.
    Fails with detailed fixture name + tile index + reason.
    """
    tiles_dir = os.path.join(device_dir, "tiles", name)
    if not os.path.isdir(tiles_dir):
        raise SystemExit(
            f"decomposition fixture {name!r}: tiles directory missing at {tiles_dir}"
        )

    files = os.listdir(tiles_dir)
    input_files = sorted(f for f in files if f.endswith("_input.f32le"))
    output_files = sorted(f for f in files if f.endswith("_output.f32le"))

    if len(input_files) != expected_tiles:
        raise SystemExit(
            f"decomposition fixture {name!r}: expected {expected_tiles} input tiles, "
            f"found {len(input_files)}: {input_files}"
        )
    if len(output_files) != expected_tiles:
        raise SystemExit(
            f"decomposition fixture {name!r}: expected {expected_tiles} output tiles, "
            f"found {len(output_files)}: {output_files}"
        )

    # Validate tile indices are unique, sequential 0..expected_tiles-1
    # and correspond to TilePlanner-equivalent plan
    indices = []
    for f in input_files:
        # file format: "tile_<i>_input.f32le"
        try:
            idx = int(f[len("tile_"):-len("_input.f32le")])
        except ValueError:
            raise SystemExit(
                f"decomposition fixture {name!r}: invalid input tile filename {f!r}"
            )
        indices.append(idx)

    expected_indices = set(range(expected_tiles))
    actual_indices = set(indices)
    if actual_indices != expected_indices:
        raise SystemExit(
            f"decomposition fixture {name!r}: tile indices {sorted(actual_indices)} "
            f"do not match expected {sorted(expected_indices)}"
        )
    if len(indices) != len(actual_indices):
        raise SystemExit(
            f"decomposition fixture {name!r}: duplicate tile indices found: {indices}"
        )

    # Validate byte sizes
    for idx in range(expected_tiles):
        inp_path = os.path.join(tiles_dir, f"tile_{idx}_input.f32le")
        out_path = os.path.join(tiles_dir, f"tile_{idx}_output.f32le")
        if not os.path.exists(inp_path):
            raise SystemExit(
                f"decomposition fixture {name!r}: missing input tile {idx} at {inp_path}"
            )
        if not os.path.exists(out_path):
            raise SystemExit(
                f"decomposition fixture {name!r}: missing output tile {idx} at {out_path}"
            )
        inp_size = os.path.getsize(inp_path)
        out_size = os.path.getsize(out_path)
        if inp_size != INPUT_TILE_BYTES:
            raise SystemExit(
                f"decomposition fixture {name!r} tile {idx}: input size {inp_size} "
                f"!= expected {INPUT_TILE_BYTES} bytes"
            )
        if out_size != OUTPUT_TILE_BYTES:
            raise SystemExit(
                f"decomposition fixture {name!r} tile {idx}: output size {out_size} "
                f"!= expected {OUTPUT_TILE_BYTES} bytes"
            )

    return tiles_dir


def decompose(model, device_dir, name, w, h, full, ref_fixtures):
    """Returns compiler_drift_mae / tiling_drift_mae over validated tiles."""
    tiles_dir = os.path.join(device_dir, "tiles", name)
    if not os.path.isdir(tiles_dir):
        # Non-designated fixtures: optional decomposition (return empty)
        if name in DECOMPOSITION_FIXTURES:
            raise SystemExit(
                f"designated decomposition fixture {name!r}: tiles directory missing at {tiles_dir}"
            )
        return {}

    # Designated fixtures MUST have complete decomposition
    if name in DECOMPOSITION_FIXTURES:
        expected = DECOMPOSITION_FIXTURES[name]
        tiles_dir = validate_decomposition_fixture(device_dir, name, expected, w, h)
    else:
        # Non-designated: if dir exists but incomplete, still return {} (optional)
        pass

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
    expected_fixture_count = len(manifest["fixtures"])
    if expected_fixture_count != 25:
        raise SystemExit(
            f"canonical fixtures manifest has {expected_fixture_count} fixtures, expected 25"
        )

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
        # Load full reference .f32le and validate SHA
        ref_path = os.path.join(args.ref_dir, "reference", "full", f"{name}_raw.f32le")
        if not os.path.exists(ref_path):
            raise SystemExit(
                f"reference tensor not found for {name!r} at {ref_path}"
            )
        expected_sha = ref_manifest["fixtures"][name]["full_raw_sha256"]
        full = load_ref_f32le(ref_path, expected_sha, name)
        assert full.shape == (C, h * SCALE, w * SCALE), full.shape

        device_path = os.path.join(args.device_dir, f"assembled_{name}.f32le")
        if not os.path.exists(device_path):
            raise SystemExit(
                f"FAIL: assembled device output missing for fixture {name!r} at {device_path}"
            )
        device_size = os.path.getsize(device_path)
        expected_size = C * h * SCALE * w * SCALE * 4
        if device_size != expected_size:
            raise SystemExit(
                f"FAIL: assembled device output for {name!r} has size {device_size} "
                f"bytes, expected {expected_size}"
            )
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