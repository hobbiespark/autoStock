# 운영자 stop 요청 durable 접수·observe 기반

2026-10-26. HandoverOperatorRequestStore/V26을추가했다. 명시DataSource/자기gate만받으며Bean/listener/키파일/poller호출0이다. receive는신선한private Verified의stop만받고UUID advisory잠금뒤지문/키ID/instance를대조해원본1행을접수한다. 같은ID/같은인자는원본시각·기한복구/duplicate, 다른인자는충돌이다. commit까지gate신선도를재검사하고commit실패시성공receipt를반환하지않는다. 자동retry는없다.

observe는별도의신선한서명요청/target_request_id만받고동일키ID/instance의원본metadata를읽는다. 오래된stop자체의재전달은거절하며새observe로옛receipt만볼수있다. observe는새stop접수/기한연장이아니고기록없는ID는empty이다. gate소유/역할혼용을거절한다. Request/Receipt 데이터객체는실행허가아니며실행token/worker/실제정지/unknown처리·keyrotation은후속이다. 상태는received뿐이다.

V26은request UUID유일/원본행수정·삭제불가·stop/received고정이며issued/expires의canonical 원문시각을text로보존해나노초정보를잃지않는다. MAC/key 원문·봇token·확인코드를DB에저장하지않는다. DBowner까지악의적DDL변경을막는주장은아니다. metadata도운영private자료이며Git/알림에실제값을넣지않는다.

이번 D18고정Timescale/newTestcontainers·JDK21/runtime layout2 분리build: 신규DB7+gate7 표적명령성공/실패0. 접수/동시중복1/재접속원본, 인증된changed body충돌, 만료stop거절/신선observe조회·미존재empty/역할혼용거절, deferred commit실패주입시row0/명시재시도, foreign gate거절, DB원본수정/삭제42501을검증했다. 이어서기존SchemaAndTimeZoneDbTest 기대Flyway25→26 한줄수정후표적클래스통과(XML증거별도). 기존전체1092회귀는반복하지않았다. 시험커넥션종료·최종Testcontainers제거확인·운영DB시험/변경0. source접수는실제명령실행을시험한것이아니다.

기존infra/복구스크립트dirty보존·신규파일/정확한보조변경만커밋. 다음146 durable단일실행/unknown·명시executor경계. 실제poller호출이붙을때기한을잠금대기후에도검사하고현재active stop/원본정지원장과대조해야한다. 응답유실을stop재호출/자동resume으로처리하지않는다. listener/CLI·운영키/활성·실제이관은아직미구현/미적용이며54/118을우회하지않는다.
