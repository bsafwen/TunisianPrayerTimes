package com.tunisianprayertimes.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.ManualSilenceMode
import com.tunisianprayertimes.Prayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class PhoneSilenceHeroStateTest {

    private lateinit var context: Context

    private val nowMillis = millisAt(hour = 6, minute = 0)
    private val futureEndMillis = millisAt(hour = 7, minute = 30)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun noticeText_phoneNormalWithDndGranted_isHidden() {
        val text = phoneStatusNoticeText(
            context = context,
            isPhoneSilenced = false,
            hasDnd = true,
            silenceReason = null,
        )

        assertNull(text)
    }

    @Test
    fun noticeText_missingDndPermission_isShownEvenWhenPhoneIsNotSilent() {
        val text = phoneStatusNoticeText(
            context = context,
            isPhoneSilenced = false,
            hasDnd = false,
            silenceReason = null,
        )

        assertEquals("إذن عدم الإزعاج مطلوب", text)
    }

    @Test
    fun noticeText_missingDndPermission_takesPriorityOverSilenceReason() {
        val text = phoneStatusNoticeText(
            context = context,
            isPhoneSilenced = true,
            hasDnd = false,
            silenceReason = PhoneSilenceReason(
                source = PhoneSilenceReasonSource.AUTO_PRAYER,
                prayer = Prayer.FAJR,
            ),
        )

        assertEquals("إذن عدم الإزعاج مطلوب", text)
    }

    @Test
    fun noticeText_silentWithoutKnownReason_usesGenericSilentText() {
        val text = phoneStatusNoticeText(
            context = context,
            isPhoneSilenced = true,
            hasDnd = true,
            silenceReason = null,
        )

        assertEquals("الهاتف صامت", text)
    }

    @Test
    fun noticeText_silentWithReason_usesSpecificReasonText() {
        val text = phoneStatusNoticeText(
            context = context,
            isPhoneSilenced = true,
            hasDnd = true,
            silenceReason = PhoneSilenceReason(
                source = PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER,
                prayer = Prayer.DHUHR,
            ),
        )

        assertEquals("الإسكات اليدوي حتى صلاة الظهر", text)
    }

    @Test
    fun resolver_phoneNotSilent_returnsNullEvenWhenFlagsAreStale() {
        val reason = resolveReason(
            isPhoneSilenced = false,
            autoSilenceActive = true,
            activeAutoSilencePrayer = Prayer.FAJR,
            manualSilenceActive = true,
            manualSilenceMode = ManualSilenceMode.UNTIL_PRAYER,
            manualTargetPrayer = Prayer.DHUHR,
            manualSilenceEndsAtMillis = futureEndMillis,
            wakeSilenceUntilAlarmActive = true,
        )

        assertNull(reason)
    }

    @Test
    fun resolver_manualUntilStopped_hasManualStoppedReason() {
        val reason = resolveReason(
            manualSilenceActive = true,
            manualSilenceMode = ManualSilenceMode.UNTIL_STOPPED,
        )

        assertEquals(PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED), reason)
    }

    @Test
    fun resolver_manualUntilStopped_ignoresStaleFutureEndTime() {
        val reason = resolveReason(
            manualSilenceActive = true,
            manualSilenceMode = ManualSilenceMode.UNTIL_STOPPED,
            manualSilenceEndsAtMillis = futureEndMillis,
        )

        assertEquals(PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED), reason)
    }

    @Test
    fun resolver_manualDurationWithFutureEnd_hasUntilTimeReason() {
        val reason = resolveReason(
            manualSilenceActive = true,
            manualSilenceMode = ManualSilenceMode.DURATION,
            manualSilenceEndsAtMillis = futureEndMillis,
        )

        assertEquals(
            PhoneSilenceReason(
                source = PhoneSilenceReasonSource.MANUAL_UNTIL_TIME,
                endsAtMillis = futureEndMillis,
            ),
            reason,
        )
    }

    @Test
    fun resolver_manualDurationWithExpiredEnd_fallsBackToManualStoppedReason() {
        val reason = resolveReason(
            manualSilenceActive = true,
            manualSilenceMode = ManualSilenceMode.DURATION,
            manualSilenceEndsAtMillis = nowMillis - 1,
        )

        assertEquals(PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED), reason)
    }

    @Test
    fun resolver_manualUntilPrayer_hasTargetPrayerReason() {
        val reason = resolveReason(
            manualSilenceActive = true,
            manualSilenceMode = ManualSilenceMode.UNTIL_PRAYER,
            manualTargetPrayer = Prayer.ASR,
            manualSilenceEndsAtMillis = futureEndMillis,
        )

        assertEquals(
            PhoneSilenceReason(
                source = PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER,
                prayer = Prayer.ASR,
            ),
            reason,
        )
    }

    @Test
    fun resolver_manualSilence_takesPriorityOverAutoAndWakeReasons() {
        val reason = resolveReason(
            autoSilenceActive = true,
            activeAutoSilencePrayer = Prayer.FAJR,
            manualSilenceActive = true,
            manualSilenceMode = ManualSilenceMode.UNTIL_PRAYER,
            manualTargetPrayer = Prayer.MAGHRIB,
            wakeSilenceUntilAlarmActive = true,
        )

        assertEquals(
            PhoneSilenceReason(
                source = PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER,
                prayer = Prayer.MAGHRIB,
            ),
            reason,
        )
    }

    @Test
    fun resolver_autoSilenceWithKnownPrayer_hasAutoPrayerReason() {
        val reason = resolveReason(
            autoSilenceActive = true,
            activeAutoSilencePrayer = Prayer.ISHA,
        )

        assertEquals(
            PhoneSilenceReason(
                source = PhoneSilenceReasonSource.AUTO_PRAYER,
                prayer = Prayer.ISHA,
            ),
            reason,
        )
    }

    @Test
    fun resolver_autoSilenceWithoutKnownPrayer_hasGenericAutoReason() {
        val reason = resolveReason(
            autoSilenceActive = true,
            activeAutoSilencePrayer = null,
        )

        assertEquals(PhoneSilenceReason(PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN), reason)
    }

    @Test
    fun resolver_autoSilence_takesPriorityOverWakeReason() {
        val reason = resolveReason(
            autoSilenceActive = true,
            activeAutoSilencePrayer = Prayer.FAJR,
            wakeSilenceUntilAlarmActive = true,
        )

        assertEquals(
            PhoneSilenceReason(
                source = PhoneSilenceReasonSource.AUTO_PRAYER,
                prayer = Prayer.FAJR,
            ),
            reason,
        )
    }

    @Test
    fun resolver_wakeSilence_hasWakeAlarmReason() {
        val reason = resolveReason(wakeSilenceUntilAlarmActive = true)

        assertEquals(PhoneSilenceReason(PhoneSilenceReasonSource.WAKE_ALARM), reason)
    }

    @Test
    fun resolver_phoneSilentWithoutAppFlags_hasExternalReason() {
        val reason = resolveReason()

        assertEquals(PhoneSilenceReason(PhoneSilenceReasonSource.EXTERNAL), reason)
    }

    @Test
    fun reasonText_formatsEveryHeroReason() {
        assertEquals(
            "الإسكات مفعل لصلاة الفجر",
            phoneSilenceReasonText(
                context,
                PhoneSilenceReason(
                    source = PhoneSilenceReasonSource.AUTO_PRAYER,
                    prayer = Prayer.FAJR,
                ),
            ),
        )
        assertEquals(
            "الإسكات مفعل لوقت الصلاة",
            phoneSilenceReasonText(context, PhoneSilenceReason(PhoneSilenceReasonSource.AUTO_PRAYER_UNKNOWN)),
        )
        assertEquals(
            "الإسكات اليدوي مفعل",
            phoneSilenceReasonText(context, PhoneSilenceReason(PhoneSilenceReasonSource.MANUAL_UNTIL_STOPPED)),
        )
        assertEquals(
            "الإسكات اليدوي حتى 07:30",
            phoneSilenceReasonText(
                context,
                PhoneSilenceReason(
                    source = PhoneSilenceReasonSource.MANUAL_UNTIL_TIME,
                    endsAtMillis = futureEndMillis,
                ),
            ),
        )
        assertEquals(
            "الإسكات اليدوي حتى صلاة الظهر",
            phoneSilenceReasonText(
                context,
                PhoneSilenceReason(
                    source = PhoneSilenceReasonSource.MANUAL_UNTIL_PRAYER,
                    prayer = Prayer.DHUHR,
                ),
            ),
        )
        assertEquals(
            "الإسكات مفعل حتى منبه الاستيقاظ",
            phoneSilenceReasonText(context, PhoneSilenceReason(PhoneSilenceReasonSource.WAKE_ALARM)),
        )
        assertEquals(
            "الهاتف صامت من إعدادات الهاتف",
            phoneSilenceReasonText(context, PhoneSilenceReason(PhoneSilenceReasonSource.EXTERNAL)),
        )
    }

    private fun resolveReason(
        isPhoneSilenced: Boolean = true,
        autoSilenceActive: Boolean = false,
        activeAutoSilencePrayer: Prayer? = null,
        manualSilenceActive: Boolean = false,
        manualSilenceMode: ManualSilenceMode = ManualSilenceMode.UNTIL_STOPPED,
        manualTargetPrayer: Prayer = Prayer.FAJR,
        manualSilenceEndsAtMillis: Long = -1L,
        wakeSilenceUntilAlarmActive: Boolean = false,
    ): PhoneSilenceReason? = resolvePhoneSilenceReason(
        isPhoneSilenced = isPhoneSilenced,
        autoSilenceActive = autoSilenceActive,
        activeAutoSilencePrayer = activeAutoSilencePrayer,
        manualSilenceActive = manualSilenceActive,
        manualSilenceMode = manualSilenceMode,
        manualTargetPrayer = manualTargetPrayer,
        manualSilenceEndsAtMillis = manualSilenceEndsAtMillis,
        wakeSilenceUntilAlarmActive = wakeSilenceUntilAlarmActive,
        currentTimeMillis = nowMillis,
    )

    private fun millisAt(hour: Int, minute: Int): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2026)
        set(Calendar.MONTH, Calendar.MAY)
        set(Calendar.DAY_OF_MONTH, 27)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}