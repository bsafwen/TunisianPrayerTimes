package com.tunisianprayertimes.remembrance

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.tunisianprayertimes.MainActivity
import com.tunisianprayertimes.MainTabNavigation
import com.tunisianprayertimes.ui.DeathRemembranceNoticeCard
import com.tunisianprayertimes.ui.theme.TunisianPrayerTimesTheme
import java.time.LocalDate
import java.util.Locale

/**
 * The reminder's notification opens the day's «ذكر الموت» here, as a card over whatever was on screen, so reading it
 * does not take the reader into the app. Its own task, left out of recents, so closing it returns to where they were.
 */
class DeathRemembranceActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val locale = Locale.forLanguageTag("ar-TN-u-nu-latn")
        Locale.setDefault(locale)
        val config = newBase.resources.configuration.apply { setLocale(locale) }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // The notification's day: the text changes at midnight, and the notification is gone by then.
        val day = intent.getLongExtra(EXTRA_EPOCH_DAY, Long.MIN_VALUE)
            .takeIf { epochDay -> epochDay != Long.MIN_VALUE }
            ?.let(LocalDate::ofEpochDay)
            ?: DeathReminder.today(this)
        val item = DeathReminder.remembranceFor(day)
        setContent {
            TunisianPrayerTimesTheme {
                DeathRemembranceNoticeCard(
                    item = item,
                    onSettings = {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                .putExtra(MainTabNavigation.EXTRA_DESTINATION, MainTabNavigation.DESTINATION_WORSHIP)
                                .putExtra(MainTabNavigation.EXTRA_WORSHIP_ACTION, MainTabNavigation.WORSHIP_ACTION_REMEMBRANCE),
                        )
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    companion object {
        const val EXTRA_EPOCH_DAY = "com.tunisianprayertimes.remembrance.EPOCH_DAY"
    }
}
