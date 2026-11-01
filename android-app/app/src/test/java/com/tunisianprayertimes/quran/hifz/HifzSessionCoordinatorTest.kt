package com.tunisianprayertimes.quran.hifz

import android.app.Application
import android.content.Context
import android.os.Looper
import com.tunisianprayertimes.quran.QuranVerseReference
import com.tunisianprayertimes.quran.audio.*
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HifzSessionCoordinatorTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val notebook get() = File(context.filesDir, "hifz/hifz.json")
    private var clock = System.currentTimeMillis()
    private val sessions = mutableListOf<HifzSessionCoordinator>()
    private val played = mutableListOf<QuranPlaylist>()
    private fun point(id: Long = 1, ayah: Int = 2, due: Long = epochDay(clock) - 1) = HifzPoint(
        id, QuranVerseReference(67, ayah), listOf(HifzMistake(HifzKind.Word)), dueDay = due, createdAt = clock - 86_400_000L,
    )

    @Before fun prepare() {
        listOf("hifz_shared_session", "hifz_learning", "quran_hifz", QuranPlaybackService.PREFERENCES).forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        notebook.parentFile!!.mkdirs()
        notebook.writeText(encodeHifzPoints(emptyList()))
        HifzRepository.retryLoad(context)
        QuranAudioController.publish(QuranPlaybackState())
    }

    @After fun dispose() { sessions.forEach { it.dispose() } }

    private fun install(vararg points: HifzPoint) {
        notebook.writeText(encodeHifzPoints(points.toList()))
        assertTrue(HifzRepository.retryLoad(context))
    }

    private fun coordinator(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)): HifzSessionCoordinator {
        val coordinator = HifzSessionCoordinator(context, scope, now = { clock })
        sessions += coordinator
        runBlocking { coordinator.initialize() }
        coordinator.phoneAudio = object : HifzSessionAudio {
            override fun play(list: QuranPlaylist) { played += list }
            override fun pause() = Unit
            override fun restore(place: String?) = Unit
        }
        return coordinator
    }

    private fun settle() { shadowOf(Looper.getMainLooper()).idleFor(500, TimeUnit.MILLISECONDS) }
    private fun reveal(coordinator: HifzSessionCoordinator) {
        settle()
        assertTrue(coordinator.reveal())
        settle()
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Shared session did not complete its write", condition())
    }

    @Test fun confirmedDailyAnswerUsesTheExistingGradingAlgorithmAndAdvancesOnce() {
        val first = point()
        install(first, point(2, 3))
        val coordinator = coordinator()
        assertTrue(coordinator.startDaily())
        reveal(coordinator)
        coordinator.assess(HifzGrade.Held)
        await { !coordinator.savingAnswer && HifzRepository.points.value.first().checks.isNotEmpty() }
        val actual = HifzRepository.points.value.first()
        val expected = first.graded(HifzGrade.Held, epochDay(clock), actual.checks.single().at)
        assertEquals(expected, actual)
        assertEquals(1, coordinator.view().tally.held)
        assertEquals(2L, coordinator.view().point!!.id)
    }

    @Test fun playbackPauseAndMediaNextDoNotSupplyAnAssessment() {
        val first = point()
        install(first)
        val coordinator = coordinator()
        assertTrue(coordinator.startDaily())
        coordinator.cue()
        assertEquals(1, played.size)
        coordinator.pause()
        coordinator.resume()
        coordinator.next()
        assertEquals(first, HifzRepository.points.value.single())
        assertEquals(0, coordinator.view().tally.graded)
        assertEquals(HifzSessionPhase.Question, coordinator.view().phase)
    }

    @Test fun hearingAnAnswerBeforeRevealCannotBecomeAnUnaidedRecall() {
        install(point())
        val coordinator = coordinator()
        coordinator.startDaily()
        coordinator.reviewState.noteExposed(setOf(point().verse), coordinator.reviewActions)
        reveal(coordinator)
        assertTrue(coordinator.view().assisted)
        coordinator.assess(HifzGrade.Held)
        await { !coordinator.savingAnswer && HifzRepository.points.value.single().checks.isNotEmpty() }
        assertEquals(HifzGrade.Hesitated, HifzRepository.points.value.single().checks.single().grade)
        assertEquals(0, coordinator.view().tally.held)
        assertEquals(1, coordinator.view().tally.hesitated)
    }

    @Test fun notebookPracticeKeepsTheExistingNonDueAnswerSemantics() {
        val first = point(due = epochDay(clock) + 5)
        install(first)
        val coordinator = coordinator()
        coordinator.startNotebook()
        reveal(coordinator)
        coordinator.assess(HifzGrade.Held)
        await { !coordinator.savingAnswer && coordinator.practiceState.answer(first) != null }
        assertEquals(first, HifzRepository.points.value.single())
        assertEquals(HifzGrade.Held, coordinator.practiceState.answer(first))
    }

    @Test fun aForgottenNonDueNotebookPointStillRecordsTheExistingSlip() {
        install(point(due = epochDay(clock) + 5))
        val coordinator = coordinator()
        coordinator.startNotebook()
        reveal(coordinator)
        coordinator.assess(HifzGrade.Again)
        await { !coordinator.savingAnswer && HifzRepository.points.value.single().checks.isNotEmpty() }
        val actual = HifzRepository.points.value.single()
        assertEquals(HifzGrade.Again, actual.checks.single().grade)
        assertTrue(actual.fresh > 0)
    }

    @Test fun simultaneousAssessmentCallbacksCannotWriteTwoGrades() {
        install(point())
        val coordinator = coordinator()
        coordinator.startDaily()
        reveal(coordinator)
        coordinator.assess(HifzGrade.Held)
        coordinator.assess(HifzGrade.Again)
        await { !coordinator.savingAnswer && HifzRepository.points.value.single().checks.isNotEmpty() }
        assertEquals(listOf(HifzGrade.Held), HifzRepository.points.value.single().checks.map { it.grade })
        assertEquals(1, coordinator.view().tally.graded)
    }

    @Test fun journalRecoveryBeforeTheNotebookWriteAppliesTheExplicitAnswerOnce() = recoverJournal(false)
    @Test fun journalRecoveryAfterTheNotebookWriteDoesNotApplyTheAnswerAgain() = recoverJournal(true)

    private fun recoverJournal(alreadyWritten: Boolean) {
        val first = point()
        install(first)
        val pausedScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
        val coordinator = coordinator(pausedScope)
        coordinator.startDaily()
        reveal(coordinator)
        coordinator.assess(HifzGrade.Held)
        runBlocking { coordinator.persist() }
        val pending = JSONObject(context.getSharedPreferences("hifz_shared_session", Context.MODE_PRIVATE).getString("session", null)!!)
            .getJSONObject("pending")
        if (alreadyWritten) assertTrue(HifzRepository.gradeReview(context, first, HifzGrade.Held, pending.getLong("day"), pending.getLong("at")))
        coordinator.dispose()
        val recovered = coordinator()
        assertEquals(1, HifzRepository.points.value.single().checks.size)
        assertEquals(1, recovered.view().tally.held)
        assertFalse(recovered.savingAnswer)
        assertTrue(recovered.paused)
    }

    @Test fun aDeletedPointIsNeverResurrectedByARecoveredAnswer() {
        val first = point()
        install(first)
        val original = coordinator(CoroutineScope(SupervisorJob() + StandardTestDispatcher()))
        original.startDaily()
        reveal(original)
        original.assess(HifzGrade.Held)
        runBlocking { original.persist() }
        original.dispose()
        HifzRepository.delete(context, first.id)
        val recovered = coordinator()
        assertTrue(HifzRepository.points.value.isEmpty())
        assertEquals(0, recovered.view().tally.graded)
    }

    @Test fun aSessionSurvivesRecreationWithoutAutoplayAndKeepsTheOrdinaryListeningPlace() {
        install(point())
        val coordinator = coordinator()
        QuranAudioController.publish(QuranPlaybackState(surah = 1, positionMs = 20_000L))
        coordinator.startDaily()
        coordinator.cue()
        val list = played.single()
        coordinator.notePlayback(QuranPlaybackState(surah = 67, playlist = list, positionMs = 12_345L, playlistRound = 1), force = true)
        coordinator.pause()
        runBlocking { coordinator.persist() }
        coordinator.dispose()
        played.clear()
        val recovered = coordinator()
        assertEquals(12_345L, recovered.savedClipPosition())
        assertEquals(list.requestId, recovered.resumableClip()!!.first.requestId)
        assertTrue(recovered.reviewReturn.value!!.contains("|1|20000|"))
        assertTrue(played.isEmpty())
        assertFalse(recovered.active)
    }

    @Test fun aWriteFailureLeavesTheQuestionUnansweredAndRetryIsIdempotent() {
        install(point())
        val coordinator = coordinator()
        coordinator.startDaily()
        reveal(coordinator)
        val collision = File(notebook.parentFile, "hifz.json.new").apply { mkdirs() }
        File(collision, "occupied").writeText("blocked")
        coordinator.assess(HifzGrade.Held)
        await { !coordinator.busy && coordinator.error != null }
        assertEquals(0, coordinator.view().tally.graded)
        assertTrue(HifzRepository.points.value.single().checks.isEmpty())
        assertTrue(coordinator.savingAnswer)
        collision.deleteRecursively()
        coordinator.retry()
        await { !coordinator.savingAnswer && HifzRepository.points.value.single().checks.isNotEmpty() }
        assertEquals(1, coordinator.view().tally.held)
        assertEquals(1, HifzRepository.points.value.single().checks.size)
    }
}
