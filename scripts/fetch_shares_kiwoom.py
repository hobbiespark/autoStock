#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""X1b·X2b 유니버스용 상장주식수 수집 — 키움 ka10001(주식기본정보)의 flo_stk(상장주식수, 천주)·mac(시가총액, 억원)을
   data/universe/prices 의 전 종목에 대해 받아 data/universe/shares.csv 로 저장한다.

   - .env 를 직접 파싱한다(load_env.ps1 과 같은 규칙: KEY=VALUE, KEY_FILE=path). 모의투자 키(KIWOOM_MOCK_G_*) 사용 —
     ka10001 은 시세 조회라 모의/실전 값이 같다.
   - 초당 3건 스로틀(키움 TR 제한 ~4/s 보수 적용). 2,600종목 ≈ 15분. 이어받기 지원.
   - 상장폐지 종목은 응답 오류/빈값 → 미기록(시총 유니버스에서 제외됨 — 생존 편향 항목에 병기).
   사용: python scripts/fetch_shares_kiwoom.py
"""
import csv, json, os, sys, time, urllib.request, urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PRICES = ROOT / "data/universe/prices"
OUT = ROOT / "data/universe/shares.csv"
HOST = "https://mockapi.kiwoom.com"
INTERVAL = 1 / 3.0


def load_env():
    env = {}
    p = ROOT / ".env"
    for ln in p.read_text(encoding="utf-8").splitlines():
        if "=" in ln and not ln.strip().startswith("#"):
            k, v = ln.split("=", 1); env[k.strip()] = v.strip()
    for k in list(env):
        if k.endswith("_FILE"):
            fp = Path(env[k]); fp = fp if fp.is_absolute() else ROOT / fp
            if fp.is_file(): env[k[:-5]] = fp.read_text(encoding="utf-8").strip()
    return env


def post(path, body, headers):
    req = urllib.request.Request(HOST + path, data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json;charset=UTF-8", **headers}, method="POST")
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())


def main():
    env = load_env()
    key, sec = env.get("KIWOOM_MOCK_G_APP_KEY"), env.get("KIWOOM_MOCK_G_APP_SECRET")
    if not key or not sec:
        print("[X] KIWOOM_MOCK_G_APP_KEY/SECRET 없음 (.env 확인)"); return 1
    tok = post("/oauth2/token", {"grant_type": "client_credentials", "appkey": key, "secretkey": sec}, {})
    if tok.get("return_code") != 0:
        print("[X] 토큰 발급 실패:", tok.get("return_msg")); return 1
    h = {"authorization": f"Bearer {tok['token']}", "api-id": "ka10001", "cont-yn": "N", "next-key": ""}

    done = {}
    if OUT.exists():
        for r in csv.DictReader(open(OUT, encoding="utf-8")):
            done[r["stock_code"]] = r
    codes = sorted(p.stem for p in PRICES.glob("*.csv"))
    todo = [c for c in codes if c not in done]
    print(f"대상 {len(codes)}종목 | 완료 {len(done)} | 남음 {len(todo)}", flush=True)
    new_rows = []; fail = 0; last = 0.0
    try:
        for n, code in enumerate(todo, 1):
            wait = last + INTERVAL - time.time()
            if wait > 0: time.sleep(wait)
            last = time.time()
            try:
                r = post("/api/dostk/stkinfo", {"stk_cd": code}, h)
                if r.get("return_code") == 0 and str(r.get("flo_stk", "")).strip():
                    new_rows.append({"stock_code": code, "stk_nm": r.get("stk_nm", ""),
                                     "listed_shares_thousand": str(r.get("flo_stk", "")).replace("+", "").replace("-", ""),
                                     "market_cap_100m": str(r.get("mac", "")).replace("+", "").replace("-", ""),
                                     "cur_prc": str(r.get("cur_prc", "")).replace("+", "").replace("-", "")})
                else:
                    fail += 1
            except urllib.error.HTTPError as e:
                fail += 1
                if e.code == 429:
                    time.sleep(5)
            except Exception:
                fail += 1
            if n % 200 == 0:
                print(f"  진행 {n}/{len(todo)} (실패 {fail})", flush=True)
    finally:
        rows = list(done.values()) + new_rows
        with open(OUT, "w", encoding="utf-8", newline="") as f:
            w = csv.DictWriter(f, fieldnames=["stock_code", "stk_nm", "listed_shares_thousand", "market_cap_100m", "cur_prc"])
            w.writeheader(); w.writerows(rows)
        print(f"저장 {OUT}: {len(rows)}종목 (이번 실행 성공 {len(new_rows)} / 실패 {fail})", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
