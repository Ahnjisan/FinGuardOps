import { useEffect, useState } from "react";
import { useAuth } from "../auth/useAuth";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { ForbiddenError } from "../api/errors";
import { fetchCaseDashboard, fetchTransactionDashboard,
  type CaseDashboard, type DashboardRange, type TransactionDashboard } from "../api/fdsDashboardApi";

type Section<T> = { status: "loading" } | { status: "success"; data: T } |
  { status: "denied" } | { status: "error" };
const SCENARIOS = ["SCN001", "SCN002", "SCN003", "SCN004", "SCN005", "SCN006", "SCN007", "SCN008"] as const;
const LEVELS = ["LOW", "MEDIUM", "HIGH", "CRITICAL"] as const;
const STATUSES = ["OPEN", "IN_REVIEW", "ADDITIONAL_INFORMATION_REQUIRED", "CLOSED"] as const;

function total(values: readonly number[]): number { return values.reduce((sum, value) => sum + value, 0); }
function ratio(numerator: number, denominator: number): string {
  return denominator === 0 ? "—" : `${(100 * numerator / denominator).toFixed(1)}%`;
}
function failure(error: unknown): Section<never> {
  return error instanceof ForbiddenError ? { status: "denied" } : { status: "error" };
}
function recentSevenDays(): DashboardRange {
  const kstNow = Date.now() + 9 * 60 * 60 * 1000;
  const day = 24 * 60 * 60 * 1000;
  return {
    from: new Date(kstNow - 6 * day).toISOString().slice(0, 10),
    to: new Date(kstNow + day).toISOString().slice(0, 10),
  };
}

export function FdsDashboardPage() {
  const { state } = useAuth();
  const session = state.status === "authenticated" ? state.session : undefined;
  const [range, setRange] = useState<DashboardRange>(recentSevenDays);
  const [fromInput, setFromInput] = useState(range.from);
  const [toInput, setToInput] = useState(range.to);
  const [revision, setRevision] = useState(0);
  const [transactions, setTransactions] = useState<Section<TransactionDashboard>>({ status: "loading" });
  const [cases, setCases] = useState<Section<CaseDashboard>>({ status: "loading" });

  useEffect(() => {
    if (session === undefined) {
      return;
    }
    const controller = new AbortController();
    const auth = getOidcAuthClient();
    queueMicrotask(() => {
      if (!controller.signal.aborted) {
        setTransactions({ status: "loading" });
        setCases({ status: "loading" });
      }
    });
    fetchTransactionDashboard(auth, range, controller.signal)
      .then(data => { if (!controller.signal.aborted) setTransactions({ status: "success", data }); })
      .catch(error => { if (!controller.signal.aborted) setTransactions(failure(error)); });
    fetchCaseDashboard(auth, range, controller.signal)
      .then(data => { if (!controller.signal.aborted) setCases({ status: "success", data }); })
      .catch(error => { if (!controller.signal.aborted) setCases(failure(error)); });
    return () => controller.abort();
  }, [session, range, revision]);

  if (session === undefined) {
    return <p role="status">로그인을 확인하는 중입니다.</p>;
  }

  const from = transactions.status === "success" ? transactions.data.from :
    cases.status === "success" ? cases.data.from : undefined;
  const to = transactions.status === "success" ? transactions.data.to :
    cases.status === "success" ? cases.data.to : undefined;
  return <section className="fds-dashboard" aria-labelledby="fds-dashboard-title">
    <h2 id="fds-dashboard-title">FDS 기간별 집계</h2>
    <p>저장된 거래·탐지·사건의 조회 시점 현황입니다. 금융 사기 확률이나 탐지 성능이 아닙니다.</p>
    <form className="fds-dashboard__filters" onSubmit={event => {
      event.preventDefault();
      if (fromInput && toInput) setRange({ from: fromInput, to: toInput });
    }}>
      <label>시작일 (KST)<input type="date" value={fromInput} onChange={event => setFromInput(event.target.value)} required /></label>
      <label>종료일 (미포함, KST)<input type="date" value={toInput} onChange={event => setToInput(event.target.value)} required /></label>
      <button type="submit">조회</button>
      <button type="button" onClick={() => { const next = recentSevenDays();
        setRange(next); setFromInput(next.from); setToInput(next.to); setRevision(n => n + 1); }}>최근 7일</button>
      <button type="button" onClick={() => setRevision(n => n + 1)}>새로고침</button>
    </form>
    {from && to && <p>기간: {from}부터 {to} 전까지 · KST 일별 · 늦게 접수된 거래는 다음 조회에서 과거 수치를 바꿀 수 있습니다.</p>}
    <section aria-labelledby="fds-transactions-title">
      <h3 id="fds-transactions-title">거래·위험등급·시나리오</h3>
      {transactions.status === "loading" && <p role="status">거래 집계를 불러오는 중입니다.</p>}
      {transactions.status === "denied" && <p role="alert">거래 집계 조회 권한이 없습니다.</p>}
      {transactions.status === "error" && <p role="alert">거래 집계 조회에 실패했습니다. 사건 집계는 별도로 표시됩니다.</p>}
      {transactions.status === "success" && <TransactionContent data={transactions.data} />}
    </section>
    <section aria-labelledby="fds-cases-title">
      <h3 id="fds-cases-title">신규 사건·현재 상태</h3>
      {cases.status === "loading" && <p role="status">사건 집계를 불러오는 중입니다.</p>}
      {cases.status === "denied" && <p role="alert">사건 집계 조회 권한이 없습니다.</p>}
      {cases.status === "error" && <p role="alert">사건 집계 조회에 실패했습니다. 거래 집계는 별도로 표시됩니다.</p>}
      {cases.status === "success" && <CaseContent data={cases.data} />}
    </section>
  </section>;
}

