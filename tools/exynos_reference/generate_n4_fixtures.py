#!/usr/bin/env python3
"""
N4 canonical multi-tile fixture generator.

Produces deterministic RGB uint8 grids at multiple sizes (all >= 128, planner-sensitive
odd/aligned dimensions) and multiple content classes, serialized to the same canonical
FP32 CHW planar byte layout as N3 (CHW, C-order, {uint8/255.0f} little-endian). These
bytes are committed under app/src/androidTest/assets/exynos_n4/ and are the EXACT inputs
fed to both the host PyTorch reference (n4_reference.py) and the Samsung FP16 NNC tiled
engine (ExynosN4TilingInstrumentationTest).

Content classes:
  smooth          2-D cosine gradient (local, low frequency)
  seam_stress     hard edges/impulses at many positions, several on/near tile boundaries
  high_frequency  fine checker/detail
  noise           deterministic uint8 noise (seed-pinned)
  mixed           gradient + noise + edges (photo-like)

Usage:
  python generate_n4_fixtures.py [output_dir]

Default output_dir = app/src/androidTest/assets/exynos_n4
"""
import hashlib
import json
import os
import struct
import sys
import zlib

import numpy as np

SEED = 20260828
C = 3

# (width, height) — >= 128, planner-sensitive.
DIMENSIONS = [
    (188, 188),
    (257, 191),
    (191, 257),
    (257, 257),
    (301, 227),
]


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


def _grid_to_chw_bytes(grid):
    f = np.asarray(grid, dtype=np.float32) / np.float32(255.0)
    chw = np.transpose(f, (2, 0, 1))  # (3,H,W)
    return chw.astype(np.float32).tobytes(order="C")


def _smooth(w, h):
    xs = np.arange(w, dtype=np.float64)
    ys = np.arange(h, dtype=np.float64)
    xg, yg = np.meshgrid(xs, ys)
    r = np.clip(127.5 + 127.5 * np.cos(2 * np.pi * xg / 64.0), 0, 255)
    g = np.clip(127.5 + 127.5 * np.cos(2 * np.pi * yg / 64.0), 0, 255)
    b = np.clip(127.5 + 127.5 * np.cos(2 * np.pi * (xg + yg) / 128.0), 0, 255)
    return np.stack([r, g, b], axis=-1).astype(np.uint8)


def _seam_stress(w, h):
    r = np.zeros((h, w), dtype=np.float64)
    g = np.zeros((h, w), dtype=np.float64)
    b = np.zeros((h, w), dtype=np.float64)
    # vertical + horizontal hard edges at many positions (several on/near 0 and 128 grid)
    for x in (0, 63, 64, 127, 128, 129, 160, w - 1):
        if 0 <= x < w:
            r[:, x] = 255
    for y in (0, 63, 64, 127, 128, 129, 160, h - 1):
        if 0 <= y < h:
            g[y, :] = 255
    # crossing diagonals + isolated impulses
    for i in range(min(w, h)):
        b[i, i % w] = 255
    for (px, py) in [(0, 0), (w - 1, h - 1), (w - 1, 0), (0, h - 1), (w // 2, h // 2)]:
        g[py, px] = 255
        r[py, px] = 255
    return np.stack([r, g, b], axis=-1).astype(np.uint8)


def _high_frequency(w, h):
    xg, yg = np.meshgrid(np.arange(w), np.arange(h))
    r = np.where(((xg // 2) + (yg // 2)) % 2 == 0, 255, 0)
    g = np.where(((xg // 4) + (yg // 4)) % 2 == 0, 220, 32)
    b = np.where(((xg // 8) + (yg // 8)) % 2 == 0, 32, 220)
    return np.stack([r, g, b], axis=-1).astype(np.uint8)


def _noise(w, h):
    rng = np.random.RandomState(SEED)
    return rng.randint(0, 256, size=(h, w, 3), dtype=np.uint8)


def _mixed(w, h):
    xg, yg = np.meshgrid(np.arange(w), np.arange(h))
    rng = np.random.RandomState(SEED + 1)
    base = 100.0 + 80.0 * np.sin(2 * np.pi * xg / 90.0) * np.cos(2 * np.pi * yg / 70.0)
    n = rng.normal(0, 12, size=(h, w))
    r = np.clip(base + n, 0, 255)
    g = np.clip(200 - base + n[:, ::-1], 0, 255)
    b = np.clip(base * 0.5 + 80 + n, 0, 255)
    r[:, w // 2] = 255
    g[h // 2, :] = 255
    b[:, 0] = 255
    b[:, -1] = 255
    return np.stack([r, g, b], axis=-1).astype(np.uint8)


BUILDERS = {
    "smooth": _smooth,
    "seam_stress": _seam_stress,
    "high_frequency": _high_frequency,
    "noise": _noise,
    "mixed": _mixed,
}


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def main():
    out_dir = (
        sys.argv[1]
        if len(sys.argv) > 1
        else os.path.join(
            os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
            "app", "src", "androidTest", "assets", "exynos_n4",
        )
    )
    os.makedirs(out_dir, exist_ok=True)

    manifest = {
        "seed": SEED,
        "layout": "CHW planar float32 little-endian (R plane, G plane, B plane) = uint8/255.0f",
        "tile": 128,
        "scale": 4,
        "halo": 34,
        "fixtures": [],
    }

    for (w, h) in DIMENSIONS:
        for content, builder in BUILDERS.items():
            name = f"{content}_{w}x{h}"
            grid = builder(w, h)
            assert grid.shape == (h, w, 3)
            raw = _grid_to_chw_bytes(grid)
            png_path = os.path.join(out_dir, f"{name}.png")
            raw_path = os.path.join(out_dir, f"{name}_input.f32le")
            _write_png_rgb8(png_path, grid)
            with open(raw_path, "wb") as f:
                f.write(raw)
            entry = {
                "name": name,
                "content": content,
                "width": w,
                "height": h,
                "raw_input_bytes": len(raw),
                "raw_input_sha256": sha256_bytes(raw),
                "png_sha256": sha256_bytes(open(png_path, "rb").read()),
            }
            manifest["fixtures"].append(entry)
            print(f"{name}: {w}x{h} raw {len(raw)} bytes sha256={entry['raw_input_sha256']}")

    manifest_path = os.path.join(out_dir, "n4_fixtures_manifest.json")
    with open(manifest_path, "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"manifest -> {manifest_path}")


if __name__ == "__main__":
    main()