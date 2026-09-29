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
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerSilenceConfig
import com.tunisianprayertimes.PrayerWakeConfig
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.SilenceAlarmComputer
import com.tunisianprayertimes.WakeAlarmComputer
import com.tunisianprayertimes.WakePlaybackOptions
import com.tunisianprayertimes.MainActivityPendingIntents
import com.tunisianprayertimes.MathDifficulty
import com.tunisianprayertimes.WAKE_RECURRING_LOOKAHEAD_DAYS
import com.tunisianprayertimes.WakeMainAlarmMode
import com.tunisianprayertimes.hasPendingWakeOccurrenceSkip
import com.tunisianprayertimes.isRepeatingWakeAlarm
import com.tunisianprayertimes.isSkippingWakeOccurrence
import com.tunisianprayertimes.adhkar.DhikrReminderScheduler
import com.tunisianprayertimes.nap.NapSilenceController
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.abs

object WakeAlarmScheduler {
	private const val TAG = "WakeAlarmScheduler"
	private const val SCHEDULER_PREFS = "wake_alarm_scheduler"
	private const val KEY_SCHEDULED_EVENT_IDS = "scheduled_event_ids"
	private const val KEY_TRIGGER_PREFIX = "trigger:"
	private const val KEY_OCCURRENCE_PREFIX = "occurrence:"
	private const val KEY_DELIVERED_PREFIX = "delivered:"
	private const val KEY_SILENCED_ALARM_ID = "silenced_alarm_id"
	private const val KEY_SILENCED_ALARM_IDS = "silenced_alarm_ids"
	private const val KEY_SILENCE_PAUSED_FOR_ALARM_ID = "silence_paused_for_alarm_id"
	private const val REPAIR_REQUEST_CODE = 70_001
	private const val REPAIR_AFTER_LAST_ALARM_DELAY_MINUTES = 2L
	private val DUE_DELIVERY_GRACE_MILLIS = TimeUnit.HOURS.toMillis(12)
	private val reminderRefreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

	fun activateSilenceUntilAlarm(context: Context, alarmId: String): Boolean {
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		val pendingAlarmIds = silencedAlarmIds(context)
		if (pendingAlarmIds.isEmpty() && !NapSilenceController.enableNapSilence(context)) {
			return false
		}
		persistSilencedAlarmIds(
			context = context,
			alarmIds = pendingAlarmIds + alarmId,
			pausedForAlarmId = if (pendingAlarmIds.isEmpty()) null else prefs.getString(KEY_SILENCE_PAUSED_FOR_ALARM_ID, null),
		)
		Log.d(TAG, "Silence activated for alarm $alarmId")
		return true
	}

	fun releaseSilenceForRingingAlarm(context: Context, alarmId: String): Boolean {
		if (!isSilencedAlarm(context, alarmId)) return false
		val remainingAlarmIds = silencedAlarmIds(context) - alarmId
		val temporarilyLifted = shouldUseWakeSilence(context) && NapSilenceController.disableNapSilence(context)
		persistSilencedAlarmIds(
			context = context,
			alarmIds = remainingAlarmIds,
			pausedForAlarmId = if (remainingAlarmIds.isNotEmpty() && temporarilyLifted) alarmId else null,
		)
		if (temporarilyLifted) refreshDhikrRemindersAfterSilenceRelease(context)
		Log.d(TAG, "Silence released for ringing alarm $alarmId")
		return true
	}

	fun removeSilenceUntilAlarm(context: Context, alarmId: String): Boolean {
		if (!isSilencedAlarm(context, alarmId)) return false
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		val remainingAlarmIds = silencedAlarmIds(context) - alarmId
		val pausedForAlarmId = prefs.getString(KEY_SILENCE_PAUSED_FOR_ALARM_ID, null)
		val lifted = remainingAlarmIds.isEmpty() && pausedForAlarmId == null &&
			shouldUseWakeSilence(context) && NapSilenceController.disableNapSilence(context)
		persistSilencedAlarmIds(
			context = context,
			alarmIds = remainingAlarmIds,
			pausedForAlarmId = pausedForAlarmId?.takeIf { remainingAlarmIds.isNotEmpty() },
		)
		if (lifted) refreshDhikrRemindersAfterSilenceRelease(context)
		Log.d(TAG, "Silence removed for alarm $alarmId")
		return true
	}

