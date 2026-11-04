#!/usr/bin/env python3
"""Offline Plane Finder parser/rule test.

Mirrors the regexes and filtering rules in
app/src/main/java/com/ruh/flight/api/collectors/PlaneFinderCollector.kt
and validates them against the captured PF API fixtures in test_data/.

Rules under test (PF-only):
  departures: PAST + status Departed + RED dot (late)      -> countable
  arrivals:   PAST + status Landed   + RED or GREEN dot     -> countable
  cancelled:  never countable, always added to purge set
  future/scheduled rows (Departing/Arriving) -> never countable
"""

import glob
import os
import re
import sys
from datetime import datetime

RE_ROW = re.compile(r"<tr\b[^>]*>.*?</tr>", re.I | re.S)
RE_FLIGHT = re.compile(r'href="/data/flight/([A-Za-z0-9]+)"')
RE_DATA_DATE = re.compile(r'data-date="([^"]+)"')
RE_CANCELLED_ROW = re.compile(r'class="[^"]*\bcancelled\b')
RE_SCHEDULED = re.compile(
    r"<td>\s*<div class=\"d-inline-flex align-items-center\">\s*([0-9]{1,2}:[0-9]{2})",
    re.I | re.S,
)
RE_STATUS = re.compile(
    r'<span class="dot\s+([a-z]+)"[^>]*>\s*</span>\s*([A-Za-z]+)\s*'
    r"(?:<div[^>]*>\s*([0-9]{1,2}:[0-9]{2})\s*</div>)?",
    re.I | re.S,
)
RE_AIRPORT = re.compile(r'href="/data/airport/([A-Z]{3})"')
RE_CITY = re.compile(r">([A-Za-z][A-Za-z\s'&.\-]{1,30})\s*<a href=\"/data/airport/")
RE_AIRLINE_CODE = re.compile(r'href="/data/airline/([A-Za-z0-9]{2,3})"')
RE_AIRLINE_NAME = re.compile(r'class="text-primary">([^<]+)</a>')

DATE_FORMATS = ("%A %d %B %Y", "%A, %d %B %Y")


def parse_date(raw):
    raw = (raw or "").strip()
    for fmt in DATE_FORMATS:
        try:
            return datetime.strptime(raw, fmt).date()
        except ValueError:
            pass
    return None


def parse_board_html(html):
    """Port of PlaneFinderCollector.parseBoardHtml."""
    rows = []
    for rm in RE_ROW.finditer(html):
        row = rm.group(0)
        m_flight = RE_FLIGHT.search(row)
        if not m_flight:
            continue
        data_date = RE_DATA_DATE.search(row)
        cancelled_row = bool(RE_CANCELLED_ROW.search(row))
        m_sched = RE_SCHEDULED.search(row)
        if not m_sched:
            continue
        m_status = RE_STATUS.search(row)
        dot = (m_status.group(1).lower() if m_status else "")
        status = (m_status.group(2) if m_status else "")
        actual = None
        if m_status and m_status.group(3):
            actual = m_status.group(3)
        m_ap = RE_AIRPORT.search(row)
        m_city = RE_CITY.search(row)
        m_alc = RE_AIRLINE_CODE.search(row)
        m_aln = RE_AIRLINE_NAME.search(row)
        rows.append(
            {
                "flight": m_flight.group(1).upper(),
                "scheduled": m_sched.group(1),
                "actual": actual,
                "status": status,
                "dot": dot,
                "data_date": data_date.group(1) if data_date else "",
                "airport": m_ap.group(1) if m_ap else "",
                "city": m_city.group(1).strip() if m_city else "",
                "airline_code": m_alc.group(1).upper() if m_alc else "",
                "airline_name": m_aln.group(1).strip() if m_aln else "",
                "cancelled_row": cancelled_row,
            }
        )
    return rows


