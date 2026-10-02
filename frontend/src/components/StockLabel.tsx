import { stockLabel, stockName } from '../stock';

interface Props {
  code: string;
  name?: string | null;
}

// 표·목록용 종목 표기 — 이름 뒤에 코드를 붙인다("삼성전자(005930)"). 복사하면 백엔드 알림과 같은 문자열이 된다.
// 이름을 모르면 "종목명 미확인"을 흐리게 보여준다(서버가 키움에서 이름을 받아 오면 다음 갱신에 채워진다).
export default function StockLabel({ code, name }: Props) {
  const known = name != null && name.trim() !== '';
  return (
    <span className="stock-label" title={stockLabel(code, name)}>
      <span className={known ? 'stock-name' : 'stock-name stock-name-unknown'}>{stockName(name)}</span>
      <span className="stock-code">({code})</span>
    </span>
  );
}
