import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { isAdoptedDetectionResponse, type AdoptedDetectionResponse } from "../../api/adoptedDetectionApi";
import issue380Fixture from "../../test/issue380AdoptedFixture.json";
import { AdoptedDetectionPanel } from "./AdoptedDetectionSection";

const transactionId = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
function verified(value: unknown): AdoptedDetectionResponse {
  if (!isAdoptedDetectionResponse(value)) throw new Error("Invalid local E2E fixture");
  return value;
}

it("shows the Rule and fraud ML contributions with the synthetic policy warning", () => {
  render(<AdoptedDetectionPanel onRetry={() => undefined} state={{ status: "success",
    data: verified(issue380Fixture.adopted) }} />);
  expect(screen.getByText("Rule 원점수")).toBeInTheDocument();
  expect(screen.getByText("85 / 100")).toBeInTheDocument();
  expect(screen.getByText("100 / 100")).toBeInTheDocument();
  expect(screen.getAllByText(/\+35점/)).toHaveLength(2);
  expect(screen.getByText("+35점 (최종 100점 상한)")).toBeInTheDocument();
  expect(screen.queryByText(/채택 \+35점/)).not.toBeInTheDocument();
  expect(screen.getByText("RECENT_SECURITY_CHANGE_HIGH_AMOUNT")).toBeInTheDocument();
  expect(screen.getByText(/합성 데이터로 검증하는 로컬 정책/)).toBeInTheDocument();
  expect(screen.getByText("fraud-logistic-v2")).toBeInTheDocument();
  expect(screen.getByText("fraud-feature-v1")).toBeInTheDocument();
  expect(screen.getByText(issue380Fixture.adopted.adoptedResult.modelSha256)).toBeInTheDocument();
});

it("shows verified funding and unknown source evidence without account ownership claims", () => {
  const base = verified(issue380Fixture.adopted);
  const extended = { ...base, adoptedResult: { ...base.adoptedResult!,
    fundFlowEvidence: { sourceVersion: "SCN006-007-contract-v1" as const,
      mockSourceCode: "LOCAL_FDS_MOCK" as const, mockSourceVersion: "v2" as const,
      evaluationCutoffAt: "2026-10-11T00:00:00Z",
      atm: { status: "AVAILABLE" as const, reasonCode: "VERIFIED_SOURCE_TO_USE",
        sourceTransactionId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430112",
        currentLinkId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430113",
        historyCoverageFactId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430114",
        sourceOccurredAt: "2026-10-10T23:40:00Z", sourceApprovedAt: "2026-10-10T23:41:00Z",
        sourceAmount: "10000000", linkedAmount: "8000000", ratioBasisPoints: 8000,
        linkedPriorTransactionIds: [] },
      loan: { status: "UNKNOWN" as const, reasonCode: "NOT_OUTBOUND_TRANSFER",
        sourceAmount: "0", linkedAmount: "0", ratioBasisPoints: 0,
        linkedPriorTransactionIds: [] } },
  } } as AdoptedDetectionResponse;
  render(<AdoptedDetectionPanel onRetry={() => undefined} state={{ status: "success",
    data: extended }} />);
  expect(screen.getByText("SCN-006·007 선행 자금 연결")).toBeInTheDocument();
  expect(screen.getByText(/VERIFIED_SOURCE_TO_USE/)).toBeInTheDocument();
  expect(screen.getByText(/NOT_OUTBOUND_TRANSFER/)).toBeInTheDocument();
  expect(screen.queryByText(/외부 계좌 소유 확정/)).not.toBeInTheDocument();
});

it("shows the SCN-008 Mock chain stages without raw external accounts", () => {
  const base = verified(issue380Fixture.adopted);
  const extended = { ...base, adoptedResult: { ...base.adoptedResult!, openBankingEvidence: {
    sourceVersion: "SCN008-contract-v1" as const,
    mockSourceCode: "LOCAL_OPEN_BANKING_MOCK" as const, mockSourceVersion: "v1" as const,
    evaluationCutoffAt: "2026-10-11T00:00:00Z", status: "AVAILABLE" as const,
    reasonCode: "VERIFIED_OPEN_BANKING_CHAIN", concentrationAmount: "10000000",
    resendAmount: "8000000", ratioBasisPoints: 8000,
    historyCoverageFactId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430114",
    concentrationStartedAt: "2026-10-10T23:40:00Z",
    concentrationCompletedAt: "2026-10-10T23:42:00Z",
    stages: [{ sourceTransactionId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430112",
      ownershipAssertionId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430113",
      linkId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430115",
      occurredAt: "2026-10-10T23:40:00Z", approvedAt: "2026-10-10T23:41:00Z",
      amount: "3000000" },
    { sourceTransactionId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430116",
      ownershipAssertionId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430117",
      linkId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430118",
      occurredAt: "2026-10-10T23:42:00Z", approvedAt: "2026-10-10T23:43:00Z",
      amount: "7000000" }],
  } } } as AdoptedDetectionResponse;
  render(<AdoptedDetectionPanel onRetry={() => undefined} state={{ status: "success",
    data: extended }} />);
  expect(screen.getByText("SCN-008 오픈뱅킹 다단계 흐름")).toBeInTheDocument();
  expect(screen.getByText(/LOCAL_OPEN_BANKING_MOCK/)).toBeInTheDocument();
  expect(screen.getAllByText(/소유 Mock 증명/)).toHaveLength(2);
  expect(screen.queryByText(/external-a/)).not.toBeInTheDocument();
});

