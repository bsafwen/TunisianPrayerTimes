package com.tunisianprayertimes.tv.remote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.pm.PackageInfoCompat
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.EventDate
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.InmLocation
import com.tunisianprayertimes.InmPrayerTimes
import com.tunisianprayertimes.IslamicDays
import com.tunisianprayertimes.ManualIslamicDateOverrides
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerFormulaSettings
import com.tunisianprayertimes.mosque.FlowState
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSettingsFile
import com.tunisianprayertimes.mosque.MosqueSettingsFile.DateEvent
import com.tunisianprayertimes.mosque.MosqueSettingsFile.ParseResult
import com.tunisianprayertimes.time.ClockSource
import com.tunisianprayertimes.tv.data.LocalMediaManager
import com.tunisianprayertimes.tv.data.MediaKind
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.data.findDelegation
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.kiosk.HealthRow
import com.tunisianprayertimes.tv.ui.settings.automaticDate
import com.tunisianprayertimes.tv.ui.usb.SettingsChangeLines
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * What the wall shows instead of the timetable, for the phone's «على الشاشة الآن», in the order the
 * display decides it: the clock page, the prayer's own screens, the clock question, a key's offer, the
 * settings (with the session's code on the phone page), the adhkar after the prayer, then the idle
 * wall's announcements, night and Eid screens. Null for the timetable, and for the prayer and its
 * adhkar, which the phase tells.
 */
object DashboardScreen {
    const val SETTINGS = "SETTINGS"
    const val CLOCK = "CLOCK"
    const val USB_OFFER = "USB_OFFER"
    const val ANNOUNCEMENTS = "ANNOUNCEMENTS"
    const val NIGHT = "NIGHT"
    const val EID = "EID"

    fun of(
        adminPage: Boolean,
        clockPage: Boolean,
        prayerScreen: Boolean,
        clockQuestion: Boolean,
        usbOffer: Boolean,
        adhkarOnWall: Boolean,
        announcements: Boolean,
        night: Boolean,
        eid: Boolean,
    ): String? = when {
        clockPage && !adminPage -> CLOCK
        prayerScreen && !adminPage -> null
        clockQuestion -> CLOCK
        usbOffer -> USB_OFFER
        adminPage -> SETTINGS
        adhkarOnWall -> null
        announcements -> ANNOUNCEMENTS
        night -> NIGHT
        eid -> EID
        else -> null
    }
}

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
    /** What the wall shows when it is not the timetable nor the prayer ([DashboardScreen]); null otherwise. */
    val screen: String? = null,
    /** Tomorrow's Fajr adhan and iqamah: what the wall counts down to after tonight's Isha. */
    val tomorrowFajr: LocalTime? = null,
    val tomorrowFajrIqamah: LocalTime? = null,
    /** The settings the wall was rebuilt from (the display's count of applied changes). */
    val settingsVersion: Int = 0,
    /** Today's iqamahs the wall moved from their setting, as the TV's kiosk page lists them. */
    val movedIqamahs: List<HealthRow> = emptyList(),
) {
    companion object {
        /** How long GET /api/state waits for the wall to catch up with the settings just applied. */
        const val CATCH_UP_MILLIS = 1_000L
        private const val CATCH_UP_STEP_MILLIS = 20L

        /**
         * The display's latest snapshot once it was built from settings version [wanted] or later: the
         * page reloads right after an apply, sooner than the display redraws with the new iqamah times.
         * After [timeoutMillis], whatever it is (a busy display must not hold the page).
         */
        fun awaitSettings(
            read: () -> DashboardLive?,
            wanted: Int,
            timeoutMillis: Long = CATCH_UP_MILLIS,
            elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
            sleep: (Long) -> Unit = Thread::sleep,
        ): DashboardLive? {
            val deadline = elapsed() + timeoutMillis
            var live = read()
            while ((live?.settingsVersion ?: wanted) < wanted && elapsed() < deadline) {
                sleep(CATCH_UP_STEP_MILLIS)
                live = read()
            }
            return live
        }
    }
}

