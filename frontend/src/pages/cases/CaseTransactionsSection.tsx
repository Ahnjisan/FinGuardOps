import { Link } from "react-router-dom";
import { useCapabilities } from "../../auth/useCapabilities";
import { useCaseTransactions, type CaseTransactionsState } from "../../api/useCaseTransactions";
import { Icon } from "../../shared/Icon";

const ERROR_TEXT = {
  timeout: "거래 ID 조회가 지연되고 있습니다. 다시 시도하세요.",
  network: "백엔드에 연결할 수 없습니다. 연결을 확인하고 다시 시도하세요.",
  "invalid-response": "거래 ID 응답을 읽을 수 없습니다. 다시 시도하세요.",
  unknown: "거래 ID를 불러올 수 없습니다. 다시 시도하세요.",
} as const;

export function CaseTransactionsSection({ caseId }: { readonly caseId: string }) {
  const { state, page, setPage, retry } = useCaseTransactions(caseId);
  const mayViewTransaction = useCapabilities().has("transaction:view");
  return <CaseTransactionsPanel caseId={caseId} state={state} pageNumber={page} onPageChange={setPage}
    onRetry={retry} mayViewTransaction={mayViewTransaction} />;
}

export function CaseTransactionsPanel({ caseId, state, pageNumber, onPageChange, onRetry,
  mayViewTransaction }: {
  readonly caseId?: string;
  readonly state: CaseTransactionsState;
  readonly pageNumber: number;
  readonly onPageChange: (number: number) => void;
  readonly onRetry: () => void;
  readonly mayViewTransaction: boolean;
}) {
  return <section className="panel case-transactions" aria-labelledby="case-transactions-heading"
    aria-busy={state.status === "loading" || undefined}>
    <h3 id="case-transactions-heading">연관 거래 ID</h3>
    <p className="case-transactions__note">저장된 연관 관계를 내부 순서로 표시합니다. 사건 기록의 연관 거래 건수와 이 목록은 조회 시점이 다를 수 있습니다.</p>
    <div role="status" aria-live="polite" aria-label="연관 거래 조회 상태">
      {state.status === "loading" && <p>연관 거래 ID를 불러오는 중입니다…</p>}
      {state.status === "idle" && <p>조회할 수 있는 사건이 없습니다.</p>}
      {state.status === "success" && state.page.totalElements === 0 &&
        <p>저장된 연관 거래가 없습니다.</p>}
      {state.status === "success" && state.page.totalElements > 0 &&
        <p>현재 {state.page.number + 1}쪽 · 관계 전체 {state.page.totalElements}건</p>}
    </div>
    {state.status === "success" && state.ids.length === 0 && state.page.totalElements > 0 &&
      <p>이 페이지에는 거래 ID가 없습니다. 이전 페이지를 확인하세요.</p>}
    {state.status === "success" && state.ids.length > 0 && <ol className="case-transactions__list"
      start={state.page.number * state.page.size + 1}>
      {state.ids.map((id) => <li key={id} className="case-transactions__item">
        {mayViewTransaction ? <Link to={`/transactions/${id}`} state={caseId ? { fromCaseId: caseId } : undefined}
          aria-label={`거래 ${id} 상세 보기`}>{id}</Link>
          : <span>{id}</span>}
      </li>)}
    </ol>}
    {state.status === "success" && state.page.totalElements > 0 &&
      <nav className="case-transactions__paging" aria-label="연관 거래 페이지">
        <button className="button" type="button" disabled={state.page.first}
          onClick={() => onPageChange(pageNumber - 1)}>이전</button>
        <span>{state.page.number + 1} / {state.page.totalPages}</span>
        <button className="button" type="button" disabled={state.page.last}
          onClick={() => onPageChange(pageNumber + 1)}>다음</button>
      </nav>}
    {(state.status === "forbidden" || state.status === "not-found" ||
      state.status === "authentication-required" || state.status === "error") &&
      <div className="notice notice--error" role="alert">
        <p className="notice__title">{state.status === "forbidden" ? "연관 거래를 볼 권한이 없습니다"
          : state.status === "not-found" ? "사건을 찾을 수 없습니다"
          : state.status === "authentication-required" ? "세션이 종료되었습니다"
          : "연관 거래 ID를 불러올 수 없습니다"}</p>
        <p className="notice__body">{state.status === "forbidden" ? "이 사건의 연관 거래 ID를 볼 수 없습니다."
          : state.status === "not-found" ? "이 ID에 해당하는 사건이 없습니다."
          : state.status === "authentication-required" ? "계속하려면 다시 로그인하세요."
          : ERROR_TEXT[state.kind]}</p>
        {state.status === "error" && <button className="button" type="button" onClick={onRetry}>
          <Icon name="refresh" />다시 시도
        </button>}
      </div>}
  </section>;
}
