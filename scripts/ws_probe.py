#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
키움 실시간 WebSocket 실측 스크립트.

목적
----
KiwoomWebSocketClient / RealMessageParser(자바 코드)가 "문서만 보고" 추정해
둔 REAL 메시지 필드 번호(FID)를 실제 모의투자 서버 응답으로 확정하기 위한
1회성 진단 도구다. LOGIN → REG(0B 시세, 00 주문체결통보) → 수신을 그대로
찍어서 실제 JSON 구조를 눈으로 확인한다.

왜 CI/샌드박스에서 못 돌리나
----------------------------
빌드/테스트를 도는 샌드박스 컨테이너는 WS 포트(10000) 아웃바운드 접속이
차단되어 있다. 그래서 이 스크립트는 반드시 "개발자 PC(로컬)"에서 직접
실행해야 한다 — 자동화 파이프라인에 넣지 말 것.

사용법
------
    1) 이 저장소 루트에 .env 파일이 있어야 한다.
         KIWOOM_APP_KEY=...
         KIWOOM_APP_SECRET=...
       (이미 app/src/main/resources/application.yml의 paper 프로필이 쓰는
        모의투자 앱키와 동일한 값을 쓴다.)
    2) 표준 라이브러리 외 유일한 의존성인 websockets를 설치한다.
         pip install websockets
    3) 저장소 루트에서 실행한다.
         python3 scripts/ws_probe.py
    4) 60초 동안 들어오는 메시지를 전부 [시:분:초.밀리초] 타임스탬프와 함께
       콘솔에 그대로 출력한다. LOGIN 응답, REG 응답, REAL(0B/00) 메시지의
       실제 JSON 구조를 확인할 수 있다.
    5) 이 출력을 근거로 다음 두 곳의 "TODO Phase 2 실측 확정" 주석을 지우고
       실제 필드 매핑으로 고정한다.
         - app/src/main/java/com/autostock/marketdata/RealMessageParser.java
         - common/src/main/java/com/autostock/common/event/OrderNotice.java

주의
----
- REG(00, grp_no=2)로 계좌 단위 주문체결통보를 등록해 본다. item을 빈 배열로
  등록하는 것이 맞는지는 문서만으로 확정할 수 없었다 — 이 스크립트로 REG
  응답 코드가 정상인지부터 확인해야 한다.
- 체결통보(REAL type=00) 메시지를 실제로 받아보려면, 이 스크립트를 띄운 채
  다른 터미널/HTS·MTS 모의투자 화면에서 실제로 주문을 넣어봐야 한다.
  가만히 기다리기만 하면 0B(시세)만 오고 00(체결통보)은 영영 안 온다.
- PING이 오면 받은 그대로 되돌려줘야 연결이 유지된다 (키움 WS 공통 규칙).
  이 스크립트도 KiwoomWebSocketClient와 동일하게 에코 처리한다.
