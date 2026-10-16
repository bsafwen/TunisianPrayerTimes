#### Jordan (`jo_awqaf`)

`fits/jo_awqaf/fit.py` (2026-10-15, about 1 minute on 16 workers) fits the Ministry of Awqaf's calendar and writes
`official-zones/jo_awqaf.json`: the calendar's 15 zones and its 18 further places, 33 zones. The ministry prepares the
calendar with ICOP (2025 calendar, pages 2-3), whose Accurate Times is its official software (8.5); the fit reproduces
that program's method from published sources (8.6) and fits each zone's inputs to the ministry's tables.

**Method.**

| Time | Rule |
|---|---|
| Fajr | 18° at the zone's eastern site, no dip (E3); 5,472 of 5,475 windows meet at 18.000° (Abed 2015 states 18°) |
| Sunrise | the sun's upper limb at the eastern site, on the horizon of its morning elevation (E2 `Refraction`) |
| Dhuhr | transit at the western site |
| Asr | factor 1 on the apparent sun (E13) at standard air, at the western site |
| Maghrib | sunset at the western site on the horizon of its evening elevation; + 1 min in three zones (E7) |
| Isha | 18° at the western site, no dip; 5,473 of 5,475 windows at 18.000° |
| All | VSOP87 with Delta T at each event's instant (E14), nearest minute, no method offsets, no Ramadan rule |

Stages, by windows (a published minute bounds the unrounded time to a half-open minute; the value most windows cover
wins), every zone refitted for each alternative; all 32,850 minutes of 2023:

- Sun and Asr: VSOP87 32,828 exact, MEEUS 32,337, TEXTBOOK 27,007; Asr on the geometric sun 32,119.
- **Standard air**: the apparent Asr at 1010 hPa and 10 °C gives 32,828, against 32,752 with loc.txt's air for the place
  (Dhuhr 5,474 against 5,455, Asr 5,474 against 5,436; 97 more minutes held out). A horizon in any air is the horizon
  of a lower elevation in standard air, so every zone keeps standard air (no pressure or temperature stored) and its
  elevations are fitted in it. In loc.txt's air the same horizons need Amman 1,111 m (its entry 1,100), Ma'an 1,160
  (1,140) and Aqaba 181 (150), but Karak 1,323 (1,100) and Tafila 1,677 (1,257), higher than their entries.
- **Sites**: the eastern site lies on the western site's latitude, within 0.1 km of a whole number of kilometres
  east, as Accurate Times' city diameter puts it: Amman 16, Irbid 20, Mafraq 20, Umm al-Qutain 25, Jerash and Ajloun
  15, Dhiban 15, Dulail and Hashimiya 10, Karak 5, Tafila 5, and 0 for Ma'an, Aqaba, Azraq, Shoubak and Petra and the
  two valley zones. Both month halves give the same diameters to 0.2 km. Amman's western site, 32.020 N 35.828 E, is
  Sweileh, the site the ministry names; its eastern site is 16 km east, towards Marka, the other one it names.
- **Maghrib + 1**: in Irbid, Mafraq and Umm al-Qutain, Maghrib is the sunset on the zone's one elevation plus 1 minute
  (363, 365 and 365 of 365 days), where two elevations without it give 350, 347 and 356. Elsewhere one elevation serves
  both sides, except Jerash and Ajloun: 1,104 m in the morning and 1,209 m in the evening in loc.txt's air, as if its
  eastern site were Jerash and its western one Ajloun.
- Rounding: UP and DOWN give the same counts (32,829, 32,824) with every site 0.125° east or west, which would put
  Amman's western site at 35.953 E or 35.703 E; NEAREST keeps it at Sweileh.

**Zones** (15): the site's ids (awqaf.gov.jo selector; Umm al-Qutain, which the 2026 site leaves out, is 10).
`nameAr`: the 2026 site's «عمان، البلقاء، الزرقاء، مادبا» for zone 1, the calendar's spelling for the others
(«الضليل والهاشمية»; the site writes «الظليل»). `point`: the zone's first named town; `morningPoint` and
`eveningPoint`: the fitted sites; `elevationM` and `eveningElevationM` in standard air; `offsets`: Maghrib + 1 for 2, 9
and 10. `admin1`: the GeoNames codes (JO.xx) of the governorates the zone serves (zone 1: 16, 02, 17, 23).
`belowSeaLevel`: North Ghor (7) and Central Ghor (13), with Ghor Safi (24) among the further places. `namedPlaces`:
the towns the ministry names (2025 calendar, page 2): Amman, Salt, Zarqa and Madaba for zone 1, Jerash and Ajloun,
Shoubak and Petra, Dulail and Hashimiya, and one town for each other zone (North and South Shuna for the two Ghor zones).