	fun resumeSilenceUntilNextAlarm(context: Context): Boolean {
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		val pendingAlarmIds = silencedAlarmIds(context)
		val pausedForAlarmId = prefs.getString(KEY_SILENCE_PAUSED_FOR_ALARM_ID, null)
		if (pendingAlarmIds.isEmpty()) {
			persistSilencedAlarmIds(context, emptySet(), null)
			return false
		}
		if (pausedForAlarmId == null || !shouldUseWakeSilence(context)) {
			return false
		}
		if (!NapSilenceController.enableNapSilence(context)) {
			return false
		}
		persistSilencedAlarmIds(context, pendingAlarmIds, null)
		Log.d(TAG, "Silence resumed for pending alarm(s) ${pendingAlarmIds.joinToString()}")
		return true
	}

	fun isSilenceUntilAlarmActive(context: Context): Boolean {
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		return silencedAlarmIds(context).isNotEmpty() && !prefs.contains(KEY_SILENCE_PAUSED_FOR_ALARM_ID)
	}

	fun isSilencedAlarm(context: Context, alarmId: String): Boolean {
		return alarmId in silencedAlarmIds(context)
	}

	fun clearSilencedAlarmId(context: Context) {
		persistSilencedAlarmIds(context, emptySet(), null)
	}

	private fun shouldUseWakeSilence(context: Context): Boolean =
		!PrefsManager.isAutoSilenceActive(context) && !PrefsManager.isManualSilenceActive(context)

	private fun refreshDhikrRemindersAfterSilenceRelease(context: Context) {
		val app = context.applicationContext
		reminderRefreshScope.launch {
			runCatching { DhikrReminderScheduler.refresh(app, rearm = true) }
				.onFailure { error -> Log.w(TAG, "Could not refresh reminders after wake silence", error) }
		}
	}

	private fun silencedAlarmIds(context: Context): Set<String> {
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		return buildSet {
			prefs.getStringSet(KEY_SILENCED_ALARM_IDS, emptySet())
				?.filterTo(this) { alarmId -> alarmId.isNotBlank() }
			prefs.getString(KEY_SILENCED_ALARM_ID, null)
				?.takeIf { alarmId -> alarmId.isNotBlank() }
				?.let(::add)
		}
	}

	private fun persistSilencedAlarmIds(
		context: Context,
		alarmIds: Set<String>,
		pausedForAlarmId: String?,
	) {
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		prefs.edit().apply {
			remove(KEY_SILENCED_ALARM_ID)
			if (alarmIds.isEmpty()) {
				remove(KEY_SILENCED_ALARM_IDS)
			} else {
				putStringSet(KEY_SILENCED_ALARM_IDS, LinkedHashSet(alarmIds))
			}
			if (pausedForAlarmId == null) {
				remove(KEY_SILENCE_PAUSED_FOR_ALARM_ID)
			} else {
				putString(KEY_SILENCE_PAUSED_FOR_ALARM_ID, pausedForAlarmId)
			}
		}.apply()
	}

	suspend fun hasEnabledWakeAlarms(context: Context): Boolean =
		PrayerWakeRepository(context)
			.getCurrentStore()
			.alarms
			.any { config -> config.hasFutureWakeTriggers(System.currentTimeMillis()) }

