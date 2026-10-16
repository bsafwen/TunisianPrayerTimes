### Turkey: Diyanet's district tables and `diyanet_tr` (`diyanet_tr`)

Fitted by `fits/diyanet_tr/fit.py` (2026-10-16, 6 to 10 min); asset `official-zones/diyanet_tr.json` (method
`diyanet_tr`, country `TR`, mapping `DISTRICT`, 869 zones), its named places' catalogue ids filled afterwards by
scripts/places/match_named_places.py's rule. The data comes from `fits/diyanet_tr/collect.py`; the point fit
(`points.py`) and the site reader (`diyanet_site.py`) also serve Austria's second tier (`fits/diyanet_eu_at`).

**The source.** namazvakitleri.diyanet.gov.tr, plain GETs, no account (read 2026-10-16):

- provinces: `/tr-TR/home/GetRegList?ChangeType=country&CountryId=2&Culture=tr-TR` (JSON `StateList`: 81, SehirID 500–580);
- a province's entries: `...?ChangeType=state&CountryId=2&StateId=<SehirID>&Culture=tr-TR` (JSON `StateRegionList`:
  IlceID, IlceAdi, IlceUrl); 869 entries in all;
- an entry's tables: its IlceUrl, `/tr-TR/<IlceID>/<slug>-icin-namaz-vakti`, an HTML page holding the week and 31 days from
  the day of the request and the whole **next** year (2027 in October 2026). The current year's past months are not
  served, so no 2026 year table exists on the site; the fit uses 2027 and holds out the 31 days of 2026.
- `/robots.txt` is answered with the site's firewall page («İsteğiniz güvenlik kurallarına takılmıştır»); the collector
  stops on that page. Now and then the server reset a connection; the collector asks again 10 s later.

Saved under `test-data-local/official/tr/` (A7, never committed): `lists/country_2.json`, 81 `lists/state_<id>.json`
and 869 `pages/<IlceID>.html.gz` (each page gzip-compressed as served, 22 MB in all; the manifest note holds the page's
own size and SHA-256). 951 requests at 3 s; with Austria's 192, about 80 minutes.

**Does `diyanet_tr` hold?** Yes. As `EuropeanPresets.DIYANET_TR` stands (18° / 17°, sunrise − 7, Dhuhr + 5, Asr + 4,
Maghrib + 7, the sun once a day at 0 h UT, NEAREST), computed at each entry's fitted point, every time of every entry is
within 1 minute, held out:

| Held out (869 entries) | Fajr | Sunrise | Dhuhr | Asr | Maghrib | Isha | Worst |
|---|---|---|---|---|---|---|---|
| exact / within 1 min | 98.0 / 100 % | 98.2 / 100 % | 97.8 / 100 % | 97.4 / 100 % | 98.2 / 100 % | 98.0 / 100 % | 1 min |
| per entry, min / median exact | 91.7 / 98.2 % | 94.9 / 98.2 % | 94.7 / 97.7 % | 93.9 / 97.5 % | 95.7 / 98.2 % | 93.4 / 98.2 % | |

