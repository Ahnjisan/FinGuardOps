import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";
import { InvalidResponseError } from "./errors";
import { fetchAdoptedDetection, isAdoptedDetectionResponse } from "./adoptedDetectionApi";
import issue380Fixture from "../test/issue380AdoptedFixture.json";

const transactionId = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const resultId = "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101";
const adopted = {
  detectionResultId: resultId, detectionResultVersion: 1, riskLevel: "HIGH", riskScore: 55,
  analysisCompletedAt: "2026-07-23T01:15:32Z", ruleSetVersion: "rule-v1",
  scoringPolicyVersion: "scoring-policy-v1",
  ruleEvidence: [{ ruleCode: "R001", ruleVersion: "1",
    reasonCode: "TRANSFER_ABSOLUTE_HIGH_AMOUNT", scoreContribution: 15 }],
};
const available = { transactionId, availability: "AVAILABLE", latestDetectionResultVersion: 2,
  latestAnalysisStatus: "IN_PROGRESS", adoptedResult: adopted };
beforeEach(() => vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080"));
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

describe("adopted detection response", () => {
  it("accepts v5 aggregation evidence and rejects leaked account references", () => {
    const aggregation = {
      sourceVersion: "SCN004-005-contract-v1", mockSourceCode: "LOCAL_FDS_MOCK",
      mockSourceVersion: "v1", evaluationCutoffAt: "2026-10-11T00:00:00Z",
      outbound: { windowStartAt: "2026-10-10T23:50:00Z", status: "AVAILABLE", transferCount: 3, distinctCount: 3,
        totalAmount: "3300000", unknownCount: 0,
        coverageFactId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430111" },
      inbound: { windowStartAt: "2026-10-10T00:00:00Z", status: "UNKNOWN", transferCount: 1, distinctCount: 1,
        totalAmount: "1200000", unknownCount: 0 },
    };
    const result = { ...available, latestDetectionResultVersion: 1,
      latestAnalysisStatus: "COMPLETED", adoptedResult: {
        detectionResultId: resultId, detectionResultVersion: 1, riskLevel: "MEDIUM",
        riskScore: 49, analysisCompletedAt: "2026-10-11T00:00:00Z",
        ruleSetVersion: "a".repeat(64), scoringPolicyVersion: "rule-ml-policy-v4",
        ruleEvidence: [{ ruleCode: "DISTRIBUTED_OUTBOUND_WINDOW", ruleVersion: "1",
          reasonCode: "DISTRIBUTED_OUTBOUND_WINDOW", scoreContribution: 25,
          appliedContribution: 25 }],
        ruleScore: 25, mlContribution: 24, mlRawContribution: 32,
        mlStatus: "APPLIED", modelVersion: "fraud-logistic-v4",
        mlFeatureVersion: "fraud-feature-v3", modelSha256: "a".repeat(64),
        mlEvidence: [{ reasonCode: "ML_RISK_SIGNAL", scoreContribution: 32,
          probabilityBasisPoints: 9000 }],
        scoreGroupSummary: ["amount", "security", "beneficiary", "external_recipient"].map(groupId =>
          ({ groupId, rawScore: 0, cap: 40, appliedScore: 0, reduction: 0 })).concat([
          { groupId: "flow_aggregation", rawScore: 25, cap: 40,
            appliedScore: 25, reduction: 0 }]),
        scn001002Evidence: { sourceVersion: "SCN001-002-contract-v1",
          baselineStatus: "INSUFFICIENT", baselineCount: 0, baselineDays: 0,
          recipientStatus: "UNKNOWN", deviceStatus: "UNKNOWN", providerCode: "PROVIDER_V1",
          providerAsOf: "2026-10-11T00:00:00Z", lookedUpAt: "2026-10-11T00:00:00Z",
          recipientAccountMatched: false, exactDeviceMatched: false },
        aggregationEvidence: aggregation,
      } };
    expect(isAdoptedDetectionResponse(result)).toBe(true);
    const persistedKrW = { ...result, adoptedResult: { ...result.adoptedResult,
      aggregationEvidence: { ...aggregation,
        outbound: { ...aggregation.outbound, totalAmount: "3300000.0000" },
        inbound: { ...aggregation.inbound, totalAmount: "1200000.0000" },
      },
    } };
    expect(isAdoptedDetectionResponse(persistedKrW)).toBe(true);
    expect(isAdoptedDetectionResponse({ ...persistedKrW, adoptedResult: {
      ...persistedKrW.adoptedResult, aggregationEvidence: { ...persistedKrW.adoptedResult.aggregationEvidence,
        outbound: { ...aggregation.outbound, totalAmount: "3300000.0001" },
      },
    } })).toBe(false);
    expect(isAdoptedDetectionResponse({ ...result, adoptedResult: {
      ...result.adoptedResult, aggregationEvidence: { ...aggregation,
        outbound: { ...aggregation.outbound, recipientAccountRef: "raw-account" } },
    } })).toBe(false);
    const unknown = { status: "UNKNOWN", reasonCode: "NOT_OUTBOUND_TRANSFER",
      sourceAmount: "0", linkedAmount: "0", ratioBasisPoints: 0,
      linkedPriorTransactionIds: [] };
    const flow = { sourceVersion: "SCN006-007-contract-v1", mockSourceCode: "LOCAL_FDS_MOCK",
      mockSourceVersion: "v2", evaluationCutoffAt: "2026-10-11T00:00:00Z",
      atm: { status: "AVAILABLE", reasonCode: "VERIFIED_SOURCE_TO_USE",
        sourceTransactionId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430112",
        currentLinkId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430113",
        historyCoverageFactId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430114",
        sourceOccurredAt: "2026-10-10T23:40:00Z", sourceApprovedAt: "2026-10-10T23:41:00Z",
        sourceAmount: "10000000", linkedAmount: "8000000", ratioBasisPoints: 8000,
        linkedPriorTransactionIds: [] }, loan: unknown };
    const v6 = { ...result, adoptedResult: { ...result.adoptedResult,
      scoringPolicyVersion: "rule-ml-policy-v5", modelVersion: "fraud-logistic-v5",
      mlFeatureVersion: "fraud-feature-v4", riskLevel: "HIGH", riskScore: 79,
      ruleScore: 75, mlContribution: 4,
      ruleEvidence: [...result.adoptedResult.ruleEvidence,
        { ruleCode: "VERIFIED_INBOUND_ATM_DRAIN", ruleVersion: "1",
          reasonCode: "VERIFIED_INBOUND_ATM_DRAIN", scoreContribution: 50,
          appliedContribution: 50 }],
      scoreGroupSummary: [...result.adoptedResult.scoreGroupSummary,
        { groupId: "flow_link", rawScore: 50, cap: 50, appliedScore: 50, reduction: 0 }],
      fundFlowEvidence: flow } };
    expect(isAdoptedDetectionResponse(v6)).toBe(true);
    const persistedFlow = { ...v6, adoptedResult: { ...v6.adoptedResult,
      fundFlowEvidence: { ...flow, atm: { ...flow.atm,
        sourceAmount: "10000000.0000", linkedAmount: "8000000.0000" } },
    } };
    expect(isAdoptedDetectionResponse(persistedFlow)).toBe(true);
    expect(isAdoptedDetectionResponse({ ...persistedFlow, adoptedResult: {
      ...persistedFlow.adoptedResult, fundFlowEvidence: { ...persistedFlow.adoptedResult.fundFlowEvidence,
        atm: { ...flow.atm, sourceAmount: "10000000.0001" } },
    } })).toBe(false);
    expect(isAdoptedDetectionResponse({ ...v6, adoptedResult: { ...v6.adoptedResult,
      fundFlowEvidence: { ...flow, atm: { ...flow.atm, externalAccountRef: "unproved" } } } }))
      .toBe(false);
    const open = { sourceVersion: "SCN008-contract-v1", mockSourceCode: "LOCAL_OPEN_BANKING_MOCK",
      mockSourceVersion: "v1", evaluationCutoffAt: "2026-10-11T00:00:00Z",
      status: "UNKNOWN", reasonCode: "ACCOUNT_HISTORY_INCOMPLETE", concentrationAmount: "0",
      resendAmount: "0", ratioBasisPoints: 0, stages: [] };
    const v7 = { ...v6, adoptedResult: { ...v6.adoptedResult,
      scoringPolicyVersion: "rule-ml-policy-v6", modelVersion: "fraud-logistic-v6",
      mlFeatureVersion: "fraud-feature-v5",
      scoreGroupSummary: [...v6.adoptedResult.scoreGroupSummary,
        { groupId: "open_banking_chain", rawScore: 0, cap: 50, appliedScore: 0, reduction: 0 }],
      openBankingEvidence: open } };
    expect(isAdoptedDetectionResponse(v7)).toBe(true);
    expect(isAdoptedDetectionResponse({ ...v7, adoptedResult: { ...v7.adoptedResult,
      aggregationEvidence: persistedKrW.adoptedResult.aggregationEvidence,
    } })).toBe(true);
    const openAvailable = { ...open, status: "AVAILABLE", reasonCode: "VERIFIED_OPEN_BANKING_CHAIN",
      concentrationAmount: "10000000.0000", resendAmount: "8000000.0000", ratioBasisPoints: 8000,
      historyCoverageFactId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430114",
      concentrationStartedAt: "2026-10-10T23:40:00Z",
      concentrationCompletedAt: "2026-10-10T23:42:00Z",
      stages: [
        { sourceTransactionId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430112", ownershipAssertionId: resultId,
          linkId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430115", occurredAt: "2026-10-10T23:40:00Z",
          approvedAt: "2026-10-10T23:41:00Z", amount: "5000000.0000" },
        { sourceTransactionId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430116", ownershipAssertionId: transactionId,
          linkId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430117", occurredAt: "2026-10-10T23:42:00Z",
          approvedAt: "2026-10-10T23:43:00Z", amount: "5000000.0000" },
      ],
    };
    const v7Open = { ...v7, adoptedResult: { ...v7.adoptedResult,
      ruleEvidence: [v7.adoptedResult.ruleEvidence[0],
        { ruleCode: "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND", ruleVersion: "1",
          reasonCode: "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND", scoreContribution: 50,
          appliedContribution: 50 }],
      scoreGroupSummary: v7.adoptedResult.scoreGroupSummary.map(group =>
        group.groupId === "flow_link" ? { ...group, rawScore: 0, appliedScore: 0 } :
          group.groupId === "open_banking_chain" ? { ...group, rawScore: 50, appliedScore: 50 } : group),
      fundFlowEvidence: { ...flow, atm: unknown },
      openBankingEvidence: openAvailable,
    } };
    expect(isAdoptedDetectionResponse({ ...v7Open, adoptedResult: { ...v7Open.adoptedResult,
      openBankingEvidence: { ...openAvailable, concentrationAmount: "10000000",
        resendAmount: "8000000", stages: openAvailable.stages.map(stage => ({
          ...stage, amount: "5000000" })) },
    } })).toBe(true);
    expect(isAdoptedDetectionResponse(v7Open)).toBe(true);
    expect(isAdoptedDetectionResponse({ ...v7Open, adoptedResult: { ...v7Open.adoptedResult,
      openBankingEvidence: { ...openAvailable, resendAmount: "8000000.0001" },
    } })).toBe(false);
    expect(isAdoptedDetectionResponse({ ...v7, adoptedResult: { ...v7.adoptedResult,
      openBankingEvidence: { ...open, externalAccountRef: "unproved" } } })).toBe(false);
  });
  it("accepts attributed v4 scoring and rejects missing rule attribution", () => {
    const v4 = {
      transactionId,
      availability: "AVAILABLE",
      latestDetectionResultVersion: 1,
      latestAnalysisStatus: "COMPLETED",
      adoptedResult: {
        detectionResultId: resultId, detectionResultVersion: 1,
        riskLevel: "CRITICAL", riskScore: 100,
        analysisCompletedAt: "2026-10-11T00:00:00Z",
        ruleSetVersion: "a".repeat(64), scoringPolicyVersion: "rule-ml-policy-v3",
        ruleEvidence: [
          { ruleCode: "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT", ruleVersion: "4",
            reasonCode: "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT", scoreContribution: 20,
            appliedContribution: 20 },
          { ruleCode: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT", ruleVersion: "4",
            reasonCode: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT", scoreContribution: 50,
            appliedContribution: 40 },
          { ruleCode: "RECENT_BENEFICIARY_TRANSFER", ruleVersion: "4",
            reasonCode: "RECENT_BENEFICIARY_TRANSFER", scoreContribution: 10,
            appliedContribution: 10 },
          { ruleCode: "CUSTOMER_BASELINE_HIGH_AMOUNT", ruleVersion: "1",
            reasonCode: "CUSTOMER_BASELINE_HIGH_AMOUNT", scoreContribution: 20,
            appliedContribution: 20 },
        ],
        ruleScore: 90, mlContribution: 10, mlRawContribution: 40,
        mlStatus: "APPLIED", modelVersion: "fraud-logistic-v3",
        mlFeatureVersion: "fraud-feature-v2", modelSha256: "a".repeat(64),
        mlEvidence: [{ reasonCode: "ML_RISK_SIGNAL", scoreContribution: 40,
          probabilityBasisPoints: 10000 }],
        scoreGroupSummary: [
          { groupId: "amount", rawScore: 20, cap: 20, appliedScore: 20, reduction: 0 },
          { groupId: "security", rawScore: 70, cap: 60, appliedScore: 60, reduction: 10 },
          { groupId: "beneficiary", rawScore: 10, cap: 10, appliedScore: 10, reduction: 0 },
          { groupId: "external_recipient", rawScore: 0, cap: 40, appliedScore: 0, reduction: 0 },
        ],
        scn001002Evidence: { sourceVersion: "SCN001-002-contract-v1", baselineStatus: "AVAILABLE",
          baselineCount: 10, baselineDays: 3, recipientStatus: "FIRST",
          deviceStatus: "NEW_UNVERIFIED", providerCode: "PROVIDER_V1",
          providerAsOf: "2026-10-11T00:00:00Z", lookedUpAt: "2026-10-11T00:00:00Z",
          recipientAccountMatched: false, exactDeviceMatched: false },
      },
    };
    expect(isAdoptedDetectionResponse(v4)).toBe(true);
    const missing = structuredClone(v4);
    delete (missing.adoptedResult.ruleEvidence[0] as { appliedContribution?: number }).appliedContribution;
    expect(isAdoptedDetectionResponse(missing)).toBe(false);
    expect(isAdoptedDetectionResponse({ ...v4, adoptedResult: {
      ...v4.adoptedResult, recipientAccountRef: "raw-secret" } })).toBe(false);
  });
  it("accepts the same adopted and failed responses captured in the local transaction E2E", () => {
    expect(isAdoptedDetectionResponse(issue380Fixture.adopted)).toBe(true);
    expect(isAdoptedDetectionResponse(issue380Fixture.failed)).toBe(true);
    expect(isAdoptedDetectionResponse({ ...issue380Fixture.adopted, adoptedResult: {
      ...issue380Fixture.adopted.adoptedResult, riskScore: 99,
    } })).toBe(false);
  });
  it("validates a combined Rule and ML result and rejects inconsistent totals", () => {
    const combined = { ...available, latestFailureCode: null, adoptedResult: {
      ...adopted, riskLevel: "CRITICAL", riskScore: 80, scoringPolicyVersion: "rule-ml-policy-v1",
      ruleScore: 55, mlContribution: 25, mlStatus: "APPLIED",
      modelVersion: "fraud-logistic-v1", mlFeatureVersion: "fraud-feature-v1",
      modelSha256: "a".repeat(64),
      mlEvidence: [{ reasonCode: "ML_RISK_SIGNAL", scoreContribution: 25,
        probabilityBasisPoints: 8125 }],
    } };
    expect(isAdoptedDetectionResponse(combined)).toBe(true);
    expect(isAdoptedDetectionResponse({ ...combined, adoptedResult:
      { ...combined.adoptedResult, riskScore: 79 } })).toBe(false);
    expect(isAdoptedDetectionResponse({ ...combined, adoptedResult:
      { ...combined.adoptedResult, riskLevel: "HIGH" } })).toBe(false);
    expect(isAdoptedDetectionResponse({ ...available, adoptedResult: {
      ...adopted, scoringPolicyVersion: "rule-ml-policy-v1",
    } })).toBe(false);
    expect(isAdoptedDetectionResponse({ ...combined, adoptedResult: {
      ...combined.adoptedResult, mlStatus: "RULE_ONLY", mlContribution: null,
      modelVersion: null, mlFeatureVersion: null, modelSha256: null, mlEvidence: [],
      ruleScore: combined.adoptedResult.riskScore,
    } })).toBe(false);
  });
  it("accepts an older adopted result while a later analysis is running", () => {
    expect(isAdoptedDetectionResponse(available)).toBe(true);
  });
  it("shows only allowlisted SCN-003 source and rejects account leakage", () => {
    const result = { ...adopted, riskLevel: "MEDIUM", riskScore: 40,
      scoringPolicyVersion: "scoring-policy-v3", ruleScore: 40,
      mlContribution: null, mlStatus: "RULE_ONLY", modelVersion: null,
      mlFeatureVersion: null, modelSha256: null, mlEvidence: [],
      ruleEvidence: [{ ruleCode: "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT",
        ruleVersion: "1", reasonCode: "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT",
        scoreContribution: 40 }],
      scn003Evidence: { sourceVersion: "SCN003-contract-v1", providerCode: "PROVIDER_V1",
        providerAsOf: "2026-07-23T01:15:00Z", lookedUpAt: "2026-07-23T01:15:32Z",
        recipientAccountMatched: true, priorApprovedRecipientTransferObserved: false },
    };
    expect(isAdoptedDetectionResponse({ ...available, adoptedResult: result })).toBe(true);
    expect(isAdoptedDetectionResponse({ ...available, adoptedResult: { ...result,
      scn003Evidence: { ...result.scn003Evidence, recipientAccountRef: "raw-account" } } })).toBe(false);
    expect(isAdoptedDetectionResponse({ ...available, adoptedResult: { ...result,
      scn003Evidence: { ...result.scn003Evidence, recipientAccountMatched: false } } })).toBe(false);
  });
  it.each(["PENDING", "IN_PROGRESS", "FAILED", "COMPLETED_NOT_ADOPTED"])(
    "accepts unadopted %s without a risk value", (availability) => {
      expect(isAdoptedDetectionResponse({ transactionId, availability,
        latestDetectionResultVersion: 1,
        latestAnalysisStatus: availability === "COMPLETED_NOT_ADOPTED" ? "COMPLETED" : availability,
        adoptedResult: null })).toBe(true);
    });
  it("accepts no history only with null version and status", () => {
    expect(isAdoptedDetectionResponse({ transactionId, availability: "NO_HISTORY",
      latestDetectionResultVersion: null, latestAnalysisStatus: null, adoptedResult: null })).toBe(true);
  });
  it.each([
    { ...available, adoptedResult: { ...adopted, observationSummary: { raw: "secret" } } },
    { ...available, adoptedResult: { ...adopted, ruleEvidence: [
      { ...adopted.ruleEvidence[0], failureCode: "INTERNAL" }] } },
    { ...available, traceId: "trace_internal" },
    { ...available, adoptedResult: { ...adopted, detectionResultVersion: 3 } },
    { ...available, adoptedResult: { ...adopted, riskScore: 101 } },
    { ...available, adoptedResult: null },
  ])("rejects inconsistent or extra fields", (body) => {
    expect(isAdoptedDetectionResponse(body)).toBe(false);
  });
  it("binds the exact requested transaction ID and sends no query", async () => {
    mockFetchOnce(async () => jsonResponse(available));
    const fetch = vi.mocked(globalThis.fetch);
    const client = createFakeAuthClient({ initialSession: { subject: transactionId, roles: ["FDS_VIEWER"] } });
    const result = await fetchAdoptedDetection(client, transactionId);
    expect(result).toEqual(available);
    expect(new URL((fetch.mock.calls[0][0] as Request).url).pathname)
      .toBe(`/api/v1/transactions/${transactionId}/adopted-detection-result`);
    expect(new URL((fetch.mock.calls[0][0] as Request).url).search).toBe("");
  });
  it("rejects a well-shaped response for another transaction", async () => {
    mockFetchOnce(async () => jsonResponse({ ...available, transactionId: resultId }));
    const client = createFakeAuthClient({ initialSession: { subject: transactionId, roles: ["FDS_VIEWER"] } });
    await expect(fetchAdoptedDetection(client, transactionId)).rejects.toBeInstanceOf(InvalidResponseError);
  });
});
