# N3 Reference Correctness — Exynos-2400 FP16 NNC vs Real-ESRGAN

Phase: N3 — FP16 NNC Reference Correctness Validation (KeplerStudio)
Status: **PASS**
Date: 2026-08-28

The Exynos-2400 FP16 NNC is numerically consistent with the upstream
`realesr-general-x4v3` **GENERAL** checkpoint (denoise_strength = 1.0 semantics),
with bounded FP16/compiler numerical error and no observed channel/layout/preprocessing
defect. The Samsung production name `Real_ESRGAN_General_x4v3` is the pure GENERAL
model — NOT the official CLI default 0.5 DNI blend and NOT WDN.

---

## Provenance

| Field | Value |
|---|---|
| Upstream repo | `xinntao/Real-ESRGAN` (BSD-3-Clause) |
| Upstream commit | `a4abfb2979a7bbff3f69f58f58ae324608821e27` |
| Architecture | `SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=32, upscale=4, act_type=prelu)` |
| GENERAL checkpoint | `realesr-general-x4v3.pth` — 4,885,111 B — `8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292` |
| WDN checkpoint | `realesr-general-wdn-x4v3.pth` — 4,885,111 B — `1641f8c4464b9f097c9fdda5589273713f67cf59f3d909e0bd688f0cee269dca` |
| Checkpoint keys | both `['params']` (no `params_ema`); official loader selects `params` |
| DNI implementation | `params[k] = 0.5·GENERAL[k] + 0.5·WDN[k]` (exact upstream `dni()` on `params`) |
| Samsung NNC | `Real-ESRGAN-General-x4v3.nnc` — 3,112,960 B — `9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12` |
| NNC target | SM-S921N · e1s · S5E9945 · Exynos 2400 |
| NNC compiler | NPUC `v2.4.11.l` (`compiler_nnc=27` on device) |
| Baseline head | `c62731d1e22a2c37bd9ab2fd368bb54687a824b7` |

Full provenance pinned in [`N3_REFERENCE_CONTRACT.md`](N3_REFERENCE_CONTRACT.md).

## Exact contracts

- **Input**: FP32 · RGB · CHW planar · batch 1 · 3×128×128 · `uint8/255.0` in [0,1] ·
  no mean/std. Serialized once (`*_input.f32le`, 196,608 B) and fed byte-identically to
  PyTorch and the NNC H2D.
- **Output**: FP32 · RGB · CHW planar · batch 1 · 3×512×512 · raw (may exceed [0,1]).
- **Preprocessing (reference)**: none beyond the canonical CHW tensor — no cv2, no BGR.
- **Postprocessing (image)**: raw → clamp [0,1] → `*255` → nearest round (half-to-even,
  matching upstream `np.round`).

## Candidate identification

Aggregate raw-tensor error (NNC vs each candidate), across all six fixtures:

| Candidate | aggregate MAE | aggregate RMSE | aggregate MAX | P95 | P99 |
|---|---|---|---|---|---|
| **GENERAL** | **0.000270** | **0.000430** | **0.010898** | 0.000784 | 0.001507 |
| WDN | 0.045861 | 0.100874 | 1.482201 | 0.133999 | 0.240020 |
| DNI-0.5 | 0.025644 | 0.053056 | 0.752744 | 0.078869 | 0.140827 |

GENERAL is ~95× closer than DNI-0.5 and ~170× closer than WDN on aggregate MAE — an
unambiguous, fixture-consistent identification (see per-fixture table below). Correlation
of NNC output vs GENERAL is ≥ 0.99997 for every fixture.

## Raw correctness

Per-fixture, NNC vs GENERAL (raw FP32, before clamp):

