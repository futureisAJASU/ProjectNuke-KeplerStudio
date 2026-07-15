package com.projectnuke.keplerstudio.editor

import android.graphics.Bitmap

enum class SelectionLayerKind(val label: String) {
    Subject("피사체"),
    Background("배경"),
    Sky("하늘"),
    Brush("브러시")
}

enum class SelectionPaintMode(val label: String) {
    Add("더하기"),
    Remove("빼기")
}

data class SelectionPaintSettings(
    val mode: SelectionPaintMode = SelectionPaintMode.Add,
    val sizePx: Float = 120f,
    val feather: Float = 0.55f,
    val strength: Float = 0.70f
)

data class SelectionLayer(
    val id: String,
    val name: String,
    val kind: SelectionLayerKind,
    val bitmap: Bitmap,
    val enabled: Boolean = true,
    val inverted: Boolean = false,
    val opacity: Float = 1f,
    val localParams: EditParams = EditParams()
)

fun List<SelectionLayer>.copyBitmapsOwned(): List<SelectionLayer> {
    val copies = ArrayList<SelectionLayer>(size)
    try {
        for (layer in this) copies += layer.copy(bitmap = layer.bitmap.copyOrThrow())
        return copies
    } catch (t: Throwable) {
        copies.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        throw t
    }
}
