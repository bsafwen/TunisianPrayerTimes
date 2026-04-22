package com.tunisianprayertimes.wake

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.tunisianprayertimes.OffsetDirection
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.WakeAlarmComputer
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.WakeMainAlarmMode
import java.util.Calendar
import kotlin.math.abs

object WakeAlarmScheduler {
	private const val TAG = "WakeAlarmScheduler"
	private const val SCHEDULER_PREFS = "wake_alarm_scheduler"
	private const val KEY_SCHEDULED_EVENT_IDS = "scheduled_event_ids"

	suspend fun hasEnabledWakeAlarms(context: Context): Boolean =
		PrayerWakeRepository(context)
			.getCurrentStore()
			.alarms
			.any { config -> config.hasFutureTriggers(System.currentTimeMillis()) }

	suspend fun scheduleAll(context: Context) {
		val repo = PrayerWakeRepository(context)
		val configs = repo.getCurrentStore().alarms
		scheduleAllInternal(context, Calendar.getInstance(), configs)
		// Auto-delete one-off (FROM_NOW) alarms once all their triggers are in the past
		val nowMillis = System.currentTimeMillis()
		configs
			.filter { config ->
				config.mainAlarm.mode == WakeMainAlarmMode.FROM_NOW &&
					!config.hasFutureTriggers(nowMillis)
			}
			.forEach { config -> runCatching { repo.deleteWakeAlarm(config.id) } }
	}

	fun cancelAll(context: Context) {
		cancelEventIds(context, scheduledEventIds(context))
		persistScheduledEventIds(context, emptySet())
	}

	@VisibleForTesting
	internal fun scheduleAllInternal(
		context: Context,
		now: Calendar,
		configs: Collection<PrayerWakeConfig>,
	) {
		val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		cancelEventIds(context, scheduledEventIds(context))

		if (!canScheduleExactAlarms(alarmManager)) {
			Log.w(TAG, "Cannot schedule wake alarms because exact alarm permission is missing")
			persistScheduledEventIds(context, emptySet())
			return
		}

		val enabledConfigs = configs.filter { config -> config.hasFutureTriggers(now.timeInMillis) }
		if (enabledConfigs.isEmpty()) {
			persistScheduledEventIds(context, emptySet())
			return
		}

		val delegationId = PrefsManager.getDelegationId(context)
		val prayerDays = loadPrayerDayContexts(context, delegationId, now)
		if (prayerDays.isEmpty() && enabledConfigs.any { config -> config.mainAlarm.mode != WakeMainAlarmMode.FROM_NOW }) {
			Log.w(TAG, "No prayer day contexts available for wake scheduling")
		}

		val scheduledEventIds = linkedSetOf<String>()
		enabledConfigs.forEach { config ->
			val result = WakeAlarmComputer.compute(now, config, prayerDays)
			result.mainAlarm?.let { trigger ->
				val eventId = wakeMainEventId(trigger.alarmId)
				scheduleTrigger(context, alarmManager, trigger, eventId)
				scheduledEventIds += eventId
			}
			result.subAlarms.forEach { trigger ->
				val eventId = wakeSubAlarmEventId(
					alarmId = trigger.alarmId,
					subAlarmId = requireNotNull(trigger.subAlarmId),
				)
				scheduleTrigger(context, alarmManager, trigger, eventId)
				scheduledEventIds += eventId
			}
		}

		persistScheduledEventIds(context, scheduledEventIds)
	}

	private fun scheduleTrigger(
		context: Context,
		alarmManager: AlarmManager,
		trigger: WakeAlarmComputer.ScheduledWakeTrigger,
		eventId: String,
	) {
		val pendingIntent = createPendingIntent(context, trigger, eventId)

		try {
			if (trigger.isSubAlarm) {
				alarmManager.setExactAndAllowWhileIdle(
					AlarmManager.RTC_WAKEUP,
					trigger.triggerAtMillis,
					pendingIntent,
				)
			} else {
				alarmManager.setAlarmClock(
					AlarmManager.AlarmClockInfo(
						trigger.triggerAtMillis,
						WakePlaybackService.alarmClockInfoIntent(context),
					),
					pendingIntent,
				)
			}
		} catch (e: SecurityException) {
			Log.w(TAG, "Exact alarm denied for $eventId; skipping", e)
		}
	}

