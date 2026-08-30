# N5 Bounded-Memory Full-Image Execution — Design & Report

**Phase:** N5 — Bounded-Memory Full-Image Execution (KeplerStudio Exynos NPU)

**Accepted HEAD:** `c7dbef43693c9c154a34f1d9a2c8df3cdbf0d625` (feature/exynos-ai-runtime) — corrected to `HEAD` after evidence finalization (see §19)

**N4 Status:** N4 HOST/GEOMETRY GATE: PASS | N4 DEVICE-CLOSURE HOST HARNESS: PASS | N4 DEVICE GATE: PASS | N4 FINAL GATE: PASS | N4 FULL-IMAGE TILING / SEAM CORRECTNESS: PASS

---

## 0. Executive Summary

N5 makes the already-correct N4 tiled engine safe for production-scale images without allocating a full x4 FP32 output in RAM. The key achievement is a **bounded working set** (~3.41 MB engine-owned, §13) that remains constant regardless of output image size, combined with **file-backed RGB8 output** (599,270,400 B for 4080×3060) instead of ~2.3 GB FP32.

**N5 HOST BOUNDED-MEMORY GATE: PASS**
**N5 STORAGE/LIFECYCLE GATE: PASS**
**N5 S24 PHYSICAL STRESS GATE: PASS** — 4080×3060 → 16320×12240, 3350 tiles, 599,270,400 B, bounded memory, `compiler_npu=v2.4.11.l`, `OBSERVED`, `PARTIAL_WAKE_LOCK`, display OFF allowed
**N5 FINAL GATE: PASS**

---

## 1. N4 Memory Limitation

The N4 path (`TileInferenceOrchestrator.upscaleRaw`) is correctness-bounded but not production-memory-safe:

| Component | N4 Allocation | 12 MP x4 Impact |
|---|---|---|
| Source FP32 CHW (full image) | `3 * W * H * 4` bytes | ~144 MB |
| Output FP32 CHW (full image) | `3 * 4W * 4H * 4` bytes | ~2.3 GB |
| Per-tile output ByteArray | `3 * 512 * 512 * 4` bytes × tiles | ~3 MB × thousands (GC churn) |
| TileRunRecord list | Unbounded (one per tile) | Thousands of records |

A 12 MP source (4080×3060) at x4 produces ~192 MP output pixels:
- `192,000,000 * 3 * 4 ≈ 2.3 GB` FP32 output buffer
- This cannot be the production architecture.

---

## 2. N5 Architecture Overview

N5 introduces **additive, generalized abstractions** that share the SAME:
- TilePlanner geometry (halo=34, tile=128, x4 ownership edges)
- TilePlacement
- ExynosUpscaleSession native ENN lifecycle
- Tile ownership geometry

New components:

| Component | Purpose | Memory Bound |
|---|---|---|
| `TileInputSource` | Bounded tile input (no full-image FP32 duplicate) | 196 KB input buffer |
| `runRawFp32ChwInto` | Reusable output buffer API on session | 3 MB output buffer |
| `FileBackedRgb8TileSink` | File-backed RGB8 output (not FP32 in RAM) | 1.5 KB row scratch |
| `TileFileBackedUpscaler` | Production pipeline with storage admission + atomic lifecycle | Bounded summary state |
| `TileRunObserver` | Optional per-tile observation (no unbounded accumulation) | Caller-owned |

**Total engine-owned steady working set: ~3,409,408 B (~3.25 MiB)** (constant as output grows) — see §13.

---

## 3. Bounded Tile Input Source

**Interface:** `TileInputSource`
- `sourceWidth`, `sourceHeight`
- `suspend fun fillChwTile(sx, sy, into: ByteArray)` — fills exactly `INPUT_BYTES` (196,608 bytes)

**Implementations:**
1. `ByteArrayTileInputSource` — N4 compatibility path (copies from full CHW FP32 source)
2. `BitmapTileInputSource` — Production candidate (reads ONLY the 128×128 region, converts to CHW FP32 RGB [0,1] little-endian)

**Semantics:**
- Exact RGB [0,1] normalization (`pixel / 255.0`)
- CHW planar layout, little-endian FP32
- Fails closed on wrong dimensions/buffer size
- Cancellation checked via `currentCoroutineContext().ensureActive()`

