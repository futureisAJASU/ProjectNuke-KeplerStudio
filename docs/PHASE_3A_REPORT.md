# Phase 3A: Debug-Only Memory Ownership & Peak-Allocation Map

**Status:** Instrumented (debug-only, zero release overhead)
**Scope:** EditorViewModel active production editor paths
**Date:** 2026-07-23

---

## 1. Changed Files

| File | Change |
|------|--------|
| `app/src/main/kotlin/com/projectnuke/keplerstudio/editor/DebugMemoryTracker.kt` | **New.** Core debug-only tracking infrastructure: identity-based Bitmap registry, operation tokens, native session state, bounded operation log, resident snapshot API. Guarded by `BuildConfig.DEBUG` — compiler eliminates all code in release builds. |
| `app/src/main/kotlin/com/projectnuke/keplerstudio/editor/MemoryTrackerScope.kt` | **New.** Convenience scope wrapper (`MemoryTrackerScope`) for begin/end operation tracking, bitmap track/release helpers, and `BitmapOwnershipInspector` for live UI-state bitmap inspection. |
| `app/src/main/kotlin/com/projectnuke/keplerstudio/editor/EditorViewModel.kt` | **Modified.** Instrumented: `openImage`, `updateParams`, `applyAutoEnhance`, `applyPresetLook`, `applyNativeSpecialEffects`, `applyFlareGuardAiOrRulePreview`, `navigateHistory` (undo/redo), `restoreCurrentDraftGeneration`, `restoreDraftIfAvailable` (legacy), `persistDraftSnapshotInternal`, `exportPreview`, `createBrushSelectionInternal`, `rotatePreview90`, `releaseNativeSessionHandle`, `onCleared`. Added `debugResidentOwnership()` inspection endpoint. |
| `app/src/main/kotlin/com/projectnuke/keplerstudio/editor/ThumbnailBitmapCache.kt` | **Modified.** Added tracking on `lease()` (register) and `release()` (unregister). |

---

## 2. Active Callers Inspected

All callers traced through static analysis of `EditorViewModel.kt` (5367 lines), `EditorHistoryStorage.kt` (1049 lines), `DraftGenerationStorage.kt` (307 lines), `NativePhotoCore.kt` (203 lines), `RemasterModelSession.kt` (310 lines), `FlareGuardBridgeV0.kt` (207 lines), `FlareGuardModelRunner.kt` (279 lines), `CropBitmapTransform.kt` (50 lines), `ThumbnailBitmapCache.kt` (207 lines), `BitmapMemoryBudget.kt` (116 lines).

### Operations traced:

