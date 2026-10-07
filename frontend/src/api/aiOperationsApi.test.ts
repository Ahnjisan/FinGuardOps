import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";
import { fetchAiOutboxDiagnostic, fetchAiRequestDetail, fetchAiUsageSummary,
  requeueAiOutbox } from "./aiOperationsApi";
import { InvalidResponseError } from "./errors";

const id = "33333333-3333-4333-8333-333333333333";
const caseId = "11111111-1111-4111-8111-111111111111";
const range = { from: "2026-10-01T00:00:00Z", to: "2026-10-02T00:00:00Z" };
const auth = () => createFakeAuthClient({ initialSession: { subject: caseId,
  roles: ["PLATFORM_ADMIN"] } });
beforeEach(() => vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080"));
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

const base = { aiRequestId: id, executionId: id, executionShared: false,
  initiatingAiRequestId: id, reportId: null, caseId, detectionResultVersion: 1,
  reportStatus: "FAILED", reportSource: null, sourceAiRequestId: null,
  lastProvider: "OLLAMA_LOCAL", lastModel: null, promptVersion: "prompt-1",
  modelVersion: "model-1", inputTokens: null, outputTokens: null,
  totalTokens: null, estimatedCost: null, costCurrency: null, costBreakdown: null,
  latencyMs: null, cacheHit: false, fallbackUsed: false,
  requestedAt: "2026-10-01T01:00:00Z", completedAt: null,
  failureCode: "TIMEOUT", traceId: "trace-test-001" };
const attempt = { attemptNumber: 1, provider: "OLLAMA_LOCAL", model: null,
  outcome: "TIMEOUT", inputTokens: null, outputTokens: null, totalTokens: null,
  estimatedCost: null, costCurrency: null, latencyMs: 4000 };

it("accepts stored attempt detail without invented timestamps or cost", async () => {
  mockFetchOnce(async () => jsonResponse({ ...base, fallbackTriggerCode: "LLM_TIMEOUT", usageFinalized: true,
    requestedByRef: caseId, attempts: [attempt], queryTraceId: "trace-query-001" }));
  const result = await fetchAiRequestDetail(auth(), id);
  expect(result.attempts).toHaveLength(1);
  expect(result.costBreakdown).toBeNull();
  expect(result.fallbackTriggerCode).toBe("LLM_TIMEOUT");
});

it("rejects invented attempt failure detail", async () => {
  mockFetchOnce(async () => jsonResponse({ ...base, fallbackTriggerCode: null, usageFinalized: true,
    requestedByRef: caseId, attempts: [{ ...attempt, failureCode: "TIMEOUT" }],
    queryTraceId: "trace-query-001" }));
  await expect(fetchAiRequestDetail(auth(), id)).rejects.toBeInstanceOf(InvalidResponseError);
});

it("accepts distinct counts and refuses inconsistent request partition", async () => {
  const response = { ...range, requestCount: 3, executionCount: 1,
    providerCallCount: 2, successCount: 2, failureCount: 1, inProgressCount: 0,
    fallbackCount: 1, cacheHitCount: 1, inputTokens: null, outputTokens: null,
    totalTokens: null, estimatedCost: null, costCurrency: null, costBreakdown: null,
    averageLatencyMs: null, traceId: "trace-summary-001" };
  mockFetchOnce(async () => jsonResponse(response));
  expect((await fetchAiUsageSummary(auth(), range)).providerCallCount).toBe(2);
  mockFetchOnce(async () => jsonResponse({ ...response, providerCallCount: 3,
    successCount: 3 }));
  await expect(fetchAiUsageSummary(auth(), range)).rejects.toBeInstanceOf(InvalidResponseError);
});

it("accepts only the exact safe outbox diagnostic and 202 DB requeue response", async () => {
  const diagnostic = { eventId: "44444444-4444-4444-8444-444444444444", executionId: id,
    outboxStatus: "BLOCKED", attemptCount: 10, failureCode: "PUBLISH_FAILED",
    executionStatus: "PENDING", executionFailureCode: null,
    requests: [{ aiRequestId: id, status: "PENDING" }], reportExists: false,
    attemptExists: false, requeueAllowed: true, rejectionReason: null,
    previouslyRequeued: false, traceId: "trace-query-001" };
  mockFetchOnce(async () => jsonResponse(diagnostic));
  expect((await fetchAiOutboxDiagnostic(auth(), id)).requeueAllowed).toBe(true);
  mockFetchOnce(async () => jsonResponse({ ...diagnostic, payload: "secret" }));
  await expect(fetchAiOutboxDiagnostic(auth(), id)).rejects.toBeInstanceOf(InvalidResponseError);
  mockFetchOnce(async () => jsonResponse({ ...diagnostic, outboxStatus: "PENDING",
    attemptCount: 0, requeueAllowed: false, rejectionReason: "OUTBOX_NOT_BLOCKED",
    previouslyRequeued: true }, { status: 202 }));
  expect((await requeueAiOutbox(auth(), diagnostic)).outboxStatus).toBe("PENDING");
});
