package com.tunisianprayertimes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GouvernoratJsonParserTest {

    @Test
    fun parsesGouvernoratWithDelegations() {
        val json = """
        {
            "gouvernorats": [
                {
                    "id": 1,
                    "nomFr": "Tunis",
                    "nomAr": "تونس",
                    "nomEn": "Tunis",
                    "delegations": [
                        {
                            "id": 100,
                            "nomFr": "Bab Bhar",
                            "nomAr": "باب البحر",
                            "nomEn": "Bab Bhar",
                            "lat": 36.7967,
                            "lng": 10.1797
                        },
                        {
                            "id": 101,
                            "nomFr": "Carthage",
                            "nomAr": "قرطاج",
                            "nomEn": "Carthage",
                            "lat": 36.8528,
                            "lng": 10.3253
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val result = GouvernoratJsonParser.parse(json)

        assertEquals(1, result.size)
        val gov = result[0]
        assertEquals(1, gov.id)
        assertEquals("Tunis", gov.nomFr)
        assertEquals("تونس", gov.nomAr)
        assertEquals("Tunis", gov.nomEn)
        assertEquals(2, gov.delegations.size)

        val d0 = gov.delegations[0]
        assertEquals(100, d0.id)
        assertEquals("Bab Bhar", d0.nomFr)
        assertEquals("باب البحر", d0.nomAr)
        assertEquals(36.7967, d0.lat, 0.001)
        assertEquals(10.1797, d0.lng, 0.001)

        val d1 = gov.delegations[1]
        assertEquals(101, d1.id)
        assertEquals("Carthage", d1.nomFr)
    }

    @Test
    fun parsesMultipleGouvernorats() {
        val json = """
        {
            "gouvernorats": [
                {
                    "id": 1,
                    "nomFr": "Tunis",
                    "nomAr": "تونس",
                    "nomEn": "Tunis",
                    "delegations": []
                },
                {
                    "id": 2,
                    "nomFr": "Ariana",
                    "nomAr": "أريانة",
                    "nomEn": "Ariana",
                    "delegations": []
                }
            ]
        }
        """.trimIndent()

        val result = GouvernoratJsonParser.parse(json)
        assertEquals(2, result.size)
        assertEquals("Tunis", result[0].nomFr)
        assertEquals("Ariana", result[1].nomFr)
    }

    @Test
    fun emptyGouvernorats() {
        val json = """{"gouvernorats": []}"""
        val result = GouvernoratJsonParser.parse(json)
        assertTrue(result.isEmpty())
    }

    @Test
    fun delegationGouvernoratNameIsSet() {
        val json = """
        {
            "gouvernorats": [
                {
                    "id": 1,
                    "nomFr": "Tunis",
                    "nomAr": "تونس",
                    "nomEn": "Tunis",
                    "delegations": [
                        {
                            "id": 100,
                            "nomFr": "Centre",
                            "nomAr": "المركز",
                            "nomEn": "Centre",
                            "lat": 36.8,
                            "lng": 10.18
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val result = GouvernoratJsonParser.parse(json)
        val delegation = result[0].delegations[0]
        assertEquals("تونس", delegation.gouvernoratName)
    }
}
