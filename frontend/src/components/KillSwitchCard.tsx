import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { describeError, setKillSwitch } from '../api';
import ConfirmDialog from './ConfirmDialog';

interface Props {
  killSwitchEngaged?: boolean;
}

// 킬스위치 카드 — 여기도 낙관적 업데이트 없이 명령 후 재조회로만 상태를 반영한다.
// Phase 0.5: 비상 정지는 1클릭 유지(안전 방향), 해제는 확인 단계를 거친다. 명령 실패는 화면에 표시한다.
export default function KillSwitchCard({ killSwitchEngaged }: Props) {
  const queryClient = useQueryClient();
  const invalidate = () => queryClient.invalidateQueries({ queryKey: ['dashboard'] });
  const [confirmRelease, setConfirmRelease] = useState(false);

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
        <button
          disabled={mutation.isPending || killSwitchEngaged === false}
          onClick={() => {
            mutation.reset();
            setConfirmRelease(true);
          }}
        >
          해제…
        </button>
      </p>
      {mutation.isError && (
        <p className="error-text" role="alert">
          킬스위치 명령 실패: {describeError(mutation.error)}
        </p>
      )}
      <p className="muted">
        비상 정지 중에는 모든 신규 주문이 RiskGate에서 차단됩니다. 상태는 재기동해도 유지되며 해제는 사람만 합니다.
      </p>

      <ConfirmDialog
        open={confirmRelease}
        title="킬스위치 해제 확인"
        confirmLabel="해제"
        danger
        onConfirm={() => {
          setConfirmRelease(false);
          mutation.mutate(false);
        }}
        onCancel={() => setConfirmRelease(false)}
      >
        <p>해제하면 신규 주문이 다시 허용됩니다. 작동 원인(이벤트 피드의 사유)을 확인했나요?</p>
      </ConfirmDialog>
    </div>
  );
}
