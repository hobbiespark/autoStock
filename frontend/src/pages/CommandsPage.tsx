import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { describeError, fetchCommands, runCommand, type CommandItem, type CommandResult } from '../api';

/**
 * 명령 목록(VALUNIVO 3-4 C4, D338) — autoStock이 할 수 있는 일을 명령 원천(설명서 1.1) 그대로 보인다.
 * 같은 명령을 니보(/nivo_autostock_…)·앱 봇(/autostock_…)·이 화면에서 부를 수 있고 실행은 같은 앱 부품이 한다.
 * 이 화면은 조회 명령만 실행한다. 긴급 정지·재개 같은 변경은 대시보드 킬스위치 카드에서 확인 뒤 한다.
 */
export default function CommandsPage() {
  const { data, isLoading, error } = useQuery({ queryKey: ['commands'], queryFn: fetchCommands });

  if (isLoading) return <section className="card">명령 목록을 불러오는 중…</section>;
  if (error) return <section className="card" role="alert">명령 목록을 불러오지 못했습니다: {describeError(error)}</section>;

  return (
    <section className="card">
      <h2>명령 목록</h2>
      <p className="muted">
        니보에서는 /nivo_autostock_help, 앱 봇에서는 /autostock_help 로 같은 목록을 볼 수 있습니다. 이 화면에서는 조회만 실행합니다.
      </p>
      <ul className="command-list">
        {(data ?? []).map((command) => (
          <CommandRow key={command.id} command={command} />
        ))}
      </ul>
    </section>
  );
}

function CommandRow({ command }: { command: CommandItem }) {
  const [text, setText] = useState(command.args.map((a) => a.example).join(' '));
  const [result, setResult] = useState<CommandResult | null>(null);
  const [failure, setFailure] = useState<string | null>(null);
  const [running, setRunning] = useState(false);

  async function run() {
    setRunning(true);
    setFailure(null);
    try {
      setResult(await runCommand(command.id, text));
    } catch (e) {
      setResult(null);
      setFailure(describeError(e));
    } finally {
      setRunning(false);
    }
  }

  return (
    <li className="command-row">
      <div className="command-head">
        <strong>{command.name}</strong>
        <span className="muted"> · {command.risk === 'query' ? '조회' : '변경'}</span>
      </div>
      {command.description && <div>{command.description}</div>}
      <div className="muted">
        니보 {command.nivoName}
        {command.botName && <> · 앱 봇 {command.botName}</>}
      </div>
      {command.runnable && (
        <div className="command-run">
          {command.args.length > 0 && (
            <input
              aria-label={`${command.name} 인수`}
              value={text}
              placeholder={command.args.map((a) => `${a.name}${a.required ? '' : '(선택)'}`).join(' ')}
              onChange={(e) => setText(e.target.value)}
            />
          )}
          <button onClick={run} disabled={running}>
            {running ? '실행 중…' : '실행'}
          </button>
        </div>
      )}
      {result && (
        <pre className={`command-result ${result.status}`} aria-live="polite">
          {result.text}
        </pre>
      )}
      {failure && <div role="alert">{failure}</div>}
    </li>
  );
}