| # | Operation | Entry point | Acquisition point | Transfer point | Release point |
|---|-----------|-------------|-------------------|----------------|---------------|
| 1 | Open image | `EditorViewModel.openImage()` | `decodeSampledMutableBitmapWithExif()` → `preview` | `_uiState.value = nextState` (originalPreviewBitmap/previewBitmap) | `releaseOrphanedBitmaps()` on next state change; `releaseNativeSessionHandle()` |
| 2 | Parameter rendering | `EditorViewModel.updateParams()` | `basePreview.copyOrThrow()` → `ownedBase`; `renderEditedPreview()` → `rendered` | `updateUiStateAndRecycleReplaced { previewBitmap = adopted }` | `finally { ownedBase.recycle(); rendered.recycle() }` |
| 3 | Auto Enhance | `EditorViewModel.applyAutoEnhance()` | Same pattern as updateParams | Same | Same |
| 4 | Preset application | `EditorViewModel.applyPresetLook()` | Same pattern | Same | Same |
| 5 | Quick effects | `EditorViewModel.applyNativeSpecialEffects()` | Same pattern | Same | Same |
| 6 | FlareGuard | `EditorViewModel.applyFlareGuardAiOrRulePreview()` | `ownedBase` copy; `applyFlareGuardModelOrRuleResultV0()` → `flareGuardBitmap`; `renderEditedPreview()` → `renderedPreview` | `originalPreviewBitmap = adoptedOriginal; previewBitmap = adoptedPreview` | `finally { flareGuardBitmap.recycle(); renderedPreview.recycle(); ownedBase.recycle() }` |
| 7 | Crop apply | `renderCropTransform()` (CropBitmapTransform.kt) | `createBitmapOrThrow()` → `output` | Returned to caller | Caller recycles or adopts |
| 8 | Selection creation | `EditorViewModel.createBrushSelectionInternal()` | `createBitmapOrThrow()` → `mask` | `SelectionLayer(bitmap = mask)` in UI state | `releaseOrphanedBitmaps()` |
| 9 | Selection bake | `applyActiveSelectionLocalEditNativeBaked()` (via UI) | Native blend in-place on preview | N/A (in-place) | N/A |
| 10 | Remaster | `RemasterModelSession.createForegroundMask()` | `bitmap.copyOrThrow()` → `inputCopy`; `createBitmapOrThrow()` → `out` | Returned to caller | Caller recycles |
| 11 | Undo/Redo | `EditorViewModel.navigateHistory()` → `historyCoordinator.navigate()` | `captureHistorySnapshotForNavigation()` → `currentSnapshot`; `storage.load()` → cold decode; `materializeHistorySnapshot()` → `rendered` | `applyHistorySnapshot()` → UI state | `snapshot.releaseBitmapOwnership()`; `recycleBitmaps()` for unreleased |
| 12 | Draft save | `EditorViewModel.persistDraftSnapshotInternal()` → `createDraftSavePayload()` | `copyOwned()` → `dirtyBitmapCopy`, `editedPreviewCopy`, `selectionLayers[].bitmap` | Passed to `saveDraftSnapshot()` → written to disk | `payload.recycleOwnedBitmaps()` |
| 13 | Draft restore | `EditorViewModel.restoreCurrentDraftGeneration()` | `decodeSampledMutableBitmapWithExif()` → `ownedBase`; `renderEditedPreview()` → `ownedRendered`; `decodeMutableBitmapOrThrow()` → `ownedMasks[]`; `nativeCreateSession()` → `createdSession` | `_uiState.value = nextState` | `finally { ownedBase.recycle(); ownedRendered.recycle(); ownedMasks.recycle(); releaseNativeSessionHandle() }` |
| 14 | Preview export | `EditorViewModel.exportPreview()` | `liveBase.copyOrThrow()` → `ownedDirtyBase` (if dirty); `renderEditedExportFromBitmap()` → `ownedExportResult` | Written to MediaStore | `finally { ownedExportResult.recycle(); ownedDirtyBase.recycle() }` |
| 15 | Full export | `renderEditedExport()` | `decodeSampledMutableBitmapWithExif()` → `decoded`; `scaleBitmapForExport()` → `scaled` | Written to MediaStore | `finally { scaled.recycle(); decoded.recycle() }` |
| 16 | Thumbnail cache | `ThumbnailBitmapCache.acquire()` | `flight.decode()` → `Entry(bitmap)` | Leased via `ThumbnailBitmapLease` | `release()` → `recycleIfUnusedLocked()` |

---

## 3. Ownership Map Summary

### 3.1 Bitmap Ownership Graph

