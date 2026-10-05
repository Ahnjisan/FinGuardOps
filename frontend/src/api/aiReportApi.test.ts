import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";
import { createAiReport, fetchAiReportCurrent } from "./aiReportApi";
import { InvalidResponseError } from "./errors";

const caseId = "11111111-1111-4111-8111-111111111111";
const requestId = "33333333-3333-4333-8333-333333333333";
const auth = () => createFakeAuthClient({ initialSession: { subject: caseId, roles: ["FDS_ANALYST"] } });
beforeEach(() => vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080"));
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

it("binds current report response to the requested case", async () => {
  mockFetchOnce(async () => jsonResponse({ caseId, currentReport: null, latestRequest: null,
    traceId: "trace-test-001" }));
  expect((await fetchAiReportCurrent(auth(), caseId)).caseId).toBe(caseId);
});

it("refuses a report for another case", async () => {
  mockFetchOnce(async () => jsonResponse({ caseId: requestId, currentReport: null,
    latestRequest: null, traceId: "trace-test-001" }));
  await expect(fetchAiReportCurrent(auth(), caseId)).rejects.toBeInstanceOf(InvalidResponseError);
});

it("refuses provider internals in the public current response", async () => {
  mockFetchOnce(async () => jsonResponse({ caseId, currentReport: null,
    latestRequest: null, traceId: "trace-test-001", modelDigest: "synthetic-secret" }));
  await expect(fetchAiReportCurrent(auth(), caseId)).rejects.toBeInstanceOf(InvalidResponseError);
});

it("sends one exact idempotency key with the generation body", async () => {
  const key = "report-case-001";
  mockFetchOnce(async () => jsonResponse({ aiRequestId: requestId, executionId: requestId,
    executionShared: false, initiatingAiRequestId: requestId, reportId: null,
    caseId, detectionResultVersion: 1,
    reportStatus: "PENDING", reportSource: null, sourceAiRequestId: null, cacheHit: false,
    requestedAt: "2026-10-05T00:00:00Z", generatedAt: null, failureCode: null,
    resultLocation: `/api/v1/cases/${caseId}/ai-reports/current`,
    traceId: "trace-test-001" }, { status: 202 }));
  await createAiReport(auth(), caseId, 1, key);
  const request = vi.mocked(globalThis.fetch).mock.calls[0][0] as Request;
  expect(request.headers.get("Idempotency-Key")).toBe(key);
  expect(await request.json()).toEqual({ detectionResultVersion: 1, regenerationReason: null });
});

it("accepts an exact-match completed report reused with 200", async () => {
  mockFetchOnce(async () => jsonResponse({ aiRequestId: requestId, executionId: requestId,
    executionShared: false, initiatingAiRequestId: requestId, reportId: requestId,
    caseId, detectionResultVersion: 1,
    reportStatus: "COMPLETED", reportSource: "LLM", sourceAiRequestId: requestId, cacheHit: true,
    requestedAt: "2026-10-05T00:00:00Z", generatedAt: "2026-10-05T00:00:01Z", failureCode: null,
    resultLocation: `/api/v1/cases/${caseId}/ai-reports/current`,
    traceId: "trace-test-001" }, { status: 200 }));

  const result = await createAiReport(auth(), caseId, 1, "report-case-001");
  expect(result.reportStatus).toBe("COMPLETED");
  expect(result.cacheHit).toBe(true);
  expect(vi.mocked(globalThis.fetch)).toHaveBeenCalledTimes(1);
});
