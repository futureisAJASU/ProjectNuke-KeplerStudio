# N6 Production Super-Resolution Integration — Design & Report

**Phase:** N6 — Production Super-Resolution Integration (AI 4x Super Resolution export)

**START HEAD:** `2dc8a6c178f9e127d718beb19ee7ca22512eb06a` (feature/exynos-ai-runtime)

**NNC pinned:** `app/src/main/assets/models/exynos/Real-ESRGAN-General-x4v3.nnc` — `3112960` bytes — `9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12`

**Accepted prior:** N4 FULL-IMAGE TILING PASS, N5 BOUNDED-MEMORY 4080×3060→16320×12240 3350 tiles PASS (S5E9945)

---

## 0. Architecture Decision

N6 integrates Exynos NPU super-resolution as a **terminal, non-destructive product export** — `current document → settle → render Full → N5 x4 file-backed RGB8 → bounded streaming PNG → MediaStore pending → publish → saved-export history` — **without** adopting the 4x result as `originalPreviewBitmap`/`previewBitmap`, without copying 599 MB RGB8 into Draft generations, and without changing base source.

Feature: “AI 4배 고해상도 저장” / “AI 4x Super Resolution export” (PNG only, see §10).

N7 will own any future “edit the 4x as new document” architecture.

---

## 1. State & Progress Model

`SuperResolutionExportState` / `SuperResolutionExportProgress`:

- phases `Idle/Preparing/Upscaling/Encoding/Publishing/Succeeded/Failed/Cancelled`
- fields `phase`, `completedTiles/totalTiles/tileFraction`, `encodingRowsCompleted/Total`, `overallFraction` (Preparing 0-10%, NPU 10-80%, PNG 80-98%, publish 98-100%), `inputWidth/Height`, `outputWidth/Height`, `message`, `canCancel`
- `SuperResolutionExportStatus` exposed as `EditorViewModel.superResolutionStatus: StateFlow` + `isBusy` participates in global `editorAction` arbitration ( `canStartSuperResolution()` checks `isBusy`, `ModelAvailabilityRegistry[ExynosUpscale].canAttemptModelUse`, document exists).

No overload of single `isBusy` string; SR progress struct is explicit.

---

## 2. Document Isolation

Successful N6 does **not** mutate: `sourcePath`, `baseContentToken`, `revision`, `originalPreviewBitmap`, `previewBitmap`, `params`, `cropState`, `selectionLayers`, `activeQuickEffects`, `Undo/Redo`, `Draft generationId`.

Only `savedExports` / SR status change. No `settleAdoptedEditHistory` / `forceDraftSaveAsync`. Tests `SuperResolutionExportHostTest.successLeavesDocumentUnchanged` (and failure/cancel/stale variants) assert byte/state equivalence before/after via ViewModel reflection.

---

## 3. Shared Export Render Semantics

Input to SR is the **same pixels a normal Full export would render before compression**.

Helper `EditorViewModel.prepareSuperResolutionSourceBitmap()` reuses `renderEditedExport` / `renderEditedExportFromBitmap` with `ExportResolution.Full`, `baseBitmapDirty` (dirty copy of `originalPreviewBitmap`/`previewBitmap`, memory-checked), `sourcePath`, `params`, `crop`, `selectionLayers`, `quickEffects`, `correctionEngine` (baked features) — not `previewBitmap` alone.

If Full cannot be prepared under `BitmapMemoryBudget`, fails `SourceRenderMemoryRejected` truthfully; no 4x Bitmap fallback.

Records actual `inputWidth/Height` → `outputWidth/Height = input*4` truthfully.

---

## 4. N5 Bridge

`BitmapTileInputSource(inputBitmap)` → `TileFileBackedUpscaler(session, context)` with `DiagnosticRetention.LAST_ONLY` (already accepted), `TilePlanner` 128/34/60/x4 midpoint rules unchanged, reusable input 196608 B / output 3145728 B, `StoragePressure` admission for RGB8, atomic internal `Files.move(ATOMIC_MOVE)` artifact, `publicationGuard`.

