package com.tunisianprayertimes.quran.hifz

import android.app.Application
import com.tunisianprayertimes.quran.QuranVerseReference
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class HifzRepositoryStorageTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val directory: File get() = File(app.filesDir, "hifz")
    private val notebook: File get() = File(directory, "hifz.json")
    private val verse = QuranVerseReference(2, 18)

    @Before
    fun prepare() {
        resetMemory()
        directory.deleteRecursively()
    }

    @After
    fun cleanUp() {
        directory.deleteRecursively()
        resetMemory()
    }

    @Test
    fun failedWriteKeepsPreviouslySavedStateVisible_andCanBeRetried() {
        val saved = HifzRepository.record(app, verse, HifzKind.Word, now = 1_000L)
        val originalBytes = notebook.readBytes()
        // A nonempty directory at AtomicFile's pending-write path forces a real file-open failure.
        val collision = File(directory, "hifz.json.new").apply { mkdirs() }
        File(collision, "occupied").writeText("blocked")

        val failure = runCatching { HifzRepository.update(app, saved.copy(key = "new note")) }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertEquals(listOf(saved), HifzRepository.points.value)
        assertTrue(originalBytes.contentEquals(notebook.readBytes()))
        collision.deleteRecursively()
        val edited = saved.copy(key = "new note")
        HifzRepository.update(app, edited)
        resetMemory()
        HifzRepository.load(app)
        assertEquals(listOf(edited), HifzRepository.points.value)
    }

    @Test
    fun malformedDevelopmentNotebookResets_andNewEntriesSurviveRestart() {
        directory.mkdirs()
        notebook.writeText("{broken notebook")

        HifzRepository.load(app)

        assertTrue(HifzRepository.isLoaded)
        assertTrue(HifzRepository.points.value.isEmpty())
        assertEquals(emptyList<HifzPoint>(), decodeHifzPoints(notebook.readText()))
        assertEquals(listOf(notebook), directory.listFiles()!!.toList())
        val saved = HifzRepository.record(app, verse, HifzKind.Word, now = 1_000L)
        resetMemory()
        HifzRepository.load(app)
        assertEquals(listOf(saved), HifzRepository.points.value)
    }

    @Test
    fun unsupportedDevelopmentNotebookResetsPersistently_withoutRecoveryCopies() {
        directory.mkdirs()
        notebook.writeText("{\"version\":999,\"points\":[]}")

        HifzRepository.load(app)

        assertTrue(HifzRepository.isLoaded)
        assertTrue(HifzRepository.points.value.isEmpty())
        val resetBytes = notebook.readBytes()
        assertEquals(emptyList<HifzPoint>(), decodeHifzPoints(notebook.readText()))
        assertEquals(listOf(notebook), directory.listFiles()!!.toList())
        resetMemory()
        HifzRepository.load(app)
        assertTrue(HifzRepository.points.value.isEmpty())
        assertTrue(resetBytes.contentEquals(notebook.readBytes()))
    }

    @Test
    fun oldRecoveryCopiesAreIgnored_withOrWithoutAnActiveNotebook() {
        val saved = HifzRepository.record(app, verse, HifzKind.Word, now = 1_000L)
        val retained = File(directory, "hifz.json.bad-old")
        val oldBytes = encodeHifzPoints(listOf(saved.copy(key = "obsolete copy"))).toByteArray()
        retained.writeBytes(oldBytes)

        resetMemory()
        HifzRepository.load(app)

        assertEquals(listOf(saved), HifzRepository.points.value)
        assertEquals(listOf(saved), decodeHifzPoints(notebook.readText()))
        assertTrue(oldBytes.contentEquals(retained.readBytes()))
        assertTrue(notebook.delete())
        resetMemory()
        HifzRepository.load(app)
        assertTrue(HifzRepository.isLoaded)
        assertTrue(HifzRepository.points.value.isEmpty())
        assertFalse(notebook.exists())
        assertTrue(oldBytes.contentEquals(retained.readBytes()))
    }

    @Test
    fun diskReadFailureDoesNotResetTheNotebook_andLoadingCanBeRetried() {
        notebook.mkdirs()
        val occupied = File(notebook, "occupied").apply { writeText("blocked") }

        assertTrue(runCatching { HifzRepository.load(app) }.exceptionOrNull() is IOException)

        assertFalse(HifzRepository.isLoaded)
        assertTrue(notebook.isDirectory)
        assertEquals("blocked", occupied.readText())
        notebook.deleteRecursively()
        notebook.writeText(encodeHifzPoints(emptyList()))
        HifzRepository.load(app)
        assertTrue(HifzRepository.isLoaded)
    }

    @Test
    fun failedDevelopmentResetDoesNotPublishAnEmptyNotebook_andCanBeRetried() {
        directory.mkdirs()
        val damaged = "{broken notebook"
        notebook.writeText(damaged)
        val collision = File(directory, "hifz.json.new").apply { mkdirs() }
        File(collision, "occupied").writeText("blocked")

        assertTrue(runCatching { HifzRepository.load(app) }.exceptionOrNull() is IOException)

        assertFalse(HifzRepository.isLoaded)
        assertEquals(damaged, notebook.readText())
        collision.deleteRecursively()
        HifzRepository.load(app)
        assertTrue(HifzRepository.isLoaded)
        assertEquals(emptyList<HifzPoint>(), decodeHifzPoints(notebook.readText()))
    }

    @Test
    fun oldUndoCannotReplaceLaterAnswerEditOrDeletion() {
        val original = HifzRepository.record(app, verse, HifzKind.Word, now = 1_000L)
        val recorded = HifzRepository.record(app, verse, HifzKind.Skip, now = 2_000L)
        val later = recorded.graded(HifzGrade.Again, today = recorded.dueDay, now = 3_000L).copy(key = "keep this note")
        HifzRepository.update(app, later)

        assertFalse(HifzRepository.restoreIfUnchanged(app, listOf(recorded), listOf(original)))
        assertEquals(listOf(later), HifzRepository.points.value)
        assertEquals(listOf(later), decodeHifzPoints(notebook.readText()))

        HifzRepository.delete(app, later.id)
        assertFalse(HifzRepository.restoreIfUnchanged(app, listOf(recorded), listOf(original)))
        assertTrue(HifzRepository.points.value.isEmpty())
    }

    @Test
    fun mirroredRecordingCapturesActualOriginals_andRetriesAsOneCommit() {
        val original = HifzRepository.record(app, verse, HifzKind.Word, now = 1_000L)
        val edited = original.copy(key = "edited before the background save")
        HifzRepository.update(app, edited)
        val other = QuranVerseReference(2, 19)
        val collision = File(directory, "hifz.json.new").apply { mkdirs() }
        File(collision, "occupied").writeText("blocked")

        assertTrue(runCatching {
            HifzRepository.recordWithUndo(app, verse, HifzKind.Similar, other, bothWays = true, now = 2_000L)
        }.exceptionOrNull() is IOException)
        assertEquals(listOf(edited), HifzRepository.points.value)
        assertEquals(listOf(edited), decodeHifzPoints(notebook.readText()))

        collision.deleteRecursively()
        val change = HifzRepository.recordWithUndo(app, verse, HifzKind.Similar, other, bothWays = true, now = 2_000L)

        assertEquals(listOf(edited, null), change.originals)
        assertEquals(2, change.saved.size)
        assertEquals(edited.slips + 1, change.saved.first().slips)
        assertEquals(change.saved, HifzRepository.points.value)
        assertEquals(change.saved, decodeHifzPoints(notebook.readText()))
        assertTrue(HifzRepository.restoreIfUnchanged(app, change.saved, change.originals))
        assertEquals(listOf(edited), HifzRepository.points.value)
    }

    @Test
    fun mirroredRecordingDoesNotMutateAnAlreadyRecordedReverseMistake() {
        val other = QuranVerseReference(2, 19)
        val reverse = HifzRepository.record(app, other, HifzKind.Similar, verse, now = 1_000L)

        val change = HifzRepository.recordWithUndo(app, verse, HifzKind.Similar, other, bothWays = true, now = 2_000L)

        assertEquals(1, change.saved.size)
        assertEquals(listOf<HifzPoint?>(null), change.originals)
        assertEquals(reverse, HifzRepository.points.value.pointOf(other))
    }

    @Test
    fun editedMistakeAndNewMirrorCommitTogether_andFailureCanBeRetried() {
        val original = HifzRepository.record(app, verse, HifzKind.Word, now = 1_000L)
        val other = QuranVerseReference(2, 19)
        val changed = original.copy(mistakes = listOf(HifzMistake(HifzKind.Similar, other, bothWays = true, at = 2_000L)))
        val collision = File(directory, "hifz.json.new").apply { mkdirs() }
        File(collision, "occupied").writeText("blocked")

        assertTrue(runCatching { HifzRepository.updateWithMirror(app, changed, 0) }.exceptionOrNull() is IOException)
        assertEquals(listOf(original), HifzRepository.points.value)
        assertEquals(listOf(original), decodeHifzPoints(notebook.readText()))

        collision.deleteRecursively()
        HifzRepository.updateWithMirror(app, changed, 0)

        assertEquals(changed, HifzRepository.points.value.pointOf(verse))
        val mirrored = HifzRepository.points.value.pointOf(other)!!
        assertEquals(verse, mirrored.mistakes.single().other)
        assertEquals(1, mirrored.slips)
        assertEquals(HifzRepository.points.value, decodeHifzPoints(notebook.readText()))
        // Editing the note later must not re-record an already established reverse mistake.
        HifzRepository.updateWithMirror(app, changed.copy(key = "note"), 0)
        assertEquals(mirrored, HifzRepository.points.value.pointOf(other))
    }

    @Test
    fun mirroredUndoIsAllOrNothing_andAnUnchangedNewPointCanBeRemoved() {
        val original = HifzRepository.record(app, verse, HifzKind.Word, now = 1_000L)
        val other = QuranVerseReference(2, 19)
        val saved = HifzRepository.record(app, verse, HifzKind.Similar, other = other, bothWays = true, now = 2_000L)
        val mirror = HifzRepository.record(app, other, HifzKind.Similar, other = verse, bothWays = true, now = 2_001L)
        val editedMirror = mirror.copy(key = "later mirror edit")
        HifzRepository.update(app, editedMirror)

        assertFalse(HifzRepository.restoreIfUnchanged(app, listOf(saved, mirror), listOf(original, null)))
        assertEquals(listOf(saved, editedMirror), HifzRepository.points.value)

        HifzRepository.update(app, mirror)
        assertTrue(HifzRepository.restoreIfUnchanged(app, listOf(saved, mirror), listOf(original, null)))
        assertEquals(listOf(original), HifzRepository.points.value)
        assertEquals(listOf(original), decodeHifzPoints(notebook.readText()))
    }

    @Suppress("UNCHECKED_CAST")
    private fun resetMemory() {
        HifzRepository::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, false)
        (HifzRepository::class.java.getDeclaredField("mutablePoints").apply { isAccessible = true }.get(null)
            as MutableStateFlow<List<HifzPoint>>).value = emptyList()
    }
}