```
┌─────────────────────────────────────────────────────────────────────┐
│                        EditorUiState (StateFlow)                      │
│                                                                     │
│  originalPreviewBitmap ──acquired── openImage / restoreDraft        │
│                            / resetAdjustments                       │
│  previewBitmap ───────────acquired── render operations              │
│                            / rotatePreview90                        │
│  selectionLayers[].bitmap ─acquired── createBrushSelection /        │
│                            restoreDraft / navigateHistory           │
│                                                                     │
│  ┌─ releaseOrphanedBitmaps() ── recycles displaced bitmaps ───────┐ │
│  │  (identity-deduplicated via IdentityHashMap)                    │ │
└─────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    EditorHistorySnapshot (hot)                      │
│                                                                     │
│  previewBitmap, originalPreviewBitmap, selectionLayers[].bitmap      │
│  ┌─ acquired ─ captureCurrentHistorySnapshot() /                   │
│  │            materializeHistorySnapshot()                         │
│  ├─ transfer ─ admitAdoptedSnapshot() / applyHistorySnapshot()      │
│  └─ release ─ recycleBitmaps() / releaseBitmapOwnership()           │
│                                                                     │
│  Cold payload: on disk (ColdHistoryPayload)                         │
│  ┌─ acquired ─ storage.publish()                                     │
│  ├─ transfer ─ storage.load() → hot decode                         │
│  └─ release ─ storage.deleteEntries() / settleColdEntries()         │
└─────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                      DraftSavePayload                                │
│                                                                     │
│  dirtyBitmapCopy, editedPreviewCopy, selectionLayers[].bitmap        │
│  ┌─ acquired ─ createDraftSavePayload() (copyOwned)                 │
│  ├─ transfer ─ writeDraftGeneration() (compress to disk)            │
│  └─ release ─ payload.recycleOwnedBitmaps()                         │
└─────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   Native Session (Long handle)                       │
│                                                                     │
│  nativeSession field in EditorViewModel                              │
│  ┌─ acquired ─ NativePhotoCore.nativeCreateSession()                 │
│  │            (openImage, restoreDraft, restoreCurrentDraftGen)    │
│  ├─ state ─ active / created / restored                              │
│  └─ release ─ releaseNativeSessionHandle() / releaseNativeSession() │
│               (onClear, onTrimMemory, openImage replacement)         │
└─────────────────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    ThumbnailBitmapCache                                │
│                                                                     │
│  Entry.bitmap (bounded by maxBytes, LRU)                             │
│  ┌─ acquired ─ acquire() → startDecode() → flight.decode()         │
│  ├─ transfer ─ leased via ThumbnailBitmapLease                       │
│  └─ release ─ ThumbnailBitmapLease.close() → release()              │
│               → recycleIfUnusedLocked()                             │
└─────────────────────────────────────────────────────────────────────┘
```

### 3.2 Ownership Table

| Bitmap | Owner | Acquisition | Transfer | Release | May alias live UI Bitmap? |
|--------|-------|-------------|----------|---------|--------------------------|
| `originalPreviewBitmap` | EditorUiState | openImage decode / restoreDraft decode / resetAdjustments decode | `_uiState.value = nextState` | `releaseOrphanedBitmaps()` | Yes (when previewBitmap === originalPreviewBitmap) |
| `previewBitmap` | EditorUiState | renderEditedPreview / rotateBitmap90 / applyHistorySnapshot | `_uiState.value = nextState` | `releaseOrphanedBitmaps()` | Yes (may alias originalPreviewBitmap) |
| `selectionLayers[].bitmap` | EditorUiState | createBitmapOrThrow / restoreDraft decode / navigateHistory load | `SelectionLayer(bitmap)` in state | `releaseOrphanedBitmaps()` | No (always distinct) |
| History snapshot bitmaps | EditorHistoryEntry | `toHistorySnapshot()` copies / `materializeHistorySnapshot()` render | `admitAdoptedSnapshot()` / `applyHistorySnapshot()` | `recycleBitmaps()` / `releaseBitmapOwnership()` | No (copies) |
| Draft payload bitmaps | DraftSavePayload | `createDraftSavePayload()` copies | `writeDraftGeneration()` compress to disk | `recycleOwnedBitmaps()` | No (copies) |
| `ownedBase` (transient) | Operation scope | `basePreview.copyOrThrow()` | N/A (local) | `finally { recycle() }` | No (copy) |
| `rendered` (transient) | Operation scope | `renderEditedPreview()` | `previewBitmap = adopted` (if current) | `finally { recycle() }` or adopted | Yes (if adopted into UI state) |
| `flareGuardBitmap` (transient) | FlareGuard operation | `applyFlareGuardModelOrRuleResultV0()` | `originalPreviewBitmap = adoptedOriginal` (if current) | `finally { recycle() }` or adopted | Yes (if adopted) |
| `ownedDirtyBase` (transient) | Export operation | `liveBase.copyOrThrow()` | N/A (local) | `finally { recycle() }` | No (copy) |
| `ownedExportResult` (transient) | Export operation | `renderEditedExportFromBitmap()` / `renderEditedExport()` | Written to MediaStore | `finally { recycle() }` | No (local) |
| Native session | EditorViewModel | `nativeCreateSession()` | `nativeSession = createdSession` | `releaseNativeSessionHandle()` | N/A (native handle) |
| Thumbnail cache bitmaps | ThumbnailBitmapCache | `flight.decode()` | Leased via `ThumbnailBitmapLease` | `release()` → `recycleIfUnusedLocked()` | No (cache-managed) |

