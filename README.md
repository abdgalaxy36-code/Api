# RUH Flights — repository

Two apps live in this repository:

| folder | package | what it is |
|---|---|---|
| **root** | `com.ruh.flight.live` | **RUH Live Flights v2.0** — the current app: actual movements, daily statistics, history, notifications and GitHub backup. Read the section below. |
| `legacy-api-app/` | `com.ruh.flight.api` | the first app (multi-source, PF-only dot rules) — kept for reference |

The signed APK of the current app is in **`dist/ruh-flight-live.apk`**.

> The signing keystore is **not** published here. Copy your own
> `app/keystore.jks` back into the app folder before building, or the release
> build will fail to sign.

---

# RUH Live Flights — v2.0 (single source: Flightradar24)

`com.ruh.flight.live` — RUH (Riyadh) actual movements, daily statistics, history
and GitHub backup. Installs alongside the older `com.ruh.flight.api` app.

```
dist/ruh-flight-live.apk     signed release (versionCode 2, versionName 2.0)
build.sh                     build + sign + verify → dist/
```

---

## Screen layout (one scrolling page, top to bottom)

| # | Section | What it does |
|---|---|---|
| 1 | **TODAY** | Big total, then two tappable squares — `Departures` and `Arrivals`. Tapping a square opens that direction's full list for today right underneath (`▸` → `▾`). |
| 2 | **BEST DAYS** | Exactly two rows: `Today` and the single best day (`#1`), numbers only. |
| 3 | **HISTORY** | One row per day (`date · D · A · total ▸`). Tap a day → opens every flight of that day. |

Every opened list is **numbered** — `Flight 1 · SV1095`, `Flight 2 · …` — and
reads the **same stored rows** the counters count, so a square showing `7` opens
exactly 7 flights (the overnight landings included). Any flight anywhere → opens
its FR24 page `https://www.flightradar24.com/data/flights/{NUMBER}`.

Header: **refresh button** (⟳), **settings gear** (⚙), a **progress bar** and a
live status line that moves during every refresh
(`departures page 1/2   33%   D 24   A 15`). The TODAY total and both squares
update as the pages arrive, not only at the end.

Auto-refresh every **120 s** while the screen is visible. Coming back to the app
does **not** force a reload — the screen re-renders from what is stored and only
hits the network if the data is older than 120 s.

---

## Data source

FR24's schedule JSON for RUH:

```
https://api.flightradar24.com/common/v1/airport.json
  ?code=RUH
  &plugin[]=schedule
  &plugin-setting[schedule][mode]=departures|arrivals
  &plugin-setting[schedule][timestamp]={local midnight, unix}
  &page=N
```

* `timestamp` must be **local midnight** — the window is `[today, today+2]`.
* FR24's API host answers **HTTP 403** to Java/Android TLS (Cloudflare), so the
  app proxies the call:
  1. **Jina** `https://r.jina.ai/{fr24-url}` — returns the raw JSON (plain text
     or a JSON envelope, both handled). Jina answers **403 to browser
     user-agents**, so the app sends `User-Agent: ruh-flight-live/2.0`.
  2. **Firecrawl** `POST https://api.firecrawl.dev/v1/scrape` — keyless tier is
     hard rate-limited (HTTP 429), used only as a last resort with backoff
     (8 s / 20 s / 45 s).
* A unique `&cb=<nanoTime>` is appended to the target URL so every refresh is a
  fresh fetch (both proxies cache by URL).
* Pages are fetched until the last scheduled time reaches midnight+24 h
  (usually 2 pages per direction = 4 requests per refresh).

### Counting rule (real movements only)

A flight counts only if it **actually happened**: `time.real.*` is a non-zero
epoch and the status is `departed` / `landed`. `scheduled`, `estimated`,
`unknown`, `delayed`, `diverted` and `canceled` rows are dropped at parse time.
Everything is in the **device timezone** (`ZoneId.systemDefault()`).

---

## Status badge colours

FR24 paints the badge by **how late** the flight is, not by whether it happened.
Measured on live RUH data:

| colour | median delay | range | meaning shown |
|---|---|---|---|
| green | +10 m | −41 … +15 m | `OnTime` |
| orange | +25 m | +18 … +46 m | `Delayed` |
| red | +123 m | +49 … +215 m | `Severe` |
| gray | — | no schedule known | `Unrated` |

The app derives the colour from `real − scheduled` with those thresholds, so
live and historic rows look identical. The badge itself carries the status word
**and** the time, like FR24 does: `DEPARTED  14:47`, with the one-word meaning
(`OnTime` / `Delayed` / `Severe`) printed beside it in the same colour.

---

## Older days (how history was obtained)

FR24's airport board only shows the last ~24 h. Older days come from FR24's
**per-flight history pages**: `www.flightradar24.com/data/flights/{NUMBER}`
renders a `FLIGHTS HISTORY` table covering roughly 13 dates (7 past + future).

`tools/backfill_history.py` walks every flight number RUH schedules (259 of
them), fetches each history page sequentially (FR24 answers 429 to bursts, so
there is a 2 s delay and an on-disk cache in `tools/cache/`), and parses the
rows:

