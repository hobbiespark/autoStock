#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""트랙 X 진단 — (1) 유니버스 데이터 품질 감사 (2) X1 12-1 모멘텀을 Java 엔진과 독립적으로 재현해 교차검증.
   Java 결과(X1 -77.7%, MDD 87%)가 데이터 오류인지 엔진 버그인지 시장 현상인지 가른다.
   출력: docs/measured/x_diagnose_20260918.txt (표준출력에도 동일)
   사용: python scripts/x_diagnose.py
"""
import csv, math, os, sys, re
from datetime import date
from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).resolve().parent.parent
PRICES = ROOT / "data/universe/prices"
CORP = ROOT / "data/corp_map_all.csv"
OUT = ROOT / "docs/measured/x_diagnose_20260918.txt"
CAL_SYMBOL = "005930"
UNIVERSE = 200; LIQ = 60; LOOKBACK = 252; SKIP = 21; TOPN = 20
OOS_START = date(2016, 1, 4); OOS_END = date(2026, 8, 31)
EXCL = re.compile(r"(호스팩|스팩\d*호|스팩$|부동산투자회사|리츠$|선박투자회사|펀드|맥쿼리인프라)")

lines = []
def out(s=""):
    print(s); lines.append(s)

def load(path):
    rows = []
    with open(path, encoding="utf-8") as f:
        next(f)
        for ln in f:
            c = ln.rstrip("\n").split(",")
            if len(c) < 6: continue
            try:
                d = date.fromisoformat(c[0]); o = float(c[1]); cl = float(c[4]); v = float(c[5])
            except ValueError:
                continue
            if cl <= 0: continue
            rows.append((d, o if o > 0 else cl, cl, v))
    return rows

def main():
    names = {}
    if CORP.exists():
        for r in csv.DictReader(open(CORP, encoding="utf-8")):
            names[r["stock_code"].strip()] = r["corp_name"].strip()
    excluded = {c for c, n in names.items() if EXCL.search(n)}

    cal_rows = load(PRICES / f"{CAL_SYMBOL}.csv")
    calendar = [r[0] for r in cal_rows]
    idx = {d: i for i, d in enumerate(calendar)}
    N = len(calendar)

    # ── 1) 데이터 품질 감사 ─────────────────────────────────────────────
    out("=== 1. 데이터 품질 감사 ===")
    series = {}
    audit = []
    for p in sorted(PRICES.glob("*.csv")):
        code = p.stem
        if code in excluded: continue
        rows = load(p)
        if len(rows) < 300: continue
        close = [math.nan] * N; opn = [math.nan] * N; vol = [0.0] * N
        big = 0; prev = None; dup = 0; seen = set(); zero_vol = 0
        for d, o, c, v in rows:
            i = idx.get(d)
            if i is None: continue
            if d in seen: dup += 1
            seen.add(d)
            close[i] = c; opn[i] = o; vol[i] = v
            if v == 0: zero_vol += 1
            if prev is not None and prev > 0 and abs(c / prev - 1) > 0.4: big += 1
            prev = c
        cl = [c for c in close if not math.isnan(c)]
        ratio = max(cl) / min(cl) if cl else 0
        oc = max(abs(opn[i] / close[i] - 1) for i in range(N) if not math.isnan(close[i]) and not math.isnan(opn[i]))
        audit.append((code, names.get(code, ""), len(cl), big, ratio, dup, zero_vol, oc))
        series[code] = (opn, close, vol)
    out(f"로드 {len(series)}종목 (제외 {len(excluded)}), 캘린더 {N}일 {calendar[0]}~{calendar[-1]}")
    out(f"일간 |수익률|>40% 발생 종목: {sum(1 for a in audit if a[3] > 0)}  / 총 건수 {sum(a[3] for a in audit)}")
    out(f"시가/종가 괴리 >40% 존재 종목: {sum(1 for a in audit if a[7] > 0.4)}")
    out("-- |수익률|>40% 상위 20 (코드, 이름, 일수, 건수, 최고/최저 배율) --")
    for a in sorted(audit, key=lambda a: -a[3])[:20]:
        out(f"  {a[0]} {a[1][:14]:<14} n={a[2]:5d} big={a[3]:3d} ratio={a[4]:9.1f} dup={a[5]} zerovol={a[6]}")
    out("-- 최고/최저 배율 상위 15 --")
    for a in sorted(audit, key=lambda a: -a[4])[:15]:
        out(f"  {a[0]} {a[1][:14]:<14} ratio={a[4]:10.1f} big={a[3]}")

    # ── 2) X1 독립 재현 (월말 형성, 익일 시가 매수, 다음 리밸런싱 익일 시가 매도, 비용 0) ──
    out(); out("=== 2. X1 12-1 모멘텀 top20 독립 재현 (비용 0, 익일 시가 체결) ===")
    month_ends = [i for i in range(N - 1) if calendar[i].month != calendar[i + 1].month]
    start_i = next(i for i, d in enumerate(calendar) if d >= OOS_START)
    end_i = max(i for i, d in enumerate(calendar) if d <= OOS_END)
    forms = [start_i - 1] + [i for i in month_ends if start_i <= i < end_i]
    equity = 1.0; monthly = []
    for k, f in enumerate(forms):
        nxt = forms[k + 1] if k + 1 < len(forms) else end_i - 1
        if f - max(LOOKBACK + 1, LIQ) + 1 < 0: continue
        liq = []
        for code, (o, c, v) in series.items():
            if math.isnan(c[f]): continue
            vals = [c[i] * v[i] for i in range(f - LIQ + 1, f + 1) if not math.isnan(c[i]) and v[i] > 0]
            if len(vals) < LIQ * 0.9: continue
            liq.append((sum(vals) / len(vals), code))
        liq.sort(reverse=True)
        uni = [code for _, code in liq[:UNIVERSE]]
        scored = []
        for code in uni:
            o, c, v = series[code]
            # forward-fill
            hist = []; last = math.nan
            for i in range(f - LOOKBACK, f + 1):
                if not math.isnan(c[i]): last = c[i]
                if math.isnan(last): hist = None; break
                hist.append(last)
            if not hist: continue
            scored.append((hist[-1 - SKIP] / hist[0] - 1, code))
        scored.sort(reverse=True)
        picks = [code for _, code in scored[:TOPN]]
        # 보유 수익: 시가(f+1) → 시가(nxt+1)
        rets = []
        for code in picks:
            o, c, v = series[code]
            b = o[f + 1] if not math.isnan(o[f + 1]) else None
            s = o[nxt + 1] if nxt + 1 < N and not math.isnan(o[nxt + 1]) else None
            if s is None:  # 종료·정지: 마지막 유효 종가
                lastc = [c[i] for i in range(f + 1, min(nxt + 2, N)) if not math.isnan(c[i])]
                s = lastc[-1] if lastc else b
            if b is None or b <= 0: rets.append((0.0, code, None)); continue
            rets.append((s / b - 1, code, (b, s)))
        mret = sum(r for r, _, _ in rets) / len(rets) if rets else 0.0
        equity *= 1 + mret
        monthly.append((calendar[f], mret, equity, rets, len(uni)))
    out(f"형성 {len(monthly)}회, 최종 배수 {equity:.3f} (총수익 {(equity - 1) * 100:+.1f}%)")
    peak = 1.0; mdd = 0
    for _, _, e, _, _ in monthly:
        peak = max(peak, e); mdd = max(mdd, 1 - e / peak)
    out(f"월간 경로 MDD {mdd * 100:.1f}%  (Java 엔진: 총수익 -77.7%, MDD 87.4% — 비용 포함)")
    out("-- 최악 12개월: 형성일, 월수익, 편입 종목 중 최악 5 (코드 수익%) --")
    for d, mret, e, rets, u in sorted(monthly, key=lambda m: m[1])[:12]:
        worst = sorted(rets)[:5]
        out(f"  {d} {mret * 100:+7.1f}%  uni={u}  " + ", ".join(f"{c}({names.get(c, '')[:6]}) {r * 100:+.0f}%" for r, c, _ in worst))
    out("-- 최고 6개월 --")
    for d, mret, e, rets, u in sorted(monthly, key=lambda m: -m[1])[:6]:
        best = sorted(rets, reverse=True)[:3]
        out(f"  {d} {mret * 100:+7.1f}%  " + ", ".join(f"{c}({names.get(c, '')[:6]}) {r * 100:+.0f}%" for r, c, _ in best))
    out("-- 편입 후 -60% 이하 종목 (데이터 오류 의심 우선 점검 대상) --")
    cnt = 0
    for d, mret, e, rets, u in monthly:
        for r, c, bs in rets:
            if r <= -0.6 and bs:
                out(f"  {d} {c} {names.get(c, '')[:10]} 매수 {bs[0]:.0f} → 매도 {bs[1]:.0f} ({r * 100:+.0f}%)"); cnt += 1
    out(f"  합계 {cnt}건")
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"\n[저장] {OUT}")

if __name__ == "__main__":
    sys.exit(main())
