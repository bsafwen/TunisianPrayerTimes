package com.tunisianprayertimes

import android.Manifest
import android.content.Context
import android.location.Location
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File
import java.util.Calendar
import java.util.Locale
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.tunisianprayertimes.ui.TestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Device UI tests inject public test coordinates; they never access real GPS. */
@RunWith(AndroidJUnit4::class)
class NeighborhoodPickerInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var coordinate = 36.8428 to 10.1465
    private val prepare = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit()
                    .clear().putBoolean("first_launch_done", true).putBoolean("silence_enabled", false)
                    .putBoolean("auto_location_update", false).commit()
                DelegationLocator.locationProvider = object : DelegationLocationProvider {
                    private fun point() = Location("test").apply {
                        latitude = coordinate.first
                        longitude = coordinate.second
                        time = System.currentTimeMillis()
                        accuracy = 5f
                    }
                    override suspend fun findCurrentLocation(context: Context, permissionState: LocationPermissionState) = point()
                    override suspend fun findRecentLocation(context: Context, permissionState: LocationPermissionState) = point()
                    override suspend fun findFreshLocation(context: Context, permissionState: LocationPermissionState) = point()
                }
                try { base.evaluate() } finally { DelegationLocator.resetLocationProviderForTests() }
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(prepare)
        .around(GrantPermissionRule.grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        .around(compose)

    private fun pressGpsAndExpect(name: String) {
        compose.onNodeWithTag(TestTags.LOCATION_PICKER).performScrollTo()
        compose.onNodeWithContentDescription(context.getString(R.string.gps_auto_detect)).performClick()
        compose.waitUntil(15_000) { PrefsManager.getLocationSelection(context).name == name }
        compose.onNode(hasText(name) and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).assertIsDisplayed()
    }

    private fun saveScreenshot(name: String) {
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(context.getExternalFilesDir(null), name).outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    @Test fun pickerOpensGroupedBeforeAnyGpsLookup() {
        val selectedSource = PrefsManager.getDelegationId(context)
        val governor = GouvernoratRepository.loadAll(context).first { gov -> gov.delegations.any { it.id == selectedSource } }
        compose.onNodeWithTag(TestTags.LOCATION_PICKER).performScrollTo()
        val openButton = hasClickAction() and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER)) and
            !hasContentDescription(context.getString(R.string.gps_auto_detect))
        val start = SystemClock.elapsedRealtime()
        compose.onNode(openButton).performClick()
        compose.onNodeWithTag("locality_list").assertIsDisplayed()
        compose.onNodeWithTag("locality_governorate_${governor.id}").assertIsDisplayed().assertTextEquals(governor.nomAr)
        val firstOpenMs = SystemClock.elapsedRealtime() - start
        compose.onNodeWithText("OpenStreetMap", substring = true).assertDoesNotExist()
        compose.onNodeWithText("CC BY", substring = true).assertDoesNotExist()
        saveScreenshot("neighborhood-grouped-picker.png")

        compose.onNodeWithTag("locality_search").performTextInput("حي السلام")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("locality_row_municipal:bouarada:cite_14").fetchSemanticsNodes().isNotEmpty()
        }
        val bouaradaGovernor = LocalityRepository.loadAll(context).first { it.id == "municipal:bouarada:cite_14" }.governorateId
        compose.onNodeWithTag("locality_governorate_$bouaradaGovernor").assertIsDisplayed()
        compose.onNodeWithTag("locality_row_municipal:bouarada:cite_14").performClick()
        compose.onNode(hasText("حيّ السلام") and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).assertIsDisplayed()

        val reopenStart = SystemClock.elapsedRealtime()
        compose.onNode(hasText("حيّ السلام") and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).performClick()
        compose.onNodeWithTag("locality_list").assertIsDisplayed()
        val reopenMs = SystemClock.elapsedRealtime() - reopenStart
        val timing = "{\"firstOpenMs\":$firstOpenMs,\"reopenMs\":$reopenMs}"
        File(context.getExternalFilesDir(null), "neighborhood-picker-timings.json").writeText(timing)
        println("PICKER_TIMING $timing")
    }

    @Test fun gpsButtonShowsNeighborhoodAndKeepsTimetableInBackground() {
        pressGpsAndExpect("المنزه 9 أ")
        assertEquals(GouvernoratRepository.findNearestDelegation(context, coordinate.first, coordinate.second)?.id,
            PrefsManager.getDelegationId(context))
        val timetable = GouvernoratRepository.findDelegationById(context, PrefsManager.getDelegationId(context))!!
        compose.onNode(hasText(timetable.nomAr) and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).assertDoesNotExist()
        saveScreenshot("neighborhood-gps.png")
    }

    @Test fun megrineSearchMergesDuplicateAreasAndKeepsGpsSelectionHighlighted() {
        coordinate = 36.767638 to 10.223168
        pressGpsAndExpect("مقرين الرياض")
        assertEquals("osm:way:124689902", PrefsManager.getLocationSelection(context).localityId)
        compose.onNode(hasText("مقرين الرياض") and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).performClick()
        compose.onNodeWithTag("locality_search").performTextInput("megrine")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val riadh = "locality_row_osm:relation:7174626"
        compose.onNodeWithTag("locality_list").performScrollToNode(hasTestTag(riadh))
        compose.onNodeWithTag(riadh).assertIsDisplayed().assertIsSelected()
        compose.onNode(hasText("معتمدية مقرين") and hasAnyAncestor(hasTestTag(riadh)), useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(hasText("مقرين الرياض") and hasAnyAncestor(hasTestTag("locality_list"))).assertCountEquals(1)
        compose.onNodeWithTag("locality_row_osm:way:124689902").assertDoesNotExist()
        saveScreenshot("megrine-picker-fixed.png")

        val sidiRezig = "locality_row_osm:relation:7174613"
        compose.onNodeWithTag("locality_list").performScrollToNode(hasTestTag(sidiRezig))
        compose.onNodeWithTag(sidiRezig).assertIsDisplayed()
        compose.onNode(hasText("معتمدية مقرين") and hasAnyAncestor(hasTestTag(sidiRezig)), useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(hasText("سيدي رزيق") and hasAnyAncestor(hasTestTag("locality_list"))).assertCountEquals(1)
        compose.onNodeWithTag("locality_row_osm:way:103565487").assertDoesNotExist()
        compose.onNodeWithTag(sidiRezig).performClick()
        compose.waitUntil(5_000) { PrefsManager.getLocationSelection(context).name == "سيدي رزيق" }
        assertEquals(448, PrefsManager.getDelegationId(context))
    }

    @Test fun labelRefreshesForBackgroundMoveWithUnchangedTimetable() {
        coordinate = 36.810562 to 10.146875
        pressGpsAndExpect("بوشوشة")
        coordinate = 36.805274 to 10.126553
        assertFalse(runBlocking { DelegationLocator.updateDelegationFromLastLocation(context) })
        compose.waitUntil(5_000) { PrefsManager.getLocationSelection(context).name == "خزندار" }
        compose.onNode(hasText("خزندار") and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).assertIsDisplayed()
        assertEquals(394, PrefsManager.getDelegationId(context))
        saveScreenshot("neighborhood-background-update.png")
    }

    @Test fun backgroundMoveRefreshesDisplayedPrayerTimesAndNeighborhood() {
        pressGpsAndExpect("المنزه 9 أ")
        val initialSource = PrefsManager.getDelegationId(context)
        val destination = 36.3426978 to 9.6190025
        val destinationSource = GouvernoratRepository.findNearestDelegation(context, destination.first, destination.second)!!.id
        assertNotEquals(initialSource, destinationSource)
        val today = Calendar.getInstance()
        fun dhuhrTime(source: Int): String {
            val time = PrayerTimesRepository.loadDayPrayerTimes(context, source,
                today.get(Calendar.YEAR), today.get(Calendar.MONTH) + 1, today.get(Calendar.DAY_OF_MONTH))!!.dhuhr
            return String.format(Locale.US, "%02d:%02d", time.hour, time.minute)
        }
        val initialTime = dhuhrTime(initialSource)
        val destinationTime = dhuhrTime(destinationSource)
        assertNotEquals("The public test locations must have different Dhuhr times", initialTime, destinationTime)
        compose.onNodeWithTag("prayer_time_DHUHR").performScrollTo().assertIsDisplayed().assertTextEquals(initialTime)

        coordinate = destination
        assertTrue(runBlocking { DelegationLocator.updateDelegationFromLastLocation(context) })
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("prayer_time_DHUHR") and hasText(destinationTime))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("prayer_time_DHUHR").assertIsDisplayed().assertTextEquals(destinationTime)
        assertEquals(destinationSource, PrefsManager.getDelegationId(context))
        compose.onNodeWithTag(TestTags.LOCATION_PICKER).performScrollTo()
        compose.onNode(hasText("حيّ السلام") and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).assertIsDisplayed()
    }

    @Test fun gpsUsesNewBouaradaBoundaryOffline() {
        coordinate = 36.3426978 to 9.6190025
        pressGpsAndExpect("حيّ السلام")
        assertEquals("municipal:bouarada:cite_14", PrefsManager.getLocationSelection(context).localityId)
        assertEquals(GouvernoratRepository.findNearestDelegation(context, coordinate.first, coordinate.second)?.id,
            PrefsManager.getDelegationId(context))
        saveScreenshot("neighborhood-bouarada-gps.png")
    }

    @Test fun conflictingImadasNeverShowAnArbitraryNameOrKeepPreviousNeighborhood() {
        pressGpsAndExpect("المنزه 9 أ")
        coordinate = 36.777334 to 10.220016121993286
        compose.onNodeWithContentDescription(context.getString(R.string.gps_auto_detect)).performClick()
        compose.waitUntil(15_000) {
            val saved = PrefsManager.getLocationSelection(context)
            saved.fromGps && saved.name == null && saved.localityId == null
        }
        assertNull(PrefsManager.getLocationSelection(context).name)
        compose.onNode(hasText(context.getString(R.string.location_current_position)) and
            hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.location_neighborhood_unavailable)).assertIsDisplayed()
        assertEquals(GouvernoratRepository.findNearestDelegation(context, coordinate.first, coordinate.second)?.id,
            PrefsManager.getDelegationId(context))
        saveScreenshot("neighborhood-conflict-fallback.png")
    }

    @Test fun manuallySelectingAPolygonNamePreservesItsName() {
        pressGpsAndExpect("المنزه 9 أ")
        compose.onNode(hasText("المنزه 9 أ") and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).performClick()
        compose.onNodeWithTag("locality_search").performTextInput("خزندار")
        val result = hasText("خزندار") and !hasSetTextAction()
        compose.waitUntil(15_000) { compose.onAllNodes(result).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(result).assertIsDisplayed()
        val governorId = LocalityRepository.loadAll(context).first { it.name == "خزندار" }.governorateId
        compose.onNodeWithTag("locality_governorate_$governorId").assertIsDisplayed()
        compose.onNodeWithText("OpenStreetMap", substring = true).assertDoesNotExist()
        compose.onNodeWithText("CC BY", substring = true).assertDoesNotExist()
        saveScreenshot("neighborhood-search.png")
        compose.onNode(result).performClick()
        compose.waitUntil(5_000) { PrefsManager.getLocationSelection(context).name == "خزندار" }
        assertFalse(PrefsManager.getLocationSelection(context).fromGps)
        assertEquals(394, PrefsManager.getDelegationId(context))
        compose.onNode(hasText("خزندار") and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER))).assertIsDisplayed()
    }
}
