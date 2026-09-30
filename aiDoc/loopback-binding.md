# 루프백 전용 바인딩 (S2)

- 날짜: 2026-09-29
- 계획: `refactoring-plan.md` S2

## 1. 목적

인증 없이 열린 위험 경로를 외부망에서 막는다. 대상 경로는 다음과 같다.

- `POST /actuator/shutdown`(`access: unrestricted`)
- `/api/dashboard/test-signal`(주문 신호)
- `/api/dashboard/killswitch`
- `/api/dashboard/orders/{id}/cancel`
- `/api/trading/start|stop`
- Swagger try-it-out

기존 설정은 PC의 모든 인터페이스(기본 `0.0.0.0`)에 바인딩했다. 그래서 같은 LAN의 다른 기기가 이 경로들을 호출할 수 있었다.

## 2. 결정과 근거

- **사용자 확정(2026-09-29)**: 원격 접속을 쓰지 않는다. 계획의 선택지 (a)를 채택했다.
- `application.yml`에 `server.address: 127.0.0.1`을 넣었다. 규칙 §5.1(Secure by Default)과 §5.7(관리 경로는 네트워크로 막는다)에 따른 것이다.
- 기존 전제("개인 PC 로컬 운영", yml 주석)를 코드로 강제한 것이다. 기존 결정과 충돌하지 않는다.
- IPv4 루프백에만 바인딩하므로, `localhost`가 `::1`로 먼저 해석되는 클라이언트는 실패할 수 있다. 그래서 두 호출부의 대상을 `127.0.0.1`로 명시했다.
  - `scripts/stop_autostock.bat`
  - `frontend/vite.config.ts`(개발 서버 프록시)

## 3. 버린 대안

- **Spring Security 도입**: 원격 접속이 필요 없으니 YAGNI다. FE, bat, Swagger에 인증 흐름을 붙여야 해서 변경 범위가 크다.
- **기동 시 바인딩 주소 검사(fail-fast)**: 환경변수 `SERVER_ADDRESS`로 의도적으로 여는 경우까지 막는다. 기본값을 안전하게 두는 것으로 충분하다고 판단했다.
- **`server.address: localhost`**: 해석 결과가 환경에 따라 달라진다. 명시적인 IP를 택했다(규칙 §2.1).

## 4. 변경 파일

- `app/src/main/resources/application.yml`: `server.address: 127.0.0.1`
- `scripts/stop_autostock.bat`: curl 대상과 메시지를 `127.0.0.1`로 변경
- `frontend/vite.config.ts`: `/api` 프록시 대상을 `http://127.0.0.1:8080`로 변경

## 5. 함정과 주의

- 원격 접속이 필요해지면 인증을 **먼저** 붙이고 연다. 바인딩만 풀면 무인증 shutdown과 주문 경로가 그대로 노출된다.
- 브라우저에서 `http://localhost:8080`으로 접속하는 것은 IPv6 실패 뒤 IPv4로 폴백하므로 동작한다(**추론**).
- Docker 컨테이너(DB)는 앱에 접속하지 않으므로 영향이 없다.
- `TelegramCommandPoller`는 앱이 텔레그램으로 나가는 호출이라 영향이 없다.

## 6. 롤백

- yml의 `server.address` 줄을 지우거나, 기동 시 환경변수 `SERVER_ADDRESS=0.0.0.0`을 준다.

## 7. 검증 상태

- `.\gradlew.bat test` 전체 통과. 이 테스트는 설정 바인딩을 검증하지 않는다.
- **미검증**: 앱을 실제로 재기동해 확인하지 않았다. 모의 운영 중이라 재기동하지 않았다. 다음 재기동 때 아래를 확인한다.
  1. `netstat -ano | findstr :8080`에서 `127.0.0.1:8080`만 LISTENING인지
  2. `http://localhost:8080` 대시보드가 열리는지
  3. `scripts\stop_autostock.bat`으로 정상 종료되는지
  4. 다른 기기에서 `http://<PC LAN IP>:8080/actuator/health`가 연결 거부되는지

## 8. 남은 일

- 위 7절의 실측.

## 9. 변경 이력

- 2026-09-29: 최초 작성