/**
 * The "today" object of GET /api/state: today's prayers in the order of the day, an Eid prayer (no
 * adhan, timed from sunrise) at its own time, and tomorrow's Fajr for the night after Isha.
 */
internal fun todayJson(live: DashboardLive?): JsonObject = buildJsonObject {
    val times = live?.times
    put("date", live?.now?.toLocalDate()?.toString())
    put("hijri", live?.hijriLabel)
    put("sunrise", times?.let { "%02d:%02d".format(Locale.ROOT, it.shurukHour, it.shurukMinute) })
    put("banner", live?.banner)
    putJsonArray("prayers") {
        if (live == null || times == null) return@putJsonArray
        // As the flow resolved it: a mosque that holds no Jumu'a has Dhuhr on Fridays.
        val jumua = live.now.dayOfWeek == java.time.DayOfWeek.FRIDAY && Prayer.DHUHR !in live.iqamahTimes
        val daily = listOf(
            Prayer.FAJR to times.fajr,
            (if (jumua) Prayer.JOMOAA else Prayer.DHUHR) to times.dhuhr,
            Prayer.ASR to times.asr,
            Prayer.MAGHRIB to times.maghrib,
            Prayer.ISHA to times.isha,
        ).map { (prayer, time) -> Triple(prayer, LocalTime.of(time.hour, time.minute), live.iqamahTimes[prayer]) }
        val eid = live.iqamahTimes.filterKeys { it == Prayer.AID_FITR || it == Prayer.AID_ADHA }.map { (prayer, iqamah) -> Triple(prayer, null, iqamah) }
        (daily + eid).sortedBy { (_, adhan, iqamah) -> adhan ?: iqamah }.forEach { (prayer, adhan, iqamah) ->
            addJsonObject {
                put("id", prayer.name)
                put("name", TvStrings.prayerName(prayer))
                put("adhan", adhan?.let(::hm))
                put("iqamah", iqamah?.let(::hm))
            }
        }
    }
    put("tomorrowFajr", live?.tomorrowFajr?.let { fajr ->
        buildJsonObject {
            put("adhan", hm(fajr))
            put("iqamah", live.tomorrowFajrIqamah?.let(::hm))
        }
    } ?: JsonNull)
}

private fun hm(time: LocalTime) = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))

/** The mosque's delegation as «حساب المواقيت» shows it: its names, and its place as the formula sees it. */
data class FormulaPlace(val delegationId: Int, val delegationName: String, val gouvernoratName: String, val location: InmLocation)

/**
 * The "formula" object of GET /api/state: whether the TV uses INM's official values, the values it
 * computes its times with (the settings file's "prayerTimes" keys, every prayer's adjustment written,
 * so the page builds its section from them), and the delegation's [place] for the page's own
 * computation, with INM's per-year sunrise elevations ("sunriseElevations"); null without a place.
 */
internal fun formulaJson(settings: PrayerFormulaSettings, place: FormulaPlace?): JsonObject = buildJsonObject {
    put("official", settings.isOfficial)
    putJsonObject("settings") {
        put("fajrAngle", number(settings.fajrAngle))
        put("ishaAngle", number(settings.ishaAngle))
        put("asrShadow", settings.asrShadow)
        put("dhuhrMinutes", settings.dhuhrMinutes)
        put("maghribMinutes", settings.maghribMinutes)
        put("elevation", settings.elevation)
        putJsonObject("adjust") {
            PrayerFormulaSettings.ADJUSTABLE.forEach { prayer -> put(prayer.name.lowercase(Locale.ROOT), settings.adjustment(prayer)) }
        }
    }
    put("location", place?.let {
        buildJsonObject {
            put("delegationId", it.delegationId)
            put("delegationName", it.delegationName)
            put("gouvernoratName", it.gouvernoratName)
            put("latitude", number(it.location.latitude))
            put("longitude", number(it.location.longitude))
            put("elevation", number(it.location.elevationM))
            putJsonObject("sunriseElevations") {
                it.location.sunriseElevationOverrides.toSortedMap().forEach { (year, metres) -> put(year.toString(), number(metres)) }
            }
        }
    } ?: JsonNull)
}

