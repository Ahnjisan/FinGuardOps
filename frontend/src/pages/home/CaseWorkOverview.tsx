import { Link } from "react-router-dom";
import { useCaseList, type CaseListErrorKind, type CaseListState } from "../../api/useCaseList";
import type { CaseListQuery } from "../../api/caseApi";
import { INFORMATION_CASE_LIST_ROUTE, OPEN_CASE_LIST_ROUTE } from "../../auth/returnRoute";
import { CASE_STATUS_LABELS, describeReference, formatCaseInstant, UNASSIGNED_LABEL } from "../cases/casePresentation";

const OPEN_QUERY: CaseListQuery = Object.freeze({
  caseStatus: "OPEN", page: 0, size: 5, sort: "lastChangedAt,desc",
});
const INFORMATION_QUERY: CaseListQuery = Object.freeze({
  caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", page: 0, size: 1,
  sort: "lastChangedAt,desc",
});

const RETRYABLE: ReadonlySet<CaseListErrorKind> = new Set([
  "timeout", "network", "invalid-response", "unknown",
]);

function StateValue({ state, retry }: { readonly state: CaseListState; readonly retry: () => void }) {
  if (state.status === "loading" || state.status === "idle") {
    return <span role="status">불러오는 중…</span>;
  }
  if (state.status === "success") {
    return <strong>{state.data.page.totalElements}건</strong>;
  }
  return (
    <span role="alert">
      {state.error === "access-denied" ? "조회 권한 없음" : "조회 실패"}
      {RETRYABLE.has(state.error) && (
        <button type="button" className="button" onClick={retry}>다시 시도</button>
      )}
    </span>
  );
}

export function CaseWorkOverview() {
  const open = useCaseList(OPEN_QUERY);
  const information = useCaseList(INFORMATION_QUERY);

  return <CaseWorkOverviewView open={open} information={information} />;
}

interface OverviewResult {
  readonly state: CaseListState;
  readonly retry: () => void;
}

/** Shared markup for the live hook and the transport-free browser layout fixture. */
export function CaseWorkOverviewView({ open, information }: {
  readonly open: OverviewResult;
  readonly information: OverviewResult;
}) {
  return (
    <section className="home-work" aria-labelledby="home-work-heading">
      <div className="home-work__heading">
        <h3 id="home-work-heading">현재 사건 현황</h3>
        <p>각 건수는 해당 상태를 별도로 조회한 결과입니다.</p>
      </div>
      <div className="home-work__cards">
        <div className="home-work__card" aria-label="OPEN 사건">
          <Link to={OPEN_CASE_LIST_ROUTE}>OPEN 사건 목록</Link>
          <StateValue state={open.state} retry={open.retry} />
        </div>
        <div className="home-work__card" aria-label="추가 정보 필요 사건">
          <Link to={INFORMATION_CASE_LIST_ROUTE}>추가 정보 필요 사건 목록</Link>
          <StateValue state={information.state} retry={information.retry} />
        </div>
      </div>
      <div className="home-work__preview">
        <div className="home-work__heading">
          <h4>OPEN 사건 미리보기</h4>
          <p>마지막 변경 최신순 · 최대 5건</p>
        </div>
        {open.state.status === "success" && (
          open.state.data.content.length === 0 ? (
            <p>표시할 OPEN 사건이 없습니다.</p>
          ) : (
            <ul className="home-work__list" aria-label="OPEN 사건 미리보기">
              {open.state.data.content.map((item) => (
                <li key={item.caseId} className="home-work__row">
                  <div className="home-work__row-primary">
                    <Link to={`/cases/${item.caseId}`} aria-label={`사건 ${item.caseId} 상세 보기`}>
                      {item.caseId}
                    </Link>
                    <span>{CASE_STATUS_LABELS[item.caseStatus]}</span>
                  </div>
                  <span>담당자 {item.assigneeRef === null ? UNASSIGNED_LABEL : describeReference(item.assigneeRef).text}</span>
                  <time dateTime={item.lastChangedAt}>
                    마지막 변경 {formatCaseInstant(item.lastChangedAt)} KST
                  </time>
                </li>
              ))}
            </ul>
          )
        )}
        {(open.state.status === "loading" || open.state.status === "idle") && <p>사건을 불러오는 중…</p>}
        {open.state.status === "error" && <p>OPEN 사건 미리보기를 표시할 수 없습니다.</p>}
      </div>
    </section>
  );
}