**Further places** (18, ids 16-33 in the calendar's printed order): `baseZoneId` 1, `offsetRule` `MONTHLY_DIFFERENCE`
(Amman's time plus the place's own time minus Amman's on the 15th, to the half-minute), each with its own fitted sites
and elevations; ties go to the solution nearest the place's loc.txt entry. Ruwaished, South Husseiniya, Quweira, Rum
(13 km diameter) and Jafr land on their entries exactly; Hasa and Mudawwara 15 and 13 km off; Irhab, the Karameh border
post and Quraiqira, which have no entry, from town seeds. Ghor Safi keeps its entry's warm air (1013 hPa, 22 °C) at
0 m, a horizon no elevation reaches in standard air (70 of 72 offsets, against 66). Jerusalem (33), for `jo_awqaf`
chosen in Jerusalem (8.8), has `country` PS and `reachKm` 15, the app's Jerusalem-governorate radius
(`PlaceResolution.JERUSALEM_KM`, around 31.7767 N 35.2345 E); no `admin2`, since GeoNames files the governorate as
7870654 and most places around the city (the Old City, Shu'fat, Bayt Hanina, Sur Bahir) carry none in cities1000.

**Mapping.** `NEAREST` with `byAdmin1` (the zones serving the place's governorate) and `namedFirst` (among them, the
nearest named town or zone point, which is the ministry's rule of 8.8). Before the named towns, `ZoneMapping.attach`
gives a place below sea level the `belowSeaLevel` zone of its governorate (North Ghor in Irbid, Central Ghor in Balqa,
Ghor Safi in Karak), or the nearest one when its governorate is unknown. The `NEAREST` rules honour `reachKm`, which
keeps the Jerusalem entry within 15 km of its point.

**Data** (test-data-local, read at run time, never copied): `arab-prototype/jo/cal2023.csv` (15 zones × 365 days),
`arab-prototype/jo/jo2025_sample.txt` (31 zone-days of 2025; `research-2026-10-11/JO/cal2025_daily.txt` is used instead
where present), `research-2026-10-08/JO/jo/dl/page.html` (Amman, 2026-10-08 to 10-17), `research-2026-10-08/JO/jo/ram1444.txt`
(Ramadan 1444), `research-2026-10-11/JO/offsets2025.txt` (the further places' offsets),
`research-2026-10-11/HIJRI/icop_ghc_1428_1450.csv` (Ramadan days only), and Accurate Times' `loc.txt` (seeds only, 8.5).
Town points: GeoNames (read 2026-10-15) for 19 towns, approximate for 20 (`TOWNS` in fit.py; M5 matches the catalogue).

**Held-out scores** (common.compute; exact share per time, all within 1 minute):

| | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha |
|---|---|---|---|---|---|---|
| 2023 month-split, 15 zones (5,475 zone-days) | 99.62 | 99.74 | 99.85 | 99.76 | 99.58 | 99.65 |
| 2025 sample, 31 zone-days | 31/31 | 31/31 | 31/31 | 31/31 | 30/31 | 31/31 |
| 2026, Amman, 10 days | 10/10 | 10/10 | 10/10 | 10/10 | 10/10 | 10/10 |
| Further places, 2025 (Amman's printed minute + printed offset, 3 days × 18) | 54/54 | 51/54 | 54/54 | 54/54 | 52/54 | 49/54 |

In-sample 2023: 99.9, 99.9, 100.0, 100.0, 99.8 and 100.0 %. The one 2025 miss (Tafila's Maghrib, 2025-01-20) lies
0.003 min from its minute. The weakest zone time held out is Amman's Maghrib, 359 of 365 (98.4 %). The printed offsets:
1,262 of 1,296 in-sample, 1,206 held out (93.1 %, month-split), all within half a minute. The Ramadan 1444 imsakiya
equals the calendar on its 28 parsed days. A transcription of 2025-01-01 to 01-04 (60 zone-days) gives 360 of 360.

**Tier A** (9.5): all 15 zones pass on their own records (at least 98.4 % exact on every time held out, all within
1 minute, 28 or more held-out days in every month from the month-split; no E8 rule). The further places do not: their
held-out times cover 3 days of January and February 2025.

**Quirk days.** None: every miss is 1 minute, at a minute's boundary.

**Open questions.**
- 20 town points are approximate (North and South Shuna, Umm al-Qutain, Dhiban, Shoubak, Hashimiya and 14 further
  places); GeoNames files Karak's old town («Qir Moav», 250624) under Ma'an, so its catalogue row needs the right
  governorate. Al-Umari's governorate (Zarqa, 17, is assumed; it may be Amman's) decides whether places in the south-east
  of Amman's governorate take its offsets.
- The remaining daily pages of the 2025 PDF (about 530 zone-days) and the 2026 calendar (Q29) would add held-out
  years and give the further places a record.
- Q19's Mafraq mosque calendars, which call Maghrib 1-3 min after the prototype: the official table itself is 1 min
  after a sunset on Mafraq's one elevation, which the prototype's two elevations missed on 20 days.
- The Jerusalem entry's `point` is GeoNames' East Jerusalem (31.7834 N 35.2339 E), 0.75 km north of the centre
  `PlaceResolution.JERUSALEM_KM` uses, so its 15 km reach is measured from a point 0.75 km away from the app's.
