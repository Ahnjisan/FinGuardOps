import { Link, useLocation } from "react-router-dom";
import { isCanonicalUuidV4 } from "../api/backendEndpoints";
import { useAiOutbox, useAiRequestDetail } from "../api/useAiOperations";
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
  const outbox = useAiOutbox(state.data?.executionId ?? null);
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
          <div><dt>{state.data.fallbackUsed && state.data.fallbackTriggerCode === null &&
            state.data.failureCode !== null ? "기존 저장 코드" : "최종 실패 분류"}</dt>
            <dd>{state.data.failureCode ?? "없음"}</dd></div>
          <div><dt>fallback 원인</dt><dd>{state.data.fallbackTriggerCode ??
            (state.data.fallbackUsed && state.data.failureCode !== null
              ? "미기록 (기존 저장 형식)" : "없음")}</dd></div>
          <div><dt>기록된 attempt 수</dt><dd>{state.data.attempts.length}</dd></div>
          <div><dt>입력 토큰</dt><dd>{state.data.inputTokens ?? "미측정"}</dd></div>
          <div><dt>출력 토큰</dt><dd>{state.data.outputTokens ?? "미측정"}</dd></div>
          <div><dt>비용</dt><dd>{state.data.attempts.length === 0 ? "기록된 attempt 없음" : "비용 미측정"}</dd></div>
        </dl>
        <h3>기록된 Provider attempts</h3>
        {state.data.attempts.length === 0 ? <p>기록된 attempt가 없습니다. 실제 호출 여부는 확인할 수 없습니다.</p> :
          <div className="ai-operations__table-wrap"><table><thead><tr><th>순서</th><th>Provider</th>
            <th>모델 digest</th><th>결과</th><th>입력 토큰</th><th>출력 토큰</th><th>호출 지연(ms)</th>
          </tr></thead><tbody>{state.data.attempts.map((attempt) => <tr key={attempt.attemptNumber}>
            <td>{attempt.attemptNumber}</td><td>{attempt.provider}</td><td>{attempt.model ?? "미기록"}</td>
            <td>{attempt.outcome}</td><td>{attempt.inputTokens ?? "미측정"}</td>
            <td>{attempt.outputTokens ?? "미측정"}</td><td>{attempt.latencyMs}</td>
          </tr>)}</tbody></table></div>}
        {state.data.executionId && <section aria-label="AI outbox recovery">
          <h3>AI outbox 진단</h3>
          {outbox.error && <p role="alert">{outbox.error === "forbidden" ? "AI outbox 조회 또는 재대기 권한이 없습니다."
            : outbox.error === "conflict" ? "재대기 조건이 변경됐습니다. 현재 상태를 다시 확인하세요."
              : outbox.error === "not-found" ? "연결된 outbox를 찾지 못했습니다."
                : "진단 또는 재대기 요청을 확인하지 못했습니다. 상태를 다시 조회하세요."}</p>}
          {outbox.data ? <>
            <dl><div><dt>이벤트 ID</dt><dd>{outbox.data.eventId}</dd></div>
              <div><dt>Outbox 상태</dt><dd>{outbox.data.outboxStatus}</dd></div>
              <div><dt>실행 상태</dt><dd>{outbox.data.executionStatus ?? "없음"}</dd></div>
              <div><dt>발행 실패 코드</dt><dd>{outbox.data.failureCode ?? "없음"}</dd></div>
              <div><dt>재대기 불가 사유</dt><dd>{outbox.data.rejectionReason ?? "없음"}</dd></div></dl>
            <p>연결 요청: {outbox.data.requests.map((request) =>
              `${request.aiRequestId} (${request.status})`).join(", ") || "없음"}</p>
            <p>결과: {outbox.data.reportExists ? "있음" : "없음"}, 기록된 attempt: {outbox.data.attemptExists ? "있음" : "없음"}</p>
            {outbox.canRequeue && <button type="button" disabled={!outbox.data.requeueAllowed || outbox.busy || outbox.error !== null}
              onClick={() => void outbox.requeue()}>단건 재대기</button>}
          </> : !outbox.error && <p>outbox 진단 조회 중</p>}
          <button type="button" onClick={outbox.refresh}>상태 다시 조회</button>
          {outbox.accepted && <p role="status">202: DB 발행 대기만 수락됐습니다. broker 발행·소비·Provider 완료는 보장되지 않습니다.</p>}
          {outbox.accepted && outbox.priorFailureCode && <p>재대기 전 발행 실패 코드: {outbox.priorFailureCode}</p>}
          <p>PUBLISHED는 broker 발행 표시이며 리포트 완료를 뜻하지 않습니다.</p>
        </section>}
      </>}
  </section>;
}