	private fun createPendingIntent(
		context: Context,
		trigger: WakeAlarmComputer.ScheduledWakeTrigger,
		eventId: String,
	): PendingIntent {
		val payload = trigger.toPayload(eventId)
		val intent = Intent(context, WakeAlarmReceiver::class.java)
			.populateWakeTriggerPayload(
				eventId = payload.eventId,
				prayer = payload.prayer,
				effectivePrayer = payload.effectivePrayer,
				mainAlarmMode = payload.mainAlarmMode,
				hour = payload.hour,
				minute = payload.minute,
				ringtone = payload.ringtone,
				customRingtoneUri = payload.customRingtoneUri,
				vibrationOnly = payload.vibrationOnly,
				wakeUpCheckEnabled = payload.wakeUpCheckEnabled,
				wakeUpCheckType = payload.wakeUpCheckType,
				whackAMoleKillTarget = payload.whackAMoleKillTarget,
				wakeUpCheckSteps = payload.wakeUpCheckSteps,
				progressiveVolume = payload.progressiveVolume,
				snoreTrackingEnabled = payload.snoreTrackingEnabled,
				awakeCheckEnabled = payload.awakeCheckEnabled,
				awakeCheckDelayMinutes = payload.awakeCheckDelayMinutes,
				wakeUpCheckChallenge = payload.wakeUpCheckChallenge,
				isSubAlarm = payload.isSubAlarm,
				subAlarmId = payload.subAlarmId,
				offsetMinutes = payload.offsetMinutes,
				offsetDirection = payload.offsetDirection,
			)
			.setAction(eventId)
			.setData(wakeEventUri(eventId))

		return PendingIntent.getBroadcast(
			context,
			wakeEventRequestCode(eventId),
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
	}

	private fun cancelEventIds(context: Context, eventIds: Set<String>) {
		if (eventIds.isEmpty()) {
			return
		}

		val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		eventIds.forEach { eventId ->
			alarmManager.cancel(cancelPendingIntent(context, eventId))
		}
	}

	private fun cancelPendingIntent(context: Context, eventId: String): PendingIntent {
		val intent = Intent(context, WakeAlarmReceiver::class.java)
			.setAction(eventId)
			.setData(wakeEventUri(eventId))

		return PendingIntent.getBroadcast(
			context,
			wakeEventRequestCode(eventId),
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
	}

	private fun loadPrayerDayContexts(
		context: Context,
		delegationId: Int,
		now: Calendar,
	): List<WakeAlarmComputer.PrayerDayContext> {
		val jomoaaHour = PrefsManager.getJomoaaTimeHour(context)
		val jomoaaMinute = PrefsManager.getJomoaaTimeMinute(context)

		return (-1..2).mapNotNull { dayOffset ->
			val date = (now.clone() as Calendar).apply {
				add(Calendar.DAY_OF_YEAR, dayOffset)
			}
			PrayerTimesRepository.loadDayPrayerTimes(
				context = context,
				delegationId = delegationId,
				year = date.get(Calendar.YEAR),
				month = date.get(Calendar.MONTH) + 1,
				day = date.get(Calendar.DAY_OF_MONTH),
			)?.let { prayerTimes ->
				WakeAlarmComputer.PrayerDayContext(
					date = date,
					prayerTimes = prayerTimes,
					isFriday = date.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY,
					jomoaaHour = jomoaaHour,
					jomoaaMinute = jomoaaMinute,
				)
			}
		}
	}

	private fun WakeAlarmComputer.ScheduledWakeTrigger.toPayload(eventId: String): WakeTriggerPayload {
		val mainTriggerAtMillis = triggerAtMillis - signedOffsetMinutes.toMillis()
		val mainTriggerTime = Calendar.getInstance().apply {
			timeInMillis = mainTriggerAtMillis
		}

		return WakeTriggerPayload(
			eventId = eventId,
			prayer = prayer,
			effectivePrayer = effectivePrayer,
			mainAlarmMode = mainAlarmMode,
			hour = mainTriggerTime.get(Calendar.HOUR_OF_DAY),
			minute = mainTriggerTime.get(Calendar.MINUTE),
			ringtone = playback.ringtone,
			customRingtoneUri = playback.customRingtoneUri,
			vibrationOnly = playback.vibrationOnly,
			wakeUpCheckEnabled = playback.wakeUpCheckEnabled,
			wakeUpCheckType = playback.wakeUpCheckType,
			wakeUpCheckDifficulty = playback.mathDifficulty,
			whackAMoleKillTarget = whackAMoleKillTargetFor(playback.mathDifficulty),
			wakeUpCheckSteps = playback.effectiveWakeUpCheckSteps,
			progressiveVolume = playback.progressiveVolume,
			snoreTrackingEnabled = playback.snoreTrackingEnabled,
			awakeCheckEnabled = if (isSubAlarm) false else playback.awakeCheckEnabled,
			awakeCheckDelayMinutes = playback.awakeCheckDelayMinutes,
			wakeUpCheckChallenge = if (playback.wakeUpCheckEnabled) {
				wakeUpCheckChallengeFor(eventId, triggerAtMillis, playback.mathDifficulty)
			} else {
				null
			},
			isSubAlarm = isSubAlarm,
			subAlarmId = subAlarmId,
			offsetMinutes = if (isSubAlarm) abs(signedOffsetMinutes) else null,
			offsetDirection = if (isSubAlarm) signedOffsetMinutes.toOffsetDirection() else null,
		)
	}

	private fun Int.toOffsetDirection(): OffsetDirection =
		if (this < 0) OffsetDirection.BEFORE else OffsetDirection.AFTER

	private fun whackAMoleKillTargetFor(difficulty: MathDifficulty): Int = when (difficulty) {
		MathDifficulty.EASY -> 5
		MathDifficulty.INTERMEDIATE -> 10
		MathDifficulty.HARD -> 15
	}

	private fun Int.toMillis(): Long = this * 60_000L

	private fun PrayerWakeConfig.hasFutureTriggers(nowMillis: Long): Boolean {
		if (!enabled) {
			return false
		}

		if (mainAlarm.mode != WakeMainAlarmMode.FROM_NOW) {
			return true
		}

		val mainTriggerAtMillis = mainAlarm.oneOffTriggerAtMillis
		if (mainTriggerAtMillis > nowMillis) {
			return true
		}

		if (mainTriggerAtMillis <= 0L) {
			return false
		}

		return subAlarms.any { subAlarm ->
			mainTriggerAtMillis + subAlarm.signedOffsetMinutes.toMillis() > nowMillis
		}
	}

	private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			alarmManager.canScheduleExactAlarms()
		} else {
			true
		}

	private fun scheduledEventIds(context: Context): Set<String> =
		context
			.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
			.getStringSet(KEY_SCHEDULED_EVENT_IDS, emptySet())
			?.toSet()
			?: emptySet()

	private fun persistScheduledEventIds(context: Context, eventIds: Set<String>) {
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		prefs.edit().apply {
			if (eventIds.isEmpty()) {
				remove(KEY_SCHEDULED_EVENT_IDS)
			} else {
				putStringSet(KEY_SCHEDULED_EVENT_IDS, LinkedHashSet(eventIds))
			}
		}.apply()
	}
}