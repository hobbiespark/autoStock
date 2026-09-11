#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""G2 2단계 — 자사주 이벤트 종목 일봉 수집 (야후, .KS→.KQ 폴백, 이어받기·8병렬).
   기존 실패 종목은 data/events/.price_fail 에 기록해 재시도하지 않음(상장폐지 추정 → 생존 편향 한계로 기록)."""
import csv, os, sys, time, json, urllib.request, concurrent.futures, threading
from datetime import datetime, timezone
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
EV = ROOT/"data/events/buyback_events.csv"
OUT = ROOT/"data/events/prices"; OUT.mkdir(parents=True, exist_ok=True)
FAIL = ROOT/"data/events/.price_fail"
UA = {"User-Agent": "Mozilla/5.0"}
P1 = int(datetime(2014,6,1,tzinfo=timezone.utc).timestamp())  # 이벤트 전 히스토리 여유
P2 = int(time.time())

def fetch(sym):
    url=f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}?period1={P1}&period2={P2}&interval=1d"
    req=urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())

def save(code):
    for suf in (".KS",".KQ"):
        try:
            d=fetch(code+suf)
            res=d.get("chart",{}).get("result")
            if not res: continue
            r=res[0]; ts=r.get("timestamp") or []
            q=r["indicators"]["quote"][0]; adj=r["indicators"].get("adjclose",[{}])[0].get("adjclose")
            rows=[]
            for i,t in enumerate(ts):
                o,h,l,c,v=q["open"][i],q["high"][i],q["low"][i],q["close"][i],q["volume"][i]
                if None in (o,h,l,c) or c==0: continue
                f=(adj[i]/c) if adj and adj[i] else 1.0
                dt=datetime.fromtimestamp(t, timezone.utc).astimezone().strftime("%Y-%m-%d")
                rows.append(f"{dt},{o*f:.4f},{h*f:.4f},{l*f:.4f},{c*f:.4f},{v or 0}")
            if len(rows)<60: continue  # 데이터 빈약 — 폴백/실패 처리
            (OUT/f"{code}.csv").write_text("date,open,high,low,close,volume\n"+"\n".join(rows)+"\n",encoding="utf-8")
            return code, suf, len(rows)
        except Exception:
            time.sleep(0.3)
    return code, None, 0

def main():
    codes=sorted({r["stock_code"] for r in csv.DictReader(open(EV,encoding="utf-8"))
                  if r["stock_code"] and r["amended"]=="N"})
    failed=set(FAIL.read_text().split()) if FAIL.exists() else set()
    todo=[c for c in codes if not (OUT/f"{c}.csv").exists() and c not in failed]
    print(f"대상 {len(codes)}종목 | 완료 {len(codes)-len(todo)-len(failed&set(codes))} | 실패기록 {len(failed&set(codes))} | 남음 {len(todo)}", flush=True)
    deadline=time.time()+int(os.environ.get("BUDGET_SECONDS","100"))
    lock=threading.Lock(); done=fail=0
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        futs=[ex.submit(save,c) for c in todo]
        for fu in concurrent.futures.as_completed(futs):
            code,suf,n=fu.result()
            with lock:
                if suf: done+=1
                else:
                    fail+=1; failed.add(code); FAIL.write_text(" ".join(sorted(failed)))
                if (done+fail)%50==0: print(f"  진행 +{done+fail} (성공 {done}/실패 {fail})", flush=True)
            if time.time()>deadline:
                for f2 in futs: f2.cancel()
                print("예산 소진 — 재실행으로 이어받기", flush=True); break
    have=len(list(OUT.glob('*.csv')))
    print(f"현재 보유 {have}/{len(codes)}종목 (커버리지 {have/len(codes)*100:.1f}%)", flush=True)

if __name__=="__main__": sys.exit(main())
