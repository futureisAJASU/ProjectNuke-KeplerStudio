# KeplerStudio N6 HOLD Report

Date: 2026-09-01
Branch: `review/n6-final-20260901`
Status: **N6 = HOLD / READY FOR REVIEW**

## Commit identity

- BASE / accepted code: `f228ebcdd6d448e13e46bdf9583e14880d11861d`
- Previous reviewed code-under-test SHA: `e34421cbf1839f5f143ac75afad1d3cba8ec1518`
- CODE UNDER TEST SHA: `0880c4bc5edd45ba01c3ab66a24101f6895a3683`
- EVIDENCE/REPORT COMMIT: created after verification; its own SHA is intentionally not embedded here

The code commit contains only the androidTest cancellation correction. The later evidence/report commit contains no production or test-code changes.

No N7 work, 16 KB gate, frozen-NNC change, or `N6_FINAL_REPORT.md` was made.

## Cancellation correction

Both cancellation tests wait after `openImage()` until `isBusy=false`, `maintenanceBusy=false`, `historyBusy=false`, history activity is idle, no Draft save job is active, and the Draft generation/pointer are non-null and equal. Only then is the baseline captured.

Both cancellation tests now use the full `documentUnchanged(...)` predicate. The JSON before/after identity structures are byte-identical for both fresh cancellation runs, and `document_unchanged` is derived from that full predicate before PASS evidence is written.

## Host verification

- Focused accepted N4/N5/N6 set: **137 tests, 0 failures, 0 errors, 0 skipped**
- Complete `testDebugUnitTest`: **1178 tests, 0 failures, 0 errors, 0 skipped** across 115 JUnit XML files
- Complete `testDebugUnitTest --rerun-tasks`: **1178 tests, 0 failures, 0 errors, 0 skipped** across 115 JUnit XML files
- Counts were parsed from actual `testDebugUnitTest` JUnit XML and matched.
- `compileDebugKotlin`, `compileDebugUnitTestKotlin`, `compileDebugAndroidTestKotlin`: PASS
- `lintDebug`, `assembleDebug`, `assembleDebugAndroidTest`: PASS

One transient unrelated DraftRestore test failure was isolated and passed; the final required suite pair was clean.

## Physical S24 verification

Device: Samsung `SM-S921N`, codename `e1s`, bounded identifier `R3CX40A15GB`, Exynos 2400
Android: `16`, API `36`
PAGE_SIZE: `4096`

All three physical tests passed sequentially on CODE UNDER TEST SHA `0880c4bc5edd45ba01c3ab66a24101f6895a3683`:

| Test | Evidence elapsed |
|---|---:|
| `n6ProductE2EWithEditedDocument` | 210641 ms |
| `n6CancellationDuringPngEncodingUsesViewModelActionAfterEncodingStarted` | 145126 ms |
| `n6CancellationDuringNpuUpscalingUsesViewModelActionAfterTilesStarted` | 517 ms |

### Product success

- Representative edits: exposure `0.35`, contrast `0.20`, active `VignetteCorrection`
- Edited N6 input / full-export source: **4080 x 3060**
- Published x4 output: **16320 x 12240**
- Observed tiles: **3350**
- Observed PNG rows: **12240**
- NPU proof: `OBSERVED`
- `document_unchanged`: **true**
- SavedExport history: `6 -> 7`, including the new published result
- Product elapsed: **210641 ms**

NNC observed size/SHA:

```text
3,112,960 bytes
9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12
```

Actual NPU diagnostics: H2D `SUCCESS`, executeReached `true`, Execute `SUCCESS`, D2H `SUCCESS`, compiler_npu `v2.4.11.l`.

Exactly 14 physical samples were produced by real callback/progress paths: `before_full_source_preparation`, `after_full_source_preparation`, `after_model_load`, `npu_early` tile 1/3350, `npu_midpoint` tile 1675/3350, `npu_late` tile 3350/3350, `after_rgb8_complete`, `png_early` row 32/12240, `png_midpoint` row 6144/12240, `png_late` row 12240/12240, `before_mediastore_publish`, `after_mediastore_publish`, `after_rgb8_cleanup`, and `after_session_close`.

There are no synthetic milestones. Cleanup reports the RGB8 file absent after the real cleanup attempt; session close follows the real close call.

### Cancellation evidence

PNG: trigger `Encoding`, exact row **32/12240**, terminal `Cancelled`, `isBusy=false`, published URI null, pending rows `0 -> 0`, RGB8 absent, session `Unloaded`, registry inactive, wake lock released, full document identity unchanged, elapsed **145126 ms**.

NPU: trigger `Upscaling`, exact tile **2/3350**, terminal `Cancelled`, `isBusy=false`, published URI null, pending rows `0 -> 0`, RGB8 absent, session `Unloaded`, registry inactive, wake lock released, full document identity unchanged, elapsed **517 ms**.

The cancellation JSONs contain byte-identical `document_identity_before` and `document_identity_after`, including Draft generation/pointer/source identity.

### Region parity

Expected RGB8 raw hashes equal decoded published-PNG hashes for all four regions; the full 16K PNG was not decoded.

| Region | Expected = actual |
|---|---|
| top-left | `d172d977a41d19ad3dac316c43fd5bbc4711c03fbe715b3ccc299fac16984319` |
| center | `ffc57ffd72c78f28ea5d27a8087f028094a970c131cd3d8bff727776383c9683` |
| bottom-right | `9b0d55cafee191a7a8f2979e2bb288c50cd3e02ffc6f82dba82954dcacf9f447` |
| planner-derived N4 seam crossing | `20bb9d7d74136a0782c12a80eeed0f1e541de4009e6a15c3a62bacef87e768d1` |

## Signing

Stable keystore, built APK, and installed APK certificate SHA-256 are equal:

```text
ac62525138841d7388237d190b37d9e119d854d97053655d063d28f9ba552c8f
```

Both APKs installed successfully with `adb install -r`. No uninstall, `pm uninstall`, or data clear was used.

## Tracked compact evidence

Directory: `artifacts/exynos-n6-s24-20260901/`

- `n6_product_e2e.json`
- `n6_memory_samples.json`
- `n6_memory_summary.json`
- `n6_region_hashes.json`
- `n6_cancel_png_e2e.json`
- `n6_cancel_npu_e2e.json`
- `n6_run_manifest.json`

The manifest binds the seven files to CODE UNDER TEST SHA `0880c4bc5edd45ba01c3ab66a24101f6895a3683`. No APK/AAB, bundle, RGB8, 16K PNG, raw logcat, HPROF, memory dump, fixture image, or private keystore is tracked.

## Final disposition

N6 remains **HOLD / READY FOR REVIEW**. Do not create `N6_FINAL_REPORT.md`, start N7, or start the separate 16 KB compatibility gate before N6 review closes.
