#!/usr/bin/env python3
"""Offline FR24 parse test: History-first, cancelled purge, no aircraft junk, no UL265-as-actual.
Usage: python3 offline_fr24_test.py arr.md dep.md
"""
import re, sys
from datetime import datetime, timezone, timedelta, date

TZ = timezone(timedelta(hours=3))
NOW = datetime.now(TZ)
TODAY = NOW.strftime('%Y-%m-%d')

def normalize(t):
    s=t.strip().upper(); ampm=''
    if s.endswith('AM'): s=s[:-2].strip(); ampm='AM'
    elif s.endswith('PM'): s=s[:-2].strip(); ampm='PM'
    parts=s.split(':')
    if len(parts)!=2: return t
    try: h=int(parts[0])
    except: return t
    if ampm=='PM' and h<12: h+=12
    if ampm=='AM' and h==12: h=0
    return f'{h:02d}:{int(parts[1]):02d}'

def resolve(pref, hhmm, now=NOW):
    h,m=map(int,hhmm.split(':')); y,mo,d=map(int,pref.split('-'))
    base=date(y,mo,d); skew=now+timedelta(minutes=2); cands=[]
    for off in (0,-1,1):
        dte=date.fromordinal(base.toordinal()+off)
        dt=datetime(dte.year,dte.month,dte.day,h,m,tzinfo=TZ)
        if dt<=skew: cands.append(dt)
    return max(cands) if cands else None

status_re=re.compile(r'^(Departed|Landed)\s+(\d{1,2}:\d{2}\s*(?:AM|PM)?)$', re.I)
hist_re=re.compile(r'\[History\s+([A-Z]{1,3}[0-9]{1,4})\]')
hist_url_re=re.compile(r'flightradar24\.com/([A-Za-z]{2}\d{3,4})/\w+')
bracket_flight=re.compile(r'^\[([A-Z]{1,3}[0-9]{1,4})\]\(https?://[^)]*/flights/')
flights_link_re=re.compile(r'flightradar24\.com/data/flights/([A-Za-z0-9]+)')
iata_re1=re.compile(r'\[(?:To|From):\s*([A-Za-z][A-Za-z\s\'&.\-]*?)\s+([A-Z]{3})\s+Airport')
iata_re2=re.compile(r'\[(?:To|From):\s*([A-Za-z][A-Za-z\s\'&.\-]*?)!\[[^\]]*\]\([^)]*\)\s*([A-Z]{3})\s+Airport')
airline_re=re.compile(r'\[(?!\])([^\]]+)\]\(https?://www\.flightradar24\.com/data/airlines/')
boundary_hist=re.compile(r'^\[History\s|flightradar24\.com/[a-z]{2}\d{3,4}/')
boundary_act=re.compile(r'^(Departed|Landed)\s+\d', re.I)
boundary_st=re.compile(r'^(Canceled|Cancelled|Scheduled|Estimated|Delayed)\b', re.I)
status_or_hist=re.compile(r'^(?:\[History |(?:Departed|Landed)\s+\d|(?:Canceled|Cancelled|Scheduled|Estimated|Delayed)\b)', re.I)

def parse_board(md, direction, date_str):
    out=[]; lines=md.splitlines(); i=0
    while i<len(lines):
        m=status_re.match(lines[i].strip())
        if not m: i+=1; continue
        event=m.group(1).lower(); time_str=m.group(2).strip()
        flightNum=''; otherIata=''; otherCity=''; airline=''
        fwdHi=min(len(lines)-1,i+10); bwdLo=max(0,i-18)
        for k in range(i+1, fwdHi+1):
            fm=hist_re.search(lines[k])
            if fm: flightNum=fm.group(1); break
            fh=hist_url_re.search(lines[k])
            if fh: flightNum=fh.group(1).upper(); break
        for k in range(i-1, bwdLo-1, -1):
            lk=lines[k]; lt=lk.strip()
            if boundary_hist.search(lt): break
            if boundary_act.search(lt): break
            if boundary_st.search(lt) and len(lt)<30: break
            if not flightNum:
                fm2=bracket_flight.match(lt)
                if fm2: flightNum=fm2.group(1)
            if not flightNum:
                fm3=flights_link_re.search(lk)
                if fm3: flightNum=fm3.group(1).upper()
            if not otherIata:
                tm=iata_re1.search(lk)
                if tm: otherCity=tm.group(1).strip(); otherIata=tm.group(2)
            if not otherIata:
                tm2=iata_re2.search(lk)
                if tm2: otherCity=tm2.group(1).strip(); otherIata=tm2.group(2)
            if not airline:
                am=airline_re.search(lk)
                if am: airline=am.group(1).strip().split(' (')[0].strip()
            if flightNum and otherIata and airline: break
        if flightNum:
            utc=resolve(date_str, normalize(time_str))
            if utc and utc<=NOW+timedelta(minutes=2):
                out.append((flightNum,event,time_str,utc,otherIata,otherCity))
        i+=1
    return out

def extract_cancelled(md):
    out=set()
    live=md.find('## Live'); body=md[live:] if live>=0 else md
    lines=body.splitlines(); i=0
    while i<len(lines):
        lt=lines[i].strip()
        is_cancel=re.match(r'^(Canceled|Cancelled)$', lt, re.I)
        is_divert=re.search(r'Diverted to [A-Z]{3}', lt)
        if is_cancel or is_divert:
            if is_divert:
                got=False
                for k in range(i+1, min(len(lines), i+5)):
                    h=hist_re.search(lines[k])
                    if h: out.add(h.group(1).upper()); got=True; break
                if got: i+=1; continue
            got=False
            for k in range(i-1, max(-1,i-13), -1):
                lk=lines[k]; ltt=lk.strip()
                if status_or_hist.search(ltt): break
                m=flights_link_re.search(lk) or bracket_flight.match(ltt)
                if m: out.add(m.group(1).upper()); got=True; break
            if not got:
                for k in range(i+1, min(len(lines), i+9)):
                    m=bracket_flight.match(lines[k].strip()) or flights_link_re.search(lines[k])
                    if m: out.add(m.group(1).upper()); break
        i+=1
    return out

def main():
    if len(sys.argv)<3:
        print('usage: offline_fr24_test.py arr.md dep.md'); sys.exit(2)
    all_rows=[]; all_cancel=set()
    for path,d in [(sys.argv[1],'arrival'),(sys.argv[2],'departure')]:
        md=open(path).read()
        rows=parse_board(md,d,TODAY); canc=extract_cancelled(md); all_cancel|=canc
        print(f'{path}: {len(rows)} actuals, {len(canc)} cancelled')
        for r in rows: print(' ', r)
        assert not any(r[0]=='UL265' for r in rows), 'UL265 must not be actual'
        assert not (set(r[0] for r in rows)&canc), 'parsed ∩ cancelled'
        for r in rows:
            assert r[0] not in ('A320','A321','A333','A20N'), f'aircraft junk {r[0]}'
        all_rows.extend(rows)
    # UL265 only asserted if present in either board
    md_a=open(sys.argv[1]).read(); md_d=open(sys.argv[2]).read()
    if 'UL265' in md_a or 'UL265' in md_d:
        assert 'UL265' in all_cancel, 'UL265 must be cancelled when on board'
        assert not any(r[0]=='UL265' for r in all_rows)
    print(f'TOTAL {len(all_rows)} actuals, {len(all_cancel)} cancelled — ALL PASS')

if __name__=='__main__':
    main()