### 3.3 Hot/Cold State

| Component | Hot state | Cold state | Spill trigger | Load trigger |
|-----------|-----------|------------|---------------|--------------|
| History entries | `hotSnapshot` in RAM | `coldPayload` on disk | `spillEntry()` when hot budget exceeded | `navigate()` when target is cold |
| Draft | In-memory payload (transient) | On disk (generation directory) | `writeDraftGeneration()` | `restoreCurrentDraftGeneration()` |
| Thumbnail cache | `Entry.bitmap` in RAM | Not cached | `evictLocked()` when over budget | `acquire()` |
| Native session | Active handle | N/A (no cold state) | `releaseNativeSession()` on trim/idle | `nativeCreateSession()` |

---

## 4. Highest Estimated Peak Paths

All estimates assume a 12MP image (4000×3000, ~48MB ARGB_8888 per full-size bitmap).

### 4.1 Peak Path Table

| Operation | Simultaneous resident bitmaps | Est. peak bytes | Notes |
|-----------|-------------------------------|-----------------|-------|
| **FlareGuard preview** | originalPreviewBitmap + previewBitmap + ownedBase + flareGuardBitmap + renderedPreview = 5× full-size | ~240MB | Highest peak. flareGuardBitmap is full-size even though model input may be smaller. |
| **Parameter rendering** | originalPreviewBitmap + previewBitmap + ownedBase + rendered = 4× full-size | ~192MB | ownedBase is a copy of originalPreviewBitmap; rendered replaces previewBitmap on success. |
| **Auto Enhance** | Same as parameter rendering | ~192MB | Same pattern: ownedBase + rendered + existing UI state. |
| **Preset application** | Same as parameter rendering | ~192MB | Same pattern. |
| **Quick effects** | Same as parameter rendering | ~192MB | Same pattern. |
| **Export (dirty base)** | originalPreviewBitmap + previewBitmap + ownedDirtyBase + ownedExportResult = 4× full-size | ~192MB | ownedDirtyBase is a full copy; ownedExportResult may be scaled. |
| **Export (clean)** | originalPreviewBitmap + previewBitmap + decoded + scaled = 4× full-size | ~192MB | decoded is full-size; scaled may be smaller. |
| **Draft save** | originalPreviewBitmap + previewBitmap + dirtyBitmapCopy + editedPreviewCopy + masks = 4+× full-size | ~192MB+ | dirtyBitmapCopy and editedPreviewCopy are full-size copies. Masks add additional bytes. |
| **History navigation (undo)** | currentSnapshot + target load + materialization = 3× full-size | ~144MB | currentSnapshot is a copy of current state; target may be cold-decoded; materialization renders. |
| **Draft restore (generation)** | ownedBase + ownedRendered + ownedMasks + native session = 3+× full-size | ~144MB+ | ownedBase and ownedRendered are full-size; masks add bytes. |
| **Selection creation** | originalPreviewBitmap + previewBitmap + mask = 3× full-size | ~144MB | mask is full-size ARGB_8888. |
| **Rotate 90°** | originalPreviewBitmap + previewBitmap + rotatedPreview + rotatedOriginal + rotatedMasks = 5× full-size | ~240MB | All rotated bitmaps are full-size. |
| **Remaster (foreground mask)** | inputCopy + rawMask + scaledMask + out = 4× full-size | ~192MB | All at target resolution. |

### 4.2 Peak Path Details

