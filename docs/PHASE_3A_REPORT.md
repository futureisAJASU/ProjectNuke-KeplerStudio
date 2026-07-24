# Phase 3A memory diagnostics stabilization

## Final architecture and release behavior

`DebugMemoryTracker` remains the object facade. A debug `EditorViewModel` owns one
`TrackerSession`; `TrackerSessionHolder` is only an observability lookup and never
replaces editor ownership. `ThumbnailBitmapCache` remains the existing
process-global singleton and exposes an immutable global snapshot.

In release, `createEditorSession()` returns `null`. The editor therefore creates
no session, holder entry, reconciler, operation scope, operation token, ledger
map, lock, reference queue, or scope deque. `beginMemoryTracking()` returns
before constructing a scope. Direct low-cost diagnostics calls go to one
stateless no-op singleton. UI transitions contain one nullable reconciler branch;
no slot map is built when the reconciler is absent. This is small branch/call
overhead, not a claim of compiler elimination or zero overhead.

## Session lifecycle and bitmap ledger

`close()` is an idempotent Active -> Closing -> Closed transition. The session
lock is authoritative for lifecycle authorization, weak-node lookup/insertion,
edge acquisition, exact edge release, last-edge removal, reference-queue purge,
native records, operation records, history publication, and terminal clearing.
A closing or closed session cannot be repopulated by a racing registration.
Holder removal uses `remove(editorId, session)` so an older instance cannot
remove a newer same-ID session.

`edgeIndex` points directly to the exact `NodeEntry`. Node removal uses
`nodes.remove(key, entry)`, and queued weak references remove only their exact
entry. The injected identity-hash provider permits deterministic collision
testing without weakening referential identity equality.

Snapshots expose identity-deduplicated bytes plus independent acquisition
counts. `byOwner` and `byOperation` count each bitmap once per label; their
totals are explicitly non-additive across alias labels. Acquisition-count maps
retain the number of independent same-label edges.

## Exact ownership transfers

`MemoryTrackerScope` retains every transient edge handle. Releasing a handle
also removes it from the scope deque; `end()` releases only handles still owned
by the scope. Production paths no longer use broad `unregisterBitmap()` for
normal transfer or cleanup.

The central UI reconciler owns these stable slots:

- `EditorUiState:previewBitmap`
- `EditorUiState:originalPreviewBitmap`
- `EditorUiState:selectionLayer:<layerId>`

It retains a handle only when slot identity and document generation both match.
Missing handles are acquired, generation changes rebind the same bitmap, and
removed/replaced slots release their exact edge before displaced bitmap
recycling. Preview/original aliasing produces two edges and one resident node;
layer reorder preserves edges. `releaseAll()` releases handles and does not
allocate or recycle a bitmap.

All ordinary state updates pass through `updateUiStateAndRecycleReplaced()`.
Open-image, current Draft, legacy Draft, and CAS metadata/direct replacement
paths use the authoritative commit helper. A failed CAS does not touch
diagnostic ownership.

## Document-generation transaction

Initialization activates the coordinator's exact initial generation. A document
replacement performs:

1. commit the actual new UI state;
2. replace coordinator history and read its exact new generation;
3. atomically `activateDocument(new, old)` in the tracker;
4. refresh generation-tagged history metrics;
5. reconcile all adopted UI slots to the new generation;
6. rebind the adopted native session to the new generation;
7. release old native/UI resources under their production ownership rules.

Completed generations archive only bounded
`CompletedGenerationPeakSummary` numeric records. The current
`HistoryMetricsSnapshot` is stored separately. Old-generation operations,
bitmap edges, native entries, or history publications cannot update a newer
generation's peak or current history snapshot.

Shutdown first invalidates editor work and releases production resources, logs
the final accepted snapshot, releases UI edge handles, and closes the one
editor-owned session. It does not clear another editor's session.

## History diagnostic contract

The coordinator publishes one immutable Main-owned snapshot tagged with its
generation and operation token. It contains hot entry count, hot resident
bytes, retained cold compressed disk bytes, deletion-debt disk bytes, active
decoded cold-load bytes, navigation direction, exact protected target ID, and
operation kind (`idle`, `loading`, `spilling`, `adopting`, `trimming`,
`maintenance`, `direct-to-cold`, or `recovery`).

Cold-load bytes are zero before decode, become the actual decoded snapshot
bytes only after decoded ownership exists, and clear only while the same
generation/token still owns the operation. Success, failure, cancellation, and
supersession all converge to zero. Stale publications are rejected by the
tracker. Metrics traverse the bounded undo/redo deques directly; publication
does not allocate combined `(undo + redo)` lists or scan storage/filesystems on
Main.

## Process-global thumbnail accounting

The cache keeps no tracker callback or strong session reference. Its one
immutable snapshot reports resident entries/bytes, total leases,
removed-but-leased entries/bytes, and the oversized-uncached leased subset.

