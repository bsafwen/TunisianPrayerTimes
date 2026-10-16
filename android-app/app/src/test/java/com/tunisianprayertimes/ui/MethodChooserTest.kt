package com.tunisianprayertimes.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.tunisianprayertimes.PrefsManager
import com.tunisianprayertimes.place.LatLngE5
import com.tunisianprayertimes.place.PrayerPlace
import com.tunisianprayertimes.place.ZoneSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The method question of 6.4: a chooser country's, an unknown country's «تأكيد الطريقة», and their answers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MethodChooserTest {
    private lateinit var context: Context

    private fun place(lat: Double, lng: Double, zone: String, country: String?, admin2: String? = null) =
        PrayerPlace.Coordinates(LatLngE5.of(lat, lng).latE5, LatLngE5.of(lat, lng).lngE5, null, zone, ZoneSource.CATALOGUE, country, null, null, admin2)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aTunisianPlaceAsksNothing() {
        assertNull(pendingMethodQuestion(context))
    }

    @Test
    fun franceAsksUntilAMethodIsPickedAndThePickSticks() {
        PrefsManager.setPlace(context, place(48.86, 2.35, "Europe/Paris", "FR", "75"), "باريس", fromGps = false)
        val question = requireNotNull(pendingMethodQuestion(context))
        assertTrue(question.hasChooser)
        answerMethodQuestion(context, question, methodId = "isna", asrShadow = null)
        assertNull(pendingMethodQuestion(context))
        val choice = requireNotNull(PrefsManager.getMethodChoice(context, question.place))
        assertEquals("isna", choice.methodId)
        assertTrue(choice.chooserAnswered)
        assertTrue(choice.methodPicked)
    }

    @Test
    fun anUnknownCountryOnlyConfirmsMwl() {
        PrefsManager.setPlace(context, place(0.5, -30.0, "Atlantic/Azores", null), null, fromGps = true)
        val question = requireNotNull(pendingMethodQuestion(context))
        assertFalse(question.hasChooser)
        answerMethodQuestion(context, question, methodId = null, asrShadow = null)
        assertNull(pendingMethodQuestion(context))
        assertEquals("mwl", PrefsManager.getMethodChoice(context, question.place)?.methodId)
    }

    @Test
    fun portugalAsksTheAsrFactorOnlyAndKeepsTheDefaultUnpicked() {
        PrefsManager.setPlace(context, place(38.72, -9.14, "Europe/Lisbon", "PT"), "لشبونة", fromGps = false)
        val question = requireNotNull(pendingMethodQuestion(context))
        answerMethodQuestion(context, question, methodId = null, asrShadow = 2)
        val choice = requireNotNull(PrefsManager.getMethodChoice(context, question.place))
        assertEquals(2, choice.asrShadow)
        assertTrue(choice.chooserAnswered)
        assertFalse(choice.methodPicked)
    }
}