"""

import asyncio
import json
import os
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime
from pathlib import Path

try:
    import websockets
except ImportError:
    print("이 스크립트는 websockets 패키지가 필요합니다. 먼저 'pip install websockets'를 실행하세요.")
    sys.exit(1)

REPO_ROOT = Path(__file__).resolve().parent.parent
ENV_PATH = REPO_ROOT / ".env"

# application.yml의 paper(모의투자) 프로필과 동일한 엔드포인트.
REST_BASE_URL = "https://mockapi.kiwoom.com"
WS_URL = "wss://mockapi.kiwoom.com:10000/api/dostk/websocket"

PROBE_SYMBOL = "005930"   # 삼성전자 — 거래가 활발해 REAL(0B) 수신 확인이 쉬움
PROBE_SECONDS = 60


def load_env(path: Path) -> dict:
    """
    .env 파일을 최소한으로 직접 파싱한다.
    python-dotenv 같은 외부 패키지를 추가하지 않기 위해 KEY=VALUE 형식만
    아주 단순하게 읽는다 — 따옴표나 이스케이프 같은 건 다루지 않는다.
    """
    values = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, value = line.partition("=")
        values[key.strip()] = value.strip()
    return values


def issue_token(app_key: str, app_secret: str) -> str:
    """
    REST 토큰 발급. 자바 쪽 TokenManager.issue()와 동일한 절차:
    POST /oauth2/token, header api-id: au10001,
    body {"grant_type": "client_credentials", "appkey": ..., "secretkey": ...}
    """
    url = REST_BASE_URL + "/oauth2/token"
    body = json.dumps({
        "grant_type": "client_credentials",
        "appkey": app_key,
        "secretkey": app_secret,
    }).encode("utf-8")

    req = urllib.request.Request(url, data=body, method="POST")
    req.add_header("content-type", "application/json;charset=UTF-8")
    req.add_header("api-id", "au10001")

    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            payload = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "ignore")
        print(f"토큰 발급 실패: HTTP {e.code} {detail}")
        sys.exit(1)
    except urllib.error.URLError as e:
        print(f"토큰 발급 실패: 연결 오류 {e.reason}")
        sys.exit(1)

    token = payload.get("token")
    if not token:
        print(f"토큰 발급 응답에 token 필드가 없습니다: {payload}")
        sys.exit(1)

    print(f"[토큰 발급 성공] return_code={payload.get('return_code')} expires_dt={payload.get('expires_dt')}")
    return token


def ts() -> str:
    """[시:분:초.밀리초] 형식 타임스탬프 — 수신 순서·간격을 눈으로 보기 위함."""
    return datetime.now().strftime("%H:%M:%S.%f")[:-3]


async def probe(token: str) -> None:
    async with websockets.connect(WS_URL, ping_interval=None) as ws:
        # 1) LOGIN — 연결 직후 토큰으로 인증
        login_msg = {"trnm": "LOGIN", "token": token}
        await ws.send(json.dumps(login_msg))
        print(f"[{ts()}] → LOGIN 전송")

        # 2) REG — 시세체결(0B) 등록, 삼성전자
        reg_tick = {
            "trnm": "REG",
            "grp_no": "1",
            "refresh": "1",
            "data": [{"item": [PROBE_SYMBOL], "type": ["0B"]}],
        }
        await ws.send(json.dumps(reg_tick))
        print(f"[{ts()}] → REG(0B, {PROBE_SYMBOL}) 전송")

        # 3) REG — 주문체결통보(00) 등록. 계좌 단위 이벤트라 item은 빈 배열로 시도한다
        #    (문서상 불명확 — 이 REG의 응답 코드로 등록 성공 여부를 확인할 것)
        reg_notice = {
            "trnm": "REG",
            "grp_no": "2",
            "refresh": "1",
            "data": [{"item": [], "type": ["00"]}],
        }
        await ws.send(json.dumps(reg_notice))
        print(f"[{ts()}] → REG(00, grp_no=2) 전송 — 주문체결통보 계좌단위 등록 시도")

        print(f"[{ts()}] {PROBE_SECONDS}초 동안 수신 대기... "
              f"(체결통보를 보려면 이 시간 안에 실제로 주문을 넣어보세요. Ctrl+C로 중단 가능)")

        deadline = time.monotonic() + PROBE_SECONDS
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=remaining)
            except asyncio.TimeoutError:
                break

            print(f"[{ts()}] ← {raw}")

            try:
                msg = json.loads(raw)
            except json.JSONDecodeError:
                continue

            # PING은 받은 그대로 되돌려줘야 연결이 유지된다 (KiwoomWebSocketClient와 동일 규칙)
            if msg.get("trnm") == "PING":
                await ws.send(raw)
                print(f"[{ts()}] → PING 에코")

        print(f"[{ts()}] {PROBE_SECONDS}초 경과 — 수신 종료")


def main() -> None:
    env = {**os.environ, **load_env(ENV_PATH)}
    app_key = env.get("KIWOOM_APP_KEY", "")
    app_secret = env.get("KIWOOM_APP_SECRET", "")
    if not app_key or not app_secret:
        print(f".env({ENV_PATH})에서 KIWOOM_APP_KEY/KIWOOM_APP_SECRET을 찾지 못했습니다.")
        sys.exit(1)

    token = issue_token(app_key, app_secret)
    try:
        asyncio.run(probe(token))
    except KeyboardInterrupt:
        print(f"[{ts()}] 사용자 중단")


if __name__ == "__main__":
    main()
