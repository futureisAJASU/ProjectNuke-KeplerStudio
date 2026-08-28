#!/usr/bin/env python3
"""
N3 raw numerical comparison: Samsung FP16 NNC output vs PyTorch reference output.

For every fixture x candidate (GENERAL / WDN / DNI-0.5), compares the raw FP32
tensors BEFORE clamp and computes the full metric set required by N3.9/N3.10.

Inputs (produced by generate_fixtures.py / reference_runner.py / device capture):
  fixtures/                      canonical inputs (authoritative input parity)
  reference/<candidate>/<name>_raw.npy   PyTorch FP32 output (3,512,512) RGB
  device_raw/npu_raw_output_<name>_1.f32le  Samsung NNC raw FP32 output

Outputs:
  comparison.csv                 per fixture+candidate metric rows
  summary.json                   structured full results (candidate table)
  diff/<candidate>/<name>.png    amplitude-normalized per-channel abs-diff image
"""
import argparse
import csv
import hashlib
import json
import os
import struct
import zlib

import numpy as np

CANDIDATES = ["GENERAL", "WDN", "DNI-0.5"]
FIXTURES = [
    "n2_original",
    "rgb_channel_ramps",
    "smooth_2d_gradient",
    "edges_impulses",
    "high_frequency",
    "deterministic_noise",
]
H = W = 512
C = 3


def load_f32le(path):
    return np.fromfile(path, dtype=np.float32).reshape(C, H, W)


def load_ref(base, candidate, name):
    return np.load(os.path.join(base, "reference", candidate, f"{name}_raw.npy"))


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def psnr(mse, peak=1.0):
    if mse == 0:
        return float("inf")
    return 10.0 * np.log10(peak * peak / mse)


def correlation(ref, nnc):
    rf = ref.ravel().astype(np.float64)
    nf = nnc.ravel().astype(np.float64)
    rf -= rf.mean()
    nf -= nf.mean()
    denom = np.sqrt((rf * rf).sum() * (nf * nf).sum())
    if denom == 0:
        return float("nan")
    return float((rf * nf).sum() / denom)


def metrics(ref, nnc):
    ref = ref.astype(np.float64)
    nnc = nnc.astype(np.float64)
    diff = ref - nnc
    ab = np.abs(diff)
    n = diff.size
    nonfinite = int((~np.isfinite(nnc)).sum()) + int((~np.isfinite(ref)).sum())
    mse = float((diff * diff).mean())
    out = {
        "element_count": n,
        "nonfinite_count": nonfinite,
        "mean_signed_error": float(diff.mean()),
        "mae": float(ab.mean()),
        "rmse": float(np.sqrt(mse)),
        "max_abs": float(ab.max()),
        "p50_abs": float(np.percentile(ab, 50)),
        "p95_abs": float(np.percentile(ab, 95)),
        "p99_abs": float(np.percentile(ab, 99)),
        "psnr_raw_peak1": psnr(mse, 1.0),
        "correlation": correlation(ref, nnc),
        "ref_min": float(ref.min()),
        "ref_max": float(ref.max()),
        "ref_mean": float(ref.mean()),
        "nnc_min": float(nnc.min()),
        "nnc_max": float(nnc.max()),
        "nnc_mean": float(nnc.mean()),
        "per_channel": {},
    }
    for ch in range(C):
        d = ref[ch] - nnc[ch]
        a = np.abs(d)
        mse_ch = float((d * d).mean())
        out["per_channel"][ch] = {
            "mae": float(a.mean()),
            "rmse": float(np.sqrt(mse_ch)),
            "max_abs": float(a.max()),
        }
    return out


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


def write_diff_image(path, ref, nnc):
    ab = np.abs(ref.astype(np.float64) - nnc.astype(np.float64))
    vmax = ab.max()
    if vmax <= 0:
        vmax = 1.0
    img = np.clip(ab / vmax * 255.0, 0, 255).astype(np.uint8)
    img = np.transpose(img, (1, 2, 0))
    _write_png_rgb8(path, img)


