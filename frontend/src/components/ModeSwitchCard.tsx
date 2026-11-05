import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { describeError, fetchMode, switchMode } from '../api';
import ConfirmDialog from './ConfirmDialog';

// VALUNIVO 612(D301): 모의↔실전 전환 카드. 실전 전환은 실제 계좌로 주문이 나가는 일이라 확인 문구를 직접 입력해야 한다.
// 앱은 모드를 기록하고 안전 종료한 뒤 다시 뜬다. 실전은 킬스위치가 켜진 채 시작하므로, 확인 뒤 킬스위치를 해제해야 주문이 나간다.
export default function ModeSwitchCard() {
  const { data } = useQuery({ queryKey: ['mode'], queryFn: fetchMode, refetchInterval: 10_000 });
  const [open, setOpen] = useState(false);
  const [typed, setTyped] = useState('');
  const target = data?.mode === 'live' ? 'paper' : 'live';
  const phrase = target === 'live' ? '실전 전환' : '모의 전환';
  const mutation = useMutation({ mutationFn: () => switchMode(target, typed), onSettled: () => setOpen(false) });

  return (
    <div className="card">
      <h2>거래 모드</h2>
      <p>
        지금: <strong>{data ? (data.mode === 'live' ? '실전(실제 계좌)' : '모의') : '확인 중'}</strong>
      </p>
      <button
        className={target === 'live' ? 'danger' : undefined}
        disabled={!data?.switchable || mutation.isPending}
        onClick={() => { setTyped(''); setOpen(true); }}
      >
        {target === 'live' ? '실전으로 전환' : '모의로 돌아가기'}
      </button>
      {data && !data.switchable && <p className="muted">전환 설정이 없어 지금은 전환할 수 없습니다.</p>}
      {mutation.isSuccess && <p role="status">{mutation.data.message}</p>}
      {mutation.isError && <p role="alert">{describeError(mutation.error)}</p>}
      <ConfirmDialog
        open={open}
        title={target === 'live' ? '실전 모드로 전환' : '모의 모드로 돌아가기'}
        confirmLabel="전환"
        danger={target === 'live'}
        confirmDisabled={typed.trim() !== phrase}
        onConfirm={() => mutation.mutate()}
        onCancel={() => setOpen(false)}
      >
        {target === 'live' ? (
          <p>실전 계좌로 실제 주문이 나갈 수 있습니다. 앱이 다시 시작되고, 킬스위치가 켜진 채 시작합니다. 미체결 주문이 있으면 전환하지 않습니다.</p>
        ) : (
          <p>모의 서버로 돌아갑니다. 앱이 다시 시작되고 킬스위치가 켜진 채 시작합니다.</p>
        )}
        <label>
          확인을 위해 <strong>{phrase}</strong>를 입력하세요
          <input value={typed} onChange={(e) => setTyped(e.target.value)} autoComplete="off" />
        </label>
      </ConfirmDialog>
    </div>
  );
}