/** 18 rather than 18.0, as the settings file writes it; 17.5 as it is. */
private fun number(value: Double): JsonPrimitive =
    if (value == kotlin.math.floor(value) && kotlin.math.abs(value) < 1e9) JsonPrimitive(value.toLong()) else JsonPrimitive(value)

/**
 * The TV's clock for the dashboard, from the clock guard: what the page compares with the phone's own
 * clock, and the two fixes it offers. Called on the server's threads: the guard belongs to the display's
 * main thread, so an implementation reads a snapshot there and runs [set] and [confirm] there.
 */
interface DashboardClock {
    /** The clock now. */
    fun state(): DashboardClockState

    /** The phone's clock says it is [epochMillis] now: the TV takes it ([ClockSource.PHONE]), or says why not. */
    fun set(epochMillis: Long): ClockAnswer

    /** The admin, having compared it with the phone, says the time shown is right; or why it cannot be. */
    fun confirm(): ClockAnswer
}

/** What became of a clock change asked from the phone; each has its own message on the page. */
enum class ClockAnswer {
    DONE,
    /** The guard refused it: the phone's time (or the TV's, for a confirmation) cannot be right. */
    REFUSED,
    /** The display's main thread did not take it in time: nothing changed, the admin may try again. */
    BUSY,
    /** This screen cannot do it (no clock given, or it failed). */
    UNAVAILABLE,
}

/**
 * The power of two to decode an image of [width] x [height] by, so its shorter side stays at least
 * [minSide]: enough for a gallery tile, at a fraction of the memory. 1 for a small or unknown size.
 */
fun thumbnailSampleSize(width: Int, height: Int, minSide: Int = 320): Int {
    val shorter = minOf(width, height)
    var sample = 1
    while (shorter / (sample * 2) >= minSide) sample *= 2
    return sample
}

/**
 * The cached thumbnail's file name for one exact version of image [name]. It carries the image's
 * [modified] time and [length] rather than comparing clocks: an offline box's clock can move back, and a
 * USB key then writes a new 1.jpg that looks older than the old one's thumbnail.
 */
fun thumbnailName(name: String, modified: Long, length: Long): String = "$name.$modified-$length.jpg"

/**
 * Runs [action] through [post] (the main thread's queue) and waits up to [timeoutMillis] for it to
 * start. Null when it had not started by then: it is then dropped and never runs. Once started, its
 * own answer is awaited, so a change made late is never reported as not made.
 */
