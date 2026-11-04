# RuH Flight API — Build Folder

Standalone, buildable copy of the **RuH Flight API** Android app (`com.ruh.flight.api`).
It counts only **actual** flight movements at Riyadh International (RUH / OERK).

Everything in this folder is a working copy — the original project at
`~/ruh-flight-api-app` is untouched.

---

## Layout

```
ruh_flight_api_build/
├── build.sh                       # build + sign + verify → dist/ruh-flight-api.apk
├── settings.gradle.kts            # Gradle project definition
├── build.gradle.kts               # AGP 8.7.3 / Kotlin 2.0.21
├── gradle.properties              # AndroidX, nonTransitiveRClass
├── local.properties               # sdk.dir → ~/android-sdk
├── gradlew / gradlew.bat          # Gradle 9.7.1 wrapper
├── gradle/wrapper/                # wrapper jar + properties
├── offline_fr24_test.py           # offline FR24 parse test (root copy)
│
├── app/
│   ├── build.gradle.kts
│   ├── keystore.jks               # release signing key (alias: ruh)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/ruh/flight/api/
│       │   ├── MainActivity.kt            # UI, per-source logcat counters
│       │   ├── Models.kt                  # FlightRecord, DayFetchResult
│       │   ├── JinaClient.kt              # Jina Reader HTTP wrapper
│       │   ├── backup/GitHubBackup.kt     # GitHub backup/restore
│       │   ├── collectors/
│       │   │   ├── Base.kt                # BaseCollector + fetch helpers
│       │   │   ├── AirLabsCollector.kt     # AirLabs direct API
│       │   │   ├── Fr24Collector.kt        # FR24 (Jina → Firecrawl)
│       │   │   ├── FlightAwareCollector.kt # FlightAware (Jina → Firecrawl)
│       │   │   ├── FlightStatsCollector.kt # FlightStats (Jina → Firecrawl)
│       │   │   └── PlaneFinderCollector.kt # PF hidden API (rewritten)
│       │   ├── database/FlightDbHelper.kt  # SQLite persistence
│       │   ├── processing/
│       │   │   ├── Deduplicator.kt        # cross-source dedup (10 min)
│       │   │   └── TimeUtil.kt            # device-timezone helpers
│       │   └── worker/CollectionWorker.kt # WorkManager 15-min collection
│       └── res/                           # layouts, drawables, values
│
├── tools/
│   ├── offline_fr24_test.py       # offline FR24 parse test
│   ├── offline_pf_test.py         # offline PF parser + rule test
│   └── test_data/                 # 22 captured PF API HTML fixtures
│
└── dist/
    ├── ruh-flight-api.apk         # signed release APK
    └── ruh-flight-api.apk.idsig   # v4 signature (install optional)
```

---

## Build

```bash
cd ~/ruh_flight_api_build
./build.sh
```

`build.sh` compiles, signs with `app/keystore.jks`, verifies the signature and
writes `dist/ruh-flight-api.apk`.

Manual equivalent:

```bash
export ANDROID_HOME=$HOME/android-sdk
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk

./gradlew :app:assembleRelease --no-daemon \
  -Pandroid.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2

bash $ANDROID_HOME/build-tools/36.0.0/apksigner sign \
  --ks app/keystore.jks --ks-key-alias ruh \
  --ks-pass pass:ruh2026 --key-pass pass:ruh2026 \
  --out dist/ruh-flight-api.apk \
  app/build/outputs/apk/release/app-release.apk
```

> **Termux notes**
> - `aapt2FromMavenOverride` is required — the Maven aapt2 binary does not run here.
> - Use **build-tools 36.0.0** for `apksigner`; the 37.0.0 script has a broken shebang.
> - Keep `app/build/tmp/kotlin-classes` clean if incremental Kotlin compilation
>   goes corrupt — delete it and rebuild.

### Known build blocker (fixed)

The build previously failed with a misleading AAPT2/resource error:

```
No package ID 7f found for resource ID ...
```

The real cause was a Kotlin visibility error in `PlaneFinderCollector.kt`:

```
e: ...PlaneFinderCollector.kt:281:9 Function 'public' exposes its
   'private-in-class' return type 'PfRow'.
```

`PfRow` was declared `private data class` but returned from the public
`parseBoardHtml()`. Fix: declare `PfRow` without the `private` modifier
(`PlaneFinderCollector.kt:89`). The build then completes with no AAPT2 errors.

