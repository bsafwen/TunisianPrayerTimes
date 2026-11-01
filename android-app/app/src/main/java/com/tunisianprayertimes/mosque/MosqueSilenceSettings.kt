package com.tunisianprayertimes.mosque

import android.content.Context
import android.os.Build
import com.tunisianprayertimes.mosque.presence.AutomaticSilenceMode

object MosqueSilenceSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("mosque_silence_settings", Context.MODE_PRIVATE)

    fun mode(context: Context): AutomaticSilenceMode? {
        val value = prefs(context).getString("mode", null) ?: return AutomaticSilenceMode.TIME_ONLY
        return AutomaticSilenceMode.entries.firstOrNull { it.name == value }
    }

    fun combined(context: Context) = mode(context) == AutomaticSilenceMode.TIME_AND_MOSQUE
    fun usesOwnedRules(context: Context) = Build.VERSION.SDK_INT >= 29 && prefs(context).getBoolean("owned_rules", false)
    fun backgroundDisclosureAccepted(context: Context) = prefs(context).getBoolean("background_disclosure", false)
    fun acceptBackgroundDisclosure(context: Context) = prefs(context).edit().putBoolean("background_disclosure", true).apply()

    internal fun enroll(context: Context) = prefs(context).edit().putBoolean("owned_rules", true).commit()
    internal fun saveMode(context: Context, mode: AutomaticSilenceMode) =
        prefs(context).edit().putString("mode", mode.name).commit()
}
