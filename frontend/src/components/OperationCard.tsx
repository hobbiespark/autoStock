import { useMutation, useQueryClient } from '@tanstack/react-query';
import { startTrading, stopTrading } from '../api';
import type { TradingStatus } from '../types';

interface Props {
  status?: TradingStatus;
}

// 운영 상태(시작/정지) 카드.
// 시작/정지 명령의 응답은 즉시 오는 STARTING/STOPPING일 뿐, 실제로 RUNNING/STOPPED에
// 도달했는지는 알려주지 않는다(Backend가 Source of Truth, ARCHITECTURE.md 10절).
// 그래서 버튼을 눌러도 여기서 상태를 바로 바꾸지 않고, 명령 후 invalidate로
// 서버를 다시 조회(refetch)해서 화면을 채운다 — 낙관적 업데이트를 의도적으로 하지 않았다.
export default function OperationCard({ status }: Props) {
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['dashboard'] });

  const startMutation = useMutation({ mutationFn: startTrading, onSettled: invalidate });
  const stopMutation = useMutation({ mutationFn: stopTrading, onSettled: invalidate });

  // 상태에 맞지 않는 명령은 서버에서도 409로 막히지만, 화면에서도
  // 미리 비활성화해 불필요한 요청과 혼란을 줄인다.
  const canStart = status === 'STOPPED';
  const canStop = status === 'RUNNING' || status === 'DEGRADED';

  return (
    <div className="card">
      <h2>운영 상태</h2>
      <p>상태: {status ?? '확인 중...'}</p>
      <p style={{ marginTop: 12 }}>
        <button
          disabled={!canStart || startMutation.isPending}
          onClick={() => startMutation.mutate()}
        >
          시작
        </button>
        <button
          className="danger"
          disabled={!canStop || stopMutation.isPending}
          onClick={() => stopMutation.mutate()}
        >
          정지
        </button>
      </p>
      <p className="muted">
        시작/정지 명령의 응답은 즉시 오는 STARTING/STOPPING일 뿐, 실제로 RUNNING/STOPPED에
        도달했는지는 알려주지 않는다(Backend가 Source of Truth, ARCHITECTURE.md 10절).
        그래서 버튼을 눌러도 여기서 상태를 바로 바꾸지 않고, 서버를 다시 조회(refetch)해서
        화면을 채운다 — 낙관적 업데이트를 의도적으로 하지 않았다.
      </p>
    </div>
  );
}
