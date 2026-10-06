import { useAiReport } from "../../api/useAiReport";
import type { AiReportCurrent } from "../../api/aiReportApi";

function failureMessage(code: string | null): string | null {
  if (code === null) return null;
  switch (code) {
    case "FASTAPI_CONNECTION_FAILED": return "AI 서비스에 연결하지 못했습니다.";
    case "FASTAPI_TIMEOUT": return "AI 서비스 응답 시간이 초과됐습니다.";
    case "LLM_TIMEOUT": case "TIMEOUT": return "로컬 모델 응답 시간이 초과됐습니다.";
    case "LLM_UNAVAILABLE": case "PROVIDER_ERROR":
    case "MODEL_NOT_PINNED": case "MODEL_VERSION_MISMATCH":
    case "MODEL_METADATA_UNAVAILABLE": return "로컬 모델을 이용할 수 없었습니다.";
    case "LLM_OUTPUT_REJECTED": case "INVALID_OUTPUT":
      return "모델 출력이 근거 검증을 통과하지 못했습니다.";
    case "TEMPLATE_FALLBACK_FAILED": case "FALLBACK_FAILED":
      return "안전한 템플릿 리포트도 생성하지 못했습니다.";
    case "WORKER_INTERRUPTED": return "리포트 작업이 중단됐습니다.";
    case "FASTAPI_RESPONSE_INVALID": return "AI 서비스 응답을 확인하지 못했습니다.";
    case "REPORT_INPUT_CHANGED": return "리포트 근거가 변경되어 생성을 마치지 못했습니다.";
    default: return "리포트 생성에 실패했습니다.";
  }
}

export function CaseAiReportSection({ caseId, caseStatus }: { readonly caseId: string; readonly caseStatus: string }) {
  const { state, refresh, create, canCreate } = useAiReport(caseId, caseStatus);
  return <CaseAiReportPanel current={state?.current ?? null} loading={state === null}
    error={state?.error ?? null} busy={state?.busy ?? false} canCreate={canCreate}
    onCreate={() => { void create(); }} onRefresh={refresh} />;
}

export function CaseAiReportPanel({ current, loading, error, busy, canCreate, onCreate, onRefresh }: {
  readonly current: AiReportCurrent | null;
  readonly loading: boolean;
  readonly error: string | null;
  readonly busy: boolean;
  readonly canCreate: boolean;
  readonly onCreate: () => void;
  readonly onRefresh: () => void;
}) {
  const report = current?.currentReport;
  const latest = current?.latestRequest;
  const pending = latest?.reportStatus === "PENDING" || latest?.reportStatus === "GENERATING";
  const earlier = report !== null && report !== undefined && latest !== null && latest !== undefined &&
    latest.reportId !== report.reportId && (pending || latest.reportStatus === "FAILED");
  const latestReason = failureMessage(latest?.failureCode ?? latest?.fallbackTriggerCode ?? null);
  const reportReason = failureMessage(report?.fallbackTriggerCode ?? report?.failureCode ?? null);
  return <section className="panel case-ai-report" aria-labelledby="case-ai-report-heading">
    <h3 id="case-ai-report-heading">AI 조사 보조 리포트</h3>
    <p>채택된 RULE 근거를 바탕으로 만든 조사 보조 초안입니다. 사건 상태와 판정은 변경하지 않습니다.</p>
    {loading && <p role="status">리포트 정보를 불러오는 중입니다.</p>}
    {error && <p role="alert">{error}</p>}
    {canCreate && <button type="button" className="button" disabled={busy || pending}
      onClick={onCreate}>리포트 생성 요청</button>}
    <button type="button" className="button" onClick={onRefresh}>리포트 새로고침</button>
    {latest && <p role="status">최근 요청: {latest.reportStatus} · 탐지 버전 {latest.detectionResultVersion}</p>}
    {earlier && <p>아래는 이전에 저장된 리포트입니다. 최신 요청 상태와 구분해 확인하세요.</p>}
    {latest?.reportStatus === "FAILED" && <p role="alert">최근 리포트 요청이 실패했습니다. {latestReason}</p>}
    {latest?.reportStatus === "FALLBACK_COMPLETED" && latestReason &&
      <p role="status">최근 요청은 안전한 템플릿으로 완료됐습니다. {latestReason}</p>}
    {report && <article className="case-ai-report__body">
      <h4>저장된 리포트</h4>
      <p>출처: {report.reportSource === "LLM" ? "로컬 LLM" : "RULE 근거 템플릿"} · 탐지 버전 {report.detectionResultVersion}</p>
      {report.reportSource === "TEMPLATE_FALLBACK" && reportReason &&
        <p>이 리포트의 fallback 원인: {reportReason}</p>}
      <p>{report.summary}</p>
      <h5>채택 근거</h5>
      <ul>{report.keyReasons.map((reason, index) =>
        <li key={`${reason.reasonCode}-${index}`}>{reason.reasonCode}: {reason.description}</li>)}</ul>
      <p>{report.timelineSummary}</p>
      <h5>확인 항목</h5>
      <ul>{report.investigationChecklist.map((item, index) => <li key={index}>{item}</li>)}</ul>
    </article>}
  </section>;
}