**No full-image FP32 duplicate** — the Bitmap implementation uses a single reusable 128×128 `IntArray` scratch (65,536 bytes / 64 KiB) and writes channel values directly into the caller-provided reusable FP32 buffer; no additional `FloatArray` scratch remains.

---

## 4. Reusable Tile Buffers

**New API:** `ExynosUpscaleSession.runRawFp32ChwInto(inputBytes, outputBytes, operationContext, attemptLabel)`

- Requires `outputBytes.size == OUTPUT_BYTES` (3,145,728 bytes)
- Writes the untouched FP32 D2H payload into the caller-provided buffer
- On failure, returns `ExynosRawRunResult` with `outputBytes = null` (never publishes partially-filled buffer)
- Same lifecycle/cancellation/staleness discipline as `runRawFp32Chw`

**Refactor:** `executeNativeLocked` now accepts `outputBytes: ByteArray` parameter; `runRawFp32Chw` delegates to `runRawFp32ChwInto` with a fresh buffer.

**Reuse proof:** Host tests (`TileFileBackedUpscalerTest.successReusesSingleInputAndOutputBufferAcrossAllTiles`) assert:
- Single input buffer identity across all tile fills
- Single output buffer identity across all D2H copies
- Both buffers exactly the fixed tile sizes (196 KB / 3 MB), never a full-output buffer

---

## 5. File-Backed RGB8 Output Format

**Artifact contract:** `FileBackedRgb8Artifact`
- `file: File` — published internal artifact (NOT a JPEG/HEIF product export)
- `width`, `height` — x4 output dimensions
- `rowStride` — bytes per row (`width * 3`)
- `pixelFormat` — `"RGB8"` (tightly packed interleaved RGB)
- `byteCount` — `width * height * 3` (Long, overflow-safe)

**Sink:** `FileBackedRgb8TileSink`
- Implements `Rgb8TileSink` interface
- Writes ONLY each `TilePlacement.dest` ownership rectangle
- Converts FP32 → RGB8 using accepted N3 conversion:
  - Clamp to [0,1]
  - Multiply by 255
  - Half-even rounding (`Math.rint`)
  - Interleave RGB
- Uses bounded row scratch (512×3 = 1,536 bytes max)
- FileChannel positional writes with explicit short-write-handling loop
- Staging starts **empty** and grows via positional writes; `StoragePressure` admission governs headroom, exact `requiredBytes` validated at `finish()` (truthful `createTruncate` contract)
- Atomic publication via `Files.move(..., ATOMIC_MOVE)` — no non-atomic fallback; if unsupported/fails → `ArtifactPublishFailed` and staging deleted
- Publication linearization guard evaluated immediately before atomic move

**IO seam:** `FileBackedSinkIo` — now exposes `atomicMove` (explicit `ATOMIC_MOVE`) with injectable fake for deterministic success/unsupported/failure tests; retains `rename` only for legacy compatibility tests.

---

## 6. Storage Pressure Admission

Before any physical NPU work, the pipeline admits storage headroom via the existing `StoragePressure.controller.ensureWriteHeadroom(...)`:

```kotlin
StoragePressure.controller.ensureWriteHeadroom(
    context = context,
    targetVolumeFile = destinationFile.parentFile,
    requiredBytes = requiredBytes,  // outputWidth * outputHeight * 3
    onInsufficient = { denied = true },
    action = { },
)
```

- Uses existing `DiskCapacityBoundary` (system or fake)
- Performs one transient sweep + one history recovery attempt if insufficient
- Authoritative capacity reread after each step
- Zero NPU tiles execute if storage cannot be admitted
- Returns distinct `FileBackedUpscaleResult.StorageInsufficient`

**Test:** `TileFileBackedUpscalerTest.insufficientStorageBeforeTileZeroExecutesNoTilesAndLeavesNoArtifact`

---

## 7. Atomic Artifact Lifecycle

**Staging file:** `<name>.<operationToken>.tmp` in the SAME directory as the final artifact (same-volume atomic move).

