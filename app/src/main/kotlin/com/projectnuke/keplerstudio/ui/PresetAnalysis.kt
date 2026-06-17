package com.projectnuke.keplerstudio.ui

import android.content.Context
import android.net.Uri
import com.projectnuke.keplerstudio.editor.EditParams

fun estimatePresetFromBeforeAfter(
    context: Context,
    beforeUri: Uri,
    afterUri: Uri
): EditParams = estimateCurveMatchedPresetFromPair(
    context = context,
    beforeUri = beforeUri,
    afterUri = afterUri
)

fun estimatePresetFromReference(
    context: Context,
    referenceUri: Uri
): EditParams = estimateCurveMatchedPresetFromReference(
    context = context,
    referenceUri = referenceUri
)
