package com.tunisianprayertimes.ui

import android.app.Application
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.tunisianprayertimes.quran.assets.QuranDownloadProblem
import com.tunisianprayertimes.quran.assets.QuranPack
import com.tunisianprayertimes.quran.assets.QuranPackArchive
import com.tunisianprayertimes.quran.assets.QuranPackFile
import com.tunisianprayertimes.quran.assets.QuranPackLayout
import com.tunisianprayertimes.quran.assets.QuranPackSource
import com.tunisianprayertimes.quran.assets.QuranPackState
import com.tunisianprayertimes.quran.assets.QuranPackStatus
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class QuranDownloadsTest {
    @get:Rule
    val compose = createComposeRule()

    private class FakeSource : QuranPackSource {
        override val states = MutableStateFlow<Map<String, QuranPackState>>(emptyMap())
        val fetched = mutableListOf<Pair<List<String>, Boolean>>()
        val removed = mutableListOf<String>()
        override fun directory(pack: QuranPack): File? = null
        override fun fetch(packs: List<QuranPack>, allowMetered: Boolean) { fetched += packs.map { it.name } to allowMetered }
        override fun confirmMobileData(packs: List<QuranPack>, launcher: ActivityResultLauncher<IntentSenderRequest>) = Unit
        override fun remove(packs: List<QuranPack>) { removed += packs.map { it.name } }
        override fun refresh() = Unit
    }

    private fun pack(name: String, kind: QuranPack.Kind, surahs: IntRange? = null, bytes: Long = 30_000_000L) = QuranPack(
        name, kind, QuranPack.Delivery.Play, if (kind == QuranPack.Kind.Audio) "hosary-qaloun" else null, surahs, bytes,
        QuranPackArchive("v1/packs/$name-0123456789ab.zip", bytes, "0".repeat(64)), listOf(QuranPackFile("quran/x/$name", bytes)),
    )

    private val pages = pack("quran_pages", QuranPack.Kind.Pages, bytes = 176_000_000L)
    private val first = pack("quran_hosary_01", QuranPack.Kind.Audio, 1..1, 1_000_000L)
    private val second = pack("quran_hosary_02", QuranPack.Kind.Audio, 2..2, 168_000_000L)
    private val rest = pack("quran_hosary_03", QuranPack.Kind.Audio, 3..114, 100_000_000L)
    private val layout = QuranPackLayout("https://cdn.example/", listOf(pages, first, second, rest))

    private fun media(source: FakeSource, metered: Boolean = false, bundled: Set<String> = emptySet()) =
        QuranMedia(layout, source, bundled) { metered }

    @Test
    fun aRecitationStartsOnceItsChaptersAreDownloaded() {
        val source = FakeSource()
        val media = media(source)
        val starter = QuranRecitationStarter { media }
        var started = 0
        starter.start("hosary-qaloun", 1..2) { started++ }
        assertEquals(0, started)
        // One recitation is worth mobile data; the listener asked for it.
        assertEquals(listOf(listOf("quran_hosary_01", "quran_hosary_02") to true), source.fetched)
        assertEquals(listOf("quran_hosary_01", "quran_hosary_02"), starter.pendingPacks.map { it.name })

        media.states = mapOf("quran_hosary_01" to QuranPackState.Available)
        starter.onStatesChanged()
        assertEquals(0, started)
        media.states = media.states + ("quran_hosary_02" to QuranPackState.Available)
        starter.onStatesChanged()
        starter.onStatesChanged()
        assertEquals(1, started)
        assertTrue(starter.pendingPacks.isEmpty())
    }

    @Test
    fun aRecitationOnTheDeviceStartsAtOnceAndFetchesTheNextChapterOnWifiOnly() {
        val source = FakeSource()
        val onWifi = media(source).apply { states = mapOf("quran_hosary_01" to QuranPackState.Available) }
        var started = 0
        QuranRecitationStarter { onWifi }.start("hosary-qaloun", 1..1) { started++ }
        assertEquals(1, started)
        assertEquals(listOf(listOf("quran_hosary_02") to false), source.fetched)

        val metered = FakeSource()
        val onMobile = media(metered, metered = true).apply { states = mapOf("quran_hosary_01" to QuranPackState.Available) }
        QuranRecitationStarter { onMobile }.start("hosary-qaloun", 1..1) { started++ }
        assertEquals(2, started)
        assertTrue(metered.fetched.isEmpty())
    }

    @Test
    fun aWaitingRecitationIsForgottenWhenTheReaderLeaves() {
        val source = FakeSource()
        val media = media(source)
        val starter = QuranRecitationStarter { media }
        var started = 0
        starter.start("hosary-qaloun", 3..3) { started++ }
        starter.cancel()
        media.states = mapOf("quran_hosary_03" to QuranPackState.Available)
        starter.onStatesChanged()
        assertEquals(0, started)
    }

    @Test
    fun bundledPacksNeedNoDownload() {
        val source = FakeSource()
        val media = media(source, bundled = setOf("quran_pages", "quran_hosary_01", "quran_hosary_02", "quran_hosary_03"))
        var started = 0
        QuranRecitationStarter { media }.start("hosary-qaloun", 1..114) { started++ }
        assertEquals(1, started)
        assertTrue(media.pagesReady)
        assertTrue(source.fetched.isEmpty())
    }

    @Test
    fun pagesBannerOffersTheDownloadThenShowsItsProgressAndProblems() {
        val source = FakeSource()
        val media = media(source)
        val downloads = mutableListOf<Boolean>()
        compose.setContent { QuranPagesDownloadBanner(media, onDownload = { downloads += it }, onUseMobileData = {}) }

        compose.onNodeWithText("صفحات المصحف غير محمّلة", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("quran_pages_download_button").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(true), downloads)

        media.states = mapOf("quran_pages" to QuranPackState(QuranPackStatus.Downloading, 88_000_000L, 176_000_000L))
        compose.onNodeWithText("جارٍ تنزيل صفحات المصحف", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("quran_pages_progress").assertIsDisplayed()

        media.states = mapOf("quran_pages" to QuranPackState(QuranPackStatus.WaitingForWifi, 0, 176_000_000L))
        compose.onNodeWithTag("quran_pages_mobile_data").assertIsDisplayed()

        media.states = mapOf("quran_pages" to QuranPackState(QuranPackStatus.Failed, problem = QuranDownloadProblem.Storage))
        compose.onNodeWithText("لا توجد مساحة كافية على الجهاز").assertIsDisplayed()
        compose.onNodeWithTag("quran_pages_retry").assertIsDisplayed()

        media.states = mapOf("quran_pages" to QuranPackState.Available)
        compose.onNodeWithTag("quran_pages_download").assertDoesNotExist()
    }

    @Test
    fun listeningSheetCountsDownloadedChaptersAndDeletesThemAfterConfirming() {
        val source = FakeSource()
        val media = media(source).apply {
            states = mapOf("quran_hosary_01" to QuranPackState.Available, "quran_hosary_02" to QuranPackState.Available)
        }
        var downloadAll = 0
        val deleted = mutableListOf<String>()
        compose.setContent {
            QuranRecitationDownloadsSection(media, "hosary-qaloun", onDownloadAll = { downloadAll++ }, onUseMobileData = {},
                onDelete = { packs -> deleted += packs.map { it.name } })
        }
        compose.onNodeWithText("التلاوات المحمّلة: ٢ من ١١٤ سورة").assertIsDisplayed()
        compose.onNodeWithTag("quran_audio_download_all").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, downloadAll)

        compose.onNodeWithTag("quran_audio_delete_downloads").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(deleted.isEmpty())
        compose.onNodeWithTag("quran_audio_delete_confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("quran_hosary_01", "quran_hosary_02"), deleted)
    }
}