def image_domain(ref, nnc):
    """Canonical 8-bit comparison separating MODEL error from POSTPROCESS difference.

    Upstream canonical = clamp[0,1] -> *255 -> round (half-to-even).
    Kepler production (post-N3.13 fix) = clamp[0,1] -> *255 -> round via Math.rint.
    Kepler legacy (pre-fix) = clamp[0,1] -> *255 -> truncation (toward zero).

    Returns three pairwise stats over the uint8 arrays so the report can cite:
      model_only      = round(ref) vs round(nnc)  (what production now yields)
      legacy_trunc    = round(ref) vs trunc(nnc)  (the discovered pre-fix defect)
      round_vs_trunc  = round(nnc)  vs trunc(nnc) (pure postprocess delta)
    """
    ref_r = np.rint(np.clip(ref, 0, 1) * 255.0).astype(np.int32)
    nnc_r = np.rint(np.clip(nnc, 0, 1) * 255.0).astype(np.int32)
    nnc_t = np.trunc(np.clip(nnc, 0, 1) * 255.0).astype(np.int32)

    def pair(a, b):
        d = a - b
        ab = np.abs(d)
        mse = float((d.astype(np.float64) ** 2).mean())
        return {
            "mae": float(ab.mean()),
            "rmse": float(np.sqrt(mse)),
            "psnr": psnr(mse, 255.0),
            "max_diff": int(ab.max()),
            "differing_fraction": float((d != 0).mean()),
        }

    return {
        "model_only": pair(ref_r, nnc_r),
        "legacy_trunc": pair(ref_r, nnc_t),
        "round_vs_trunc": pair(nnc_r, nnc_t),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", required=True)
    args = ap.parse_args()
    base = args.base

    rows = []
    summary = {"candidates": {}, "image_domain": {}, "input_parity": {}}

    for candidate in CANDIDATES:
        summary["candidates"][candidate] = {"per_fixture": {}, "aggregate": {}}
        for name in FIXTURES:
            ref = load_ref(base, candidate, name)
            nnc = load_f32le(os.path.join(base, "device_raw", f"npu_raw_output_{name}_1.f32le"))
            m = metrics(ref, nnc)
            rows.append({"candidate": candidate, "fixture": name, **m})
            summary["candidates"][candidate]["per_fixture"][name] = m

            diff_dir = os.path.join(base, "diff", candidate)
            os.makedirs(diff_dir, exist_ok=True)
            write_diff_image(os.path.join(diff_dir, f"{name}.png"), ref, nnc)

        # aggregate across fixtures
        names = FIXTURES
        mae = np.mean([summary["candidates"][candidate]["per_fixture"][n]["mae"] for n in names])
        rmse = np.sqrt(np.mean([summary["candidates"][candidate]["per_fixture"][n]["rmse"] ** 2 for n in names]))
        max_abs = max(summary["candidates"][candidate]["per_fixture"][n]["max_abs"] for n in names)
        p95 = float(np.mean([summary["candidates"][candidate]["per_fixture"][n]["p95_abs"] for n in names]))
        p99 = float(np.mean([summary["candidates"][candidate]["per_fixture"][n]["p99_abs"] for n in names]))
        per_channel = []
        for ch in range(C):
            per_channel.append(
                {
                    "mae": float(np.mean([summary["candidates"][candidate]["per_fixture"][n]["per_channel"][ch]["mae"] for n in names])),
                    "rmse": float(np.sqrt(np.mean([summary["candidates"][candidate]["per_fixture"][n]["per_channel"][ch]["rmse"] ** 2 for n in names]))),
                    "max_abs": max(summary["candidates"][candidate]["per_fixture"][n]["per_channel"][ch]["max_abs"] for n in names),
                }
            )
        summary["candidates"][candidate]["aggregate"] = {
            "mae": float(mae),
            "rmse": float(rmse),
            "max_abs": max_abs,
            "p95_abs": p95,
            "p99_abs": p99,
            "per_channel_mae": [p["mae"] for p in per_channel],
            "per_channel_rmse": [p["rmse"] for p in per_channel],
        }

        # image domain (vs GENERAL only for canonical; also record per candidate)
        img = {}
        for name in names:
            ref = load_ref(base, candidate, name)
            nnc = load_f32le(os.path.join(base, "device_raw", f"npu_raw_output_{name}_1.f32le"))
            img[name] = image_domain(ref, nnc)
        summary["image_domain"][candidate] = img

    # input parity evidence: hash each device raw output and record input identity
    for name in FIXTURES:
        r1 = os.path.join(base, "device_raw", f"npu_raw_output_{name}_1.f32le")
        r2 = os.path.join(base, "device_raw", f"npu_raw_output_{name}_2.f32le")
        summary["input_parity"][name] = {
            "npu_raw_output_1_sha256": sha256_bytes(open(r1, "rb").read()),
            "npu_raw_output_2_sha256": sha256_bytes(open(r2, "rb").read()),
            "run1_eq_run2": sha256_bytes(open(r1, "rb").read()) == sha256_bytes(open(r2, "rb").read()),
        }

    # CSV
    fieldnames = [
        "candidate",
        "fixture",
        "mae",
        "rmse",
        "max_abs",
        "p50_abs",
        "p95_abs",
        "p99_abs",
        "mean_signed_error",
        "psnr_raw_peak1",
        "correlation",
        "nonfinite_count",
        "element_count",
        "ref_min",
        "ref_max",
        "nnc_min",
        "nnc_max",
        "ch0_mae",
        "ch0_rmse",
        "ch0_max",
        "ch1_mae",
        "ch1_rmse",
        "ch1_max",
        "ch2_mae",
        "ch2_rmse",
        "ch2_max",
    ]
    with open(os.path.join(base, "comparison.csv"), "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames)
        w.writeheader()
        for r in rows:
            flat = {
                "candidate": r["candidate"],
                "fixture": r["fixture"],
                "mae": r["mae"],
                "rmse": r["rmse"],
                "max_abs": r["max_abs"],
                "p50_abs": r["p50_abs"],
                "p95_abs": r["p95_abs"],
                "p99_abs": r["p99_abs"],
                "mean_signed_error": r["mean_signed_error"],
                "psnr_raw_peak1": r["psnr_raw_peak1"],
                "correlation": r["correlation"],
                "nonfinite_count": r["nonfinite_count"],
                "element_count": r["element_count"],
                "ref_min": r["ref_min"],
                "ref_max": r["ref_max"],
                "nnc_min": r["nnc_min"],
                "nnc_max": r["nnc_max"],
                "ch0_mae": r["per_channel"][0]["mae"],
                "ch0_rmse": r["per_channel"][0]["rmse"],
                "ch0_max": r["per_channel"][0]["max_abs"],
                "ch1_mae": r["per_channel"][1]["mae"],
                "ch1_rmse": r["per_channel"][1]["rmse"],
                "ch1_max": r["per_channel"][1]["max_abs"],
                "ch2_mae": r["per_channel"][2]["mae"],
                "ch2_rmse": r["per_channel"][2]["rmse"],
                "ch2_max": r["per_channel"][2]["max_abs"],
            }
            w.writerow(flat)

    with open(os.path.join(base, "summary.json"), "w") as f:
        json.dump(summary, f, indent=2)

    # console: candidate table
    print("\n=== AGGREGATE CANDIDATE TABLE ===")
    print(f"{'candidate':12} {'MAE':>12} {'RMSE':>12} {'P95':>12} {'P99':>12} {'MAX':>12}")
    for candidate in CANDIDATES:
        a = summary["candidates"][candidate]["aggregate"]
        print(f"{candidate:12} {a['mae']:12.6f} {a['rmse']:12.6f} {a['p95_abs']:12.6f} {a['p99_abs']:12.6f} {a['max_abs']:12.6f}")

    print("\n=== PER-FIXTURE MAE per candidate ===")
    for name in FIXTURES:
        line = f"{name:24}"
        for candidate in CANDIDATES:
            line += f" {summary['candidates'][candidate]['per_fixture'][name]['mae']:10.6f}"
        print(line)

    print("\n=== PER-CHANNEL MAE (aggregate) per candidate ===")
    for candidate in CANDIDATES:
        a = summary["candidates"][candidate]["aggregate"]
        print(f"{candidate:12} R={a['per_channel_mae'][0]:.6f} G={a['per_channel_mae'][1]:.6f} B={a['per_channel_mae'][2]:.6f}")

    print("\nDone.")


if __name__ == "__main__":
    main()