#!/usr/bin/env python3
"""
Backfill RUH daily movement stats from FR24 flight-history pages.

The schedule API rejects any timestamp outside [today, today+2], so past days
cannot be read from it. Each /data/flights/{NUMBER} page instead ships a
server-rendered FLIGHTS HISTORY table covering ~13 dates, which is enough to
rebuild the days the API will not give us.

Counting rules (validated against live pages):
  real departure = FROM is Riyadh AND ATD is a real time (not "—")
  real arrival   = TO   is Riyadh AND status starts with "Landed"

Only days strictly before today are emitted; today is owned by the live app.
"""
import json
import os
import re
import sys
import time
import datetime
import zoneinfo
from collections import Counter

import html as html_lib
import urllib.request
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.dirname(HERE)
OUT = os.path.join(BUILD, "app", "src", "main", "assets", "backfill.json")
OUT_FLIGHTS = os.path.join(BUILD, "app", "src", "main", "assets", "backfill_flights.json")
CACHE = os.path.join(HERE, "cache")
SCHED_DIR = "/data/data/com.termux/files/usr/tmp/opencode"

# FR24 answers 429 quickly, so stay polite: one request at a time with a
# real gap, and back off hard when throttled. Pages are cached on disk so an
# interrupted run resumes without refetching anything already seen.
DELAY = 2.0
BACKOFF = [10, 30, 60, 120, 240]

RUH = zoneinfo.ZoneInfo("Asia/Riyadh")
TODAY = datetime.datetime.now(RUH).date()
# FR24 ships a fixed 13-row history per flight (7 future + ~6 past), so the
# data thins out below 29 Oct: 28 Oct carries only 60 rows against 137+ on
# 29 Oct, and 25-27 Oct are outside the window entirely. Start where the
# counts are demonstrably complete; older days can be added later if FR24
# widens the window or another source appears.
FIRST_DAY = datetime.date(2026, 10, 29)

