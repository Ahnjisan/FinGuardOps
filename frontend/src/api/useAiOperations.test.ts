import { createElement, type ReactNode } from "react";
import { MemoryRouter } from "react-router-dom";
import { act, render, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import type { AuthSession } from "../auth/authClient";
import { AuthContext, type AuthContextValue } from "../auth/authContext";
import { AiOperationsPage } from "../pages/AiOperationsPage";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import type { AiUsageFilters, AiUsageListQuery } from "./aiOperationsApi";

const adapter = vi.hoisted(() => ({ client: null as unknown }));
const probe = vi.hoisted(() => ({ summaryQueries: [] as unknown[] }));
vi.mock("../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => adapter.client }));
vi.mock("./aiOperationsApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./aiOperationsApi")>();
  return { ...actual,
    fetchAiUsageSummary: (...args: Parameters<typeof actual.fetchAiUsageSummary>) => {
      probe.summaryQueries.push(args[1]);
      return actual.fetchAiUsageSummary(...args);
    } };
});

const { useAiUsage, useAiOutbox } = await import("./useAiOperations");
const session: AuthSession = { subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: ["PLATFORM_ADMIN"] };

function authenticatedWrapper(client: FakeAuthClient) {
  const value: AuthContextValue = {
    state: { status: "authenticated", session }, client,
    signIn: () => undefined, signOut: () => undefined,
    notifyCallbackStarted: () => undefined,
    notifyCallbackSucceeded: () => undefined,
    notifyCallbackFailed: () => undefined,
  };
  return ({ children }: { children: ReactNode }) =>
    createElement(AuthContext.Provider, { value }, children);
}

function observeRequests() {
  const requests: Request[] = [];
  const fetch = vi.fn(async (request: Request) => {
    requests.push(request);
    const url = new URL(request.url);
    if (url.pathname === "/api/v1/ai-report-usage") {
      return jsonResponse({ content: [], page: { number: 0, size: 20, totalElements: 0,
        totalPages: 0, first: true, last: true }, traceId: "trace-list-001" });
    }
    if (url.pathname === "/api/v1/ai-report-usage/summary") {
      return jsonResponse({ from: url.searchParams.get("from"), to: url.searchParams.get("to"),
        requestCount: 0, executionCount: 0, providerCallCount: 0, successCount: 0,
        failureCount: 0, inProgressCount: 0, fallbackCount: 0, cacheHitCount: 0,
        inputTokens: null, outputTokens: null, totalTokens: null, estimatedCost: null,
        costCurrency: null, costBreakdown: [], averageLatencyMs: null,
        traceId: "trace-summary-001" });
    }
    throw new Error("An unexpected Backend path was requested.");
  });
  vi.stubGlobal("fetch", fetch);
  return { requests, fetch };
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  adapter.client = createFakeAuthClient({ initialSession: session });
  probe.summaryQueries.length = 0;
});
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

it("classifies forbidden diagnosis without retaining a requeue action", async () => {
  vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 403 })));
  const client = adapter.client as FakeAuthClient;
  const view = renderHook(() => useAiOutbox("33333333-3333-4333-8333-333333333333"),
    { wrapper: authenticatedWrapper(client) });
  await waitFor(() => expect(view.result.current.error).toBe("forbidden"));
  expect(view.result.current.data).toBeNull();
});

