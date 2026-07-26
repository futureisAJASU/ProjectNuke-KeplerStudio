package com.projectnuke.keplerstudio.bridge

import com.projectnuke.keplerstudio.editor.BitmapMemoryBudget

internal enum class NativeScratchKind {
    MainRender,
    SpecialEffect,
    FlareCorrection,
    FlareMask,
    Crop,
    SelectionBlend,
}

internal data class NativeScratchPlan(
    val kind: NativeScratchKind,
    val knownBytes: Long,
    val unknownContributor: Boolean = false,
) {
    val withinNativeBudget: Boolean
        get() = knownBytes in 0L..MAX_NATIVE_TEMPORARY_BYTES

    fun admitted(operationBudgetBytes: Long = BitmapMemoryBudget.operationReserveBytes()): Boolean =
        withinNativeBudget &&
            knownBytes <= operationBudgetBytes &&
            BitmapMemoryBudget.canAllocate(knownBytes)

    companion object {
        const val MAX_NATIVE_TEMPORARY_BYTES = 256L * 1024L * 1024L
    }
}

internal object NativeScratchPlanner {
    fun mainRender(rowBytes: Int, needsFiveRows: Boolean, needsThreeRows: Boolean): NativeScratchPlan {
        val rowCount = when {
            needsFiveRows -> 5L
            needsThreeRows -> 3L
            else -> 0L
        }
        return NativeScratchPlan(
            NativeScratchKind.MainRender,
            checkedMultiply(positive(rowBytes), rowCount),
        )
    }

    fun specialEffect(rowBytes: Int, height: Int, effect: Int): NativeScratchPlan =
        NativeScratchPlan(
            NativeScratchKind.SpecialEffect,
            if (effect == 0 || effect == 3) bitmapBytes(rowBytes, height) else 0L,
        )

    fun flareCorrection(rowBytes: Int, width: Int, height: Int): NativeScratchPlan {
        val maxDimension = maxOf(width, height)
        val scale = when {
            maxDimension > 6144 -> 4
            maxDimension > 3072 -> 2
            else -> 1
        }
        val scaledWidth = ceilDivide(positive(width), scale.toLong())
        val scaledHeight = ceilDivide(positive(height), scale.toLong())
        val planeBytes =
            checkedMultiply(
                checkedMultiply(scaledWidth, scaledHeight),
                Float.SIZE_BYTES.toLong(),
            )
        return NativeScratchPlan(
            NativeScratchKind.FlareCorrection,
            checkedAdd(bitmapBytes(rowBytes, height), checkedMultiply(planeBytes, 3L)),
        )
    }

    fun flareMask(width: Int, height: Int, radius: Int, passes: Int): NativeScratchPlan {
        val pixels = checkedMultiply(positive(width), positive(height))
        val needsBlur = radius > 0 && passes > 0 && width > 1 && height > 1
        return NativeScratchPlan(
            NativeScratchKind.FlareMask,
            checkedMultiply(pixels, if (needsBlur) 2L else 1L),
        )
    }

    fun crop(): NativeScratchPlan = NativeScratchPlan(NativeScratchKind.Crop, 0L)

    fun selectionBlend(): NativeScratchPlan =
        NativeScratchPlan(NativeScratchKind.SelectionBlend, 0L)

    private fun bitmapBytes(rowBytes: Int, height: Int): Long =
        checkedMultiply(positive(rowBytes), positive(height))

    private fun positive(value: Int): Long {
        require(value > 0) { "Native dimensions and strides must be positive" }
        return value.toLong()
    }

    private fun ceilDivide(value: Long, divisor: Long): Long =
        checkedAdd(value, divisor - 1L) / divisor

    private fun checkedMultiply(left: Long, right: Long): Long =
        runCatching { Math.multiplyExact(left, right) }
            .getOrElse { throw IllegalArgumentException("Native scratch size overflow", it) }

    private fun checkedAdd(left: Long, right: Long): Long =
        runCatching { Math.addExact(left, right) }
            .getOrElse { throw IllegalArgumentException("Native scratch size overflow", it) }
}
