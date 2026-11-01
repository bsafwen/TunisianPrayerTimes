package com.tunisianprayertimes.mosque

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.PrayerTimesRepository
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ScheduleRefreshCoordinator
import com.tunisianprayertimes.mosque.presence.*
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** Also the system Modes configuration activity. Opening it never activates an unrequested rule. */
class MosqueSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TunisianPrayerTimesTheme {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                Surface(Modifier.fillMaxSize()) { MosqueSettingsScreen(onBack = ::finish) }
            }
        } }
    }
}

@Composable
private fun MosqueSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    var mode by remember { mutableStateOf(MosqueSilenceSettings.mode(context)) }
    var areas by remember { mutableStateOf(PrivateSilenceAreaStore.read(context)) }
    var issues by remember { mutableStateOf(emptyList<Int>()) }
    var state by remember { mutableStateOf<PresenceState?>(null) }
    var inWindow by remember { mutableStateOf(false) }
    var releasePending by remember { mutableStateOf(false) }
    var phoneSilent by remember { mutableStateOf(false) }
    var warmed by remember { mutableStateOf(false) }
    var disclosure by remember { mutableStateOf(false) }
    var exportPreview by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<SavedSilenceArea?>(null) }
    var editor by remember { mutableStateOf<AreaDraft?>(null) }
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/geo+json")) { uri ->
        val json = exportPreview
        if (uri != null && json != null) scope.launch(Dispatchers.IO) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) } != null }.getOrDefault(false)
            withContext(Dispatchers.Main) { Toast.makeText(context, if (ok) R.string.mosque_export_done else R.string.mosque_save_failed, Toast.LENGTH_LONG).show() }
        }
        exportPreview = null
    }
    fun settings(action: String, packageUri: Boolean = false) {
        runCatching { context.startActivity(Intent(action).apply { if (packageUri) data = Uri.parse("package:${context.packageName}") }) }
            .onFailure { context.startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
    fun reschedule(start: Boolean = false) {
        scope.launch {
            withContext(ScheduleRefreshCoordinator.scheduling) { ScheduleRefreshCoordinator.syncSilence(context) }
            if (start && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) MosqueSilenceMonitor.reconcile(context, canStart = true)
            refresh++
        }
    }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { PrayerTimesRepository.warm(context.applicationContext) }
        warmed = true
    }
    LaunchedEffect(refresh, warmed, lifecycle) {
        if (!warmed) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Only a visible activity resumes acquisition; a lost visit still needs the explicit button.
            MosqueSilenceMonitor.reconcile(context, canStart = true)
            while (true) {
                val snapshot = withContext(Dispatchers.Default) {
                    val currentMode = MosqueSilenceSettings.mode(context)
                    Triple(PrivateSilenceAreaStore.read(context),
                        if (currentMode == AutomaticSilenceMode.TIME_ONLY) MosqueReadiness.timeIssues(context) else MosqueReadiness.issues(context),
                        MosqueSilenceMonitor.status(context))
                }
                areas = snapshot.first; issues = snapshot.second; state = snapshot.third
                mode = MosqueSilenceSettings.mode(context)
                inWindow = MosqueSilenceMonitor.hasWindow(context)
                releasePending = SilenceCoordinator.releasePending(context)
                phoneSilent = com.tunisianprayertimes.SilenceStatus.isPhoneSilenced(context)
                delay(2_000)
            }
        }
    }
    val draft = editor
    if (draft != null) {
        AreaEditor(draft, onClose = { editor = null; refresh++ })
        return
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.mosque_back)) }
        Text(stringResource(R.string.mosque_settings_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.mosque_mode_explanation))
        AutomaticSilenceMode.entries.forEach { option ->
            Row(Modifier.fillMaxWidth().selectable(selected = mode == option, role = Role.RadioButton) {
                if (option == AutomaticSilenceMode.TIME_AND_MOSQUE && !MosqueSilenceSettings.usesOwnedRules(context) && !SilenceCoordinator.enrollOrRepair(context)) {
                    Toast.makeText(context, R.string.mosque_enroll_instruction, Toast.LENGTH_LONG).show()
                } else if (SilenceCoordinator.switchMode(context, option)) {
                    mode = option; reschedule(start = option == AutomaticSilenceMode.TIME_AND_MOSQUE)
                }
            }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(selected = mode == option, onClick = null)
                Text(stringResource(if (option == AutomaticSilenceMode.TIME_ONLY) R.string.mosque_mode_time else R.string.mosque_mode_combined),
                    Modifier.padding(top = 12.dp))
            }
        }
        if (mode == null) Text(stringResource(R.string.mosque_invalid_mode), color = MaterialTheme.colorScheme.error)
        if (!PrefsManager.isEnabled(context)) {
            Text(stringResource(R.string.mosque_master_off))
            Button(onClick = { PrefsManager.setEnabled(context, true); reschedule(start = true) }) { Text(stringResource(R.string.mosque_enable_auto)) }
        }
        HorizontalDivider()
        Text(stringResource(R.string.mosque_readiness_title), style = MaterialTheme.typography.titleMedium)
        if (issues.isEmpty()) Text(stringResource(if (mode == AutomaticSilenceMode.TIME_ONLY) R.string.mosque_status_time_only else R.string.mosque_ready)) else issues.forEach { Text("• " + stringResource(it)) }
        Text(stringResource(R.string.mosque_enroll_instruction), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            if (SilenceCoordinator.enrollOrRepair(context)) { MosqueSilenceMonitor.environmentChanged(context); reschedule(start = mode == AutomaticSilenceMode.TIME_AND_MOSQUE) }
            else Toast.makeText(context, R.string.mosque_enroll_instruction, Toast.LENGTH_LONG).show()
        }) { Text(stringResource(R.string.mosque_prepare_rules)) }
        TextButton(onClick = { settings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }) { Text(stringResource(R.string.mosque_allow_dnd)) }
        if (Build.VERSION.SDK_INT >= 29) {
            TextButton(onClick = { foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                Text(stringResource(R.string.mosque_allow_precise))
            }
            TextButton(onClick = { disclosure = true }) { Text(stringResource(R.string.mosque_allow_background)) }
        }
        if (Build.VERSION.SDK_INT >= 31) TextButton(onClick = { settings(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true) }) { Text(stringResource(R.string.mosque_allow_exact)) }
        if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = { foreground.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }) { Text(stringResource(R.string.mosque_allow_notifications)) }
        TextButton(onClick = { settings(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text(stringResource(R.string.mosque_location_settings)) }
        TextButton(onClick = { settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) { Text(stringResource(R.string.mosque_app_settings)) }
        Text(stringResource(R.string.mosque_provider_note), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        val statusText = when {
            mode != AutomaticSilenceMode.TIME_AND_MOSQUE -> R.string.mosque_status_time_only
            issues.isNotEmpty() -> R.string.mosque_status_setup
            !inWindow -> R.string.mosque_status_waiting
            state == PresenceState.VISIT_CONFIRMED -> R.string.mosque_status_active
            state == PresenceState.VISIT_UNCERTAIN || state == PresenceState.EXITING -> R.string.mosque_status_uncertain
            state == PresenceState.INACTIVE_NEEDS_REARM || state == PresenceState.SUPPRESSED -> R.string.mosque_status_rearm
            state == PresenceState.ACQUIRING -> R.string.mosque_status_checking
            else -> R.string.mosque_status_no_match
        }
        Text(stringResource(statusText), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (phoneSilent) R.string.mosque_phone_silent else R.string.mosque_phone_not_silent))
        if (releasePending) Text(stringResource(R.string.mosque_release_pending), color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.mosque_status_other_dnd), style = MaterialTheme.typography.bodySmall)
        Button(enabled = warmed && inWindow && issues.isEmpty(), onClick = {
            MosqueSilenceMonitor.reconcile(context, canStart = true, deliberate = true); refresh++
        }) { Text(stringResource(R.string.mosque_recheck)) }
        OutlinedButton(enabled = inWindow, onClick = { MosqueSilenceMonitor.skip(context); refresh++ }) { Text(stringResource(R.string.mosque_skip_window)) }
        HorizontalDivider()
        Text(stringResource(R.string.mosque_areas_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.mosque_catalog_later))
        if (!areas.available) Text(stringResource(R.string.mosque_store_unavailable), color = MaterialTheme.colorScheme.error)
        if (areas.rejected > 0) Text(stringResource(R.string.mosque_invalid_records, areas.rejected.toString()))
        areas.areas.forEach { area ->
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(area.name, Modifier.weight(1f))
                    Switch(area.geometry.enabled, modifier = Modifier.semantics { contentDescription = area.name }, onCheckedChange = { enabled ->
                        runCatching { PrivateSilenceAreaStore.setEnabled(context, area.geometry.id, enabled) }; refresh++
                    })
                }
                Row {
                    TextButton(onClick = {
                        val polygon = area.geometry.polygons.singleOrNull()
                        if (polygon != null && polygon.holes.isEmpty()) editor = AreaDraft(area.geometry.id, area.name, coordinateText(polygon.exterior))
                        else Toast.makeText(context, R.string.mosque_complex_read_only, Toast.LENGTH_LONG).show()
                    }) { Text(stringResource(R.string.mosque_edit)) }
                    TextButton(onClick = { deleting = area }) { Text(stringResource(R.string.mosque_delete)) }
                }
            } }
        }
        Button(enabled = areas.available, onClick = { editor = AreaDraft(null, "", "") }) { Text(stringResource(R.string.mosque_add_area)) }
        val savedDraft = PrivateSilenceAreaStore.readDraft(context)
        if (savedDraft != null) TextButton(onClick = {
            editor = AreaDraft(savedDraft.optString("id").takeUnless { it == "null" || it.isBlank() },
                savedDraft.optString("name"), savedDraft.optString("coordinates"))
        }) { Text(stringResource(R.string.mosque_restore_draft)) }
        OutlinedButton(enabled = areas.available && areas.areas.isNotEmpty(), onClick = { exportPreview = PrivateSilenceAreaStore.exportGeoJson(context) }) {
            Text(stringResource(R.string.mosque_export))
        }
        Text(stringResource(R.string.mosque_privacy), style = MaterialTheme.typography.bodySmall)
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text(stringResource(R.string.mosque_disclosure_title)) },
        text = { Text(stringResource(R.string.mosque_background_disclosure)) },
        confirmButton = { TextButton(onClick = {
            disclosure = false
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!fine) foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else {
                MosqueSilenceSettings.acceptBackgroundDisclosure(context)
                if (Build.VERSION.SDK_INT == 29) background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else settings(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
                refresh++
            }
        }) { Text(stringResource(R.string.mosque_continue)) } }, dismissButton = { TextButton(onClick = { disclosure = false }) { Text(stringResource(R.string.mosque_cancel)) } })
    deleting?.let { area -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.mosque_delete)) },
        text = { Text(stringResource(R.string.mosque_delete_question, area.name)) },
        confirmButton = { TextButton(onClick = { PrivateSilenceAreaStore.delete(context, area.geometry.id); deleting = null; refresh++ }) { Text(stringResource(R.string.mosque_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.mosque_cancel)) } }) }
    exportPreview?.let { json -> AlertDialog(onDismissRequest = { exportPreview = null }, title = { Text(stringResource(R.string.mosque_export)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.mosque_export_warning)); Text(json, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
        } }, confirmButton = { TextButton(onClick = { export.launch("my-mosque-areas.geojson") }) { Text(stringResource(R.string.mosque_export_save)) } },
        dismissButton = { TextButton(onClick = { exportPreview = null }) { Text(stringResource(R.string.mosque_cancel)) } }) }
}

