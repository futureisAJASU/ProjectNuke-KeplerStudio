# N3 Reference Contract — Upstream Real-ESRGAN provenance

Phase: N3 — FP16 NNC Reference Correctness Validation (KeplerStudio)
Document status: accepted N3 input contract

This document pins the exact upstream reference used to judge whether the Samsung
Exynos-2400 FP16 NNC implements the expected Real-ESRGAN model. It freezes provenance so
no later phase silently drifts to "whatever HEAD is today".

---

## 1. Upstream repository

| Field | Value |
|---|---|
| Repository | `xinntao/Real-ESRGAN` |
| Remote | `https://github.com/xinntao/Real-ESRGAN.git` |
| Pinned commit | `a4abfb2979a7bbff3f69f58f58ae324608821e27` |
| License | BSD-3-Clause |

The commit is the audited upstream hash specified by N3.1 (preferred known audited
commit). The network architecture source used for the Python reference is
`realesrgan/archs/srvgg_arch.py` at this commit, imported **verbatim** into
`tools/exynos_reference/upstream/srvgg_arch.py` (with only the `basicsr.utils.registry`
import stubbed — see `tools/exynos_reference/shim/`). The registry decorator is not part
of the forward computation.

## 2. Reference architecture

The official implementation defines `realesr-general-x4v3` as:

```
SRVGGNetCompact(
    num_in_ch  = 3,
    num_out_ch = 3,
    num_feat   = 64,
    num_conv   = 32,
    upscale    = 4,
    act_type   = 'prelu',
)
```

Wiring (from `srvgg_arch.py`): 1(n)/Conv3x3 + PReLU, followed by 32 × (Conv3x3 64→64 +
PReLU), then the last Conv3x3 64→(3·4·4) and a `PixelShuffle(4)`, plus a `nearest`
residual of the input added back.

## 3. Checkpoint provenance (official release distribution only)

Both weights were acquired from the official GitHub release attached to the pinned
repository, tag `v0.2.5.0`:

| Field | GENERAL | WDN |
|---|---|---|
| Source | `https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth` | `https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-wdn-x4v3.pth` |
| Exact filename | `realesr-general-x4v3.pth` | `realesr-general-wdn-x4v3.pth` |
| Bytes | 4,885,111 | 4,885,111 |
| SHA-256 | `8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292` | `1641f8c4464b9f097c9fdda5589273713f67cf59f3d909e0bd688f0cee269dca` |
| Top-level keys | `['params']` | `['params']` |
| State-dict key selected by official loader | `params` | `params` |
| Parameter tensors | 101 | 101 |

The official loader (`inference_realesrgan.py` / `realesrgan/utils.py`) prefers
`params_ema` only if present, otherwise `params`. Neither official checkpoint carries
`params_ema`, so `params` is selected for both. No `params_ema` branch is exercised.

## 4. DNI (Deep Network Interpolation) — exact semantics

The official CLI default `denoise_strength = 0.5` for `realesr-general-x4v3` builds a
weight-interpolated model. The loader orders `model_path = [GENERAL, WDN]` and sets
`dni_weight = [denoise_strength, 1 - denoise_strength]`, then computes, per `params` key:

```
params[k] = dni_weight[0] * GENERAL_params[k] + dni_weight[1] * WDN_params[k]
```

So the three materially distinct reference candidates are:

| Candidate | Construction |
|---|---|
| A. GENERAL | `general` `params` loaded directly (`denoise_strength=1.0` semantics) |
| B. WDN | `wdn` `params` loaded directly |
| C. DNI-0.5 | `0.5·GENERAL + 0.5·WDN` elementwise on `params` |

N3.2 requires that Samsung's model name `Real_ESRGAN_General_x4v3` NOT be assumed to mean
the default 0.5 blend; the identity is determined numerically (see the correctness
report). **Result: Samsung NNC = pure GENERAL (A).**

## 5. Exact input/output contracts