UA = ("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

DATE_RE = re.compile(r"^\d{1,2} [A-Z][a-z]{2} 20\d{2}$")
AIRPORT_RE = re.compile(r"^.+ \([A-Z]{3}\)$")
MONTHS = {m: i + 1 for i, m in enumerate(
    ["Jan", "Feb", "Mar", "Apr", "May", "Jun",
     "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"])}


def _fetch(url):
    req = urllib.request.Request(url, headers={
        "User-Agent": UA,
        "Accept": "text/html,application/xhtml+xml",
        "Accept-Language": "en-US,en;q=0.9",
    })
    with urllib.request.urlopen(req, timeout=30) as r:
        return r.read().decode("utf-8", "replace")


def get(number):
    """Fetch one flight-history page, cached on disk, throttled + backed off."""
    path = os.path.join(CACHE, f"{number}.html")
    if os.path.exists(path):
        with open(path, encoding="utf-8", errors="replace") as f:
            body = f.read()
        if "FLIGHTS HISTORY" in body:
            return body, True

    url = f"https://www.flightradar24.com/data/flights/{number}"
    last = None
    for i, wait in enumerate([0] + BACKOFF):
        if wait:
            time.sleep(wait)
        try:
            body = _fetch(url)
            if "FLIGHTS HISTORY" in body:
                os.makedirs(CACHE, exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(body)
                time.sleep(DELAY)
                return body, False
            last = "no FLIGHTS HISTORY marker"
        except urllib.error.HTTPError as e:
            last = f"HTTP {e.code}"
            if e.code == 429:
                continue  # back off and retry
            if e.code == 404:
                return "", False  # unknown flight number, not an error
            time.sleep(DELAY)
        except Exception as e:  # noqa: BLE001
            last = str(e)
            time.sleep(DELAY)
    raise RuntimeError(last or "fetch failed")


def clean(s):
    s = re.sub(r"<[^>]+>", " ", s)
    # must decode entities: an empty ATD arrives as "&mdash;", and treating
    # that literal string as a time counted every future flight as departed.
    s = html_lib.unescape(s)
    return re.sub(r"\s+", " ", s).strip()


def parse_history(html):
    i = html.find("FLIGHTS HISTORY")
    if i < 0:
        return []
    seg = html[i:]
    rows = re.findall(r"<tr\b[^>]*data-timestamp[^>]*>.*?</tr>", seg, re.S | re.I)
    out = []
    skipped = 0
    for r in rows:
        cells = [clean(c) for c in re.findall(r"<td\b[^>]*>(.*?)</td>", r, re.S | re.I)]
        if len(cells) < 12:
            skipped += 1
            continue
        date, frm, to = cells[2], cells[3], cells[4]
        aircraft, atd, status = cells[5], cells[8], cells[11]
        # [7] STD = scheduled departure, [9] STA = scheduled arrival
        std, sta = cells[7], cells[9]
        if not DATE_RE.match(date) or not AIRPORT_RE.match(frm) or not AIRPORT_RE.match(to):
            skipped += 1
            continue
        out.append((date, frm, to, aircraft, atd, status, std, sta))
    if skipped:
        print(f"    ! skipped {skipped} malformed row(s)", file=sys.stderr)
    return out


IATA_RE = re.compile(r"\(([A-Z]{3})\)")
LANDED_RE = re.compile(r"Landed\s+(\d{1,2}:\d{2})")


def iata(cell):
    m = IATA_RE.search(cell)
    return m.group(1) if m else ""


def to_epoch(date_str, time_str):
    """'03 Nov 2026' + '08:59' -> device-local (Riyadh) epoch seconds."""
    try:
        dt = datetime.datetime.strptime(f"{date_str} {time_str}", "%d %b %Y %H:%M")
    except ValueError:
        return None
    return int(dt.replace(tzinfo=RUH).timestamp())


def to_iso(date_str):
    # "04 Nov 2026" -> "2026-11-04"
    parts = date_str.split()
    if len(parts) != 3:
        return None
    day, mon, year = parts
    if mon not in MONTHS:
        return None
    return f"{year}-{MONTHS[mon]:02d}-{int(day):02d}"


def flight_numbers():
    """Every flight number RUH schedules today — the best available roster."""
    nums = set()
    for mode in ("departures", "arrivals"):
        for page in (1, 2, 3):
            path = os.path.join(SCHED_DIR, f"all_{mode}_{page}.json")
            if not os.path.exists(path):
                continue
            try:
                with open(path) as f:
                    d = json.load(f)
                data = (d["result"]["response"]["airport"]["pluginData"]
                        ["schedule"][mode]["data"])
            except Exception as e:  # noqa: BLE001
                print(f"  schedule read failed {path}: {e}", file=sys.stderr)
                continue
            for x in data:
                n = (x["flight"]["identification"].get("number") or {}).get("default")
                if n:
                    nums.add(n.upper().strip())
    return sorted(nums)


def work(number):
    try:
        html, cached = get(number)
    except Exception as e:  # noqa: BLE001
        return number, [], str(e), False
    if not html:
        return number, [], None, cached  # 404 — not a real flight page
    return number, parse_history(html), None, cached


def _row(date, number, direction, epoch, origin, destination, aircraft, status,
         scheduled=0, color="gray"):
    return {
        "date": date,
        "number": number,
        "direction": direction,
        "epoch": epoch,
        "scheduled": scheduled,
        "origin": origin,
        "destination": destination,
        "airline": "",
        "aircraft": aircraft,
        "status": status,
        "color": color,
    }


def color_for(delay_min):
    """FR24 colours are delay severity: green ~on time, yellow slight, red bad."""
    if delay_min is None:
        return "gray"
    if delay_min <= 15:
        return "green"
    if delay_min <= 45:
        return "yellow"
    return "red"


def main():
    numbers = flight_numbers()
    print(f"roster: {len(numbers)} flight numbers")
    if not numbers:
        print("no schedule files found — run the fetch first", file=sys.stderr)
        return 1

    os.makedirs(CACHE, exist_ok=True)
    cached_n = sum(1 for n in numbers if os.path.exists(os.path.join(CACHE, f"{n}.html")))
    print(f"cached: {cached_n}/{len(numbers)}  (rest will be fetched)")

    dep = Counter()
    arr = Counter()
    flights = []
    bad = []

    # Sequential on purpose: concurrent requests trip FR24's 429 immediately.
    for i, number in enumerate(numbers, 1):
        number, rows, err, was_cached = work(number)
        if err:
            bad.append((number, err))
        for date, frm, to, aircraft, atd, status, std, sta in rows:
            iso = to_iso(date)
            if not iso:
                continue
            # history only: strictly before today, on/after the first
            # reliable day (today itself is owned by the live app)
            d = datetime.date.fromisoformat(iso)
            if d < FIRST_DAY or d >= TODAY:
                continue

            is_ruh_from = frm.startswith("Riyadh")
            is_ruh_to = to.startswith("Riyadh")
            is_dep = is_ruh_from and atd not in ("—", "", "-")
            is_arr = is_ruh_to and status.startswith("Landed")

            if is_dep:
                dep[iso] += 1
                # a departure's own time is its actual time off the stand
                epoch = to_epoch(date, atd)
                sched = to_epoch(date, std) if std not in ("—", "", "-") else None
                if epoch is not None:
                    delay = None if sched is None else (epoch - sched) // 60
                    flights.append(_row(iso, number, "dep", epoch,
                                        iata(frm), iata(to), aircraft, "DEPARTED",
                                        sched or 0, color_for(delay)))
            if is_arr:
                arr[iso] += 1
                # an arrival's own time is when it landed, not when it left
                m = LANDED_RE.search(status)
                epoch = to_epoch(date, m.group(1)) if m else to_epoch(date, atd)
                sched = to_epoch(date, sta) if sta not in ("—", "", "-") else None
                if epoch is not None:
                    delay = None if sched is None else (epoch - sched) // 60
                    flights.append(_row(iso, number, "arr", epoch,
                                        iata(frm), iata(to), aircraft, "LANDED",
                                        sched or 0, color_for(delay)))
        if i % 20 == 0:
            mark = "cached" if was_cached else "fetched"
            print(f"  {i}/{len(numbers)} ({mark}), "
                  f"{len(set(dep) | set(arr))} days collected", flush=True)

    all_days = sorted(set(dep) | set(arr))
    payload = [
        {"date": d, "departures": dep.get(d, 0), "arrivals": arr.get(d, 0)}
        for d in all_days
    ]

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w") as f:
        json.dump(payload, f, indent=1)
        f.write("\n")
    flights.sort(key=lambda r: (r["date"], r["epoch"]))
    with open(OUT_FLIGHTS, "w") as f:
        json.dump(flights, f, indent=1)
        f.write("\n")

    print(f"\nwrote {OUT}")
    print(f"wrote {OUT_FLIGHTS} ({len(flights)} flights)")
    print(f"{'date':12s} {'dep':>5s} {'arr':>5s} {'total':>6s} {'flights':>8s}")
    per_day = Counter(r["date"] for r in flights)
    for row in payload:
        t = row["departures"] + row["arrivals"]
        print(f"{row['date']:12s} {row['departures']:5d} {row['arrivals']:5d} "
              f"{t:6d} {per_day.get(row['date'], 0):8d}")
    print(f"days: {len(payload)}   fetch failures: {len(bad)}")
    for n, e in bad[:10]:
        print(f"  fail {n}: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