def is_countable(row, direction, now):
    """Port of the fetchDay() filtering rules (past-check uses device date)."""
    num = row["flight"]
    if row["cancelled_row"] or row["dot"] == "cancelled":
        return False, "cancelled"
    if row["status"].lower() in ("cancelled", "canceled"):
        return False, "cancelled"

    is_arr = direction == "arrivals"
    status_lower = row["status"].lower()
    if is_arr and not status_lower.startswith("landed"):
        return False, "not-landed"
    if not is_arr and not status_lower.startswith("departed"):
        return False, "not-departed"

    dot = row["dot"]
    if not is_arr and dot != "late":
        return False, "dep-dot-%s" % (dot or "none")
    if is_arr and dot not in ("late", "ontime"):
        return False, "arr-dot-%s" % (dot or "none")

    if not row["actual"]:
        return False, "no-actual-time"

    day = parse_date(row["data_date"])
    if day is None:
        return False, "unparsed-date"

    hh, mm = (int(x) for x in row["actual"].split(":"))
    if day > now.date() or (day == now.date() and (hh, mm) > (now.hour, now.minute)):
        return False, "future"

    return True, "counted"


def run():
    here = os.path.dirname(os.path.abspath(__file__))
    data_dir = os.path.join(here, "test_data")
    files = sorted(glob.glob(os.path.join(data_dir, "pf_api_*.html")))
    if not files:
        print("FAIL: no fixtures found in %s" % data_dir)
        return 1

    now = datetime.now()
    failures = []
    totals = {"countable": 0, "cancelled": 0, "parsed": 0, "rows": 0}

    for path in files:
        name = os.path.basename(path)
        direction = "arrivals" if "arrivals" in name else "departures"
        html = open(path, encoding="utf-8", errors="replace").read()

        raw_rows = RE_ROW.findall(html)
        rows = parse_board_html(html)
        totals["rows"] += len(raw_rows)
        totals["parsed"] += len(rows)

        with_flight = sum(1 for r in raw_rows if RE_FLIGHT.search(r))
        if len(rows) != with_flight:
            failures.append(
                "%s: parsed %d rows but %d rows carry a flight link"
                % (name, len(rows), with_flight)
            )

        seen = set()
        for r in rows:
            ok, reason = is_countable(r, direction, now)
            if reason == "counted":
                totals["countable"] += 1
                seen.add(r["flight"])
            elif reason == "cancelled":
                totals["cancelled"] += 1
                if r["flight"] in seen:
                    failures.append(
                        "%s: cancelled flight %s also counted" % (name, r["flight"])
                    )

            if r["dot"] and r["dot"] not in ("ontime", "early", "late", "cancelled"):
                failures.append("%s: unknown dot class '%s'" % (name, r["dot"]))
            if r["status"] and r["status"] not in (
                "Landed",
                "Departed",
                "Cancelled",
                "Arriving",
                "Departing",
            ):
                failures.append("%s: unknown status '%s'" % (name, r["status"]))
            if r["status"] in ("Landed", "Departed") and not r["actual"]:
                failures.append(
                    "%s: %s row %s missing actual time"
                    % (name, r["status"], r["flight"])
                )
            if r["cancelled_row"] and r["dot"] != "cancelled":
                failures.append(
                    "%s: flight %s flagged cancelled but dot=%s"
                    % (name, r["flight"], r["dot"])
                )

    print("fixtures            : %d" % len(files))
    print("raw <tr> rows       : %d" % totals["rows"])
    print("parsed rows         : %d" % totals["parsed"])
    print("countable (rules)   : %d" % totals["countable"])
    print("cancelled (purge)   : %d" % totals["cancelled"])

    if failures:
        print("\nFAILURES (%d):" % len(failures))
        for f in failures[:40]:
            print("  - %s" % f)
        return 1

    if totals["countable"] == 0:
        print("\nFAIL: no countable flights parsed from fixtures")
        return 1

    print("\nPASS: PF parser matches PF-only dot/time rules on all fixtures")
    return 0


if __name__ == "__main__":
    sys.exit(run())