**FlareGuard preview (highest peak):**
```
Timeline:
  T0: originalPreviewBitmap (48MB) + previewBitmap (48MB) = 96MB [existing UI state]
  T1: ownedBase = baseOriginal.copyOrThrow() → +48MB = 144MB [transient]
  T2: flareGuardBitmap = applyFlareGuardModelOrRuleResultV0(ownedBase) → +48MB = 192MB [transient]
  T3: renderedPreview = renderEditedPreview(flareGuardBitmap) → +48MB = 240MB [transient]
  T4: if adopted: originalPreviewBitmap = flareGuardBitmap, previewBitmap = renderedPreview
      → ownedBase recycled (-48MB) = 192MB [stable]
  T5: finally: flareGuardBitmap=null, renderedPreview=null (already adopted)
      → 192MB [stable, 2× full-size in UI state]
```

**Rotate 90° (second highest peak):**
```
Timeline:
  T0: originalPreviewBitmap (48MB) + previewBitmap (48MB) + masks (N×48MB) = 96MB+ [existing UI state]
  T1: rotatedPreview = rotateBitmap90(preview) → +48MB = 144MB+ [transient]
  T2: rotatedOriginal = rotateBitmap90(originalPreviewBitmap) → +48MB = 192MB+ [transient]
  T3: rotatedMasks = rotateBitmap90(each mask) → +N×48MB [transient]
  T4: if adopted: all rotated bitmaps in UI state, originals recycled
      → 192MB+ [stable]
```

---

## 5. Explicit Findings

### 5.1 Redundant full-resolution copies

1. **`renderEditedPreview()` copies the base bitmap unconditionally** (`basePreview.copyOrThrow()` at line 3981). The copy is needed because the native render is in-place, but the copy is never reused across operations. Each parameter change, auto-enhance, preset, or quick effect creates a fresh copy.

2. **FlareGuard creates a full-size `flareGuardBitmap`** even though the model may operate at a smaller input resolution. The `applyFlareGuardModelOrRuleResultV0()` returns a bitmap at the source resolution, which is then passed to `renderEditedPreview()`. If the model input is smaller, the output could potentially be at a reduced resolution for preview purposes.

3. **Export dirty base creates a full copy** (`ownedDirtyBase = liveBase.copyOrThrow()`) even when the export resolution is lower than the source. The copy is needed because `renderEditedExportFromBitmap()` renders in-place, but the copy could be at the export resolution if the source is already at or above that resolution.

4. **Draft save copies all bitmaps** (`createDraftSavePayload()` → `copyOwned()`). When `baseBitmapDirty` is false and the source is reusable, the dirty bitmap copy is skipped, but the edited preview copy and all mask copies are always made.

5. **History snapshots copy all bitmaps** (`toHistorySnapshot()` → `copyOrThrow()` for preview, original, and each selection layer). For metadata-only changes (e.g., param adjustments with no bitmap changes), the snapshot still copies all bitmaps.

### 5.2 Unexpectedly long-lived full-size Bitmaps

1. **Native session held during idle**: `nativeSession` is released only on `onTrimMemory`, `performMemoryCleanup` (when not busy), or `onCleared`. During normal editing, the session persists even when no rendering is happening. The session holds native memory (not JVM Bitmap), but it prevents the JVM from GC'ing associated resources.

2. **History hot snapshots**: Up to 5 undo + 5 redo entries can hold hot snapshots simultaneously. Each snapshot contains copies of preview, original, and all selection layer bitmaps. The `rebalanceHot()` function spills entries to cold storage when the budget is exceeded, but during rapid editing, multiple hot snapshots can accumulate.

3. **`pendingParamUndoSnapshot`**: Held during the 900ms param undo window. This snapshot contains copies of all bitmaps and is only released when the window closes or the snapshot is committed.

4. **`brushingSnapshot`**: Held during brush stroke interaction. Contains copies of all bitmaps. Released on `finishBrushStroke()` or `cancelBrushStroke()`, but if the user takes a long time, the snapshot persists.

5. **`selectionParamTransaction.snapshot`**: Held during selection parameter gestures. Contains copies of all bitmaps. Released on `settleSelectionParamTransaction()` or `restoreSelectionParamTransaction()`.

### 5.3 Duplicate preview/original ownership

