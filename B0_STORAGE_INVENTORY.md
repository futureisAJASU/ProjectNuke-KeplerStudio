# Phase B0 — Storage Artifact Ownership Inventory (VERIFIED)

Based on production code inspection:
`DraftGenerationStorage.kt` (full), `IncomingSourceTransaction.kt` (full),
`EditorHistoryStorage.kt` (init/publish/trim), `SavedExportHistoryStore.kt`,
`EditorViewModel.kt` (restore/startup/save/legacy-draft paths).

## Reachability Roots (authoritative)

| Root | Where | Resolves to |
|---|---|---|
| Draft generation pointer | SharedPreferences `PREF_NAME_DRAFT` / `KEY_DRAFT_GENERATION_ID` | `filesDir/drafts/generations/gen_<id>/` (must have `complete` marker) |
| Legacy draft source | SharedPreferences `PREF_NAME` / `KEY_DRAFT_SOURCE` | one of: `filesDir/drafts/current/source_<uuid>.img`, `cacheDir/source_<id>.img`, `filesDir/editor_sources/restored_<uuid>.img` |
| History index | in-memory `SavedExportHistoryStore` (prefs `export_history_retention` + rawHistory JSON) | `filesDir/editor_history_v3/session_<id>/<entry>/` |

Everything else is derived/reachable from these roots, or in-memory-only (dead on process death).

## Artifact Families (verified)

### 1. Draft Generations — `filesDir/drafts/generations/`
- Staging dir: `.staging_<uuid>` (hidden prefix, `DRAFT_GENERATION_STAGING_PREFIX`), created by `newDraftGenerationDirectory`.
- Final dir: `gen_<id>` (`DRAFT_GENERATION_DIR_PREFIX`), produced by `finalizeDraftGeneration` rename.
- Per dir: `manifest.json` (`DRAFT_MANIFEST_FILE_NAME`), `source.img`, `thumbnail.jpg`, `mask_<name>` per selection layer, `complete` marker (`DRAFT_COMPLETION_FILE_NAME`).
- Mid-write temps inside a dir: `source.<uuid>.tmp`, `<mask>.<uuid>.tmp`, `thumbnail.<uuid>.tmp`, `manifest.<uuid>.tmp` — UUID-suffixed, never referenced by manifest.
- Authority: pointer (`currentDraftGenerationId`). Retirement: `deleteAllDraftGenerationsExcept(context, keep)` runs after every successful save (EditorViewModel.kt:4814); `deleteDraftGenerationById` on leave/invalid-restore paths.
- Existing primitives: `deleteDraftDirectory` (files + dir, logs failures), `deleteAllDraftGenerationsExcept` (both prefixes, keeps one), `deleteDraftGenerationById` (never deletes pointer target).

### 2. Editor History — `filesDir/editor_history_v3/`
- Layout: `session_<sessionId>/<entryId>/` with `manifest.json` + `complete` markers.
- Already self-healing at session init: `initializeSession` deletes non-session dirs and, for the current session, `staging_*`-prefixed and non-complete entry dirs (EditorHistoryStorage.kt:1404-1417).
- Publish uses staging dir + rename (crash-safe). Disk budget trim with protected sets exists.
- **No reconciler work needed here — covered by existing protocol.**

### 3. IncomingSourceTransaction Staging/Final — `cacheDir/`
- `source_<id>.img.staging` → promoted to `source_<id>.img` via rename in `acquire`.
- Transaction is in-memory-only; the staging name is never exposed as a document source.
- Cleanup (`cleanupPaths`) only on exception/cancellation in-process; process death leaves orphans.
- Final `source_<id>.img` MAY be referenced by `KEY_DRAFT_SOURCE` (legacy compatibility prefs) — see migration flow (`migrateDraftSourceIfNeeded`, `resolveDraftRecovery`).

### 4. Restore Working Copies — `filesDir/editor_sources/`
- `restored_<uuid>.img` created by `copyGenerationSourceToWorkingFile` during draft restore.
- Referenced in-memory by the restored document; MAY also be referenced by `KEY_DRAFT_SOURCE`.
- Deleted only by `deleteOwnedWorkingSource` on editor leave in-process → process death orphans them.

