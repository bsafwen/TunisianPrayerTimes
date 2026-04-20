package com.tunisianprayertimes.wake

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri
import androidx.annotation.StringRes
import com.tunisianprayertimes.R
import com.tunisianprayertimes.RingtonePreset

data class WakeRingtoneChoice(
    val preset: RingtonePreset,
    @param:StringRes val titleResId: Int,
    @param:StringRes val summaryResId: Int,
)

object WakeRingtoneCatalog {
    val selectableChoices: List<WakeRingtoneChoice> = listOf(
        WakeRingtoneChoice(
            preset = RingtonePreset.ADHAN_ISLAM_SOBHI,
            titleResId = R.string.wake_ringtone_adhan_islam_sobhi_title,
            summaryResId = R.string.wake_ringtone_bundled_adhan_summary,
        ),
        WakeRingtoneChoice(
            preset = RingtonePreset.ADHAN_OMAR_HISHAM_AL_ARABI,
            titleResId = R.string.wake_ringtone_adhan_omar_hisham_al_arabi_title,
            summaryResId = R.string.wake_ringtone_bundled_adhan_summary,
        ),
        WakeRingtoneChoice(
            preset = RingtonePreset.ADHAN_ABDULLAH_AL_ZAILI,
            titleResId = R.string.wake_ringtone_adhan_abdullah_al_zaili_title,
            summaryResId = R.string.wake_ringtone_bundled_adhan_summary,
        ),
        WakeRingtoneChoice(
            preset = RingtonePreset.ADHAN_MADINAH_MARWAN_QASSAS,
            titleResId = R.string.wake_ringtone_adhan_madinah_marwan_qassas_title,
            summaryResId = R.string.wake_ringtone_bundled_adhan_summary,
        ),
        WakeRingtoneChoice(
            preset = RingtonePreset.ADHAN_NASSER_AL_QATAMI,
            titleResId = R.string.wake_ringtone_adhan_nasser_al_qatami_title,
            summaryResId = R.string.wake_ringtone_bundled_adhan_summary,
        ),
        WakeRingtoneChoice(
            preset = RingtonePreset.DEFAULT_ALARM,
            titleResId = R.string.wake_ringtone_default_alarm_title,
            summaryResId = R.string.wake_ringtone_default_alarm_summary,
        ),
        WakeRingtoneChoice(
            preset = RingtonePreset.DEFAULT_NOTIFICATION,
            titleResId = R.string.wake_ringtone_default_notification_title,
            summaryResId = R.string.wake_ringtone_default_notification_summary,
        ),
        WakeRingtoneChoice(
            preset = RingtonePreset.DEFAULT_RINGTONE,
            titleResId = R.string.wake_ringtone_default_ringtone_title,
            summaryResId = R.string.wake_ringtone_default_ringtone_summary,
        ),
    )

    fun titleFor(context: Context, preset: RingtonePreset, customRingtoneUri: String? = null): String {
        if (preset == RingtonePreset.CUSTOM && customRingtoneUri != null) {
            return resolveRingtoneName(context, customRingtoneUri)
                ?: context.getString(R.string.wake_ringtone_custom_title)
        }
        return context.getString(choiceFor(preset).titleResId)
    }

    fun summaryFor(context: Context, preset: RingtonePreset): String =
        if (preset == RingtonePreset.CUSTOM) {
            context.getString(R.string.wake_ringtone_custom_summary)
        } else {
            context.getString(choiceFor(preset).summaryResId)
        }

    fun resolveRingtoneName(context: Context, uriString: String): String? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val ringtone = runCatching { RingtoneManager.getRingtone(context, uri) }.getOrNull() ?: return null
        return ringtone.getTitle(context)
    }

    fun systemTypeFor(preset: RingtonePreset): Int? = when (preset) {
        RingtonePreset.DEFAULT_ALARM -> RingtoneManager.TYPE_ALARM
        RingtonePreset.DEFAULT_NOTIFICATION -> RingtoneManager.TYPE_NOTIFICATION
        RingtonePreset.DEFAULT_RINGTONE -> RingtoneManager.TYPE_RINGTONE
        else -> null
    }

    fun rawResIdFor(preset: RingtonePreset): Int? = when (preset) {
        RingtonePreset.ADHAN_ISLAM_SOBHI -> R.raw.adhan_islam_sobhi
        RingtonePreset.ADHAN_OMAR_HISHAM_AL_ARABI -> R.raw.adhan_omar_hisham_al_arabi
        RingtonePreset.ADHAN_ABDULLAH_AL_ZAILI -> R.raw.adhan_abdullah_al_zaili
        RingtonePreset.ADHAN_MADINAH_MARWAN_QASSAS -> R.raw.adhan_madinah_marwan_qassas
        RingtonePreset.ADHAN_NASSER_AL_QATAMI -> R.raw.adhan_nasser_al_qatami
        else -> null
    }

    private fun choiceFor(preset: RingtonePreset): WakeRingtoneChoice =
        selectableChoices.firstOrNull { choice -> choice.preset == preset }
            ?: selectableChoices.first()
}