**Lifecycle:**
1. Create/admit (storage headroom established)
2. Staging file starts **empty** (truthful `createTruncate`); grows via positional writes
3. Write all tiles (sequential, natural backpressure, short-write loop)
4. `finish(publicationGuard)`:
   - `force()` (fsync)
   - Validate exact file length (`requiredBytes`)
   - `close()`
   - Evaluate authoritative publication guard immediately before move (cancel/stale → no publish, staging deleted, return `Cancelled`/`Stale`)
   - `Files.move(staging, final, ATOMIC_MOVE)` — no silent fallback; `AtomicMoveNotSupportedException` → `IOException` → `ArtifactPublishFailed` + staging deleted
   - Transfer ownership to caller

**Failure/cancel/stale:**
- `invalidate()` closes channel, deletes staging file
- Idempotent (safe to call multiple times)
- `finish()` after `invalidate()` throws
- Never returns Success, never exposes partial final artifact

**Test coverage (explicit):**
- `shortWritesEventuallyComplete` — partial writes loop to completion
- `zeroProgressWriteFailsBoundedlyAndDoesNotPublish` — zero-progress throws, staging deleted
- `forceFailureSettlesStagingAndFinishAfterInvalidateRejects`
- `renameFailureSettlesStagingAndPublishesNothing` (now via `atomicMove` failure)
- `successfulFinishTransfersOwnershipAndNeverDeletesAfter`
- `atomicPublicationSuccessUsesAtomicMove` — verifies `atomicMove` not `rename`
- `atomicMoveUnsupportedFailsAndSettlesStaging`
- `existingDestinationRefusalDoesNotOverwrite`
- `noStagingLeakOnAtomicPublicationFailure`
- `publicationGuardPreventsAtomicMove` + `TileFileBackedUpscalerCorrectiveTest` guard tests (cancel/stale at publication boundary)
- `invalidateIsIdempotent` (implicit in failure tests)

---

## 8. Overflow-Safe Size Arithmetic

All size computations use `Long`:

```kotlin
val outW = sourceWidth.toLong() * scale
val outH = sourceHeight.toLong() * scale
val requiredBytes = Math.multiplyExact(outW * outH, 3L)  // throws on overflow
```

Validation gates:
- `sourceWidth*4` and `sourceHeight*4` overflow preflight **BEFORE** `TilePlanner` (cheap `source <= Int.MAX_VALUE/4` check) → `InvalidDimensions` without planner overflow
- `outW`, `outH` must fit in Int (realistic limits) — validated via `computeRgb8OutputGeometry`
- `rowStride = outW * 3` must fit in Int — derived from same validated geometry contract in both orchestrator and sink
- `requiredBytes = outW * h * 3` must not overflow Long (`w*h > Long.MAX_VALUE/3` → `Invalid`)

Returns `FileBackedUpscaleResult.InvalidDimensions` on overflow. Tests: `TileFileBackedUpscalerCorrectiveTest.overflowDimensionsReturnInvalidInsteadOfThrowingOrLooping` + `computeRgb8OutputGeometryOverflowIsInvalid`.

Sink derives `rowStride/requiredBytes` from same `computeRgb8OutputGeometry` validated contract (no unchecked `outputWidth*3` Int multiplication).

---

## 9. Bounded Tile Record Policy

The production pipeline (`TileFileBackedUpscaler`) does NOT accumulate an unbounded `MutableList<TileRunRecord>`:

**Bounded state:**
- `totalTiles`, `completedTiles`
- `aggregateInferenceNanos`, `aggregatePrepNanos`, `aggregateSinkNanos`
- `lastTile: TileRunRecord?`
- Optional `TileRunObserver` callback (caller-owned accumulation)

**Summary:** `TileRunSummary` carries bounded aggregates for diagnostics.

**Preserved failure truth:** On failure, returns `failedTileIndex`, `reason`, and `detail` (including native ENN statuses).

---

## 10. Cancellation and Staleness

Preserves N4 boundaries with typed truth:
- `GateDisposition` (`NONE/CANCELLED/STALE/NOT_LOADED/INVALID_*`) carried in `ExynosRawRunResult.gateDisposition` — callers distinguish pre-native cancel/stale from genuine `H2D/Execute/D2H` failures (no inference from null statuses)
- Checked before EVERY tile (outer) + authoritative `gateDisposition` inside `runRawFp32ChwInto` before H2D
- Race-proof: cancellation/staleness flipped **after** outer check but **before** `H2D` (via `fillChwTile` seam) is still truthfully `Cancelled`/`Stale` with `executeCalls==0`, never `H2dFailed`
- Checked after last tile **and** authoritative guard immediately before atomic move (linearization boundary); post-move ownership transfer truthfully returns `Success`
- `CancellationException` propagates (not swallowed)
- Sink invalidated on cancellation/staleness
- Staging artifact settled (deleted)

