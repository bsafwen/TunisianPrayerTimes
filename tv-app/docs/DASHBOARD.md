# Mosque TV dashboard

The TV serves a small management site on the local network (the mosque's Wi-Fi, or a phone's
hotspot the TV joins). No internet is needed. An admin starts a session from the TV
(Settings → الإدارة من الهاتف), scans the QR code, and manages the screen from a phone or laptop.
The code holds the TV's address on the network it uses; the TV's other local addresses (Ethernet and
Wi-Fi, Wi-Fi Direct) are listed under it. While the session runs the address is read again on return
to the app, on each network change and every minute, so a TV that joins the phone's hotspot after the
session started shows its code without a new session.

- Every session has a new random token (in the QR code). All `/api/*` calls need it as `?t=TOKEN`.
  Ten wrong tokens from one address lock that address out for a minute, even with the right token
  (`403`); other addresses, the admin's phone among them, go on. Image requests from an old page
  don't count. The session ends 15 minutes after the last request with the token, and 2 hours
  after it started; from then on the token is refused (`403 { "error": "انتهت الجلسة" }`) even
  before the TV stops the server (it does so when it next draws, not while the app is in the background).
- The TV judges each request from its head before reading any body: token, route, and the body
  size that route accepts (413 otherwise). At most 6 connections at a time: a new one waits up to
  5 s for a free slot, then gets `503 { "error": "الشاشة مشغولة، أعد المحاولة", "busy": true }`,
  which the page retries a few times (nothing was done). Each connection has time limits, measured
  on the TV's time since boot (setting its clock from the page moves none): 15 s for the head,
  2 minutes for the body, and for the answer 15 s plus its size at 32 KB/s; a connection past its
  limit is cut, so a phone gone mid-transfer does not hold a slot. Images are streamed from their
  files, never held whole in memory.
- Every change goes through the same checked settings file as the USB key: the page builds a
  (partial) `mosque-tv.json`, the TV previews what would change (or lists the mistakes, in
  Arabic), and nothing is written until the admin applies it. The previous settings can be
  restored ("undo the last import").
- The page itself (`/`, `/app.js`, `/formula.js`, `/style.css`, `/views/*.js`) is static and holds no data;
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
  screen shows (`flow`: the prayer's phase, or the night, Eid morning or announcements screen, or the
  settings, the clock's question or a key's offer when they hold the wall);
  today's times with the next (or current) prayer highlighted; the weather (credited to
  Open-Meteo); the kiosk checks with coloured dots; the version and the update button.
- Its clock card compares the TV's clock with the phone's own (below). It says «ساعة الشاشة مؤكَّدة»
  (and how: the internet, the admin on the TV, a phone, or the box's zone) or «غير مؤكَّدة», shows
  both times in Tunisia's time, and offers the one fix that fits: «اضبط الشاشة على وقت هاتفي» when
  they are more than a minute apart, «الوقت صحيح» when an unconfirmed time agrees. A TV confirmed by
  the internet that disagrees with the phone gets a line asking to check the phone first. The
  device's zone is shown when it is not Tunisia's, as information. A problem (implausible clock,
  more than a minute apart, unconfirmed) puts the card first and is announced to screen readers
  once, not again at every refresh; a confirmed clock that agrees sits under today's times.
- «الإقامة», «الأذكار», «المسجد», «حساب المواقيت» and «رمضان والعيد» keep their main button at the
  bottom of the screen while they scroll; a field reached with Tab scrolls clear of it and of the tabs.
  «الإقامة», «المسجد» and «رمضان والعيد» send only the fields (or dates) the admin changed, «الأذكار»
  only the lists changed, «حساب المواقيت» its whole `prayerTimes` section (or `null` for the official
  values); nothing changed gives «لا تغيير». «الإعلانات» sends the whole list of
  written announcements and «متقدّم» the whole settings file, each from a button under its own card.
- Each tab tapped adds a history entry (`#section`), so the phone's Back button returns to the
  section before instead of leaving the page. Edits not applied yet are kept per section until
  applied: the edited fields of «الإقامة», «المسجد», «حساب المواقيت», «رمضان والعيد» and «متقدّم» (by
  field id), and the
  lists of «الأذكار» and «الإعلانات». They survive another tab, Back, and a redraw after an upload or
  a clock action (which redraws a section only if it is still the one shown). They are also stored
  in the browser (`localStorage`, key `mosque-tv-drafts:<packageName>:<installedAt>`, for a day): a
  reloaded or discarded tab, or a new session with the same screen, offers «استعادة التعديلات» or
  «تجاهلها» above the section. The key names the TV's installation (`app.installedAt`), not its
  address, so another mosque's TV at the same IP never gets them. A place list that loads after the
  form is drawn re-baselines its fields (`ctx.drawn`), so a place put back is not an edit. Leaving the page with edits not applied asks first (`beforeunload`).
- When a request fails past the session's limits (15 minutes since the TV last answered, or the end
  given by `session.remainingMillis`), the page shows «انتهت الجلسة» with how to start a new one,
  not the Wi-Fi hint. From 10 minutes before the 2-hour cap a notice above the section says when it ends.
