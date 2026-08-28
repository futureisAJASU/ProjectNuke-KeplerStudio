#!/usr/bin/env python3
"""
N3 deterministic RGB fixture generator (128x128, uint8-representable).

Produces, for every fixture, a canonical RGB grid of uint8 values in [0,255]
whose float32 CHW planar representation is byte-identical to:

    floats[i]            = R_i / 255.0f     (i  = y*128 + x)
    floats[16384 + i]    = G_i / 255.0f
    floats[32768 + i]    = B_i / 255.0f

matching Kepler `ExynosUpscaleSession.preprocess()` and the pinned Samsung
CHW FLOAT32 contract (3x128x128, N=1). The serialized files are the SAME bytes
fed to both the PyTorch reference and the Samsung NNC (H2D).

Outputs per fixture:
  <name>.png                lossless RGB8 PNG (auxiliary provenance)
  <name>_input.f32le        raw FP32 CHW planar, little-endian (authoritative)
  fixtures_manifest.json    hashes + per-channel stats for every fixture

Usage:
  python generate_fixtures.py [output_dir]

Defaults output_dir to the N3 artifacts fixtures directory.
"""
import hashlib
import json
import os
import struct
import sys
import zlib

import numpy as np

W = 128
H = 128
C = 3
SEED = 20260828


def _write_png_rgb8(path, rgb):
    """Minimal dependency-free RGB 8-bit PNG writer (filter type 0 per row)."""
    height, width, _ = rgb.shape
    raw = bytearray()
    for y in range(height):
        raw.append(0)  # filter None
        row = rgb[y]
        raw.extend(row.tobytes())

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        out += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return out

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
        + chunk(b"IEND", b"")
    )
    with open(path, "wb") as f:
        f.write(png)


def _float32(x):
    return np.float32(x)


def _build_float_grid(rgb_uint8):
    """uint8 RGB (H,W,3) -> float32 CHW planar (3,H,W) via C/255.0f."""
    f = np.asarray(rgb_uint8, dtype=np.float32) / _float32(255.0)
    return np.transpose(f, (2, 0, 1))  # (3,H,W)


def _n2_original():
    grid = np.zeros((H, W, 3), dtype=np.uint8)
    half = W // 2
    for y in range(H):
        for x in range(W):
            if x < half and y < half:
                r = x * 255 // (half - 1)
                g = y * 255 // (half - 1)
                b = (x + y) * 255 // (half + half - 2)
            elif x >= half and y < half:
                q = (x // (W // 8)) % 4
                if q == 0:
                    r, g, b = 255, 0, 0
                elif q == 1:
                    r, g, b = 0, 255, 0
                elif q == 2:
                    r, g, b = 0, 0, 255
                else:
                    r, g, b = 255, 255, 255
            elif x < half and y >= half:
                on = ((x // 4) + (y // 4)) % 2 == 0
                v = 240 if on else 16
                r = g = b = v
            else:
                r = g = b = 128
            grid[y, x] = (r, g, b)
    return grid


def _rgb_channel_ramps():
    grid = np.zeros((H, W, 3), dtype=np.uint8)
    for y in range(H):
        for x in range(W):
            grid[y, x] = (x * 255 // (W - 1), y * 255 // (H - 1), (x + y) * 255 // (W - 1 + H - 1))
    return grid


def _smooth_2d_gradient():
    grid = np.zeros((H, W, 3), dtype=np.uint8)
    xs = np.arange(W, dtype=np.float64)
    ys = np.arange(H, dtype=np.float64)
    for y in range(H):
        for x in range(W):
            r = int(round(127.5 + 127.5 * np.cos(2.0 * np.pi * x / 128.0)))
            g = int(round(127.5 + 127.5 * np.cos(2.0 * np.pi * y / 128.0)))
            b = int(round(127.5 + 127.5 * np.cos(2.0 * np.pi * (x + y) / 256.0)))
            grid[y, x] = (r, g, b)
    return grid


def _edges_impulses():
    grid = np.zeros((H, W, 3), dtype=np.uint8)
    # vertical white lines at x=32, x=96
    for y in range(H):
        grid[y, 32] = (255, 255, 255)
        grid[y, 96] = (255, 255, 255)
    # horizontal white lines at y=32, y=96
    for x in range(W):
        grid[32, x] = (255, 255, 255)
        grid[96, x] = (255, 255, 255)
    # isolated impulses
    for (px, py) in [(0, 0), (63, 127), (127, 63), (127, 127), (1, 1)]:
        grid[py, px] = (255, 255, 255)
    return grid


def _high_frequency():
    grid = np.zeros((H, W, 3), dtype=np.uint8)
    half = W // 2
    for y in range(H):
        for x in range(W):
            if x < half and y < half:
                period = 2
            elif x >= half and y < half:
                period = 4
            elif x < half and y >= half:
                period = 8
            else:
                period = 16
            on = ((x // period) + (y // period)) % 2 == 0
            v = 255 if on else 0
            grid[y, x] = (v, v, v)
    return grid


def _deterministic_noise():
    rng = np.random.RandomState(SEED)
    vals = rng.randint(0, 256, size=(H, W, 3), dtype=np.uint8)
    return vals


FIXTURES = [
    ("n2_original", _n2_original),
    ("rgb_channel_ramps", _rgb_channel_ramps),
    ("smooth_2d_gradient", _smooth_2d_gradient),
    ("edges_impulses", _edges_impulses),
    ("high_frequency", _high_frequency),
    ("deterministic_noise", _deterministic_noise),
]


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def channel_stats(arr, name):
    return {
        f"{name}_min": float(np.min(arr)),
        f"{name}_max": float(np.max(arr)),
        f"{name}_mean": float(np.mean(arr)),
    }


def main():
    out_dir = (
        sys.argv[1]
        if len(sys.argv) > 1
        else os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
            "artifacts",
            "exynos-n3-reference-s24-20260828",
            "fixtures",
        )
    )
    os.makedirs(out_dir, exist_ok=True)

    manifest = {
        "width": W,
        "height": H,
        "channels": C,
        "layout": "CHW planar float32 little-endian (R plane, G plane, B plane)",
        "serialization": "C-order tobytes() of (3,H,W) float32 array == uint8/255.0f",
        "seed_used_for_noise": SEED,
        "fixtures": [],
    }

    for name, builder in FIXTURES:
        grid = builder()
        assert grid.shape == (H, W, 3)
        chw = _build_float_grid(grid)

        png_path = os.path.join(out_dir, f"{name}.png")
        raw_path = os.path.join(out_dir, f"{name}_input.f32le")
        _write_png_rgb8(png_path, grid)
        raw_bytes = chw.astype(np.float32).tobytes(order="C")
        with open(raw_path, "wb") as f:
            f.write(raw_bytes)

        entry = {
            "name": name,
            "png_bytes": os.path.getsize(png_path),
            "png_sha256": sha256_bytes(open(png_path, "rb").read()),
            "raw_input_bytes": len(raw_bytes),
            "raw_input_sha256": sha256_bytes(raw_bytes),
        }
        for ch, chn in [(0, "r"), (1, "g"), (2, "b")]:
            entry.update(channel_stats(chw[ch], chn))
        manifest["fixtures"].append(entry)
        print(f"{name}: raw {len(raw_bytes)} bytes sha256={entry['raw_input_sha256']}")

    manifest_path = os.path.join(out_dir, "fixtures_manifest.json")
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"manifest -> {manifest_path}")


if __name__ == "__main__":
    main()