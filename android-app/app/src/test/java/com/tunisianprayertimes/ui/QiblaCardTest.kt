package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class QiblaCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun withoutLocationPermission_showsBearingFromSelectedDelegation() {
        val delegationLabel = context.getString(R.string.qibla_location_selected, "مدينة تونس")
        compose.setContent { QiblaCard(selectedDelegationId = TUNIS_DELEGATION_ID) }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(delegationLabel).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(delegationLabel).assertExists()
        compose.onNodeWithText(context.getString(R.string.qibla_degrees_value, 113.0)).assertExists()
        compose.onNodeWithText(context.getString(R.string.qibla_location_permission_required)).assertExists()
        // While the delegation loads, the status line must not claim the location is missing.
        compose.onNodeWithText(context.getString(R.string.qibla_location_required)).assertDoesNotExist()
    }

    private companion object {
        const val TUNIS_DELEGATION_ID = 615
    }
}
