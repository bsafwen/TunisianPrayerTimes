# Official-table collectors (plan 9.3, milestone M0)

Small read-only scripts that save the official prayer tables the fits need, as published, before they disappear. Most
of these sources show only today or the current month, so a day not collected is lost for good. Ramadan 1448 (from
about 2027-02-08) is the key window: the collectors must be running by mid-January 2027.

- **Polite and read-only:** one request every 3 seconds, a plain User-Agent, GET or the sites' own public POST forms
  only. Egypt's monthly view and Jordan's search are form submissions that wait for the owner's go-ahead, and are not
  collected here.
- **Network rules:** every request goes through the system proxy and verifies TLS. Never turn verification off.
- **Storage:** raw files are saved byte for byte under the git-ignored `test-data-local/official/<cc>/`, with one row
  per file in that folder's `MANIFEST.tsv`: `file`, `bytes`, `sha256`, `fetched` (UTC), `source`, `note`. Nothing
  collected is committed (A7).
- **Repeats:** identical bytes are never saved twice. A page that changed during the day is kept beside the first copy,
  with its fetch time appended to the name.

```
python scripts/collectors/run.py --list                  # the collectors, their country and cadence
python scripts/collectors/run.py --cadence daily         # what the daily task runs
python scripts/collectors/run.py --cadence monthly       # what the monthly task runs
python scripts/collectors/run.py --only ye               # one collector, whatever its cadence
python scripts/collectors/gaps.py --days 14              # the weekly gap check
python scripts/collectors/test_common.py                 # offline tests (each collector has its own test_<name>.py)
```

`run.py` exits 1 when a collector failed, and the others still run. `gaps.py` lists each daily collector's days without
a saved file over the last 14 days.

## The owner's scheduled tasks

Run once, in PowerShell, from the repository root of the main checkout, after the collectors are committed there. Use
the Python the project uses (`scripts/find-python.sh` finds it), and replace `python` below with its full path if it is
not on the PATH. `-StartWhenAvailable` runs a task as soon as possible after a start missed while the PC was off.

```powershell
$repo = (Get-Location).Path
$settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -ExecutionTimeLimit (New-TimeSpan -Hours 2)
$daily = New-ScheduledTaskAction -Execute "python" -Argument "scripts\collectors\run.py --cadence daily" -WorkingDirectory $repo
Register-ScheduledTask -TaskName "TunisianPrayerTimes collectors (daily)" -Action $daily -Settings $settings `
    -Trigger (New-ScheduledTaskTrigger -Daily -At 10am)
$monthly = New-ScheduledTaskAction -Execute "python" -Argument "scripts\collectors\run.py --cadence monthly" -WorkingDirectory $repo
Register-ScheduledTask -TaskName "TunisianPrayerTimes collectors (monthly)" -Action $monthly -Settings $settings `
    -Trigger (New-ScheduledTaskTrigger -Weekly -WeeksInterval 2 -DaysOfWeek Monday -At 10:30am)
```

The monthly sources are fetched every two weeks, so no Hijri month (29 or 30 days) passes without a run: Morocco shows
the current Hijri month only. The monthly run also takes the yearly collector (Iraq), which asks nothing before 2027
and then only for the days still missing.