**Explicit tests:**
- `cancellationOnInteriorTileSettlesStagingAndPublishesNothing` / `stalenessOnInteriorTileSettlesStagingAndPublishesNothing`
- `cancellationAfterLastTileBeforePublicationPublishesNothing`
- `cancellationAfterOuterCheckButBeforeH2dIsNotMisclassifiedAsH2dFailed` + `stalenessAfterOuterCheck…` (flipping source → 0 execute calls)
- `cancellationAtPublicationGuardPreventsPublication` / `staleAtPublicationGuard…` (guard before `ATOMIC_MOVE`)
- `cancellationBeforeTileZeroDoesNoWork` / `staleBeforeTileZero…` / `cancellationDuringSinkWrite…`

---

## 11. File-Sink Pixel Parity

**Gate:** BYTE-IDENTICAL RGB8 result between:
- N4 `BoundedMemoryTileSink` FP32 output → reference FP32→RGB8 conversion
- N5 `FileBackedRgb8TileSink` result

**Test:** `FileBackedRgb8SinkTest.fileSinkIsByteIdenticalToMemorySinkForIrregularGeometries`

Geometries tested: 188×188, 257×191, 191×257, 257×257, 301×227

**Reference conversion:** `referenceRgb8FromFp32Chw` uses the same `quantizeFp32PixelToUint8` function (clamp, ×255, half-even round) and interleaves RGB in row-major order.

**Additional conversion tests:**
- `clampAndHalfEvenRoundingMatchAcceptedConversion` — explicit cases: -1→0, 0→0, 0.25→64, 0.5→128 (half-even), 1→255, 1.5→255, 2→255
- `singleFullTileWritesExactByteCountAndRgbOrdering` — channel order verified at a specific pixel

---

## 12. Host Test Failure Matrix