- Photos larger than the screen are scaled on the phone to fit 1920×1080, in their own format
  (JPEG quality 0.85), before they are sent; the original goes when it fits already or the browser
  cannot decode or encode it. A batch that would pass 20 images is refused before anything is sent.
- «حساب المواقيت» (after «المسجد») explains how the times of the mosque's delegation are computed:
  the place (latitude, longitude, elevation, from `formula.location`; without a place it asks for one
  in «المسجد» first), a day of the TV's year (a slider, with «اليوم», «أطول نهار» and «أقصر نهار»),
  the sun's altitude through that day with each time on it, the rule of each time, and Asr's stick
  and shadow. It computes on the phone with `formula.js`, a port of the TV's formula (the same
  results, checked for every delegation and day of 2026 by `scripts/prayer_formula/check_dashboard_formula.js`).
  INM's official values are locked: «تخصيص الحساب» unlocks the Fajr and Isha angles (15° to 20°, by
  0.5°), Asr's shadow (1, or 2 for the Hanafi rule), Dhuhr's minutes after solar noon (0-15), Maghrib's
  after sunset (0-10), whether the elevation lowers the horizon, and each prayer's own minutes
  (−15 to +15; not the sunrise). Every change shows at once, each time with its difference from the
  official one, under a warning that Tunisia's official times are INM's. «معاينة وحفظ» sends the
  whole `prayerTimes` section (or `null` when the values are the official ones, «الرجوع إلى الأوقات
  الرسمية» included), through the usual preview. The TV then computes every time with them: the wall,
  the iqamahs, Jumu'a, the Eid prayers (from sunrise), the night screen, suhoor and iftar. «نظرة
  عامة» says under today's times when they are computed with the mosque's own values.
- «متقدّم» shows where the file goes on a USB key: `Android/data/<packageName>/files/mosque-tv.json`,
  under that exact name.
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
| GET | `/api/image?kind=K&name=N` | | the image bytes; with `&thumb=1` a small JPEG of it (shorter side 320 to 640 px, made once and cached on the TV per image version, its time and size, never by clock order), for the gallery |
| POST | `/api/image?kind=K&name=N` | the image bytes | `{ "ok": true, "name": "N_2.jpg" }` (the name kept) or `{ "ok": false, "error": "..." }` |
| POST | `/api/image/delete?kind=K&name=N` | | `{ "ok": true }`; also removes an announcement `.txt` file (`kind=announcements`) |
| POST | `/api/update` | | `{ "ok": true, "message": "..." }` (GitHub build only); during a prayer or within 15 minutes of an adhan nothing is installed and `message` says so; after 20 s it answers that the update goes on, and `update.status` in the state follows it |
| POST | `/api/clock` | `{ "epochMillis": 1790686805000 }` or `{ "confirm": true }` | `{ "ok": true, "message": "ضُبطت ساعة الشاشة على وقت هاتفك" }`; `ok: false` with the reason when the TV refuses, is busy or cannot |

