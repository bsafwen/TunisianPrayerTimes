package com.tunisianprayertimes

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.util.AtomicFile
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.*
import com.tunisianprayertimes.quran.hifz.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Real MediaSession callbacks against temporary points; the user's notebook and preferences are restored. */
@RunWith(AndroidJUnit4::class)
class QuranAutoReviewInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val core get() = HifzSessions.get(context)
    private val notebook get() = File(context.filesDir, "hifz/hifz.json")
    private val originals = mutableMapOf<String, Map<String, *>>()
    private val files = mutableMapOf<File, ByteArray?>()
    private lateinit var fixture: List<HifzPoint>

    @Before fun preserveUserDataAndInstallTemporaryPoints() {
        main { core.initialize(); require(!core.savingAnswer); core.persist() }
        listOf("hifz_shared_session", "hifz_learning", "quran_hifz", "quran_auto", "prayer_silence_prefs", QuranPlaybackService.PREFERENCES).forEach {
            originals[it] = context.getSharedPreferences(it, Context.MODE_PRIVATE).all.toMap()
        }
        listOf(notebook, File("${notebook.path}.bak"), File("${notebook.path}.new")).forEach { files[it] = it.takeIf(File::isFile)?.readBytes() }
        main { core.clear(); core.dismissError() }
        HifzSettings.write(context, HifzSettings.read(context).copy(reminder = false))
        context.getSharedPreferences("hifz_learning", Context.MODE_PRIVATE).edit().clear().commit()
        val now = System.currentTimeMillis()
        fixture = listOf(2, 4, 6).mapIndexed { i, ayah -> HifzPoint(
            9_800_001L + i, QuranVerseReference(67, ayah), listOf(HifzMistake(HifzKind.Word)),
            dueDay = todayEpochDay() + if (i == 2) 5 else -1, createdAt = now - 86_400_000L,
        ) }
        AtomicFile(notebook).delete()
        notebook.parentFile!!.mkdirs()
        notebook.writeText(encodeHifzPoints(fixture))
        HifzRepository.reload(context)
    }

    @After fun restoreUserData() {
        if (originals.isEmpty()) return
        await { !core.busy }
        main { core.pause(); core.clear(); core.dismissError(); core.persist() }
        AtomicFile(notebook).delete()
        files.forEach { (file, bytes) -> if (bytes != null) { file.parentFile!!.mkdirs(); file.writeBytes(bytes) } }
        HifzRepository.reload(context)
        originals.forEach { (name, values) -> restorePreferences(context.getSharedPreferences(name, Context.MODE_PRIVATE), values) }
        val saved = originals["hifz_shared_session"]?.get("session") as? String
        main {
            if (saved != null) {
                HifzSessionCoordinator::class.java.getDeclaredMethod("restore", JSONObject::class.java)
                    .apply { isAccessible = true }.invoke(core, JSONObject(saved))
            }
            core.leaveCar()
            core.dismissError()
            core.persist()
        }
        HifzReminder.refresh(context)
        files.forEach { (file, bytes) -> if (bytes != null && file == notebook) assertArrayEquals(bytes, file.readBytes()) }
    }

    @Test fun dailyReviewRequiresAnExplicitAnswerAndResumesTheSameNextQuestion() = browser { browser ->
        val controller = MediaController(context, browser.sessionToken)
        controller.transportControls.playFromMediaId(QuranAutoReview.DAILY_ID, null)
        await { core.mode == HifzSessionMode.Daily && core.view().point?.id == fixture.first().id && core.active }
        await { controller.playbackState?.customActions?.any { it.action == "${QuranAutoReview.ACTION_PREFIX}${core.view().token}/reveal" } == true }
        assertEquals(0L, controller.playbackState!!.actions and PlaybackState.ACTION_SKIP_TO_NEXT)
        controller.transportControls.skipToNext()
        Thread.sleep(450)
        assertEquals(fixture.first().id, core.view().point!!.id)
        reveal(controller)
        assertTrue(HifzRepository.points.value.first().checks.isEmpty())
        val oldAgain = action(controller, "again")
        controller.transportControls.sendCustomAction(action(controller, "held"), null)
        await { !core.savingAnswer && core.view().point?.id == fixture[1].id }
        val saved = HifzRepository.points.value.first()
        assertEquals(1, saved.checks.size)
        assertEquals(HifzGrade.Held, saved.checks.single().grade)
        controller.transportControls.sendCustomAction(oldAgain, null)
        Thread.sleep(450)
        assertTrue(HifzRepository.points.value[1].checks.isEmpty())
        controller.transportControls.stop()
        await { core.paused && !QuranAudioController.state.value.playing }
        controller.transportControls.play()
        await { core.active && core.view().point?.id == fixture[1].id }
        assertEquals(1, HifzRepository.points.value.first().checks.size)
    }

    @Test fun notebookReviewKeepsThePhoneRulesForANonDueRecall() = browser { browser ->
        val controller = MediaController(context, browser.sessionToken)
        controller.transportControls.playFromMediaId(QuranAutoReview.NOTEBOOK_ID, null)
        await { core.mode == HifzSessionMode.Notebook && core.active }
        controller.transportControls.skipToNext()
        await { core.view().index == 1 }
        controller.transportControls.skipToNext()
        await { core.view().index == 2 }
        Thread.sleep(500)
        reveal(controller)
        controller.transportControls.sendCustomAction(action(controller, "held"), null)
        await { !core.savingAnswer && core.view().phase == HifzSessionPhase.Finished }
        val point = HifzRepository.points.value.single { it.id == fixture[2].id }
        assertEquals(fixture[2], point)
        assertEquals(HifzGrade.Held, core.practiceState.answer(point))
    }

    @Test fun thePhoneContinuesTheCarQuestionAndItsAnswerUpdatesTheHeadUnit() = browser { browser ->
        val controller = MediaController(context, browser.sessionToken)
        controller.transportControls.playFromMediaId(QuranAutoReview.DAILY_ID, null)
        await { core.mode == HifzSessionMode.Daily && core.active }
        Thread.sleep(500)
        reveal(controller)
        val questionId = core.view().point!!.id
        // This test exercises a returning user's handoff; retain the device's original onboarding preference.
        PrefsManager.markFirstLaunchDone(context)
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_QURAN)
            .putExtra(MainTabNavigation.EXTRA_QURAN_ACTION, MainTabNavigation.QURAN_ACTION_HIFZ)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("hifz_continue_shared").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("hifz_continue_shared").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("hifz_grade_held").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(questionId, core.view().point!!.id)
            Thread.sleep(500)
            compose.onNodeWithTag("hifz_grade_held").performClick()
            await { !core.savingAnswer && core.view().point?.id == fixture[1].id }
            await { controller.metadata?.description?.title?.toString() == core.view().question }
            assertEquals(HifzGrade.Held, HifzRepository.points.value.first().checks.single().grade)
        }
    }

    /** Explicitly selected only when taking real head-unit screenshots. */
    @Test fun headUnitPreview() = browser { browser ->
        val hold = InstrumentationRegistry.getArguments().getString("preview_ms")?.toLongOrNull() ?: 0L
        Assume.assumeTrue("Preview hold was not requested", hold > 0)
        val controller = MediaController(context, browser.sessionToken)
        controller.transportControls.playFromMediaId(QuranAutoReview.DAILY_ID, null)
        await { core.mode == HifzSessionMode.Daily && core.active }
        val until = System.currentTimeMillis() + hold.coerceAtMost(300_000L)
        while (System.currentTimeMillis() < until) Thread.sleep(500)
        controller.transportControls.pause()
    }

    private fun reveal(controller: MediaController) {
        await { controller.playbackState?.customActions?.any { it.action == "${QuranAutoReview.ACTION_PREFIX}${core.view().token}/reveal" } == true }
        Thread.sleep(500)
        controller.transportControls.sendCustomAction(action(controller, "reveal"), null)
        await { core.view().phase == HifzSessionPhase.Assessment }
        await { QuranAudioController.state.value.let { it.playing && it.playlist?.title == HifzListenOneTitle && it.ayah == core.view().point?.verse?.ayah } }
        Thread.sleep(500)
    }

    private fun action(controller: MediaController, command: String): String =
        controller.playbackState!!.customActions.single { it.action.endsWith("/$command") }.action

    private fun browser(block: (MediaBrowser) -> Unit) {
        val connected = CountDownLatch(1)
        lateinit var browser: MediaBrowser
        instrumentation.runOnMainSync {
            browser = MediaBrowser(context, ComponentName(context, QuranAutoBrowserService::class.java),
                object : MediaBrowser.ConnectionCallback() { override fun onConnected() { connected.countDown() } }, null)
            browser.connect()
        }
        try { assertTrue(connected.await(15, TimeUnit.SECONDS)); block(browser) }
        finally { instrumentation.runOnMainSync { browser.disconnect() } }
    }

    private fun main(block: suspend () -> Unit) = runBlocking { withContext(Dispatchers.Main.immediate) { block() } }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(30)
        assertTrue("Timed out: ${core.view()} / ${core.error} / ${QuranAudioController.state.value}", condition())
    }

    private fun restorePreferences(prefs: SharedPreferences, values: Map<String, *>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        assertTrue(editor.commit())
    }
}