it("labels capped v4 Rule totals as adopted scores", () => {
  const legacy = verified(issue380Fixture.adopted);
  const adoptedResult = legacy.adoptedResult!;
  render(<AdoptedDetectionPanel onRetry={() => undefined} state={{ status: "success", data: {
    ...verified({ ...legacy, adoptedResult: {
      ...adoptedResult,
      scoringPolicyVersion: "rule-ml-policy-v3",
      mlFeatureVersion: "fraud-feature-v2",
      modelVersion: "fraud-logistic-v3",
      ruleEvidence: [
        { ruleCode: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT", ruleVersion: "4", reasonCode: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT", scoreContribution: 50, appliedContribution: 50 },
        { ruleCode: "RECENT_BENEFICIARY_TRANSFER", ruleVersion: "4", reasonCode: "RECENT_BENEFICIARY_TRANSFER", scoreContribution: 10, appliedContribution: 10 },
        { ruleCode: "CUSTOMER_BASELINE_HIGH_AMOUNT", ruleVersion: "1", reasonCode: "CUSTOMER_BASELINE_HIGH_AMOUNT", scoreContribution: 20, appliedContribution: 20 },
      ],
      ruleScore: 80,
      mlRawContribution: 40,
      mlContribution: 20,
      mlEvidence: [{ reasonCode: "ML_RISK_SIGNAL", probabilityBasisPoints: 10000, scoreContribution: 40 }],
      scoreGroupSummary: [
        { groupId: "amount", rawScore: 20, cap: 20, appliedScore: 20, reduction: 0 },
        { groupId: "security", rawScore: 50, cap: 60, appliedScore: 50, reduction: 0 },
        { groupId: "beneficiary", rawScore: 10, cap: 10, appliedScore: 10, reduction: 0 },
        { groupId: "external_recipient", rawScore: 0, cap: 40, appliedScore: 0, reduction: 0 },
      ],
      scn001002Evidence: {
        sourceVersion: "SCN001-002-contract-v1", baselineStatus: "AVAILABLE", baselineCount: 10, baselineDays: 10,
        recipientStatus: "FIRST", deviceStatus: "KNOWN", providerCode: "EXTERNAL_RISK_MOCK_V1",
        providerAsOf: "2026-10-10T00:55:47Z", lookedUpAt: "2026-10-10T00:55:47Z",
        recipientAccountMatched: false, exactDeviceMatched: false,
      },
    } }),
  } }} />);
  expect(screen.getByText("Rule 채택 점수")).toBeInTheDocument();
  expect(screen.getByText("원점수 +40점 · 채택 +20점")).toBeInTheDocument();
  expect(screen.getByText(/원기여 \+40점/)).toBeInTheDocument();
});

it("shows only the adopted version and keeps a later analysis separate", () => {
  render(<AdoptedDetectionPanel onRetry={() => undefined} state={{ status: "success", data: {
    transactionId, availability: "AVAILABLE", latestDetectionResultVersion: 2,
    latestAnalysisStatus: "IN_PROGRESS", adoptedResult: {
      detectionResultId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101",
      detectionResultVersion: 1, riskLevel: "HIGH", riskScore: 55,
      analysisCompletedAt: "2026-07-23T01:15:32Z", ruleSetVersion: "rule-v1",
      scoringPolicyVersion: "scoring-policy-v1", ruleEvidence: [
        { ruleCode: "R001", ruleVersion: "1", reasonCode: "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
          scoreContribution: 15 },
        { ruleCode: "R003", ruleVersion: "1", reasonCode: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
          scoreContribution: 40 },
      ],
    },
  } }} />);
  expect(screen.getByText(/이후 탐지 실행 버전 2/)).toHaveTextContent("IN_PROGRESS");
  expect(screen.getByText("55 / 100")).toBeInTheDocument();
  expect(screen.getByText("HIGH")).toBeInTheDocument();
  expect(screen.getByText(/개별 원점수의 합은/)).toBeInTheDocument();
  expect(document.querySelector("time")?.getAttribute("datetime")).toBe("2026-07-23T01:15:32Z");
  expect(screen.queryByText(/사기 확정률/)).not.toBeInTheDocument();
});

it("shows failed analysis without a synthetic risk grade", () => {
  render(<AdoptedDetectionPanel onRetry={() => undefined} state={{ status: "success",
    data: verified(issue380Fixture.failed) }} />);
  expect(screen.getByText(/최근 탐지 분석이 실패/)).toBeInTheDocument();
  expect(screen.getByText(/ML_MODEL_HASH_MISMATCH/)).toBeInTheDocument();
  expect(screen.queryByText("위험 등급")).not.toBeInTheDocument();
});
