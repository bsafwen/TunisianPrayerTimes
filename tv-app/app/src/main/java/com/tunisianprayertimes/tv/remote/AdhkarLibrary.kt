package com.tunisianprayertimes.tv.remote

import com.tunisianprayertimes.adhkar.DhikrCatalog
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.countForCollection
import com.tunisianprayertimes.mosque.FlowTiming
import com.tunisianprayertimes.mosque.MosqueAdhkar
import com.tunisianprayertimes.mosque.ReviewedDhikr
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The reviewed adhkar that ship with the app, for the dashboard (GET /api/adhkar, see
 * docs/DASHBOARD.md): the texts a mosque's lists pick from, and the bundled lists. It never changes
 * while the app runs, so it is built once.
 */
object AdhkarLibrary {

    val json: String by lazy { build() }

    private fun build(): String = buildJsonObject {
        put("afterSalahMaxMinutes", FlowTiming.MAX_AFTER_SALAH_MINUTES)
        putJsonObject("lists") {
            putJsonArray("afterSalah") { MosqueAdhkar.AFTER_SALAH_IDS.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("ticker") { MosqueAdhkar.TICKER_IDS.forEach { add(JsonPrimitive(it)) } }
        }
        putJsonArray("categories") {
            DhikrCategory.entries.forEach { category -> addJsonObject { put("id", category.name); put("title", category.title) } }
        }
        putJsonArray("entries") {
            DhikrCatalog.entries.filterNot { it.custom }.forEach { entry ->
                addJsonObject {
                    put("id", entry.id)
                    put("title", entry.title)
                    put("text", entry.text)
                    put("reference", entry.reference)
                    put("count", entry.countForCollection(DhikrCategory.SALAH))
                    if (entry.steps.isNotEmpty()) {
                        putJsonArray("steps") {
                            entry.steps.forEach { step -> addJsonObject { put("text", step.text); put("count", step.repetitions) } }
                        }
                    }
                    putJsonArray("categories") { entry.categories.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.name)) } }
                    val item = ReviewedDhikr(entry.id)
                    put("afterSalahMillis", MosqueAdhkar.afterSalahSlides(item).sumOf { it.durationMillis })
                    put("tickerMillis", MosqueAdhkar.tickerMillis(MosqueAdhkar.tickerSlides(item)))
                }
            }
        }
    }.toString()
}