No new tiler, no `Bitmap/ByteArray/IntArray/FloatArray/MappedByteBuffer` for whole 4x.

---

## 5. Model Availability

`ModelFeature.ExynosUpscale` + `ModelAvailabilityRegistry.state[ExynosUpscale]` is authoritative. Hub reads it; SR enabled only when `document exists && canEnterEditorActionPure && no conflicting export && canAttemptModelUse==true`. No hard-coded “available”.

No silent bilinear/bicubic fallback; `NpuLoadFailed/H2d/Execute/D2h/NativeThrow` fail truthfully (`SuperResolutionFailureKind`).

---

## 6. Streaming PNG Encoder

`StreamingPngEncoder` (bounded):

- PNG signature, IHDR 8/2/0/0/0, one raw scanline `width*3+1` + filter 0, `Deflater(6)` chunked `64 KiB` IDAT, CRC32, IEND — no whole-image Bitmap.
- Row-by-row `FileChannel` read with partial-read loop, zero-progress bounded failure, `isCancelled`/`isCurrent` checked between rows/chunks.

Tests `StreamingPngEncoderTest` (9): 1x1 black/white/primary, odd 3×5, 7×7 random byte-identical via `BitmapFactory`, IHDR/CRC/multi-IDAT, invalid size, short read, output write failure, cancel/stale mid-encode.

---

## 7. MediaStore Transaction

`SuperResolutionRowStore` (`AndroidSuperResolutionRowStore` via `ContentResolver`):

`insertPending PNG → streaming encode → isCurrent → publish → SavedExportHistoryStore.commit` ; on any pre-publish failure/cancel/stale/encode/ENOSPC → `delete(pending)`; bounded retry via `ExportRowTransaction` philosophy (one extra delete attempt, then surfaced).

After `publish`, `MetadataPersistFailure` preserves image, returns `PublishedWithMetadataFailure` (analogous to normal export). `ExportRowTransaction` idempotent.

Tests cover pending→encode→publish, stale/cancel/encode-failure deletes row, publish failure settles, metadata failure preserves image, no double-delete after publish.

---

## 8. RGB8 Artifact Ownership

Operation-owned temp `sr6_*.rgb8` in `context.cacheDir`:

- success: NPU → `rgb8Artifact` → PNG consumes → MediaStore publish → rgb8 deleted → session close
- failure/cancel/stale: rgb8 deleted, pending row deleted, session closed, no fake success
- post-publish cleanup failure: does **not** delete published image, surfaces `InternalCleanupFailure` compactly
- idempotent `File.delete()` + `deleteOnExit` + double-cleanup test.

---

## 9. Storage Admission

Conservative PNG bound: `pngUpperBound(width,height) = (width*3+1)*height + raw/100 +128 + header` (≈ zlib `compressBound` + PNG overhead) checked Long.

- N5 retains `StoragePressure` for RGB8.
- Before NPU, `StoragePressure.ensureWriteHeadroom` for `pngBound` on `getExternalFilesDir` volume (proxy for MediaStore). If `usableSpace==null` (UNKNOWN) → proceed, fail-closed on actual `ENOSPC` write failure. No history-storage semantics changed.

Tests: `InternalStorageInsufficient` (RGB8), `DestinationStorageInsufficient` (PNG bound).

---

## 10. Identity & Staleness

`SuperResolutionExportIdentity(token, sourcePath, baseContentToken, revision, owningJob)` + `ModelOperationContext(operationToken=documentGeneration)`; stale if `token!=superResolutionToken || sourcePath/baseToken/revision changed || ViewModel shutdown || explicit cancel || superseding export`.

Stale never publishes. Not just `isBusy`.

Tests: `invalidateExportForTest` makes SR stale, newer SR supersedes old, document replace → stale.

---

## 11. Conflict Policy

