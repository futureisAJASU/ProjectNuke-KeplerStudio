#!/usr/bin/env python3
"""
N4 host reference generation.

For every canonical N4 fixture (committed under app/src/androidTest/assets/exynos_n4/),
run the pinned SRVGGNetCompact architecture TWICE:

  full  - whole-image inference (the ground-truth full-image semantics), and
  tiled - the fixed 128x128 overlap-and-crop assembly at the production halo (34),
          mirroring TilePlanner.kt exactly.

The tiled output must be bit-identical to the full output (receptive-field halo = 34,
already proven geometrically by halo_sweep.py). These references back
compare_n4.py's NNC-vs-PyTorch comparison and the compiler-vs-tiling error
decomposition.

Weights: the pinned checkpoint files are not vendored in-repo. Without --weights-dir the
references use deterministic seeded weights, which is sufficient for the geometry and for
the decomposition scaffolding (compare_n4.py notes the same). Provide --weights-dir to use
the real GENERAL checkpoint when present.

Usage:
  python n4_reference.py --fixtures-dir <exynos_n4 assets> --out-dir <dir> [--weights-dir DIR]
"""
import argparse
import hashlib
import json
import os
import sys

import numpy as np
import torch

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, THIS_DIR)
sys.path.insert(0, os.path.join(THIS_DIR, "shim"))
sys.path.insert(0, os.path.join(THIS_DIR, "upstream"))

from halo_sweep import build_model, init_deterministic, plan_axis, tiled_assembly  # noqa: E402

HALO = 34
C = 3


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fixtures-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--weights-dir", default=None)
    args = ap.parse_args()

    model = build_model()
    if args.weights_dir and os.path.exists(os.path.join(args.weights_dir, "realesr-general-x4v3.pth")):
        d = torch.load(os.path.join(args.weights_dir, "realesr-general-x4v3.pth"), map_location="cpu", weights_only=True)
        key = "params_ema" if "params_ema" in d else "params"
        model.load_state_dict(d[key], strict=True)
        weights_note = "GENERAL checkpoint"
    else:
        init_deterministic(model)
        weights_note = "deterministic seeded (20260828) - geometry only; run with --weights-dir for GENERAL"

    manifest = json.load(open(os.path.join(args.fixtures_dir, "n4_fixtures_manifest.json")))
    ref_manifest = {
        "weights": weights_note,
        "halo": HALO,
        "architecture": "SRVGGNetCompact(num_in_ch=3,num_out_ch=3,num_feat=64,num_conv=32,upscale=4,act_type=prelu)",
        "fixtures": {},
    }

    for f in manifest["fixtures"]:
        name = f["name"]
        w, h = f["width"], f["height"]
        raw = open(os.path.join(args.fixtures_dir, f"{name}_input.f32le"), "rb").read()
        arr = np.frombuffer(raw, dtype=np.float32).reshape(C, h, w).copy()
        x = torch.from_numpy(np.ascontiguousarray(arr)).unsqueeze(0)

        with torch.no_grad():
            full = model(x).squeeze(0).cpu().numpy().astype(np.float32)
        tiled = tiled_assembly(model, x, HALO)
        identical = bool(np.array_equal(full, tiled))
        max_abs = float(np.abs(full.astype(np.float64) - tiled.astype(np.float64)).max())

        full_dir = os.path.join(args.out_dir, "reference", "full")
        tiled_dir = os.path.join(args.out_dir, "reference", "tiled")
        os.makedirs(full_dir, exist_ok=True)
        os.makedirs(tiled_dir, exist_ok=True)
        np.save(os.path.join(full_dir, f"{name}_raw.npy"), full)
        full.astype(np.float32).tofile(os.path.join(full_dir, f"{name}_raw.f32le"))
        np.save(os.path.join(tiled_dir, f"{name}_raw.npy"), tiled)
        tiled.astype(np.float32).tofile(os.path.join(tiled_dir, f"{name}_raw.f32le"))

        ref_manifest["fixtures"][name] = {
            "width": w,
            "height": h,
            "full_raw_sha256": sha256_file(os.path.join(full_dir, f"{name}_raw.f32le")),
            "tiled_raw_sha256": sha256_file(os.path.join(tiled_dir, f"{name}_raw.f32le")),
            "full_eq_tiled": identical,
            "full_vs_tiled_max_abs": max_abs,
        }
        print(f"{name}: full==tiled {identical}  max_abs {max_abs:.3g}")

    with open(os.path.join(args.out_dir, "n4_reference_manifest.json"), "w") as fp:
        json.dump(ref_manifest, fp, indent=2)
    print(f"\nreference manifest -> {os.path.join(args.out_dir, 'n4_reference_manifest.json')}")
    print(f"weights: {weights_note}")


if __name__ == "__main__":
    main()