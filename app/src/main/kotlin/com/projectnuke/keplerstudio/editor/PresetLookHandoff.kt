package com.projectnuke.keplerstudio.editor

object PresetLookHandoff {
    private var pendingLook: PresetColorLook? = null
    private var activeLook: PresetColorLook? = null

    fun offer(look: PresetColorLook?) {
        pendingLook = look
        if (look == null) activeLook = null
    }

    fun consumeActive(): PresetColorLook? {
        val offered = pendingLook
        pendingLook = null
        if (offered != null) activeLook = offered
        return activeLook
    }

    fun clear() {
        pendingLook = null
        activeLook = null
    }
}