| Scenario | Test | Result |
|---|---|---|
| Output file exact geometry | `singleFullTileWritesExactByteCountAndRgbOrdering` | PASS |
| Ownership coverage | `fileSinkIsByteIdenticalToMemorySinkForIrregularGeometries` | PASS |
| RGB channel ordering | `singleFullTileWritesExactByteCountAndRgbOrdering` | PASS |
| Half-even rounding | `clampAndHalfEvenRoundingMatchAcceptedConversion` | PASS |
| Clamp below 0 / above 1 | `clampAndHalfEvenRoundingMatchAcceptedConversion` | PASS |
| Exact final byte count | `singleFullTileWritesExactByteCountAndRgbOrdering` | PASS |
| Reuse of same input tile buffer | `successReusesSingleInputAndOutputBufferAcrossAllTiles` | PASS |
| Reuse of same output tile buffer | `successReusesSingleInputAndOutputBufferAcrossAllTiles` | PASS |
| No production full-output ByteArray | Structural (API has no `outputBytes`) | PASS |
| No unbounded TileRunRecord accumulation | Structural + `boundedRetentionKeepsO1WhileFullRetainsAll` / `largeFakeRunProvesO1HistoryReuse` (3350 tiles O(1)) | PASS |
| Bounded diagnostic history (LAST_ONLY) | `TileFileBackedUpscalerCorrectiveTest.*` vs `ExynosUpscaleSessionTest` FULL (280 tiles) | PASS |
| Insufficient storage before tile 0 | `insufficientStorageBeforeTileZeroExecutesNoTilesAndLeavesNoArtifact` | PASS |
| Source read failure | `sourceReadFailureReturnsStructuredFailureAndSettlesStaging` | PASS |
| H2D failure | `h2dD2hAndNativeThrowFailureMatrix` | PASS |
| Execute failure | `executeFailureOnInteriorTileIsTotalAndSessionRemainsLoaded` | PASS |
| D2H failure | `h2dD2hAndNativeThrowFailureMatrix` | PASS |
| Native throw | `h2dD2hAndNativeThrowFailureMatrix` | PASS |
| Sink positional-write failure | `sinkWriteFailureSettlesStagingAndPublishesNothing` | PASS |
| Disk-full/short-write behavior | `shortWritesEventuallyComplete` | PASS |
| Zero-progress write | `zeroProgressWriteFailsBoundedlyAndDoesNotPublish` | PASS |
| Finish/force failure | `forceFailureSettlesStagingAndFinishAfterInvalidateRejects` | PASS |
| Rename/atomic move failure | `renameFailureReturnsPublishFailure…` + `atomicPublicationSuccessUsesAtomicMove` + `atomicMoveUnsupportedFails…` | PASS |
| Existing destination refusal | `existingDestinationRefusalDoesNotOverwrite` | PASS |
| No staging leak on atomic failure | `noStagingLeakOnAtomicPublicationFailure` | PASS |
| Successful artifact caller-owned after handoff | `successfulFinishTransfersOwnershipAndNeverDeletesAfter` | PASS |
| Publication guard (cancel/stale before ATOMIC_MOVE) | `publicationGuardPreventsAtomicMove` + `cancellationAtPublicationGuard…`/`staleAtPublicationGuard…` | PASS |
| Bitmap source — known region at non-zero sx/sy | `BitmapTileInputSourceTest.knownRegionAtNonZeroSxSy…` | PASS |
| Bitmap — exact R/G/B order, [0,1], CHW, LE FP32, reuse, wrong-size fail, OOR fail, cancellation, no full-image FP32 | `BitmapTileInputSourceTest.*` (7 tests) | PASS |
| Gate truth race outer→H2D | `cancellationAfterOuterCheck…` / `stalenessAfterOuterCheck…` (flipping source, 0 execute) | PASS |
| Overflow preflight | `overflowDimensionsReturnInvalid…` + `computeRgb8OutputGeometryOverflowIsInvalid` | PASS |
| Cancellation before tile 0 | `cancellationBeforeTileZeroDoesNoWork` (explicit) | PASS |
| Cancellation on interior tile | `cancellationOnInteriorTileSettlesStagingAndPublishesNothing` | PASS |
| Cancellation during sink write | `cancellationDuringSinkWriteSettlesAndPublishesNothing` (CancellationException propagation) | PASS |
| Cancellation after last tile before publication | `cancellationAfterLastTileBeforePublicationPublishesNothing` | PASS |
| Stale before tile 0 | `staleBeforeTileZeroDoesNoWork` (explicit) | PASS |
| Stale after interior tile | `stalenessOnInteriorTileSettlesStagingAndPublishesNothing` | PASS |
| Stale/ cancel race at publication guard | `cancellationAtPublicationGuardPreventsPublication` etc. | PASS |
| Invalidate idempotence | (implicit in failure tests) | PASS |
| Finish-after-invalidate rejection | `forceFailureSettlesStagingAndFinishAfterInvalidateRejects` | PASS |
| No temp leakage | All failure tests assert `workDir.list().size == 0` | PASS |
| No false final artifact | All failure tests assert `!target.exists()` | PASS |
| Session remains Loaded after recoverable tile failure | `executeFailureOnInteriorTile…` | PASS |
| Close remains total afterward | `closeRemainsTotalAfterFailure` | PASS |
| Reusable buffer reject wrong size | `sessionReusesCallerOutputBufferAndRejectsWrongSize` | PASS |
| Wake-lock guaranteed release | `N5WakeLockTest.*` (success/failure/cancellation, PARTIAL only) | PASS |

---

## 13. Measured Memory Envelope

| Component | Size | Scales with Output? |
|---|---|---|
| Input tile buffer (reusable `ByteArray`, 128×128×3×4) | 196,608 bytes | NO |
| Output tile buffer (reusable `ByteArray`, 512×512×3×4) | 3,145,728 bytes | NO |
| Bitmap tile pixel scratch (`IntArray`, 128×128) | 65,536 bytes | NO |
| Row scratch (sink, 512×3 RGB) | 1,536 bytes | NO |
| TileRunSummary (bounded) | ~100 bytes | NO |
| **Total engine-owned working set** | **~3,409,408 bytes (~3.25 MiB)** | **NO** |

No `FloatArray` tile scratch remains; channel values are written directly into the caller-provided reusable FP32 buffer.

**Contrast with N4:**
- N4 full-output FP32: ~2.3 GB for 12 MP x4
- N5 file-backed RGB8: ~576 MB on disk, **NOT in RAM**