| Fixture | MAE | RMSE | MAX | corr | mean-signed-err |
|---|---|---|---|---|---|
| n2_original | 0.000240 | 0.000361 | 0.008237 | 0.9999995 | -0.000100 |
| rgb_channel_ramps | 0.000236 | 0.000431 | 0.010592 | 0.9999987 | -0.000051 |
| smooth_2d_gradient | 0.000331 | 0.000558 | 0.010898 | 0.9999987 | -0.000079 |
| edges_impulses | 0.000094 | 0.000169 | 0.002013 | 0.9999996 | -0.000057 |
| high_frequency | 0.000292 | 0.000390 | 0.005812 | 0.9999997 | -0.000155 |
| deterministic_noise | 0.000425 | 0.000548 | 0.003062 | 0.9999721 | -0.000242 |

Aggregate per-channel MAE: R=0.000267, G=0.000238, B=0.000303 (symmetric — rules out
channel swap). Non-finite element count in every NNC output: **0**.

**Diagnosis (N3.11)**: the error is ~1e-4 to 1e-3 with correlation ≈ 1 and a near-zero
mean signed error. This is the FP16/compiler numerical-drift signature, not a functional
mismatch. Ruled out explicitly: RGB/BGR swap (would yield per-channel MAE asymmetry and
errors ~0.1), CHW/HWC or spatial transpose (per-pixel errors ~0.1–1.0, not 1e-4),
[0,1] vs [0,255] scale (error ∝ value, not 1e-4), wrong checkpoint (GENERAL vs WDN vs
DNI distinguished above), and input/output byte-order/stride (input parity itself proven).

**FP16 secondary (N3.8, optional)**: PyTorch FP16 execution of the same GENERAL model on
the canonical `n2_original` input yields MAE 0.000245 / RMSE 0.000373 / MAX 0.008969 vs
the FP32 reference — essentially the same magnitude as the NNC's 0.000240 / 0.000361 /
0.008237. The NNC is even slightly closer to PyTorch-FP16 than to PyTorch-FP32
(FP16-cpu vs NNC: MAE 0.000132). This confirms the NNC drift is normal half-precision
numerical error, not a Samsung compiler/model mismatch.

## Determinism (N3.14)

After warm-up, each fixture was run twice on the S24. All six fixtures were
**bit-identical** across runs (run-1 SHA == run-2 SHA; max absolute difference 0.0).
No nondeterminism observed.

## Input parity (N3.6, hard prerequisite)

The SHA-256 of the exact bytes handed to H2D was captured on-device for every run and
equals the canonical serialized input hash for all 12 recorded runs (plus warm-up):

| Fixture | H2D input SHA == fixture SHA |
|---|---|
| n2_original | ✓ (`4ed73d81…`) |
| rgb_channel_ramps | ✓ (`e0f7f7b4…`) |
| smooth_2d_gradient | ✓ (`75ac8cfe…`) |
| edges_impulses | ✓ (`17b25fd9…`) |
| high_frequency | ✓ (`1e52e92f…`) |
| deterministic_noise | ✓ (`0144c67d…`) |

`n2_original` was additionally verified pixel-identical to the accepted N2 deterministic
tile (0 differing pixels vs the decoded N2A input PNG).

## Image correctness (N3.12 / N3.13)

Canonical 8-bit comparison (NNC vs GENERAL), with the model-only vs postprocess split:

| Fixture | model-only MAE (levels) | model-only differing fraction | legacy truncation MAE (levels) |
|---|---|---|---|
| n2_original | 0.057 | 5.7% | 0.396 |
| rgb_channel_ramps | 0.063 | 6.3% | 0.487 |
| smooth_2d_gradient | 0.083 | 8.2% | 0.461 |
| edges_impulses | 0.006 | 0.6% | 0.043 |
| high_frequency | 0.048 | 4.8% | 0.245 |
| deterministic_noise | 0.103 | 10.3% | 0.451 |

## Findings / fixes

1. **Model identity** — Samsung's `Real_ESRGAN_General_x4v3` is the pure GENERAL
   checkpoint, not the official DNI 0.5 default. No production change required; recorded
   as the identified identity.