private data class AreaDraft(val id: String?, val name: String, val coordinates: String)
private fun coordinateText(points: List<GeoPoint>) = points.joinToString("\n") { String.format(Locale.US, "%.7f, %.7f", it.latitude, it.longitude) }
private fun parseCoordinates(value: String): List<GeoPoint> = value.lineSequence().filter { it.isNotBlank() }.map { line ->
    val parts = line.trim().split(Regex("[,;\\s]+")); require(parts.size == 2)
    GeoPoint(parts[0].toDouble(), parts[1].toDouble())
}.toList().also { require(it.size in 3..256); SilencePolygon(it) }

@Composable
private fun AreaEditor(initial: AreaDraft, onClose: () -> Unit) {
    val context = LocalContext.current
    BackHandler { onClose() } // Keep the recoverable draft; Cancel explicitly discards it.
    var name by remember { mutableStateOf(initial.name) }
    var text by remember { mutableStateOf(initial.coordinates) }
    var undo by remember { mutableStateOf(emptyList<String>()) }
    var rectangle by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val vertices = remember(text) { runCatching { parseCoordinates(text) }.getOrNull() }
    fun update(value: String) {
        undo = (undo + text).takeLast(30); text = value.take(32_000)
        PrivateSilenceAreaStore.saveDraft(context, name, text, initial.id)
    }
    fun save(enabled: Boolean) {
        val points = vertices
        if (points == null || name.isBlank()) { error = true; return }
        runCatching { PrivateSilenceAreaStore.save(context, name, points, initial.id, enabled) }
            .onSuccess { onClose() }.onFailure { error = true }
    }
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.mosque_editor_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.mosque_editor_help))
        OutlinedTextField(name, onValueChange = { name = it.take(100); PrivateSilenceAreaStore.saveDraft(context, name, text, initial.id) },
            label = { Text(stringResource(R.string.mosque_area_name)) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedTextField(text, onValueChange = ::update, label = { Text(stringResource(R.string.mosque_coordinates)) },
            modifier = Modifier.fillMaxWidth(), minLines = 5, maxLines = 10, textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr))
        Row {
            TextButton(onClick = { rectangle = true }) { Text(stringResource(R.string.mosque_rectangle)) }
            TextButton(enabled = undo.isNotEmpty(), onClick = {
                text = undo.last(); undo = undo.dropLast(1); PrivateSilenceAreaStore.saveDraft(context, name, text, initial.id)
            }) { Text(stringResource(R.string.mosque_undo)) }
        }
        val preview = vertices
        if (preview != null) PolygonPreview(preview)
        if (error || text.isNotBlank() && preview == null) Text(stringResource(R.string.mosque_geometry_error), color = MaterialTheme.colorScheme.error)
        Button(onClick = { save(true) }) { Text(stringResource(R.string.mosque_save_enable)) }
        OutlinedButton(onClick = { save(false) }) { Text(stringResource(R.string.mosque_save_disabled)) }
        TextButton(onClick = { PrivateSilenceAreaStore.clearDraft(context); onClose() }) { Text(stringResource(R.string.mosque_cancel)) }
    }
    if (rectangle) {
        var corners by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { rectangle = false }, title = { Text(stringResource(R.string.mosque_rectangle)) },
            text = { Column { Text(stringResource(R.string.mosque_rectangle_help)); OutlinedTextField(corners, { corners = it }, minLines = 2,
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)) } },
            confirmButton = { TextButton(onClick = {
                runCatching {
                    val p = corners.lineSequence().filter { it.isNotBlank() }.map { line -> line.trim().split(Regex("[,;\\s]+"))
                        .also { require(it.size == 2) }.let { GeoPoint(it[0].toDouble(), it[1].toDouble()) } }.toList()
                    require(p.size == 2 && p.all { it.isValid() })
                    val west = p.minOf { it.longitude }; val east = p.maxOf { it.longitude }
                    val south = p.minOf { it.latitude }; val north = p.maxOf { it.latitude }
                    val points = listOf(GeoPoint(south, west), GeoPoint(south, east), GeoPoint(north, east), GeoPoint(north, west))
                    SilencePolygon(points); update(coordinateText(points)); rectangle = false
                }.onFailure { error = true; rectangle = false }
            }) { Text(stringResource(R.string.mosque_continue)) } }, dismissButton = { TextButton(onClick = { rectangle = false }) { Text(stringResource(R.string.mosque_cancel)) } })
    }
}

@Composable
private fun PolygonPreview(points: List<GeoPoint>) {
    val description = stringResource(R.string.mosque_preview_description, points.size.toString())
    Canvas(Modifier.fillMaxWidth().height(220.dp).semantics { contentDescription = description }) {
        val minX = points.minOf { it.longitude }; val maxX = points.maxOf { it.longitude }
        val minY = points.minOf { it.latitude }; val maxY = points.maxOf { it.latitude }
        val scale = minOf((size.width - 32) / (maxX - minX).coerceAtLeast(0.0000001).toFloat(),
            (size.height - 32) / (maxY - minY).coerceAtLeast(0.0000001).toFloat())
        val offsets = points.map { Offset(16 + ((it.longitude - minX) * scale).toFloat(), size.height - 16 - ((it.latitude - minY) * scale).toFloat()) }
        val path = Path().apply { offsets.forEachIndexed { index, point -> if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y) }; close() }
        drawPath(path, Color(0x33427A55)); drawPath(path, Color(0xFF427A55), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
        offsets.forEach { drawCircle(Color(0xFF234F36), 5f, it) }
    }
}
