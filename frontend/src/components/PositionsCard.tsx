import type { Position } from '../types';

interface Props {
  positions?: Position[];
}

export default function PositionsCard({ positions }: Props) {
  return (
    <div className="card">
      <h2>보유 포지션</h2>
      <table>
        <thead>
          <tr>
            <th>종목</th>
            <th>수량</th>
            <th>평균단가</th>
          </tr>
        </thead>
        <tbody>
          {positions === undefined ? (
            <tr>
              <td colSpan={3}>-</td>
            </tr>
          ) : positions.length === 0 ? (
            <tr>
              <td colSpan={3}>보유 없음</td>
            </tr>
          ) : (
            positions.map((p) => (
              <tr key={p.symbol}>
                <td>{p.symbol}</td>
                <td>{p.quantity}</td>
                <td>{Number(p.avgPrice).toLocaleString()}</td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </div>
  );
}
