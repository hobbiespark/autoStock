#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""X1b·X2b 유니버스용 시점별(point-in-time) 시가총액 — KRX 정보데이터시스템 [12001] 전종목 시세를 월말 거래일마다 받아
   data/universe/marketcap/{YYYYMMDD}.csv (stock_code,name,market_cap,listed_shares,close) 로 저장한다.

   키움 ka10001 방식(현재 상장주식수 × 과거 종가 근사, fetch_shares_kiwoom.py)보다 낫다:
   - 그 날짜의 실제 시가총액·상장주식수라 룩어헤드가 전혀 없고(증자·감자 반영), 폐지 종목도 그 시점엔 포함된다.
   - 월 1회 × 약 140개월 = 140요청뿐 — 키움 호출 제한(운영 중 앱과 키 공유)과 무관.

   우선 pykrx(pip install pykrx)를 쓰고, 없으면 KRX 원 API(data.krx.co.kr getJsonData.cmd)를 직접 호출한다.
   ※ KRX 정보데이터시스템은 로그인 필수(무료 회원가입) — 환경변수 KRX_ID / KRX_PW 를 설정하면 pykrx가 자동 로그인한다.
   이어받기 지원(이미 있는 날짜는 건너뜀). 사용: python scripts/fetch_krx_marketcap.py
"""
import csv, io, json, sys, time, urllib.request, urllib.parse
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CAL = ROOT / "data/universe/prices/005930.csv"
OUT = ROOT / "data/universe/marketcap"; OUT.mkdir(parents=True, exist_ok=True)
FIRST = date(2014, 12, 1)
SLEEP = 1.2


def month_ends(dates):
    out = []
    for i in range(len(dates) - 1):
        if dates[i].month != dates[i + 1].month and dates[i] >= FIRST:
            out.append(dates[i])
    return out


def via_pykrx(d):
    from pykrx import stock  # noqa
    df = stock.get_market_cap(d.strftime("%Y%m%d"), market="ALL")
    if df is None or df.empty:
        return None
    rows = []
    for code, r in df.iterrows():
        rows.append((str(code).zfill(6), "", int(r["시가총액"]), int(r["상장주식수"]), int(r["종가"])))
    return rows


def via_krx(d):
    url = "http://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd"
    body = urllib.parse.urlencode({
        "bld": "dbms/MDC/STAT/standard/MDCSTAT01501", "locale": "ko_KR", "mktId": "ALL",
        "trdDd": d.strftime("%Y%m%d"), "share": "1", "money": "1", "csvxls_isNo": "false"}).encode()
    req = urllib.request.Request(url, data=body, headers={
        "User-Agent": "Mozilla/5.0", "Referer": "http://data.krx.co.kr/contents/MDC/MDI/mdiLoader/index.cmd?menuId=MDC0201020101",
        "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8"})
    with urllib.request.urlopen(req, timeout=30) as r:
        j = json.loads(r.read())
    rows = []
    for x in j.get("OutBlock_1", []):
        try:
            rows.append((x["ISU_SRT_CD"], x.get("ISU_ABBRV", ""), int(x["MKTCAP"].replace(",", "")),
                         int(x["LIST_SHRS"].replace(",", "")), int(x["TDD_CLSPRC"].replace(",", ""))))
        except (KeyError, ValueError):
            continue
    return rows or None


def main():
    dates = []
    with open(CAL, encoding="utf-8") as f:
        next(f)
        for ln in f:
            dates.append(date.fromisoformat(ln.split(",")[0]))
    targets = month_ends(dates)
    todo = [d for d in targets if not (OUT / f"{d.strftime('%Y%m%d')}.csv").exists()]
    print(f"월말 {len(targets)}일 | 보유 {len(targets) - len(todo)} | 남음 {len(todo)}", flush=True)
    try:
        import pykrx  # noqa
        fetch = via_pykrx; print("소스: pykrx")
    except ImportError:
        fetch = via_krx; print("소스: KRX 원 API (pykrx 미설치 — pip install pykrx 권장)")
    ok = fail = 0
    consecutive = 0
    for d in todo:
        if consecutive >= 3:
            print("[X] 3회 연속 실패 — KRX 정보데이터시스템은 로그인이 필요합니다(2025~). data.krx.co.kr 무료 회원가입 후"
                  " 같은 터미널에서  $env:KRX_ID=\"아이디\"; $env:KRX_PW=\"비밀번호\"  설정하고 재실행하세요(pykrx가 자동 로그인).", flush=True)
            break
        try:
            rows = fetch(d)
            if not rows:
                rows = via_krx(d) if fetch is via_pykrx else None
            if not rows:
                fail += 1; consecutive += 1; print(f"  {d} 실패(빈 응답)", flush=True); time.sleep(SLEEP); continue
            consecutive = 0
            with open(OUT / f"{d.strftime('%Y%m%d')}.csv", "w", encoding="utf-8", newline="") as f:
                w = csv.writer(f); w.writerow(["stock_code", "name", "market_cap", "listed_shares", "close"]); w.writerows(rows)
            ok += 1
            if ok % 12 == 0:
                print(f"  진행 {ok}/{len(todo)} (실패 {fail}) 최근 {d}: {len(rows)}종목", flush=True)
        except Exception as e:
            fail += 1; consecutive += 1; print(f"  {d} 오류: {e}", flush=True)
            time.sleep(5)
        time.sleep(SLEEP)
    have = len(list(OUT.glob("*.csv")))
    print(f"완료: 보유 {have}/{len(targets)} (이번 성공 {ok}, 실패 {fail})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