1. **`originalPreviewBitmap === previewBitmap` aliasing**: When `baseBitmapDirty` is false, `originalPreviewBitmap` and `previewBitmap` point to the same Bitmap instance (see `openImage` line 1165-1166, `resetAdjustments` line 1613-1614). This is handled correctly by `releaseOrphanedBitmaps()` using `IdentityHashMap`, but it means the same bitmap is counted once in the ownership map.

2. **History snapshot `originalPreviewBitmap` may alias `previewBitmap`**: In `toHistorySnapshot()`, if `originalPreviewBitmap === previewBitmap`, the snapshot's `originalPreviewBitmap` is set to `previewCopy` (the same copy). This is correct but means the snapshot's `bitmapBytes()` function deduplicates via `identityBitmapSet()`.

3. **FlareGuard adopted `flareGuardBitmap` becomes `originalPreviewBitmap`**: When FlareGuard succeeds, `flareGuardBitmap` is adopted as `originalPreviewBitmap`, and `renderedPreview` is adopted as `previewBitmap`. The old `originalPreviewBitmap` and `previewBitmap` are recycled by `releaseOrphanedBitmaps()`. This is correct but creates a momentary 4× peak (old + new + ownedBase + flareGuardBitmap).

### 5.4 Stale worker result retention

1. **`renderJob` cancellation**: When `renderJob?.cancel()` is called, the `finally` block recycles `rendered` and `ownedBase`. However, if the coroutine is cancelled mid-render, the `CancellationException` is thrown, and the `finally` block runs. The `rendered` bitmap may be null at this point, so `rendered?.recycle()` is a no-op. This is correct.

2. **`managedEditJob` cancellation**: `launchManagedEdit()` cancels the previous job before starting a new one. The old job's `finally` block runs, recycling bitmaps. However, there's a window where the old job's `finally` block may not have completed before the new job starts, potentially causing a brief overlap. The `isManagedEditCurrent()` check prevents adoption of stale results.

3. **Export job cancellation**: When `invalidateExport()` is called, `exportJob?.cancel()` is called. The `finally` block recycles `ownedExportResult` and `ownedDirtyBase`. The `pendingUri` is deleted if not published. This is correct.

4. **Draft save cancellation**: When `draftSaveJob?.cancel()` is called, the `finally` block in `persistDraftSnapshotInternal()` runs. If `committed` is null (not yet committed), `payload.recycleOwnedBitmaps()` is called. If committed, the `settleCommittedDraft()` function handles rollback. This is correct.

5. **History navigation cancellation**: When `historyIoJob?.cancel()` is called, the `finally` block in `navigateHistory()` runs. The `navTracker?.end()` is called. However, the `historyCoordinator.navigate()` function has its own cleanup in the `finally` block, which recycles `currentSnapshot`, `materialized`, and `loaded` if not adopted. This is correct.

### 5.5 Native/model session overlap

1. **Single native session**: `EditorViewModel` holds a single `nativeSession` handle. When a new session is created (e.g., in `openImage` or `restoreDraft`), the old session is released via `releaseNativeSessionHandle(previousSession)`. There is no overlap — the old session is released before or after the new one is adopted, with proper error handling.

2. **FlareGuard model runner**: `FlareGuardModelRunner.createOrNull()` creates a new runner each time `applyFlareGuardModelOrRuleV0()` is called. The runner is closed in the `finally` block. There is no session overlap — each FlareGuard operation creates and destroys its own model runner.

3. **Remaster model session**: `RemasterModelSession` is a singleton object that holds a single model. `load()` closes the previous model before loading a new one. `unloadIdleNow()` unloads the model when idle. There is no overlap.

4. **Native session during memory cleanup**: `performMemoryCleanup()` releases the native session when `!isBusy && renderJob?.isActive != true && exportJob?.isActive != true`. This means the session is released when no rendering or export is happening, but it may be released during history navigation (which sets `isBusy = true`). This is correct — the session is not needed during history navigation.

### 5.6 Compose or StateFlow references retaining large objects

