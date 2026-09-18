#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""X 계열(횡단면 전략) 유니버스 일봉 수집 — corp_map_all.csv의 전 종목(상장폐지 포함)을 야후에서 받아
   data/universe/prices/{code}.csv 로 저장한다(형식은 data/events/prices와 동일: date,open,high,low,close,volume, 수정주가).

   - data/events/prices 에 이미 있는 종목은 재수집하지 않고 복사한다(1,375종목 절약).
   - 야후는 상장폐지 종목의 과거 시계열을 일부 보존한다 — 시계열이 조기에 끝나는 종목은 엔진이 "폐지"로
     간주해 마지막 종가에 청산 처리한다. 아예 응답이 없는 종목은 .price_fail 에 기록(생존 편향 한계로 병기).
   - 전역 스로틀(초당 약 6건)·이어받기·예산 초과 시 중단 후 재실행 이어받기 — fetch_fscore_financials.py 와 동일 관례.

   사용: python scripts/fetch_universe_prices.py            (기본 예산 600초, 재실행으로 이어받기)
         BUDGET_SECONDS=1800 python scripts/fetch_universe_prices.py
"""
import csv, os, sys, time, json, shutil, urllib.request, concurrent.futures, threading
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CORP = ROOT / "data/corp_map_all.csv"
REUSE = ROOT / "data/events/prices"
OUT = ROOT / "data/universe/prices"; OUT.mkdir(parents=True, exist_ok=True)
FAIL = ROOT / "data/universe/.price_fail"
UA = {"User-Agent": "Mozilla/5.0"}
P1 = int(datetime(2014, 1, 1, tzinfo=timezone.utc).timestamp())   # 2015-01 형성 시점의 12개월 룩백 여유
P2 = int(time.time())
MIN_ROWS = 60
_throttle_lock = threading.Lock(); _last = [0.0]; INTERVAL = 1 / 6.0


def throttled():
    with _throttle_lock:
        wait = _last[0] + INTERVAL - time.time()
        if wait > 0:
            time.sleep(wait)
        _last[0] = time.time()


def fetch(sym):
    throttled()
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}?period1={P1}&period2={P2}&interval=1d"
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())


def save(code):
    for suf in (".KS", ".KQ"):
        try:
            d = fetch(code + suf)
            res = d.get("chart", {}).get("result")
            if not res:
                continue
            r = res[0]; ts = r.get("timestamp") or []
            q = r["indicators"]["quote"][0]
            adj = r["indicators"].get("adjclose", [{}])[0].get("adjclose")
            rows = []
            for i, t in enumerate(ts):
                o, h, l, c, v = q["open"][i], q["high"][i], q["low"][i], q["close"][i], q["volume"][i]
                if None in (o, h, l, c) or c == 0:
                    continue
                f = (adj[i] / c) if adj and adj[i] else 1.0
                dt = datetime.fromtimestamp(t, timezone.utc).astimezone().strftime("%Y-%m-%d")
                rows.append(f"{dt},{o*f:.4f},{h*f:.4f},{l*f:.4f},{c*f:.4f},{v or 0}")
            if len(rows) < MIN_ROWS:
                continue
            (OUT / f"{code}.csv").write_text("date,open,high,low,close,volume\n" + "\n".join(rows) + "\n", encoding="utf-8")
            return code, suf, len(rows)
        except Exception:
            time.sleep(0.3)
    return code, None, 0


def main():
    codes = sorted({r["stock_code"].strip() for r in csv.DictReader(open(CORP, encoding="utf-8"))
                    if r["stock_code"].strip().isdigit()})
    # 1) 기존 이벤트 수집분 재사용
    reused = 0
    if REUSE.is_dir():
        for c in codes:
            src = REUSE / f"{c}.csv"; dst = OUT / f"{c}.csv"
            if src.is_file() and not dst.exists():
                shutil.copy2(src, dst); reused += 1
    failed = set(FAIL.read_text().split()) if FAIL.exists() else set()
    todo = [c for c in codes if not (OUT / f"{c}.csv").exists() and c not in failed]
    print(f"유니버스 {len(codes)}종목 | 재사용 복사 {reused} | 보유 {len(codes)-len(todo)-len(failed & set(codes))} "
          f"| 실패기록 {len(failed & set(codes))} | 남음 {len(todo)}", flush=True)
    deadline = time.time() + int(os.environ.get("BUDGET_SECONDS", "600"))
    lock = threading.Lock(); done = fail = 0
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as ex:
        futs = [ex.submit(save, c) for c in todo]
        for fu in concurrent.futures.as_completed(futs):
            code, suf, n = fu.result()
            with lock:
                if suf:
                    done += 1
                else:
                    fail += 1; failed.add(code); FAIL.write_text(" ".join(sorted(failed)))
                if (done + fail) % 100 == 0:
                    print(f"  진행 +{done+fail} (성공 {done}/실패 {fail})", flush=True)
            if time.time() > deadline:
                for f2 in futs:
                    f2.cancel()
                print("예산 소진 — 재실행으로 이어받기", flush=True); break
    have = len(list(OUT.glob("*.csv")))
    print(f"현재 보유 {have}/{len(codes)}종목 (커버리지 {have/len(codes)*100:.1f}%) — 실패 {len(failed)}종목은 .price_fail", flush=True)


if __name__ == "__main__":
    sys.exit(main())
