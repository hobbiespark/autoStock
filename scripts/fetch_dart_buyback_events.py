#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DART 주요사항보고서 중 자사주 관련 공시 이력 수집 (G2 백테스트용, PLAN ADR-14).

수집 대상 (report_nm 접미사 매칭 — G1의 MajorDisclosureDartClient와 동일 방식):
  - 주요사항보고서(자기주식취득결정)      → BUYBACK_DIRECT (직접취득)
  - 주요사항보고서(자기주식취득신탁계약체결결정) → BUYBACK_TRUST (신탁)

출력: data/events/buyback_events.csv
  rcept_dt,rcept_no,corp_name,stock_code,type,amended
  (amended: [기재정정] 등 정정 공시 여부 — 원공시만 신호로 쓰기 위해 구분)

사용: .env의 DART_API_KEY 필요. 분기 단위로 페이징하며 표준출력에 진행 표시.
키는 절대 출력하지 않는다.
"""
import csv
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from datetime import date
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_PATH = REPO_ROOT / ".env"
OUT_PATH = REPO_ROOT / "data" / "events" / "buyback_events.csv"

TARGETS = {
    "주요사항보고서(자기주식취득결정)": "BUYBACK_DIRECT",
    "주요사항보고서(자기주식취득신탁계약체결결정)": "BUYBACK_TRUST",
}


def load_key() -> str:
    key = os.environ.get("DART_API_KEY", "")
    if not key and ENV_PATH.is_file():
        for line in ENV_PATH.read_text(encoding="utf-8").splitlines():
            if line.startswith("DART_API_KEY="):
                key = line.split("=", 1)[1].strip()
    if not key:
        print("DART_API_KEY 없음 (.env 확인)")
        sys.exit(1)
    return key


def fetch_page(key: str, bgn: str, end: str, page: int) -> dict:
    qs = urllib.parse.urlencode({
        "crtfc_key": key, "bgn_de": bgn, "end_de": end,
        "pblntf_ty": "B", "page_no": page, "page_count": 100,
    })
    url = f"https://opendart.fss.or.kr/api/list.json?{qs}"
    for attempt in range(3):  # 간헐 SSL 타임아웃 재시도
        try:
            with urllib.request.urlopen(url, timeout=20) as r:
                return json.loads(r.read())
        except Exception:
            if attempt == 2:
                raise
            time.sleep(1 + attempt)


def quarters(start_year: int):
    today = date.today()
    y, m = start_year, 1
    while date(y, m, 1) <= today:
        end_m = m + 2
        end_d = date(y, end_m, [31, 30, 30, 31][0] if end_m in (1, 3) else 30)
        # 분기 말일 근사: 3/31, 6/30, 9/30, 12/31
        end_map = {3: 31, 6: 30, 9: 30, 12: 31}
        end_d = date(y, end_m, end_map[end_m])
        yield f"{y}{m:02d}01", end_d.strftime("%Y%m%d")
        m += 3
        if m > 12:
            m, y = 1, y + 1


def _extract(items, out):
    for it in items:
            name = it.get("report_nm", "").strip()
            base, amended = name, "N"
            if base.startswith("["):
                i = base.rfind("]")
                if i >= 0:
                    base, amended = base[i+1:].strip(), "Y"
            etype = TARGETS.get(base)
            if etype:
                out.append({"rcept_dt": it.get("rcept_dt",""), "rcept_no": it.get("rcept_no",""),
                            "corp_name": it.get("corp_name",""), "stock_code": it.get("stock_code","").strip(),
                            "type": etype, "amended": amended})


def collect_quarter(key: str, bgn: str, end: str):
    """1페이지로 total_page 파악 후 나머지 페이지를 8병렬로 수집 — 분기당 수초."""
    import concurrent.futures
    out = []
    d = fetch_page(key, bgn, end, 1)
    st = d.get("status")
    if st == "013":
        return bgn, out
    if st != "000":
        print(f"  [경고] {bgn} p1 status={st} {d.get('message')}", flush=True)
        return bgn, out
    total_page = int(d.get("total_page", 1))
    _extract(d.get("list", []), out)
    if total_page > 1:
        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as px:
            for dd in px.map(lambda p: fetch_page(key, bgn, end, p), range(2, total_page + 1)):
                if dd.get("status") == "000":
                    _extract(dd.get("list", []), out)
    return bgn, out


def main() -> int:
    import concurrent.futures, threading
    key = load_key()
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    state_path = OUT_PATH.parent / ".buyback_state"
    done = set(state_path.read_text().split()) if state_path.exists() else set()
    rows, seen = [], set()
    if OUT_PATH.exists():
        with OUT_PATH.open(encoding="utf-8") as f:
            for r in csv.DictReader(f):
                rows.append(dict(r)); seen.add(r["rcept_no"])
    todo = [(b, e) for b, e in quarters(2015) if b not in done]
    print(f"남은 분기 {len(todo)}개, 기존 {len(rows)}건", flush=True)
    lock = threading.Lock()
    deadline = time.time() + int(os.environ.get("BUDGET_SECONDS", "450"))

    def save():
        rows.sort(key=lambda r: r["rcept_dt"])
        with OUT_PATH.open("w", newline="", encoding="utf-8") as f:
            w = csv.DictWriter(f, fieldnames=["rcept_dt","rcept_no","corp_name","stock_code","type","amended"])
            w.writeheader(); w.writerows(rows)
        state_path.write_text(" ".join(sorted(done)))

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as ex:
        futs = {ex.submit(collect_quarter, key, b, e): b for b, e in todo}
        for fut in concurrent.futures.as_completed(futs):
            bgn, out = fut.result()
            with lock:
                added = 0
                for r in out:
                    if r["rcept_no"] not in seen:
                        seen.add(r["rcept_no"]); rows.append(r); added += 1
                done.add(bgn); save()
                print(f"[{bgn[:4]}Q{(int(bgn[4:6])-1)//3+1}] +{added} (누적 {len(rows)}건, 완료 {len(done)}분기)", flush=True)
            if time.time() > deadline:
                print("시간 예산 소진 — 재실행으로 이어받기", flush=True)
                for f2 in futs:
                    f2.cancel()
                break
    listed = [r for r in rows if r["stock_code"]]
    print(f"현재 상태: {len(rows)}건 (상장 {len(listed)}) -> {OUT_PATH}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
