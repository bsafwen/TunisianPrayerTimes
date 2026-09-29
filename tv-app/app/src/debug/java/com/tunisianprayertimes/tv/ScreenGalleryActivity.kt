package com.tunisianprayertimes.tv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import com.tunisianprayertimes.DayPrayerTimes
import com.tunisianprayertimes.HijriLabels
import com.tunisianprayertimes.IslamicDays
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.PrayerTime
import com.tunisianprayertimes.mosque.DayBanner
import com.tunisianprayertimes.mosque.FastCountdown
import com.tunisianprayertimes.mosque.MosqueAdhkar
import com.tunisianprayertimes.mosque.PrayerEvent
import com.tunisianprayertimes.tv.data.Announcement
import com.tunisianprayertimes.tv.ui.common.VirtualCanvas
import com.tunisianprayertimes.tv.ui.display.AdhanScreen
import com.tunisianprayertimes.tv.ui.display.AfterSalahAzkarScreen
import com.tunisianprayertimes.tv.ui.display.AnnouncementsSlideshow
import com.tunisianprayertimes.tv.ui.display.EidScreen
import com.tunisianprayertimes.tv.ui.display.IqamahCountdownScreen
import com.tunisianprayertimes.tv.ui.display.KhutbaScreen
import com.tunisianprayertimes.tv.ui.display.MainScreenModel
import com.tunisianprayertimes.tv.ui.display.NightScreen
import com.tunisianprayertimes.tv.ui.display.PrayerBlackScreen
import com.tunisianprayertimes.tv.ui.display.PrayerDisplayScreen
import com.tunisianprayertimes.tv.ui.theme.Sky
import com.tunisianprayertimes.tv.ui.theme.ThemeRegistry
import com.tunisianprayertimes.tv.ui.theme.TvPrayerTheme
import com.tunisianprayertimes.weather.WeatherNow
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Debug builds only: one screen of the display at a chosen moment, with the mockups' day, so the
 * design can be checked on an emulator without waiting for a prayer. For example:
 * `adb shell am start -n com.tunisianprayertimes.tv.dev/com.tunisianprayertimes.tv.ScreenGalleryActivity --es screen adhan`
 * Extras: screen (main, adhan, iqamah, salah, khutba, adhkar, announcement, image, night, eid),
 * at (HH:mm:ss), date (yyyy-MM-dd), day (friday, ramadan), theme (horizon, midad), index (a text's
 * number), images (true: the mosque's own background images).
 */
class ScreenGalleryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // As when the admin opens the system's settings: the kiosk watchdog leaves the gallery on screen.
        val awayUntil = android.os.SystemClock.elapsedRealtime() + 30 * 60_000L
        (application as TvApplication).kiosk.update { it.copy(adminAwayUntil = awayUntil) }
        val screen = intent.getStringExtra("screen") ?: "main"
        val day = intent.getStringExtra("day")
        val date = intent.getStringExtra("date")?.let(LocalDate::parse) ?: when (day) {
            "friday" -> LocalDate.of(2026, 10, 2)
            "ramadan" -> LocalDate.of(2027, 2, 21)
            else -> if (screen == "eid") LocalDate.of(2027, 3, 10) else LocalDate.of(2026, 9, 29)
        }
        val at = intent.getStringExtra("at")?.let(LocalTime::parse) ?: LocalTime.of(14, 7, 12)
        val theme = ThemeRegistry.findById(intent.getStringExtra("theme") ?: "horizon")
        val index = intent.getIntExtra("index", 0)
        val images = if (intent.getBooleanExtra("images", false)) listOf(sampleImage("backdrop", 1920, 1080)) else emptyList()
        setContent {
            TvPrayerTheme(theme) {
                VirtualCanvas { Gallery(screen, LocalDateTime.of(date, at), day, index, theme.sky, images) }
            }
        }
    }

    @Composable
    private fun Gallery(screen: String, now: LocalDateTime, day: String?, index: Int, withSky: Boolean, images: List<Uri>) {
        val today = times(now.toLocalDate(), 0)
        val tomorrow = times(now.toLocalDate().plusDays(1), 1)
        val friday = day == "friday"
        val iqamah = mapOf(
            Prayer.FAJR to LocalTime.of(5, 1), Prayer.ASR to LocalTime.of(15, 42),
            Prayer.MAGHRIB to LocalTime.of(18, 15), Prayer.ISHA to LocalTime.of(19, 40),
        ) + if (friday) mapOf(Prayer.JOMOAA to LocalTime.of(13, 0)) else mapOf(Prayer.DHUHR to LocalTime.of(12, 27))
        val sky = if (withSky) Sky.at(now, today) else null
        val hijri = HijriLabels.dateLabel(IslamicDays.of(now.toLocalDate()).hijri)
        val date = now.toLocalDate()
        val banner = when {
            day == "ramadan" && now.toLocalTime() < LocalTime.of(18, 8) ->
                DayBanner.Ramadan(FastCountdown(FastCountdown.Kind.IFTAR, date.atTime(18, 8)))
            day == "ramadan" -> DayBanner.Ramadan(FastCountdown(FastCountdown.Kind.SUHOOR_ENDS, date.plusDays(1).atTime(4, 47)))
            screen == "eid" -> DayBanner.Eid(Prayer.AID_FITR, date.atTime(7, 25).takeIf { now.isBefore(it) })
            else -> null
        }
        val mosque = "جامع النور"
        when (screen) {
            "adhan" -> {
                val prayer = if (friday) Prayer.JOMOAA else Prayer.ASR
                val event = event(prayer, date.atTime(15, 32), date.atTime(15, 42))
                val companions = MosqueAdhkar.adhanCompanion()
                AdhanScreen(event, now, mosque, companions.getOrNull(index), sky)
            }
            "iqamah" -> IqamahCountdownScreen(event(Prayer.ASR, date.atTime(15, 32), date.atTime(15, 42)), now, mosque, sky)
            "eidprayer" -> IqamahCountdownScreen(event(Prayer.AID_FITR, date.atTime(6, 25), date.atTime(7, 25)), now, mosque, sky)
            "salah" -> PrayerBlackScreen()
            "khutba" -> KhutbaScreen()
            "adhkar" -> {
                val slides = MosqueAdhkar.afterSalah()
                AfterSalahAzkarScreen(slides[index.coerceIn(slides.indices)], index.coerceIn(slides.indices), slides.size, sky)
            }
            "announcement", "image" -> AnnouncementsSlideshow(
                announcements = if (screen == "image") listOf(Announcement.Image(sampleImage("poster", 1600, 900)))
                else listOf(Announcement.Text("", "درس أسبوعي كل خميس بعد صلاة العشاء في قاعة الدروس، والدعوة عامة للجميع", LocalDate.of(2026, 10, 31))),
                onDismiss = {},
                now = now,
                displaySeconds = 3600,
                footer = MainScreenModel.nextPrayerLine(now, today, tomorrow, iqamah, LocalTime.of(5, 2)),
            )
            "night" -> NightScreen(now, tomorrow.let { date.plusDays(if (now.hour >= 12) 1 else 0).atTime(it.fajr.hour, it.fajr.minute) }, date.plusDays(if (now.hour >= 12) 1 else 0).atTime(5, 2))
            "eid" -> EidScreen(banner as DayBanner.Eid, now, mosque, hijri, MainScreenModel.nextPrayerLine(now, today, tomorrow, iqamah, LocalTime.of(5, 2)))
            else -> PrayerDisplayScreen(
                todayTimes = today,
                tomorrowTimes = tomorrow,
                mosqueName = mosque,
                delegationName = "المرسى",
                gouvernoratName = "تونس",
                now = now,
                hijriLabel = hijri,
                iqamahTimes = iqamah,
                tomorrowFajrIqamah = LocalTime.of(5, 2),
                banner = banner,
                ramadanTomorrow = day == "ramadan",
                weather = WeatherNow(28.6, 0, isDay = now.hour in 7..18, todayMin = 21.0, todayMax = 31.0),
                ticker = MosqueAdhkar.ticker().let { if (index > 0) it.drop(index % it.size) + it.take(index % it.size) else it },
                sky = sky,
                backgroundImages = images,
                onSettingsRequested = {},
            )
        }
    }

    /** The mockups' day: Fajr 04:46, sunrise 06:12, Dhuhr 12:17, Asr 15:32, Maghrib 18:08, Isha 19:32. */
    private fun times(date: LocalDate, shift: Int) = DayPrayerTimes(
        day = date.dayOfMonth,
        fajr = PrayerTime(Prayer.FAJR, 4, 46 + shift), shurukHour = 6, shurukMinute = 12,
        dhuhr = PrayerTime(Prayer.DHUHR, 12, 17), asr = PrayerTime(Prayer.ASR, 15, 32),
        maghrib = PrayerTime(Prayer.MAGHRIB, 18, 8), isha = PrayerTime(Prayer.ISHA, 19, 32),
    )

    private fun event(prayer: Prayer, adhan: LocalDateTime, iqamah: LocalDateTime) =
        PrayerEvent(prayer, adhan, adhan.plusMinutes(3), iqamah, iqamah.plusMinutes(10), iqamah.plusMinutes(20), false)

    /** A picture the size of a mosque's poster or background, drawn here so the gallery needs no file. */
    private fun sampleImage(name: String, width: Int, height: Int): Uri {
        val file = File(cacheDir, "$name.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), 0xFF2D6A8F.toInt(), 0xFFD9A441.toInt(), Shader.TileMode.CLAMP)
            })
            val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = height / 8f; textAlign = Paint.Align.CENTER }
            canvas.drawText("$name ${width}x$height", width / 2f, height / 2f, text)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return Uri.fromFile(file)
    }
}
