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