### 5. Legacy Current Draft — `filesDir/drafts/current/`
- `source_<uuid>.img` (owned), `thumbnail.jpg`, `source.img` (`DRAFT_SOURCE_FILE_NAME`), `*.tmp`.
- `.tmp` cleaned by `cleanupDraftTemporaryFiles` (restore path only, EditorViewModel.kt:8201/10303).
- Obsolete `source_*.img` cleaned by `deleteObsoleteDraftSources` on save only.

### 6. Export History — prefs only (`SavedExportHistoryStore`)
- Index + retention in SharedPreferences; history images live in `editor_history_v3`. Self-pruning.

## Crash-Point Matrix (process death)

| # | Crash point | Leftover | Orphan class |
|---|---|---|---|
| 1 | During `IncomingSourceTransaction.acquire` copy | `cacheDir/source_<id>.img.staging` | reclaim (never referenced) |
| 2 | After staging→final rename, before doc adoption/save | `cacheDir/source_<id>.img` | reclaim IF not `KEY_DRAFT_SOURCE` |
| 3 | During `writeDraftGeneration` (any payload temp) | `.staging_<uuid>` partial dir (no `complete`) | reclaim (never referenced) |
| 4 | After `complete`, before `finalizeDraftGeneration` rename | `.staging_<uuid>` complete dir | reclaim (never referenced) |
| 5 | After finalize rename, before pointer publish | `gen_<id>` orphan dir | reclaim (not pointer) |
| 6 | After pointer publish, before retirement sweep | stale old `gen_*` dirs | reclaim (not pointer) |
| 7 | During restore working-copy creation | `editor_sources/restored_<uuid>.img` | reclaim IF not `KEY_DRAFT_SOURCE` |
| 8 | History publish crash | staging/incomplete entry dirs | ALREADY handled by `initializeSession` |
| 9 | Legacy draft temp write (`persistDraftBitmapFile`/`copyFileAtomically`) | `drafts/current/*.tmp` | reclaim (already cleaned at restore; reconciler covers non-restore startups) |

## Orphan Classes + Disposition (reconciler scope)

1. `cacheDir/source_*.img.staging` → always DELETE (never persistently referenced).
2. `cacheDir/source_*.img` not equal to `KEY_DRAFT_SOURCE` → DELETE.
3. `filesDir/editor_sources/restored_*.img` not equal to `KEY_DRAFT_SOURCE` → DELETE.
4. `filesDir/drafts/generations/.staging_*` dirs → DELETE (never referenced).
5. `filesDir/drafts/generations/gen_*` dirs not equal to pointer → DELETE (reuse `deleteDraftDirectory`).
6. Stale `*.uuid.tmp` inside the KEPT pointer generation dir → DELETE (never manifest-referenced).
7. `filesDir/drafts/current/*.tmp` → DELETE; `source_*.img` in this dir → PRESERVE (B4 correction: `drafts/current` is the live draft working dir — `persistDraftBitmapFile` and document sources land here; ownership is unprovable at startup, so startup cleanup here is limited to `*.tmp` only; `deleteObsoleteDraftSources` covers unreferenced `source_*.img` on the SAVE path, where ownership IS provable).
8. History v3 → NOT in reconciler scope (self-healing).
9. Anything not matching an owned pattern → IGNORED (never touch foreign/unknown files).

## Conservative Policy

- Delete only artifacts provably unreferenced by a root (pointer / `KEY_DRAFT_SOURCE`).
- Pointer target is PRESERVED unconditionally (validity judgment belongs to restore, not cleanup).
- Referenced source files are PRESERVED even if they live in `cacheDir`/`editor_sources`.
- Failure to delete is never fatal: report, log, continue.

## Files Inspected
- `DraftGenerationStorage.kt` (full, lines 1-307)
- `IncomingSourceTransaction.kt` (full, 127 lines)
- `EditorHistoryStorage.kt` (initializeSession/publish/trim regions)
- `SavedExportHistoryStore.kt` (index/retention)
- `EditorViewModel.kt` (startup job 4849-4894, restore 7732+/8121+, legacy draft paths 10124-10333, 11198-11303)

---

# Phase B1 — Startup Storage Reconciliation Design

## Gap Analysis (what is NOT cleaned today)