While SR owns external operation, normal export / new SR / open image / draft restore / engine switch / history navigation / `viewModelScope` clear are arbitrated via `canStartSuperResolution()` / `isCurrentSuperResolution()` + `superResolutionToken` bump. No hidden queue; only current authoritative may publish.

Tests cover normal-vs-SR conflict, supersession, shutdown.

---

## 12. Progress & Cancellation

Recommended weighting implemented via `onProgress`: Preparing 0-10%, NPU 10-80% (`completedTiles/totalTiles`), PNG 80-98% (`rowsCompleted/height`), publish 98-100%; monotonic `overallFraction` to 1.0 on success, `Cancelled` never reports success.

Cancellation propagates via `superResolutionJob.cancel()` → `isCancelled` checked in `prepare`, NPU tile boundaries (`operationContext.isCancelled` + `ensureActive`), PNG rows, pre-publish. `EnnExecuteModel` in-flight not magically interruptible — next tile/row not started. UI returns Idle after cleanup, no MediaStore image for pre-publish cancel.

Tests: tile/encoding monotonic, success 1.0, cancel no success.

---

## 13. Wake Lock

`N5WakeLock` (`RealN5WakeLock` `PowerManager.PARTIAL_WAKE_LOCK`, non-reference-counted, no `KEEP_SCREEN_ON`/`FULL`) — already `WAKE_LOCK` permission.

`SuperResolutionExportOrchestrator` acquires before NPU/PNG, releases in `finally` on success/failure/cancel/stale/exception. N5 proved display-OFF with same primitive; N6 retains tightly-scoped per-operation wake.

Tests `N5WakeLockTest` (success/failure/cancel, PARTIAL only) + orchestrator `wakeLockReleasedOnAllPaths`.

---

## 14. UI — Remaster Panel

`RemasterToolPanel.kt` adds card:

Title “AI 4배 고해상도”, status from `ModelAvailabilityRegistry[ExynosUpscale]` (`사용 가능` / `준비됨` / `phase`), explanation “Exynos NPU에서 현재 편집 결과를 4배 확대해 PNG로 저장합니다. 4배 고해상도는 현재 PNG로 저장됩니다.”

Button “AI 4배 PNG 저장” enabled only `hasImage && !isBusy && !srBusy && canAttemptModelUse`.

During run replaces with phase text (`AI 확대 중 · 1842 / 3350 타일`, `PNG 저장 중 · 7420 / 12240행`), `input→output` dimensions, `(overall*100)%`, Cancel button (`canCancel`). Post-run shows `저장 완료` / failure. No N4/N5 debug (H2D/D2H/compiler_npu) in UI.

Model Hub adds `ExynosUpscale` via `ModelFeature.ExynosUpscale`, does not route through `RemasterModelSession`.

---

## 15. Saved Export History & Provenance

`SavedExportHistoryStore.commit(SavedExport)` with `ExportFormat.Png`, `resolutionLabel = "${outW}x${outH}"`, `provenanceFeature = ExynosUpscale`, `scale=4`, `modelId=exynos_real_esrgan_x4v3`, `sha=9cff7af…8bae12`, `input/output dimensions`, `route=Exynos ENN/NPU`.

`SavedExport` extended with nullable `provenance*` fields, `encodeHistory` 5→14 pipe fields backward-compatible (old 5-field rows decode with nulls, new 14-field rows carry provenance).

Tests verify history load after SR success contains N6 entry with correct provenance, old entries still parse.

---

## 16. Draft/History Isolation

Proved no new `Undo/Redo`, `baseContentToken`, `revision`, draft generation: `SuperResolutionExportHostTest` asserts `revision`/`baseContentToken`/`previewBitmap` identity before/after success/failure/cancel/stale.

Saved-export history allowed to change after publish; Draft not rewritten — closes 599 MB Draft payload without N7.

---

## 17. Transparency

NNC output RGB8 no alpha. N6 does bounded scan (100 sampled pixels) for non-opaque `alpha != 0xFF`; if found → `AlphaUnsupported` `Failure` (reject), not opaque PNG silently. Photo editor: meaningful-alpha reject acceptable for N6.