| Field | Input | Output |
|---|---|---|
| dtype | FP32 | FP32 |
| layout | CHW planar (R plane, G plane, B plane) | CHW planar (R plane, G plane, B plane) |
| batch | 1 | 1 |
| shape | 3×128×128 | 3×512×512 |
| range | `uint8 / 255.0` in [0,1], no mean/std | raw FP32 (may exceed [0,1]) |
| raw bytes | 196,608 | 3,145,728 |

The upstream helper reads OpenCV BGR and converts to RGB before inference; N3 bypasses
file decoding entirely — a single canonical FP32 CHW RGB tensor is serialized once and
fed byte-identically to both the PyTorch reference and the Samsung NNC (H2D).

Canonical 8-bit conversion (upstream image semantics): raw tensor → clamp [0,1] → `*255`
→ `np.round` (round half to even) → uint8 RGB.

## 6. Samsung NNC production asset (frozen in N3)

| Field | Value |
|---|---|
| Logical asset | `models/exynos/Real-ESRGAN-General-x4v3.nnc` |
| Bytes | 3,112,960 |
| SHA-256 | `9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12` |
| Header | `ENNC` |
| Embedded compiler target | `--compiler NPU`, `--framework SNC`, `--soc-type Root`, `--chip_version EVT1`, `--schema_version v2` |
| Embedded model name | `real_esrgan_general_x4v3_simplify` |
| NPUC version | `v2.4.11.l` |
| Target | SM-S921N · e1s · S5E9945 · Exynos 2400 |
| On-device meta (N3) | `compiler_npu = v2.4.11.l`, `compiler_nnc = 27` |

This asset is left byte-identical for the duration of N3. `N3_BASELINE_HEAD =
c62731d1e22a2c37bd9ab2fd368bb54687a824b7`.

## 7. Reference environment pinning

| Component | Version |
|---|---|
| Python | 3.13.7 |
| torch | 2.13.0+cpu |
| numpy | 2.3.3 |
| Pillow | (fixture/PNG convenience only; raw comparison is numpy-only) |
| cv2 | **not used** (N3 avoids file-decoder ambiguity) |
| basicsr | **not installed**; only `basicsr.utils.registry` is shimmed |

See `tools/exynos_reference/README.md` for the exact reproduction steps and the two
runtime scripts (`generate_fixtures.py`, `reference_runner.py`, `compare.py`).

## 8. Deterministic fixture corpus (canonical inputs)

Six 128×128 RGB fixtures, all values `uint8/255.0` (exactly representable), generated by
`tools/exynos_reference/generate_fixtures.py` (seed `20260828` for the noise fixture).

| Fixture | Raw FP32 CHW SHA-256 |
|---|---|
| n2_original | `4ed73d81aef9c69ecce6e0eb36d2e443e49ae25d5c4061f549035a0c8d404ed2` |
| rgb_channel_ramps | `e0f7f7b4ba0eaf9030d037048de19dc7a15380f131679a1783441caf50cdbb28` |
| smooth_2d_gradient | `75ac8cfe41114c4b48cabc6aea6fdfabaa3a846a9e2af2fb62f5d94e0badbaaf` |
| edges_impulses | `17b25fd931355ad6dfa8c305811094aadfae0446d1814a37f84a8a63ecdfa4fd` |
| high_frequency | `1e52e92fd3fe16dce5cbc04d36b66d9fd2a2922f2ed351c2b287d295d698ec1e` |
| deterministic_noise | `0144c67d9d56a7cbb0b336fc7d9697ec766ef374c6ae968664be6575821f932b` |

`n2_original` was verified bit-for-bit equal to the accepted N2 deterministic tile
(the decoded N2A input PNG differs by 0 pixels), so the N3 corpus directly continues the
N2 evidence chain. The per-fixture PNG + per-channel stats live in
`artifacts/exynos-n3-reference-s24-20260828/fixtures/fixtures_manifest.json`.