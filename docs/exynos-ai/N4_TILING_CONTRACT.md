# N4 Tiling Contract — Full-Image Tiling / Seam Correctness

Phase: N4 — Full-Image Tiling / Seam Correctness (KeplerStudio)
Document status: durable design note (freezes the mathematical tiling contract)

This note pins the geometry used to run the fixed-input compiled NNC
(`Real-ESRGAN-General-x4v3.nnc`, 128×128 → 512×512, ×4) over arbitrary images larger
than 128×128, without synthesizing fake pixels and without the seam artifacts of naive
blend tiling. It is the input contract for the pure tile planner (N4.2–N4.5) and the
NPU tiled engine (N4.8+).

---

## 1. Fixed compiled inputs

| Field | Value |
|---|---|
| Compiled input (source) | 128×128 LR pixels |
| Compiled output | 512×512 HR pixels |
| Scale | ×4 (integer, aligned) |
| Input tensor | FP32 · RGB · CHW planar · 3×128×128 (196,608 B) |
| Output tensor | FP32 · RGB · CHW planar · 3×512×512 (3,145,728 B) |

The NNC accepts **exactly** 128×128 and returns **exactly** 512×512. There is no
runtime-arbitrary-size branch; the pinned architecture is `SRVGGNetCompact`.

## 2. Pinned architecture and derived receptive field

`SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4,
act_type=prelu)` wires, from `tools/exynos_reference/upstream/srvgg_arch.py`:

1. first Conv3×3 (3→64), pad 1, stride 1
2. PReLU
3. `num_conv = 32` body Conv3×3 (64→64), pad 1, stride 1, each followed by PReLU
4. final Conv3×3 (64→3·4·4 = 48), pad 1, stride 1
5. `PixelShuffle(upscale=4)`
6. residual: `F.interpolate(x, scale=4, mode='nearest')` added to the shuffled output

Total padded stride-1 **3×3 convolutions = 1 + 32 + 1 = 34**.

A single 3×3 (stride 1, pad 1) convolution has receptive-field radius 1. Stacking
stride-1 3×3 convolutions increases the radius by exactly 1 per layer: after `k` such
convolutions the receptive-field radius is `k` LR pixels (diameter `2k+1`). Therefore:

```
receptive_field_radius = 34 LR pixels   (= 136 output pixels at ×4)
receptive_field_span   = 69 LR pixels
```

`PixelShuffle` is a channel→spatial rearrangement that does **not** change the receptive
field in LR space, and the nearest residual uses only the 1-pixel input block already
inside the radius-34 conv field — so neither widens the radius beyond 34.

This value was **independently checked** by perturbing a single LR input pixel and
measuring the affected output span on the host reference: affected offsets were exactly
`-34 … +34` LR pixels (radius 34), matching the analytic per-layer count. `34` is
therefore a derived and test-verified architectural fact, not assumed from the
Real-ESRGAN CLI's unrelated `tile_pad=10` default.

## 3. Chosen halo and usable core

For a tile whose **interior** boundary is artificial (i.e., not a true image edge), the
NNC's implicit zero-padding at that boundary contaminates every output pixel whose
convolution footprint crosses it — a band exactly `receptive_field_radius` LR pixels
wide. To discard all boundary-contaminated output while preserving exact coverage:

| Field | Value |
|---|---|
| halo (internal, per side) | **34 LR pixels** (= 136 output pixels) |
| usable core (both sides interior) | 128 − 2·34 = **60 LR pixels** (= 240 output pixels) |
| maximum interior step | 128 − 2·halo = **60 LR pixels** |

`halo = 34` is the smallest value that makes a retained output independent of the
artificial tile boundary; it is justified by both the derivation above and the
full-image-vs-tiled host sweep (N4.6). It is deliberately **not** reduced for speed.

## 4. Edge policy

For images with **both** dimensions ≥ 128, tiles are aligned to the **real** image
boundary on the outer edges:

```
leftmost  tile source x = 0
rightmost tile source x = width  − 128
top       tile source y = 0
bottom    tile source y = height − 128
```

At a real image boundary, the NNC's zero-padding exactly matches the full-image model's
own boundary padding, so **no halo is discarded on that side** — the outer boundary
pixels remain owned by the tile whose NNC input boundary equals the true image boundary
(N4.15). Only **interior** (inter-tile) boundaries contribute a discarded halo band.

No reflect/wrap padding is used outside the image. Upstream Real-ESRGAN has unrelated
`--tile_pad`/pre-pad options; those are a PyTorch deployment concern, not this NNC
deployment.

## 5. Ownership / split policy (overlap-crop, not blending)

Strategy: **overlap input tiles, discard boundary-contaminated output, assign every
destination pixel to exactly one tile.** No alpha feathering or blending.

- Each axis is partitioned independently; the destination partition is the Cartesian
  product of the two axis partitions, so full-output coverage, no gaps, and no double
  ownership follow directly from the 1-D property (N4.5).
- Adjacent tile **valid regions** (the per-tile uncontaminated interval, halo-trimmed
  only on its interior sides) always overlap or touch. Their ownership boundary is the
  **midpoint** between adjacent valid regions:

```
split_boundary_LR(t, t+1) = (valid_left(t+1) + valid_right(t)) / 2
```

  where `valid_left`/`valid_right` are the halo-trimmed edges of tile `t`/`t+1` (with
  the outer edge of an edge tile left untrimmed). Because both operands are integers,
  the midpoint is either integer or half-LR; ×4 makes it an **integer output
  coordinate** (`4·0.5 = 2`), so no fractional output pixels and no off-by-one gap.
- Tile starts are generated so adjacent starts never exceed the usable-core step
  (60 LR), which is exactly the condition that consecutive valid regions overlap
  (never gap). Irregular final overlaps are handled by the same midpoint split, not by
  assuming uniform crop widths.
- Each retained region of a tile lies entirely inside that tile's valid region, and
  assign each destination pixel once, so a correctly-covered interior needs no seam
  blending. Blending is rejected because it (a) blurs high-frequency output, (b) mixes
  two independently boundary-contaminated estimates, and (c) can mask planner bugs.

## 6. Unsupported sources

`width < 128` or `height < 128` returns an explicit `UnsupportedSourceSize` — the N4
planner does **not** silently invent a correctness policy (no reflect/edge-stretch
upsampling to 128). A later bounded product/fallback phase owns the tiny-image
decision.

## 7. Determinism and order

The plan is a pure function of `(width, height, halo, tile=128, scale=4)`. Tile order
is stable row-major (`iy · tilesX + ix`), first row top. No randomness; deduplicated,
strictly increasing starts. (N4.5 asserts all of this.)