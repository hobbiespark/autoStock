"""글로벌 이벤트 필터 검증용 데이터 수집 (aiDoc/global-event-filter-plan.md 5장 1단계).

만드는 파일(기본 data/, git 제외):
  macro_events.csv  date,kind   — 미국 날짜 기준 예정 발표일. kind = FOMC | CPI | NFP
  fred_DGS10.csv    date,value  — 미국 10년물 금리(%). 값이 '.'인 날(휴장)은 뺀다
  fred_DTWEXBGS.csv date,value  — 광의 달러 지수(H.10, 주 1회 공표)

출처:
  FOMC — 연준 공개 일정(fomccalendars.htm, fomchistorical{연도}.htm). 정례 회의만(비정례·취소·서면 결의 제외),
         발표일 = 회의 마지막 날.
  CPI  — FRED/ALFRED CPIAUCNS(계절조정 없음) 공표 시점(vintage) 날짜. 계절조정 계열은 연례 계수 개정일이 섞이므로 쓰지 않는다.
  NFP  — FRED release 50(Employment Situation) 공표일.

사용: FRED_API_KEY 환경변수(값은 출력하지 않는다).
  py -3.13 scripts/fetch_macro_event_data.py [--out data] [--start 2014-01-01]

운영 일정 표(E1 적용, 계획 4장 — 연 1회 갱신):
  py -3.13 scripts/fetch_macro_event_data.py --schedule 2026 2027
  → app/src/main/resources/macro/us-event-schedule.csv. 앞으로의 발표일은 FRED 예정 공표일
    (CPI release 10, 고용 release 50, include_release_dates_with_no_data=true)과 연준 일정에서 받는다.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import re
import sys
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

FED = "https://www.federalreserve.gov/monetarypolicy/"
FRED = "https://api.stlouisfed.org/fred/"
UA = {"User-Agent": "autostock-research/1.0"}
MONTHS = {m: i for i, m in enumerate(
    ["January", "February", "March", "April", "May", "June", "July", "August", "September",
     "October", "November", "December"], start=1)}
SHORT = {k[:3]: v for k, v in MONTHS.items()}


def get(url: str) -> str:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", errors="replace")


def fred(path: str, **params: str) -> dict:
    key = os.environ.get("FRED_API_KEY", "").strip().strip("'\"")
    if not key:
        sys.exit("FRED_API_KEY 환경변수가 없다")
    params.update(api_key=key, file_type="json")
    return json.loads(get(FRED + path + "?" + urllib.parse.urlencode(params)))


def month_of(token: str) -> int:
    token = token.strip()
    if token in MONTHS:
        return MONTHS[token]
    return SHORT[token[:3]]


def fomc_dates(start_year: int) -> list[date]:
    out: set[date] = set()
    this_year = date.today().year
    # 최근 연도: 연도 패널마다 월·날짜 칸이 번갈아 나온다.
    cal = get(FED + "fomccalendars.htm")
    parts = re.split(r">(\d{4}) FOMC Meetings<", cal)
    for i in range(1, len(parts), 2):
        year, body = int(parts[i]), parts[i + 1]
        months = re.findall(r'fomc-meeting__month[^>]*><strong>([^<]+)<', body)
        days = re.findall(r'fomc-meeting__date[^>]*>([^<]+)<', body)
        for m, d in zip(months, days):
            if re.search(r"unscheduled|cancel|notation", d, re.I):
                continue
            end_month = month_of(m.split("/")[-1])
            end_day = int(re.findall(r"\d+", d)[-1])
            out.add(date(year, end_month, end_day))
    # 옛 연도: 회의마다 제목 한 줄("January 26-27 Meeting - 2016", "April 30-May 1 Meeting - 2019").
    for year in range(start_year, this_year + 1):
        if any(x.year == year for x in out):
            continue
        try:
            page = get(FED + f"fomchistorical{year}.htm")
        except Exception:  # 아직 역사 페이지가 없는 연도
            continue
        for title in re.findall(r"<h5[^>]*>([^<]+)</h5>|<h5[^>]*>([^<]+)", page):
            text = (title[0] or title[1]).strip()
            if "Meeting" not in text or re.search(r"unscheduled|cancel|notation|conference call", text, re.I):
                continue
            head = text.split("Meeting")[0]
            month_tokens = re.findall(r"[A-Z][a-z]+", head)
            nums = re.findall(r"\d+", head)
            if not month_tokens or not nums:
                continue
            out.add(date(year, month_of(month_tokens[-1]), int(nums[-1])))
    return sorted(x for x in out if x.year >= start_year)


def cpi_dates(start: str) -> list[date]:
    d = fred("series/vintagedates", series_id="CPIAUCNS", realtime_start=start, limit="10000")
    return [date.fromisoformat(x) for x in d["vintage_dates"]]


def nfp_dates(start: str) -> list[date]:
    d = fred("release/dates", release_id="50", realtime_start=start,
             include_release_dates_with_no_data="false", limit="10000", sort_order="asc")
    return [date.fromisoformat(x["date"]) for x in d["release_dates"]]


def scheduled_dates(release_id: str, year: int) -> list[date]:
    d = fred("release/dates", release_id=release_id, realtime_start=f"{year}-01-01", realtime_end="9999-12-31",
             include_release_dates_with_no_data="true", limit="10000", sort_order="asc")
    return [x for x in (date.fromisoformat(r["date"]) for r in d["release_dates"]) if x.year == year]


def write_schedule(years: list[int], path: Path) -> None:
    fomc = fomc_dates(min(years))
    rows: list[tuple[date, str]] = []
    covered: list[int] = []
    for year in years:
        part = [(x, "FOMC") for x in fomc if x.year == year]
        part += [(x, "CPI") for x in scheduled_dates("10", year)]
        part += [(x, "NFP") for x in scheduled_dates("50", year)]
        counts = {k: sum(1 for _, kk in part if kk == k) for k in ("FOMC", "CPI", "NFP")}
        # 세 종류가 다 공표 예정에 올라온 해만 넣는다(일부만 있으면 그 해의 사건 밤을 놓친다).
        if counts["FOMC"] < 7 or counts["CPI"] < 10 or counts["NFP"] < 10:
            print(year, counts, "- 예정 공표일이 아직 다 없어 뺀다")
            continue
        covered.append(year)
        rows += part
    if not covered:
        sys.exit("덮을 수 있는 연도가 없다")
    years = covered
    rows.sort()
    path.parent.mkdir(parents=True, exist_ok=True)
    lines = ["# 미국 예정 발표일(미국 날짜). 생성: scripts/fetch_macro_event_data.py --schedule — 손으로 고치지 말고 다시 생성한다.",
             "# 덮는 연도: " + ",".join(str(y) for y in years),
             "date,kind"]
    lines += [f"{x.isoformat()},{k}" for x, k in rows]
    path.write_bytes(("\n".join(lines) + "\n").encode("utf-8"))
    for year in years:
        counts = {k: sum(1 for x, kk in rows if kk == k and x.year == year) for k in ("FOMC", "CPI", "NFP")}
        print(year, counts)


def series(series_id: str, start: str) -> list[tuple[str, str]]:
    d = fred("series/observations", series_id=series_id, observation_start=start)
    return [(o["date"], o["value"]) for o in d["observations"] if o["value"] not in (".", "")]


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default="data")
    ap.add_argument("--start", default="2014-01-01")
    ap.add_argument("--schedule", type=int, nargs="+", help="운영 일정 표를 만들 연도들")
    ap.add_argument("--schedule-out", default="app/src/main/resources/macro/us-event-schedule.csv")
    a = ap.parse_args()
    if a.schedule:
        write_schedule(sorted(a.schedule), Path(a.schedule_out))
        return
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    start_year = int(a.start[:4])

    rows = [(x, "FOMC") for x in fomc_dates(start_year)]
    rows += [(x, "CPI") for x in cpi_dates(a.start)]
    rows += [(x, "NFP") for x in nfp_dates(a.start)]
    rows.sort()
    with open(out / "macro_events.csv", "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["date", "kind"])
        w.writerows((x.isoformat(), k) for x, k in rows)
    for kind in ("FOMC", "CPI", "NFP"):
        per_year: dict[int, int] = {}
        for x, k in rows:
            if k == kind:
                per_year[x.year] = per_year.get(x.year, 0) + 1
        print(kind, sum(per_year.values()), dict(sorted(per_year.items())))

    for sid in ("DGS10", "DTWEXBGS"):
        obs = series(sid, a.start)
        with open(out / f"fred_{sid}.csv", "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(["date", "value"])
            w.writerows(obs)
        print(sid, len(obs), obs[0][0], obs[-1][0])


if __name__ == "__main__":
    main()
