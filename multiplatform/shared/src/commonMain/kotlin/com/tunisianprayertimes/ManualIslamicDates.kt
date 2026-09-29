package com.tunisianprayertimes

import com.tunisianprayertimes.platform.Preferences
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Ramadan and Eid dates a mosque admin set by hand for one Hijri year. Each date that is set
 * wins over the official announcement and over the estimate (the mosque follows its own sighting,
 * or an offline TV never received the announcement).
 */
data class ManualIslamicDates(
    val ramadanStart: LocalDate? = null,
    val eidFitr: LocalDate? = null,
    val eidAdha: LocalDate? = null,
) {
    val isEmpty: Boolean get() = ramadanStart == null && eidFitr == null && eidAdha == null
}

/** The admin's dates, persisted. The TV sets them; every calendar in both apps obeys them. */
object ManualIslamicDateOverrides {
    internal var store = ManualIslamicDateStore(
        read = Preferences::getManualIslamicDatesJson,
        write = Preferences::setManualIslamicDatesJson,
    )

    val updates: StateFlow<Map<Int, ManualIslamicDates>> get() = store.updates

    fun forYear(hijriYear: Int): ManualIslamicDates = store.all()[hijriYear] ?: ManualIslamicDates()

    /** Replaces the year's dates; empty dates return the year to announcements and estimates. */
    fun set(hijriYear: Int, dates: ManualIslamicDates) = store.set(hijriYear, dates)

    fun clear(hijriYear: Int) = store.set(hijriYear, ManualIslamicDates())

    /** Every year the admin set dates for. */
    fun all(): Map<Int, ManualIslamicDates> = store.all()
}

/** Loads lazily (preferences may not be ready when the object is first touched) and never throws. */
internal class ManualIslamicDateStore(
    private val read: () -> String?,
    private val write: (String?) -> Unit,
) {
    private val lock = Any()
    private var loaded = false
    private val state = MutableStateFlow<Map<Int, ManualIslamicDates>>(emptyMap())
    val updates: StateFlow<Map<Int, ManualIslamicDates>> = state.asStateFlow()

    fun all(): Map<Int, ManualIslamicDates> {
        ensureLoaded()
        return state.value
    }

    fun set(hijriYear: Int, dates: ManualIslamicDates) {
        synchronized(lock) {
            ensureLoaded()
            val next = if (dates.isEmpty) state.value - hijriYear else state.value + (hijriYear to dates)
            if (next == state.value) return
            state.value = next
            runCatching { write(if (next.isEmpty()) null else encode(next)) }
        }
    }

    private fun ensureLoaded() = synchronized(lock) {
        if (loaded) return
        val text = runCatching { read() }.getOrElse { return } // not ready yet: try again next time
        loaded = true
        state.value = text?.let(::decode).orEmpty()
    }

    internal companion object {
        fun encode(all: Map<Int, ManualIslamicDates>): String = buildJsonObject {
            all.toSortedMap().forEach { (year, dates) ->
                put(year.toString(), buildJsonObject {
                    dates.ramadanStart?.let { put("ramadanStart", it.toString()) }
                    dates.eidFitr?.let { put("eidFitr", it.toString()) }
                    dates.eidAdha?.let { put("eidAdha", it.toString()) }
                })
            }
        }.toString()

        fun decode(text: String): Map<Int, ManualIslamicDates> = runCatching {
            val root = Json.parseToJsonElement(text) as JsonObject
            root.mapNotNull { (year, value) ->
                val fields = value as? JsonObject ?: return@mapNotNull null
                fun date(key: String) = (fields[key] as? JsonPrimitive)?.jsonPrimitive?.content
                    ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                val dates = ManualIslamicDates(date("ramadanStart"), date("eidFitr"), date("eidAdha"))
                year.toIntOrNull()?.takeUnless { dates.isEmpty }?.let { it to dates }
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}

/** [official] records with the admin's dates laid over them, field by field. */
internal fun withManualDates(
    official: Map<Int, RamadanOverrideChecker.RamadanOverride>,
    manual: Map<Int, ManualIslamicDates>,
): Map<Int, RamadanOverrideChecker.RamadanOverride> {
    if (manual.isEmpty()) return official
    val merged = official.toMutableMap()
    manual.forEach { (year, dates) ->
        val base = merged[year] ?: RamadanOverrideChecker.RamadanOverride(year, null, null, null)
        merged[year] = base.copy(
            ramadanStart = dates.ramadanStart ?: base.ramadanStart,
            eidFitrDate = dates.eidFitr ?: base.eidFitrDate,
            eidAdhaDate = dates.eidAdha ?: base.eidAdhaDate,
        )
    }
    return merged
}
