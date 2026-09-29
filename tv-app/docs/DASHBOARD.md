# Mosque TV dashboard

The TV serves a small management site on the local network (the mosque's Wi-Fi, or a phone's
hotspot the TV joins). No internet is needed. An admin starts a session from the TV
(Settings → الإدارة من الهاتف), scans the QR code, and manages the screen from a phone or laptop.

- Every session has a new random token (in the QR code). All `/api/*` calls need it as `?t=TOKEN`.
  Ten wrong tokens close the session. The session ends after 15 minutes without requests.
- Every change goes through the same checked settings file as the USB key: the page builds a
  (partial) `mosque-tv.json`, the TV previews what would change (or lists the mistakes, in
  Arabic), and nothing is written until the admin applies it. The previous settings can be
  restored ("undo the last import").
- The page itself (`/`, `/app.js`, `/style.css`, `/views/*.js`) is static and holds no data;
  it lives in `app/src/main/assets/dashboard/`.

## API

All JSON is UTF-8. Times are the TV's time in Tunisia.

| Method | Path | Body | Answer |
|---|---|---|---|
| GET | `/api/state` | | the state below |
| GET | `/api/places` | | `[{ "id": 11, "name": "تونس", "delegations": [{ "id": 615, "name": "مدينة تونس" }] }]` |
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
  "mosque": { "name": "مسجد النور", "delegationId": 615, "delegationName": "مدينة تونس", "gouvernoratId": 11, "themeId": "midnight_navy" },
  "themes": [{ "id": "midnight_navy", "name": "أزرق داكن" }],
  "today": {
    "date": "2026-09-29", "hijri": "18 ربيع الثاني 1448 هـ", "sunrise": "06:12", "banner": null,
    "prayers": [{ "id": "FAJR", "name": "الفجر", "adhan": "04:46", "iqamah": "05:01" }]
  },
  "flow": { "phase": "IDLE", "prayer": null, "until": null },
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
- `weather`: `enabled` is the admin's choice; `text` is null when the TV has no recent weather
  (offline); `updated` is `HH:MM`. The data comes from Open-Meteo, which must be credited.
- `update`: `supported` is false in the Play build. `/api/undo` answers `{ "available": false }`
  when there is nothing to undo.
- The settings file the TV writes always has every prayer and the `mosque` and `display` sections.
- `islamicDates.events[].id`: `ramadanStart`, `eidFitr`, `eidAdha` (the keys of the settings
  file); `source` is `MANUAL`, `OFFICIAL` or `ESTIMATE`; `min`/`max` are the dates the file accepts.
- `kiosk[].level`: `GOOD`, `WARNING`, `BAD` or `INFO`.

### The settings file the forms build

The same format as the USB key (see `INSTALL_AR.md`). Every section is optional, and so is every
field of `mosque`, `display` and each prayer. The forms send only the fields the admin changed, so
a change made meanwhile from the remote, a USB key or another phone is kept:

```json
{
  "mosque": { "name": "مسجد النور", "delegation": 615 },
  "display": { "theme": "midnight_navy", "weather": true, "backgrounds": true, "announcements": true,
               "slideSeconds": 15, "announcementsEveryMinutes": 15 },
  "prayers": { "fajr": { "iqamah": "+15", "duration": 10 }, "isha": { "iqamah": "20:00", "duration": 10 },
               "jumua": { "iqamah": "+15", "duration": 15 }, "eidFitr": { "iqamah": "+30", "duration": 30 } },
  "ramadan": { "isha": { "duration": 75 }, "fajr": { "iqamah": null } },
  "islamicDates": { "1448": { "ramadanStart": "2027-02-08", "eidFitr": null } },
  "announcements": [{ "text": "درس بعد صلاة العشاء", "from": "2026-10-01", "until": "2026-10-31" }],
  "adhkar": { "afterSalah": { "mode": "append", "items": [{ "text": "...", "reference": "...", "count": 3 }] },
              "ticker": null }
}
```

- `iqamah`: `"+N"` minutes after the adhan (after sunrise for the Eids, 1-90), or a fixed `"HH:MM"`.
  `duration`: minutes of prayer (the black screen), 1-90.
- `ramadan`: only what changes in Ramadan; `null` returns a field to the usual setting.
- `islamicDates`: `null` returns a date to automatic.
- `announcements` replaces the whole list; `[]` removes them all.
- `adhkar`: `null` returns a list to the bundled, reviewed texts; `"append"` adds after them,
  `"replace"` shows only the mosque's texts. Every text needs a `reference`.