`K` is `backgrounds` or `announcements`. Images are JPEG, PNG or WebP, at most 15 MB each and
20 per kind. Uploaded names use letters, digits, `-`, `_`, `.`. An upload never replaces an image:
when the name is taken (ignoring case) the TV stores it as `name_2.jpg`, `name_3.jpg`... and says
the name kept. The page makes the same guess first, but the TV decides with the names it has now
(another phone, or a USB key, may have added one since). Images copied from a USB key keep
their names, which may contain other characters: show and delete them by the exact name listed.
`textFiles` are the written announcements that came as `.txt` files on a USB key.

The session ends 15 minutes after the last request with the token, and at most 2 hours after it
started, both measured on the TV's time since boot: setting the TV's clock from the page neither
ends the session nor stretches it. An open page keeps it alive with a light request every few minutes.
Past either limit the token is refused (`403 { "error": "انتهت الجلسة" }`) until the TV stops the server.

Errors: `403 { "error": "..." }` without a valid token (or from a locked-out address), `503` when the TV stays busy (see above), `404 { "error": "..." }` for anything else.

### `GET /api/state`

```json
{
  "app": { "versionName": "1.0", "versionCode": 1, "flavor": "github", "packageName": "com.tunisianprayertimes.tv", "installedAt": 1790000000000 },
  "clock": { "now": "2026-09-29T14:00:05", "trusted": true, "epochMillis": 1790686805000, "verified": false,
             "source": null, "deviceZone": "Asia/Shanghai", "zoneDiffers": true },
  "mosque": { "name": "مسجد النور", "delegationId": 615, "delegationName": "مدينة تونس", "gouvernoratId": 11, "themeId": "horizon" },
  "themes": [{ "id": "horizon", "name": "أفق", "description": "سماء تتبع أوقات الصلاة، تُحسب على الجهاز دون إنترنت" },
             { "id": "midad", "name": "مداد", "description": "أرضية داكنة ثابتة دون سماء" }],
  "today": {
    "date": "2026-09-29", "hijri": "18 ربيع الثاني 1448 هـ", "sunrise": "06:12", "banner": null,
    "prayers": [{ "id": "FAJR", "name": "الفجر", "adhan": "04:46", "iqamah": "05:01" }],
    "tomorrowFajr": { "adhan": "04:47", "iqamah": "05:02" }
  },
  "flow": { "phase": "IDLE", "prayer": null, "until": null, "screen": null },
  "settingsFile": "{ ... the TV's whole settings file ... }",
  "formula": {
    "official": true,
    "settings": { "fajrAngle": 18, "ishaAngle": 18, "asrShadow": 1, "dhuhrMinutes": 7, "maghribMinutes": 2, "elevation": true,
                  "adjust": { "fajr": 0, "dhuhr": 0, "asr": 0, "maghrib": 0, "isha": 0 } },
    "location": { "delegationId": 615, "delegationName": "تونس", "gouvernoratName": "تونس",
                  "latitude": 36.8, "longitude": 10.183, "elevation": 9, "sunriseElevations": {} }
  },
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
  "canUndo": true,
  "session": { "remainingMillis": 6840000 }
}
```

- `session.remainingMillis`: how long the session has left at most (its 2-hour cap), added by the
  routes; the page warns 10 minutes before and tells an ended session from a network problem.

- `today.prayers`: in the order of the day. An Eid prayer has `adhan: null` and its `iqamah` is the
  prayer itself (timed from sunrise), placed at that time; the page counts down to it as
  «صلاة عيد الفطر بعد». `today.tomorrowFajr` is tomorrow's Fajr adhan and iqamah (`iqamah` may be
  null), which the page counts down to after Isha as the wall does; null until the TV has tomorrow's times.