Tests: `alphaUnsupportedRejected` (semi-transparent 128x128 → `AlphaUnsupported`).

---

## 18. Failure Taxonomy

`SuperResolutionFailureKind`: `NoDocument`, `ActionBusy`, `ModelUnavailable`, `ModelValidationFailed`, `SourceRenderMemoryRejected`, `SourceRenderFailed`, `InvalidDimensions`, `AlphaUnsupported`, `InternalStorageInsufficient`, `DestinationStorageInsufficient`, `NpuLoadFailed`, `NpuH2dFailed`, `NpuExecuteFailed`, `NpuD2hFailed`, `NpuNativeThrow`, `Rgb8ArtifactFailure`, `PngEncodeFailure`, `MediaStoreInsertFailure`, `MediaStoreWriteFailure`, `MediaStorePublishFailure`, `MetadataPersistFailure`, `InternalCleanupFailure`, `Cancelled`, `Stale`.

Diagnostics exact stage; UI maps to friendly Korean.

---

## 19. Host Matrix (explicit)

`SuperResolutionExportHostTest` (12) + `StreamingPngEncoderTest` (9) + `FileBackedRgb8SinkTest` (13) + `TileFileBackedUpscalerCorrectiveTest` (12) + `N5WakeLockTest` (4) etc.:

- model unavailable disables / capability allows
- no document rejects (ViewModel `canStart`)
- conflicting busy rejects
- success leaves `revision`/`baseContentToken`/`preview` unchanged, no Undo/Redo
- failure/cancel/stale leave document unchanged
- SR input equals Full export rendered pixels (256→1024 etc.), truthful `input→output` dimensions
- N5 bridge `BitmapTileInputSource` exact dims, no 4x Bitmap
- PNG byte-exact, row/channel order, bounded buffers, partial reads/writes, CRC, multi-IDAT, cancel/stale mid-encode
- MediaStore pending→publish, stale/cancel/encode-failure deletes row, publish failure settles, metadata failure preserves image, no double-delete
- RGB8 cleanup after success/failure/cancel/stale, post-publish cleanup failure preserves image, session/wake released on all paths
- arbitration supersession / document replace stale / shutdown
- progress monotonic tile/encoding/phase, success 1.0, cancel never success

All `testDebugUnitTest` 1160+ tests green ×2.

---

## 20. Regression Gates — 2026-08-30

`compileDebugKotlin` PASS, `compileDebugUnitTestKotlin` PASS, `compileDebugAndroidTestKotlin` PASS,
`TilePlannerTest`/`TileInferenceOrchestratorTest`/`ExynosUpscaleSessionTest`/`FileBackedRgb8SinkTest`/`TileFileBackedUpscalerTest`/`BitmapTileInputSourceTest`/`TileFileBackedUpscalerCorrectiveTest`/`N5WakeLockTest`/`ModelAvailabilityRegistryTest` + `StreamingPngEncoderTest`/`SuperResolutionExportHostTest` PASS,
`testDebugUnitTest` 1160 tests 0 failures ×2, `lintDebug` PASS, `assembleDebug` PASS, `assembleDebugAndroidTest` PASS.

---

## 21. Physical S24 E2E

Target `SM-S921N` `e1s` `S5E9945` / Exynos 2400, NNC `3112960` `9cff7af…` (device verified).

**Product path traversed:** `EditorViewModel.prepareSuperResolutionSourceBitmap()` (exposure/contrast quick-effect edited document, Full) → `ModelAvailabilityRegistry` `ExynosUpscale` → `ExynosUpscaleSession` `DiagnosticRetention.LAST_ONLY` → `TileFileBackedUpscaler` (BitmapTileInputSource) → `FileBackedRgb8Artifact` → `StreamingPngEncoder` (row-by-row, bounded) → `AndroidSuperResolutionRowStore` pending → `publish` → `SavedExportHistoryStore` (PNG + provenance).

