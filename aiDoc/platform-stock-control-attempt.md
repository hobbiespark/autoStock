# 단일 최초시도와 unknown 기반

2026-10-26. StockControlAttemptStore/V22를추가했다. 명시DataSource/자기verifier/clock/1~30초budget만받고Bean/worker/endpoint/실제명령실행0이다. 신선Verified·원본receipt의동일intent·현재관측highest fence일치를bot잠금안에서확인하고receipt당단한행의running 시도를생성한다. UUID시도ID/최초fence/원본요청지문/실행기한과token SHA256만저장하고rawtoken은private Claim에한번만반환한다. Claim생성자는private이며로그에확인코드/token을보이지않는다. 실제업무효과는수행하지않는다.

begin 재호출/다른인스턴스재접속/새fence원본복구후에도새시도/token발급을거절한다. requireCurrent는자기Claim/Verified원문동일성·현재floor·running/시도/tokenhash·기한과prooffreshness를재검사한다. read와실제업무호출사이의원자성/플랫폼current live·실제업무권한검사까지제공하는것은아니다. markUnknown은기존privateClaim만가능한보수적종결이며멱등이다. 명시recover는신선한동일intent proof로metadata만조회하고expired running을unknown으로바꾼다. 재claim/기한연장/실행token회수는없다.

deadline=min(신선proof기한,앱budget)을microsecond내림하여PG 반올림으로원래기한을늘리지않는다. DBtrigger는시도identity/tokenhash/기한변경·삭제를거절하고 running→unknown만허용한다. 현재정상완료·결과/ACK는미구현이며running상태를성공으로보고하지않는다. 60초일회용resume/거래정책/킬스위치를연결하지않았다.

D-18 고정Timescale이미지/사전적재·새Testcontainers에서신규6+직전receipt6=12통과후submicro기한회귀추가/내림보완으로최종시도7/skipped0통과(재실행중복합산0). 최초시도/private생성·재접속metadata, 동시1, outcomeLost unknown/새fence 재시도거절, 기한정확경계unknown/불연장, 높은floor·다른인자차단, DBidentity삭제/변경42501/tokenhash64, submicro기한불확장검증. 최초Timestamp모호import컴파일실패를명시import로수정했다. Java21/runtime분리build만·시험컨테이너종료/제거확인. 운영DB시험/변경0.

기존dirty보존/새파일과receipt helper의intent package-scope공유만변경. 운영migration/DB/봇/offset/서비스/실제제어0. 다음app결과/terminal·durable ACK계약과최초시도불변연결기반→현재권한live·최종업무검사/실제adapter·60초일회용확인보존. 실제sender/receiver/거래권한확대는하지않는다.
