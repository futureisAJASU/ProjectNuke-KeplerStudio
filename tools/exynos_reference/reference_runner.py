#!/usr/bin/env python3
"""
N3 FP32 PyTorch reference runner over the pinned upstream Real-ESRGAN source.

Candidates (see N3.2):
  A. GENERAL  realesr-general-x4v3.pth      (dni=None)
  B. WDN      realesr-general-wdn-x4v3.pth  (dni=None)
  C. DNI-0.5  weight interpolation of GENERAL and WDN with [0.5, 0.5]
              (exact upstream `dni()` semantics on the `params` key)

The network is loaded VERBATIM from the pinned upstream
`realesrgan/archs/srvgg_arch.py` (SRVGGNetCompact, num_conv=32, upscale=4,
prelu). Forward runs under torch.no_grad() in FP32. The exact serialized
canonical CHW FP32 input bytes (fixtures/*_input.f32le) are fed directly (no
cv2, no file decode), producing raw FP32 RGB output (3,512,512) before clamp.

Also emits, per candidate/fixture:
  <name>_raw.f32le     raw FP32 model output (3,512,512) little-endian
  <name>_rgb8.png      canonical 8-bit RGB = clamp[0,1] -> *255 -> round()
  <name>_raw.npy       float32 array for host-side metric reuse

Optional --half emits a secondary FP16 (internal) execution into
reference_fp16/ only when the environment can run it reliably.

Usage:
  python reference_runner.py --weights-dir DIR --fixtures-dir DIR --out-dir DIR [--half]
"""
import argparse
import hashlib
import json
import os
import sys

import numpy as np
import torch

THIS_DIR = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(THIS_DIR, "shim"))
sys.path.insert(0, os.path.join(THIS_DIR, "upstream"))

from srvgg_arch import SRVGGNetCompact  # noqa: E402

W = 128
H = 128
C = 3
UPSCALE = 4
OW = W * UPSCALE
OH = H * UPSCALE


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def build_model():
    return SRVGGNetCompact(
        num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4, act_type="prelu"
    )


def load_state(path):
    d = torch.load(path, map_location=torch.device("cpu"), weights_only=True)
    keyname = "params_ema" if "params_ema" in d else "params"
    return d[keyname]


def load_candidate(weights_dir):
    """Returns dict of candidate -> (state_dict)."""
    general = load_state(os.path.join(weights_dir, "realesr-general-x4v3.pth"))
    wdn = load_state(os.path.join(weights_dir, "realesr-general-wdn-x4v3.pth"))

    # DNI deep network interpolation (exact upstream dni()):
    #   params[k] = w0 * general[k] + w1 * wdn[k], w = [0.5, 0.5]
    dni = {k: 0.5 * v_a + 0.5 * wdn[k] for k, v_a in general.items()}

    return {
        "GENERAL": general,
        "WDN": wdn,
        "DNI-0.5": dni,
    }


def run_model(model, x, half=False):
    model.eval()
    if half:
        model = model.half()
        x = x.half()
    with torch.no_grad():
        out = model(x)
    return out.float()


def save_raw_npy_png(out_f32, out_dir, name):
    arr = out_f32.squeeze(0).cpu().numpy()  # (3,512,512)
    npy_path = os.path.join(out_dir, f"{name}_raw.npy")
    np.save(npy_path, arr)
    raw_path = os.path.join(out_dir, f"{name}_raw.f32le")
    arr.astype(np.float32).tofile(raw_path)
    # canonical 8-bit RGB = clamp -> *255 -> round (upstream semantics)
    rgb8 = np.clip(arr, 0.0, 1.0) * 255.0
    rgb8 = np.round(rgb8).astype(np.uint8)
    rgb8 = np.transpose(rgb8, (1, 2, 0))
    png = _write_png_rgb8(os.path.join(out_dir, f"{name}_rgb8.png"), rgb8)
    return {
        "raw_sha256": sha256_file(raw_path),
        "raw_bytes": os.path.getsize(raw_path),
        "png_bytes": png,
        "out_min": float(arr.min()),
        "out_max": float(arr.max()),
        "out_mean": float(arr.mean()),
        "nonfinite": int((~np.isfinite(arr)).sum()),
    }


def _write_png_rgb8(path, rgb):
    import struct
    import zlib

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
    return os.path.getsize(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--weights-dir", required=True)
    ap.add_argument("--fixtures-dir", required=True)
    ap.add_argument("--out-dir", required=True)
    ap.add_argument("--half", action="store_true")
    args = ap.parse_args()

    candidates = load_candidate(args.weights_dir)

    fixture_names = sorted(
        f[:-len("_input.f32le")]
        for f in os.listdir(args.fixtures_dir)
        if f.endswith("_input.f32le")
    )

    manifest = {
        "torch": torch.__version__,
        "numpy": np.__version__,
        "upstream_commit": "a4abfb2979a7bbff3f69f58f58ae324608821e27",
        "architecture": "SRVGGNetCompact(num_in_ch=3,num_out_ch=3,num_feat=64,num_conv=32,upscale=4,act_type=prelu)",
        "weapons_load_key": "params (no params_ema present in official checkpoints)",
        "candidates": {},
    }

    for candidate, state in candidates.items():
        model = build_model()
        model.load_state_dict(state, strict=True)
        out_dir = os.path.join(args.out_dir, "reference", candidate)
        os.makedirs(out_dir, exist_ok=True)
        entries = {}
        for name in fixture_names:
            raw_input_bytes = open(os.path.join(args.fixtures_dir, f"{name}_input.f32le"), "rb").read()
            arr = np.frombuffer(raw_input_bytes, dtype=np.float32).reshape(C, H, W)
            x = torch.from_numpy(np.ascontiguousarray(arr)).unsqueeze(0)  # (1,3,128,128)
            out = run_model(model, x, half=False)
            assert out.shape == (1, 3, OH, OW), out.shape
            entries[name] = save_raw_npy_png(out, out_dir, name)

        if args.half:
            fp16_dir = os.path.join(args.out_dir, "reference_fp16", candidate)
            os.makedirs(fp16_dir, exist_ok=True)
            for name in fixture_names:
                raw_input_bytes = open(os.path.join(args.fixtures_dir, f"{name}_input.f32le"), "rb").read()
                arr = np.frombuffer(raw_input_bytes, dtype=np.float32).reshape(C, H, W)
                x = torch.from_numpy(np.ascontiguousarray(arr)).unsqueeze(0)
                out = run_model(model, x, half=True)
                save_raw_npy_png(out, fp16_dir, name)

        manifest["candidates"][candidate] = entries

    with open(os.path.join(args.out_dir, "reference_manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()