function TransactionContent({ data }: { readonly data: TransactionDashboard }) {
  const all = total(data.days.map(day => day.total));
  const classified = total(data.days.map(day => day.classified));
  return <>
    <p>조회 시점: <time dateTime={data.asOf}>{data.asOf}</time></p>
    {all === 0 ? <p>선택 기간에 거래가 없습니다.</p> : <>
      <div className="fds-dashboard__cards">
        <div>전체 거래 <strong>{all}</strong></div>
        <div>미분류 <strong>{total(data.days.map(day => day.unclassified))}</strong></div>
        <div>채택 완료 <strong>{classified}</strong></div>
      </div>
      <h4>위험등급별 거래</h4>
      <p>비율 분모: 같은 기간의 채택 완료 거래 {classified}건</p>
      <ul>{LEVELS.map(level => {
        const count = total(data.days.map(day => day.riskLevels[level]));
        return <li key={level}>{level}: {count}건 ({ratio(count, classified)})</li>;
      })}</ul>
      <h4>일별 거래 추세</h4>
      <div className="fds-dashboard__bars" role="img" aria-label="일별 전체 거래 막대 추세">
        {data.days.map(day => <div key={day.date} title={`${day.date}: ${day.total}건`}>
          <span style={{ height: `${all === 0 ? 0 : Math.max(2, 100 * day.total / Math.max(...data.days.map(item => item.total)))}%` }} />
          <small>{day.date.slice(5)}</small>
        </div>)}
      </div>
      <table><caption>일별 거래·위험등급 건수</caption><thead><tr>
        <th scope="col">일자</th><th scope="col">전체</th><th scope="col">미분류</th>
        {LEVELS.map(level => <th key={level} scope="col">{level}</th>)}
      </tr></thead><tbody>{data.days.map(day => <tr key={day.date}>
        <th scope="row">{day.date}</th><td>{day.total}</td><td>{day.unclassified}</td>
        {LEVELS.map(level => <td key={level}>{day.riskLevels[level]}</td>)}
      </tr>)}</tbody></table>
      <h4>SCN-001~008 채택 Rule 적중</h4>
      <p>시나리오별 비율 분모: 채택 완료 거래 {classified}건. 한 거래의 복수 적중으로 합계는 100%를 넘을 수 있습니다.</p>
      <ul>{SCENARIOS.map(scenario => {
        const count = total(data.days.map(day => day.scenarios[scenario]));
        return <li key={scenario}>{scenario}: {count}건 ({ratio(count, classified)})</li>;
      })}</ul>
      <table><caption>일별 시나리오 적중 거래 건수</caption><thead><tr><th scope="col">일자</th>
        {SCENARIOS.map(scenario => <th key={scenario} scope="col">{scenario}</th>)}</tr></thead>
        <tbody>{data.days.map(day => <tr key={day.date}><th scope="row">{day.date}</th>
          {SCENARIOS.map(scenario => <td key={scenario}>{day.scenarios[scenario]}</td>)}</tr>)}</tbody></table>
    </>}
  </>;
}

function CaseContent({ data }: { readonly data: CaseDashboard }) {
  const all = total(data.days.map(day => day.newCases));
  return <>
    <p>현재 상태 조회 시점: <time dateTime={data.asOf}>{data.asOf}</time>. 선택 기간에 생성된 사건의 현재 상태이며 과거 상태 추세가 아닙니다.</p>
    {all === 0 ? <p>선택 기간에 신규 사건이 없습니다.</p> : <>
      <div className="fds-dashboard__cards"><div>신규 사건 <strong>{all}</strong></div>
        {STATUSES.map(status => <div key={status}>{status} <strong>{data.currentStatuses[status]}</strong></div>)}</div>
      <table><caption>일별 신규 사건 추세</caption><thead><tr><th scope="col">일자</th><th scope="col">신규 사건</th></tr></thead>
        <tbody>{data.days.map(day => <tr key={day.date}><th scope="row">{day.date}</th><td>{day.newCases}</td></tr>)}</tbody></table>
    </>}
  </>;
}