	suspend fun toggleSkipNextWakeOccurrence(context: Context, alarmId: String): PrayerWakeConfig? {
		val repository = PrayerWakeRepository(context)
		val config = repository.getWakeAlarm(alarmId) ?: return null
		if (!config.isRepeatingWakeAlarm()) return null
		val now = Calendar.getInstance()
		if (config.hasPendingWakeOccurrenceSkip(now.timeInMillis)) {
			val skippedOccurrence = config.skipNextOccurrenceAtMillis
			val updated = repository.setWakeAlarmSkipOccurrence(alarmId, null) ?: return null
			skippedOccurrence?.let { occurrenceAtMillis ->
				WakeOccurrenceSkipRegistry.clear(alarmId, occurrenceAtMillis)
				WakeAlarmQueueHolder.queue.restorePendingForOccurrence(alarmId, occurrenceAtMillis)
			}
			rescheduleAlarmForSkipToggle(context, alarmId)
			return updated
		}
		if (!config.enabled) return null

		val activeOccurrenceAtMillis = WakeAlarmQueueHolder.queue.activeOccurrenceForAlarm(alarmId)
		val awakeCheckEventId = wakeMainEventId(alarmId)
		val scheduledAwakeOccurrenceAtMillis = AwakeCheckScheduler.occurrenceAtMillis(context, awakeCheckEventId)
		val awakeOccurrenceAtMillis = AwakeCheckService.activeOccurrenceForEvent(awakeCheckEventId)
			?: scheduledAwakeOccurrenceAtMillis
		val prayerDays = loadPrayerDayContexts(context, PrefsManager.getDelegationId(context), now)
		val nextOccurrenceAtMillis = activeOccurrenceAtMillis
			?: awakeOccurrenceAtMillis
			?: nextWakeOccurrenceAtMillis(now, config, prayerDays)
			?: return null
		val updated = repository.setWakeAlarmSkipOccurrence(alarmId, nextOccurrenceAtMillis) ?: return null
		WakeOccurrenceSkipRegistry.mark(alarmId, nextOccurrenceAtMillis)
		WakeAlarmQueueHolder.queue.discardSkippedOccurrencesForAlarm(alarmId, nextOccurrenceAtMillis)
		val removedDeliveredAlarm = WakeAlarmQueueHolder.queue.discardOccurrence(alarmId, nextOccurrenceAtMillis)
		if (removedDeliveredAlarm) {
			context.startService(
				Intent(context, WakePlaybackService::class.java)
					.setAction(WakePlaybackService.ACTION_REFRESH_FOR_CURRENT),
			)
			context.sendBroadcast(
				Intent(ACTION_SYNC_WAKE_ALERT).setPackage(context.packageName),
			)
		}
		if (scheduledAwakeOccurrenceAtMillis == nextOccurrenceAtMillis ||
			(nextOccurrenceAtMillis <= now.timeInMillis && scheduledAwakeOccurrenceAtMillis == null)
		) {
			AwakeCheckScheduler.cancel(context, awakeCheckEventId)
		}
		AwakeCheckService.cancelForOccurrence(context, awakeCheckEventId, nextOccurrenceAtMillis)
		// Cancel this alarm's remaining triggers now without touching another due alarm.
		rescheduleAlarmForSkipToggle(context, alarmId)
		return updated
	}

	private suspend fun rescheduleAlarmForSkipToggle(context: Context, alarmId: String) {
		val configs = PrayerWakeRepository(context).getCurrentStore().alarms
		scheduleAllInternal(context, Calendar.getInstance(), configs, onlyAlarmId = alarmId)
	}

	suspend fun scheduleAll(context: Context) {
		val repo = PrayerWakeRepository(context)
		val now = Calendar.getInstance()
		var configs = repo.getCurrentStore().alarms
		val staleSkipCutoffMillis = now.timeInMillis - TimeUnit.MINUTES.toMillis(REPAIR_AFTER_LAST_ALARM_DELAY_MINUTES)
		configs
			.filter { config ->
				val skippedOccurrence = config.skipNextOccurrenceAtMillis
				skippedOccurrence != null &&
					(!config.isRepeatingWakeAlarm() ||
						(!config.hasPendingWakeOccurrenceSkip(staleSkipCutoffMillis) &&
							!WakeOccurrenceSkipRegistry.wasRecentlyMarked(config.id, skippedOccurrence, now.timeInMillis)))
			}
			.forEach { config ->
				config.skipNextOccurrenceAtMillis?.let { occurrenceAtMillis ->
					WakeAlarmQueueHolder.queue.discardSkippedOccurrence(config.id, occurrenceAtMillis)
				}
				runCatching { repo.setWakeAlarmSkipOccurrence(config.id, null) }
		}
		configs = repo.getCurrentStore().alarms
		// Keep each skip on its original occurrence while any of its extras remain pending.
		scheduleAllInternal(context, now, configs)
		// Auto-delete one-off (FROM_NOW) alarms once all their triggers are in the past
		val nowMillis = System.currentTimeMillis()
		configs
			.filter { config -> config.isExpiredOneOffWakeAlarm(nowMillis) }
			.forEach { config -> runCatching { repo.deleteWakeAlarm(config.id) } }
	}