| Leftover | Cleaned today? |
|---|---|
| `drafts/generations/.staging_*` / orphan `gen_*` dirs | Only on the NEXT successful save (`deleteAllDraftGenerationsExcept` at EditorViewModel.kt:4814) or leave/restore paths — never at startup |
| `cacheDir/source_*.img.staging` | Never (cleanup only in-process on exception) |
| `cacheDir/source_*.img` (unreferenced) | Never (only `deleteOwnedWorkingSource` on leave) |
| `editor_sources/restored_*.img` (unreferenced) | Never (only `deleteOwnedWorkingSource` on leave) |
| `drafts/current/*.tmp` + unreferenced `source_*.img` | Only in restore path / on save |
| `editor_history_v3` orphans | ALREADY handled (`initializeSession`) — out of scope |
| Export metadata / caches | Self-managed — out of scope |

## Design: `reconcileStartupArtifacts(context, inProcessSourcePath): StartupReconcileOutcome`

New internal function in `app/src/main/kotlin/com/projectnuke/keplerstudio/editor/StartupStorageReconciler.kt`.

**Reference roots (preserved):**
- Draft generation pointer (`KEY_DRAFT_GENERATION_ID`) — pointer-named `gen_*` dir preserved UNCONDITIONALLY (validity judgment belongs to restore, not cleanup).
- `KEY_DRAFT_SOURCE` canonical path.
- `inProcessSourcePath` canonical path (live document source — after restore adoption this is `editor_sources/restored_<uuid>.img` (EditorViewModel.kt:7970) or a legacy source; must never be deleted).

**Rules per location (all canonical-parent-validated, never throw, per-item runCatching):**

1. `drafts/generations/`:
   - dir name == pointer → PRESERVED_POINTER; delete stale `*.tmp` inside (UUID-suffixed temps, never manifest-referenced) → DELETED_TEMP
   - `.staging_*` dir → delete → DELETED_STAGING (or FAILED_DELETION)
   - `gen_*` dir ≠ pointer → delete → DELETED_UNREFERENCED (or FAILED_DELETION)
   - other → IGNORED_UNKNOWN
2. `cacheDir/`:
   - `*.img.staging` → delete → DELETED_STAGING (never persistently referenced — transaction is in-memory)
   - `source_*.img` not referenced → delete → DELETED_UNREFERENCED; referenced → PRESERVED_REFERENCED
   - other → IGNORED_UNKNOWN
3. `filesDir/editor_sources/`:
   - `restored_*.img` not referenced → delete → DELETED_UNREFERENCED; referenced → PRESERVED_REFERENCED
   - other → IGNORED_UNKNOWN
4. `filesDir/drafts/current/`:
   - `*.tmp` → delete → DELETED_TEMP
   - `source_*.img` not referenced → delete → DELETED_UNREFERENCED; referenced → PRESERVED_REFERENCED
   - other (e.g. `source.img`, `thumbnail.jpg`) → IGNORED_UNKNOWN (live legacy state)

**Diagnostics:** `StartupReconcileDisposition` enum (PRESERVED_POINTER / PRESERVED_REFERENCED / DELETED_STAGING / DELETED_UNREFERENCED / DELETED_TEMP / FAILED_DELETION / IGNORED_UNKNOWN); outcome carries per-entry records + derived counts. `StartupReconcileTestSeam` (registry pattern like `HistoryAdmissionTestSeam`) records the last outcome / notifies an observer for production tests.

**Startup ordering (safety):**
- Runs once, in the EditorViewModel startup job AFTER `restoreDraftIfAvailable` (all roots committed, working file adopted into `uiState.sourcePath`) and after history load, BEFORE `startupInitCompletion.complete`.
- Holds `draftSaveMutex` on Dispatchers.IO → serialized against any in-flight save (save+settle hold the same mutex, EditorViewModel.kt:4734-4749); restore critical sections hold the mutex too → no race with migration/adoption.
- In-process source acquisition (`IncomingSourceTransaction`) cannot be in flight: reconcile runs at VM init before any user open action.
- Idempotent: second pass deletes nothing new.
- Failure-to-delete is non-fatal: logged via `FLARE_GUARD_AI_TAG`, counted, never thrown.
- Conservative policy holds: only provably-unreferenced artifacts are deleted.

**Exit criteria (B1):** design recorded above; production change + mandatory tests in B2/B3.
