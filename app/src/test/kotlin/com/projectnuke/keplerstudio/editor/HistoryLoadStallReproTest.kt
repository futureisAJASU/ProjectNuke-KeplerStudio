package com.projectnuke.keplerstudio.editor

import android.app.Application
import android.graphics.Bitmap
import com.projectnuke.keplerstudio.bridge.installNativeSessionFactoryForTest
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Clean-baseline reproduction for the previously observed EditorViewModel
 * startup stall at HISTORY_LOAD_STARTED. No pressure code exists on this
 * branch; this harness exercises only frozen production infrastructure:
 * seed legacy Draft -> startup restore -> adoption save -> history load ->
 * reconciliation -> startup completion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistoryLoadStallReproTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication() as Application

    private lateinit var harness: OwnedEditorViewModelHarness

    @Before
    fun setUp() {
        resetRestoredWorkingSourceSandboxForTest(context)
        resetDraftSandboxForTest(context)
        LegacyDraftSourceOwnership.clearForTest()
        harness = OwnedEditorViewModelHarness(context, installBitmapCopySeam = true)
    }

    @After
    fun tearDown() {
        val failures = CleanupFailureAggregator()
        failures.attempt { harness.close() }
        failures.attempt { resetRestoredWorkingSourceSandboxForTest(context) }
        failures.attempt { resetDraftSandboxForTest(context) }
        failures.attempt { LegacyDraftSourceOwnership.clearForTest() }
        failures.throwIfAny()
    }

    @Test
    fun startupHistoryLoadCompletes() = runBlocking {
        seedLegacyDraft(exposure = 0.27f)
        val renderer = EditorRenderer.installRendererOverrideForTest { request ->
            successRestoreOutput(request.operation)
        }
        var sessionFactory: AutoCloseable? = null
        try {
            sessionFactory = installNativeSessionFactoryForTest { 9700L }
            val vm = harness.createEditor()
            val startedAt = System.nanoTime()

            // House-standard deterministic waiter (identical budget to every
            // production suite; no sleeps, no extra timeouts, no retries).
            // Exits immediately on success.
            var remaining = 4000
            while (remaining-- > 0) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
                if (vm.startupInitCompletion.isCompleted && !vm.uiState.value.isBusy) break
                shadowOf(android.os.Looper.getMainLooper()).idle()
                yieldToEditorBackgroundForTest()
            }

            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
            val completed = vm.startupInitCompletion.isCompleted && !vm.uiState.value.isBusy
            assertTrue(
                "startup did not complete: stage=${vm.lastStartupStageForTest} " +
                    "gen=${vm.uiState.value.draftGenerationId} busy=${vm.uiState.value.isBusy} " +
                    "msg=${vm.uiState.value.message} elapsedMs=$elapsedMs",
                completed,
            )
            println("HISTORY-LOAD-REPRO: completed in ${elapsedMs}ms stage=COMPLETED")
            // Startup completion does NOT join the adoption autosave (the
            // restore launches it without joining); wait for its terminal
            // state deterministically, as every production suite does.
            var saveRemaining = 4000
            while (saveRemaining-- > 0) {
                shadowOf(android.os.Looper.getMainLooper()).idleFor(20, TimeUnit.MILLISECONDS)
                if (!vm.hasActiveDraftSaveJobForTest() &&
                    vm.uiState.value.draftGenerationId != null
                ) break
                shadowOf(android.os.Looper.getMainLooper()).idle()
                yieldToEditorBackgroundForTest()
            }
            assertNotNull(
                "adoption save must publish a generation; src=${vm.uiState.value.sourcePath} " +
                    "draftSrc=${vm.uiState.value.draftSourcePath} msg=${vm.uiState.value.message} " +
                    "jobActive=${vm.hasActiveDraftSaveJobForTest()} prefs=${draftPrefs().all}",
                vm.uiState.value.draftGenerationId,
            )
            assertEquals(
                vm.uiState.value.draftGenerationId,
                draftPrefs().getString(KEY_DRAFT_GENERATION_ID, null),
            )
        } finally {
            sessionFactory?.close()
            renderer.close()
        }
    }

    private fun draftPrefs() =
        context.getSharedPreferences(PREF_NAME_DRAFT, android.content.Context.MODE_PRIVATE)

    private fun seedLegacyDraft(exposure: Float): File {
        val payload = context.cacheDir.resolve("stall-repro-seed.png")
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            payload.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
        } finally {
            bitmap.recycle()
        }
        val legacyDirectory = context.filesDir.resolve("drafts/current").apply { mkdirs() }
        val legacySource = legacyDirectory.resolve("source.img")
        payload.copyTo(legacySource, overwrite = true)
        draftPrefs()
            .edit()
            .putString(KEY_DRAFT_SOURCE, legacySource.absolutePath)
            .putFloat("draft_exposure", exposure)
            .commit()
        return legacySource
    }

    private fun successRestoreOutput(operation: RenderOperation): RenderResult.Success {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        return RenderResult.Success(
            operation = operation,
            requestedRoute = NativeRenderRoute.V1,
            output = bitmap,
            actualRoute = NativeRenderRoute.V1,
            decision = RenderRouteDecision.FollowDocument,
            usedDebugOverride = false,
            algorithmVersion = AlgorithmContracts.NATIVE_V1,
            participation = RenderParticipation(),
            durationMillis = 0L,
            knownTransientBytes = 0L,
        )
    }
}