	private fun nextWakeOccurrenceAtMillis(
		now: Calendar,
		config: PrayerWakeConfig,
		prayerDays: List<WakeAlarmComputer.PrayerDayContext>,
	): Long? {
		val futureTriggers = WakeAlarmComputer
			.scheduledTriggers(config.copy(skipNextOccurrenceAtMillis = null), prayerDays)
			.filter { trigger -> trigger.triggerAtMillis > now.timeInMillis }
		// A main alarm that already rang still owns its upcoming extra alarms.
		return futureTriggers
			.filter { trigger -> trigger.occurrenceAtMillis <= now.timeInMillis }
			.maxByOrNull { trigger -> trigger.occurrenceAtMillis }
			?.occurrenceAtMillis
			?: futureTriggers.minByOrNull { trigger -> trigger.triggerAtMillis }?.occurrenceAtMillis
	}

	fun cancelAll(context: Context) {
		cancelEventIds(context, scheduledEventIds(context))
		persistScheduledEventIds(context, emptySet())
		cancelRepairAlarm(context)
		WakeAlarmVerifyWorker.cancel(context)
	}

	fun schedulingSnapshot(
		context: Context,
		configs: Collection<PrayerWakeConfig>,
		nowMillis: Long = System.currentTimeMillis(),
	): SchedulingSnapshot {
		val enabledFutureAlarmCount = configs.count { config -> config.hasFutureWakeTriggers(nowMillis) }
		val scheduledEventCount = scheduledEventIds(context).size
		val exactAlarmAllowed = canScheduleExactAlarms(context)
		val state = when {
			enabledFutureAlarmCount == 0 -> SchedulingState.NO_ENABLED_FUTURE_ALARMS
			!exactAlarmAllowed -> SchedulingState.EXACT_ALARM_PERMISSION_MISSING
			scheduledEventCount == 0 -> SchedulingState.NOT_SCHEDULED
			else -> SchedulingState.READY
		}

		return SchedulingSnapshot(
			state = state,
			enabledFutureAlarmCount = enabledFutureAlarmCount,
			scheduledEventCount = scheduledEventCount,
			exactAlarmAllowed = exactAlarmAllowed,
		)
	}

	fun canScheduleExactAlarms(context: Context): Boolean {
		val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		return canScheduleExactAlarms(alarmManager)
	}

	/** A delivered broadcast can be replaced with the next recurrence during refresh. */
	fun markDelivered(context: Context, payload: WakeTriggerPayload) {
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		if (prefs.getLong(KEY_TRIGGER_PREFIX + payload.eventId, 0L) == payload.triggerAtMillis) {
			prefs.edit().putBoolean(KEY_DELIVERED_PREFIX + payload.eventId, true).apply()
		}
	}