1. **`EditorUiState` (StateFlow)**: The `_uiState` MutableStateFlow holds `EditorUiState` which contains `originalPreviewBitmap`, `previewBitmap`, and `selectionLayers`. Each `updateUiStateAndRecycleReplaced()` call replaces the state, and `releaseOrphanedBitmaps()` recycles displaced bitmaps. The StateFlow itself does not hold strong references to old states — it only holds the latest value.

2. **`brushPreviewEpoch` StateFlow**: Holds a `Long`, no bitmap references.

3. **Compose UI**: `EditorScreenV2.kt` / `EditorScreen.kt` observe `uiState` via `collectAsState()`. The Compose runtime holds a reference to the latest `EditorUiState` during recomposition, but this is released when the composable is disposed. No long-lived references to bitmaps.

4. **`RemasterModelSession` Compose state**: `activeModel`, `statusText`, `isModelLoaded`, `isModelLoading`, `isInferring` are Compose `mutableStateOf` properties. They hold `RemasterModelCandidate?` (small data class) and `String`/`Boolean` values. No bitmap references.

5. **History coordinator `visibleFlags`**: `HistoryFlags` is a small data class with `canUndo`, `canRedo`, `busy` booleans. No bitmap references.

---

## 6. Concrete Phase 3B Optimization Candidates

> **Do not implement these yet.** These are candidates for Phase 3B analysis and implementation.

### 6.1 Highest priority

1. **Eliminate redundant base copy in renderEditedPreview()**: Instead of copying the base bitmap and rendering in-place, consider a render-to-target approach where the native render writes to a new bitmap without copying the source. This would save 1× full-size allocation per render operation. *Risk: requires native API change.*

2. **FlareGuard preview at reduced resolution**: The `flareGuardBitmap` is created at full source resolution. For preview purposes, it could be rendered at a reduced resolution (e.g., 50% of source) before passing to `renderEditedPreview()`. The full-resolution bitmap would only be needed for export. *Risk: may affect preview quality.*

3. **Export dirty base at export resolution**: When `baseBitmapDirty` is true and the export resolution is lower than the source, the `ownedDirtyBase` copy could be at the export resolution instead of the source resolution. *Risk: may affect render quality if native render expects full resolution.*

### 6.2 Medium priority

4. **Metadata-only history snapshots for param-only changes**: When only parameters change (no bitmap changes), the history snapshot could be metadata-only even when `originalPreviewBitmap != null` and `selectionLayers.isEmpty()`. Currently, `supportsMetadataOnlyHistory()` requires `originalPreviewBitmap != null && selectionLayers.isEmpty() && activeSelectionLayerId == null`, but the snapshot storage is still `Exact` when `baseContentToken == targetBaseToken` is false. *Risk: may lose bitmap state if base changes.*

5. **Selection layer bitmaps at reduced resolution for display**: Selection layer bitmaps are stored at full resolution. For display purposes, they could be stored at a reduced resolution and only decoded at full resolution when needed for export or baking. *Risk: may affect selection quality.*

6. **Native session lazy release**: The native session is held during idle periods. It could be released more aggressively (e.g., after each render operation) and recreated when needed. *Risk: may increase latency for subsequent operations.*

7. **Draft save bitmap reuse**: When `baseBitmapDirty` is false and the source is reusable, the `dirtyBitmapCopy` is skipped. However, the `editedPreviewCopy` is always copied. If the preview bitmap hasn't changed since the last draft save, the copy could be skipped. *Risk: may save stale previews.*

### 6.3 Lower priority

8. **Thumbnail cache identity deduplication**: The `ThumbnailBitmapCache` uses a `LinkedHashMap` keyed by string. If the same thumbnail is requested under different keys, duplicate entries may exist. An identity-based deduplication layer could prevent this. *Risk: low.*

9. **History snapshot bitmap sharing**: When a history snapshot's `originalPreviewBitmap === previewBitmap`, the snapshot stores two references to the same copy. This is already handled by `identityBitmapSet()`, but the snapshot could store a single bitmap and a flag indicating aliasing. *Risk: low.*

10. **Rotate 90° in-place**: Instead of creating new rotated bitmaps for preview, original, and each mask, the rotation could be applied as a transform matrix in the display layer, with the actual rotation only performed when needed (e.g., for export). *Risk: requires UI changes.*

