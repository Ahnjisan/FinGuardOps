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
      {state.status === "success" && state.data.latestAnalysisStatus === "FAILED" &&
        state.data.latestFailureCode && <p>최근 ML 분석 실패: {state.data.latestFailureCode}.
          이 실패는 채택 결과의 점수와 별개입니다.</p>}
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
        {state.data.adoptedResult.ruleScore !== undefined &&
          <div><dt>{state.data.adoptedResult.scoreGroupSummary ? "Rule 채택 점수" : "Rule 원점수"}</dt>
            <dd>{state.data.adoptedResult.ruleScore} / 100</dd></div>}
        {state.data.adoptedResult.mlStatus !== undefined &&
          <div><dt>사기 탐지 ML 상태</dt><dd>{state.data.adoptedResult.mlStatus === "APPLIED" ?
            "결합됨" : "Rule 전용 결과"}</dd></div>}
        {state.data.adoptedResult.mlStatus === "APPLIED" && <>
          <div><dt>ML 기여도</dt><dd>{state.data.adoptedResult.mlRawContribution !== undefined
            ? <>원점수 +{state.data.adoptedResult.mlRawContribution}점 · 채택 +{state.data.adoptedResult.mlContribution}점</>
            : <>+{state.data.adoptedResult.mlContribution}점 (최종 100점 상한)</>}</dd></div>
          <div><dt>사기 탐지 모델 버전</dt><dd className="facts__ref">{state.data.adoptedResult.modelVersion}</dd></div>
          <div><dt>ML Feature 버전</dt><dd>{state.data.adoptedResult.mlFeatureVersion}</dd></div>
          <div><dt>모델 SHA-256</dt><dd className="facts__ref">{state.data.adoptedResult.modelSha256}</dd></div>
        </>}
      </dl>
      <h4>채택 결과의 규칙 근거</h4>
      {state.data.adoptedResult.ruleEvidence.length === 0 ? <p>저장된 매칭 RULE 근거가 없습니다.</p> :
        <ol className="adopted-detection__rules">{state.data.adoptedResult.ruleEvidence.map((rule, index) =>
          <li key={`${rule.ruleCode}-${index}`}>
            <strong>{rule.ruleCode}</strong> (버전 {rule.ruleVersion})<br />
            Reason Code: {rule.reasonCode}<br />원점수: {rule.scoreContribution}
            {rule.appliedContribution !== undefined && <> · 채택: {rule.appliedContribution}</>}
          </li>)}</ol>}
      {state.data.adoptedResult.scoreGroupSummary && <section aria-label="점수 그룹 상한">
        <h4>그룹별 점수 배분</h4><ol>{state.data.adoptedResult.scoreGroupSummary.map(group =>
          <li key={group.groupId}>{group.groupId}: 원점수 {group.rawScore} · 상한 {group.cap} · 채택 {group.appliedScore}</li>)}</ol>
      </section>}
      <p className="adopted-detection__note">개별 원점수의 합은 최종 위험 점수와 다를 수 있습니다.</p>
      {state.data.adoptedResult.scn001002Evidence && <section aria-label="신규 기기와 한도 변경 근거">
        <h4>SCN-001·002 평가 근거</h4>
        <p>기준선 {state.data.adoptedResult.scn001002Evidence.baselineStatus} · 표본 {state.data.adoptedResult.scn001002Evidence.baselineCount}건/{state.data.adoptedResult.scn001002Evidence.baselineDays}일</p>
        <p>수취인 {state.data.adoptedResult.scn001002Evidence.recipientStatus} · 기기 {state.data.adoptedResult.scn001002Evidence.deviceStatus}</p>
        <p>외부 위험 출처 {state.data.adoptedResult.scn001002Evidence.providerCode} · 기기 정확 일치 {state.data.adoptedResult.scn001002Evidence.exactDeviceMatched ? "확인" : "없음"}</p>
      </section>}
      {state.data.adoptedResult.aggregationEvidence && <section aria-label="송금 및 집금 집계 근거">
        <h4>SCN-004·005 집계 근거</h4>
        <p>출처 {state.data.adoptedResult.aggregationEvidence.mockSourceCode} · 계약 {state.data.adoptedResult.aggregationEvidence.sourceVersion}</p>
        <p>평가 cutoff {formatKstDateTime(state.data.adoptedResult.aggregationEvidence.evaluationCutoffAt)} KST</p>
        <ol>{(["outbound", "inbound"] as const).map((direction) => {
          const window = state.data.adoptedResult?.aggregationEvidence?.[direction];
          return window && <li key={direction}>{direction === "outbound" ? "10분 분산 송금" : "24시간 다고객 집금"}: {window.status} · 시작 {formatKstDateTime(window.windowStartAt)} KST · {window.transferCount}건 · 고유 관계 {window.distinctCount}개 · 합계 {window.totalAmount} KRW · 승인 시각 불명 {window.unknownCount}건 · Mock 증명 {window.coverageFactId ?? "없음"}</li>;
        })}</ol>
      </section>}
      {state.data.adoptedResult.fundFlowEvidence && <section aria-label="선행 자금 연결 근거">
        <h4>SCN-006·007 선행 자금 연결</h4>
        <p>출처 {state.data.adoptedResult.fundFlowEvidence.mockSourceCode} · 계약 {state.data.adoptedResult.fundFlowEvidence.sourceVersion} · 평가 cutoff {formatKstDateTime(state.data.adoptedResult.fundFlowEvidence.evaluationCutoffAt)} KST</p>
        <ol>{(["atm", "loan"] as const).map((kind) => {
          const flow = state.data.adoptedResult?.fundFlowEvidence?.[kind];
          return flow && <li key={kind}>{kind === "atm" ? "입금 후 ATM 인출" : "대출금 신규 수취인 송금"}: {flow.status} · 근거 {flow.reasonCode} · 원천 {flow.sourceTransactionId ?? "미확인"} · 연결 {flow.currentLinkId ?? "미확인"} · 원천금 {flow.sourceAmount} KRW · 연결금 {flow.linkedAmount} KRW · 비율 {flow.ratioBasisPoints}bp · 과거 연결 {flow.linkedPriorTransactionIds.length}건</li>;
        })}</ol>
      </section>}
      {state.data.adoptedResult.openBankingEvidence && <section aria-label="오픈뱅킹 다단계 흐름 근거">
        <h4>SCN-008 오픈뱅킹 다단계 흐름</h4>
        <p>로컬 Mock 출처 {state.data.adoptedResult.openBankingEvidence.mockSourceCode} · 계약 {state.data.adoptedResult.openBankingEvidence.sourceVersion} · 평가 cutoff {formatKstDateTime(state.data.adoptedResult.openBankingEvidence.evaluationCutoffAt)} KST</p>
        <p>상태 {state.data.adoptedResult.openBankingEvidence.status} · 근거 {state.data.adoptedResult.openBankingEvidence.reasonCode} · 집금 {state.data.adoptedResult.openBankingEvidence.concentrationAmount} KRW · 재송금 {state.data.adoptedResult.openBankingEvidence.resendAmount} KRW · 비율 {state.data.adoptedResult.openBankingEvidence.ratioBasisPoints}bp</p>
        <p>계좌 이력 증명 {state.data.adoptedResult.openBankingEvidence.historyCoverageFactId ?? "미확인"} · 검증된 외부 계좌 단계 {state.data.adoptedResult.openBankingEvidence.stages.length}건</p>
        <ol>{state.data.adoptedResult.openBankingEvidence.stages.map(stage =>
          <li key={stage.linkId}>원천 거래 {stage.sourceTransactionId} · 소유 Mock 증명 {stage.ownershipAssertionId} · 연결 {stage.linkId} · 승인 {formatKstDateTime(stage.approvedAt)} KST · {stage.amount} KRW</li>)}</ol>
      </section>}
      {state.data.adoptedResult.scn003Evidence && <section aria-label="외부 위험 수취 계좌 근거">
        <h4>외부 위험 수취 계좌 근거</h4>
        <p>출처 {state.data.adoptedResult.scn003Evidence.providerCode} · 계약 {state.data.adoptedResult.scn003Evidence.sourceVersion}</p>
        <p>기준 시각 {formatKstDateTime(state.data.adoptedResult.scn003Evidence.providerAsOf)} KST · 조회 시각 {formatKstDateTime(state.data.adoptedResult.scn003Evidence.lookedUpAt)} KST</p>
        <p>수취 계좌 일치: {state.data.adoptedResult.scn003Evidence.recipientAccountMatched ? "확인" : "없음"}</p>
        <p>적격 과거 승인 송금: {state.data.adoptedResult.scn003Evidence.priorApprovedRecipientTransferObserved ? "관측됨" : "관측되지 않음"}</p>
      </section>}
      {state.data.adoptedResult.mlStatus === "APPLIED" && <>
        <h4>채택 결과의 사기 탐지 ML 근거</h4>
        <p>5000bp 기준·최대 40점 가산은 합성 데이터로 검증하는 로컬 정책입니다. 실제 금융 사기 성능이나 운영 오탐 기준이 아닙니다.</p>
        <ol>{state.data.adoptedResult.mlEvidence?.map((item) =>
          <li key={item.reasonCode}>{item.reasonCode} · 확률 {item.probabilityBasisPoints}bp · {state.data.adoptedResult?.mlRawContribution !== undefined ? "원기여" : "기여"} +{item.scoreContribution}점</li>)}</ol>
      </>}
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