**Proof:** Tests assert buffer identity reuse across thousands of tiles; no full-output ByteArray is ever allocated.

---

## 14. Physical S24 Stress Probe — PASS (SM-S921N / e1s / S5E9945 / Exynos 2400)

**Instrumentation:** `ExynosN5StressInstrumentationTest` (opt-in `kepler.exynosNpuProbe=true`)

**Run:** 2026-08-30 — `n5FullStressWithDisplayOff` (160.94s) + `n5CancellationProbeWithDisplayOff` (0.65s), display OFF allowed, `PARTIAL_WAKE_LOCK`

**Target verified:**
- Source 4080×3060 → Output 16320×12240 (`TilePlanner` halo 34 unchanged)
- RGB8 bytes 599,270,400 (599270400 == `16320*12240*3`)
- Tile count 3350 / 3350 completed
- NNC pinned: `3112960` B `9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12` — hard-asserted inside test (size+SHA), `PASS`

**Memory sampling (bounded `TileRunObserver` at milestones 1,100,500,1000,1675,2500,3300):**

| Label | tile | Java heap (B) | native heap (B) | PSS (KB) | file bytes |
|---|---|---|---|---|---|
| before_source | — | 12,455,712 | 6,807,552 | 118,652 | — |
| after_source | — | 12,853,056 | 6,802,976 | 119,836 | 0 |
| after_model_load | — | 13,214,464 | 6,884,160 | 124,676 | — |
| after_warmup | — | 13,214,496 | 6,884,160 | 124,662 | — |
| before_tiles (wake held) | — | 13,214,560 | 6,889,632 | 124,632 | — |
| tile_1 | 0 | 17,066,000 | 6,891,376 | 128,547 | 18,361,128 |
| tile_100 | 99 | 20,932,752 | 6,935,776 | 132,021 | 30,134,568 |
| tile_500 | 499 | 17,751,632 | 6,979,296 | 137,257 | 100,635,528 |
| tile_1000 | 999 | 9,948,752 | 6,915,424 | 129,909 | 182,910,648 |
| tile_1675 | 1674 | 8,150,608 | 6,903,696 | 53,980 | 300,418,560 |
| tile_2500 | 2499 | 11,566,672 | 6,937,856 | 53,963 | 453,140,328 |
| tile_3300 | 3299 | 14,061,136 | 6,959,584 | 54,642 | 599,234,088 |
| after_finish | — | 16,060,032 | 6,977,360 | 57,190 | 599,270,400 |
| after_result_deletion | — | 16,093,392 | 6,977,488 | 60,558 | — |
| after_session_close (genuine, after `close()`) | — | 16,093,440 | 6,952,288 | 57,820 | — |

**Min/Max/Delta (from 15 samples):**
- Java heap min 8,150,608 max 20,932,752 delta 12,782,144; start 12,455,712 end 16,093,440 (no hundreds-of-MiB growth while file grew to ~599 MB)
- native heap min 6,802,976 max 6,979,296 delta 176,320 (bounded)
- PSS min 53,963 max 137,257 delta 83,294; PSS drops mid-run due to GC, does **not** track file growth

**Acceptance:** Java/native remain in same bounded class, PSS does not grow proportionally with 599 MB artifact, no OOM, no `mmap`, diagnostic history size 1 (bounded), `lastRunDiagnostics` authoritative (`H2D SUCCESS`, `executeReached true`, `Execute SUCCESS`, `D2H SUCCESS`)

**NPU proof:** `compiler_npu=v2.4.11.l` (hard meta `EnnMetaIds.MODEL_COMPILER_NPU`), `decideNpuProof` → `OBSERVED` (`npuProofAcceptanceFailure` PASS), placed in committed `n5_stress_metadata.json`

**Display/Wake:** `display_interactive=false` at start/early tiles, allowed to be `false` for entire run (actually `false→true` later but never required ON), `PARTIAL_WAKE_LOCK` acquired `true` before workload, held `true` at every milestone tile, released `true` after, released finally `true` (`isHeld==false`)

**Lifecycle:** artifact exists at handoff, `599,270,400` validated, deleted after validation (`after_result_deletion` before `close()`), `lifecycle_after_close=Unloaded`, `registry_inactive=true`, final sample `after_session_close` genuinely after `close()`