---

## 7. Debug Inspection API

### 7.1 Resident Bitmap Ownership

```kotlin
// From EditorViewModel:
val report = viewModel.debugResidentOwnership()
// Returns a string with:
// - Current revision, baseContentToken, nativeSession handle
// - Active job states (renderJob, exportJob, managedEditJob, etc.)
// - Full DebugMemoryTracker snapshot (byOwner, byOperation, nativeSessions, activeOperations, peak bytes)
```

### 7.2 DebugMemoryTracker API

```kotlin
DebugMemoryTracker.isEnabled()           // Boolean
DebugMemoryTracker.snapshot()            // ResidentSnapshot
DebugMemoryTracker.logSnapshot(tag)      // Log.d with summary
DebugMemoryTracker.debugString()         // Full multi-line string
DebugMemoryTracker.clear()               // Clear all tracking data
```

### 7.3 MemoryTrackerScope API

```kotlin
val tracker = viewModel.beginMemoryTracking("operationName", snapshotState = "hot")
tracker?.track(bitmap, "ownerName")      // Register bitmap
tracker?.release(bitmap)                 // Unregister bitmap
tracker?.end()                           // Release all + end operation
```

---

## 8. Safety Review

### 8.1 Cancellation safety

All instrumented operations follow the existing cancellation safety patterns:
- `CancellationException` is re-thrown, not swallowed
- `finally` blocks recycle transient bitmaps and end trackers
- `isManagedEditCurrent()` / `isCropOperationCurrent()` / etc. checks prevent adoption of stale results
- Tracker `end()` is idempotent (safe to call multiple times)

### 8.2 Replacement safety

- `launchManagedEdit()` cancels the previous job before starting a new one
- `invalidateExport()` cancels the previous export job
- `beginDraftSaveOperation()` cancels the previous draft save job
- `invalidateSelectionPreview()` cancels the previous selection preview job
- Trackers are ended in `finally` blocks, so superseded operations clean up their tracking data

### 8.3 Shutdown safety

- `onCleared()` sets `shuttingDown = true`, cancels all jobs, clears `ThumbnailBitmapCache`, releases native session, closes history coordinator, and clears `DebugMemoryTracker`
- All operations check `shuttingDown` before proceeding
- `isShuttingDown()` is checked in `canEnterEditorAction()` and all public entry points

### 8.4 Release behavior

- All tracking code is guarded by `DebugMemoryTracker.isEnabled()` which returns `BuildConfig.DEBUG`
- In release builds, `BuildConfig.DEBUG` is a compile-time `false` constant, so the compiler eliminates all tracking code
- No behavior changes in release builds — the instrumentation is purely additive

### 8.5 Sensitive data

- No user image contents, file paths, or sensitive metadata are logged
- Bitmap identity is tracked via `System.identityHashCode()` (integer, not content)
- Native session source identity is tracked via `hashCode().toString()` (not the actual path)
- Document generation is a UUID (not user data)
- All logging uses `Log.d(TAG, ...)` with `TAG = "KeplerDebugMem"` — no user-facing strings

---

## 9. Build and Static-Validation Results

See Section 10 for actual build output.

### 9.1 Files changed

```
app/src/main/kotlin/com/projectnuke/keplerstudio/editor/DebugMemoryTracker.kt     (new, 280 lines)
app/src/main/kotlin/com/projectnuke/keplerstudio/editor/MemoryTrackerScope.kt      (new, 95 lines)
app/src/main/kotlin/com/projectnuke/keplerstudio/editor/EditorViewModel.kt        (modified, +~200 lines)
app/src/main/kotlin/com/projectnuke/keplerstudio/editor/ThumbnailBitmapCache.kt   (modified, +~15 lines)
docs/PHASE_3A_REPORT.md                                                          (this file)
```

### 9.2 Validation checks

- `git diff --check`: No whitespace errors
- UTF-8 validation: All files UTF-8 encoded
- BOM check: No BOM in any file
- Conflict marker check: No conflict markers
- `gradlew assembleDebug`: See Section 10