* real departure = `FROM` starts with *Riyadh* **and** `ATD` is a time
* real arrival = `TO` starts with *Riyadh* **and** status starts with *Landed*
* `STD` / `STA` give the scheduled time → delay → badge colour

Result: **29 Oct – 3 Nov 2026, 214 flights, 0 failures**, shipped as
`app/src/main/assets/backfill_flights.json` (+ `backfill.json` for the totals).
25–28 Oct are outside FR24's history window and were deliberately skipped.

---

## How a day becomes history

Nothing is copied or migrated — the tables are keyed by the local date, so a day
becomes history the moment the device clock passes midnight.

1. Every successful refresh writes **today's** rows under today's key
   (`stats.record(todayStr, …)` + `stats.replaceDayFlights(todayStr, …)`).
2. After midnight `Clock.todayKey()` returns the new date, so the app starts
   writing a **new** row; yesterday's rows stay untouched under yesterday's key
   and show up in HISTORY with their count and their flight list.
3. **Repair of the previous day — the fix for "the phone was off all day and
   the count came out wrong".** FR24's schedule window reaches back exactly
   one day, so once a day the app re-fetches **that whole day** with
   `timestamp = yesterday 00:00` — not noon: a noon window threw away every
   morning movement — merges the rows into yesterday's existing rows and
   recounts it. It runs whether or not today already has a row, it is marked
   done only when **both** directions came back, and otherwise it retries on
   the next refresh. Flights that were scheduled yesterday but actually moved
   after midnight are counted into **today**. Log line:
   `repair 2026-11-05: +41 dep +35 arr (+0 into today)`.

4. **Background refresh — the fix for "the app does not update while the phone
   is unused".** Every **hour** an alarm fires `Sync.refresh()` even with the
   screen off (`setAndAllowWhileIdle`) and merges today's window into today's
   rows — frequent enough to lose nothing, cheap enough for battery and fetch
   quota. It follows the **auto-refresh** switch. Log line:
   `bg refresh D=26 A=15 T=41 pages=3/3`.

So a day is collected **while it happens** and repaired **after it ends**, and
neither needs the phone to be awake.

---

## Storage

SQLite `ruh_stats.db` (version 3):

| table | contents |
|---|---|
| `daily_stats` | `date` PK, `departures`, `arrivals`, `total`, `updated_at` |
| `daily_flights` | `date, number, direction, epoch, scheduled, origin, destination, airline, terminal, gate, aircraft, status, color` — PK `(date, number, direction, epoch)` |

Seeded once from the assets (`INSERT OR IGNORE`), so app-collected rows are
never overwritten.

### Data check (Settings → Run check)

Two independent checks in one report.

**1. Internal — does the database agree with itself?** Scans every stored row
and proves the data is real-time only:

```
Scanned 256 flight(s) across 7 day(s)
PASS  no missing real time
PASS  no scheduled/unknown rows
PASS  every day's count matches its total
ALL CHECKS PASSED
```

**2. Against FR24 — is the number actually right?** The internal check cannot
catch a day that lost its whole morning: four rows still agree with a total of
four. So the check then asks FR24 what really happened on the days its
schedule window still covers (yesterday and today) and prints a verdict per
day:

```
AGAINST FR24 SOURCE
  2026-11-05: MISMATCH  app=4  FR24=76
  2026-11-06: MATCH

Only the last day or two can be compared: FR24's
schedule window no longer reaches older dates.
```

The internal part also runs automatically once per launch (visible in
logcat); the FR24 comparison runs only when you press the button, because it
costs a handful of requests.

### Fix data (Settings → Fix data)

The repair button for exactly the case above. It pulls yesterday and today
again, and for **each day where the check says MISMATCH** it rebuilds that day
from FR24 — the day's rows are replaced by FR24's own rows and the total is
counted back from them:

```
FIXED 1 day(s) from FR24
  2026-11-05: already correct (75) — untouched
  2026-11-06: 8 -> 12  FIXED (FR24=12)
```

Days that already match are printed as untouched — **it never overwrites good
data**, and if everything matches it reports
`NOTHING TO FIX — every day already matches FR24`.

Afterwards the screen is safe: today's refresh now **merges** its rows instead
of replacing them, so a correct day cannot be clobbered by a later, shorter
fetch, and both TODAY and HISTORY read their number back from the stored rows.

---

## Settings screen

* **Run check** — the verification above: it scans every stored flight row and
  checks three things —
  1. every row has an actual timestamp (`epoch > 0`) — nothing stored without a
     real time,
  2. no row still carries a planning status (`SCHEDULED` / `UNKNOWN` /
     `PREDICTED` / `CANCELED`) — only real movements are stored,
  3. for every day in `daily_stats`, the number of stored flight rows equals the
     recorded `total` — catches lost rows or a count without its flights.

  It prints a PASS/FAIL per check, lists the offending rows (first 20) and ends
  with `ALL CHECKS PASSED` or `CHECKS FAILED`. It then compares yesterday's and
  today's totals against FR24 itself and prints `MATCH` or
  `MISMATCH  app=…  FR24=…` per day — the only way to notice a day that lost
  data, since such a day is still internally consistent. The internal part also
  runs automatically once per launch (see logcat); the FR24 comparison runs only
  on the button.

