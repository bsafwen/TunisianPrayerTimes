package com.tunisianprayertimes.wake

import android.content.Context
import android.util.Log
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.engine.PrayerDay
import com.tunisianprayertimes.engine.TimeEvent
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val arabicMarks = Regex("[\\p{M}ـ]")
private val titleSeparators = Regex("[^\\p{L}\\p{N}]+")

private fun normalizedMotivationTitle(title: String): String = title
    .lowercase(Locale.ROOT)
    .replace(arabicMarks, "")
    .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
    .replace(titleSeparators, " ")
    .trim()

private val quranPurposes = listOf(
    "قراءة القرآن", "قراءة قرآن", "ورد القرآن", "تلاوة القرآن", "حفظ القرآن", "مراجعة القرآن",
    "quran reading", "quran review", "quran memorization",
).map(::normalizedMotivationTitle)

private val nightPurposes = listOf(
    "قيام الليل", "صلاة الليل", "تهجد", "التهجد", "tahajjud", "qiyam", "night prayer",
).map(::normalizedMotivationTitle)

private fun String.namesPurpose(purposes: List<String>): Boolean =
    purposes.any { " $this ".contains(" $it ") }

private fun Instant.isBetween(start: Instant?, end: Instant?): Boolean =
    start != null && end != null && start < end && this >= start && this < end

/**
 * Context comes from the configured purpose and the selected place's actual prayer instants, never a
 * hard-coded 05:00 or the placeholder FAJR value carried by fixed-time and timer payloads.
 * Missing solar events leave only messages whose context can actually be established.
 */
internal fun wakeMotivationTopics(
    now: Instant,
    linkedPrayer: Prayer?,
    alarmTitle: String,
    days: List<PrayerDay>,
): Set<WakeMotivationTopic> = buildSet {
    add(WakeMotivationTopic.GENERAL)
    if (linkedPrayer != null) add(WakeMotivationTopic.PRAYER)
    val title = normalizedMotivationTitle(alarmTitle)
    if (title.namesPurpose(quranPurposes)) add(WakeMotivationTopic.QURAN_READING)

    for (day in days) {
        val fajr = day[TimeEvent.FAJR].at
        val sunrise = day[TimeEvent.SUNRISE].at
        if (fajr != null && sunrise != null) {
            val morningEnd = minOf(sunrise.plus(Duration.ofHours(2)), day[TimeEvent.DHUHR].at ?: Instant.MAX)
            if (now.isBetween(fajr, morningEnd)) add(WakeMotivationTopic.MORNING)
            // A large offset from Fajr must not make an afternoon alarm show Fajr-specific text.
            if (linkedPrayer == Prayer.FAJR && now.isBetween(fajr.minus(Duration.ofHours(2)), sunrise)) {
                add(WakeMotivationTopic.FAJR)
                if (now >= fajr) add(WakeMotivationTopic.FAJR_SUNNAH)
            }
        }
        val asr = day[TimeEvent.ASR].at
        if (linkedPrayer == Prayer.ASR && now.isBetween(asr?.minus(Duration.ofHours(2)), day[TimeEvent.MAGHRIB].at)) {
            add(WakeMotivationTopic.ASR)
        }
    }

    // A clock time alone does not say that the person intends voluntary night prayer.
    if (title.namesPurpose(nightPurposes)) {
        val isha = days.mapNotNull { it[TimeEvent.ISHA].at }.filter { it <= now }.maxOrNull()
        val fajr = isha?.let { start ->
            days.mapNotNull { it[TimeEvent.FAJR].at }.filter { it > start }.minOrNull()
        }
        if (isha != null && fajr != null && now.isBetween(isha, fajr)) {
            add(WakeMotivationTopic.NIGHT_PRAYER)
            add(WakeMotivationTopic.PRAYER)
            val sunset = days.mapNotNull { it[TimeEvent.MAGHRIB].at }.filter { it <= isha }.maxOrNull()
            if (sunset != null && sunset < fajr) {
                val lastThird = fajr.minus(Duration.between(sunset, fajr).dividedBy(3))
                if (now >= lastThird) add(WakeMotivationTopic.PRE_DAWN)
            }
        }
    }
}

internal suspend fun resolveWakeMotivation(
    context: Context,
    payload: WakeTriggerPayload,
    now: Instant = Instant.now(),
): WakeMotivation = withContext(Dispatchers.IO) {
    val alarmId = wakeAlarmIdFromEventId(payload.eventId).orEmpty().ifBlank { payload.eventId }
    try {
        val place = PrayerTimesRepository.place(context)
        val today = now.atZone(place.zone).toLocalDate()
        val days = (-1L..1L).mapNotNull { offset ->
            PrayerTimesRepository.prayerDay(context, today.plusDays(offset), place)
        }
        val title = PrayerWakeRepository(context).getWakeAlarm(alarmId)?.title.orEmpty()
        val linkedPrayer = payload.effectivePrayer.takeIf { payload.mainAlarmMode == WakeMainAlarmMode.PRAYER_RELATIVE }
        val topics = wakeMotivationTopics(now, linkedPrayer, title, days)
        val occurrence = payload.occurrenceAtMillis.takeIf { it > 0 }
            ?: payload.triggerAtMillis.takeIf { it > 0 }
        val rotationDay = (occurrence?.let(Instant::ofEpochMilli) ?: now)
            .atZone(place.zone).toLocalDate().toEpochDay()
        WakeMotivations.select(topics, alarmId, rotationDay)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        // An unavailable catalogue or a failed preferences read must never affect alarm controls.
        Log.w("WakeMotivation", "Using a general message because the alarm context is unavailable", error)
        WakeMotivations.select(
            setOf(WakeMotivationTopic.GENERAL), alarmId,
            now.atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay(),
        )
    }
}
