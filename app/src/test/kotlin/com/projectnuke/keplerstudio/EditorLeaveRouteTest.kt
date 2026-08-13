package com.projectnuke.keplerstudio

import com.projectnuke.keplerstudio.editor.EditorLeavePhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLeaveRouteTest {
    @Test
    fun newCompletedTokenIsHandledAndReplayIsIgnored() {
        assertTrue(
            shouldHandleEditorLeaveTerminal(
                EditorLeavePhase.Completed,
                token = 7L,
                lastHandledToken = null,
            )
        )
        assertFalse(
            shouldHandleEditorLeaveTerminal(
                EditorLeavePhase.Completed,
                token = 7L,
                lastHandledToken = 7L,
            )
        )
    }

    @Test
    fun newFailedTokenIsHandledAndReplayIsIgnored() {
        assertTrue(
            shouldHandleEditorLeaveTerminal(
                EditorLeavePhase.Failed,
                token = 8L,
                lastHandledToken = 7L,
            )
        )
        assertFalse(
            shouldHandleEditorLeaveTerminal(
                EditorLeavePhase.Failed,
                token = 8L,
                lastHandledToken = 8L,
            )
        )
    }

    @Test
    fun nonTerminalLeavePhaseIsNeverConsumedAsTerminal() {
        assertFalse(
            shouldHandleEditorLeaveTerminal(
                EditorLeavePhase.Quiescing,
                token = 9L,
                lastHandledToken = null,
            )
        )
        assertFalse(
            shouldHandleEditorLeaveTerminal(
                EditorLeavePhase.Saving,
                token = 9L,
                lastHandledToken = null,
            )
        )
    }
}
