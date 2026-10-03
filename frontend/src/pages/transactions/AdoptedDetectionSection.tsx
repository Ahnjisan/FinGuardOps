import { Icon } from "../../shared/Icon";
import { useAdoptedDetection, type AdoptedDetectionState } from "../../api/useAdoptedDetection";
import { formatKstDateTime } from "./transactionPresentation";

const AVAILABILITY_TEXT = {
  NO_HISTORY: "저장된 탐지 실행 이력이 없습니다.",
  PENDING: "탐지 실행 대기 중이며 채택된 결과가 없습니다.",
  IN_PROGRESS: "탐지 분석 중이며 채택된 결과가 없습니다.",
  FAILED: "최근 탐지 분석이 실패하여 채택된 결과가 없습니다.",
  COMPLETED_NOT_ADOPTED: "완료된 탐지 이력은 있으나 채택된 결과가 없습니다.",
  AVAILABLE: "채택된 탐지 결과가 있습니다.",
} as const;

export function AdoptedDetectionSection({ transactionId }: { readonly transactionId: string }) {
  const { state, retry } = useAdoptedDetection(transactionId);
  return <AdoptedDetectionPanel state={state} onRetry={retry} />;
}

export function AdoptedDetectionPanel({ state, onRetry }: {
  readonly state: AdoptedDetectionState; readonly onRetry: () => void;
}) {
  return <section className="panel adopted-detection" aria-labelledby="adopted-detection-heading"
    aria-busy={state.status === "loading" || undefined}>
    <h3 id="adopted-detection-heading">채택된 탐지 결과</h3>
    <p className="adopted-detection__note">위험 평가는 사기 확정이나 사건 최종 판정이 아닙니다.</p>
    <div role="status" aria-live="polite" aria-label="탐지 결과 조회 상태">
      {state.status === "idle" && <p>조회할 수 있는 거래가 없습니다.</p>}
      {state.status === "loading" && <p>채택된 탐지 결과를 불러오는 중입니다…</p>}
      {state.status === "success" && <p>{AVAILABILITY_TEXT[state.data.availability]}</p>}
    </div>
    {state.status === "success" && state.data.adoptedResult !== null && <>
      {state.data.latestDetectionResultVersion !== null &&
        state.data.latestDetectionResultVersion > state.data.adoptedResult.detectionResultVersion &&
        <p>이후 탐지 실행 버전 {state.data.latestDetectionResultVersion}의 상태: {state.data.latestAnalysisStatus}.
          아래 평가는 현재 채택 버전 {state.data.adoptedResult.detectionResultVersion}의 결과입니다.</p>}
      <dl className="facts">
        <div><dt>위험 등급</dt><dd>{state.data.adoptedResult.riskLevel}</dd></div>
        <div><dt>위험 점수</dt><dd>{state.data.adoptedResult.riskScore} / 100</dd></div>
        <div><dt>채택 결과 ID</dt><dd className="facts__ref">{state.data.adoptedResult.detectionResultId}</dd></div>
        <div><dt>채택 분석 버전</dt><dd>{state.data.adoptedResult.detectionResultVersion}</dd></div>
        <div><dt>탐지 분석 완료 시각</dt><dd><time dateTime={state.data.adoptedResult.analysisCompletedAt}>
          {formatKstDateTime(state.data.adoptedResult.analysisCompletedAt)} KST</time></dd></div>
        <div><dt>규칙 집합 버전</dt><dd className="facts__ref">{state.data.adoptedResult.ruleSetVersion}</dd></div>
        <div><dt>점수 정책 버전</dt><dd className="facts__ref">{state.data.adoptedResult.scoringPolicyVersion}</dd></div>
      </dl>
      <h4>채택 결과의 규칙 근거</h4>
      {state.data.adoptedResult.ruleEvidence.length === 0 ? <p>저장된 매칭 RULE 근거가 없습니다.</p> :
        <ol className="adopted-detection__rules">{state.data.adoptedResult.ruleEvidence.map((rule, index) =>
          <li key={`${rule.ruleCode}-${index}`}>
            <strong>{rule.ruleCode}</strong> (버전 {rule.ruleVersion})<br />
            Reason Code: {rule.reasonCode}<br />개별 기여도: {rule.scoreContribution}
          </li>)}</ol>}
      <p className="adopted-detection__note">개별 기여도의 합은 최종 위험 점수와 다를 수 있습니다.</p>
    </>}
    {(state.status === "forbidden" || state.status === "not-found" ||
      state.status === "authentication-required" || state.status === "error") &&
      <div className="notice notice--error" role="alert" tabIndex={-1}>
        <p className="notice__title">{state.status === "forbidden" ? "탐지 결과 조회 권한이 없습니다"
          : state.status === "not-found" ? "거래를 찾을 수 없습니다"
          : state.status === "authentication-required" ? "세션이 종료되었습니다" : "탐지 결과를 불러올 수 없습니다"}</p>
        {state.status === "error" && <>
          <p>{state.kind === "timeout" ? "조회가 지연되고 있습니다." : state.kind === "network"
            ? "백엔드에 연결할 수 없습니다." : state.kind === "invalid-response"
              ? "응답을 안전하게 표시할 수 없습니다." : "잠시 후 다시 시도하세요."}</p>
          <button className="button" type="button" onClick={onRetry}><Icon name="refresh" />다시 시도</button>
        </>}
      </div>}
  </section>;
}
