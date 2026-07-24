# Phase 3A memory diagnostics

## Architecture

`DebugMemoryTracker` remains an object facade. Debug editor instances own a
`TrackerSession`; release editors use one stateless `NoopTrackerDiagnostics`
object. The no-op does not create an editor id, generation, lock, queue, map,
or holder entry. `ThumbnailBitmapCache` remains the existing process-global
singleton and owns its own immutable global diagnostics snapshot.

## Lifecycle and generation

`TrackerSession.close()` is idempotent and transitions Active -> Closing ->
Closed. It rejects late bitmap, operation, history, and native events, releases
diagnostic records, drains its reference queue, and conditionally removes only
itself from `TrackerSessionHolder`. `clearForTest()` is intentionally separate.

The history coordinator supplies the initial document generation. Editor setup
activates that exact value, and `clearEditHistory()` uses one ordered
`activateDocument(new, old)` transition after coordinator replacement. Completed
generations retain only bounded numeric peak summaries; no bitmap is archived.

## Ownership and transfer

`UiStateOwnershipReconciler` holds only slot -> edge-handle mappings. It
reconciles `previewBitmap`, `originalPreviewBitmap`, and stable
`selectionLayer:<id>` slots before displaced bitmaps are recycled. Alias slots
therefore expose two owner edges but one identity-deduplicated resident node.
`MemoryTrackerScope.release(handle)` removes the handle from its local set, so
scope end cannot process a transferred edge again.

## History and cache metrics

History publishes a generation-tagged Main-owned snapshot: hot count and bytes,
cold compressed bytes, deletion debt, operation states, and an internal target
id. Cold-load decoded bytes are set only by the decoded-load callback and reset
on every navigation exit.

The thumbnail cache snapshot reports resident entries/bytes, exact active lease
count, and removed-but-leased entries/bytes. Eviction, invalidation, and clear
move leased entries into the removed set; final lease close recycles and removes
the record. Cache diagnostics are read outside cache behavior and cannot affect
it.

RAM peak = deduplicated bitmap bytes + known native bytes + hot history bytes
(only where not separately represented) + active decoded cold load + active
operation reserve. Cold compressed bytes and deletion debt are disk metrics and
are excluded. Unknown native contributors are exposed as a boolean/count; the
numeric known total never uses `Long.MAX_VALUE`. Per-generation peaks use CAS
and reject superseded generations.

## Coverage and remaining work

Current instrumentation covers existing open/render/auto-enhance/preset/quick
effects/FlareGuard/rotate/export/draft/history paths. Phase 3B candidates, in
priority order: reduce full-resolution transient copies, consider tiled/ROI
editing, and evaluate ALPHA_8 masks. None are implemented here.

## Validation

`./gradlew :app:compileDebugKotlin --stacktrace` passed on 2026-07-25 (Kotlin
daemon access warnings fell back to a successful compiler). Focused unit-test
execution compiled production and test Kotlin, but the Gradle run timed out
while the environment held Android SDK archive files open; it did not report a
test assertion failure. Full build/static validation remains required after the
SDK file-lock issue is cleared.
