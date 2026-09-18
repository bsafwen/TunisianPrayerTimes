package com.tunisianprayertimes

import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tunisianprayertimes.ui.TestTags
import java.io.FileInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Records device timings and frame jank while opening and closing the locality picker. */
@RunWith(AndroidJUnit4::class)
class LocalityPickerOpenInstrumentedBenchmark {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val prepare = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit()
                    .clear().putBoolean("first_launch_done", true).putBoolean("silence_enabled", false)
                    .putBoolean("auto_location_update", false).commit()
                base.evaluate()
            }
        }
    }
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(prepare).around(compose)

    private fun shell(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }
    }

    private fun gfxSnapshot(): String =
        shell("dumpsys gfxinfo ${context.packageName}")
            .lineSequence()
            .filter { line ->
                listOf("Total frames", "Janky frames", "50th percentile", "90th percentile",
                    "95th percentile", "99th percentile", "Number Missed Vsync", "Number Slow UI thread")
                    .any { it in line }
            }
            .joinToString(" | ")

    private fun openAndCloseOnce(): Long {
        val start = SystemClock.elapsedRealtime()
        compose.onNode(
            hasClickAction() and hasAnyAncestor(hasTestTag(TestTags.LOCATION_PICKER)) and
                !hasContentDescription(context.getString(R.string.gps_auto_detect))
        ).performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("locality_list").fetchSemanticsNodes().isNotEmpty()
        }
        val elapsed = SystemClock.elapsedRealtime() - start
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("locality_list").fetchSemanticsNodes().isEmpty()
        }
        return elapsed
    }

    @Test
    fun openAndCloseTimings() {
        val catalog = LocalityRepository.loadAvailable(context, GouvernoratRepository.loadAllDelegations(context))
        val gouvernorats = GouvernoratRepository.loadAll(context)
        repeat(3) {
            val start = SystemClock.elapsedRealtime()
            val matches = searchLocalities(catalog, "").groupBy { it.governorateId }
            val names = gouvernorats.associate { it.id to it.nomAr }
            (gouvernorats.map { it.id } + matches.keys.filter { it !in names }).mapNotNull { id ->
                matches[id]?.let { rows -> Triple(id, names[id] ?: rows.first().parentName, rows) }
            }
            println("PICKER_DERIVE_BLANK ${SystemClock.elapsedRealtime() - start} ms over ${catalog.size} rows")
            val typeStart = SystemClock.elapsedRealtime()
            catalog.groupBy { it.governorateId to it.normalizedName }
                .values.filter { rows ->
                    rows.any { it.kind == "delegation" } &&
                        rows.map { it.kind }.distinct().size > 1
                }
                .flatten().mapTo(mutableSetOf()) { it.id }
            println("PICKER_DERIVE_TYPE ${SystemClock.elapsedRealtime() - typeStart} ms")

            val prepared = LocalityRepository.preparePicker(context, GouvernoratRepository.loadAllDelegations(context))
            val openStart = SystemClock.elapsedRealtime()
            prepared.groups.map { group ->
                Triple(group.governorateId, names[group.governorateId] ?: group.fallbackName, group.rows)
            }
            println("PICKER_DERIVE_PREPARED_OPEN ${SystemClock.elapsedRealtime() - openStart} ms over ${prepared.groups.size} groups")
        }

        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag(TestTags.LOCATION_PICKER).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(TestTags.LOCATION_PICKER).performScrollTo()

        repeat(2) { openAndCloseOnce() }
        shell("dumpsys gfxinfo ${context.packageName} reset")
        val samples = mutableListOf<Long>()
        repeat(20) { samples += openAndCloseOnce() }
        println("PICKER_OPEN_BENCH ${samples.joinToString(",")}")
        println("PICKER_GFX ${gfxSnapshot()}")
    }
}
