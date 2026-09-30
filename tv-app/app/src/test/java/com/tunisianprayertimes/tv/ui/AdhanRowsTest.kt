package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.mosque.MosqueAdhkar
import com.tunisianprayertimes.tv.ui.display.adhanRows
import org.junit.Assert.assertEquals
import org.junit.Test

class AdhanRowsTest {

    private val takbir = "اللَّهُ أَكْبَرُ اللَّهُ أَكْبَرُ"
    private val first = "أَشْهَدُ أَنْ لَا إِلَهَ إِلَّا اللَّهُ"
    private val second = "أَشْهَدُ أَنَّ مُحَمَّدًا رَسُولُ اللَّهِ"
    private val laHawla = "لَا حَوْلَ وَلَا قُوَّةَ إِلَّا بِاللَّهِ"
    private val tahlil = "لَا إِلَهَ إِلَّا اللَّهُ"

    @Test
    fun eachPhraseSaidTwiceSharesARow() {
        assertEquals(
            listOf(
                listOf(takbir), listOf(first, first), listOf(second, second),
                listOf(laHawla, laHawla), listOf(laHawla, laHawla), listOf(takbir), listOf(tahlil),
            ),
            adhanRows(MosqueAdhkar.adhanReplies(fajr = false)),
        )
    }

    @Test
    fun fajrsRepliesToItsOwnPhraseMakeARowOfTheirOwn() {
        assertEquals(
            listOf(
                listOf(takbir), listOf(first, first), listOf(second, second),
                listOf(laHawla, laHawla), listOf(laHawla, laHawla), listOf(laHawla, laHawla), listOf(takbir), listOf(tahlil),
            ),
            adhanRows(MosqueAdhkar.adhanReplies(fajr = true)),
        )
    }
}
