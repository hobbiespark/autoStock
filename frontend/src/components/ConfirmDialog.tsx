import { useEffect, useId, useRef, type ReactNode } from 'react';

interface Props {
  open: boolean;
  title: string;
  confirmLabel: string;
  danger?: boolean;
  confirmDisabled?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  children: ReactNode;
}

// 위험 동작 확인 창(Phase 0.5) — 브라우저 기본 <dialog>(showModal)라 포커스 가두기·Esc 닫기·배경 차단을
// 브라우저가 맡는다. 라이브러리를 들이지 않는다(번들 비대화 회피, ADR-10). Esc는 취소와 같다.
// 위험 확인(danger)이면 확인 버튼 앞에 [위험] 단어를 붙여 색만으로 알리지 않는다(VALUNIVO 공통 UI-M5).
export default function ConfirmDialog({
  open,
  title,
  confirmLabel,
  danger,
  confirmDisabled,
  onConfirm,
  onCancel,
  children,
}: Props) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) {
      return;
    }
    if (open && !dialog.open) {
      dialog.showModal();
    } else if (!open && dialog.open) {
      dialog.close();
    }
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="confirm-dialog"
      aria-labelledby={titleId}
      onCancel={(e) => {
        e.preventDefault(); // Esc — 상태는 부모가 쥔다
        onCancel();
      }}
    >
      <h3 id={titleId}>{title}</h3>
      <div className="confirm-body">{children}</div>
      <div className="confirm-actions">
        <button type="button" className="secondary" onClick={onCancel}>
          취소
        </button>
        <button
          type="button"
          className={danger ? 'danger' : undefined}
          disabled={confirmDisabled}
          onClick={onConfirm}
        >
          {danger ? `[위험] ${confirmLabel}` : confirmLabel}
        </button>
      </div>
    </dialog>
  );
}
