package com.projectnuke.keplerstudio.editor

object PresetLookHandoff {
    private var pendingLook: PresetColorLook? = null

    fun offer(look: PresetColorLook?) {
        pendingLook = look
    }

    fun consumeOrKeep(current: PresetColorLook?): PresetColorLook? {
        val offered = pendingLook
        pendingLook = null
        return offered ?: current
    }

    fun clear() {
        pendingLook = null
    }
}