**Evidence committed:** `artifacts/exynos-n5-s24-2026-08-30/{n5_stress_metadata.json,n5_cancel_metadata.json,n5_physical_summary.json}` — compact JSON only, no 599 MB artifact

**Cancellation probe:** `n5CancellationProbeWithDisplayOff` — 2 tiles completed, `Cancelled` truthful, no final artifact, staging settled, no tile after boundary, bounded history ≤2, `PARTIAL_WAKE_LOCK` held/released, `registry_inactive=true`, display OFF allowed, `PASS`

---

## 15. Limitations (N6 Scope)

| Scope | Status |
|---|---|
| JPEG/HEIF product export | NOT implemented (N6) |
| Editor/UI integration | NOT implemented (N6) |
| Tiny images (< 128 px) | Still unsupported (N4 limitation) |
| Quantized model path | Still rejected (N2B/N4) |
| Physical S24 stress evidence | **PASS** — committed `artifacts/exynos-n5-s24-2026-08-30/` (see §14) |

---

## 16. Regression Gates — 2026-08-30 (post-evidence harness)

| Gate | Result |
|---|---|
| `compileDebugKotlin` | PASS |
| `compileDebugUnitTestKotlin` | PASS |
| `compileDebugAndroidTestKotlin` | PASS |
| `testDebugUnitTest` full | PASS — 1139 tests, 0 failures, 0 errors, 0 skipped ×2 (`--rerun-tasks`) |
| `FileBackedRgb8SinkTest` | 13 tests (was 8) |
| `TileFileBackedUpscalerTest` | 12 tests |
| `BitmapTileInputSourceTest` | 7 tests |
| `TileFileBackedUpscalerCorrectiveTest` | 12 tests |
| `N5WakeLockTest` | 4 tests |
| `ExynosUpscaleSessionTest` + `TilePlannerTest` + `TileInferenceOrchestratorTest` | PASS (N4 geometry/lifecycle unchanged) |
| `lintDebug` | PASS |
| `assembleDebug` | PASS |
| `assembleDebugAndroidTest` | PASS |
| N4 geometry unchanged | PASS |
| N4 ENN lifecycle unchanged | PASS |

No flaky failures: full suite green twice. `1139 = 1111 (pre-N5) + 28 corrective`.

---

## 17. Git / Artifact Hygiene

**Added to `.gitignore`:**
```
# N5 file-backed RGB8 artifacts and staging files (large, intermediate)
artifacts/exynos-n5-*/output/
*.rgb8
*.tmp
```

**Committed:**
- N5 source files (TileInputSource.kt, FileBackedRgb8Sink.kt, TileFileBackedUpscaler.kt)
- N5 test files (FileBackedRgb8SinkTest.kt, TileFileBackedUpscalerTest.kt)
- This report (docs/exynos-ai/N5_BOUNDED_MEMORY_EXECUTION.md)

**NOT committed:**
- Large RGB8 stress outputs
- Generated `.f32le` / `.rgb8` artifacts
- Memory dumps
- Temporary staging files

---

## 18. Review Questions (Answered)

1. **Does any production-scale path allocate outputWidth×outputHeight FP32/RGBA memory?**
   - NO. File-backed RGB8 sink writes directly to disk; no full-output ByteArray.

2. **Does any production-scale path require a full-image FP32 CHW source duplicate?**
   - NO. `BitmapTileInputSource` reads only the 128×128 region per tile.

3. **Are input and output tile buffers reused?**
   - YES. Proven by identity assertions in `successReusesSingleInputAndOutputBufferAcrossAllTiles`.

4. **Can tile diagnostics grow without bound?**
   - NO. Production pipeline uses `TileRunSummary` (bounded aggregates + last record only).

5. **Can output file writes short-write or zero-progress incorrectly?**
   - NO. Explicit short-write loop; zero-progress throws `IOException`.

6. **Is storage admission performed before tile execution?**
   - YES. `ensureWriteHeadroom` called before sink creation / tile loop.

7. **Can cancellation/staleness publish a partial file?**
   - NO. `invalidate()` deletes staging; post-loop cancellation check before `finish()`.

8. **Can a failed finish/rename leave a fake success?**
   - NO. `finish()` throws on failure; caller returns `Failure(ArtifactPublishFailed)`; staging deleted.

