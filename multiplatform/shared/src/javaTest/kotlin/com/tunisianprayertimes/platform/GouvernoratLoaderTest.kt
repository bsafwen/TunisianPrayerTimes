package com.tunisianprayertimes.platform

import com.tunisianprayertimes.*
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GouvernoratLoaderTest {

    private val testDir = File(System.getProperty("java.io.tmpdir"), "gouv-test-${System.currentTimeMillis()}")

    private val sampleJson = """
    {
        "gouvernorats": [
            {
                "id": 1,
                "nomFr": "Tunis",
                "nomAr": "تونس",
                "nomEn": "Tunis",
                "delegations": [
                    {
                        "id": 615,
                        "nomFr": "Tunis Centre",
                        "nomAr": "تونس المدينة",
                        "nomEn": "Tunis Centre",
                        "lat": 36.8065,
                        "lng": 10.1815
                    },
                    {
                        "id": 616,
                        "nomFr": "La Marsa",
                        "nomAr": "المرسى",
                        "nomEn": "La Marsa",
                        "lat": 36.8783,
                        "lng": 10.3247
                    }
                ]
            }
        ]
    }
    """.trimIndent()

    private fun setup() {
        testDir.mkdirs()
        File(testDir, "gouvernorats.json").writeText(sampleJson)
        // Create CSV data so delegations pass the hasPrayerData filter
        val csv = "Day,Fajr,Shuruk,Duhr,Asr,Maghrib,Isha\n1,05:30,06:50,12:15,15:30,18:10,19:30"
        val now = java.util.Calendar.getInstance()
        val year = now.get(java.util.Calendar.YEAR)
        val month = now.get(java.util.Calendar.MONTH) + 1
        for (id in listOf(615, 616)) {
            val dir = File(testDir, "csv/$id/$year")
            dir.mkdirs()
            File(dir, "%02d.csv".format(month)).writeText(csv)
        }
        PrayerDataLoader.init(testDir)
        GouvernoratLoader.init(testDir)
    }

    @Test
    fun loadAllReturnsGouvernorats() {
        setup()
        val result = GouvernoratLoader.loadAll()
        assertEquals(1, result.size)
        assertEquals("Tunis", result[0].nomFr)
        assertEquals(2, result[0].delegations.size)
        testDir.deleteRecursively()
    }

    @Test
    fun findDelegationByIdWorks() {
        setup()
        val d = GouvernoratLoader.findDelegationById(615)
        assertNotNull(d)
        assertEquals("Tunis Centre", d.nomFr)

        val missing = GouvernoratLoader.findDelegationById(9999)
        assertNull(missing)
        testDir.deleteRecursively()
    }

    @Test
    fun findNearestDelegationWorks() {
        setup()
        // Coordinates near La Marsa
        val nearest = GouvernoratLoader.findNearestDelegation(36.88, 10.32)
        assertNotNull(nearest)
        assertEquals(616, nearest.id)
        testDir.deleteRecursively()
    }

    @Test
    fun missingJsonReturnsEmptyList() {
        val emptyDir = File(System.getProperty("java.io.tmpdir"), "gouv-empty-${System.currentTimeMillis()}")
        emptyDir.mkdirs()
        GouvernoratLoader.init(emptyDir)
        PrayerDataLoader.init(emptyDir)
        val result = GouvernoratLoader.loadAll()
        assertTrue(result.isEmpty())
        emptyDir.deleteRecursively()
    }
}
