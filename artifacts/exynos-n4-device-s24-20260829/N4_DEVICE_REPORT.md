# N4 Device Closure — Final Report

Phase N4 physical-device closure on the real Galaxy S24 (Exynos 2400).
Full-image tiling / seam correctness against the pinned GENERAL PyTorch reference.

> **Normative note.** This commit is an evidence/report normalization corrective
> over the already accepted physical run (evidence commit `1bbb0dd`). No new
> device execution occurred. The aggregate region definitions now match
> `compare_n4.py` exactly, pooled P95/P99 are recomputed from the retained raw
> outputs using production masks, and a machine-readable
> `n4_aggregate.json` is committed.

## Execution summary

| Gate | Status |
|---|---|
| N4 HOST/GEOMETRY GATE | PASS |
| N4 DEVICE-CLOSURE HOST HARNESS | PASS |
| N4 DEVICE GATE | PASS |
| N4 FINAL GATE | PASS |
| N4 FULL-IMAGE TILING / SEAM CORRECTNESS | PASS |

> This report was normalized after independent bundle review. The physical S24 execution
> (SM-S921N, Exynos 2400, 25/25 fixtures, 280/280 tiles) was accepted without re-run.
> The normalization corrected aggregate SEAM/NON-SEAM values to use the exact region
> masks from `compare_n4.py`: SEAM = `seam & ~border`, NON-SEAM = `~seam & ~border`.

## Repository state

- START HEAD: `f1d03cb4d15905aa8ecbaade0594e86906f4fb2f`
- FINAL HEAD: `e49a3d96ecb6893f9d7594fc5e528aa8790ed879`
- Branch: `feature/exynos-ai-runtime`

## Device identity

| Field | Value |
|---|---|
| serial | `R3CX40A15GB` |
| model | `SM-S921N` |
| device codename | `e1s` |
| platform / SoC | `erd9945` / `s5e9945` (Exynos 2400) |
| Android | 16 (SDK 36) |
| build fingerprint | `samsung/e1sksx/e1s:16/BP4A.251205.006/S921NKSSHDZH3:user/release-keys` |

## Production NNC

| Field | Value |
|---|---|
| path | `app/src/main/assets/models/exynos/Real-ESRGAN-General-x4v3.nnc` |
| size | `3,112,960` bytes |
| SHA-256 | `9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12` |

## GENERAL reference identity

| Field | Value |
|---|---|
| weights identity | `GENERAL` |
| checkpoint SHA-256 | `8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292` |
| upstream commit | `a4abfb2979a7bbff3f69f58f58ae324608821e27` |

## Corpus cardinality

| Field | Expected | Completed |
|---|---|---|
| fixtures | 25 | 25 |
| canonical tiles | 280 | 280 |

Warm-up and decomposition reruns are excluded from the canonical 280.

## NPU proof

| Field | Value |
|---|---|
| compiler_npu | `v2.4.11.l` |
| compiler_nnc | `27` |
| enn_execute_observed | `true` |
| npu_execution_observed | `true` |
| npu_proof_status | `OBSERVED` |

All 280 canonical tiles recorded `h2d_status = 0(ENN_RET_SUCCESS)`,
`execute_status = 0(ENN_RET_SUCCESS)`, `d2h_status = 0(ENN_RET_SUCCESS)`,
confirming the Samsung ENN retail path executed on the Exynos NPU:
Kotlin → `System.loadLibrary("enn_kepler_jni")` → app JNI →
`libenn_public_api_ndk_v1.so` → vendor ENN → `libenn_user.samsung_slsi.so` → Exynos NPU.

## Aggregate corpus metrics (raw FP32 abs-diff vs GENERAL full reference, 64,294,560 elements)

Region definitions (from `compare_n4.py`, these three partition GLOBAL):

- **SEAM:** `seam & ~border`
- **NON-SEAM:** `~seam & ~border`
- **BORDER:** `border`

| Region | count | MAE | RMSE | P95 | P99 | MAX |
|---|---:|---:|---:|---:|---:|---:|
| GLOBAL | 64,294,560 | 0.000295646 | 0.000454904 | 0.001012 | 0.001541 | 0.010799 |
| SEAM | 4,964,160 | 0.000293767 | 0.000461322 | 0.001017 | 0.001556 | 0.010773 |
| NON-SEAM | 57,128,160 | 0.000295418 | 0.000455756 | 0.001018 | 0.001546 | 0.010799 |
| BORDER | 2,202,240 | 0.000305787 | 0.000416593 | 0.000872 | 0.001309 | 0.006450 |

Seam / non-seam ratios (aggregate):

- `seam_mae / non_seam_mae = 0.994413`
- `seam_rmse / non_seam_rmse = 1.012213`

> **Previous aggregate divergence.** The earlier report computed SEAM using the raw
> `seam` mask and NON-SEAM using `~seam`, without excluding border pixels. With the
> production masks above, SEAM shrinks from 5,052,480 → 4,964,160 and NON-SEAM
> from 59,242,080 → 57,128,160; GLOBAL and BORDER are unaffected. P95/P99 are
> now pooled directly from the retained raw files using production masks.

Machine-readable aggregate: `n4_comparison/n4_aggregate.json`.

