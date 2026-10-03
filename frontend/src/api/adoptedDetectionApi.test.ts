import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";
import { InvalidResponseError } from "./errors";
import { fetchAdoptedDetection, isAdoptedDetectionResponse } from "./adoptedDetectionApi";

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
  it("accepts an older adopted result while a later analysis is running", () => {
    expect(isAdoptedDetectionResponse(available)).toBe(true);
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
