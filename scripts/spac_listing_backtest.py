#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
스팩 공모주 상장일 매도 백테스트 (2026-11-10, aiDoc/spac-listing-backtest-2026-11-10.md).

대상: 공모주 이력 수집(fetch_ipo_history.py)의 DART 목록 캐시에서 이름이 스팩이고 [발행조건확정] 신고서가 있는 회사.
회사마다: 대표 주관사·공모가(DART estkRs.json), 상장일 시가·종가와 5거래일째 종가(네이버 모바일 시세, 수정주가 아님).
규칙 3개(R1 시초가·R2 상장일 종가·R3 5거래일째 종가 매도)를 사전 선언대로 비교한다.

사용: py -3.13 -I scripts/spac_listing_backtest.py [목록 캐시 경로] [.env 경로]
      기본값은 운영 체크아웃(C:/ai/autoStock)의 data/ipo/history/list_rows.jsonl과 .env — 읽기만 한다.
      DART_API_KEY는 환경 변수가 먼저다. 키는 출력하지 않는다.
결과: 이 저장소의 data/ipo/history/spac_listing.csv와 화면 요약.
"""
import csv
import json
import os
import re
import statistics
import sys
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
PROD = Path("C:/ai/autoStock")
LIST_CACHE = Path(sys.argv[1]) if len(sys.argv) > 1 else PROD / "data/ipo/history/list_rows.jsonl"
ENV = Path(sys.argv[2]) if len(sys.argv) > 2 else PROD / ".env"
OUT = REPO / "data/ipo/history/spac_listing.csv"

SPAC = re.compile(r"스팩|기업인수목적")
CONFIRMED = "[발행조건확정]증권신고서(지분증권)"
SELL_COST = 0.00015 + 0.0020 + 0.0005   # 매도 수수료 + 증권거래세 + 슬리피지(CostModel.defaults)
DEFAULT_PRICE = 2000.0


def load_key() -> str:
    key = os.environ.get("DART_API_KEY", "")
    if not key and ENV.is_file():
        for line in ENV.read_text(encoding="utf-8").splitlines():
            if line.startswith("DART_API_KEY="):
                key = line.split("=", 1)[1].strip()
    return key


KEY = load_key()


def http(url: str, timeout: int = 40) -> bytes:
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 autostock-research"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception:
            if attempt == 3:
                raise
            time.sleep(2 + attempt * 3)
    raise RuntimeError("unreachable")


def num(s):
    if s in (None, "", "-"):
        return None
    try:
        return float(str(s).replace(",", ""))
    except ValueError:
        return None


def detail(corp_code: str, rcept_dates: list):
    """estkRs.json — 최신 신고서 한 건. 반환 (대표 주관사, 공모가 slprc, 청약 기간 글)."""
    if not KEY:
        return None, None, None
    bgn = (min(rcept_dates) - timedelta(days=5)).strftime("%Y%m%d")
    end = (max(rcept_dates) + timedelta(days=5)).strftime("%Y%m%d")
    q = urllib.parse.urlencode({"crtfc_key": KEY, "corp_code": corp_code, "bgn_de": bgn, "end_de": end})
    j = json.loads(http("https://opendart.fss.or.kr/api/estkRs.json?" + q).decode("utf-8"))
    if j.get("status") != "000":
        return None, None, None
    groups = {g.get("title"): g.get("list", []) for g in j.get("group", [])}
    under = groups.get("인수인정보", [])
    lead = next((u.get("actnmn") for u in under if u.get("actsen") == "대표"), None) or \
        next((u.get("actnmn") for u in under), None)
    sec = groups.get("증권의종류", [])
    price = num(sec[-1].get("slprc")) if sec else None
    gen = groups.get("일반사항", [])
    sbd = gen[-1].get("sbd") if gen else None
    return lead, price, sbd


def prices(code: str, around: date):
    """상장일 행과 5거래일째 행 — (날짜, 시가, 고가, 저가, 종가, 거래량). 확정 전 시세가 있으면 pre=True."""
    rows = []
    for page in range(1, 40):
        data = json.loads(http(f"https://m.stock.naver.com/api/stock/{code}/price?pageSize=60&page={page}").decode("utf-8"))
        if not data:
            break
        for x in data:
            rows.append((datetime.strptime(x["localTradedAt"], "%Y-%m-%d").date(), num(x.get("openPrice")),
                         num(x.get("highPrice")), num(x.get("lowPrice")), num(x.get("closePrice")),
                         num(x.get("accumulatedTradingVolume"))))
        if rows[-1][0] < around - timedelta(days=30):
            break
    rows.sort(key=lambda r: r[0])
    pre = any(r[0] < around for r in rows)
    after = [r for r in rows if r[0] >= around]
    return pre, (after[0] if after else None), (after[4] if len(after) >= 5 else None)


def spacs():
    by = {}
    for line in LIST_CACHE.read_text(encoding="utf-8").splitlines():
        if line.strip():
            r = json.loads(line)
            if SPAC.search(r.get("corp_name", "")):
                by.setdefault(r["corp_code"], []).append(r)
    out = []
    for corp, rs in by.items():
        conf = sorted([r for r in rs if CONFIRMED in r["report_nm"]], key=lambda r: r["rcept_no"])
        if conf:
            out.append((corp, rs, conf))
    return out


FIELDS = ["corp_code", "corp_name", "stock_code", "lead_manager", "offer_price", "price_source", "subscription",
          "confirmed_dt", "listing_date", "open", "high", "low", "close", "volume", "d5_date", "d5_close", "note"]


def collect(item):
    corp, rs, conf = item
    row = {k: "" for k in FIELDS}
    codes = [r["stock_code"].strip() for r in rs if r.get("stock_code", "").strip()]
    cdt = datetime.strptime(conf[-1]["rcept_dt"], "%Y%m%d").date()
    row.update(corp_code=corp, corp_name=rs[0]["corp_name"], stock_code=codes[0] if codes else "",
               confirmed_dt=cdt.isoformat())
    try:
        lead, price, sbd = detail(corp, [datetime.strptime(r["rcept_dt"], "%Y%m%d").date() for r in rs])
        row.update(lead_manager=lead or "", subscription=sbd or "")
        row["offer_price"] = price if price else DEFAULT_PRICE
        row["price_source"] = "estkRs" if price else "default_2000"
        if not row["stock_code"]:
            row["note"] = "no_stock_code"
            return row
        pre, first, fifth = prices(row["stock_code"], cdt)
        if pre:
            row["note"] = "pre_listed"
            return row
        if not first:
            row["note"] = "not_listed"
            return row
        row.update(listing_date=first[0].isoformat(), open=first[1], high=first[2], low=first[3], close=first[4],
                   volume=first[5])
        if fifth:
            row.update(d5_date=fifth[0].isoformat(), d5_close=fifth[4])
    except Exception as e:
        row["note"] = f"error {type(e).__name__}"
    return row


def net(sell, buy):
    return sell * (1 - SELL_COST) / buy - 1


def stats(xs):
    if not xs:
        return "표본 없음"
    return (f"n={len(xs)} 평균 {statistics.mean(xs):+.1%} 중앙값 {statistics.median(xs):+.1%} "
            f"최소 {min(xs):+.1%} 최대 {max(xs):+.1%} 손실 {sum(1 for x in xs if x < 0)}/{len(xs)}")


def report(rows):
    ok = sorted([r for r in rows if r["listing_date"] and r["open"] and r["close"]], key=lambda r: r["listing_date"])
    print(f"\n대상 {len(rows)}곳, 상장일 시세 있음 {len(ok)}곳")
    for r in rows:
        if r["note"]:
            print(f"  제외: {r['corp_name']} ({r['note']})")
    odd = [r for r in ok if r["price_source"] != "estkRs" or float(r["offer_price"]) != DEFAULT_PRICE]
    for r in odd:
        print(f"  공모가 확인: {r['corp_name']} {r['offer_price']} ({r['price_source']})")

    def block(name, part):
        r1 = [net(float(r["open"]), float(r["offer_price"])) for r in part]
        r2 = [net(float(r["close"]), float(r["offer_price"])) for r in part]
        d5 = [r for r in part if r["d5_close"]]
        r3 = [net(float(r["d5_close"]), float(r["offer_price"])) for r in d5]
        print(f"\n[{name}] {part[0]['listing_date']} ~ {part[-1]['listing_date']}")
        print(f"  R1 시초가    {stats(r1)}")
        print(f"  R2 당일 종가 {stats(r2)}")
        print(f"  R3 5일 종가  {stats(r3)}")
        d21 = [b - a for a, b in zip(r1, r2)]
        d31 = [net(float(r["d5_close"]), float(r["offer_price"])) - net(float(r["open"]), float(r["offer_price"]))
               for r in d5]
        for label, d in (("R2-R1", d21), ("R3-R1", d31)):
            if d:
                print(f"  {label} 평균 {statistics.mean(d):+.1%} 중앙값 {statistics.median(d):+.1%} "
                      f"R1보다 나음 {sum(1 for x in d if x > 0)}/{len(d)}")

    if not ok:
        return
    block("전체", ok)
    half = len(ok) // 2
    if half >= 3:
        block("앞쪽 절반", ok[:half])
        block("뒤쪽 절반", ok[half:])

    print("\n[주관사별 — 서술용] 개수, R1 중앙값")
    by = {}
    for r in ok:
        by.setdefault(r["lead_manager"] or "(모름)", []).append(net(float(r["open"]), float(r["offer_price"])))
    for k, v in sorted(by.items(), key=lambda kv: -len(kv[1])):
        print(f"  {k}: {len(v)}곳, R1 중앙값 {statistics.median(v):+.1%}")

    print("\n[종목별] 상장일, 이름, 주관사, 시초가·종가·5일 종가 순수익률")
    for r in ok:
        p = float(r["offer_price"])
        r3 = f"{net(float(r['d5_close']), p):+.1%}" if r["d5_close"] else "-"
        print(f"  {r['listing_date']} {r['corp_name']} ({r['lead_manager'] or '-'}) "
              f"{net(float(r['open']), p):+.1%} / {net(float(r['close']), p):+.1%} / {r3}")


def main():
    sys.stdout.reconfigure(encoding="utf-8")   # -I 실행은 PYTHONIOENCODING을 무시한다 — 콘솔 cp949에서 끊기지 않게
    if not LIST_CACHE.is_file():
        sys.exit(f"목록 캐시 없음: {LIST_CACHE}")
    if not KEY:
        print("DART_API_KEY 없음 — 주관사·공모가 없이 2,000원으로 계산한다")
    items = spacs()
    print(f"스팩 [발행조건확정] 회사 {len(items)}곳")
    with ThreadPoolExecutor(max_workers=4) as pool:
        rows = list(pool.map(collect, items))
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)
    print(f"저장: {OUT}")
    report(rows)


if __name__ == "__main__":
    main()
