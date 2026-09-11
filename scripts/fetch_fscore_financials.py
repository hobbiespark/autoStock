#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""G3 — F-Score 산출용 재무 수집 (fnlttSinglAcntAll, 사업보고서 11011, CFS→OFS 폴백).
   출력: data/fscore/fs_{year}.csv (corp_code,stock_code,fs_div + 9계정×당기/전기)
   재개형: 출력 CSV의 corp_code + .fail_{year} 기록은 건너뜀. YEAR·BUDGET_SECONDS 환경변수."""
import csv, json, os, sys, time, threading, urllib.parse, urllib.request, concurrent.futures
from pathlib import Path
ROOT=Path(__file__).resolve().parent.parent
OUT=ROOT/"data/fscore"; OUT.mkdir(parents=True, exist_ok=True)
ACC={"자산총계":"assets","부채총계":"liab","유동자산":"ca","유동부채":"cl","매출액":"rev",
     "매출총이익":"gp","영업이익":"op","당기순이익":"ni","영업활동현금흐름":"cfo"}
COLS=["corp_code","stock_code","fs_div"]+[f"{v}_{t}" for v in ACC.values() for t in("cur","prev")]
def load_key():
    k=os.environ.get('DART_API_KEY','')
    if not k:
        for l in (ROOT/'.env').read_text(encoding='utf-8').splitlines():
            if l.startswith('DART_API_KEY='): k=l.split('=',1)[1].strip()
    return k
def num(s):
    s=(s or '').replace(',','').strip()
    try: return str(int(s))
    except: return ''
def fetch(key, corp, year, fs):
    qs=urllib.parse.urlencode({"crtfc_key":key,"corp_code":corp,"bsns_year":year,"reprt_code":"11011","fs_div":fs})
    for a in range(3):
        try:
            with urllib.request.urlopen(f"https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json?{qs}",timeout=20) as r:
                return json.loads(r.read())
        except Exception:
            if a==2: raise
            time.sleep(1+a)
def collect(key, corp, stock, year):
    for fs in ("CFS","OFS"):
        d=fetch(key,corp,year,fs)
        if d.get('status')!='000': continue
        row={"corp_code":corp,"stock_code":stock,"fs_div":fs}
        got=set()
        for it in d.get('list',[]):
            nm=it.get('account_nm','').strip()
            for k,v in ACC.items():
                if k in nm and v not in got:
                    got.add(v)
                    row[f"{v}_cur"]=num(it.get('thstrm_amount'))
                    row[f"{v}_prev"]=num(it.get('frmtrm_amount'))
        if "assets" in got and "ni" in got:  # 최소 요건
            for c in COLS: row.setdefault(c,'')
            return row
    return None
def main():
    key=load_key(); year=os.environ.get('YEAR','2024')
    out=OUT/f"fs_{year}.csv"; failp=OUT/f".fail_{year}"
    done=set(); rows=[]
    if out.exists():
        for r in csv.DictReader(open(out,encoding='utf-8')): done.add(r['corp_code']); rows.append(r)
    failed=set(failp.read_text().split()) if failp.exists() else set()
    univ=[(r['corp_code'],r['stock_code']) for r in csv.DictReader(open(ROOT/'data/corp_map_all.csv',encoding='utf-8'))]
    todo=[(c,s) for c,s in univ if c not in done and c not in failed]
    print(f"[{year}] 유니버스 {len(univ)} | 완료 {len(done)} | 실패 {len(failed)} | 남음 {len(todo)}",flush=True)
    lock=threading.Lock(); deadline=time.time()+int(os.environ.get('BUDGET_SECONDS','100')); ok=ng=0
    def save():
        with out.open('w',newline='',encoding='utf-8') as f:
            w=csv.DictWriter(f,fieldnames=COLS); w.writeheader(); w.writerows(rows)
        failp.write_text(' '.join(sorted(failed)))
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        futs=[ex.submit(collect,key,c,s,year) for c,s in todo]
        idx={f:(c,s) for f,(c,s) in zip(futs,todo)}
        for fu in concurrent.futures.as_completed(futs):
            c,s=idx[fu]
            try: row=fu.result()
            except Exception: row=None
            with lock:
                if row: rows.append(row); done.add(c); ok+=1
                else: failed.add(c); ng+=1
                if (ok+ng)%200==0:
                    save(); print(f"  +{ok+ng} (성공 {ok}/실패 {ng})",flush=True)
            if time.time()>deadline:
                for f2 in futs: f2.cancel()
                break
    save()
    print(f"[{year}] 현재 {len(rows)}건 수집 (미제공/비12월결산 등 실패 {len(failed)})",flush=True)
if __name__=='__main__': sys.exit(main())