Per-fixture metrics: `n4_comparison/n4_comparison.json` and
`n4_comparison/n4_comparison.csv`.

## Notable per-fixture result: mixed_188x188

`seam/non-seam MAE ratio ≈ 1.753`. This is **not** classified as a tiling seam
defect. The canonical fixture generator intentionally writes `r[:, w//2] = 255`
and `g[h//2, :] = 255`, placing strong image-content edges directly at
`w//2 = h//2 = 94` LR → `376` HR, which coincides with the 188×188 ownership
seam. The elevation is therefore **content-localized compiler error** (edge
amplification), not systematic tiling discontinuity. All other fixtures have
seam/non-seam ratios near 1.0.

## Error decomposition (N4.14)

Designated fixtures, all 20 tile input/output pairs persisted:

| Fixture | tiles | compiler_drift_mae | tiling_drift_mae |
|---|---|---|---|
| seam_stress_188x188 | 4 | 0.000117 | 0.0 |
| smooth_257x257 | 16 | 0.000254 | 0.0 |

Total decomposition tiles: 20 / 20.

- **Tiling/context drift = 0.0** for the canonical corpus, matching the host N4
  result at halo=34.
- The entire observed device-vs-reference difference is **compiler drift**
  (Samsung NNC FP16 vs pinned-GENERAL PyTorch), which the full-image metrics show
  is uniform across seam and non-seam regions (ratio ≈ 1.0). No localized seam
  discontinuity, no border/corner corruption.

## Timing (physical S24, ms)

| Stage | mean | median | P95 | min | max |
|---|---|---|---|---|---|
| per-tile NNC execution (280 tiles) | 13.93 | 13.0 | 20.0 | 10 | 29 |
| per-fixture total inference | 174.1 | 187.0 | 267.4 | 69 | 268 |
| assembly | 12.3 | 11.0 | — | 8 | 31 |

Canonical corpus wall total: 4352 ms. Warm-up and decomposition reruns excluded
from the canonical 280 count. (N4 is a correctness gate; performance recorded for
later phases, not used to redesign N4.)

## Session / close / registry

| Field | Value |
|---|---|
| close | PASS |
| registry_session_active_after_close | `false` |

`ModelAvailabilityRegistry` did not remain session-active after close.

## Regression gates

| Gate | Result |
|---|---|
| compileDebugKotlin | PASS |
| compileDebugUnitTestKotlin | PASS |
| compileDebugAndroidTestKotlin | PASS |
| TilePlannerTest (focused) | PASS |
| TileInferenceOrchestratorTest (focused) | PASS |
| ExynosUpscaleSessionTest (focused) | PASS |
| ModelAvailabilityRegistryTest (focused) | PASS |
| testDebugUnitTest (run 1) | PASS |
| testDebugUnitTest (run 2, --rerun-tasks) | PASS |
| lintDebug | PASS |
| assembleDebug | PASS |
| assembleDebugAndroidTest | PASS |

Actual JUnit XML totals: **1091 tests, 0 failures, 0 errors, 0 skipped**.

## Git hygiene

- `.gitignore` generalized to `artifacts/exynos-n4-*/reference/full/` and
  `artifacts/exynos-n4-*/reference/tiled/`.
- `git check-ignore` confirms both
  `artifacts/exynos-n4-reference-20260829/reference/full/test_raw.f32le` and
  `artifacts/exynos-n4-device-s24-20260829/reference/full/test_raw.f32le` are ignored,
  and `artifacts/exynos-n4-device-s24-20260829/n4_reference_manifest.json` is NOT ignored.
- No N4 raw `.f32le` reference tensors committed.

## Evidence retained (durable compact)

| Path | Description |
|---|---|
| `n4_reference_manifest.json` | GENERAL identity + 25 fixture host hashes. |
| `device_evidence/metadata.json` | Device identity, model identity, per-tile records, NPU proof, timing, close/registry state. |
| `n4_comparison/n4_comparison.json` | Full GLOBAL/SEAM/NON-SEAM/BORDER metrics per fixture + decomposition. |
| `n4_comparison/n4_comparison.csv` | Per-fixture metric rows. |
| `n4_comparison/n4_aggregate.json` | Machine-readable pooled aggregate: region defs, counts, MAE/RMSE/P95/P99/MAX, ratios, identities. |
| `n4_comparison/heatmaps/*_8bit_abs.png` | Compact 8-bit absolute-error heatmaps. |

Raw-tensor retention (intentional, not lost / not committed):

- **raw assembled outputs** (`device_evidence/assembled_*.f32le`): local only
- **raw decomposition tile inputs/outputs** (`device_evidence/tiles/`): local only
- **raw GENERAL reference tensors** (`reference/full/*_raw.f32le`): local / regenerable via `reference_runner.py` with the pinned GENERAL checkpoint

All `.f32le` under `device_evidence/` and under `reference/full/` and
`reference/tiled/` are excluded via `.gitignore`. The compact metadata,
machine-readable aggregate, per-fixture JSON/CSV, and 8-bit heatmaps above are
the committed durable evidence. The on-disk device run remains identical
(25/25 fixtures, 280/280 canonical tiles; see `device_evidence/metadata.json`);
no raw file has been altered in this reporting commit.