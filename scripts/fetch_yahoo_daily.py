#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
야후 파이낸스 차트 API에서 국내 종목 일봉을 내려받아
CandleCsvLoader가 읽을 수 있는 형식(date,open,high,low,close,volume)의
CSV로 저장하는 스크립트.

사용법:
    python3 scripts/fetch_yahoo_daily.py

내려받는 종목(기본값):
    005930.KS (삼성전자), 000660.KS (SK하이닉스), 035420.KS (NAVER),
    035720.KS (카카오), 069500.KS (KODEX200)

핵심 처리 — 분할/배당 왜곡 보정
    야후가 주는 raw OHLC는 액면분할·배당 등 기업행위를 반영하지 않은
    "당시 실제 호가"다. 반면 adjclose는 그런 이벤트를 모두 역산해 보정한
    종가다. 두 값의 비율 factor = adjclose / close 를 구해서 그날의
    O/H/L/C 전체에 곱하면(거래량은 그대로) "쭉 이어지는" 조정 OHLC를
    만들 수 있다. 이렇게 하지 않으면 예를 들어 액면분할일 전후로
    캔들 하나가 갑자기 반토막 나서 변동성 돌파 전략의 목표가 계산이
    완전히 망가진다.

    다만 O/H/L/C를 "각각 자기 자신의 factor"로 스케일하는 것이 아니라
    전부 "그날의 close 기준 factor"로 통일해서 곱한다 — 야후가 제공하는
    adjclose는 종가 하나에 대해서만 계산되므로, 같은 날 안에서는 하루
    전체에 동일한 배율을 적용하는 것이 합리적인 근사다(하루 안에서
    분할이 일어나지는 않으므로 왜곡이 없다).

null 봉(거래정지일) 처리
    야후 응답은 거래정지일 등에 대해 open/high/low/close/volume 중
    일부 또는 전부를 null로 채워 반환한다. 이런 봉은 통째로 제거한다
    (보간하지 않는다 — 실제로 거래가 없었던 날을 가짜 가격으로
    채우면 그 자체가 왜곡이다).
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timezone

# 야후는 브라우저 User-Agent가 없는 요청을 종종 차단한다.
USER_AGENT = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
)

CHART_URL = (
    "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}"
    "?period1={period1}&period2={period2}&interval=1d"
)

# 종목코드(.KS 포함) 목록 — 기본 5종목.
SYMBOLS = [
    "005930.KS",  # 삼성전자
    "000660.KS",  # SK하이닉스
    "035420.KS",  # NAVER
    "035720.KS",  # 카카오
    "069500.KS",  # KODEX200
]

PERIOD1 = int(datetime(2019, 1, 1, tzinfo=timezone.utc).timestamp())


def fetch_chart(symbol: str, period1: int, period2: int) -> dict:
    """야후 차트 API를 호출해 JSON을 dict로 반환한다."""
    url = CHART_URL.format(symbol=symbol, period1=period1, period2=period2)
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=30) as resp:
        payload = json.loads(resp.read().decode("utf-8"))

    result = payload.get("chart", {}).get("result")
    if not result:
        error = payload.get("chart", {}).get("error")
        raise RuntimeError(f"{symbol} 응답에 result가 없음: {error}")
    return result[0]


def to_adjusted_rows(chart_result: dict) -> list[tuple[date, float, float, float, float, int]]:
    """차트 API 결과를 (날짜, 조정시가, 조정고가, 조정저가, 조정종가, 거래량) 목록으로 변환한다.

    - factor = adjclose / close 를 그날의 O/H/L/C 전체에 곱해 분할/배당 왜곡을 보정한다.
    - O/H/L/C/volume 중 하나라도 null이면 거래정지일로 보고 그 봉을 통째로 버린다.
    """
    timestamps = chart_result.get("timestamp") or []
    quote = chart_result.get("indicators", {}).get("quote", [{}])[0]
    adjclose_list = chart_result.get("indicators", {}).get("adjclose", [{}])[0].get("adjclose") or []

    opens = quote.get("open") or []
    highs = quote.get("high") or []
    lows = quote.get("low") or []
    closes = quote.get("close") or []
    volumes = quote.get("volume") or []

    rows = []
    for i, ts in enumerate(timestamps):
        o = opens[i] if i < len(opens) else None
        h = highs[i] if i < len(highs) else None
        l = lows[i] if i < len(lows) else None
        c = closes[i] if i < len(closes) else None
        v = volumes[i] if i < len(volumes) else None
        adj = adjclose_list[i] if i < len(adjclose_list) else None

        # null 봉(거래정지일 등)은 제거 — 어느 필드든 하나라도 비어 있으면 신뢰할 수 없다.
        if None in (o, h, l, c, v, adj) or c == 0:
            continue

        factor = adj / c
        adj_o = o * factor
        adj_h = h * factor
        adj_l = l * factor
        adj_c = c * factor  # == adj, factor 정의상 항상 성립

        # 야후 일봉 타임스탬프는 거래소 현지 시각의 장 시작 시점(KRX는 UTC+9 09:00 = UTC 00:00)이므로
        # UTC 기준으로 날짜만 뽑아도 실제 거래일과 일치한다.
        trade_date = datetime.fromtimestamp(ts, tz=timezone.utc).date()
        rows.append((trade_date, adj_o, adj_h, adj_l, adj_c, int(v)))

    # 혹시 모를 정렬 흐트러짐/중복 타임스탬프에 대비해 날짜순으로 정리한다.
    rows.sort(key=lambda r: r[0])
    dedup = {}
    for r in rows:
        dedup[r[0]] = r  # 같은 날짜가 중복되면 마지막 것을 채택
    return [dedup[d] for d in sorted(dedup.keys())]


def write_csv(rows: list[tuple[date, float, float, float, float, int]], out_path: str) -> None:
    """CandleCsvLoader 포맷(date,open,high,low,close,volume)으로 저장한다."""
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("date,open,high,low,close,volume\n")
        for d, o, h, l, c, v in rows:
            f.write(f"{d.isoformat()},{o:.4f},{h:.4f},{l:.4f},{c:.4f},{v}\n")


def main() -> int:
    period2 = int(time.time())
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    data_dir = os.path.join(repo_root, "data")
    os.makedirs(data_dir, exist_ok=True)

    for symbol in SYMBOLS:
        code = symbol.split(".")[0]  # ".KS" 등 거래소 접미사를 뗀 6자리 종목코드
        print(f"[fetch] {symbol} ...", file=sys.stderr)
        try:
            chart_result = fetch_chart(symbol, PERIOD1, period2)
        except (urllib.error.URLError, RuntimeError) as e:
            print(f"[fetch] {symbol} 실패: {e}", file=sys.stderr)
            continue

        rows = to_adjusted_rows(chart_result)
        if not rows:
            print(f"[fetch] {symbol}: 유효한 봉이 없음 — 건너뜀", file=sys.stderr)
            continue

        out_path = os.path.join(data_dir, f"{code}.csv")
        write_csv(rows, out_path)
        print(
            f"[fetch] {symbol} -> {out_path} "
            f"({rows[0][0]} ~ {rows[-1][0]}, {len(rows)}개 봉)",
            file=sys.stderr,
        )

        time.sleep(1)  # 야후 쪽 레이트리밋 회피용 짧은 대기

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
