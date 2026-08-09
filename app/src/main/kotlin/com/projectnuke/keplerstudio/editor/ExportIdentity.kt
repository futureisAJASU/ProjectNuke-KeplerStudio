package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.Job

/**
 * One coherent captured export identity, owned by `exportPreview()` and
 * evaluated by the production export pipeline's `isCurrent` callback.
 *
 * An export is current only while *every* component below matches the live
 * ViewModel state:
 *  - its owning [Job] is still the registered export job and is still active;
 *  - [token] is unchanged (no newer export has bumped `exportToken`);
 *  - shutdown has not begun;
 *  - [sourcePath], [baseToken] and [revision] are unchanged.
 *
 * The pipeline never modifies this identity; it only reads it through the
 * [ExportIdentity.evaluate] callback. Capturing the owning [Job] at creation
 * keeps a stale export from impersonating a newer one even after its
 * coroutine has been cancelled.
 */
internal data class ExportIdentity(
    val token: Long,
    val sourcePath: String,
    val baseToken: String,
    val revision: Int,
    val owningJob: Job,
)