- `flow.phase`: `IDLE`, `ADHAN`, `ADHAN_DUA`, `IQAMAH_COUNTDOWN`, `KHUTBA`, `SALAH` or `AFTER_SALAH`
  (`ADHAN_DUA` is the minute of the dua after the adhan, right after the adhan screen);
  `flow.prayer` is the prayer's Arabic name and `flow.until` the end of the phase as `HH:MM`
  (both null when idle). `clock.now` is the TV's time in Tunisia, `YYYY-MM-DDTHH:MM:SS`, no zone.
  `KHUTBA` follows the Jumu'a adhan screen and the dua after it (`ADHAN_DUA`) until the iqamah, or the
  adhan screen directly when the mosque turned that dua off (`dua`); with a khutba length (`khutba`), a
  longer wait starts as `IQAMAH_COUNTDOWN`, whose `until` is then when the khutba screen begins.
- `today.prayers`: on Fridays `JOMOAA` in Dhuhr's place, unless the mosque holds no Jumu'a; an Eid
  prayer only on its day and only when the mosque holds it.
- `clock`: `trusted` is false when the TV's clock cannot be right (a box reset to a past year): the
  screen shows no prayer times then. `epochMillis` is the instant the TV's time comes from (its
  device clock with the in-app correction); the page compares it with the phone's `Date.now()`,
  allowing for half the request's round trip. `verified` is true when the time was confirmed, and
  `source` says how: `NETWORK` (an internet time agreed or corrected it), `ADMIN` (on the TV),
  `PHONE` (this page), or `ZONE` (the box's zone keeps Tunisia's time all year, so its clock means Tunisia's);
  `source` is null while unconfirmed. `deviceZone` is the box's own zone id and `zoneDiffers` whether
  it reads another time than Tunisia's now: information only, the times are Tunisia's whatever it
  is. A TV without these fields (an older version) sends only `now` and `trusted`.
- `flow.screen`: what the wall shows while the flow is `IDLE`, when it is not the timetable: `NIGHT`
  (the dim night screen), `EID` (the Eid morning screen) or `ANNOUNCEMENTS` (the slideshow);
  absent or null for the timetable. `ANNOUNCEMENTS` also comes during `AFTER_SALAH` once its adhkar
  have played through: the slideshow owed to the prayer starts there. The page reads these only then
  and while the flow is `IDLE`. In any phase, `SETTINGS` (the TV's settings, the session's code on
  its phone page among them: the card tells the admin to press Back, or that the settings close by
  themselves after 3 minutes without a key; stopping the session leaves them open), `CLOCK`
  (the clock's question, or the clock page of an impossible clock) and `USB_OFFER` (a key's settings
  file or images offered) say the wall is not the timetable, nor the prayer (`remote/DashboardBackendImpl.kt`,
  `DashboardScreen`). With an impossible clock the flow is `IDLE`: no phantom day's prayer is reported.
- Right after an apply the page reads the state again at once: the TV answers once the wall was
  rebuilt from the new settings (the iqamah times of `today`, and its prayer times once recomputed
  for a new place or new formula values), waiting up to 1 s for it
  (`DashboardLive.awaitSettings`). A clock set or confirmed from the page is in the next state at once.
- `flow.eid`: `true` when `flow.prayer` is an Eid prayer (absent or false otherwise). It has no
  adhan and no iqamah: from sunrise its `IQAMAH_COUNTDOWN` is the wait for the prayer itself, which
  the page words as the TV does («صلاة عيد الفطر بعد», «انتظار صلاة العيد»).
- `weather`: `enabled` is the admin's choice; `text` is null when the TV has no recent weather
  (offline); `updated` is `HH:MM`. The data comes from Open-Meteo, which must be credited.
- `update`: `supported` is false in the Play build. `/api/undo` answers `{ "available": false }`
  when there is nothing to undo. Its text, sent back to preview or apply, is read as the TV saved
  it: a text an update retired since does not block the undo.
- The settings file the TV writes always has every prayer and the `mosque` and `display` sections,
  with every `display` option (`nightScreen` included), so the page reads the current choices there.
  It is written in full, so that another TV reading it ends up the same: every Ramadan field of
  every prayer (`null` when unset), this Hijri year and the next under `islamicDates` (`null` for an
  automatic date), `"adhkar": { "afterSalah": null, "ticker": null }` for the bundled texts, and
  `"announcements": []` when there are none. The TV's own lists are accepted back as they are, even
  with a text an update retired or an after-prayer list a slower pace made longer than 30 minutes.
- The preview's `lines` say values as the TV's settings pages do («بعد الأذان 15 د», «الساعة 20:00»,
  «بعد الشروق 45 د» for an Eid, «كل 15 دقيقة», «مفعّل»); a fixed iqamah that today's adhan would not
  use says what the screen counts instead, an iqamah before the end of the adhan screen and the dua
  after it says it waits for them, and an announcement whose `until` has passed is named. The prayer-time values read
  «زاوية الفجر: 18° ← 16.5°», «ظلّ العصر: مثل واحد ← مثلان», «تعديل العشاء: 0 د ← +2 د» (the degree
  sign and the signs kept by their numbers, LRI…PDI), with a line when they return to INM's official
  times, then today's times that move («الفجر اليوم: 04:47 ← 04:57»); a fixed iqamah is then judged
  against those new times.
  Mistakes name their place («الإعلان 4: …», «قرب السطر 23») and keep the file's samples left to
  right (LRI…PDI). At most 50 mistakes and 100 changes are listed, then one line with the count of
  the rest.
- `formula` (`formulaJson`): the values the TV computes its times with, under the settings file's
  `prayerTimes` keys, every prayer's `adjust` written (0 included), angles as the file writes them
  (`18`, `17.5`); `official` is true when they are INM's. `location` is the delegation as the formula
  sees it (degrees, metres), or null when no place is set; `sunriseElevations` holds INM's per-year
  sunrise elevations (`{ "2026": 15.6 }` for Zeriba, 409; `{}` for nearly every delegation), which the
  sunrise uses instead of `elevation` when the elevation is counted, so the page's sunrise is the TV's.
- `themes[].description`: one line for the theme picker. A theme id saved by a version before «أفق»
  reads as `horizon`.
- `islamicDates.events[].id`: `ramadanStart`, `eidFitr`, `eidAdha` (the keys of the settings
  file); `source` is `MANUAL`, `OFFICIAL` or `ESTIMATE` (what the screen uses: an admin's date the
  calendar could not keep is not `MANUAL`); `automatic` is where the date goes once its own manual
  date is cleared, the admin's other dates kept (a manual Ramadan start moves the Eid al-Fitr with
  it), as the TV's dates page says it; `min`/`max` are the dates the file accepts, each date alone. `hijriYear` is this Hijri year until four days after its Eid al-Adha (the last day it may
  still be moved to), then the next, judged on the announcements and estimates only, so the admin's
  own dates never turn the page to another year.
