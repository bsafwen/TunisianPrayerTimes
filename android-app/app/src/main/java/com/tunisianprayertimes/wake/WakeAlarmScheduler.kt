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
import com.tunisianprayertimes.isSuppressedWakeOccurrence
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
	private val DUE_RING_GRACE_MILLIS = TimeUnit.MINUTES.toMillis(15)
	private const val KEY_RING_OFFSETS_PREFIX = "rings:"
	private val RING_OFFSETS_RETAIN_MILLIS = TimeUnit.DAYS.toMillis(2)
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

	/** Lifts the silence for the pending alarms while another alarm rings, until [resumeSilenceUntilNextAlarm]. */
	fun pauseSilenceForRingingAlarm(context: Context, alarmId: String): Boolean {
		if (!isSilenceUntilAlarmActive(context) || !shouldUseWakeSilence(context)) return false
		if (!NapSilenceController.disableNapSilence(context)) return false
		persistSilencedAlarmIds(context, silencedAlarmIds(context), alarmId)
		refreshDhikrRemindersAfterSilenceRelease(context)
		Log.d(TAG, "Silence paused for ringing alarm $alarmId")
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
		// A prayer or manual silence does not block the resume: when it ends, it hands the phone
		// over to this silence instead of restoring its state.
		if (pausedForAlarmId == null) {
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

	/** One occurrence of an alarm, from its earliest ring to its latest, extras included. */
	data class OccurrenceWindow(
		val occurrenceAtMillis: Long,
		val startsAtMillis: Long,
		val endsAtMillis: Long,
	)

	/** Every occurrence of the alarm around today, a skipped one included but not one the user stopped. */
	fun occurrenceWindows(context: Context, delegationId: Int, config: PrayerWakeConfig): List<OccurrenceWindow> =
		occurrenceWindows(config, loadPrayerDayContexts(context, delegationId, Calendar.getInstance()))

	private fun occurrenceWindows(
		config: PrayerWakeConfig,
		prayerDays: List<WakeAlarmComputer.PrayerDayContext>,
	): List<OccurrenceWindow> =
		WakeAlarmComputer
			.scheduledTriggers(config.copy(skipNextOccurrenceAtMillis = null), prayerDays)
			.groupBy { trigger -> trigger.occurrenceAtMillis }
			.map { (occurrenceAtMillis, triggers) ->
				OccurrenceWindow(
					occurrenceAtMillis = occurrenceAtMillis,
					startsAtMillis = triggers.minOf { trigger -> trigger.triggerAtMillis },
					endsAtMillis = triggers.maxOf { trigger -> trigger.triggerAtMillis },
				)
			}

	/** One ring of an occurrence: when it is or was due, and whether it is an extra or the main alarm. */
	data class Ring(val atMillis: Long, val isExtra: Boolean)

	/** An occurrence in progress: what is ringing or late, and its awake check once the last ring is stopped. */
	data class RunningOccurrence(
		val occurrenceAtMillis: Long,
		/** The ring going now, or one waiting in the queue behind another alarm's ring ([ringWaiting]). */
		val ringing: Ring? = null,
		val ringWaiting: Boolean = false,
		/** A ring past its time that Android has not delivered yet. */
		val dueRing: Ring? = null,
		/** When the awake check starts, or started if it is running. */
		val awakeCheckAtMillis: Long? = null,
		/** When the awake check was set, i.e. when the last ring was stopped. */
		val awakeCheckFromMillis: Long? = null,
		val awakeCheckRunning: Boolean = false,
		/** The occurrence's rings as signed minutes from its main alarm, as they were scheduled. */
		val ringOffsets: List<Int>? = null,
	)

	/**
	 * The alarm's occurrence in progress: from its earliest ring to its latest, and while a delivered ring, a ring that
	 * is late, or the awake check that follows is still going. A skipped occurrence never rings, so it is not in
	 * progress. The alarm row offers Stop in this state and Skip otherwise; the hero and the row show this occurrence
	 * until it ends.
	 */
	fun runningOccurrence(
		context: Context,
		config: PrayerWakeConfig,
		windows: List<OccurrenceWindow>,
		nowMillis: Long = System.currentTimeMillis(),
	): RunningOccurrence? {
		val awakeCheckEventId = wakeMainEventId(config.id)
		val queuedRing = WakeAlarmQueueHolder.queue.ringForAlarm(config.id)
		val awakeCheckRunningFor = AwakeCheckService.activeOccurrenceForEvent(awakeCheckEventId)
		val awakeCheckPendingFor = AwakeCheckScheduler.occurrenceAtMillis(context, awakeCheckEventId)
			?.takeIf { AwakeCheckScheduler.isPending(context, awakeCheckEventId, nowMillis) }
		val dueRings = dueRings(context, config, nowMillis)
		val occurrenceAtMillis = queuedRing?.payload?.resolvedOccurrenceAtMillis()
			?: awakeCheckRunningFor
			?: awakeCheckPendingFor
			?: dueRings.minByOrNull { (_, ring) -> ring.atMillis }?.first
			?: windows
				.firstOrNull { window ->
					nowMillis in window.startsAtMillis..window.endsAtMillis &&
						!config.isSkippingWakeOccurrence(window.occurrenceAtMillis)
				}
				?.occurrenceAtMillis
			?: return null
		val awakeCheckRunning = awakeCheckRunningFor == occurrenceAtMillis
		val awakeCheckOfThisOccurrence = awakeCheckRunning || awakeCheckPendingFor == occurrenceAtMillis
		val ownRing = queuedRing?.takeIf { ring -> ring.payload.resolvedOccurrenceAtMillis() == occurrenceAtMillis }
		return RunningOccurrence(
			occurrenceAtMillis = occurrenceAtMillis,
			ringing = ownRing?.payload?.let { payload -> Ring(payload.triggerAtMillis, payload.isSubAlarm) },
			ringWaiting = ownRing?.waiting == true,
			dueRing = dueRings
				.filter { (occurrence, _) -> occurrence == occurrenceAtMillis }
				.minByOrNull { (_, ring) -> ring.atMillis }
				?.second,
			awakeCheckAtMillis = when {
				awakeCheckRunning -> AwakeCheckService.activeTriggerForEvent(awakeCheckEventId)
					?: AwakeCheckScheduler.triggerAtMillis(context, awakeCheckEventId)
				awakeCheckOfThisOccurrence -> AwakeCheckScheduler.triggerAtMillis(context, awakeCheckEventId)
				else -> null
			},
			awakeCheckFromMillis = AwakeCheckScheduler.scheduledAtMillis(context, awakeCheckEventId)
				.takeIf { awakeCheckOfThisOccurrence },
			awakeCheckRunning = awakeCheckRunning,
			ringOffsets = occurrenceRingOffsets(context, config.id, occurrenceAtMillis),
		)
	}

	/**
	 * Rings of the alarm past their time that Android has not delivered yet, with their occurrences, for at most
	 * [DUE_RING_GRACE_MILLIS]. Until one arrives its occurrence stays in progress, so Stop can still end it and Skip
	 * cannot be pressed for the next alarm by mistake.
	 */
	private fun dueRings(context: Context, config: PrayerWakeConfig, nowMillis: Long): List<Pair<Long, Ring>> {
		val prefs = context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
		val mainEventId = wakeMainEventId(config.id)
		return scheduledEventIds(context)
			.filter { eventId -> wakeAlarmIdFromEventId(eventId) == config.id }
			.mapNotNull { eventId ->
				val triggerAtMillis = prefs.getLong(KEY_TRIGGER_PREFIX + eventId, 0L)
				val occurrenceAtMillis = prefs.getLong(KEY_OCCURRENCE_PREFIX + eventId, 0L)
				val due = triggerAtMillis in (nowMillis - DUE_RING_GRACE_MILLIS + 1)..nowMillis &&
					occurrenceAtMillis > 0L &&
					!prefs.getBoolean(KEY_DELIVERED_PREFIX + eventId, false) &&
					!config.isSuppressedWakeOccurrence(occurrenceAtMillis)
				if (due) occurrenceAtMillis to Ring(triggerAtMillis, isExtra = eventId != mainEventId) else null
			}
	}

	/** The occurrence's rings as signed minutes from its main alarm, as scheduled before it started. */
	private fun occurrenceRingOffsets(context: Context, alarmId: String, occurrenceAtMillis: Long): List<Int>? =
		context.getSharedPreferences(SCHEDULER_PREFS, Context.MODE_PRIVATE)
			.getString(ringOffsetsKey(alarmId, occurrenceAtMillis), null)
			?.split(',')
			?.mapNotNull { offset -> offset.toIntOrNull() }
			?.takeIf { offsets -> offsets.isNotEmpty() }

	/** Whether the alarm's awake check is still to start or running, so a finished one-off is kept for it. */
	fun hasAwakeCheckInProgress(context: Context, alarmId: String): Boolean {
		val eventId = wakeMainEventId(alarmId)
		return AwakeCheckService.activeOccurrenceForEvent(eventId) != null || AwakeCheckScheduler.isPending(context, eventId)
	}

	/** Deletes a one-off whose rings are all over once its awake check has ended. */
	fun removeExpiredOneOffAfterAwakeCheckAsync(context: Context, alarmId: String) {
		reminderRefreshScope.launch {
			runCatching {
				val repository = PrayerWakeRepository(context)
				val config = repository.getWakeAlarm(alarmId) ?: return@runCatching
				if (config.isExpiredOneOffWakeAlarm(System.currentTimeMillis()) && !hasAwakeCheckInProgress(context, alarmId)) {
					repository.deleteWakeAlarm(alarmId)
				}
			}.onFailure { error -> Log.w(TAG, "Failed to remove one-off $alarmId after its awake check", error) }
		}
	}

	/**
	 * Stops the alarm's running occurrence whatever the type of its rings: the one ringing, the extras still to come
	 * and the awake check. A repeating alarm keeps its later occurrences, and its skip stays free for the next alarm.
	 * A one-off is turned off, which also keeps a trigger already on its way quiet.
	 */
	suspend fun stopWakeAlarm(context: Context, alarmId: String): PrayerWakeConfig? {
		val repository = PrayerWakeRepository(context)
		val config = repository.getWakeAlarm(alarmId) ?: return null
		val now = Calendar.getInstance()
		val awakeCheckEventId = wakeMainEventId(alarmId)
		val prayerDays = loadPrayerDayContexts(context, PrefsManager.getDelegationId(context), now)
		val occurrenceAtMillis = runningOccurrence(context, config, occurrenceWindows(config, prayerDays), now.timeInMillis)
			?.occurrenceAtMillis
			?: return null
		val repeating = config.isRepeatingWakeAlarm()
		val updated: PrayerWakeConfig? = if (repeating) {
			repository.setWakeAlarmStoppedOccurrence(alarmId, occurrenceAtMillis)
				?.also { WakeOccurrenceSkipRegistry.mark(alarmId, occurrenceAtMillis) }
		} else {
			config.copy(enabled = false).also { disabled -> repository.saveWakeConfig(disabled) }
		}
		if (updated == null) return null
		val queue = WakeAlarmQueueHolder.queue
		if (queue.discardOccurrence(alarmId, occurrenceAtMillis)) {
			context.startService(
				Intent(context, WakePlaybackService::class.java)
					.setAction(WakePlaybackService.ACTION_REFRESH_FOR_CURRENT),
			)
			context.sendBroadcast(Intent(ACTION_SYNC_WAKE_ALERT).setPackage(context.packageName))
		}
		// A stop is not undone, so nothing queued for this occurrence is kept for a restore.
		queue.discardSkippedOccurrence(alarmId, occurrenceAtMillis)
		AwakeCheckScheduler.cancel(context, awakeCheckEventId)
		AwakeCheckService.cancelForOccurrence(context, awakeCheckEventId, occurrenceAtMillis)
		if (repeating) rescheduleAlarmForSkipToggle(context, alarmId) else scheduleAll(context)
		return updated
	}

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

		// Skipping concerns the next alarm; the running occurrence, if any, is ended with stopWakeAlarm.
		val prayerDays = loadPrayerDayContexts(context, PrefsManager.getDelegationId(context), now)
		val nextOccurrenceAtMillis = occurrenceWindows(config, prayerDays)
			.filter { window -> window.startsAtMillis > now.timeInMillis }
			.minByOrNull { window -> window.startsAtMillis }
			?.occurrenceAtMillis
			?: return null
		val updated = repository.setWakeAlarmSkipOccurrence(alarmId, nextOccurrenceAtMillis) ?: return null
		WakeOccurrenceSkipRegistry.mark(alarmId, nextOccurrenceAtMillis)
		WakeAlarmQueueHolder.queue.discardSkippedOccurrencesForAlarm(alarmId, nextOccurrenceAtMillis)
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
		// Auto-delete one-off (FROM_NOW) alarms once all their triggers are in the past and their awake check is over
		val nowMillis = System.currentTimeMillis()
		configs
			.filter { config ->
				config.isExpiredOneOffWakeAlarm(nowMillis) && !hasAwakeCheckInProgress(context, config.id)
			}
			.forEach { config -> runCatching { repo.deleteWakeAlarm(config.id) } }
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
				!config.isSuppressedWakeOccurrence(occurrenceAtMillis) &&
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
		val ringOffsetsEditor = prefs.edit()
		prefs.all.keys
			.filter { key -> key.startsWith(KEY_RING_OFFSETS_PREFIX) }
			.filter { key ->
				val occurrenceAtMillis = key.substringAfterLast(':').toLongOrNull() ?: 0L
				occurrenceAtMillis < now.timeInMillis - RING_OFFSETS_RETAIN_MILLIS
			}
			.forEach(ringOffsetsEditor::remove)
		enabledConfigs.forEach { config ->
			val autoSilenceOverrideAllowed = config.shouldUseAutoSilenceConflictPlayback()
			val result = WakeAlarmComputer.compute(now, config, prayerDays)
			recordRingOffsets(prefs, ringOffsetsEditor, config, result.allTriggers, now.timeInMillis)
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

		ringOffsetsEditor.apply()
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

	/**
	 * Keeps the rings each coming occurrence is scheduled with, so an occurrence in progress still shows the rings it
	 * really had after the alarm is edited. An occurrence that has started keeps the rings it started with.
	 */
	private fun recordRingOffsets(
		prefs: android.content.SharedPreferences,
		editor: android.content.SharedPreferences.Editor,
		config: PrayerWakeConfig,
		triggers: List<WakeAlarmComputer.ScheduledWakeTrigger>,
		nowMillis: Long,
	) {
		val offsets = (listOf(0) + config.subAlarms.map { subAlarm -> subAlarm.signedOffsetMinutes }).distinct().sorted()
		triggers.map { trigger -> trigger.occurrenceAtMillis }.distinct().forEach { occurrenceAtMillis ->
			val key = ringOffsetsKey(config.id, occurrenceAtMillis)
			val startsAtMillis = occurrenceAtMillis + offsets.first().toMillis()
			if (!prefs.contains(key) || nowMillis < startsAtMillis) {
				editor.putString(key, offsets.joinToString(","))
			}
		}
	}

	private fun ringOffsetsKey(alarmId: String, occurrenceAtMillis: Long): String =
		"$KEY_RING_OFFSETS_PREFIX$alarmId:$occurrenceAtMillis"

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
			// Extras are alarm clocks too: OxygenOS delivered setExactAndAllowWhileIdle extras up to 75% of the time
			// left late (capped at an hour), so a "1 minute before" extra rang after the main alarm.
			alarmManager.setAlarmClock(
				AlarmManager.AlarmClockInfo(
					trigger.triggerAtMillis,
					MainActivityPendingIntents.wakeAlarmClock(context),
				),
				pendingIntent,
			)
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