On 2026-10-16 both tasks were registered on the owner's PC, pointing at this worktree, with their output appended to
`test-data-local/official/collector-logs/daily.log` and `monthly.log`; after committing, re-register them from the main
checkout (or edit the tasks' start folder).

Check the gaps once a week (`gaps.py`), and look at the tasks' last result in Task Scheduler: 1 means a collector failed.

## The collectors

Every collector has an offline `test_<name>.py` (research samples where they exist, synthetic pages otherwise). None
has run live yet: the first run is the owner's call.

| Name | Country, cadence | Saves (under `official/<cc>/`) | Gaps and notes |
|---|---|---|---|
| `ly` | Libya, daily | `<yyyy>/<mm>/ly_awqaf_<date>_<city>.json`: Awqaf's row for each of its 22 cities (a POST per city to its `admin-ajax.php`); `ly_ifta_<first day>.json`: Dar al-Ifta's Tripoli for today and the next 7 days | a missed day is lost for 21 cities; Tripoli's survives if any run happened in the 7 days before. The month's first run re-reads the homepage's city list and fails if it changed. The fit (`fits/ly_awqaf`) needs a reader for these files |
| `eg` | Egypt, daily | `praytimes/praytimes_<date>.html`: EGSA's all-cities page (GET) | read by `fits/eg_egsa --wayback test-data-local/official/eg/praytimes`; unreadable pages go to `praytimes-unparsed/`. The monthly view and the 4 menu cities missing from the table need form submissions: not collected |
| `ma` | Morocco, monthly | `<Hijri year>-<month>/ville_<id>.html`: Habous's current Hijri month for each of 191 localities | read by `fits/ma_habous` as saved; a run fetches only the localities still missing, and a revised table goes to `r2/` |
| `qa` | Qatar, daily | `cal/cal_YYYYMMDD.html`: QCH's `/cal` page (six places, the only source of Mesaieed and Halul), dated by Qatar's day; each new wall-calendar PDF it links, into `pdfs/` | read by `fits/qa/fit.py --cal test-data-local/official/qa/cal`; unreadable pages go to `cal-unparsed/` |
| `gaza` | Gaza (`ps/gaza/`), daily | the Telegram previews of Quran Radio's and the ministry's «مواقيت» and «إمساكية» posts when they hold new posts; their month-table images; palwakf.ps's page (footer: today's five times) | a preview shows the latest 20 posts, so a pause over about 20 days needs `?before=` paging by hand. The fit needs parsers for these pages |
| `ye` | Yemen, monthly | `dYYYYMMDD.json`: the Tebyan JSON of the 31 locations for every date from 2026-05-21 to today + 31 not yet saved (at most 400 requests a run) | empty answers (10 days so far) are asked again each run; future dates likely answer empty until they come |
| `iq` | Iraq, yearly (by hand in January 2027) | `<year>/dws_YYYYMMDD.json`: Baghdad's row for each day, `is_closest` rows dropped | fails after 12 requests while the year is not served: run again later. The fit needs a loader for 2027 |
| `mk` | North Macedonia, daily | `<yyyy>/<mm>/bfi_<date>.html`: bfi.mk's home page; the note repeats the five «Koha (Shkup)» times | undated page: the fetch time dates it, and a missed day is lost |
| `bg` | Bulgaria, monthly | `<yyyy>/<mm>/<city>_<yyyy-mm>.html`: the Chief Mufti's month for each of 48 cities | the city is chosen through the page's form, which the owner allowed on 2026-10-16. If the choice does not reach the server, only `default_<yyyy-mm>.html` is kept and the run fails |
| `si` | Slovenia, monthly | `feed_<yyyy-mm>.json`: vaktija.si's feed (Ljubljana for three months, the five places' offsets) | use `www.vaktija.si` (the bare host's certificate does not match); keep the task through August 2027 |

## By hand or one-off (not automated)

These sources are images, PDFs or one-off downloads, collected by hand when they appear (plan 9.3):

- **Algeria:** the yearly calendar PDF (images).
- **Mauritania:** each Ramadan's imsakiya.
- **Jordan:** opendata.gov.jo. The awqaf.gov.jo search is a form submission: owner's go-ahead first.
- **West Bank:** darifta.ps imsakiyas.
- **Lebanon:** each Ramadan's imsakiya.
- **Syria:** each Ramadan's Telegram images, plus the app «مسجدك» read by hand.
- **Kuwait:** the six sheets still to transcribe, and a request to the ministry for its PDF.
- **Bahrain:** the 1449 calendar, about June 2027.
- **UAE:** the Open Data API, after the owner reads its terms; never the website's embedded key.
- **Saudi Arabia:** the KACST API's one-off sample.
- **Oman:** mara.gov.om's all-zone pages.
- **Sudan, Somalia, Djibouti, the Comoros:** nothing is online; the owner asks the authorities.