- Held out: a month-split of 2027 (each entry's point refitted on the odd months, from the year's point as fits/om_mara
  does, scores the even ones, and the reverse) and the 31 days of 2026 (the point fitted on all of 2027), with
  common.compute. In-sample (2027) it is 97.9–98.8 % exact. 322 of the 869 zone records reach 97 % exact on every time
  (9.5's gate); the others have one or two times at 93–97 %.
- The point is fitted on all of 2027, Fajr and Isha from 15 April to 31 August left out (the widest takdir season of
  the Austrian tables). The odd- and even-month points lie a median 0.36 km apart (at most 1.7 km); 603 entries have a
  single best grid point.
- **The grid is 1/400 degree, not whole arc-minutes.** On sampled entries, held out, whole arc-minutes give 97.5 % exact,
  0.01° 97.7 %, 0.005° 97.8–97.9 %, 0.0025° 97.9 %, 0.001° and 0.0005° 97.9 %: Diyanet's points are not on an arc-minute
  grid, and finer than 0.0025° only fits noise.
- The closed form of the search (the sun fixed at 0 h UT) equals common.compute on every time checked.

**The takdir at Turkey's latitudes.** Diyanet prints no takdir in Turkey. Fajr and Isha from 15 April to 31 August,
never used in a fit, are plain 18° and 17° times: 98.5 % and 98.4 % exact, all within 1 minute, and so in every
one-degree band of the entries' latitudes, 35.9° to 42.0° N (97.1–98.8 % exact; the one entry north of 42°, SİNOP,
96.4 % and 100 %). The engine's takdir starts at 44.5° N (7.4), so it never acts in Turkey either: there the season
matches (Turkey's shortest midnight depression, about 24.5° at 42.1° N, stays far from 18°). Where it does act, Diyanet's
own Austrian tables show the V-shaped ramp described in `fits/diyanet_eu_at`.

**Zones.** One per entry, id the IlceID, `point` its district seat (GeoNames, the catalogue's own point), from which the
nearest entry is measured, and its fitted point as its E6 morning and evening points (as IGGÖ's capitals and the
Takvim's towns); the 12 entries GeoNames lists no seat for keep their fitted point as `point`. `nameAr` is the
catalogue's Arabic name of the seat (555) or D3's label, the native name in an LTR isolate then «تركيا» (314), for M5 to
replace where an Arabic name is found. Each zone's `namedPlaces` is its seat (852 matched to a catalogue row by
scripts/places/match_named_places.py's rule, run on this asset only).

- Matching: the central entry (named after its province: İSTANBUL, ANKARA, HATAY, KOCAELİ …, 81) takes its province's
  capital (PPLA/PPLC); any other entry the GeoNames row of its name within 30 km of its fitted point, in its province
  (its own name before an alternate one, a seat before a village, then the nearest), aliases for Diyanet's spellings
  (M.EREĞLİSİ, KARADENİZ EREĞLİ, 19 MAYIS, KAHRAMANKAZAN = Kazan, AZİZİYE = Ilıca). Fitted point to seat: median 1.1 km,
  90 % within 1.8 km.
- **Diyanet computes a few entries away from their town:** ARSUZ's table is HATAY's (identical on all 396 days, at
  Antakya, 34 km from Arsuz), BEYLİKDÜZÜ's point lies 20 km south of the town, in the sea (40.80° N), KARTEPE's 29 km
  north-east, AZİZİYE's 29 km from Ilıca, BAŞAKŞEHİR's 11 km south (which is why Bahçeşehir's mosques, nearer Esenyurt's
  point, still call Başakşehir's table, 6.5). The zones reproduce the tables as published.
- 12 entries have no GeoNames row in cities500 (AVCILAR, KARTAL, TUZLA, ATAKUM, GÜZELBAHÇE, BEYDAĞ, DİLOVASI, ERGENE,
  KÖYCEĞİZ, SEYDİKEMER, DEMIRKOY, DERECİK). Four take the unclaimed code of the GeoNames row within 4 km of their fitted
  point: TUZLA (İçmeler, 3.8 km), DİLOVASI (Diliskelesi, 1.6 km), SEYDİKEMER (Kemer, 0.5 km), ERGENE (Marmaracık, 0.7 km).

**Districts (`admin2`).** Each entry lists the GeoNames admin2 code of its seat; each province's central entry also lists
every code of the province no other entry serves. GeoNames' cities500 gives 955 Turkish codes; 953 are listed. Two are
listed under none, since GeoNames gives one code to two distant seats: 7732736 (Kastamonu and Cide) and 7911029
(Kalkandere and Çamlıhemşin); their places take the nearest entry. 12 entries list no code and are reached as the nearest
entry only: KONYA (every central district of Konya has its own entry; the city's row has no code), BAHÇE (its seat row
has none), CİDE and ÇAMLIHEMŞİN (the two codes above), and AVCILAR, KARTAL, ATAKUM, GÜZELBAHÇE, BEYDAĞ, KÖYCEĞİZ,
DEMIRKOY, DERECİK (no GeoNames row). İstanbul's 19 entries serve the province's 31 GeoNames codes (the central entry 15;
GeoNames has rows for 31 of the 39 districts), Ankara's 17 its 24 (central 8), Kocaeli's 9 its 12 (central 4).

- On the catalogue's 1,167 Turkish rows: 1,141 map by their code, 26 (no code, or an unlisted one) to the nearest entry;
  for 69 rows the code's entry is not the nearest one (6.5's Bahçeşehir case). A row lies a median 1.3 km from the
  point its table is computed at, 95 % within 15.8 km. One GeoNames slip: Çamyuva (325159, Kemer district) carries
  Alanya's code, 129 km away (the validity guard would act; the catalogue build should give it Kemer's).

**Open.**
1. A GPS place takes the district (admin2) of the nearest Turkish catalogue row (6.8), but GeoNames has no row in 8
   districts with an entry (Kartal, Avcılar, Atakum, Güzelbahçe …): a fix in Kartal takes Maltepe's or Pendik's code
   and table. Finding a TR place's district from the TR rows and Diyanet's entry points together, each point standing
   for its own entry, as 6.8 does with IRN's points in Norway, would fix it (CountryResolver).
2. The asset is 2.0 MB (70 KB gzipped, 1.05 MB without indentation, 0.4 MB without the 869 zone records), and
   `OfficialZoneAssets` parses every asset at first use: loading an authority's asset when its method is first needed
   would keep it off every start outside Turkey.
3. 314 D3 labels, and five names shared by two entries of different provinces (Ayvacık, Kemalpaşa, Yenice, Bayat,
   Kemer, Köprübaşı, Ortaköy, Pınarbaşı), where a province name could be added.
4. Next year's table (2028) appears on the same pages about September 2027: one more year to hold out.
