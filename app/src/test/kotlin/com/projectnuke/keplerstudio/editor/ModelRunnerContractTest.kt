package com.projectnuke.keplerstudio.editor

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ModelRunnerContractTest {
    @Test
    fun operationContextRejectsCancellationBeforeInference() {
        val context =
            ModelOperationContext(
                operationToken = 7,
                documentGeneration = "11",
                isCancelled = { true },
            )

        assertFailsWith<CancellationException> { context.validateOrThrow() }
    }

    @Test
    fun operationContextRejectsStaleGenerationBeforePublication() {
        val context =
            ModelOperationContext(
                operationToken = 7,
                documentGeneration = "11",
                isCurrent = { token, generation -> token == 8L && generation == "11" },
            )

        assertFailsWith<StaleModelGenerationException> { context.validateOrThrow() }
    }
}