| Transition | Resident ownership | Removed/leased ownership | Lease total |
|---|---|---|---|
| accepted cached decode with N waiters | add once | none | +N |
| cache hit | unchanged | unchanged | +1 |
| eviction/invalidate/clear with leases | remove | add once | unchanged |
| oversized uncached decode | none | add once, marked oversized | +N |
| lease close | unchanged unless removed | remove on 1 -> 0 and recycle | -1 |

Memory-increasing transitions notify current debug sessions without retaining
them. Each tracker snapshot captures the global cache snapshot once.

## RAM and disk semantics

`combinedKnownEstimatedBytes` and its per-generation CAS peak are:

`deduplicated current-generation bitmap nodes`
`+ current hot-history resident bytes`
`+ active cold-load decoded bytes`
`+ current-generation operation reserves`
`+ known current-generation native/model bytes`
`+ global thumbnail resident bytes`
`+ global removed-but-leased bytes`

History cold compressed bytes and deletion debt are disk-only fields and never
enter RAM totals. Oversized thumbnail bytes are a subset of
removed-but-leased bytes and are not added twice. Unknown native/model
contributors use `unknownNativeContributorCount`; the known numeric total stays
usable, `combinedHasUnknownContributors` is explicit, and the complete estimate
is null while any contributor is unknown.

## Production instrumentation

| Path | Actual instrumentation |
|---|---|
| Open image | decode edge, native session, UI adoption transfer, document replacement |
| Parameter, Auto Enhance, preset, engine change, reset | scope before owned input/result allocations |
| Rotate and native quick effects | exact input/result/mask edges and central UI transfer |
| Crop | auto-straighten input; apply input, masks, transformed outputs |
| Selection | brush mask, subject input/result, live preview inputs/result, local render and native-bake outputs |
| Remaster | preparation input, inference mask, original and preview outputs |
| FlareGuard | input, model/rule result and rendered preview |
| History Undo/Redo | generation/token operation reserve plus coordinator hot/cold/load metrics |
| Draft | save copies, current-generation restore base/masks/render, legacy restore base/render |
| Export | dirty preparation copy and render result; clean export operation reserve/result |
| Thumbnail cache | exact global resident/lease/removed state and peak notifications |
| Replacement/shutdown | generation rebind, final snapshot, exact close |

Model-runner allocations whose libraries do not expose defensible byte sizes
remain explicit unknown contributors rather than invented estimates.

## Tests and validation (2026-07-25)

Focused tracker/UI/cache tests passed, including synchronized release/acquire
and close/register races, collision-safe identity lookup, independent
same-owner handles, recycle-before-release, holder conditional removal,
generation-stale history rejection, RAM/disk separation, unknown native
semantics, UI generation rebinding/alias/reorder/release ordering, and exact
thumbnail lease/removed-byte transitions.

- `./gradlew :app:compileDebugKotlin --stacktrace`: passed. The first source
  diagnostic was malformed `try { lock.withLock { ... } } catch` bracing in
  `registerDocument`, `unregisterDocument`, and compatibility unregister; the
  lock blocks were closed correctly. The host Kotlin daemon marker file was
  access-denied, but Gradle's fallback compiler succeeded.
- `./gradlew :app:compileReleaseKotlin --stacktrace`: passed, confirming the
  nullable release session/no-op path compiles as a release variant. The
  architecture test also verifies that the null-session path creates no holder
  entry or operation/bitmap token.
- Focused tracker/UI/thumbnail command: passed.
- `./gradlew :app:testDebugUnitTest`: passed, 73 tests. The first run exposed a
  stale `saturatingAdd(-1, 1)` expectation; the test now matches the existing
  contract that non-positive contributors are ignored.
- `./gradlew assembleDebug`: blocked in native compilation because Windows
  denied execution of the installed NDK
  `.../ndk/28.2.13676358/.../clang++.exe`. Kotlin, Java, resources, dex setup,
  and CMake configuration had completed. The blocked command was not retried.
- `git diff --check`, strict UTF-8/no-BOM, conflict-marker, and temporary-file
  checks: passed.

## Remaining unmeasured contributors

- opaque MediaPipe/TFLite interpreter arenas, delegates, and vendor-native
  buffers;
- decoder/encoder and MediaStore buffers not surfaced as owned bitmaps;
- Compose/render-thread/GPU allocations;
- filesystem and platform caches outside the thumbnail/history ledgers.

These prevent claiming a complete process-memory estimate.

## Phase 3B candidates

1. Measure the existing full-size owned input copies per operation and remove
   only copies proven unnecessary by ownership/cancellation tests.
2. Measure export decode/render/encode overlap and shorten lifetimes without
   changing pixels, resolution, or file output.
3. Measure history capture/materialization overlap and reduce duplicate
   snapshots only after Stage 2 ownership invariants are preserved.

Tiling/ROI editing remains a deferred algorithm-specific hypothesis requiring
pixel-equivalence and seam tests. Reduced resolution and other pixel-changing
ideas are excluded. ALPHA_8 mask conversion is Phase 3C, not an approved Phase
3B change.
