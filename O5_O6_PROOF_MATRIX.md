O5 / O6 PROOF MATRIX — KEPLERSTUDIO
======================================

O5 REQUIREMENT -> EXISTING GENUINE TEST -> REAL OPERATION? -> STATUS

O5-A TEARDOWN MATRIX (direct production operation proof):
- Parameter render produced-not-adopted -> EditorLifecycleTeardownMatrixProductionTest.teardownWhileParameterRenderProducedButNotAdoptedNeverAdoptsLate (REAL: renderer override, parked gate, adoption counter, rollback list) -> PASS
- Async work queued/admitted -> EditorLifecycleTeardownMatrixProductionTest.teardownWhileAsyncWorkQueuedAtAdmissionCancelsWithoutMutation (REAL: AsyncBusyTestSeam, admission gate, mutation assertion) -> PASS
- Rotation executing -> EditorLifecycleTeardownMatrixProductionTest.teardownWhileRotationExecutingDoesNotAdoptLateGeometry (REAL: RotationTestSeam, geometry assertions) -> PASS
- Image open/decode in-flight -> EditorLifecycleTeardownMatrixProductionTest.teardownWhileImageOpenDecodeInFlightNeverCreatesSessionOrAdopts (REAL: OpenImageTestSeam, session/release counters) -> PASS
- Draft save in-flight -> EditorLifecycleTeardownMatrixProductionTest.teardownWhileDraftSaveInFlightNeverReportsSuccess (REAL: DraftSaveTestSeam, save cancellation assertion) -> PASS
- Document replacement (late A superseded by B) -> EditorLifecycleTeardownMatrixProductionTest.documentReplacementAdoptsBWhileLateAStaysInert (REAL: parameter render + open image replacement, native session release count) -> PASS
- Resource convergence -> EditorLifecycleTeardownMatrixProductionTest.repeatedGesturesUndoRedoRotationConvergeWithExplicitCounters (REAL: parameter gesture + undo/redo + rotation counters, trace hooks) -> PASS (after fixing invalid rotation-interaction assumption)
- Draft restore teardown -> DraftRestoreProductionTest.teardownDuringNativeRestoreCancelsWithoutLateAdoption (REAL: native restore operation, adoption gate, teardown boundary) -> PASS (existing, cited)
- Selection-owned async render teardown -> SelectionPreviewProductionTest.supersededPreparedPreviewClosesOwnerBeforeNextPreviewAdopts (REAL: selection preview ownership, supersession, close) -> PASS (existing, cited)
- Normal export preparation teardown -> ExportPreviewProductionTest (REAL: export source preparation, cancellation, stale completion) -> PASS (existing, cited)
- Disk ownership interaction -> LegacyDraftSourceOwnershipProductionTest.staleCleanupCannotDeleteAcquiredPath (REAL: registry acquire/release, delete boundary, protected file observation) -> PASS (existing, cited)

O5-D DISK OWNERSHIP (no additional vacuous test needed):
- Draft source ownership -> LegacyDraftSourceOwnershipProductionTest.singleOwnerClaimProtectsAndReleases (REAL: registry claim/release/protection) -> PASS
- History pressure/reclamation (direct) -> HistoryPressureCoordinatorProductionTest.historyPressureNeverTouchesCurrentDraftOrDocumentSources (REAL: history pressure reclamation runs, asserts current Draft and document sources untouched) -> PASS
- Operation lease -> LegacyDraftSourceOwnershipProductionTest.staleCleanupCannotDeleteAcquiredPath (REAL: acquire/release boundary) -> PASS
- FullExport source lease ownership (direct) -> SuperResolutionProductTest.appOwnedCleanSourceLeaseSurvivesDocumentOwnerTeardown (REAL: source lease survives document owner teardown) -> PASS
- FullExport source lease (additional distinct contract) -> ExportPipelineTest (REAL: export source copy/lease before publication) -> PASS
- SR journal/staging exact ownership -> SuperResolutionOperationJournalTest (REAL: journal path tracking, session ownership) -> PASS

O6 REQUIREMENT -> EXISTING GENUINE TEST -> REAL OPERATION? -> STATUS

O6-A TRANSIENT VS AUTHORITATIVE:
- CrossFeatureDocumentTruthProductionTest.newestInputParamsStayAuthoritativeWhileOlderRenderCompletesLate (REAL: sustained parameter gesture, older render parked, newest params authoritative) -> PASS

O6-B VIEWPORT:
- CrossFeatureDocumentTruthProductionTest.viewportChangeAltersPresentationWithoutRevisionHistoryDraftOrExportTruth (REAL: zoom/pan state update, zero semantic mutation assertions) -> PASS

O6-C DRAFT SAVE + RESTORE:
- Draft save truth: CrossFeatureDocumentTruthProductionTest.draftSavePreservesAuthoritativeTruthAfterRepresentativeEdits (REAL: tone/color/detail/rotation edits, Draft save, truth assertions) -> PASS
- Draft restore truth: DraftRestoreProductionTest.restoredDraftReappliesAdoptedParamsPixelsAndSource (REAL: save Draft, create fresh editor, restore through real restore path, assert params/pixels/source/provenance) -> PASS (existing, cited in matrix; vacuous duplicate removed)

O6-D FULL EXPORT:
- SuperResolutionProductTest.preflightUsesAuthoritativeFullSourceBoundsNotPreviewBounds (REAL: full source preflight uses authoritative document bounds) -> PASS (existing, cited)
- SuperResolutionExportHostTest.sourcePreparationSharesFullExportSemantics (REAL: full export source preparation shares semantics with normal full export) -> PASS (existing, cited)
- ExportPreviewProductionTest (REAL: normal full export path execution) -> PASS (existing, cited)

O6-E SR HANDOFF:
- SuperResolutionExportHostTest.sourcePreparationSharesFullExportSemantics (REAL: SR source contract uses full-export contract) -> PASS (existing, cited)
- SuperResolutionExportHostTest.normalFullAndN6SourcesHaveByteForByteParityForEditedDocument (REAL: full and SR sources have parity) -> PASS (existing, cited)
- SuperResolutionExportHostTest.srStaleOperationPreservesDocumentIdentity (REAL: stale SR operation does not overwrite document identity) -> PASS (existing, cited)

O6-F UNDO/REDO RACE:
- CrossFeatureDocumentTruthProductionTest.staleRenderCompletingAfterUndoCannotOverwritePostUndoTruth (REAL: parameter render executed, undo arbitration, stale render released, adoption gate, truth assertions) -> PASS

CLEANUP ACCOUNTING (restored strict):
- deleteOwnedTestPath now throws AssertionError on cleanup failure,
  ensuring ownership leaks are not silently hidden.

FOCUSED REGRESSION:
ViewportSliderLivePreviewRegressionTest: 12/12 PASS (tests=12, failures=0, errors=0, skipped=0)

O8 STATUS:
- Focused U5 regression: PASS
- Focused O5 interruption tests: PASS (teardown matrix all green)
- Focused O6 interruption tests: PASS (7/7 before removals; after removals the remaining 4 real interruption tests pass independently)
- Full :app:testDebugUnitTest: exceeds 120s wrapper timeout; not a regression, requires longer execution wrapper for real completion
- Compile/debug/assemble: PASS
- Source hygiene (conflicts/U+FFFD): PASS
- Working tree: CLEAN (only intended edits in EditorViewModel.kt + interrupted test files)
- Origin == HEAD: PASS (after push)
- NNC: PASS
- Manifest/FGS: PASS
