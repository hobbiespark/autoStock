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
class RateLimited(Exception):
    """DART 한도/차단 응답(020/021/800 등) — 실패 기록이 아니라 전체 중단 사유."""

# 전역 속도 제한 — DART는 분당 과다 요청 시 차단한다. 안전하게 초당 ~6건(분당 ~360)으로 묶는다.
_rl_lock=threading.Lock(); _rl_next=[0.0]
def _throttle():
    with _rl_lock:
        now=time.time()
        wait=_rl_next[0]-now
        _rl_next[0]=max(now,_rl_next[0])+1.0/6.0
    if wait>0: time.sleep(wait)

def fetch(key, corp, year, fs):
    qs=urllib.parse.urlencode({"crtfc_key":key,"corp_code":corp,"bsns_year":year,"reprt_code":"11011","fs_div":fs})
    for a in range(3):
        try:
            _throttle()
            with urllib.request.urlopen(f"https://opendart.fss.or.kr/api/fnlttSinglAcntAll.json?{qs}",timeout=20) as r:
                d=json.loads(r.read())
            if d.get('status') in ('020','021','800','901'):
                raise RateLimited(d.get('status'))
            return d
        except RateLimited: raise
        except Exception:
            if a==2: raise
            time.sleep(1+a)
def collect(key, corp, stock, year):
    for fs in ("CFS","OFS"):
        d=fetch(key,corp,year,fs)  # RateLimited는 그대로 위로 던진다(실패 기록 금지)
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
    lock=threading.Lock(); started=time.time()
    deadline=started+int(os.environ.get('BUDGET_SECONDS','100')); ok=ng=0
    def save():
        with out.open('w',newline='',encoding='utf-8') as f:
            w=csv.DictWriter(f,fieldnames=COLS); w.writeheader(); w.writerows(rows)
        failp.write_text(' '.join(sorted(failed)))
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as ex:
        futs=[ex.submit(collect,key,c,s,year) for c,s in todo]
        idx={f:(c,s) for f,(c,s) in zip(futs,todo)}
        aborted=False
        streak=0  # 연속 실패 — IP 차단(RemoteDisconnected 등 무응답)은 status가 없어 이걸로만 감지된다
        STREAK_ABORT=25
        for fu in concurrent.futures.as_completed(futs):
            c,s=idx[fu]
            try: row=fu.result()
            except RateLimited as e:
                print(f"[!] DART 한도/차단 응답(status={e}) — 실패 기록 없이 즉시 중단. 잠시(또는 내일) 후 재실행하면 이어짐",flush=True)
                aborted=True
                for f2 in futs: f2.cancel()
                break
            except concurrent.futures.CancelledError: continue
            except Exception: row=None; neterr=True
            else: neterr=False
            # 중요(2026-09-12 실측): "status 013 조회된 데이타가 없습니다"(=row None)는 API가 정상
            # 동작 중이라는 증거이지 장애가 아니다 — 상장폐지·비12월결산·소규모 기업이 유니버스에
            # 다수 섞여 있어 013이 수백 건 연속으로 나오는 구간이 실제로 존재한다(어제 "성공 0"의
            # 진짜 원인). 그래서 연속 카운트는 <b>네트워크 예외로 실패한 경우만</b> 센다 —
            # IP 차단은 응답 없이 연결이 끊기므로(RemoteDisconnected) 정확히 이쪽으로만 잡힌다.
            if neterr:
                streak+=1
                if streak>=STREAK_ABORT:
                    print(f"[!] 네트워크 연속 실패 {streak}회 — IP 차단/장애로 판단, 실패 기록 없이 즉시 중단",flush=True)
                    aborted=True
                    for f2 in futs: f2.cancel()
                    break
            else:
                streak=0
            with lock:
                if row: rows.append(row); done.add(c); ok+=1
                else: failed.add(c); ng+=1
                n=ok+ng
                if n%50==0:
                    if n%200==0: save()
                    elapsed=time.time()-started
                    rate=n/elapsed if elapsed>0 else 0
                    remain=len(todo)-n
                    eta=int(remain/rate) if rate>0 else 0
                    bar_len=24; filled=int(bar_len*n/max(1,len(todo)))
                    bar='#'*filled+'-'*(bar_len-filled)
                    print(f"  [{bar}] {n}/{len(todo)} ({100*n/max(1,len(todo)):.1f}%) "
                          f"성공 {ok}/실패 {ng} | {rate:.1f}건/s | 예상 잔여 {eta//60}분 {eta%60}초",flush=True)
            if time.time()>deadline:
                for f2 in futs: f2.cancel()
                break
    if aborted:
        # 차단 구간에서 기록된 실패는 "그 기업의 문제"가 아니므로 이번 실행분을 되돌린다 —
        # 남겨두면 다음 실행이 정상 기업을 영구히 건너뛴다(2026-09-11 오염 사례).
        failed=set(failp.read_text().split()) if failp.exists() else set()
        print(f"[!] 중단 — 이번 실행의 실패 기록은 저장하지 않음(정상 기업 오염 방지). 완료 {len(rows)}건 보존",flush=True)
    save()
    print(f"[{year}] 현재 {len(rows)}건 수집 (미제공/비12월결산 등 실패 {len(failed)})",flush=True)
if __name__=='__main__': sys.exit(main())