- `kiosk[].level`: `GOOD`, `WARNING`, `BAD` or `INFO`. An offline TV adds a `WARNING` row from three
  days before a Ramadan or Eid date that is still the estimate, and a `WARNING` row for each of
  today's iqamahs the wall moved from its setting (a fixed time that does not suit today's adhan, or
  one before the end of the adhan screen and the dua after it, which waits for them), as on its own kiosk page.

### `POST /api/clock`

Sets or confirms the TV's clock from the phone, the reliable clock in an offline mosque. The body is
one of:

- `{ "epochMillis": n }`: the phone's `Date.now()` when the button was pressed. The TV takes it as
  the time now (`source` becomes `PHONE`) and keeps it as a correction of its own clock until that
  clock is changed or reset (a power cut on a box without a clock battery). Refused (`ok: false`, «لم تُضبط الساعة: تاريخ الهاتف غير صحيح») when it cannot be
  right: before 1 September 2026 or after 2100.
- `{ "confirm": true }`: the time the TV shows is right (the page offers it only when it agrees with
  the phone). Refused when the TV's clock cannot be right.

Each answer says what happened: done, refused (the messages above), «الشاشة مشغولة، أعد المحاولة»
when the screen's main thread did not take the change within 5 s (then nothing changed: a change
that started runs to its end and is reported as it went), or «تعذّر ذلك على الشاشة الآن، أعد المحاولة».

