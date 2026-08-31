N6 CORRECTIVE PASS — HOLD REPORT
=================================

BASELINE HEAD: 079dff256dbc9ef92addc65c8f6f378d3e1c9d3a
FINAL HEAD: (corrective edits applied to 184a311e15a3983be364d7de8d4659c72759b1ef)
BRANCH: feature/exynos-ai-runtime
DATE: 2026-08-31

STATUS: N6 HOST GATES PASS — PHYSICAL E2E PENDING (device unavailable)

================================================================================
GIT HYGIENE
================================================================================
- N6_FINAL_REPORT.md renamed to N6_HOLD_REPORT.md (not authoritative)
- Added: tools/pin_debug_keystore.ps1, docs/DEBUG_SIGNING.md
- Modified: EditorViewModel.kt, SuperResolutionExportHostTest.kt, SuperResolutionExportState.kt, SuperResolutionExportOrchestrator.kt, ExynosN6ProductE2ETest.kt, app/build.gradle.kts, .gitignore

================================================================================
DEBUG SIGNING
================================================================================
Installed cert SHA-256: N/A (no device connected during bootstrap)
Source/default debug.keystore SHA-256: ac62525138841d7388237d190b37d9e119d854d97053655d063d28f9ba552c8f
Pinned stable cert SHA-256: ac62525138841d7388237d190b37d9e119d854d97053655d063d28f9ba552c8f
Built APK cert SHA-256: ac62525138841d7388237d190b37d9e119d854d97053655d063d28f9ba552c8f
Stable keystore path: %USERPROFILE%\.projectnuke\keplerstudio\keplerstudio-stable-debug.jks
adb install -r result: N/A (device unavailable)
Uninstall/data clear occurred: NO
Release signing untouched: YES (release buildType not configured with debugStable)

================================================================================
N4 REGRESSION
================================================================================
Status: PASS (host gates only — physical N4 not re-run)

================================================================================
N5 REGRESSION
================================================================================
Status: PASS (host gates only — physical N5 not re-run)

================================================================================
N6 HOST
================================================================================
Compile:
- compileDebugKotlin: PASS
- compileDebugUnitTestKotlin: PASS
- compileDebugAndroidTestKotlin: PASS

JUnit XML (×2 runs):
- Run 1: tests=1175, failures=0, errors=0, skipped=0
- Run 2: tests=1177, failures=0, errors=0, skipped=0

Focused tests added:
- normalFullAndN6SourcesHaveByteForByteParityForEditedDocument (real product entrypoints, bounded row-hash)
- viewModelProductActionSuccessPreservesDocumentAndHistoryState
- srNpuFailurePreservesDocumentIdentity
- srPngFailurePreservesDocumentIdentity
- srUserCancellationPreservesDocumentIdentity
- srStaleOperationPreservesDocumentIdentity
- srMetadataAfterPublicationFailurePreservesDocumentIdentity
- arbitrationNewerSrSupersedesOlderSr
- heavyWorkerRunsOffMainThread (non-vacuous with gates)

lintDebug: PASS
assembleDebug: PASS
assembleDebugAndroidTest: PASS

================================================================================
N6 PHYSICAL (S24 Exynos)
================================================================================
Status: PENDING (device unavailable)

Physical E2E test harness fixes applied:
- Section 9: sourceBitmapObserver added for exact pre-compression source hash
- Section 10: memory milestones use actual FileBackedRgb8Artifact.file.length()
- Section 11: removed false Publishing→after_mediastore_publish mapping
- Section 12: N4 seam Rect fixed (left,top,right,bottom) using TilePlanner.plan(); same Rect for RGB8 and PNG decode
- Section 13: success settlement assertions (session Unloaded, registry inactive, wake released, RGB8 cleaned)
- Section 14: cancellation assertions (Cancelled terminal, isBusy=false, no published URI, pending row settled, wake released)

Frozen NNC proof (hard assert in production):
- size = 3,112,960 bytes
- SHA-256 = 9cff7af64dbe5b4ed260449153ea08e91cabd758ce3478344c286ee2798bae12

================================================================================
N6 FINAL
================================================================================
Status: OPEN

Host gates: PASS
Physical E2E: PENDING (requires SM-S921N / e1s / Exynos 2400 device)

DO NOT start N7 until physical E2E completes.

================================================================================
CHANGED FILES (corrective pass)
================================================================================
app/src/main/kotlin/com/projectnuke/keplerstudio/editor/EditorViewModel.kt
- Fixed stray duplicate statements after catch block (failureKind, t)
- Split isCurrentWork vs isTerminalOwner (Job.isActive not required for terminal settlement)
- Added sourceBitmapObserver seam hook for exportPreview and exportSuperResolution

app/src/main/kotlin/com/projectnuke/keplerstudio/editor/SuperResolutionExportState.kt
- PublishedWithMetadataFailure: added metadataCause, rgb8CleanupCause, pendingRowCleanupCause, suppressedCleanupCauses

app/src/main/kotlin/com/projectnuke/keplerstudio/editor/SuperResolutionExportOrchestrator.kt
- Aggregate metadata failure with cleanup debt (preserve both facts)

app/src/main/kotlin/com/projectnuke/keplerstudio/editor/ExportTestSeam.kt
- Added sourceBitmapObserver seam

app/src/main/kotlin/com/projectnuke/keplerstudio/editor/SuperResolutionTestSeam.kt
- Added sourceBitmapObserver seam

app/src/test/kotlin/com/projectnuke/keplerstudio/editor/SuperResolutionExportHostTest.kt
- Fixed Looper.post → Handler(Looper.getMainLooper()).post
- Made heavyWorkerRunsOffMainThread non-vacuous with deterministic gates
- Rewrote normalFullAndN6SourcesHaveByteForByteParityForEditedDocument (real product actions, bounded row-hash)
- Added document-isolation matrix tests (NPU failure, PNG failure, cancellation, stale, metadata-after-publication)
- Fixed arbitrationNewerSrSupersedesOlderSr (actual second SR start)

app/src/androidTest/kotlin/com/projectnuke/keplerstudio/exynos/ExynosN6ProductE2ETest.kt
- Added sourceBitmapObserver for source hash
- Fixed N4 seam Rect using TilePlanner.plan() (proper left,top,right,bottom)
- Removed false Publishing→after_mediastore_publish milestone mapping
- Added success/cancellation settlement assertions

app/build.gradle.kts
- Added debugStable signingConfig pointing to stable keystore
- debug buildType uses debugStable; release untouched

.gitignore
- Added .jks, .keystore, .projectnuke/, signing.properties patterns

tools/pin_debug_keystore.ps1 (new)
- Bootstrap script for stable DEBUG keystore

docs/DEBUG_SIGNING.md (new)
- Development signing documentation

================================================================================
REMAINING GAPS
================================================================================
1. Physical E2E on S24 device (input=4080x3060, output=16320x12240, tiles=3350, PNG rows=12240)
2. Generate fresh n6_product_e2e.json, n6_cancel_e2e.json, n6_memory_samples.json, n6_memory_summary.json, n6_region_hashes.json

================================================================================
NEXT STEPS
================================================================================
1. Connect SM-S921N (Exynos 2400) device
2. Run: .\gradlew assembleDebugAndroidTest && adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
3. Run: adb shell am instrument -w -e kepler.exynosNpuProbe true com.projectnuke.keplerstudio.test/androidx.test.runner.AndroidJUnitRunner
4. Capture evidence JSON files and verify NPU proof = OBSERVED
5. Declare N6 FINAL PASS or file corrective issues
