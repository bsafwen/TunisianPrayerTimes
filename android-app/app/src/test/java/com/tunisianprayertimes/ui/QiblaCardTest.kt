package com.tunisianprayertimes.ui

import android.app.Application
import android.telephony.TelephonyManager
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.QiblaMethod
import com.tunisianprayertimes.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class QiblaCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun inTunisia_withoutLocationPermission_showsBearingFromSelectedDelegation() {
        setNetworkCountry("tn")
        val delegationLabel = context.getString(R.string.qibla_location_selected, "مدينة تونس")
        compose.setContent { QiblaCard(selectedDelegationId = TUNIS_DELEGATION_ID) }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(delegationLabel).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(delegationLabel).assertExists()
        compose.onNodeWithTag(TestTags.QIBLA_BEARING_VALUE)
            .assertTextEquals(context.getString(R.string.qibla_degrees_value, 113.0))
        compose.onNodeWithText(context.getString(R.string.qibla_location_permission_required)).assertExists()
        // While the delegation loads, the status line must not claim the location is missing.
        compose.onNodeWithText(context.getString(R.string.qibla_location_required)).assertDoesNotExist()
    }

    @Test
    fun abroad_withoutLocationPermission_doesNotFallBackToTheTunisianDelegation() {
        setNetworkCountry("fr")
        val permissionHint = context.getString(R.string.qibla_location_permission_required)
        compose.setContent { QiblaCard(selectedDelegationId = TUNIS_DELEGATION_ID) }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(permissionHint).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(context.getString(R.string.qibla_location_selected, "مدينة تونس")).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.qibla_degrees_value, 113.0)).assertDoesNotExist()
        val locationRequired = compose.onAllNodesWithText(context.getString(R.string.qibla_location_required))
            .fetchSemanticsNodes()
        assertTrue(locationRequired.isNotEmpty())
    }

    @Test
    fun methodChoice_staysOutOfTheWayUntilOpened() {
        setNetworkCountry("tn")
        compose.setContent { QiblaCard(selectedDelegationId = TUNIS_DELEGATION_ID) }
        waitForBearing(113.0)

        compose.onNodeWithTag(TestTags.QIBLA_METHOD_ROW).assertExists()
        compose.onNodeWithText(context.getString(R.string.qibla_method_standard)).assertExists()
        // The choice itself, and the chip for the less common method, only appear on demand.
        compose.onNodeWithTag(TestTags.QIBLA_METHOD_RHUMB_LINE).assertDoesNotExist()
        compose.onNodeWithTag(TestTags.QIBLA_METHOD_CHIP).assertDoesNotExist()
    }

    @Test
    fun switchingToTheRhumbLine_explainsTheDifferenceAndRemembersTheChoice() {
        setNetworkCountry("tn")
        compose.setContent { QiblaCard(selectedDelegationId = TUNIS_DELEGATION_ID) }
        waitForBearing(113.0)

        compose.onNodeWithTag(TestTags.QIBLA_METHOD_ROW).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(TestTags.QIBLA_METHOD_GREAT_CIRCLE).assertIsSelected()
        // In Tunis the two methods part by about 8°, and the sheet says so before the switch.
        compose.onNodeWithText(context.getString(R.string.qibla_method_difference, 8)).assertExists()
        compose.onNodeWithTag(TestTags.QIBLA_METHOD_RHUMB_LINE).performClick()

        waitForBearing(121.0)
        compose.onNodeWithTag(TestTags.QIBLA_METHOD_RHUMB_LINE).assertIsSelected()
        compose.onNodeWithTag(TestTags.QIBLA_METHOD_CHIP).assertExists()
        assertEquals(QiblaMethod.RhumbLine, PrefsManager.getQiblaMethod(context))
    }

    @Test
    fun savedRhumbLineChoice_isUsedAndFlaggedWhenTheCardOpens() {
        setNetworkCountry("tn")
        PrefsManager.setQiblaMethod(context, QiblaMethod.RhumbLine)
        compose.setContent { QiblaCard(selectedDelegationId = TUNIS_DELEGATION_ID) }
        waitForBearing(121.0)

        compose.onNodeWithTag(TestTags.QIBLA_METHOD_CHIP).assertExists()
    }

    private fun waitForBearing(degrees: Double) {
        val expected = context.getString(R.string.qibla_degrees_value, degrees)
        compose.waitUntil(timeoutMillis = 10_000) {
            runCatching { compose.onNodeWithTag(TestTags.QIBLA_BEARING_VALUE).assertTextEquals(expected) }.isSuccess
        }
    }

    private fun setNetworkCountry(countryIso: String) {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        Shadows.shadowOf(telephonyManager).setNetworkCountryIso(countryIso)
    }

    private companion object {
        const val TUNIS_DELEGATION_ID = 615
    }
}
