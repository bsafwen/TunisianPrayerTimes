package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Opening the picker must only render data prepared off the main thread.
 * These tests pin both the equivalence with the previous per-open derivation
 * and the reuse that removes that work from the opening frame.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalityPickerPerformanceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun kindLabel(kind: String): Int = when (kind) {
        "delegation" -> 0
        "sector" -> 1
        "municipality" -> 2
        "town", "city" -> 3
        "village" -> 4
        "hamlet" -> 5
        "neighbourhood", "quarter", "suburb", "city_district" -> 6
        "residential" -> 7
        else -> 8
    }

    @Test fun `prepared type context matches the previous per-open derivation`() {
        val catalog = LocalityRepository.preparePicker(context, GouvernoratRepository.loadAllDelegations(context))
        val legacy = catalog.localities.groupBy { it.governorateId to it.normalizedName }
            .values.filter { rows ->
                rows.any { it.kind == "delegation" } &&
                    rows.map { kindLabel(it.kind) }.distinct().size > 1
            }
            .flatten().mapTo(mutableSetOf()) { it.id }
        assertEquals(legacy, catalog.typeContextIds)
    }

    @Test fun `prepared groups match the previous per-open grouping`() {
        val gouvernorats = GouvernoratRepository.loadAll(context)
        val catalog = LocalityRepository.preparePicker(context, GouvernoratRepository.loadAllDelegations(context))
        val names = gouvernorats.associate { it.id to it.nomAr }
        val legacy = searchLocalities(catalog.localities, "").groupBy { it.governorateId }
        val legacyOrder = (gouvernorats.map { it.id } + legacy.keys.filter { it !in names })
            .filter { it in legacy }
        assertEquals(
            legacyOrder, catalog.groups.map { it.governorateId },
        )
        catalog.groups.forEach { group ->
            assertEquals(legacy.getValue(group.governorateId).map { it.id }, group.rows.map { it.id })
            assertEquals(legacy.getValue(group.governorateId).first().parentName, group.fallbackName)
        }
    }

    @Test fun `per group search matches the previous flat search for every governorate`() {
        val gouvernorats = GouvernoratRepository.loadAll(context)
        val catalog = LocalityRepository.preparePicker(context, GouvernoratRepository.loadAllDelegations(context))
        listOf("", "megrine", "حي السلام", "Mégrine", "بوشوشة", "سيدي رزيق", "zzzznonexistent").forEach { query ->
            val search = LocalitySearchQuery.parse(query)
            val legacy = searchLocalities(catalog.localities, search).groupBy { it.governorateId }
            catalog.groups.forEach { group ->
                assertEquals(
                    "query=$query governorate=${group.governorateId}",
                    legacy[group.governorateId].orEmpty().map { it.id },
                    searchLocalities(group.rows, search).map { it.id },
                )
            }
        }
    }

    @Test fun `opening reuses the prepared catalog and rows`() {
        val sources = GouvernoratRepository.loadAllDelegations(context)
        val catalog = LocalityRepository.preparePicker(context, sources)
        assertSame(catalog, LocalityRepository.preparePicker(context, sources.reversed()))
        assertTrue(catalog.groups.isNotEmpty())
        val blank = LocalitySearchQuery.parse("")
        catalog.groups.forEach { group ->
            assertSame("A blank query must not copy rows for governorate ${group.governorateId}",
                group.rows, searchLocalities(group.rows, blank))
        }
    }

    @Test fun `measures the opening derivation`() {
        val gouvernorats = GouvernoratRepository.loadAll(context)
        val catalog = LocalityRepository.preparePicker(context, GouvernoratRepository.loadAllDelegations(context))

        fun medianNanos(samples: Int = 11, warmup: Int = 5, block: () -> Unit): Long {
            repeat(warmup) { block() }
            val times = LongArray(samples) {
                val start = System.nanoTime()
                block()
                System.nanoTime() - start
            }
            times.sort()
            return times[samples / 2]
        }

        val legacyBlank = medianNanos {
            val matches = searchLocalities(catalog.localities, "").groupBy { it.governorateId }
            val names = gouvernorats.associate { it.id to it.nomAr }
            (gouvernorats.map { it.id } + matches.keys.filter { it !in names }).mapNotNull { id ->
                matches[id]?.let { rows -> Triple(id, names[id] ?: rows.first().parentName, rows) }
            }
        }
        val legacyTypeContext = medianNanos {
            catalog.localities.groupBy { it.governorateId to it.normalizedName }
                .values.filter { rows ->
                    rows.any { it.kind == "delegation" } &&
                        rows.map { kindLabel(it.kind) }.distinct().size > 1
                }
                .flatten().mapTo(mutableSetOf()) { it.id }
        }
        val names = gouvernorats.associate { it.id to it.nomAr }
        val preparedBlank = medianNanos {
            catalog.groups.map { group ->
                Triple(group.governorateId, names[group.governorateId] ?: group.fallbackName, group.rows)
            }
        }
        val legacyQuery = medianNanos {
            searchLocalities(catalog.localities, "حي السلام").groupBy { it.governorateId }
        }
        val preparedQuery = medianNanos {
            val search = LocalitySearchQuery.parse("حي السلام")
            catalog.groups.mapNotNull { group ->
                val rows = searchLocalities(group.rows, search)
                if (rows.isEmpty()) null else Triple(group.governorateId, group.fallbackName, rows)
            }
        }
        println(
            "PICKER_OPEN_DERIVATION legacyBlank=${legacyBlank / 1_000_000.0}ms " +
                "legacyTypeContext=${legacyTypeContext / 1_000_000.0}ms " +
                "preparedBlank=${preparedBlank / 1_000_000.0}ms " +
                "legacyQuery=${legacyQuery / 1_000_000.0}ms preparedQuery=${preparedQuery / 1_000_000.0}ms",
        )
        assertTrue(
            "Opening must not repeat the type-context scan",
            preparedBlank <= legacyBlank + legacyTypeContext,
        )
    }
}