Anything else (another field, a number as a string, both at once) is `400 { "error": "طلب غير صالح" }`.
The body is at most 256 bytes. `message` is Arabic, for the page's toast.

### `GET /api/adhkar`

The reviewed texts that ship with the app, which the mosque's lists pick from, and the bundled
lists the screen shows when the mosque changed nothing:

```json
{
  "afterSalahMaxMinutes": 30,
  "lists": {
    "afterSalah": ["salah_istighfar", "salah_salam", "salah_la_mani", "salah_hundred", "ayat_kursi", "surah_ikhlas", "surah_falaq", "surah_nas"],
    "ticker": ["subhanallah_bihamdih", "kalimatan_khafifatan", "la_hawla_quwwata", "salawat_ibrahimiyya"]
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
  reading, three times at most. A page holds at most 260 characters and 6 lines; the pages of a
  text are as even as its verse ends, pause marks and punctuation allow (`pages()` in
  `views/adhkar.js` is the TV's `AdhkarPacer.pages`, line for line).
- `afterSalahMillis`: how long the TV shows the text after the prayer, at its `count`.
  `tickerMillis`: at least how long it stays in the ticker (every page or step at least 12 s, longer
  only for its reading time; the announcements between texts add time). The ticker pages and never
  scrolls: it shows every text on one line (line breaks become spaces), set smaller or on two lines
  when long, and a text too long even for that in turns of up to three lines, with no extra time. A
  long source is cited by its first clause there. The page adds them up to show about how long the
  adhkar last.

### The settings file the forms build

The same format as the USB key (see `INSTALL_AR.md`). Every section is optional, and so is every
field of `mosque`, `display` and each prayer. «الإقامة» and «المسجد» send only the fields the admin
changed («الإقامة» sends the adhan screen's minutes as `display.adhanScreenMinutes`), and «الأذكار» only the lists, so a change made meanwhile from the remote, a USB key or
another phone is kept. «رمضان والعيد» sends only the dates the admin changed (`null` for one set
back to automatic), so a date set meanwhile on the remote is not reverted; «الإعلانات» sends the
whole list, and «متقدّم» the whole file:

```json
{
  "mosque": { "name": "مسجد النور", "delegation": 615 },
  "display": { "theme": "horizon", "weather": true, "backgrounds": true, "announcements": true,
               "slideSeconds": 15, "announcementsEveryMinutes": 15, "nightScreen": true, "adhanScreenMinutes": 2 },
  "prayerTimes": { "fajrAngle": 17.5, "ishaAngle": 18, "asrShadow": 1, "dhuhrMinutes": 7, "maghribMinutes": 2, "elevation": true,
                   "adjust": { "fajr": 0, "dhuhr": 0, "asr": 0, "maghrib": 0, "isha": 2 } },
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
  5-60. `announcementsEveryMinutes`: 0-120, 0 for after the prayer's adhkar only. A pass starts only
  if it ends 10 minutes before the next adhan, and then runs to its end; a list changed during a
  pass starts a new pass, under the same rule. `adhanScreenMinutes`: how long the adhan screen lasts,
  1-5, 2 by default; it shows, all at once, what the listener says while the muezzin calls (Muslim
  385, with two lines more at Fajr), and the dua after the adhan (Bukhari 614) follows it alone for
  one minute, which is not a setting, except before the Friday khutba (`dua` below). The «الإقامة»
  page edits it, with the iqamah, and says what it means for the iqamah.
- An iqamah before the end of the adhan screen and the dua's minute (`"+1"` or `"+2"` with the
  2-minute default, or a fixed time that close to the adhan) waits for their end, so the wall never
  goes black while the muezzin still calls or before the dua ends; `today.prayers` gives that later time,
  and the TV's kiosk page names it. A Jumu'a without the dua (`dua: false`) waits for the adhan screen only.