* **Fix data** — rebuilds only the days the check found wrong, straight from
  FR24 (see *Fix data* above). Days that already match are reported as
  untouched. Use it after `Run check` shows a MISMATCH.

* **Service credits** — one compact coloured row per service:

  | row | meaning |
  |---|---|
  | `Jina  19 / 20 left` | green = plenty, orange = below half, red = exhausted |
  | `Firecrawl  no credits` | red when the keyless tier is empty |

  *Check credits* sends one tiny probe to each service and re-reads
  `x-ratelimit-limit` / `x-ratelimit-remaining` (Jina) or
  `x-firecrawl-ratelimit-*` (Firecrawl, needs a key). A free Firecrawl API key
  can be pasted in to restore that service's credits.

* **Refresh** — `Auto refresh` on/off plus an interval picker
  (1 / 2 / 5 / 10 / 15 / 30 min, default **5 min**). The app also only
  refetches when you come back to it if the data on screen is older than that
  interval.

* **Notifications** — four switches, each on its own channel:

  | switch | fires | shows |
  |---|---|---|
  | New best day | when a day's total passes the previous record | `date   total` and the old best |
  | Day summary at 00:00 | 00:02 after every midnight | the finished day's `D / A / total` |
  | Today so far | every 3 hours | today's `D / A / total` |
  | Backup reminder | weekly, if no backup was made | how long ago the last backup was |

  If the day-summary alarm was missed because the phone was asleep, the
  next-day repair posts it instead.

  A fifth alarm carries **no** notification: the background refresh described
  above, which keeps today's counts moving while the phone sleeps.

* **GitHub backup** — repository / branch / file name / token, plus
  **Test** (checks the token can reach the repo and prints whether it is
  private and its default branch), **Backup**, **Restore** and the
  **last backup date and time** with its result.
* **GitHub backup** — repository / branch / file name / personal access token.
  * **Backup** pushes every stored flight and every daily total (flights data
    only — no app code) to the repo through the GitHub Contents API.
  * **Restore** pulls that file back and replaces the local tables.
  * Defaults: repo `abdgalaxy36-code/ruh-flights-backup`, branch `main`,
    file `ruh-flight-live-data.json` (the old app's `ruh-flight-data.json` is
    left untouched).
  * The token is stored in the app's private `SharedPreferences` only.
    Create one at <https://github.com/settings/tokens> with `repo` scope.

Backup payload:

```json
{ "version": 2, "app": "ruh-flight-live", "exported_at": "…",
  "stats":  [ { "date", "departures", "arrivals", "total", "updated_at" } ],
  "flights":[ { "date", "number", "direction", "epoch", "scheduled",
                "origin", "destination", "airline", "terminal", "gate",
                "aircraft", "status", "color" } ] }
```

---

## Logging

```bash
adb logcat -s Fr24Live
```

```
Fr24Live: render days=7 today=2026-11-04 total=42
Fr24Live: firecrawl departures ok (51234 bytes)
Fr24Live: refresh dep=26 arr=15 pages=2/2 via=firecrawl err=null
Fr24Live: stats record 2026-11-04 D=26 A=15 T=41
Fr24Live: flights stored for 2026-11-04: 41
Fr24Live: data check: … ALL CHECKS PASSED
```

---

## Build / install

```bash
cd ~/ruh_flight_live_build
sh build.sh
adb -s <device> install -r dist/ruh-flight-live.apk
```

Needs `JAVA_HOME` = JDK 21, `ANDROID_HOME` = `~/android-sdk`,
`aapt2FromMavenOverride` = Termux aapt2, build-tools 36.0.0 for `apksigner`.

---

## Files

```
app/src/main/java/com/ruh/flight/live/
├── Fr24Api.kt        fetch (direct + Firecrawl), parse, real-time filter
├── Clock.kt          device-time helpers (today / day key / is-today)
├── StatsStore.kt     SQLite daily_stats + daily_flights, seed, verify, restore
├── StatusColor.kt    delay → badge colour, colour → one-word meaning
├── FlightAdapter.kt  row rendering (badge with word + time)
├── GithubBackup.kt   export/import JSON + GitHub Contents API push/pull
├── SettingsActivity.kt  data check + backup + restore
└── MainActivity.kt   single-page UI, progress bar, refresh, FR24 links

app/src/main/res/layout/
├── activity_main.xml      header + progress bar + page body
├── activity_settings.xml  settings, check, backup
├── item_stat_today.xml    TODAY card (tappable squares)
├── item_day.xml           history row
├── item_flight.xml        flight row with coloured badge
├── item_stat.xml          best-day row
└── item_label.xml         section label

tools/backfill_history.py  FR24 flight-history scraper → assets
tools/cache/*.html         cached history pages (259)
```