2. **Postprocess rounding defect (fixed)** — Kepler's 8-bit conversion used truncation
   (`(x*255).toInt()`), while upstream uses nearest rounding (`np.round`, half-to-even).
   This inflated the 8-bit difference to ~0.4 levels / ~40% of pixels while the raw error
   was only ~0.06 levels. Fixed `quantizeFp32PixelToUint8` to use `Math.rint`
   (round-half-to-even), reducing the image-domain difference to the pure model-error
   floor. Production raw model output is untouched. Regression test added; the existing
   output-contract test updated (128/64 vs the former 127/63).

## Regression (N3.18)

Host:

| Gate | Result |
|---|---|
| `compileDebugKotlin` | PASS |
| `compileDebugUnitTestKotlin` | PASS |
| `compileDebugAndroidTestKotlin` | PASS |
| `testDebugUnitTest` (×2) | PASS — **1073 tests, 0 failures** (baseline 1072 + 1 added) |
| session/model/availability shard (×2) | PASS |
| `lintDebug` | PASS |
| `assembleDebug` | PASS |
| `assembleDebugAndroidTest` | PASS |

On-device (S24 / Exynos 2400), N2 FP16 probe re-run with the final code: **PASS** —
OpenModel PASS, Allocate PASS, Execute PASS, NPU proof OBSERVED, close PASS, lifecycle
round-trip PASS. Prepared asset SHA unchanged (`9cff7af6…`). The N3 raw-probe
instrumentation independently re-proved the same gates.

## Conclusion

The Exynos-2400 FP16 NNC (`Real-ESRGAN-General-x4v3.nnc`) is numerically consistent with
the upstream `realesr-general-x4v3` **GENERAL** checkpoint (SRVGGNetCompact 64f/32c/×4),
with bounded FP16/compiler error (aggregate MAE 2.7e-4, RMSE 4.3e-4, MAX 1.1e-2, zero
non-finite values, correlation ≥ 0.99997) and no observed channel, layout, scale, offset,
or preprocessing defect.

---

## Final gate (N3.20)

```
N3 REFERENCE CORRECTNESS: PASS

UPSTREAM:
commit      = a4abfb2979a7bbff3f69f58f58ae324608821e27
architecture= SRVGGNetCompact(num_in_ch=3,num_out_ch=3,num_feat=64,num_conv=32,upscale=4,act_type=prelu)
general SHA = 8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292
wdn SHA     = 1641f8c4464b9f097c9fdda5589273713f67cf59f3d909e0bd688f0cee269dca

SAMSUNG NNC:
SHA      = 9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12
target   = SM-S921N / e1s / S5E9945 / Exynos 2400
compiler = NPU, NPUC v2.4.11.l

MATCHED REFERENCE: GENERAL

RAW NNC vs REFERENCE (GENERAL, aggregate):
MAE=0.000270  RMSE=0.000430  P95=0.000784  P99=0.001507  MAX=0.010898
per-channel MAE: R=0.000267 G=0.000238 B=0.000303

REPEATABILITY: bit-identical = yes (6/6 fixtures, max abs diff 0.0)

IMAGE DOMAIN (GENERAL, model-only):
upstream canonical vs NNC = MAE 0.006–0.103 levels, PSNR >= ~40 dB
Kepler postprocess vs upstream = nearest-round (half-to-even), parity restored

DISCOVERED DEFECTS:
  - postprocess used truncation instead of upstream nearest rounding (fixed)

PRODUCTION FIXES:
  - quantizeFp32PixelToUint8 -> Math.rint (round half-to-even)

UNIT TESTS: 1073 (0 failures); ExynosUpscaleSessionTest PASS
S24 NPU REGRESSION: OpenModel/Allocate/Execute/NPU-proof/close PASS

GATE: PASS
NEXT: N4 Full-Image Tiling / Seam Correctness   (NOT started)
```