**Case A — full product scale:** 4080×3060 (generated deterministic photo, exposure/contrast + quick-effect applied) → 16320×12240 PNG (3350 tiles, `TilePlanner` halo 34), `BitmapRegionDecoder` sampled top-left/center/bottom-right + seam 240×240 without decoding whole 16320 PNG, byte-identical RGB vs artifact samples before delete.

- input 4080×3060, output 16320×12240, tileCount 3350 completed, pngRows 12240, publishedUri `content://media/...`, MIME `image/png`, elapsed 218115 ms
- NPU proof `compiler_npu=v2.4.11.l` `H2D SUCCESS` `executeReached` `Execute SUCCESS` `D2H SUCCESS` `OBSERVED` (bounded `LAST_ONLY` history 1)
- wake `PARTIAL_WAKE_LOCK` acquired before NPU, held true at sampled milestones, released true after, display `isInteractive false` allowed (device locked)

**Case B — cancellation:** mid-NPU `isCancelled true` after 2 tiles → `Cancelled`, no pending `content://` row, RGB8 deleted, `history size ≤2`, wake released, `registry inactive`, UI `Cancelled/Idle`, document `revision/baseContentToken` unchanged.

All with display OFF (screen may turn off, process stays via `PARTIAL_WAKE_LOCK`).

---

## 22. Memory / Product Behavior

Compact samples (host orchestrator reuses N5 working set 3.4 MiB + one source Full bitmap ~50 MB for 4080×3060, still bounded vs 2.3 GB FP32):

- before source ~12 MB, after source ~20 MB, after NPU load ~13 MB, mid NPU ~15 MB, after artifact ~16 MB, mid PNG ~25 MB, after publish ~10 MB, after cleanup ~10 MB, after close ~10 MB
- no 4x Bitmap (`16320*12240*4≈635 MB`) or 4x `ByteArray` ever allocated; RGB8 file grows to 599 MB on disk, heap stays bounded, PSS delta <100 MB, no OOM.

---

## 23. UI Smoke (S24)

Remaster panel shows `ExynosUpscale` status `사용 가능` when NNC present else `phase`, button enabled only `hasImage && !isBusy && canAttempt`, tap → `AI 확대 중 · x/3350` → `PNG 저장 중 · y/12240`, `4080×3060 → 16320×12240`, cancel works, success shows `저장 완료 ...` and `savedExports` contains `KeplerStudio_SR4x_*.png` PNG entry, normal editor remains usable (params/crop still editable).

---

## 24. Evidence

Compact only:

- `docs/exynos-ai/N6_SUPER_RESOLUTION_INTEGRATION.md` (this)
- `artifacts/exynos-n6-s24-2026-08-30/n6_product_e2e.json` (input/output dims, tile counts, PNG rows, URI, NPU proof, memory samples, wake, publish/history, elapsed, display)
- `artifacts/exynos-n6-s24-2026-08-30/n6_cancel_e2e.json`
- `artifacts/exynos-n6-s24-2026-08-30/n6_memory_summary.json` (min/max/deltas)

Not committed: 4x PNG, RGB8 599 MB, `f32le`, giant fixtures, raw logcat, dumps.

---

## 25. Review Answers

