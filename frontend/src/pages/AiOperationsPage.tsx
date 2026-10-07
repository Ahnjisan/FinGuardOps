import { useState } from "react";
import { Link } from "react-router-dom";
import { type AiUsageListQuery } from "../api/aiOperationsApi";
import { useAiUsage } from "../api/useAiOperations";
import { compareUtcInstants, isUtcInstantString } from "../api/responseValidation";

function initialQuery(): AiUsageListQuery {
  const to = new Date();
  return { from: new Date(to.getTime() - 24 * 60 * 60 * 1000).toISOString(),
    to: to.toISOString(), page: "0", size: "20", sort: "requestedAt,desc" };
}

export function AiOperationsPage() {
  const [query, setQuery] = useState<AiUsageListQuery>(initialQuery);
  const [from, setFrom] = useState(query.from);
  const [to, setTo] = useState(query.to);
  const [status, setStatus] = useState("");
  const [provider, setProvider] = useState("");
  const [model, setModel] = useState("");
  const [problem, setProblem] = useState(false);
  const { list, summary } = useAiUsage(query);
  function apply(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!isUtcInstantString(from) || !isUtcInstantString(to) ||
        compareUtcInstants(from, to) >= 0 ||
        Date.parse(to) - Date.parse(from) > 31 * 86400000 ||
        (provider !== "" && !/^[A-Z][A-Z0-9_]{0,31}$/.test(provider)) ||
        (model !== "" && !/^[a-zA-Z0-9:_.-]{1,128}$/.test(model))) {
      setProblem(true); return;
    }
    setProblem(false);
    setQuery({ from, to, reportStatus: status || undefined, provider: provider || undefined,
      model: model || undefined, page: "0", size: "20", sort: "requestedAt,desc" });
  }
  const page = list.data?.page;
  return <section className="ai-operations" aria-labelledby="ai-operations-title">
    <Link to="/ai-operations/dlq">AI 리포트 DLQ 단건 복구</Link>
    <h2 id="ai-operations-title">AI 요청 사용량</h2>
    <p>호출 수는 영속 기록된 attempt 수입니다. 중단으로 기록되지 않은 호출은 포함되지 않을 수 있습니다.</p>
    <form onSubmit={apply} className="ai-operations__filters">
      <label>시작 (UTC)<input value={from} onChange={(event) => setFrom(event.target.value)} /></label>
      <label>끝 (UTC)<input value={to} onChange={(event) => setTo(event.target.value)} /></label>
      <label>상태<select value={status} onChange={(event) => setStatus(event.target.value)}>
        <option value="">전체</option>{["PENDING", "GENERATING", "COMPLETED", "FALLBACK_COMPLETED", "FAILED"]
          .map((value) => <option key={value}>{value}</option>)}
      </select></label>
      <label>Provider<input value={provider} onChange={(event) => setProvider(event.target.value)} /></label>
      <label>모델 digest<input value={model} onChange={(event) => setModel(event.target.value)} /></label>
      <button type="submit">조회</button>
    </form>
    {problem && <p role="alert">UTC 시작·끝과 31일 이하 기간, 필터 형식을 확인하세요.</p>}
    <section aria-label="선택 기간 전체 집계">
      <h3>선택 기간 전체 집계</h3>
      {summary.loading ? <p>집계 조회 중</p> : summary.error ? <p role="alert">집계를 조회하지 못했습니다.</p>
        : summary.data && <dl className="ai-operations__summary">
          <div><dt>요청</dt><dd>{summary.data.requestCount}</dd></div>
          <div><dt>distinct 실행</dt><dd>{summary.data.executionCount}</dd></div>
          <div><dt>기록된 attempt 수</dt><dd>{summary.data.providerCallCount}</dd></div>
          <div><dt>fallback</dt><dd>{summary.data.fallbackCount}</dd></div>
          <div><dt>캐시 적중</dt><dd>{summary.data.cacheHitCount}</dd></div>
          <div><dt>입력 토큰</dt><dd>{summary.data.inputTokens ?? "미측정"}</dd></div>
          <div><dt>출력 토큰</dt><dd>{summary.data.outputTokens ?? "미측정"}</dd></div>
          <div><dt>비용</dt><dd>{summary.data.providerCallCount === 0 ? "기록된 Provider 호출 없음" : "비용 미측정"}</dd></div>
        </dl>}
    </section>
    <section aria-label="요청 목록">
      <h3>요청 목록</h3>
      {list.loading ? <p>목록 조회 중</p> : list.error ? <p role="alert">목록을 조회하지 못했습니다.</p>
        : list.data?.content.length === 0 ? <p>조건에 맞는 요청이 없습니다.</p> :
          <div className="ai-operations__table-wrap"><table><thead><tr>
            <th>요청 시각</th><th>요청 ID</th><th>상태</th><th>출처</th><th>실행</th><th>캐시</th><th>토큰</th>
          </tr></thead><tbody>{list.data?.content.map((item) => <tr key={item.aiRequestId}>
            <td>{item.requestedAt}</td><td><Link to={`/ai-operations/${item.aiRequestId}`}>{item.aiRequestId}</Link></td>
            <td>{item.reportStatus}</td><td>{item.reportSource ?? "없음"}</td>
            <td>{item.executionShared ? "공유" : item.executionId ? "단독" : "호출 없음"}</td>
            <td>{item.cacheHit ? "적중" : "아니오"}</td><td>{item.totalTokens ?? "미측정"}</td>
          </tr>)}</tbody></table></div>}
      {page && <nav aria-label="요청 목록 페이지" className="ai-operations__pagination">
        <button disabled={page.first} onClick={() => setQuery({ ...query, page: String(page.number - 1) })}>이전</button>
        <span>{page.number + 1} / {Math.max(page.totalPages, 1)}</span>
        <button disabled={page.last} onClick={() => setQuery({ ...query, page: String(page.number + 1) })}>다음</button>
      </nav>}
    </section>
  </section>;
}
