package com.tunisianprayertimes.quran.audio

import com.tunisianprayertimes.quran.QuranVerseReference
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuranPlaylistTest {
    private val verse = QuranVerseReference(2, 67)
    private val passage = QuranRepeatRange(verse, verse, times = 1)
    private val drill = QuranPlaylist("Drill", listOf(passage), listOf("2:67"), rounds = 3, answer = passage)

    @Test
    fun playbackRequestIdentitySurvivesTheServiceAndSavedDraftCodec() {
        val requested = drill.copy(requestId = "e3341772-24b9-4b48-8e6d-bb7a96aad50f")
        val startedByService = QuranPlaylist.decode(requested.encode())
        val restoredDraft = QuranPlaylist.decode(requested.encode())
        assertEquals(requested, startedByService)
        assertEquals(restoredDraft, startedByService)
    }

    @Test
    fun anEarlierIdenticalDrillCannotCompleteANewerRequest() {
        val earlier = drill.copy(requestId = "first")
        val requested = drill.copy(requestId = "second")
        assertNotEquals(QuranPlaylist.decode(earlier.encode()), QuranPlaylist.decode(requested.encode()))
        assertEquals(earlier.items, requested.items)
    }

    @Test
    fun oldPlaylistsDecodeWithoutRequestIdentityAndKeepTheirOldShape() {
        val old = """{"title":"Drill","rounds":3,"gap":1.0,"speed":0.0,"items":[[2,67,2,67,"2:67"]],"answer":[2,67,2,67]}"""
        val decoded = QuranPlaylist.decode(old)
        assertEquals(drill, decoded)
        assertNull(decoded?.requestId)
        assertFalse(JSONObject(decoded!!.encode()).has("requestId"))
        assertEquals(drill, QuranPlaylist.decode(JSONObject(old).put("requestId", JSONObject.NULL).toString()))
    }
}
