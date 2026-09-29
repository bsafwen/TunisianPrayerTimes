# Mosque TV dashboard

The TV serves a small management site on the local network (the mosque's Wi-Fi, or a phone's
hotspot the TV joins). No internet is needed. An admin starts a session from the TV
(Settings → الإدارة من الهاتف), scans the QR code, and manages the screen from a phone or laptop.

- Every session has a new random token (in the QR code). All `/api/*` calls need it as `?t=TOKEN`.
  Ten wrong tokens close the session (image requests from an old page don't count). The session
  ends 15 minutes after the last request with the token, and 2 hours after it started.
- The TV judges each request from its head before reading any body: token, route, and the body
  size that route accepts (413 otherwise). At most 6 connections at a time.
- Every change goes through the same checked settings file as the USB key: the page builds a
  (partial) `mosque-tv.json`, the TV previews what would change (or lists the mistakes, in
  Arabic), and nothing is written until the admin applies it. The previous settings can be
  restored ("undo the last import").
- The page itself (`/`, `/app.js`, `/style.css`, `/views/*.js`) is static and holds no data;
  it lives in `app/src/main/assets/dashboard/`. Its fonts come from the app too, so it looks like
  the screen without internet: `/fonts/readex-pro.ttf` (everything) and `/fonts/amiri.ttf` (the
  adhkar texts, loaded on that section only), `font/ttf`, public like the page. Any other name is 404.

## The page

Plain browser JavaScript, no build step, no external file. It uses the day palette «سيدي بوسعيد»
(limewash `#EFECE4`, surfaces `#FAF8F3`, ink `#0E1B2A`, door blue `#0B5E9E`), one dark card for
what the screen shows now, with the countdown in the screen's stone gold.

- The header shows the mosque's name, a dot that is green («متصل بالشاشة») while the TV answers
  and red («غير متصل») once a request fails, and the TV's clock. The sections are pill tabs.
- «نظرة عامة» follows the TV (every 30 s, and its countdown every second on the TV's clock): the next
  adhan, or the iqamah the screen is waiting for (for an Eid, the prayer itself), with what the
  screen shows (`flow`: the prayer's phase, or the night, Eid morning or announcements screen);
  today's times with the next (or current) prayer highlighted; the weather (credited to
  Open-Meteo); the kiosk checks with coloured dots; the version and the update button. A wrong TV
  clock is announced to screen readers once, not again at every refresh.
- «الإقامة», «الأذكار», «المسجد» and «رمضان والعيد» keep their main button at the bottom of the
  screen while they scroll; a field reached with Tab scrolls clear of it and of the tabs.
  «الإقامة» and «المسجد» send only the fields the admin changed, «الأذكار» only the lists changed,
  «رمضان والعيد» the three dates of the year. «الإعلانات» sends the whole list of written
  announcements and «متقدّم» the whole settings file, each from a button under its own card.
- Numbers that tick use cells of one width: Readex Pro has proportional figures and no tabular feature.

## API

All JSON is UTF-8. Times are the TV's time in Tunisia.

| Method | Path | Body | Answer |
|---|---|---|---|
| GET | `/api/state` | | the state below |
| GET | `/api/places` | | `[{ "id": 11, "name": "تونس", "delegations": [{ "id": 615, "name": "مدينة تونس" }] }]` |
| GET | `/api/adhkar` | | the reviewed adhkar library below (the same for every session: load it once) |
| POST | `/api/preview` | settings file text | `{ "ok": true, "lines": ["العشاء · مدة الصلاة: 10 د ← 12 د"] }`, or `ok: false` with the mistakes |
| POST | `/api/apply` | settings file text | same shape; `ok: true` when applied |
| GET | `/api/undo` | | `{ "available": true, "text": "<settings file>" }`: preview then apply it to undo |
| GET | `/api/image?kind=K&name=N` | | the image bytes |
| POST | `/api/image?kind=K&name=N` | the image bytes | `{ "ok": true }` or `{ "ok": false, "error": "..." }` |
| POST | `/api/image/delete?kind=K&name=N` | | `{ "ok": true }`; also removes an announcement `.txt` file (`kind=announcements`) |
| POST | `/api/update` | | `{ "ok": true, "message": "..." }` (GitHub build only) |

`K` is `backgrounds` or `announcements`. Images are JPEG, PNG or WebP, at most 15 MB each and
20 per kind. Uploaded names use letters, digits, `-`, `_`, `.`; an upload with the name of an
existing image replaces it, so the page picks a free name. Images copied from a USB key keep
their names, which may contain other characters: show and delete them by the exact name listed.
`textFiles` are the written announcements that came as `.txt` files on a USB key.

The session ends 15 minutes after the last request with the token, and at most 2 hours after it
started. An open page keeps it alive with a light request every few minutes.

Errors: `403 { "error": "..." }` without a valid token, `404 { "error": "..." }` for anything else.

### `GET /api/state`

```json
{
  "app": { "versionName": "1.0", "versionCode": 1, "flavor": "github", "packageName": "com.tunisianprayertimes.tv" },
  "clock": { "now": "2026-09-29T14:00:05", "trusted": true },
  "mosque": { "name": "مسجد النور", "delegationId": 615, "delegationName": "مدينة تونس", "gouvernoratId": 11, "themeId": "horizon" },
  "themes": [{ "id": "horizon", "name": "أفق", "description": "سماء تتبع أوقات الصلاة، تُحسب على الجهاز دون إنترنت" },
             { "id": "midad", "name": "مداد", "description": "أرضية داكنة ثابتة دون سماء" }],
  "today": {
    "date": "2026-09-29", "hijri": "18 ربيع الثاني 1448 هـ", "sunrise": "06:12", "banner": null,
    "prayers": [{ "id": "FAJR", "name": "الفجر", "adhan": "04:46", "iqamah": "05:01" }]
  },
  "flow": { "phase": "IDLE", "prayer": null, "until": null, "screen": null },
  "settingsFile": "{ ... the TV's whole settings file ... }",
  "islamicDates": {
    "hijriYear": 1448,
    "events": [{ "id": "ramadanStart", "name": "بداية رمضان", "date": "2027-02-08", "source": "ESTIMATE",
                 "automatic": "2027-02-08", "min": "2027-02-03", "max": "2027-02-13" }]
  },
  "images": { "backgrounds": [{ "name": "a.jpg", "size": 120345 }], "announcements": [] },
  "textFiles": [{ "name": "lesson.txt", "text": "درس بعد صلاة العشاء" }],
  "kiosk": [{ "level": "GOOD", "text": "...", "fix": null, "command": null }],
  "update": { "supported": true, "available": "1.1", "status": "..." },
  "weather": { "enabled": true, "text": "صحو 24°", "updated": "13:40" },
  "canUndo": true
}
```

- `flow.phase`: `IDLE`, `ADHAN`, `IQAMAH_COUNTDOWN`, `KHUTBA`, `SALAH` or `AFTER_SALAH`;
  `flow.prayer` is the prayer's Arabic name and `flow.until` the end of the phase as `HH:MM`
  (both null when idle). `clock.now` is the TV's time in Tunisia, `YYYY-MM-DDTHH:MM:SS`, no zone.
- `flow.screen`: what the wall shows while the flow is `IDLE`, when it is not the timetable: `NIGHT`
  (the dim night screen), `EID` (the Eid morning screen) or `ANNOUNCEMENTS` (the slideshow);
  absent or null for the timetable. The page reads it only while the flow is `IDLE`.
- `flow.eid`: `true` when `flow.prayer` is an Eid prayer (absent or false otherwise). It has no
  adhan and no iqamah: from sunrise its `IQAMAH_COUNTDOWN` is the wait for the prayer itself, which
  the page words as the TV does («صلاة عيد الفطر بعد», «انتظار صلاة العيد»).
- `weather`: `enabled` is the admin's choice; `text` is null when the TV has no recent weather
  (offline); `updated` is `HH:MM`. The data comes from Open-Meteo, which must be credited.
- `update`: `supported` is false in the Play build. `/api/undo` answers `{ "available": false }`
  when there is nothing to undo.
- The settings file the TV writes always has every prayer and the `mosque` and `display` sections,
  with every `display` option (`nightScreen` included), so the page reads the current choices there.
- `themes[].description`: one line for the theme picker. A theme id saved by a version before «أفق»
  reads as `horizon`.
- `islamicDates.events[].id`: `ramadanStart`, `eidFitr`, `eidAdha` (the keys of the settings
  file); `source` is `MANUAL`, `OFFICIAL` or `ESTIMATE`; `min`/`max` are the dates the file accepts.
- `kiosk[].level`: `GOOD`, `WARNING`, `BAD` or `INFO`.

### `GET /api/adhkar`

The reviewed texts that ship with the app, which the mosque's lists pick from, and the bundled
lists the screen shows when the mosque changed nothing:

```json
{
  "afterSalahMaxMinutes": 30,
  "lists": {
    "afterSalah": ["salah_istighfar", "salah_salam", "salah_la_mani", "salah_hundred", "ayat_kursi", "surah_ikhlas", "surah_falaq", "surah_nas"],
    "ticker": ["salah_salam", "subhanallah_bihamdih", "kalimatan_khafifatan", "la_hawla_quwwata", "salawat_ibrahimiyya"]
  },
  "categories": [{ "id": "SALAH", "title": "بعد الصلاة" }, { "id": "MORNING", "title": "الصباح" }],
  "entries": [{
    "id": "salah_hundred", "title": "ذكر المائة بعد الصلاة", "text": "سُبْحَانَ اللَّهِ (33)، ثم ...", "reference": "صحيح مسلم 597؛ ...",
    "count": 100,
    "steps": [{ "text": "سُبْحَانَ اللَّهِ", "count": 33 }, { "text": "الْحَمْدُ لِلَّهِ", "count": 33 },
              { "text": "اللَّهُ أَكْبَرُ", "count": 33 }, { "text": "لَا إِلَهَ إِلَّا اللَّهُ ...", "count": 1 }],
    "categories": ["SALAH"],
    "afterSalahMillis": 255150, "tickerMillis": 48000
  }]
}
```

- `afterSalahMaxMinutes`: the longest the adhkar after the prayer may last; a longer list is refused.
- `entries` is the whole library (about 100 texts, 60 KB) in its reading order; every id in
  `lists` is one of them. `categories[].id` are the values of `entries[].categories`.
- `count` is how many times the text is said after the prayer (the ticker shows every text once).
  `steps` is present only for a text said in steps (the 33/33/33/1 tasbih): its count can't change.
  A long text (several pages on screen) with a count above 1 is shown whole again for each
  reading, three times at most.
- `afterSalahMillis`: how long the TV shows the text after the prayer, at its `count`.
  `tickerMillis`: at least how long it stays in the ticker (every page or step at least 12 s, longer
  only for its reading time; the announcements between texts add time). The ticker pages and never
  scrolls: a long text is set smaller or on two lines, with no extra time. The page adds them up to
  show about how long the adhkar last.

### The settings file the forms build

The same format as the USB key (see `INSTALL_AR.md`). Every section is optional, and so is every
field of `mosque`, `display` and each prayer. «الإقامة» and «المسجد» send only the fields the admin
changed, and «الأذكار» only the lists, so a change made meanwhile from the remote, a USB key or
another phone is kept. «رمضان والعيد» sends the three dates of the year, «الإعلانات» the whole
list, and «متقدّم» the whole file:

```json
{
  "mosque": { "name": "مسجد النور", "delegation": 615 },
  "display": { "theme": "horizon", "weather": true, "backgrounds": true, "announcements": true,
               "slideSeconds": 15, "announcementsEveryMinutes": 15, "nightScreen": true },
  "prayers": { "fajr": { "iqamah": "+15", "duration": 10 }, "isha": { "iqamah": "20:00", "duration": 10 },
               "jumua": { "iqamah": "+15", "duration": 15 }, "eidFitr": { "iqamah": "+30", "duration": 30 } },
  "ramadan": { "isha": { "duration": 75 }, "fajr": { "iqamah": null } },
  "islamicDates": { "1448": { "ramadanStart": "2027-02-08", "eidFitr": null } },
  "announcements": [{ "text": "درس بعد صلاة العشاء", "from": "2026-10-01", "until": "2026-10-31" }],
  "adhkar": { "afterSalah": { "mode": "replace", "items": [{ "id": "salah_istighfar" }, { "id": "ayat_kursi" },
                                                        { "text": "...", "reference": "...", "count": 3 }] },
              "ticker": null }
}
```

- `display`: `theme` is an id of `themes` (`horizon` or `midad`). `weather`, `backgrounds`,
  `announcements` and `nightScreen` (the dim night screen, from an hour after the Isha iqamah, two
  in Ramadan, to 30 minutes before Fajr) are `true` or `false`, all on by default. `slideSeconds`:
  5-60. `announcementsEveryMinutes`: 0-120, 0 for after the prayer's adhkar only.
- `iqamah`: `"+N"` minutes after the adhan (after sunrise for the Eids, 1-90), or a fixed `"HH:MM"`.
  `duration`: minutes of prayer (the black screen), 1-90.
- `ramadan`: only what changes in Ramadan; `null` returns a field to the usual setting.
- `islamicDates`: `null` returns a date to automatic.
- `announcements` replaces the whole list; `[]` removes them all.
- `adhkar`: two lists, `afterSalah` and `ticker`. `null` returns a list to the bundled one.
  `"append"`: the bundled list, then `items`. `"replace"`: only `items`, in their order; this is how
  a bundled text is hidden or moved. 1 to 100 items. Each item is either
  - a reviewed text of the library, `{ "id": "ayat_kursi" }`, optionally with `"count"` (1-1000) to
    change how many times it is said after the prayer (not in the ticker, not for a text with
    `steps`); or
  - the mosque's own text, `{ "text": "...", "reference": "...", "count": 3 }`: the source is
    required, `count` is 1-1000 (default 1; in the ticker only 1, as every text is shown once).
    Text at most 1000 characters, source at most 200.
- The adhkar after the prayer may last at most 30 minutes on screen; a longer list is refused
  (the preview says how long it would last).
- The adhkar page sends only the lists the admin changed: `null` when a list is the bundled one
  again, `"append"` when it starts with the whole bundled list unchanged, and `"replace"` otherwise.
- A reviewed text that a later version of the app no longer has stays in the TV's saved list but
  shows nothing; the page shows it as missing so the admin can delete it (a new file naming it is
  refused).
