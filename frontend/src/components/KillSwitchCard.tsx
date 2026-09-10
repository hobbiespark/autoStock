import { useMutation, useQueryClient } from '@tanstack/react-query';
import { setKillSwitch } from '../api';

interface Props {
  killSwitchEngaged?: boolean;
}

// 킬스위치 카드 — 여기도 낙관적 업데이트 없이 명령 후 재조회로만 상태를 반영한다.
export default function KillSwitchCard({ killSwitchEngaged }: Props) {
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['dashboard'] });

  const mutation = useMutation({ mutationFn: setKillSwitch, onSettled: invalidate });

  return (
    <div className="card">
      <h2>킬스위치</h2>
      <p>
        상태:{' '}
        <span className={killSwitchEngaged ? 'ks-on' : 'ks-off'}>
          {killSwitchEngaged === undefined ? '확인 중...' : killSwitchEngaged ? '비상 정지 중' : '정상 (주문 허용)'}
        </span>
      </p>
      <p style={{ marginTop: 12 }}>
        <button className="danger" disabled={mutation.isPending} onClick={() => mutation.mutate(true)}>
          비상 정지
        </button>
        <button disabled={mutation.isPending} onClick={() => mutation.mutate(false)}>
          해제
        </button>
      </p>
      <p className="muted">비상 정지 중에는 모든 신규 주문이 RiskGate에서 차단됩니다.</p>
    </div>
  );
}
