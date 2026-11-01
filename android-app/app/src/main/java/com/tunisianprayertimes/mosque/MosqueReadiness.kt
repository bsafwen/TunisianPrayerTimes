package com.tunisianprayertimes.mosque

import android.Manifest
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.tunisianprayertimes.R

object MosqueReadiness {
    fun timeIssues(context: Context): List<Int> = buildList {
        if (!context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) add(R.string.mosque_needs_dnd)
        if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) add(R.string.mosque_needs_exact)
        if (MosqueSilenceSettings.usesOwnedRules(context) && SilenceOwner.entries.any { !SilenceCoordinator.ruleReady(context, it) }) add(R.string.mosque_needs_rules)
    }
    fun issues(context: Context): List<Int> = buildList {
        if (Build.VERSION.SDK_INT < 29) add(R.string.mosque_needs_android)
        if (!MosqueSilenceSettings.usesOwnedRules(context) || !SilenceCoordinator.ruleReady(context, SilenceOwner.MOSQUE)) add(R.string.mosque_needs_rules)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) add(R.string.mosque_needs_precise)
        if (Build.VERSION.SDK_INT >= 29 && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) add(R.string.mosque_needs_background)
        if (!MosqueSilenceSettings.backgroundDisclosureAccepted(context)) add(R.string.mosque_needs_disclosure)
        val locations = context.getSystemService(LocationManager::class.java)
        val enabled = if (Build.VERSION.SDK_INT >= 28) locations.isLocationEnabled else
            locations.isProviderEnabled(LocationManager.GPS_PROVIDER) || locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!enabled) add(R.string.mosque_needs_location)
        if (!context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) add(R.string.mosque_needs_dnd)
        if (Build.VERSION.SDK_INT >= 31 && !context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) add(R.string.mosque_needs_exact)
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) add(R.string.mosque_needs_notifications)
        if (Build.VERSION.SDK_INT >= 28 && context.getSystemService(ActivityManager::class.java).isBackgroundRestricted) add(R.string.mosque_background_restricted)
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) != ConnectionResult.SUCCESS) add(R.string.mosque_needs_play_services)
        val store = PrivateSilenceAreaStore.read(context)
        if (!store.available) add(R.string.mosque_store_unavailable)
        else if (store.areas.none { it.geometry.enabled }) add(R.string.mosque_no_areas)
        else if (store.areas.filter { it.geometry.enabled }.sumOf { it.geometry.polygons.size } > 100) add(R.string.mosque_too_many_areas)
    }
    fun ready(context: Context) = issues(context).isEmpty()
}
