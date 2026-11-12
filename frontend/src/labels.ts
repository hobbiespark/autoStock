// VALUNIVO 849: 화면에 보이는 코드 값의 한국어 이름. 니보·앱 봇 조회 답(백엔드 ReadableText.VALUES, VALUNIVO 848)과
// 같은 낱말을 쓴다. 값 자체(API·타입·CSS 클래스)는 그대로 두고 보이는 글만 바꾼다. 모르는 값은 원래 값 그대로.

export const ORDER_STATUS_LABELS: Record<string, string> = {
  CREATED: '생성',
  VALIDATED: '검증 통과',
  SUBMITTING: '전송 중',
  SUBMITTED: '접수',
  ACCEPTED: '접수 확인',
  PARTIALLY_FILLED: '일부 체결',
  FILLED: '체결 완료',
  CANCEL_REQUESTED: '취소 요청',
  CANCELLED: '취소됨',
  REJECTED: '거부',
  UNKNOWN: '결과 확인 중',
};

export const TRADING_STATUS_LABELS: Record<string, string> = {
  STOPPED: '정지',
  STARTING: '시작 중',
  RUNNING: '운영 중',
  STOPPING: '정지 중',
  DEGRADED: '매매 차단(시스템은 동작)',
  ERROR: '오류',
};

export const EVENT_TYPE_LABELS: Record<string, string> = {
  SIGNAL: '신호',
  ORDER: '주문',
  FILL: '체결',
};

export function label(labels: Record<string, string>, value: string): string {
  return labels[value] ?? value;
}