- `iqamah`: `"+N"` minutes after the adhan (after sunrise for the Eids, 1-90), or a fixed `"HH:MM"`.
  `duration`: minutes of prayer (the black screen), 1-90. A fixed time that cannot apply on a day
  (before the adhan, or more than 90 minutes after it) falls back to the minutes the TV kept behind it.
- `held`: `false` for Jumu'a (`jumua`) or an Eid prayer (`eidFitr`, `eidAdha`, or `eid` for both)
  the mosque does not hold: Fridays keep Dhuhr and its countdown, with no khutba screen, and an Eid
  has no Eid prayer (the greeting stays). Only in `prayers`; the TV writes it when `false` (and
  `true` in the undo snapshot). The «الإقامة» page has one switch for Jumu'a and one for both Eids,
  and sends `held` only when a switch changed.
- `khutba`: Jumu'a's khutba length in minutes, 0-60 (`jumua` in `prayers` only). The khutba screen
  covers only that long before the iqamah, after the iqamah countdown; 0, the default, keeps it from
  the adhan to the iqamah. The TV writes it when set (and `0` in the undo snapshot); the page sends it
  only when changed.
- `dua`: whether the dua after the Jumu'a adhan shows for its minute before the khutba screen, `true`
  by default (the imam says it too); `false` puts the khutba screen (or the countdown before it) right
  after the adhan screen, and the iqamah then waits for the adhan screen only. Also `adhanDua`,
  `الدعاء` or `دعاء الأذان`; `jumua` in `prayers` only (the other prayers always have the dua). The TV
  writes it when `false` (and `true` in the undo snapshot); the page's box «الدعاء بعد الأذان قبل
  الخطبة», in the Jumu'a card, sends it only when changed.
- `prayerTimes`: the values the times are computed with, INM's official ones by default:
  `fajrAngle` and `ishaAngle` 15-20 by 0.5 (18), `asrShadow` 1 or 2 (1), `dhuhrMinutes` 0-15 after
  solar noon (7), `maghribMinutes` 0-10 after sunset (2), `elevation` `true` or `false` (`true`), and
  `adjust`, whole minutes −15 to +15 added to `fajr`, `dhuhr`, `asr`, `maghrib` or `isha` (a number,
  or `"+2"`/`"-1"`). A field left out keeps the TV's value; `null` returns a field (or, for the whole
  section, every value) to the official one. «حساب المواقيت» sends the whole section, or `null`. The
  TV writes it whole when its values are not the official ones (`null` in the full file), and keeps
  them with its settings (`PrefsManager.formula`). A file with only this section is accepted on its
  own (it does not need a `prayers` section) and leaves every other setting as it is.
- `ramadan`: only what changes in Ramadan; `null` returns a field to the usual setting.
- `islamicDates`: `null` returns a date to automatic. The dates are also checked together, with the
  TV's announced ones: a Ramadan of 28 or 31 days is refused (`DATES_CONFLICT`, with the reason and
  which date to move too); across several months an announcement of another event keeps its day,
  so a mosque's own Eid al-Fitr may stand beside the nation's Eid al-Adha. The undo snapshot, sent
  back unchanged, is not checked again: undo returns the dates the TV had even if an announcement
  arrived since.
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