it("classifies requeue conflict and refreshes before another action", async () => {
  const executionId = "33333333-3333-4333-8333-333333333333";
  const diagnostic = { eventId: "44444444-4444-4444-8444-444444444444", executionId,
    outboxStatus: "BLOCKED", attemptCount: 10, failureCode: "PUBLISH_FAILED",
    executionStatus: "PENDING", executionFailureCode: null,
    requests: [{ aiRequestId: executionId, status: "PENDING" }],
    reportExists: false, attemptExists: false, requeueAllowed: true,
    rejectionReason: null, previouslyRequeued: false, traceId: "trace-query-001" };
  let reads = 0;
  let releaseRefresh: ((response: Response) => void) | undefined;
  const fetch = vi.fn((request: Request) => {
    if (request.method === "POST") return Promise.resolve(new Response(null, { status: 409 }));
    reads += 1;
    if (reads === 1) return Promise.resolve(jsonResponse(diagnostic));
    return new Promise<Response>((resolve) => { releaseRefresh = resolve; });
  });
  vi.stubGlobal("fetch", fetch);
  const client = adapter.client as FakeAuthClient;
  const view = renderHook(() => useAiOutbox(executionId),
    { wrapper: authenticatedWrapper(client) });
  await waitFor(() => expect(view.result.current.data?.requeueAllowed).toBe(true));
  await act(async () => { await view.result.current.requeue(); });
  expect(view.result.current.error).toBe("conflict");
  await waitFor(() => expect(reads).toBe(2));
  await act(async () => {
    releaseRefresh?.(jsonResponse({ ...diagnostic, outboxStatus: "PUBLISHED",
      requeueAllowed: false, rejectionReason: "OUTBOX_NOT_BLOCKED" }));
  });
  await waitFor(() => expect(view.result.current.data?.requeueAllowed).toBe(false));
  expect(view.result.current.error).toBeNull();
  expect(fetch).toHaveBeenCalledTimes(3);
});

it("starts one list and one summary GET for the default 24-hour period without unset filters", async () => {
  const { requests, fetch } = observeRequests();
  const client = adapter.client as FakeAuthClient;
  render(createElement(authenticatedWrapper(client), null,
    createElement(MemoryRouter, null, createElement(AiOperationsPage))));

  await waitFor(() => expect(requests).toHaveLength(2));
  await waitFor(() => expect(probe.summaryQueries).toHaveLength(1));
  const [list, summary] = requests.map((request) => new URL(request.url));
  expect(requests.map((request) => request.method)).toEqual(["GET", "GET"]);
  expect(list.pathname).toBe("/api/v1/ai-report-usage");
  expect(summary.pathname).toBe("/api/v1/ai-report-usage/summary");
  expect(list.searchParams.get("from")).toBe(summary.searchParams.get("from"));
  expect(list.searchParams.get("to")).toBe(summary.searchParams.get("to"));
  expect(Date.parse(summary.searchParams.get("to")!) -
    Date.parse(summary.searchParams.get("from")!)).toBe(24 * 60 * 60 * 1000);
  expect([...list.searchParams.keys()]).toEqual(["from", "to", "page", "size", "sort"]);
  expect([...summary.searchParams.keys()]).toEqual(["from", "to"]);
  expect(Object.keys(probe.summaryQueries[0] as AiUsageFilters)).toEqual(["from", "to"]);
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(client.calls.authorizeRequest).toBe(2);
});

it("passes configured provider and model to summary while omitting every unset filter", async () => {
  const { requests, fetch } = observeRequests();
  const client = adapter.client as FakeAuthClient;
  const query: AiUsageListQuery = { from: "2026-10-01T00:00:00.000Z",
    to: "2026-10-02T00:00:00.000Z", provider: "OLLAMA_LOCAL", model: "model-v1",
    page: "0", size: "20", sort: "requestedAt,desc" };
  const wrapper = authenticatedWrapper(client);
  const view = renderHook(() => useAiUsage(query), { wrapper });

  await waitFor(() => expect(view.result.current.summary.data).not.toBeNull());
  expect(requests).toHaveLength(2);
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(client.calls.authorizeRequest).toBe(2);
  expect(Object.keys(probe.summaryQueries[0] as AiUsageFilters)).toEqual(
    ["from", "to", "provider", "model"],
  );
  const summary = requests.map((request) => new URL(request.url))
    .find((url) => url.pathname === "/api/v1/ai-report-usage/summary");
  expect(summary).toBeDefined();
  expect([...summary!.searchParams.keys()]).toEqual(["from", "to", "provider", "model"]);
  expect(summary!.searchParams.get("provider")).toBe("OLLAMA_LOCAL");
  expect(summary!.searchParams.get("model")).toBe("model-v1");
});
