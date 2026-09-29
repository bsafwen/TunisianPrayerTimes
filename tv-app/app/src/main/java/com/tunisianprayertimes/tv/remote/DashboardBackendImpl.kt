package com.tunisianprayertimes.tv.remote

import android.content.Context
import androidx.core.content.pm.PackageInfoCompat
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.EventDate
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.IslamicDays
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.FlowState
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.DateEvent
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.tv.data.LocalMediaManager
import com.tunisianprayertimes.tv.data.MediaKind
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.update.AppUpdater
import com.tunisianprayertimes.tv.usb.UsbMedia
import com.tunisianprayertimes.tv.usb.UsbSettingsFound
import com.tunisianprayertimes.tv.usb.UsbSettingsInbox
import com.tunisianprayertimes.tv.ui.theme.ThemeRegistry
import com.tunisianprayertimes.weather.CachedWeather
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What the screen shows at a moment, captured by the display for the dashboard (read from the server's threads). */
data class DashboardLive(
    val now: LocalDateTime,
    val clockTrusted: Boolean,
    val times: DayPrayerTimes?,
    val iqamahTimes: Map<Prayer, LocalTime>,
    val hijriLabel: String,
    val banner: String?,
    val flow: FlowState,
    val weather: CachedWeather?,
    /** What the wall shows when it is not the timetable nor the prayer: "NIGHT", "EID", "ANNOUNCEMENTS"; null otherwise. */
    val screen: String? = null,
)

/**
 * The dashboard's view of the TV and the actions it may take. Settings changes go through
 * [inbox] exactly like a USB file (checked, previewed, applied whole, with an undo snapshot);
 * images through the same checks as images from a key.
 */
class DashboardBackendImpl(
    private val context: Context,
    private val prefs: PrefsManager,
    private val inbox: UsbSettingsInbox,
    private val media: LocalMediaManager,
    private val gouvernorats: List<Gouvernorat>,
    private val undoFile: File,
    private val updater: AppUpdater,
    private val flavor: String,
    private val live: () -> DashboardLive?,
    private val kioskRows: () -> List<HealthRow>,
    private val onSettingsChanged: () -> Unit,
    private val onMediaChanged: () -> Unit,
) : DashboardBackend {

    override fun stateJson(): String {
        val live = live()
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return buildJsonObject {
            putJsonObject("app") {
                put("versionName", info?.versionName.orEmpty())
                put("versionCode", info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L)
                put("flavor", flavor)
                put("packageName", context.packageName)
            }
            putJsonObject("clock") {
                put("now", live?.now?.withNano(0)?.toString().orEmpty())
                put("trusted", live?.clockTrusted ?: true)
            }
            putJsonObject("mosque") {
                put("name", prefs.mosqueName)
                put("delegationId", prefs.delegationId)
                put("delegationName", prefs.delegationName)
                put("gouvernoratId", prefs.gouvernoratId)
                put("themeId", prefs.themeId)
            }
            putJsonArray("themes") {
                ThemeRegistry.builtInThemes.forEach { theme ->
                    addJsonObject {
                        put("id", theme.id)
                        put("name", theme.nameAr)
                        put("description", theme.description)
                    }
                }
            }
            put("today", today(live))
            put("flow", flow(live))
            put("settingsFile", inbox.currentFile())
            put("islamicDates", islamicDates(live))
            putJsonObject("images") {
                MediaKind.entries.forEach { kind ->
                    putJsonArray(kind.folder) {
                        media.images(kind).forEach { file -> addJsonObject { put("name", file.name); put("size", file.length()) } }
                    }
                }
            }
            putJsonArray("textFiles") {
                media.textFiles().forEach { file -> addJsonObject { put("name", file.name); put("text", file.text) } }
            }
            putJsonArray("kiosk") {
                runCatching(kioskRows).getOrDefault(emptyList()).forEach { row ->
                    addJsonObject {
                        put("level", row.level.name)
                        put("text", row.text)
                        put("fix", row.fix)
                        put("command", row.command)
                    }
                }
            }
            putJsonObject("update") {
                val status = updater.status
                put("supported", status.supported)
                put("available", status.available)
                put("status", status.message)
                put("needsPermission", status.needsPermission)
            }
            putJsonObject("weather") {
                put("enabled", prefs.weatherEnabled)
                val weather = live?.weather?.takeIf { prefs.weatherEnabled && it.isFresh(System.currentTimeMillis()) }
                put("text", weather?.weather?.line())
                put("updated", weather?.let { hm(Instant.ofEpochMilli(it.fetchedAtMillis).atZone(ZoneId.of("Africa/Tunis")).toLocalTime()) })
            }
            put("canUndo", undoFile.isFile)
        }.toString()
    }

    private fun today(live: DashboardLive?): JsonObject = buildJsonObject {
        val times = live?.times
        put("date", live?.now?.toLocalDate()?.toString())
        put("hijri", live?.hijriLabel)
        put("sunrise", times?.let { "%02d:%02d".format(Locale.ROOT, it.shurukHour, it.shurukMinute) })
        put("banner", live?.banner)
        putJsonArray("prayers") {
            if (live == null || times == null) return@putJsonArray
            val friday = live.now.dayOfWeek == java.time.DayOfWeek.FRIDAY
            listOf(
                (if (friday) Prayer.JOMOAA else Prayer.DHUHR) to times.dhuhr,
                Prayer.ASR to times.asr,
                Prayer.MAGHRIB to times.maghrib,
                Prayer.ISHA to times.isha,
            ).let { listOf(Prayer.FAJR to times.fajr) + it }.forEach { (prayer, time) ->
                addJsonObject {
                    put("id", prayer.name)
                    put("name", TvStrings.prayerName(prayer))
                    put("adhan", "%02d:%02d".format(Locale.ROOT, time.hour, time.minute))
                    put("iqamah", live.iqamahTimes[prayer]?.let(::hm))
                }
            }
            // An Eid prayer today (timed from sunrise).
            live.iqamahTimes.filterKeys { it == Prayer.AID_FITR || it == Prayer.AID_ADHA }.forEach { (prayer, iqamah) ->
                addJsonObject {
                    put("id", prayer.name)
                    put("name", TvStrings.prayerName(prayer))
                    put("adhan", null as String?)
                    put("iqamah", hm(iqamah))
                }
            }
        }
    }

    private fun flow(live: DashboardLive?): JsonObject = buildJsonObject {
        val flow = live?.flow
        put("phase", flow?.phase?.name ?: "IDLE")
        put("prayer", flow?.event?.prayer?.let(TvStrings::prayerName))
        put("until", flow?.phaseEndsAt?.toLocalTime()?.let(::hm))
        live?.screen?.let { put("screen", it) }
        // The Eid prayer's wait has no adhan and no iqamah: the page says so.
        if (flow?.event?.prayer in com.tunisianprayertimes.mosque.MosqueSchedule.EID) put("eid", true)
    }

    private fun islamicDates(live: DashboardLive?): JsonObject = runCatching {
        val today = live?.now?.toLocalDate() ?: java.time.LocalDate.now(ZoneId.of("Africa/Tunis"))
        val year = IslamicDays.upcomingYear(today)
        val dates = IslamicDays.yearDates(year)
        buildJsonObject {
            put("hijriYear", year)
            putJsonArray("events") {
                listOf(
                    Triple("ramadanStart", DateEvent.RAMADAN_START, dates.ramadanStart),
                    Triple("eidFitr", DateEvent.EID_FITR, dates.eidFitr),
                    Triple("eidAdha", DateEvent.EID_ADHA, dates.eidAdha),
                ).forEach { (id, event, date) -> add(eventJson(id, year, event, date)) }
            }
        }
    }.getOrDefault(buildJsonObject { put("events", JsonArray(emptyList())) })

    private fun eventJson(id: String, year: Int, event: DateEvent, date: EventDate): JsonObject = buildJsonObject {
        val range = MosqueSettingsFile.acceptedDates(year, event)
        put("id", id)
        put("name", MosqueSettingsFile.dateEventName(event))
        put("date", date.date.toString())
        put("source", date.source.name)
        put("automatic", date.withoutManual.toString())
        put("min", range.start.toString())
        put("max", range.endInclusive.toString())
    }

    override fun placesJson(): String = buildJsonArray {
        gouvernorats.forEach { gouvernorat ->
            addJsonObject {
                put("id", gouvernorat.id)
                put("name", gouvernorat.nomAr)
                putJsonArray("delegations") {
                    gouvernorat.delegations.forEach { delegation -> addJsonObject { put("id", delegation.id); put("name", delegation.nomAr) } }
                }
            }
        }
    }.toString()

    private fun found(text: String) = UsbSettingsFound(File("dashboard"), text, "dashboard")

    override fun preview(text: String): ParseResult = inbox.preview(found(text))

    override fun apply(text: String): Boolean = inbox.apply(found(text), fromKey = false).also { if (it) onSettingsChanged() }

    override fun describe(result: ParseResult): List<String> = com.tunisianprayertimes.tv.ui.usb.SettingsChangeLines.of(result)

    override fun undoText(): String? = undoFile.takeIf { it.isFile }?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }

    override fun image(kind: MediaKind, name: String): ByteArray? =
        media.images(kind).firstOrNull { it.name == name }?.let { runCatching { it.readBytes() }.getOrNull() }

    @Synchronized
    override fun addImage(kind: MediaKind, name: String, bytes: ByteArray): String? {
        if (!UsbMedia.isImage(bytes.copyOfRange(0, minOf(bytes.size, 12)))) return "ليست صورة JPEG أو PNG أو WebP"
        val existing = media.images(kind)
        if (existing.none { it.name == name } && existing.size >= UsbMedia.MAX_FILES) return "بلغت الصور الحد الأقصى (${UsbMedia.MAX_FILES}): احذف صورة أولًا"
        return runCatching { media.add(kind, name, bytes) }
            .fold(onSuccess = { onMediaChanged(); null }, onFailure = { "تعذّر حفظ الصورة" })
    }

    @Synchronized
    override fun deleteImage(kind: MediaKind, name: String): Boolean {
        val listed = media.images(kind).map { it.name } + if (kind == MediaKind.ANNOUNCEMENTS) media.textFiles().map { it.name } else emptyList()
        return name in listed && media.delete(kind, name).also { if (it) onMediaChanged() }
    }

    override fun update(): String = runBlocking { updater.installNow() }

    override fun asset(name: String): ByteArray? =
        runCatching { context.assets.open("dashboard/$name").use { it.readBytes() } }.getOrNull()

    override fun font(name: String): ByteArray? {
        val resource = when (name) {
            "readex-pro.ttf" -> com.tunisianprayertimes.tv.R.font.readex_pro
            "amiri.ttf" -> com.tunisianprayertimes.tv.R.font.amiri
            else -> return null
        }
        return runCatching { context.resources.openRawResource(resource).use { it.readBytes() } }.getOrNull()
    }

    private fun hm(time: LocalTime) = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
}