	/** A changed alarm should not keep an old due trigger during the next refresh. */
	fun invalidateScheduledTriggerMetadata(context: Context, alarmId: String) {
		val editor = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE).edit()
		scheduledEventIds(context)
			.filter { eventId -> wakeAlarmIdFromEventId(eventId) == alarmId }
			.forEach { eventId ->
				editor.remove(KEY_TRIGGER_PREFIX + eventId)
				editor.remove(KEY_OCCURRENCE_PREFIX + eventId)
				editor.remove(KEY_DELIVERED_PREFIX + eventId)
			}
		editor.apply()
	}

	@VisibleForTesting
	internal fun scheduleAllInternal(
		context: Context,
		now: Calendar,
		configs: Collection<PrayerWakeConfig>,
		onlyAlarmId: String? = null,
	) {
		val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		val previousEventIds = scheduledEventIds(context)
		val scopedEventIds = if (onlyAlarmId == null) previousEventIds else {
			previousEventIds.filterTo(mutableSetOf()) { eventId ->
				wakeAlarmIdFromEventId(eventId) == onlyAlarmId
			}
		}
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		val preservedDueEventIds = if (onlyAlarmId != null) emptySet() else scopedEventIds.filterTo(mutableSetOf()) { eventId ->
			val alarmId = wakeAlarmIdFromEventId(eventId)
			val config = configs.firstOrNull { candidate -> candidate.id == alarmId }
			val triggerAtMillis = prefs.getLong(KEY_TRIGGER_PREFIX + eventId, 0L)
			val occurrenceAtMillis = prefs.getLong(KEY_OCCURRENCE_PREFIX + eventId, 0L)
			config?.enabled == true &&
				!config.isSkippingWakeOccurrence(occurrenceAtMillis) &&
				triggerAtMillis in (now.timeInMillis - DUE_DELIVERY_GRACE_MILLIS)..now.timeInMillis &&
				!prefs.getBoolean(KEY_DELIVERED_PREFIX + eventId, false)
		}
		val replacedEventIds = scopedEventIds - preservedDueEventIds
		val retainedEventIds = previousEventIds - replacedEventIds
		cancelEventIds(context, replacedEventIds)

		if (!canScheduleExactAlarms(alarmManager)) {
			Log.w(TAG, "Cannot schedule wake alarms because exact alarm permission is missing")
			persistScheduledEventIds(context, retainedEventIds)
			if (onlyAlarmId == null && retainedEventIds.isEmpty()) cancelRepairAlarm(context)
			return
		}

		val enabledConfigs = configs.filter { config ->
			(onlyAlarmId == null || config.id == onlyAlarmId) &&
				config.hasFutureWakeTriggers(now.timeInMillis)
		}
		if (enabledConfigs.isEmpty()) {
			persistScheduledEventIds(context, retainedEventIds)
			if (onlyAlarmId == null) {
				val earliestDueAtMillis = preservedDueEventIds
					.map { eventId -> prefs.getLong(KEY_TRIGGER_PREFIX + eventId, 0L) }
					.minOrNull()
				if (earliestDueAtMillis == null) cancelRepairAlarm(context) else {
					scheduleRepairAlarm(
						context, alarmManager, now,
						earliestDueAtMillis + DUE_DELIVERY_GRACE_MILLIS -
							TimeUnit.MINUTES.toMillis(REPAIR_AFTER_LAST_ALARM_DELAY_MINUTES),
					)
				}
			}
			return
		}

		val delegationId = PrefsManager.getDelegationId(context)
		val prayerDays = loadPrayerDayContexts(context, delegationId, now)
		val silenceConfigs: Map<Prayer, PrayerSilenceConfig> = if (PrefsManager.isEnabled(context)) {
			Prayer.entries.associateWith { prayer -> PrefsManager.getConfig(context, prayer) }
		} else {
			emptyMap()
		}
		if (prayerDays.isEmpty() && enabledConfigs.any { config ->
			config.mainAlarm.mode != WakeMainAlarmMode.FROM_NOW
		}) {
			Log.w(TAG, "No prayer day contexts available for wake scheduling")
		}

		val scheduledEventIds = linkedSetOf<String>().apply { addAll(retainedEventIds) }
		var latestTriggerAtMillis: Long? = null
		enabledConfigs.forEach { config ->
			val autoSilenceOverrideAllowed = config.shouldUseAutoSilenceConflictPlayback()
			val result = WakeAlarmComputer.compute(now, config, prayerDays)
			val lastExtraOffset = config.subAlarms
				.map { subAlarm -> subAlarm.signedOffsetMinutes }
				.filter { offset -> offset >= 0 }
				.maxOrNull()
			// Extras at the same latest time can arrive in either order.
			val lastExtraIds = config.subAlarms
				.filter { subAlarm -> subAlarm.signedOffsetMinutes == lastExtraOffset }
				.mapTo(mutableSetOf()) { subAlarm -> subAlarm.id }
			val awakeCheckGroupSize = maxOf(
				1,
				lastExtraIds.size + if (lastExtraOffset == 0) 1 else 0,
			)
			result.mainAlarm?.let { trigger ->
				val eventId = wakeMainEventId(trigger.alarmId)
				if (eventId in preservedDueEventIds) return@let
				val autoSilenceConflictPrayer = trigger.autoSilenceConflictPrayer(prayerDays, silenceConfigs)
					.takeIf { autoSilenceOverrideAllowed }
				scheduleTrigger(
					context = context,
					alarmManager = alarmManager,
					trigger = trigger,
					eventId = eventId,
					autoSilenceConflictPrayer = autoSilenceConflictPrayer,
					autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
					awakeCheckPlayback = config.playback.takeIf {
						lastExtraOffset == null || lastExtraOffset == 0
					},
					awakeCheckGroupSize = awakeCheckGroupSize,
				)
				scheduledEventIds += eventId
				latestTriggerAtMillis = maxOf(latestTriggerAtMillis ?: Long.MIN_VALUE, trigger.triggerAtMillis)
			}
			result.subAlarms.forEach { trigger ->
				val eventId = wakeSubAlarmEventId(
					alarmId = trigger.alarmId,
					subAlarmId = requireNotNull(trigger.subAlarmId),
				)
				if (eventId in preservedDueEventIds) return@forEach
				val autoSilenceConflictPrayer = trigger.autoSilenceConflictPrayer(prayerDays, silenceConfigs)
					.takeIf { autoSilenceOverrideAllowed }
				scheduleTrigger(
					context = context,
					alarmManager = alarmManager,
					trigger = trigger,
					eventId = eventId,
					autoSilenceConflictPrayer = autoSilenceConflictPrayer,
					autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
					awakeCheckPlayback = config.playback.takeIf {
						trigger.subAlarmId?.let { subAlarmId -> subAlarmId in lastExtraIds } == true
					},
					awakeCheckGroupSize = awakeCheckGroupSize,
				)
				scheduledEventIds += eventId
				latestTriggerAtMillis = maxOf(latestTriggerAtMillis ?: Long.MIN_VALUE, trigger.triggerAtMillis)
			}
		}

		persistScheduledEventIds(context, scheduledEventIds)
		if (onlyAlarmId != null) return
		val needsRepairForSkippedOccurrences = enabledConfigs.any { config ->
			config.skipNextOccurrenceAtMillis != null && config.isRepeatingWakeAlarm()
		}
		if (scheduledEventIds.isNotEmpty() || needsRepairForSkippedOccurrences) {
			val dueGraceEndsAtMillis = preservedDueEventIds
				.map { eventId -> prefs.getLong(KEY_TRIGGER_PREFIX + eventId, 0L) + DUE_DELIVERY_GRACE_MILLIS }
				.minOrNull()
			val repairBaseAtMillis = dueGraceEndsAtMillis
				?.minus(TimeUnit.MINUTES.toMillis(REPAIR_AFTER_LAST_ALARM_DELAY_MINUTES))
				?.let { dueBase -> minOf(latestTriggerAtMillis ?: Long.MAX_VALUE, dueBase) }
				?: latestTriggerAtMillis
			scheduleRepairAlarm(context, alarmManager, now, repairBaseAtMillis)
		} else {
			cancelRepairAlarm(context)
		}
	}

	@VisibleForTesting
	internal fun repairTriggerAtMillis(now: Calendar, latestWakeTriggerAtMillis: Long?): Long {
		val midnightRepairAtMillis = (now.clone() as Calendar).apply {
			add(Calendar.DAY_OF_YEAR, 1)
			set(Calendar.HOUR_OF_DAY, 0)
			set(Calendar.MINUTE, 1)
			set(Calendar.SECOND, 0)
			set(Calendar.MILLISECOND, 0)
		}.timeInMillis

		val afterLastWakeAlarmAtMillis = latestWakeTriggerAtMillis
			?.plus(TimeUnit.MINUTES.toMillis(REPAIR_AFTER_LAST_ALARM_DELAY_MINUTES))
			?.takeIf { triggerAtMillis -> triggerAtMillis > now.timeInMillis }

		return listOfNotNull(afterLastWakeAlarmAtMillis, midnightRepairAtMillis)
			.minOrNull()
			?: midnightRepairAtMillis
	}

	private fun scheduleRepairAlarm(
		context: Context,
		alarmManager: AlarmManager,
		now: Calendar,
		latestWakeTriggerAtMillis: Long?,
	) {
		val triggerAtMillis = repairTriggerAtMillis(now, latestWakeTriggerAtMillis)
		try {
			alarmManager.setExactAndAllowWhileIdle(
				AlarmManager.RTC_WAKEUP,
				triggerAtMillis,
				createRepairPendingIntent(context),
			)
			Log.d(TAG, "Scheduled wake repair at ${Calendar.getInstance().apply { timeInMillis = triggerAtMillis }.time}")
		} catch (e: SecurityException) {
			Log.w(TAG, "Exact alarm denied for wake repair; skipping", e)
		}
	}

	private fun scheduleTrigger(
		context: Context,
		alarmManager: AlarmManager,
		trigger: WakeAlarmComputer.ScheduledWakeTrigger,
		eventId: String,
		autoSilenceConflictPrayer: Prayer?,
		autoSilenceOverrideAllowed: Boolean,
		awakeCheckPlayback: WakePlaybackOptions?,
		awakeCheckGroupSize: Int,
	) {
		val pendingIntent = createPendingIntent(
			context = context,
			trigger = trigger,
			eventId = eventId,
			autoSilenceConflictPrayer = autoSilenceConflictPrayer,
			autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
			awakeCheckPlayback = awakeCheckPlayback,
			awakeCheckGroupSize = awakeCheckGroupSize,
		)

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
						MainActivityPendingIntents.wakeAlarmClock(context),
					),
					pendingIntent,
				)
			}
			context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE).edit()
				.putLong(KEY_TRIGGER_PREFIX + eventId, trigger.triggerAtMillis)
				.putLong(KEY_OCCURRENCE_PREFIX + eventId, trigger.occurrenceAtMillis)
				.putBoolean(KEY_DELIVERED_PREFIX + eventId, false)
				.apply()
		} catch (e: SecurityException) {
			Log.w(TAG, "Exact alarm denied for $eventId; skipping", e)
		}
	}

	private fun createPendingIntent(
		context: Context,
		trigger: WakeAlarmComputer.ScheduledWakeTrigger,
		eventId: String,
		autoSilenceConflictPrayer: Prayer?,
		autoSilenceOverrideAllowed: Boolean,
		awakeCheckPlayback: WakePlaybackOptions?,
		awakeCheckGroupSize: Int,
	): PendingIntent {
		val payload = trigger.toPayload(
			eventId = eventId,
			autoSilenceConflictPrayer = autoSilenceConflictPrayer,
			autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
			awakeCheckPlayback = awakeCheckPlayback,
			awakeCheckGroupSize = awakeCheckGroupSize,
		)
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
				wakeUpCheckDifficulty = payload.wakeUpCheckDifficulty,
				whackAMoleKillTarget = payload.whackAMoleKillTarget,
				wakeUpCheckSteps = payload.wakeUpCheckSteps,
				progressiveVolume = payload.progressiveVolume,
				snoreTrackingEnabled = payload.snoreTrackingEnabled,
				autoSilenceOverrideAllowed = payload.autoSilenceOverrideAllowed,
				useAutoSilenceConflictPlayback = payload.useAutoSilenceConflictPlayback,
				autoSilenceConflictPrayer = payload.autoSilenceConflictPrayer,
				awakeCheckEnabled = payload.awakeCheckEnabled,
				awakeCheckDelayMinutes = payload.awakeCheckDelayMinutes,
				awakeCheckGroupSize = payload.awakeCheckGroupSize,
				awakeCheckRingtone = payload.awakeCheckRingtone,
				awakeCheckCustomRingtoneUri = payload.awakeCheckCustomRingtoneUri,
				wakeUpCheckChallenge = payload.wakeUpCheckChallenge,
				wakeUpCheckSeed = payload.wakeUpCheckSeed,
				isSubAlarm = payload.isSubAlarm,
				subAlarmId = payload.subAlarmId,
				offsetMinutes = payload.offsetMinutes,
				offsetDirection = payload.offsetDirection,
				triggerAtMillis = payload.triggerAtMillis,
				occurrenceAtMillis = payload.occurrenceAtMillis,
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
		val editor = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE).edit()
		eventIds.forEach { eventId ->
			editor.remove(KEY_TRIGGER_PREFIX + eventId)
			editor.remove(KEY_OCCURRENCE_PREFIX + eventId)
			editor.remove(KEY_DELIVERED_PREFIX + eventId)
		}
		editor.apply()
	}

	private fun cancelRepairAlarm(context: Context) {
		val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
		alarmManager.cancel(createRepairPendingIntent(context))
	}

	private fun createRepairPendingIntent(context: Context): PendingIntent {
		val intent = Intent(context, WakeAlarmRepairReceiver::class.java)
			.setAction(WakeAlarmRepairReceiver.ACTION_REPAIR)

		return PendingIntent.getBroadcast(
			context,
			REPAIR_REQUEST_CODE,
			intent,
			PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
		)
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

		return (-1..WAKE_RECURRING_LOOKAHEAD_DAYS).mapNotNull { dayOffset ->
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

	private fun WakeAlarmComputer.ScheduledWakeTrigger.toPayload(
		eventId: String,
		autoSilenceConflictPrayer: Prayer?,
		autoSilenceOverrideAllowed: Boolean,
		awakeCheckPlayback: WakePlaybackOptions?,
		awakeCheckGroupSize: Int,
	): WakeTriggerPayload {
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
			autoSilenceOverrideAllowed = autoSilenceOverrideAllowed,
			useAutoSilenceConflictPlayback = autoSilenceConflictPrayer != null,
			autoSilenceConflictPrayer = autoSilenceConflictPrayer,
			awakeCheckEnabled = awakeCheckPlayback?.awakeCheckEnabled == true,
			awakeCheckDelayMinutes = awakeCheckPlayback?.awakeCheckDelayMinutes
				?: playback.awakeCheckDelayMinutes,
			awakeCheckGroupSize = awakeCheckGroupSize,
			awakeCheckRingtone = awakeCheckPlayback?.ringtone,
			awakeCheckCustomRingtoneUri = awakeCheckPlayback?.customRingtoneUri,
			wakeUpCheckSeed = if (playback.wakeUpCheckEnabled) triggerAtMillis else null,
			wakeUpCheckChallenge = if (playback.wakeUpCheckEnabled) {
				wakeUpCheckChallengeFor(eventId, triggerAtMillis, playback.mathDifficulty)
			} else {
				null
			},
			isSubAlarm = isSubAlarm,
			triggerAtMillis = triggerAtMillis,
			occurrenceAtMillis = occurrenceAtMillis,
			subAlarmId = subAlarmId,
			offsetMinutes = if (isSubAlarm) abs(signedOffsetMinutes) else null,
			offsetDirection = if (isSubAlarm) signedOffsetMinutes.toOffsetDirection() else null,
		)
	}

	private fun WakeAlarmComputer.ScheduledWakeTrigger.autoSilenceConflictPrayer(
		prayerDays: List<WakeAlarmComputer.PrayerDayContext>,
		silenceConfigs: Map<Prayer, PrayerSilenceConfig>,
	): Prayer? {
		if (silenceConfigs.isEmpty()) {
			return null
		}

		return prayerDays
			.asSequence()
			.mapNotNull { prayerDay ->
				SilenceAlarmComputer.overlapForTrigger(
					triggerAtMillis = triggerAtMillis,
					prayerDay = prayerDay.date,
					prayerTimes = prayerDay.prayerTimes,
					configs = silenceConfigs,
					isFriday = prayerDay.isFriday,
					jomoaaHour = prayerDay.jomoaaHour,
					jomoaaMinute = prayerDay.jomoaaMinute,
				)?.prayer
			}
			.firstOrNull()
	}

	private fun PrayerWakeConfig.shouldUseAutoSilenceConflictPlayback(): Boolean =
		mainAlarm.mode == WakeMainAlarmMode.FROM_NOW || ringDuringSilenceWindow

	private fun Int.toOffsetDirection(): OffsetDirection =
		if (this < 0) OffsetDirection.BEFORE else OffsetDirection.AFTER

	private fun whackAMoleKillTargetFor(difficulty: MathDifficulty): Int = when (difficulty) {
		MathDifficulty.EASY -> 5
		MathDifficulty.INTERMEDIATE -> 10
		MathDifficulty.HARD -> 15
	}

	private fun Int.toMillis(): Long = this * 60_000L

	private fun canScheduleExactAlarms(alarmManager: AlarmManager): Boolean =
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
			alarmManager.canScheduleExactAlarms()
		} else {
			true
		}

	enum class SchedulingState {
		READY,
		NO_ENABLED_FUTURE_ALARMS,
		EXACT_ALARM_PERMISSION_MISSING,
		NOT_SCHEDULED,
	}

	data class SchedulingSnapshot(
		val state: SchedulingState,
		val enabledFutureAlarmCount: Int,
		val scheduledEventCount: Int,
		val exactAlarmAllowed: Boolean,
	)

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