fun runPosted(post: (Runnable) -> Unit, timeoutMillis: Long, action: () -> Boolean): Boolean? {
    val claimed = AtomicBoolean(false)
    val result = CompletableFuture<Boolean>()
    post(Runnable { if (claimed.compareAndSet(false, true)) result.complete(runCatching(action).getOrDefault(false)) })
    return try {
        result.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (e: TimeoutException) {
        if (claimed.compareAndSet(false, true)) null else result.get()
    }
}

/** What GET /api/state says of the clock, besides "now" and "trusted" (see docs/DASHBOARD.md). */
data class DashboardClockState(
    /** The instant the screen's time comes from (the device clock with the guard's correction), in epoch millis. */
    val epochMillis: Long,
    /** Confirmed ([com.tunisianprayertimes.time.ClockTrust.TRUSTED]); [source] says how. */
    val verified: Boolean,
    val source: ClockSource?,
    /** The device's own zone id: information for the admin, since the times are Tunisia's whatever it is. */
    val deviceZone: String,
    /** Whether the device's zone reads another time than Tunisia's now. */
    val zoneDiffers: Boolean,
)

/** The "clock" object of GET /api/state: "now" and "trusted" from what the screen shows, the rest from [clock] when known. */
internal fun clockJson(live: DashboardLive?, clock: DashboardClockState?): JsonObject = buildJsonObject {
    put("now", live?.now?.withNano(0)?.toString().orEmpty())
    put("trusted", live?.clockTrusted ?: true)
    if (clock != null) {
        put("epochMillis", clock.epochMillis)
        put("verified", clock.verified)
        put("source", clock.source?.name)
        put("deviceZone", clock.deviceZone)
        put("zoneDiffers", clock.zoneDiffers)
    }
}

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
    /** The latest settings version the display was asked to load ([DashboardLive.settingsVersion]). */
    private val settingsWanted: () -> Int = { 0 },
    /** The clock the page checks against the phone; without it the page only knows "now" and "trusted", and cannot set it. */
    private val clock: DashboardClock? = null,
    /**
     * The formula's data: the delegation's place for «حساب المواقيت», and today's times a file would
     * leave, for its preview. Without it the page has no place and the preview no new times.
     */
    private val prayerTimes: (() -> InmPrayerTimes)? = null,
) : DashboardBackend {

    private val updates = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val thumbnails = File(context.cacheDir, "dashboard-thumbnails")

    override fun stateJson(): String {
        val live = DashboardLive.awaitSettings(live, settingsWanted())
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return buildJsonObject {
            putJsonObject("app") {
                put("versionName", info?.versionName.orEmpty())
                put("versionCode", info?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L)
                put("flavor", flavor)
                put("packageName", context.packageName)
                // Tells this screen from another one at the same address: the page keeps unapplied edits per screen.
                put("installedAt", info?.firstInstallTime ?: 0L)
            }
            put("clock", clockJson(live, clock?.let { runCatching(it::state).getOrNull() }))
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
            put("today", todayJson(live))
            put("flow", flow(live))
            put("settingsFile", inbox.currentFile())
            put("formula", formulaJson(prefs.formula, formulaPlace()))
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

    private fun flow(live: DashboardLive?): JsonObject = buildJsonObject {
        val flow = live?.flow
        put("phase", flow?.phase?.name ?: "IDLE")
        put("prayer", flow?.event?.prayer?.let(TvStrings::prayerName))
        put("until", flow?.phaseEndsAt?.toLocalTime()?.let(::hm))
        live?.screen?.let { put("screen", it) }
        // The Eid prayer's wait has no adhan and no iqamah: the page says so.
        if (flow?.event?.prayer in com.tunisianprayertimes.mosque.MosqueSchedule.EID) put("eid", true)
    }

    /** The mosque's delegation for [formulaJson]; null when none is set or its place is unknown. */
    private fun formulaPlace(): FormulaPlace? = runCatching {
        val id = prefs.delegationId.takeIf { it > 0 } ?: return null
        val location = prayerTimes?.invoke()?.location(id) ?: return null
        val found = gouvernorats.findDelegation(id)
        FormulaPlace(id, found?.second?.nomAr ?: prefs.delegationName, found?.first?.nomAr.orEmpty(), location)
    }.getOrNull()

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
        // Where the TV's own page returns on «تلقائي»: the admin's other dates kept (a manual Ramadan start moves Shawwal).
        put("automatic", automaticDate(year, ManualIslamicDateOverrides.forYear(year), event).toString())
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

    /** The page's undo sends back the TV's own snapshot: read as it was saved, like the undo on the TV. */
    private fun found(text: String) = UsbSettingsFound(File("dashboard"), text, "dashboard", stored = text == undoText())

    override fun preview(text: String): ParseResult = inbox.preview(found(text))

    override fun apply(text: String): Boolean = inbox.apply(found(text), fromKey = false).also { if (it) onSettingsChanged() }

    override fun describe(result: ParseResult): List<String> {
        val live = live()
        val today = live?.let { SettingsChangeLines.Today.of(it.now.toLocalDate(), it.times, timesOn(it.now.toLocalDate())) }
        return SettingsChangeLines.of(result, today)
    }

    /** [date]'s times for the place and prayer-time values of a profile (a previewed file's). */
    private fun timesOn(date: java.time.LocalDate): ((MosqueProfile) -> DayPrayerTimes?)? = prayerTimes?.let { source ->
        { profile: MosqueProfile ->
            profile.delegationId?.let { id ->
                source().loadDayPrayerTimes(id, date.year, date.monthValue, date.dayOfMonth, profile.formulaSettings)
            }
        }
    }

    override fun undoText(): String? = undoFile.takeIf { it.isFile }?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }

    override fun image(kind: MediaKind, name: String, thumbnail: Boolean): File? {
        val file = media.images(kind).firstOrNull { it.name == name } ?: return null
        return if (thumbnail) thumbnail(kind, file) ?: file else file
    }

    /**
     * A small JPEG of [image] for the phone's gallery, made once and kept in the cache until the image
     * changes: the originals weigh up to 15 MB. One at a time, so a gallery never decodes six photos at once
     * on a box with a small heap. Null when it cannot be made (the original is sent then).
     */
    private fun thumbnail(kind: MediaKind, image: File): File? = synchronized(thumbnails) {
        val folder = File(thumbnails, kind.folder)
        val thumb = File(folder, thumbnailName(image.name, image.lastModified(), image.length()))
        if (thumb.isFile) return thumb
        // Thumbnails of this image's earlier versions are stale now.
        val versions = Regex(Regex.escape(image.name) + """\.-?\d+-\d+\.jpg""")
        folder.listFiles { file -> versions.matches(file.name) }?.forEach { it.delete() }
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.path, bounds)
            val options = BitmapFactory.Options().apply { inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight) }
            val bitmap = BitmapFactory.decodeFile(image.path, options) ?: return null
            try {
                check(thumb.parentFile!!.let { it.mkdirs() || it.isDirectory })
                val temp = File(thumb.parentFile, ".${thumb.name}.part")
                temp.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, it)) }
                check(temp.renameTo(thumb) || (thumb.delete() && temp.renameTo(thumb)))
            } finally {
                bitmap.recycle()
            }
            thumb
        }.getOrNull()
    }

    @Synchronized
    override fun addImage(kind: MediaKind, name: String, bytes: ByteArray): ImageUpload {
        if (!UsbMedia.isImage(bytes.copyOfRange(0, minOf(bytes.size, 12)))) return ImageUpload.Refused("ليست صورة JPEG أو PNG أو WebP")
        val existing = media.images(kind)
        if (existing.size >= UsbMedia.MAX_FILES) return ImageUpload.Refused("بلغت الصور الحد الأقصى (${UsbMedia.MAX_FILES}): احذف صورة أولًا")
        // Chosen here, under this lock: the page's own guess may be stale (another phone, a USB key).
        val free = DashboardRoutes.freeImageName(name, existing.map { it.name })
        return runCatching { media.add(kind, free, bytes) }
            .fold(onSuccess = { onMediaChanged(); ImageUpload.Stored(free) }, onFailure = { ImageUpload.Refused("تعذّر حفظ الصورة") })
    }

    @Synchronized
    override fun deleteImage(kind: MediaKind, name: String): Boolean {
        val listed = media.images(kind).map { it.name } + if (kind == MediaKind.ANNOUNCEMENTS) media.textFiles().map { it.name } else emptyList()
        return name in listed && media.delete(kind, name).also { if (it) onMediaChanged() }
    }

    /** A check and a download can take minutes: the answer waits a little, then says it goes on (the state tells the rest). */
    override fun update(): String {
        val install = updates.async { updater.installNow() }
        return runBlocking { withTimeoutOrNull(UPDATE_WAIT_MILLIS) { install.await() } } ?: TvStrings.PHONE_UPDATE_CONTINUES
    }

    override fun setClock(epochMillis: Long): ClockAnswer =
        clock?.let { runCatching { it.set(epochMillis) }.getOrDefault(ClockAnswer.UNAVAILABLE) } ?: ClockAnswer.UNAVAILABLE

    override fun confirmClock(): ClockAnswer =
        clock?.let { runCatching { it.confirm() }.getOrDefault(ClockAnswer.UNAVAILABLE) } ?: ClockAnswer.UNAVAILABLE

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

    private companion object {
        /** Within the server's time for a handler. */
        const val UPDATE_WAIT_MILLIS = 20_000L
        const val THUMBNAIL_QUALITY = 80
    }
}