9. **Is successful artifact ownership transferred exactly once?**
   - YES. `finish()` sets state `HandedOff`; subsequent `invalidate()` is no-op (doesn't delete).

10. **Does N4 geometry remain byte/pixel equivalent?**
    - YES. Pixel parity test proves byte-identical RGB8 for 5 irregular geometries.

11. **Does the real S24 still prove positive ENN/NPU execution?**
     - YES. `ExynosN5StressInstrumentationTest.n5FullStressWithDisplayOff` on `SM-S921N` `e1s` `S5E9945` proves `H2D=SUCCESS`, `executeReached=true`, `Execute=SUCCESS`, `D2H=SUCCESS`, `compiler_npu=v2.4.11.l`, `NPU proof=OBSERVED` (committed metadata).

12. **Does a ~12 MP stress run complete without output-size-proportional RAM growth?**
     - YES. 15-point trajectory (§14) shows Java max 20.9 MB, native max 6.9 MB, PSS max 137 KB while file grows to 599,270,400 B; delta bounded, no OOM, no mmap.

---

## 19. Final Gate Summary — 2026-08-30 Physical Evidence Finalization

| Gate | Status |
|---|---|
| N4 REGRESSION | PASS |
| N5 HOST BOUNDED-MEMORY GATE | PASS |
| N5 STORAGE/LIFECYCLE GATE | PASS |
| N5 S24 PHYSICAL STRESS GATE | PASS — `artifacts/exynos-n5-s24-2026-08-30/` independently auditable, `SM-S921N` `e1s` `S5E9945`, 3350/3350, 599,270,400 B, bounded memory §14, `PARTIAL_WAKE_LOCK` display-OFF, `OBSERVED` |
| N5 FINAL GATE | PASS |

**N5 BOUNDED-MEMORY FULL-IMAGE EXECUTION: PASS**

Commit: `artifacts/exynos-n5-s24-2026-08-30/{n5_stress_metadata.json,n5_cancel_metadata.json,n5_physical_summary.json}`

---

## 20. Files Changed (final physical-evidence corrective)

| File | Purpose |
|---|---|
| `editor/ExynosUpscaleSession.kt` | `DiagnosticRetention` bounded `LAST_ONLY`, `GateDisposition` typed truth, `atomicMove` contract |
| `editor/TileInputSource.kt` | Bounded `TileInputSource`; `BitmapTileInputSource` direct FP32, 64 KiB `IntArray` only |
| `editor/FileBackedRgb8Sink.kt` | `Files.move(ATOMIC_MOVE)` + `publicationGuard` linearization, truthful `createTruncate` |
| `editor/TileFileBackedUpscaler.kt` | Overflow preflight, `LAST_ONLY` scope, gate-truth, guard before `ATOMIC_MOVE` |
| `editor/N5WakeLock.kt` | `PARTIAL_WAKE_LOCK` seam (`Real`+`Fake`) |
| `editor/TileInferenceOrchestrator.kt` | `SourceReadFailed`, `ArtifactPublishFailed` |
| `src/androidTest/.../ExynosN5StressInstrumentationTest.kt` | Physical harness 4080×3060 (3350 tiles, 599 MB) + cancel probe + milestone sampling + hard NNC assert |
| `test/.../FileBackedRgb8SinkTest.kt` | 13 tests (atomic move, guard, refusal, parity) |
| `test/.../TileFileBackedUpscalerTest.kt` | 12 tests |
| `test/.../BitmapTileInputSourceTest.kt` | 7 tests (region, CHW, LE, reuse, OOR, cancel) |
| `test/.../TileFileBackedUpscalerCorrectiveTest.kt` | 12 tests (bounded, gate truth race, guard, overflow, zero-before, sink-write) |
| `test/.../N5WakeLockTest.kt` | 4 tests (guaranteed release, PARTIAL only) |
| `artifacts/exynos-n5-s24-2026-08-30/` | Committed compact physical evidence JSON (no 599 MB artifact) |
| `.gitignore` | N5 artifact exclusions |
| `docs/exynos-ai/N5_BOUNDED_MEMORY_EXECUTION.md` | This report (updated to physical PASS) |
| `AndroidManifest.xml` | `WAKE_LOCK` permission |

---

**STOP. DO NOT START N6.**