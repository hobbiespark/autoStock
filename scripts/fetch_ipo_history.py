#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
공모주 청약 추천 전략 검증용 과거 자료 수집 (2026-10-15, aiDoc/ipo-subscription-strategy.md).

무엇을 모으나(회사마다 한 줄):
  - DART [발행조건확정]증권신고서(지분증권) 접수번호·접수일 — 원본 XML은 data/ipo/history/docs/에 저장한다.
    기관경쟁률·의무보유확약·확정 공모가·희망 밴드는 앱의 파서(Java, 시험으로 검증된 것)로 읽는다
    (IpoHistoryParseTool — app 시험 소스).
  - 상장 직후 유통가능물량: 투자설명서(없으면 증권신고서) 본문의
    "상장예정주식수 N주 중 약 X%에 해당하는 M주는 상장 직후 유통가능물량" 문장.
  - 상장일 시가·고가·저가·종가와 5거래일째 종가: 네이버 금융 일봉(siseJson).

대상: 기간 안에 [발행조건확정]이 나온 회사 전부. 스팩은 이름으로 뺀다. 상장사 유상증자는 수요예측 결과가 없어
파서 단계에서 빠진다. 이미 상장돼 있던 회사(확정 30일 전에 시세가 있음)는 pre_listed=1로 표시한다.

사용: python scripts/fetch_ipo_history.py [시작 YYYYMMDD] [끝 YYYYMMDD] [시간 예산(초)]
      python scripts/fetch_ipo_history.py reprice  — 상장일 시세를 수정주가 아닌 값(네이버 모바일)으로 다시 채운다
      python scripts/fetch_ipo_history.py snippets [시간 예산(초)]  — 유통가능물량 문단을 float/{회사코드}.txt로 남긴다
        (앱의 FloatSharesParser가 같은 글로 읽는다 — 표현이 회사마다 달라 파이썬 정규식은 참고용)   (기본 20250401 ~ 오늘, 예산 없음)
      .env의 DART_API_KEY가 필요하다. 키는 출력하지 않는다. 결과: data/ipo/history/ipo_candidates.csv
      이어서 할 수 있다 — 목록 쪽은 list_pages/에, 회사별 결과는 CSV에 쌓고, 예산을 다 쓰면 종료 코드 3으로 끝난다
      (다시 실행하면 남은 것부터). DART 응답이 쪽마다 수 초 걸려 전부 받는 데 수십 분이 든다.
