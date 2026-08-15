# Phase B4 — Startup Storage Reconciliation: Implementation + Verification

## What shipped (B2/B3/B4)

`app/src/main/kotlin/com/projectnuke/keplerstudio/editor/StartupStorageReconciler.kt` (new, untracked until B commit):

- `reconcileStartupArtifacts(context, inProcessSourcePath): StartupReconcileOutcome` runs once in the EditorViewModel startup job after `restoreDraftIfAvailable` + history load, before `startupInitCompletion.complete`; holds `draftSaveMutex` on Dispatchers.IO; never throws; failure-to-delete is logged + counted, never fatal.
- Reference roots preserved unconditionally: pointer-named `gen_*` dir (`KEY_DRAFT_GENERATION_ID`), `KEY_DRAFT_SOURCE` canonical path, `inProcessSourcePath` canonical path.
- Dispositions per location (canonical-parent-validated, per-item runCatching):
  1. `drafts/generations/`: pointer dir → PRESERVED_POINTER (+ stale `*.tmp` inside → DELETED_TEMP); `.staging_*` → DELETED_STAGING; `gen_*` ≠ pointer → DELETED_UNREFERENCED; other → IGNORED_UNKNOWN.
  2. `cacheDir/`: `*.img.staging` → DELETED_STAGING; `source_*.img` referenced → PRESERVED_REFERENCED else DELETED_UNREFERENCED; other → IGNORED_UNKNOWN.
  3. `filesDir/editor_sources/`: `restored_*.img` referenced → PRESERVED_REFERENCED else DELETED_UNREFERENCED; other → IGNORED_UNKNOWN.
  4. `filesDir/drafts/current/`: `*.tmp` → DELETED_TEMP; **ALL other files (incl. `source_*.img`, `source.img`, `thumbnail.jpg`) → IGNORED_UNKNOWN (preserved)** — see correction below.
- `StartupReconcileTestSeam` (registry pattern) records last outcome / notifies observers.

## B4 correction (legacy scan ownership bug — found by regression test)

The initial implementation deleted `drafts/current/source_*.img` not referenced by prefs as `DELETED_UNREFERENCED`. `EditorSaveAndLeaveQuiescenceProductionTest` (16 tests) exposed this as 14 failures: the legacy `drafts/current` root IS the live draft working dir — `persistDraftBitmapFile` output (`source_leave-*.img`) and the adopted document source land here, and no persistent root names them, so ownership is UNPROVABLE at startup. Deleting them broke `saveDraftSnapshot` (`no-draft-source-result` → `reuse-source-null:<path>`).

- Traced via retained test hooks: `EditorViewModel.lastDraftSaveFailureReasonForTest`, `DraftSaveTestSeam.Registry.lastFailureReasonForTest` (branch reasons: `no-draft-source-result:<path>/dirty=`, `reuse-source-null:<path>`, `dirty-bitmap-persist-null`, `save-draft-not-current`, `generation-*` stages).
- Baseline proof (stash only EditorViewModel, run, pop): 16/16 GREEN at baseline — the failures were the reconciler bug, NOT pre-existing.
- Fix: legacy scan deletes ONLY `*.tmp` (`DRAFT_TEMP_SUFFIX`); all other files → `IGNORED_UNKNOWN`. `StartupStorageReconcilerProductionTest.legacyCurrentTempsDeletedSourcesPreserved` asserts the corrected behavior (orphan `source_orphan.img` preserved; 1 DELETED_TEMP + 3 IGNORED_UNKNOWN).

## Verification (regression gate)

- `StartupStorageReconcilerProductionTest`: 11/11 green.
- `EditorSaveAndLeaveQuiescenceProductionTest`: 16/16 green (also dual-mode pump on `await`).
- Shard 2 (17 classes / 218): green (incl. EditorActionSettlement 4, ExportPreview 13, EditorHistoryCoordinatorTransition 38, EditorLeaveRoute 3, EditorViewModelBrushTransaction 10, EditorViewModelViewport 5, EditorWaitInfrastructure 3, Experimental* 35, ExportPipeline 14, ExternalIntentCoordination 9, ExternalIntentSupersession 7, FlareGuardModelLoaderLifecycle 16, EditorActionAdmission 12, EditorSaveAndLeave 16).
- Shard 3 (16 classes / 112): green (AlgorithmVersionPolicy 6, AsyncActionPreparation 5, BitmapCopyOwnershipProof 2, BitmapLease 17, BitmapMemoryBudget 14, FlareGuardProductionAvailability 1, FlareGuardSelectionPolicy 3, FlareGuardTensorContract 7, GlobalParameterProduction 8, HistoryActivityRegistry 6, HistoryAdmissionFeedback 5, HistoryNavigationFeedback 7, IncomingSourceTransaction 5, ManagedEditLaunchController 11, MaskQualityValidator 8, MaskRefinement 7).
- Shard 4 (16 classes / 124): green after pump fixes — `MemoryRecoveryOwnershipProductionTest.awaitEvent` → 15s dual-mode (`successfulStrongRetryClearsStrongAttemptMarker`); `ParameterInactivityWindowProductionTest` → 15s dual-mode (`failedRenderClosesGestureAndNoTimerEverFires`: virtual-clock `idleFor(20ms)` raced past the 900ms inactivity timer; real-thread render-failure continuation needed background turns).
- Shard 5 (16 classes / 210): green after fixing `ShutdownDraftIntegrityProductionTest` hang — 4 bare `persistDraftSnapshotNow()` sites (incl. `saveAndLeaveWithAdoptedAAndPendingBRetainsA` line 442, confirmed via jstack: main thread parked in `BlockingCoroutine.joinBlocking` on Robolectric main looper) → `persistDraftForTest(vm)` wrapper (Default-scope async + `awaitEditorCompletionForTest` 30s + pump); test 9's save moved to a Default `callerScope` so the `DraftSaveTestSeam` gate stays observable. 11/11.
- Catch-all (26 classes / 219): green — `ParameterNoAdoptionRollbackProductionTest.openImageAfterNoAdoptionRollsBackBeforeDecode` failed only in batch (legacy weak pump, passes alone; not reconciler-related: cacheDir source, drafts cleaned in @Before); `awaitEvent` → 15s dual-mode; 7/7.
- `lintDebug` + `assembleDebug`: SUCCESS (45 warnings, all pre-existing; none in reconciler/seam code).
- **Known pre-existing, unchanged: `ExternalIntentOrderingProductionTest`** — verified failing at BASELINE (15-min hang). With B2 changes it fail-fasts (`awaitInit` assert at line 383, `awaitEvent` assert at line 407) then hits the same `pendingGate.await()` deadlock family; never green. Documented, not fixed (gate rule).

## Retained instrumentation (test-only diagnostics)

- `EditorViewModel.lastEditorLeaveFailureForTest`, `EditorViewModel.lastDraftSaveFailureReasonForTest` (5 false-paths in `persistDraftSnapshotInternal`: `epoch-or-pointer-mismatch` / `snapshot-acquire-failed` / `saveDraftSnapshot-returned-null` / `settleCommittedDraft-rolled-back`).
- `DraftSaveTestSeam.Registry.lastFailureReasonForTest` (branch-level reasons listed above).
- Consumed by `EditorSaveAndLeaveQuiescenceProductionTest.await` diagnostics (`failure=`/`saveReason=`/`saveStage=`).
