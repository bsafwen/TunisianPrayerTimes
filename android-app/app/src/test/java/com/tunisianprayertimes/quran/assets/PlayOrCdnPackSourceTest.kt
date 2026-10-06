package com.tunisianprayertimes.quran.assets

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityOptionsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Play installs: Play's packs go through Play, the rest through the quran-cdn Worker. */
class PlayOrCdnPackSourceTest {
    @get:Rule val folder = TemporaryFolder()

    private class FakeSource : QuranPackSource {
        override val states = MutableStateFlow<Map<String, QuranPackState>>(emptyMap())
        // The Worker takes over on another thread.
        val fetched = CopyOnWriteArrayList<Pair<List<String>, Boolean>>()
        val confirmed = CopyOnWriteArrayList<List<String>>()
        val removed = CopyOnWriteArrayList<List<String>>()
        override fun directory(pack: QuranPack): File? = null
        override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) { fetched += packs.map { it.name } to allowMetered }
        override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) { confirmed += packs.map { it.name } }
        override fun remove(packs: List<QuranPack>) { removed += packs.map { it.name } }
        override fun refresh() {}
    }

    private val launcher = object : ActivityResultLauncher<IntentSenderRequest>() {
        override fun launch(input: IntentSenderRequest, options: ActivityOptionsCompat?) {}
        override fun unregister() {}
        override val contract: ActivityResultContract<IntentSenderRequest, *> = ActivityResultContracts.StartIntentSenderForResult()
    }

    private fun pack(name: String, delivery: String, surahs: IntRange, reciter: String) = JSONObject()
        .put("name", name).put("kind", "audio").put("delivery", delivery).put("reciterId", reciter)
        .put("surahs", JSONArray(listOf(surahs.first, surahs.last))).put("bytes", 10)
        .put("archive", JSONObject().put("key", "v1/packs/$name-0123456789ab.zip").put("bytes", 110).put("sha256", "a".repeat(64)))
        .put("files", JSONArray(surahs.map { JSONObject().put("path", "quran/audio/$reciter/%03d.mp3".format(it)).put("bytes", 10) }))

    private val layout = QuranPackLayout.parse(JSONObject().put("version", 1).put("cdn", "https://cdn.example/").put("packs", JSONArray(listOf(
        pack("quran_hosary_01", "play", 1..57, "hosary"),
        pack("quran_hosary_02", "play", 58..114, "hosary"),
        pack("quran_later_01", "cdn", 1..114, "later"),
    ))).toString())
    private val hosary1 = layout.packs[0]
    private val hosary2 = layout.packs[1]
    private val later = layout.packs[2]

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val play = FakeSource()
    private val worker = FakeSource()
    private var onUnavailable: () -> Unit = {}
    private val source by lazy {
        PlayOrCdnPackSource(layout, lazy { worker }, QuranPackInstaller(folder.root), scope) { unavailable ->
            onUnavailable = unavailable
            play
        }
    }

    @After
    fun stop() = scope.cancel()

    private fun awaitStates(condition: (Map<String, QuranPackState>) -> Boolean) = runBlocking {
        withTimeout(5_000) { source.states.first(condition) }
    }

    @Test
    fun eachPackGoesToTheSourceThatCarriesIt() {
        source.fetch(listOf(hosary1, later), allowMetered = false)
        assertEquals(listOf(listOf("quran_hosary_01") to false), play.fetched)
        assertEquals(listOf(listOf("quran_later_01") to false), worker.fetched)

        source.remove(listOf(hosary2, later))
        assertEquals(listOf(listOf("quran_hosary_02")), play.removed)
        assertEquals(listOf(listOf("quran_later_01")), worker.removed)
    }

    @Test
    fun statesComeFromTheSourceThatCarriesEachPack() {
        play.states.value = mapOf("quran_hosary_01" to QuranPackState(QuranPackStatus.Downloading, 5, 10))
        // The Worker's view of a Play pack does not count while Play delivers it.
        worker.states.value = mapOf(
            "quran_later_01" to QuranPackState(QuranPackStatus.WaitingForWifi, 0, 110),
            "quran_hosary_02" to QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Network),
        )
        val states = awaitStates { it["quran_later_01"]?.status == QuranPackStatus.WaitingForWifi }
        assertEquals(QuranPackStatus.Downloading, states["quran_hosary_01"]?.status)
        assertTrue(states["quran_hosary_02"]?.status != QuranPackStatus.Failed)
    }

    @Test
    fun mobileDataIsAskedOfEachSourceForItsWaitingPacks() {
        play.states.value = mapOf(
            "quran_hosary_01" to QuranPackState(QuranPackStatus.NeedsConfirmation, 0, 10),
            "quran_hosary_02" to QuranPackState(QuranPackStatus.Downloading, 1, 10),
        )
        awaitStates { it["quran_hosary_01"]?.status == QuranPackStatus.NeedsConfirmation }
        source.confirmMobileData(listOf(hosary1, hosary2, later), launcher)
        assertEquals(listOf(listOf("quran_hosary_01")), play.confirmed)
        assertEquals(listOf(listOf("quran_later_01")), worker.confirmed)

        // Nothing of Play's waits: Play's dialog is not shown at all.
        play.confirmed.clear()
        source.confirmMobileData(listOf(hosary2, later), launcher)
        assertTrue(play.confirmed.isEmpty())
    }

    @Test
    fun whenPlayCannotServeThisInstallTheWorkerTakesOverWhatPlayWasAsked() {
        source.fetch(listOf(hosary1), allowMetered = true)
        worker.states.value = layout.packs.associate { it.name to QuranPackState.Missing }
        onUnavailable()
        runBlocking { withTimeout(5_000) { while (worker.fetched.none { it.first == listOf("quran_hosary_01") }) delay(10) } }
        assertEquals(true, worker.fetched.first { it.first == listOf("quran_hosary_01") }.second)

        // From now on every pack comes from the Worker.
        source.fetch(listOf(hosary2), allowMetered = false)
        assertEquals(listOf("quran_hosary_02") to false, worker.fetched.last())
        assertEquals(listOf(listOf("quran_hosary_01") to true), play.fetched)
    }
}