"""
import csv
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timedelta
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
OUT = REPO / "data" / "ipo" / "history"
DOCS = OUT / "docs"
SNIPPETS = OUT / "float"
LIST_CACHE = OUT / "list_rows.jsonl"
LIST_PAGES = OUT / "list_pages"
DEADLINE = [None]


class OutOfTime(Exception):
    pass


def check_time():
    if DEADLINE[0] is not None and time.time() > DEADLINE[0]:
        raise OutOfTime()
CSV_PATH = OUT / "ipo_candidates.csv"

CONFIRMED = "[발행조건확정]증권신고서(지분증권)"
SPAC = re.compile(r"스팩|기업인수목적")
FLOAT_SENTENCE = re.compile(
    r"상장예정주식수\s*(?:총\s*)?([\d,]+)\s*주\s*중\s*(?:약\s*)?([\d.]+)\s*%\s*에?\s*해당하는\s*(?:보통주\s*)?([\d,]+)\s*주[는가이]?\s*"
    r"(?:상장\s*(?:직후|후|당일|일)\s*)?(?:즉시\s*)?유통\s*가능")


def load_key() -> str:
    key = os.environ.get("DART_API_KEY", "")
    env = REPO / ".env"
    if not key and env.is_file():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("DART_API_KEY="):
                key = line.split("=", 1)[1].strip()
    if not key:
        sys.exit("DART_API_KEY 없음 (.env 확인)")
    return key


KEY = load_key()


def http(url: str, timeout: int = 40) -> bytes:
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 autostock-research"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:  # 간헐 SSL·타임아웃 — 짧게 쉬고 다시
            if attempt == 3:
                raise
            time.sleep(2 + attempt * 3)
    raise RuntimeError("unreachable")


def dart(path: str, **params) -> bytes:
    params["crtfc_key"] = KEY
    return http("https://opendart.fss.or.kr/api/" + path + "?" + urllib.parse.urlencode(params))


def list_page(bgn: date, end: date, page: int) -> dict:
    """한 쪽 — 받은 쪽은 list_pages/에 남겨 다시 받지 않는다."""
    path = LIST_PAGES / f"{bgn:%Y%m%d}_{end:%Y%m%d}_{page:03d}.json"
    if path.is_file():
        return json.loads(path.read_text(encoding="utf-8"))
    check_time()
    j = json.loads(dart("list.json", pblntf_ty="C", bgn_de=bgn.strftime("%Y%m%d"), end_de=end.strftime("%Y%m%d"),
                        page_no=page, page_count=100))
    if j.get("status") not in ("000", "013"):
        raise RuntimeError(f"list.json 오류 {j.get('status')} {j.get('message')} ({bgn}~{end}, {page}쪽)")
    keep = dict(j)
    keep["list"] = [r for r in j.get("list", []) if "증권신고서(지분증권)" in r["report_nm"] or "투자설명서" in r["report_nm"]]
    path.write_text(json.dumps(keep, ensure_ascii=False), encoding="utf-8")
    return keep


def list_rows(start: date, end: date) -> list:
    """pblntf_ty=C(발행공시) 전부 — 회사 지정 없는 조회는 3개월 창이라 나눠서 모든 쪽을 읽는다(쪽은 동시에 6개씩)."""
    if LIST_CACHE.is_file():
        rows = [json.loads(l) for l in LIST_CACHE.read_text(encoding="utf-8").splitlines() if l.strip()]
        print(f"목록 캐시 {len(rows)}건 사용 ({LIST_CACHE})")
        return rows
    LIST_PAGES.mkdir(parents=True, exist_ok=True)
    windows = []
    cur = start
    while cur <= end:
        stop = min(cur + timedelta(days=85), end)
        windows.append((cur, stop))
        cur = stop + timedelta(days=1)
    with ThreadPoolExecutor(max_workers=6) as pool:
        firsts = list(pool.map(lambda w: list_page(w[0], w[1], 1), windows))
        jobs = []
        for (bgn, stop), first in zip(windows, firsts):
            total = first.get("total_page", 0) if first.get("status") == "000" else 0
            jobs += [(bgn, stop, p) for p in range(2, total + 1)]
        pages = firsts + list(pool.map(lambda job: list_page(*job), jobs))
    rows = [r for j in pages for r in j.get("list", [])]
    LIST_CACHE.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in rows), encoding="utf-8")
    print(f"  목록 {len(pages)}쪽 → 지분증권 신고·투자설명서 {len(rows)}건", flush=True)
    return rows


def main_xml(rcept_no: str):
    raw = dart("document.xml", rcept_no=rcept_no)
    try:
        z = zipfile.ZipFile(io.BytesIO(raw))
    except zipfile.BadZipFile:
        return None, None
    chosen = None
    for info in z.infolist():
        name = info.filename
        if not name.lower().endswith(".xml"):
            continue
        if chosen is None or name == rcept_no + ".xml":
            chosen = z.read(info)
        if name == rcept_no + ".xml":
            break
    if chosen is None:
        return None, None
    head = chosen[:300].decode("ascii", "ignore").lower()
    enc = "euc-kr" if "euc-kr" in head else "utf-8"
    return chosen, chosen.decode(enc, "replace")


def plain(x: str) -> str:
    t = re.sub(r"<[^>]+>", " ", x)
    t = t.replace("&nbsp;", " ").replace("&amp;", "&")
    return re.sub(r"\s+", " ", t)


def float_info(text: str):
    """(상장예정주식수, 유통가능 주식수, 본문 비율 %, 문장) — 못 찾으면 None."""
    m = FLOAT_SENTENCE.search(text)
    if not m:
        return None
    listed = int(m.group(1).replace(",", ""))
    floating = int(m.group(3).replace(",", ""))
    return listed, floating, float(m.group(2)), m.group(0)


def prices(code: str, around: date):
    """확정일 30일 전 ~ 50일 뒤 일봉. 반환: (확정 전에 이미 시세가 있었나, 상장일 행, 5거래일째 행)."""
    url = ("https://api.finance.naver.com/siseJson.naver?" + urllib.parse.urlencode({
        "symbol": code, "requestType": 1, "timeframe": "day",
        "startTime": (around - timedelta(days=30)).strftime("%Y%m%d"),
        "endTime": (around + timedelta(days=50)).strftime("%Y%m%d")}))
    body = http(url).decode("utf-8", "replace")
    rows = re.findall(r'\["(\d{8})",\s*([\d.]+),\s*([\d.]+),\s*([\d.]+),\s*([\d.]+),\s*([\d.]+)', body)
    if not rows:
        return None, None, None
    rows = [(datetime.strptime(r[0], "%Y%m%d").date(), float(r[1]), float(r[2]), float(r[3]), float(r[4]), float(r[5]))
            for r in rows]
    pre = any(r[0] < around for r in rows)
    after = [r for r in rows if r[0] >= around]
    if not after:
        return pre, None, None
    first = after[0]
    fifth = after[4] if len(after) >= 5 else None
    return pre, first, fifth


def main():
    start = datetime.strptime(sys.argv[1], "%Y%m%d").date() if len(sys.argv) > 1 else date(2025, 4, 1)
    end = datetime.strptime(sys.argv[2], "%Y%m%d").date() if len(sys.argv) > 2 else date.today()
    if len(sys.argv) > 3:
        DEADLINE[0] = time.time() + float(sys.argv[3])
    DOCS.mkdir(parents=True, exist_ok=True)
    try:
        rows = list_rows(start, end)
    except OutOfTime:
        print(f"시간 예산 끝(목록 {len(list(LIST_PAGES.glob('*.json')))}쪽 받음) — 다시 실행하면 이어서 한다")
        sys.exit(3)

    by_corp = {}
    for r in rows:
        by_corp.setdefault(r["corp_code"], []).append(r)
    candidates = []
    for corp, rs in by_corp.items():
        confirmed = sorted([r for r in rs if CONFIRMED in r["report_nm"]], key=lambda r: r["rcept_no"])
        if not confirmed or SPAC.search(rs[0]["corp_name"]):
            continue
        candidates.append((corp, rs, confirmed))
    print(f"[발행조건확정] 회사 {len(candidates)}곳(스팩 제외)")

    done = {}
    if CSV_PATH.is_file():
        with CSV_PATH.open(encoding="utf-8") as f:
            for row in csv.DictReader(f):
                done[row["corp_code"]] = row
    fields = ["corp_code", "corp_name", "stock_code", "corp_cls", "confirmed_rcept_nos", "confirmed_dt",
              "float_source", "listed_shares", "float_shares", "float_pct_text", "float_ratio",
              "pre_listed", "listing_date", "open", "high", "low", "close", "volume", "d5_date", "d5_close", "note"]
    def work(item):
        corp, rs, confirmed = item
        if corp in done and done[corp].get("note") in ("", "pre_listed"):
            return done[corp]
        if DEADLINE[0] is not None and time.time() > DEADLINE[0]:
            return done.get(corp)   # 이번에는 못 한다 — 다음 실행에서
        return collect(corp, rs, confirmed, fields)

    out = []
    with ThreadPoolExecutor(max_workers=4) as pool:
        for i, row in enumerate(pool.map(work, candidates), 1):
            if row is None:
                continue
            out.append(row)
            print(f"{i}/{len(candidates)} {row['corp_name']} {row['stock_code']} 유통 {row['float_ratio'] or '-'} "
                  f"상장 {row['listing_date'] or '-'}", flush=True)
            if i % 20 == 0:
                write(out, fields)
    write(out, fields)
    left = len(candidates) - sum(1 for r in out if r.get("note") in ("", "pre_listed"))
    print(f"저장: {CSV_PATH} ({len(out)}줄, 남은 회사 {left})")
    if left:
        sys.exit(3)


def collect(corp, rs, confirmed, fields):
    name = rs[0]["corp_name"]
    row = {k: "" for k in fields}
    row.update(corp_code=corp, corp_name=name, confirmed_rcept_nos=" ".join(r["rcept_no"] for r in confirmed),
               confirmed_dt=confirmed[-1]["rcept_dt"])
    codes = [r.get("stock_code", "").strip() for r in rs if r.get("stock_code", "").strip()]
    row["stock_code"] = codes[0] if codes else ""
    row["corp_cls"] = rs[0].get("corp_cls", "")
    try:
        confirmed_dt = confirmed[-1]["rcept_dt"]
        # 시세 먼저 — 확정 전부터 시세가 있으면 이미 상장된 회사(상장사 유상증자·이전상장)라 원본을 받지 않는다
        if row["stock_code"]:
            cdt = datetime.strptime(confirmed_dt, "%Y%m%d").date()
            pre, first, fifth = prices_unadjusted(row["stock_code"], cdt)
            row["pre_listed"] = int(bool(pre))
            if pre:
                row["note"] = "pre_listed"
                return row
            if first:
                row.update(listing_date=first[0].isoformat(), open=first[1], high=first[2], low=first[3],
                           close=first[4], volume=first[5])
            if fifth:
                row.update(d5_date=fifth[0].isoformat(), d5_close=fifth[4])
        # 확정 신고서 원본(모두) — 수요예측 표가 빠진 확정 신고서가 있다(진코스텍 9/28 첫 건)
        for c in confirmed:
            path = DOCS / (c["rcept_no"] + ".xml")
            if not path.exists():
                raw, _ = main_xml(c["rcept_no"])
                if raw is not None:
                    path.write_bytes(raw)
        # 유통가능물량 — 확정일 이후 투자설명서 → 원 증권신고서 순서
        prospectus = sorted([r for r in rs if "투자설명서" in r["report_nm"] and r["rcept_dt"] >= confirmed_dt],
                            key=lambda r: r["rcept_no"], reverse=True)
        filings = sorted([r for r in rs if "증권신고서(지분증권)" in r["report_nm"] and CONFIRMED not in r["report_nm"]],
                         key=lambda r: r["rcept_no"], reverse=True)
        info = None
        for src in prospectus[:1] + filings[:2]:
            _, text = main_xml(src["rcept_no"])
            if text is None:
                continue
            info = float_info(plain(text))
            if info:
                row["float_source"] = src["rcept_no"]
                break
        if info:
            listed, floating, pct, _ = info
            row.update(listed_shares=listed, float_shares=floating, float_pct_text=pct,
                       float_ratio=round(floating / listed, 4) if listed else "")
    except Exception as e:
        row["note"] = "error"
        print(f"  ! {name}: {type(e).__name__}: {e}", flush=True)
    return row


def write(out, fields):
    with CSV_PATH.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fields)
        w.writeheader()
        for r in out:
            w.writerow({k: r.get(k, "") for k in fields})


def snippet_windows(text: str, width: int = 1500) -> str:
    """'상장 직후'·'유통가능' 문장 주변 글 — 겹치는 창은 합친다."""
    spans = []
    for m in re.finditer(r"유통\s*가능", text):
        a, b = max(0, m.start() - width), min(len(text), m.end() + 300)
        if spans and a <= spans[-1][1]:
            spans[-1][1] = max(spans[-1][1], b)
        else:
            spans.append([a, b])
    return "\n\n".join(text[a:b] for a, b in spans)


def snippets(budget):
    deadline = time.time() + budget if budget else None
    SNIPPETS.mkdir(parents=True, exist_ok=True)
    rows = [json.loads(l) for l in LIST_CACHE.read_text(encoding="utf-8").splitlines() if l.strip()]
    with CSV_PATH.open(encoding="utf-8") as f:
        ipos = [r for r in csv.DictReader(f) if r["note"] == ""]
    todo = [r for r in ipos if not (SNIPPETS / (r["corp_code"] + ".txt")).exists()]
    print(f"공모주 {len(ipos)}곳 중 남은 {len(todo)}곳")

    def one(r):
        if deadline and time.time() > deadline:
            return None
        rs = [x for x in rows if x["corp_code"] == r["corp_code"]]
        prospectus = sorted([x for x in rs if "투자설명서" in x["report_nm"] and x["rcept_dt"] >= r["confirmed_dt"]],
                            key=lambda x: x["rcept_no"], reverse=True)
        filings = sorted([x for x in rs if "증권신고서(지분증권)" in x["report_nm"] and CONFIRMED not in x["report_nm"]],
                         key=lambda x: x["rcept_no"], reverse=True)
        for src in prospectus[:1] + filings[:2]:
            _, text = main_xml(src["rcept_no"])
            if text is None:
                continue
            body = snippet_windows(plain(text))
            if "상장 직후" in body or "상장직후" in body:
                (SNIPPETS / (r["corp_code"] + ".txt")).write_text(f"# {r['corp_name']} {src['rcept_no']} {src['report_nm']}\n" + body,
                                                                 encoding="utf-8")
                return r["corp_name"] + " " + src["rcept_no"]
        (SNIPPETS / (r["corp_code"] + ".txt")).write_text(f"# {r['corp_name']} 문장 없음\n", encoding="utf-8")
        return r["corp_name"] + " 없음"

    with ThreadPoolExecutor(max_workers=4) as pool:
        for res in pool.map(one, todo):
            if res:
                print(" ", res, flush=True)
    left = sum(1 for r in ipos if not (SNIPPETS / (r["corp_code"] + ".txt")).exists())
    print(f"남은 {left}곳")
    if left:
        sys.exit(3)


def prices_unadjusted(code: str, around: date):
    """상장일 시세(수정주가 아님) — 네이버 모바일 시세(m.stock.naver.com)를 최근부터 거꾸로 읽는다.
    siseJson은 수정주가라 상장 뒤 무상증자한 회사의 상장일 가격이 공모가와 맞지 않는다(지투지바이오: 수정 28,848원, 실제 86,500원).
    반환: (확정 전에 시세가 있었나, 상장일 행, 5거래일째 행) — 행은 (날짜, 시가, 고가, 저가, 종가, 거래량)."""
    rows = []
    for page in range(1, 40):
        body = http(f"https://m.stock.naver.com/api/stock/{code}/price?pageSize=60&page={page}")
        data = json.loads(body.decode("utf-8"))
        if not data:
            break
        for x in data:
            num = lambda k: float(str(x[k]).replace(",", "")) if x.get(k) not in (None, "") else None
            rows.append((datetime.strptime(x["localTradedAt"], "%Y-%m-%d").date(), num("openPrice"), num("highPrice"),
                         num("lowPrice"), num("closePrice"), num("accumulatedTradingVolume")))
        if rows[-1][0] < around - timedelta(days=30):
            break
    rows.sort(key=lambda r: r[0])
    if not rows:
        return None, None, None
    pre = any(r[0] < around for r in rows)
    after = [r for r in rows if r[0] >= around]
    if not after:
        return pre, None, None
    return pre, after[0], after[4] if len(after) >= 5 else None


def reprice():
    """ipo_candidates.csv의 공모주 행 시세를 수정주가 아닌 값으로 다시 채운다."""
    with CSV_PATH.open(encoding="utf-8") as f:
        reader = csv.DictReader(f)
        fields = reader.fieldnames
        rows = list(reader)

    def one(r):
        if r["note"] != "" or not r["stock_code"]:
            return r
        cdt = datetime.strptime(r["confirmed_dt"], "%Y%m%d").date()
        pre, first, fifth = prices_unadjusted(r["stock_code"], cdt)
        r["pre_listed"] = int(bool(pre))
        for k in ("listing_date", "open", "high", "low", "close", "volume", "d5_date", "d5_close"):
            r[k] = ""
        if first:
            r.update(listing_date=first[0].isoformat(), open=first[1], high=first[2], low=first[3], close=first[4],
                     volume=first[5])
        if fifth:
            r.update(d5_date=fifth[0].isoformat(), d5_close=fifth[4])
        return r

    with ThreadPoolExecutor(max_workers=6) as pool:
        rows = list(pool.map(one, rows))
    write(rows, fields)
    print(f"시세 다시 채움: {sum(1 for r in rows if r['note'] == '' and r['listing_date'])}곳")


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "reprice":
        reprice()
    elif len(sys.argv) > 1 and sys.argv[1] == "snippets":
        snippets(float(sys.argv[2]) if len(sys.argv) > 2 else None)
    else:
        main()
