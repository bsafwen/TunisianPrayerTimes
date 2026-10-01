package com.tunisianprayertimes

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocalityRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before fun clearPreferences() {
        context.getSharedPreferences("prayer_silence_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `catalog covers all governorates and maps to existing prayer sources`() {
        val rows = LocalityRepository.loadAll(context)
        val governors = GouvernoratRepository.loadAll(context)
        assertTrue(rows.size > 2000)
        assertEquals(24, rows.map { it.governorateId }.toSet().size)
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val timetableIds = governors.flatMap { it.delegations }.map { it.id }.toSet()
        rows.forEach { row ->
            // The nearest timetable is allowed to be across a governorate boundary.
            assertTrue(row.governorateId in governors.map { it.id })
            assertTrue(row.delegationId in timetableIds)
        }
    }

    @Test fun `search tolerates Arabic diacritics elongation and mixed whitespace`() {
        val rows = LocalityRepository.loadAll(context)
        val plain = searchLocalities(rows, "بوشوشة")
        assertFalse(plain.isEmpty())
        assertEquals(plain.map { it.id }, searchLocalities(rows, "  بُـوشُوشَة  ").map { it.id })
        assertTrue(plain.any { it.delegationId == 394 })
        assertEquals("ennasr cite", normalizeLocalitySearch("Ennasr\tCité"))
    }

    @Test fun `locality selection persists independently of prayer source`() {
        val rows = LocalityRepository.loadAll(context).filter { it.delegationId == 394 }
        val first = rows.first { it.name == "بوشوشة" }
        val second = rows.first { it.name == "خزندار" }
        PrefsManager.setLocality(context, first)
        assertEquals(first.id, LocalityRepository.selected(context)?.id)
        PrefsManager.setDelegationId(context, 394)
        assertEquals(first.id, LocalityRepository.selected(context)?.id)
        PrefsManager.setLocality(context, second)
        assertEquals(second.id, LocalityRepository.selected(context)?.id)
        assertEquals(394, PrefsManager.getDelegationId(context))
        PrefsManager.setDelegationId(context, 615)
        assertNull(LocalityRepository.selected(context))
    }

    @Test fun `GPS selection can clear a locality even within the same prayer source`() {
        PrefsManager.setLocality(context, LocalityRepository.loadAll(context).first())
        PrefsManager.clearLocality(context)
        assertNull(LocalityRepository.selected(context))
    }

    @Test fun `unknown query does not guess a location`() {
        assertTrue(searchLocalities(LocalityRepository.loadAll(context), "zzzznonexistent").isEmpty())
    }

    @Test fun `Megrine picker has one row per repeated place and keeps Sidi Rezig 2 separate`() {
        val rows = LocalityRepository.loadAvailable(context, GouvernoratRepository.loadAllDelegations(context))
        val matches = searchLocalities(rows, "megrine")
        listOf("مقرين", "جوهرة", "مقرين الرياض", "سيدي رزيق", "سيدي رزيق 2", "منزل مبروك").forEach { name ->
            assertEquals("Repeated or missing $name", 1, matches.count { it.name == name })
        }
        listOf("جوهرة", "مقرين الرياض", "سيدي رزيق").forEach { name ->
            assertEquals("معتمدية مقرين", matches.single { it.name == name }.parentName)
        }
        assertEquals(matches.map { it.id }, searchLocalities(rows, "Mégrine").map { it.id })
        assertEquals(matches.map { it.id }.toSet(), searchLocalities(rows, "مقرين").map { it.id }.toSet())
    }

    @Test fun `both original Megrine polygon selections highlight their single picker row`() {
        val all = LocalityRepository.loadAll(context)
        val picker = LocalityRepository.loadAvailable(context, GouvernoratRepository.loadAllDelegations(context))
        listOf(
            "osm:relation:7174626" to "osm:way:124689902",
            "osm:relation:7174613" to "osm:way:103565487",
        ).forEach { (sectorId, suburbId) ->
            val row = picker.single { it.id == sectorId }
            assertTrue(row.representsSelection(sectorId))
            assertTrue(row.representsSelection(suburbId))
            assertTrue(all.any { it.id == sectorId && it.hasBoundary })
            val suburb = all.single { it.id == suburbId && it.hasBoundary }
            PrefsManager.setLocality(context, suburb)
            assertEquals(suburbId, LocalityRepository.selected(context)?.id)
            assertTrue(row.representsSelection(LocalityRepository.selected(context)!!.id))
        }
    }

    @Test fun `grouping preserves aliases but never merges unverified homonyms`() {
        val sector = Locality("sector", "Same name", "Parent", 1, 1, "same name official")
        val suburb = sector.copy(id = "suburb", searchText = "same name local alias", pickerGroupId = "sector")
        val separatePlace = sector.copy(id = "elsewhere", searchText = "same name elsewhere")
        val rows = groupPickerLocalities(listOf(sector, suburb, separatePlace))
        assertEquals(listOf("sector", "elsewhere"), rows.map { it.id })
        assertEquals("sector", searchLocalities(rows, "local alias").single().id)
        assertTrue(rows.first().representsSelection("suburb"))
        assertFalse(rows.last().representsSelection("suburb"))
    }

    @Test fun `Mahdia namesake imadas select their sectors without duplicate delegation rows`() {
        val all = LocalityRepository.loadAll(context)
        val sources = GouvernoratRepository.loadAllDelegations(context)
        val picker = LocalityRepository.loadAvailable(context, sources)
        listOf(
            Triple("osm:relation:7152189", "delegation:429", "شربان"),
            Triple("osm:relation:7152235", "delegation:430", "هبيرة"),
            Triple("osm:relation:7152253", "delegation:428", "السواسي"),
        ).forEach { (sectorId, delegationId, name) ->
            val row = picker.single { it.id == sectorId }
            assertEquals(name, row.name)
            assertEquals("sector", row.kind)
            assertTrue(row.hasBoundary)
            assertEquals(1, picker.count { it.governorateId == 345 && it.name == name })
            assertTrue(row.representsSelection(delegationId))
            val savedDelegation = LocalityRepository.manualSelection(context, delegationId)!!
            assertEquals(delegationId, savedDelegation.id)
            assertEquals(
                withAvailablePrayerSource(savedDelegation, sources)?.delegationId,
                withAvailablePrayerSource(row, sources)?.delegationId,
            )
        }
        val sectorIds = all.filter { it.kind == "sector" }.mapTo(mutableSetOf()) { it.id }
        assertEquals(sectorIds, picker.filter { it.kind == "sector" }.mapTo(mutableSetOf()) { it.id })
        assertEquals(sectorIds.size, picker.count { it.id in sectorIds })
    }

    @Test fun `different sector and delegation names remain separate choices`() {
        val picker = LocalityRepository.loadAvailable(context, GouvernoratRepository.loadAllDelegations(context))
        val sector = picker.single { it.id == "osm:relation:7095862" }
        val delegation = picker.single { it.id == "delegation:553" }
        assertEquals("وادي الليل", sector.name)
        assertEquals("واد الليل", delegation.name)
        assertFalse(delegation.representsSelection(sector.id))
    }

    @Test fun `namesake village and suburb groups keep official sector IDs`() {
        val picker = LocalityRepository.loadAvailable(context, GouvernoratRepository.loadAllDelegations(context))
        listOf(
            Triple("osm:relation:7095859", "osm:way:456466522", "القباعة"),
            Triple("osm:relation:7201552", "osm:node:1143501777", "الملاسين"),
        ).forEach { (sectorId, oldPointId, name) ->
            val row = picker.single { it.id == sectorId }
            assertEquals(name, row.name)
            assertEquals("sector", row.kind)
            assertTrue(row.representsSelection(oldPointId))
            assertFalse(picker.any { it.id == oldPointId })
        }
    }

    @Test fun `nearest timetable name does not make a place part of its delegation in search`() {
        val source = Delegation(id = 1, nomAr = "مصدر قريب", nomFr = "Nearby source", nomEn = "Nearby source", lat = 36.8, lng = 10.1)
        val governor = Gouvernorat(id = 1, nomAr = "ولاية", nomFr = "Governorate", nomEn = "Governorate", delegations = listOf(source))
        val place = Locality("test", "Actual place", "Actual parent", 1, 1, "actual place actual parent", lat = 36.8, lng = 10.1)
        val rows = enrichLocalityCatalog(listOf(place), listOf(governor))
        assertTrue(searchLocalities(rows, "Nearby source").isEmpty())
        assertEquals(place.id, searchLocalities(rows, "Actual parent").single().id)
        assertEquals(1, withAvailablePrayerSource(rows.single(), listOf(source))?.delegationId)
    }

    @Test fun `manual place uses nearest available source regardless of its stored parent`() {
        val place = Locality("test", "Test", "Parent", 1, 1, "test", lat = 36.8, lng = 10.1)
        val distant = Delegation(id = 1, nomAr = "A", nomFr = "A", nomEn = "A", lat = 36.9, lng = 10.2)
        val nearby = Delegation(id = 2, nomAr = "B", nomFr = "B", nomEn = "B", lat = 36.8001, lng = 10.1001)
        assertEquals(2, withAvailablePrayerSource(place, listOf(distant, nearby))?.delegationId)
        assertEquals(1, withAvailablePrayerSource(place, listOf(distant))?.delegationId)
        assertNull(withAvailablePrayerSource(place, emptyList()))
    }

    @Test fun `outdated source and parent IDs do not discard the neighborhood catalog`() {
        val governors = GouvernoratRepository.loadAll(context)
        val place = Locality("removed-source", "حي الاختبار", "Parent", -1, -1, "test alias", lat = 36.8, lng = 10.1)
        val unaffected = place.copy(id = "unaffected", governorateId = governors.first().id)
        val rows = enrichLocalityCatalog(listOf(place, unaffected), governors)
        assertEquals(listOf(place.id, unaffected.id), rows.map { it.id })
        val expectedSource = withAvailablePrayerSource(place, governors.flatMap { it.delegations })!!.delegationId
        assertEquals(expectedSource, rows.first().delegationId)
        assertEquals(rows, searchLocalities(rows, "test alias"))
        assertEquals(rows, searchLocalities(rows, "حي الاختبار"))
    }

    @Test fun `place with missing source and no coordinates remains searchable without guessing a source`() {
        val place = Locality("unmapped", "Unmapped place", "Parent", -1, -1, "unmapped place")
        val rows = enrichLocalityCatalog(listOf(place), GouvernoratRepository.loadAll(context))
        assertEquals(listOf(place.id), searchLocalities(rows, "unmapped").map { it.id })
        assertNull(withAvailablePrayerSource(rows.single(), GouvernoratRepository.loadAllDelegations(context)))
    }

    @Test fun `invalid representative coordinates cannot select an arbitrary prayer source`() {
        val source = Delegation(id = 1, nomAr = "A", nomFr = "A", nomEn = "A", lat = 36.8, lng = 10.1)
        val place = Locality("test", "Test", "Parent", 1, 1, "test", lat = 36.8, lng = 10.1)
        listOf(
            place.copy(lat = Double.NaN),
            place.copy(lng = Double.POSITIVE_INFINITY),
            place.copy(lat = 91.0),
            place.copy(lng = 181.0),
            place.copy(lat = null),
            place.copy(lng = null)
        ).forEach { assertNull(withAvailablePrayerSource(it, listOf(source))) }
        assertEquals(1, withAvailablePrayerSource(place.copy(lat = null, lng = null), listOf(source))?.delegationId)
    }

    @Test fun `nearest prayer source skips invalid source coordinates and resolves distance ties by ID`() {
        val place = Locality("test", "Test", "Parent", 1, 1, "test", lat = 36.8, lng = 10.1)
        val source = Delegation(id = 2, nomAr = "A", nomFr = "A", nomEn = "A", lat = 36.8, lng = 10.1)
        val sources = listOf(
            source.copy(id = 0, lat = Double.NaN),
            source.copy(id = 1, lat = 0.0),
            source.copy(id = 4, lng = Double.POSITIVE_INFINITY),
            source.copy(id = 3),
            source
        )
        assertEquals(2, withAvailablePrayerSource(place, sources)?.delegationId)
    }

    @Test fun `browsing reuses a valid compiled source and selection computes the nearest available source`() {
        val source = Delegation(id = 1, nomAr = "A", nomFr = "A", nomEn = "A", lat = 36.9, lng = 10.2)
        val near = source.copy(id = 2, lat = 36.8001, lng = 10.1001)
        val governor = Gouvernorat(id = 1, nomAr = "Gov", nomFr = "Gov", nomEn = "Gov", delegations = listOf(source, near))
        val place = Locality("test", "Test", "Parent", 1, 1, "test", lat = 36.8, lng = 10.1)

        val catalog = enrichLocalityCatalog(listOf(place), listOf(governor))
        assertEquals(1, catalog.single().delegationId)
        assertSame(catalog.single(), filterAvailableLocalities(catalog, listOf(near)).single())
        assertEquals(2, withAvailablePrayerSource(catalog.single(), listOf(source, near))?.delegationId)
    }

    @Test fun `browsing eligibility agrees with selection without eagerly remapping every locality`() {
        val rows = LocalityRepository.loadAll(context)
        val available = GouvernoratRepository.loadAllDelegations(context).take(3)
        val eligible = filterAvailableLocalities(rows, available)
        assertEquals(rows.filter { withAvailablePrayerSource(it, available) != null }.map { it.id }, eligible.map { it.id })
        assertTrue(eligible.all { candidate -> rows.any { it === candidate } })
        assertTrue(filterAvailableLocalities(rows, emptyList()).isEmpty())
    }

    @Test fun `browsing rejects malformed places and unusable coordinate sources`() {
        val source = Delegation(id = 1, nomAr = "A", nomFr = "A", nomEn = "A", lat = 36.8, lng = 10.1)
        val place = Locality("test", "Test", "Parent", 1, 1, "test", lat = 36.8, lng = 10.1)
        val delegation = place.copy(id = "delegation:1", lat = null, lng = null)
        val invalid = listOf(place.copy(lat = null), place.copy(lng = null), place.copy(lat = Double.NaN), place.copy(lng = 181.0))
        assertEquals(listOf(place, delegation), filterAvailableLocalities(invalid + place + delegation, listOf(source)))
        assertEquals(listOf(delegation), filterAvailableLocalities(listOf(place, delegation), listOf(source.copy(lat = 0.0))))
    }

    @Test fun `available catalog is reused across reopening and tracks changed source coordinates`() {
        val sources = GouvernoratRepository.loadAllDelegations(context).take(3)
        val catalog = LocalityRepository.loadAvailable(context, sources)
        assertSame(catalog, LocalityRepository.loadAvailable(context, sources.reversed()))

        val invalidSources = sources.map { it.copy(lat = 0.0, lng = 0.0) }
        val unavailable = LocalityRepository.loadAvailable(context, invalidSources)
        assertTrue(unavailable.all { it.lat == null && it.lng == null })
        assertEquals(sources.size, unavailable.size)
        assertEquals(catalog, LocalityRepository.loadAvailable(context, sources))
        assertTrue(LocalityRepository.loadAvailable(context, emptyList()).isEmpty())
    }
}
