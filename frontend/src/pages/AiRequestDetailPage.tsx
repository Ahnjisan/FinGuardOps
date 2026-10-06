import { Link, useLocation } from "react-router-dom";
import { isCanonicalUuidV4 } from "../api/backendEndpoints";
import { useAiRequestDetail } from "../api/useAiOperations";
import { NotFoundPage } from "./NotFoundPage";

export function AiRequestDetailPage() {
  const location = useLocation();
  const match = /^\/ai-operations\/([^/]+)$/.exec(location.pathname);
  const id = match?.[1] ?? "";
  if (!isCanonicalUuidV4(id) || location.search || location.hash) return <NotFoundPage />;
  return <Detail id={id} />;
}

function Detail({ id }: { id: string }) {
  const state = useAiRequestDetail(id);
  return <section className="ai-operations" aria-labelledby="ai-request-title">
    <Link to="/ai-operations">AI 요청 목록</Link><h2 id="ai-request-title">AI 요청 상세</h2>
    {state.loading ? <p>조회 중</p> : state.error ? <p role="alert">요청 상세를 조회하지 못했습니다.</p>
      : state.data && <>
        <dl className="ai-operations__summary">
          <div><dt>요청 ID</dt><dd>{state.data.aiRequestId}</dd></div>
          <div><dt>상태</dt><dd>{state.data.reportStatus}</dd></div>
          <div><dt>실행 ID</dt><dd>{state.data.executionId ?? "없음"}</dd></div>
          <div><dt>실행 공유</dt><dd>{state.data.executionShared ? "예" : "아니오"}</dd></div>
          <div><dt>캐시</dt><dd>{state.data.cacheHit ? "적중" : "아니오"}</dd></div>
          <div><dt>fallback</dt><dd>{state.data.fallbackUsed ? "사용" : "미사용"}</dd></div>
          <div><dt>최종 실패 분류</dt><dd>{state.data.failureCode ?? "없음"}</dd></div>
          <div><dt>기록된 attempt 수</dt><dd>{state.data.attempts.length}</dd></div>
          <div><dt>입력 토큰</dt><dd>{state.data.inputTokens ?? "미측정"}</dd></div>
          <div><dt>출력 토큰</dt><dd>{state.data.outputTokens ?? "미측정"}</dd></div>
          <div><dt>비용</dt><dd>{state.data.attempts.length === 0 ? "기록된 Provider 호출 없음" : "비용 미측정"}</dd></div>
        </dl>
        <h3>기록된 Provider attempts</h3>
        {state.data.attempts.length === 0 ? <p>기록된 Provider 호출이 없습니다.</p> :
          <div className="ai-operations__table-wrap"><table><thead><tr><th>순서</th><th>Provider</th>
            <th>모델 digest</th><th>결과</th><th>입력 토큰</th><th>출력 토큰</th><th>호출 지연(ms)</th>
          </tr></thead><tbody>{state.data.attempts.map((attempt) => <tr key={attempt.attemptNumber}>
            <td>{attempt.attemptNumber}</td><td>{attempt.provider}</td><td>{attempt.model ?? "미기록"}</td>
            <td>{attempt.outcome}</td><td>{attempt.inputTokens ?? "미측정"}</td>
            <td>{attempt.outputTokens ?? "미측정"}</td><td>{attempt.latencyMs}</td>
          </tr>)}</tbody></table></div>}
      </>}
  </section>;
}