1. Can N6 allocate full x4 Bitmap? **NO** — file-backed RGB8 + streaming PNG; `BitmapRegionDecoder` samples only.
2. Can it allocate full x4 ByteArray? **NO** — same.
3. Is SR input exactly current normal-export rendered document? **YES** — `prepareSuperResolutionSourceBitmap()` shares `renderEditedExport` Full semantics (params/crop/selection/quick-effects/correction).
4. Does success mutate document? **NO** — `sourcePath/baseContentToken/revision/preview` unchanged; only `savedExports`/status.
5. Does it add Undo/Redo? **NO**.
6. Does it rewrite Draft generations? **NO** — no `settleAdoptedEditHistory`.
7. Does it use `ModelFeature.ExynosUpscale` truth? **YES** — `ModelAvailabilityRegistry.state[ExynosUpscale]`.
8. Can NPU failure silently fallback as AI 4x? **NO** — `Failure` with `Npu*` kinds, normal export still separate.
9. Is 4x PNG encoded without full output Bitmap? **YES** — `StreamingPngEncoder` row-by-row.
10. Can stale/cancel publish? **NO** — `isCurrent/isCancelled` before `publish`, pending row deleted.
11. Are RGB8 + pending row settled on every pre-publish failure? **YES** — `finally` deletes both.
12. Is publication commit point? **YES** — `publish()` then `history.commit`.
13. Does metadata failure preserve published image? **YES** — `PublishedWithMetadataFailure` without delete.
14. Does user get truthful phase/tile/row progress? **YES** — `SuperResolutionExportProgress` monotonic 0→1.
15. Does display-off avoid screen-on? **YES** — `PARTIAL_WAKE_LOCK` only, `isInteractive false` allowed.
16. Does S24 E2E traverse product path not N5 directly? **YES** — `EditorViewModel` render → `SuperResolutionExportOrchestrator` → N5 → PNG → MediaStore.

---

## 26. Files Changed

- `editor/ExportModels.kt` — `SavedExport` provenance optional 9 fields, backward-compatible 5/14 pipe encoding
- `editor/StreamingPngEncoder.kt` — NEW bounded PNG encoder
- `editor/SuperResolutionExportState.kt` — NEW state/progress/result/identity
- `editor/SuperResolutionExportOrchestrator.kt` — NEW product pipeline (N5+PNG+MediaStore+history+wake+progress)
- `editor/SavedExportHistoryStore.kt` — 14-field provenance encode/decode
- `editor/EditorViewModel.kt` — `superResolutionStatus/flow`, `canStart/export/cancel/isCurrent`, `prepareSuperResolutionSourceBitmap`, document isolation, `isBusy` arbitration
- `ui/RemasterToolPanel.kt` — AI 4배 고해상도 card, status/progress/cancel, PNG-only truth
- `editor/N5WakeLock.kt` — reused `PARTIAL_WAKE_LOCK`
- `androidTest/.../ExynosN6ProductE2ETest.kt` — NEW product E2E 4080×3060 + cancel
- `test/.../StreamingPngEncoderTest.kt` — NEW 9 tests
- `test/.../SuperResolutionExportHostTest.kt` — NEW 12+ tests (matrix)
- `docs/exynos-ai/N6_SUPER_RESOLUTION_INTEGRATION.md` — this
- `artifacts/exynos-n6-s24-2026-08-30/` — compact E2E evidence
- `AndroidManifest.xml` — `WAKE_LOCK` already

---

## 27. Final Gates

N4 REGRESSION: PASS
N5 REGRESSION: PASS (bounded 3350, file-backed, `ATOMIC_MOVE`, guard, `LAST_ONLY`)
N6 PRODUCT INTEGRATION HOST GATE: PASS (state/progress, document isolation, shared Full render, N5 bridge, PNG streaming, MediaStore, cleanup, arbitration)
N6 STREAMING PNG / MEDIASTORE GATE: PASS (IHDR 8/2, CRC, bounded 64 KiB IDAT, row read, `isPending` transaction)
N6 DOCUMENT/HISTORY ISOLATION GATE: PASS (no Draft/Undo, only `savedExports`)
N6 S24 PRODUCT E2E GATE: PASS (S5E9945 4080→16320, PNG region, NPU `OBSERVED`, bounded memory, display-OFF wake)
N6 CANCELLATION/CLEANUP GATE: PASS (cancel before publish → no URI, rgb8/pending deleted, session/wake released)
N6 FINAL GATE: PASS

**N6 PRODUCTION SUPER-RESOLUTION INTEGRATION: PASS**

START HEAD `2dc8a6c178f9e127d718beb19ee7ca22512eb06a` → FINAL HEAD `<this commit>`

STOP. DO NOT START N7.
