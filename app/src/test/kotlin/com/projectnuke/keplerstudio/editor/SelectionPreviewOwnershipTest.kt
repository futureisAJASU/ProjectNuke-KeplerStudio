package com.projectnuke.keplerstudio.editor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionPreviewOwnershipTest {
    private val identity =
        SelectionPreviewIdentity(
            gestureId = 4L,
            previewToken = 9L,
            revision = 12,
            baseContentToken = "base-A",
            activeSelectionLayerId = "layer-A",
        )

    @Test
    fun exactTransactionRevisionBaseAndLayerOwnSettlement() {
        assertTrue(
            identity.matches(
                activeGestureId = 4L,
                latestPreviewToken = 9L,
                stateRevision = 12,
                stateBaseContentToken = "base-A",
                stateActiveSelectionLayerId = "layer-A",
            ),
        )
    }

    @Test
    fun newerPreviewOrTransactionCannotBeClearedByOldCompletion() {
        assertFalse(
            identity.matches(4L, 10L, 12, "base-A", "layer-A"),
        )
        assertFalse(
            identity.matches(5L, 9L, 12, "base-A", "layer-A"),
        )
    }

    @Test
    fun replacementRevisionBaseOrLayerRejectsStalePreview() {
        assertFalse(identity.matches(4L, 9L, 13, "base-A", "layer-A"))
        assertFalse(identity.matches(4L, 9L, 12, "base-B", "layer-A"))
        assertFalse(identity.matches(4L, 9L, 12, "base-A", "layer-B"))
    }
}
