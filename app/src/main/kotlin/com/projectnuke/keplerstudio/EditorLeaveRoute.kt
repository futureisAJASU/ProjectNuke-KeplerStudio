package com.projectnuke.keplerstudio

import com.projectnuke.keplerstudio.editor.EditorLeavePhase

/**
 * Returns whether MainActivity should consume a terminal leave result.
 *
 * The leave StateFlow intentionally retains its terminal value until an
 * explicit editor action acknowledges it.  A recreated Activity may observe
 * the same terminal token again, so terminal navigation/toast handling must
 * be identity-based rather than phase-based.
 */
internal fun shouldHandleEditorLeaveTerminal(
    phase: EditorLeavePhase,
    token: Long,
    lastHandledToken: Long?,
): Boolean =
    (phase == EditorLeavePhase.Completed || phase == EditorLeavePhase.Failed) &&
        token != lastHandledToken
