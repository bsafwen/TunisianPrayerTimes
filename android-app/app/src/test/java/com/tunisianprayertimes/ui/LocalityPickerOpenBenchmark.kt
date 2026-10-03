package com.tunisianprayertimes.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.GouvernoratRepository
import com.tunisianprayertimes.LocalityPickerCatalog
import com.tunisianprayertimes.LocalityRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Compares a warm picker open against an empty catalog to surface opening-frame work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "ar-rTN-w360dp-h800dp-mdpi", application = Application::class)
class LocalityPickerOpenBenchmark {
    @get:Rule val compose = createComposeRule()

    private fun measure(catalog: LocalityPickerCatalog, selectedId: String): LongArray {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val gouvernorats = GouvernoratRepository.loadAll(context)
        var show by mutableStateOf(false)
        compose.setContent {
            if (show) {
                LocalityPickerSheet(
                    catalog = catalog,
                    gouvernorats = gouvernorats,
                    selectedId = selectedId,
                    onDismiss = {},
                    onSelect = {},
                )
            }
        }
        compose.waitForIdle()
        val samples = LongArray(6)
        repeat(samples.size) { index ->
            val start = System.nanoTime()
            show = true
            compose.waitForIdle()
            if (catalog.localities.isNotEmpty()) {
                compose.onNodeWithTag("locality_list").assertIsDisplayed()
            }
            samples[index] = System.nanoTime() - start
            show = false
            compose.waitForIdle()
        }
        return samples
    }

    private fun run(label: String, selectedAt: (LocalityPickerCatalog) -> String) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val catalog = LocalityRepository.preparePicker(context, GouvernoratRepository.loadAllDelegations(context))
        val samples = measure(catalog, selectedAt(catalog))
        println("OPEN_$label " + samples.joinToString(",") { "%.2f".format(it / 1_000_000.0) })
    }

    @Test fun openEmpty() {
        val samples = measure(LocalityPickerCatalog.empty, "")
        println("OPEN_EMPTY " + samples.joinToString(",") { "%.2f".format(it / 1_000_000.0) })
    }

    @Test fun openTop() = run("TOP") { "" }

    @Test fun openMiddle() = run("MIDDLE") { it.localities.getOrNull(it.localities.size / 2)?.id ?: "" }

    @Test fun openBottom() = run("BOTTOM") { it.localities.lastOrNull()?.id ?: "" }
}