---

## Sources & counting rules

| Source | Transport | Notes |
|---|---|---|
| Flightradar24 | Jina Reader → Firecrawl fallback | `page=-1` board gives real actuals |
| FlightAware | Jina Reader → Firecrawl fallback | public board pages |
| FlightStats | Jina Reader → Firecrawl fallback | scheduled board |
| AirLabs | direct HTTPS | schedules API, no scraper needed |
| **Plane Finder** | **direct HTTPS hidden API** | primary actual-movement source |

### Plane Finder

Hidden endpoint (returns raw `<tr>` HTML):

```
https://planefinder.net/api/airport/RUH/{arrivals|departures}/{unixTs}/offset/{0..-4}
```

Required headers: browser `User-Agent`, `Referer:
https://planefinder.net/data/airport/RUH/{dir}`, `X-Requested-With: XMLHttpRequest`.
Offsets `0`…`-4` cover the recent past windows. The API is rate limited — the
collector sleeps between calls.

**PF-only counting rules** (`PlaneFinderCollector.fetchDay`):

| Direction | Accepted |
|---|---|
| Departures | past time **+** `Departed HH:MM` **+** RED dot (`dot late`) |
| Arrivals | past time **+** `Landed HH:MM` **+** RED (`late`) or GREEN (`ontime`) dot |

- GREEN (`dot ontime`) departures are ignored.
- Future / scheduled rows (`Departing`, `Arriving`) are never counted.
- `dot cancelled` rows are **never** counted and are added to the purge set
  (`DayFetchResult.cancelledFlightNumbers`) so existing records are removed.
- Arrivals are checked against the flight's history page
  (`/data/flight/{number}`); if the final `To` is not `RUH` the flight is
  treated as diverted and skipped.

### Dot colours (from PF `data-site.css`)

| Class | Colour | Meaning |
|---|---|---|
| `dot ontime`, `dot early` | `#34c658` | green |
| `dot late` | `#ff4848` | red |
| `dot cancelled` | `#000000` | black |

---

## Other rules

- Only actual movements are stored; scheduled/estimated times are never
  recorded as actuals.
- Cancelled flights never appear as arrived.
- Cross-source dedup window: **10 minutes**.
- Collection runs every **15 minutes** via WorkManager.
- History starts at `2026-10-25` (`HISTORY_START`).
- Times use the **device system timezone only** (`ZoneId.systemDefault()`;
  device TZ is Riyadh) — no hardcoded offsets.
- Links in the app point back to **FR24** only.
- versionCode `1`, versionName `"1.0"`.

---

## Logging

All collectors log to logcat for on-device debugging:

- tag `RuHFlights` — per-source counted/today/error/pages, purge targets
- tag `PlaneFinder` — PF offset pages, rule skips, final totals

```bash
adb logcat -s RuHFlights PlaneFinder
```

Do **not** open or screenshot the app while debugging — read the log instead.

---

## Tests

```bash
cd tools
python3 offline_pf_test.py      # PF parser + dot/time rules
python3 offline_fr24_test.py    # FR24 parse harness
```

`offline_pf_test.py` ports the exact regexes and filters from
`PlaneFinderCollector.kt` and checks them against the captured fixtures in
`tools/test_data/`:

```
fixtures            : 22
raw <tr> rows       : 96
parsed rows         : 96
countable (rules)   : 30
cancelled (purge)   : 49
PASS: PF parser matches PF-only dot/time rules on all fixtures
```

It asserts that every row carrying a flight link is parsed, that only
`ontime`/`early`/`late`/`cancelled` dots appear, that `Landed`/`Departed` rows
always carry an actual time, and that no cancelled flight is ever countable.

---

## Install

```bash
adb install -r dist/ruh-flight-api.apk
```

or copy to the device and install manually.

---

## Jina alternatives (researched)

If a Jina key is unavailable, these were evaluated as replacements:
Firecrawl (works keyless — `POST https://api.firecrawl.dev/v1/scrape` with
`{"url":..., "formats":["html"]}`), Crawl4AI Cloud, ScrapingBee, Browserless,
Zyte, ScrapingAnt, Tavily, Exa AI, Diffbot, Apify, ScraperAPI,
WebScrapingAPI, Microlink, ZenRows, Trafilatura, Cheerio, Selenium,
Playwright.

**Firecrawl** is the active fallback for the three Jina-based collectors.
