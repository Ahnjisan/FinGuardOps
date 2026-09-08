import { createElement, StrictMode, useEffect, useMemo, useState, type ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthClient, AuthSession } from "../auth/authClient";
import { AuthContext, type AuthContextValue } from "../auth/authContext";
import { AuthProvider } from "../auth/AuthProvider";
import type { AuthState } from "../auth/authState";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import type { CaseListQuery } from "./caseApi";

/**
 * The adapter the hook reaches for at its own credential boundary.
 *
 * The hook calls `getOidcAuthClient()` inside an effect and nowhere else, so
 * this is the one seam a test has to stand in at. Everything below it is
 * production code: the real `fetchCaseList`, the real query builder, the real
 * URL re-verification, the real authenticated transport and the real response
 * validator all run here. Only `fetch` itself and the OIDC adapter are doubles.
 */
const adapter = vi.hoisted(() => ({ client: null as unknown }));

vi.mock("../auth/oidcAuthClient", () => ({
  getOidcAuthClient: () => adapter.client,
}));

/**
 * The exact envelopes the API module handed the hook, recorded as they pass.
 *
 * Not a stand-in for `fetchCaseList`: the real one runs, and its return value
 * is pushed here on its way through. That is the only way an identity
 * assertion is possible at all - `JSON.parse` builds a fresh object inside the
 * transport, so a test that re-declared the response body would be comparing
 * against something the hook never saw. With the real object in hand, "the
 * screen state is not the API envelope" becomes a fact about the two
 * references rather than about their shapes.
 */
const apiProbe = vi.hoisted(() => ({ envelopes: [] as unknown[] }));

vi.mock("./caseApi", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./caseApi")>();
  return {
    ...actual,
    fetchCaseList: async (...args: Parameters<typeof actual.fetchCaseList>) => {
      const result = await actual.fetchCaseList(...args);
      apiProbe.envelopes.push(result.data);
      return result;
    },
  };
});

/** The most recent envelope the API module produced, as the object it produced. */
function lastEnvelope(): Record<string, unknown> {
  const envelope = apiProbe.envelopes.at(-1);
  if (typeof envelope !== "object" || envelope === null) {
    throw new Error("The API module produced no envelope.");
  }
  return envelope as Record<string, unknown>;
}

/**
 * Every property name reachable from a value, however deeply nested.
 *
 * `JSON.stringify` alone would miss a non-enumerable or non-serializable
 * carrier, so the walk is explicit: this is what turns "the trace id is not
 * displayed" into "there is no property called `traceId` anywhere in what the
 * hook publishes".
 */
function reachablePropertyNames(value: unknown, seen = new Set<unknown>()): string[] {
  if (typeof value !== "object" || value === null || seen.has(value)) {
    return [];
  }
  seen.add(value);
  const names: string[] = [];
  for (const key of Reflect.ownKeys(value)) {
    if (typeof key !== "string") {
      continue;
    }
    names.push(key);
    names.push(...reachablePropertyNames((value as Record<string, unknown>)[key], seen));
  }
  return names;
}

const { useCaseList } = await import("./useCaseList");

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const OTHER_CASE_ID = "6d3e2f10-8b9c-4d0e-8f01-2b3c4d5e6f71";
const TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const ASSIGNEE_REF = "analyst_ref_demo_a7f2";
const TRACE_ID = "trace_demo_case_list_01";

const SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  displayName: "Local Analyst",
  roles: ["FDS_ANALYST"],
};

const SECOND_SESSION: AuthSession = {
  subject: "7a2f1c7d-4b3c-4d9e-8f01-2b3c4d5e6f70",
  displayName: "Second Analyst",
  roles: ["FDS_ANALYST"],
};

/**
 * The opening query, written out rather than imported from the screen.
 *
 * These three values are the contract, so the test states them independently:
 * a page component that changed its default sort would still agree with itself
 * and would fail here.
 */
const DEFAULT_QUERY: CaseListQuery = Object.freeze({
  page: 0,
  size: 20,
  sort: "lastChangedAt,desc",
});

const FILTERED_QUERY: CaseListQuery = Object.freeze({
  page: 0,
  size: 20,
  sort: "lastChangedAt,desc",
  caseStatus: "IN_REVIEW",
});

function listItem(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    caseId: CASE_ID,
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: ASSIGNEE_REF,
    relatedTransactionCount: 3,
    createdAt: "2026-07-23T01:15:30Z",
    lastChangedAt: "2026-07-24T02:20:40Z",
    ...overrides,
  };
}

function listBody(
  content: readonly Record<string, unknown>[] = [listItem()],
): Record<string, unknown> {
  return {
    content,
    page: {
      number: 0,
      size: 20,
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : 1,
      first: true,
      last: true,
    },
    traceId: TRACE_ID,
  };
}

interface PendingCall {
  readonly promise: Promise<Response>;
  readonly request: Request;
  readonly signal: AbortSignal;
  settle: (response: Response) => void;
  fail: (error: unknown) => void;
}

/**
 * A fetch double whose every call is settled by the test, in order, and which
 * deliberately ignores the abort signal.
 *
 * Ignoring it is the point: a cooperative fetch would make "a superseded
 * request answers late" untestable, and that is precisely the case the hook has
 * to survive. The signal is still recorded, so cancellation can be asserted
 * separately from what the answer does.
 */
function controlledFetch(): {
  readonly calls: PendingCall[];
  readonly spy: ReturnType<typeof vi.fn>;
} {
  const calls: PendingCall[] = [];
  const spy = vi.fn().mockImplementation((request: Request) => {
    let settle!: (response: Response) => void;
    let fail!: (error: unknown) => void;
    const promise = new Promise<Response>((resolve, reject) => {
      settle = resolve;
      fail = reject;
    });
    promise.catch(() => undefined);
    calls.push({ promise, request, signal: request.signal, settle, fail });
    return promise;
  });
  vi.stubGlobal("fetch", spy);
  return { calls, spy };
}

beforeEach(() => {
  apiProbe.envelopes.length = 0;
});

function providerWrapper(client: AuthClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(StrictMode, null, createElement(AuthProvider, { client, children }));
}

function render(client: AuthClient, query: CaseListQuery = DEFAULT_QUERY) {
  return renderHook((current: CaseListQuery) => useCaseList(current), {
    initialProps: query,
    wrapper: providerWrapper(client),
  });
}

/** Lets the provider's initialization and the hook's first effect run. */
async function settle(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

function signedIn(): FakeAuthClient {
  const client = createFakeAuthClient({ initialSession: SESSION });
  adapter.client = client;
  return client;
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  adapter.client = null;
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("useCaseList without a session", () => {
  it("makes no request at all and stays idle", async () => {
    const { spy } = controlledFetch();
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    const { result } = render(client);
    await settle();

    expect(result.current.state).toEqual({ status: "idle" });
    expect(spy).not.toHaveBeenCalled();
    // No credential is even looked up, so an unauthorized visit costs the
    // Authorization Server nothing either.
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("does not sign in, and offers a retry that does nothing", async () => {
    const { spy } = controlledFetch();
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    const { result } = render(client);
    await settle();
    act(() => {
      result.current.retry();
    });
    await settle();

    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.signIn).toHaveLength(0);
    expect(result.current.state).toEqual({ status: "idle" });
  });
});

describe("useCaseList request lifecycle", () => {
  it("issues exactly one request under StrictMode and publishes the page", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();

    expect(result.current.state).toEqual({ status: "loading", phase: "initial" });
    expect(spy).toHaveBeenCalledTimes(1);

    await act(async () => {
      calls[0].settle(jsonResponse(listBody()));
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    expect(spy).toHaveBeenCalledTimes(1);
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content).toHaveLength(1);
    expect(result.current.state.data.content[0].caseId).toBe(CASE_ID);
  });

  it("sends the query to the case endpoint as approved parameters", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    render(client, FILTERED_QUERY);
    await settle();

    const sent = spy.mock.calls[0][0] as Request;
    const url = new URL(sent.url);
    // The case endpoint, not the ledger one: a hook wired to the wrong typed
    // API would still load rows and would still pass every state assertion.
    expect(url.origin + url.pathname).toBe("http://localhost:8080/api/v1/cases");
    expect(url.searchParams.get("caseStatus")).toBe("IN_REVIEW");
    expect(url.searchParams.get("page")).toBe("0");
    expect(url.searchParams.get("size")).toBe("20");
    expect(url.searchParams.get("sort")).toBe("lastChangedAt,desc");
    expect(calls).toHaveLength(1);
  });

  it("sends every case filter the query contract declares", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();

    render(client, {
      caseStatus: "CLOSED",
      finalDisposition: "CONFIRMED_FRAUD",
      assigneeRef: ASSIGNEE_REF,
      createdAtFrom: "2026-07-01T00:00:00Z",
      createdAtTo: "2026-07-31T00:00:00Z",
      lastChangedAtFrom: "2026-08-01T00:00:00Z",
      lastChangedAtTo: "2026-08-31T00:00:00Z",
      transactionId: TRANSACTION_ID,
      page: 2,
      size: 50,
      sort: "lastChangedAt,asc",
    });
    await settle();

    const url = new URL((spy.mock.calls[0][0] as Request).url);
    expect(Object.fromEntries(url.searchParams)).toEqual({
      caseStatus: "CLOSED",
      finalDisposition: "CONFIRMED_FRAUD",
      assigneeRef: ASSIGNEE_REF,
      createdAtFrom: "2026-07-01T00:00:00Z",
      createdAtTo: "2026-07-31T00:00:00Z",
      lastChangedAtFrom: "2026-08-01T00:00:00Z",
      lastChangedAtTo: "2026-08-31T00:00:00Z",
      transactionId: TRANSACTION_ID,
      page: "2",
      size: "50",
      sort: "lastChangedAt,asc",
    });
  });

  it("makes no request while the query object is unchanged", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { rerender } = render(client);
    await settle();
    rerender(DEFAULT_QUERY);
    rerender(DEFAULT_QUERY);
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(1);
  });

  it("does not retry a failure on its own", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse({ code: "X" }, { status: 500 }));
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "unknown" });
    });
    await settle();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("re-sends exactly once when the user asks, and not again on its own", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].fail(new TypeError("connection refused"));
      await calls[0].promise.catch(() => undefined);
    });
    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "network" });
    });

    act(() => {
      result.current.retry();
    });
    await settle();

    expect(spy).toHaveBeenCalledTimes(2);
    expect(result.current.state).toEqual({ status: "loading", phase: "refresh" });

    // And a retry while that one is still in flight adds nothing: the state is
    // no longer an error, so the control is inert.
    act(() => {
      result.current.retry();
    });
    await settle();
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("ignores a retry from a success state", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse(listBody()));
      await calls[0].promise;
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    act(() => {
      result.current.retry();
    });
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(result.current.state.status).toBe("success");
  });

  it("polls nothing while it sits on a loaded page", async () => {
    vi.useFakeTimers();
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      calls[0].settle(jsonResponse(listBody()));
      await calls[0].promise;
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(120_000);
    });

    expect(result.current.state.status).toBe("success");
    expect(spy).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });
});

describe("useCaseList latest request wins", () => {
  it("shows a refresh phase while a new query is in flight", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse(listBody()));
      await calls[0].promise;
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    rerender(FILTERED_QUERY);
    expect(result.current.state).toEqual({ status: "loading", phase: "refresh" });
  });

  it("ignores a late success belonging to a superseded query", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    rerender(FILTERED_QUERY);
    await settle();
    expect(calls).toHaveLength(2);

    // The newest query answers first, then the abandoned one answers late -
    // through a fetch double that ignored the abort entirely, so the only thing
    // keeping its rows off the screen is the generation check.
    await act(async () => {
      calls[1].settle(jsonResponse(listBody([listItem()])));
      await calls[1].promise;
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    await act(async () => {
      calls[0].settle(jsonResponse(listBody([listItem({ caseId: OTHER_CASE_ID })])));
      await calls[0].promise;
      await Promise.resolve();
    });

    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content[0].caseId).toBe(CASE_ID);
  });

  it("ignores a late error belonging to a superseded query", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    rerender(FILTERED_QUERY);
    await settle();

    await act(async () => {
      calls[1].settle(jsonResponse(listBody()));
      await calls[1].promise;
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    await act(async () => {
      calls[0].fail(new TypeError("connection refused"));
      await calls[0].promise.catch(() => undefined);
      await Promise.resolve();
    });

    expect(result.current.state.status).toBe("success");
  });

  it("cancels the superseded request", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { rerender } = render(client);
    await settle();
    expect(calls[0].signal.aborted).toBe(false);

    rerender(FILTERED_QUERY);
    await settle();

    expect(calls[0].signal.aborted).toBe(true);
    expect(calls[1].signal.aborted).toBe(false);
  });

  it("cancels the outstanding request when the screen goes away", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { unmount } = render(client);
    await settle();

    unmount();
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(calls[0].signal.aborted).toBe(true);
  });

  it("publishes nothing after the screen has gone away", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, unmount } = render(client);
    await settle();
    const before = result.current.state;

    unmount();
    await act(async () => {
      // The answer arrives after unmount, through a fetch that ignored the
      // abort. React would warn on a state update here; nothing updates.
      calls[0].settle(jsonResponse(listBody()));
      await calls[0].promise;
      await Promise.resolve();
    });

    expect(result.current.state).toBe(before);
  });
});

describe("useCaseList 401 and 403", () => {
  it("clears data and drops the session on 401", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse({}, { status: 401 }));
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state.status).not.toBe("loading");
    });
    // The port invalidated the session that signed the request, so the provider
    // has already moved to unauthenticated and no page is left on screen.
    expect(result.current.state).toEqual({ status: "idle" });
    expect(client.calls.invalidateIfCurrent).toBe(1);
    expect(client.calls.notified).toBe(1);
  });

  it("does not retry or redirect after a 401", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse({}, { status: 401 }));
      await calls[0].promise;
    });
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(client.calls.signIn).toHaveLength(0);
  });

  it("does not let a stale 401 remove a newer session", async () => {
    const { calls } = controlledFetch();
    const client = createFakeAuthClient({
      initialSession: SESSION,
      completeSignInResult: { session: SECOND_SESSION, returnTo: "/" },
    });
    adapter.client = client;

    const { result } = render(client);
    await settle();

    // The session is replaced at the port while its request is still in flight.
    await act(async () => {
      await client.completeSignIn("https://app.example/auth/callback?code=x&state=y");
    });
    const notifiedBefore = client.calls.notified;

    await act(async () => {
      calls[0].settle(jsonResponse({}, { status: 401 }));
      await calls[0].promise;
      await Promise.resolve();
    });

    // The 401 belonged to a session that no longer owns anything, so the port
    // treated it as a no-op: no subscriber was told, and the newer session
    // stands.
    expect(client.calls.invalidateIfCurrent).toBe(1);
    expect(client.calls.notified).toBe(notifiedBefore);
    expect(result.current.state.status).not.toBe("idle");
  });

  it("keeps the session and shows a fixed refusal on 403", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse({}, { status: 403 }));
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "access-denied" });
    });
    // A 403 is about this endpoint, not about this session: nothing was
    // invalidated and no subscriber was told.
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });

  it("does not retry a 403 on its own", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse({}, { status: 403 }));
      await calls[0].promise;
    });
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
  });
});

describe("useCaseList error classification", () => {
  it("reports a network failure", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].fail(new TypeError("connection refused"));
      await calls[0].promise.catch(() => undefined);
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "network" });
    });
  });

  it("reports a timeout, separately from a network failure", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => new Promise<Response>(() => {})),
    );
    const client = signedIn();

    const { result } = render(client);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });

    expect(result.current.state).toEqual({ status: "error", error: "timeout" });
    vi.useRealTimers();
  });

  it("refuses a malformed page in full rather than showing part of it", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      // One bad item among two: a status outside `FraudCaseStatus`. The whole
      // response is refused rather than the good row being displayed.
      calls[0].settle(
        jsonResponse({
          content: [listItem(), listItem({ caseId: OTHER_CASE_ID, caseStatus: "ESCALATED" })],
          page: {
            number: 0,
            size: 20,
            totalElements: 2,
            totalPages: 1,
            first: true,
            last: true,
          },
          traceId: TRACE_ID,
        }),
      );
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
  });

  it("refuses a negative related-transaction count", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse(listBody([listItem({ relatedTransactionCount: -1 })])));
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
  });

  it("refuses a page whose metadata contradicts itself", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(
        jsonResponse({
          content: [listItem()],
          page: {
            number: 0,
            size: 20,
            totalElements: 500,
            totalPages: 25,
            first: true,
            last: true,
          },
          traceId: TRACE_ID,
        }),
      );
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
  });

  it("accepts an empty page as data rather than as an error", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse(listBody([])));
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content).toHaveLength(0);
    expect(result.current.state.data.page.totalElements).toBe(0);
  });

  it("accepts a case with no disposition and no assignee", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(
        jsonResponse(
          listBody([listItem({ finalDisposition: null, assigneeRef: null, caseStatus: "OPEN" })]),
        ),
      );
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content[0].finalDisposition).toBeNull();
    expect(result.current.state.data.content[0].assigneeRef).toBeNull();
  });

  it("refuses a query the contract does not allow, before sending anything", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();

    // `size` above Backend's 100 is refused by the query builder, so no URL is
    // ever assembled and no credential is looked up.
    const { result } = render(client, { page: 0, size: 500, sort: "lastChangedAt,desc" });
    await settle();

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "request-rejected" });
    });
    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("carries no response body, trace id or credential into the error value", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(
        jsonResponse(
          { code: "ACCESS_DENIED", message: "role FDS_ANALYST lacks case:read" },
          { status: 403, headers: { "X-Trace-Id": TRACE_ID } },
        ),
      );
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe("error");
    });
    const serialized = JSON.stringify(result.current.state);
    expect(serialized).not.toContain(TRACE_ID);
    expect(serialized).not.toContain("FDS_ANALYST");
    expect(serialized).not.toContain("ACCESS_DENIED");
    expect(serialized).not.toContain("case:read");
    expect(serialized).not.toContain("fake.access.token");
  });

  it("publishes nothing that can authorize a request", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse(listBody()));
      await calls[0].promise;
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    const published = result.current as unknown as Record<string, unknown>;
    expect(Object.keys(published).sort()).toEqual(["retry", "state"]);
    expect("authorizeRequest" in published).toBe(false);
    expect("client" in published).toBe(false);
    // Nothing on this object can sign a request, and there is nothing on it but
    // the two keys above. What the success value itself may carry is decided by
    // the projection tests below.
    expect(JSON.stringify(result.current.state)).not.toContain("fake.access.token");
    expect(JSON.stringify(result.current.state)).not.toMatch(/bearer|authorization/i);
  });
});

/**
 * What the hook is allowed to put into React state.
 *
 * The API contract and the screen contract are not the same contract. The
 * transport has to verify Backend's `traceId` against the response header
 * before it will accept a body at all, so `CaseListPage` carries it; a list
 * screen displays rows and page metadata and has no use for a correlation
 * identifier. This block is where the second contract is stated: the hook
 * projects the envelope into `content` and `page`, and everything else - the
 * trace id included - stops at that boundary.
 *
 * "Stops" is meant literally. These tests are not about what is rendered. They
 * are about what exists: no `traceId` key anywhere in the published state, and
 * no object from the parsed envelope left reachable from it, so a devtools
 * panel, a `JSON.stringify`, a serializing error reporter and a future
 * component that walks the state all arrive at the same nothing.
 *
 * The identical problem in `useTransactionList` is untouched here and is
 * tracked separately; nothing in this file asserts anything about it.
 */
describe("useCaseList published data", () => {
  async function successState() {
    const { calls } = controlledFetch();
    const client = signedIn();
    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse(listBody()));
      await calls[0].promise;
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    return result;
  }

  it("publishes exactly the two fields a case list screen displays", async () => {
    const result = await successState();
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }

    // Exact, not a superset: an added key would be a new thing the console
    // holds, and a removed one would be a column that cannot render.
    expect(Object.keys(result.current.state.data).sort()).toEqual(["content", "page"]);
    expect("traceId" in result.current.state.data).toBe(false);
  });

  it("carries no property called traceId anywhere in the published state", async () => {
    const result = await successState();

    // The whole state, walked rather than serialized: a non-enumerable or
    // unserializable carrier would survive `JSON.stringify` and is caught here.
    expect(reachablePropertyNames(result.current.state)).not.toContain("traceId");
    // And the serialized form carries neither the key nor the value, which is
    // the form a log line, an error reporter or a devtools copy would take.
    const serialized = JSON.stringify(result.current.state);
    expect(serialized).not.toContain("traceId");
    expect(serialized).not.toContain(TRACE_ID);
  });

  it("keeps no object from the API envelope reachable from React state", async () => {
    const result = await successState();
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const envelope = lastEnvelope();
    const published = result.current.state.data;

    // The envelope really is the thing the API module returned, and it really
    // does carry the trace id - otherwise the four assertions below would pass
    // against a value that never had one.
    expect(envelope.traceId).toBe(TRACE_ID);

    expect(published as unknown).not.toBe(envelope);
    expect(published.content as unknown).not.toBe(envelope.content);
    expect(published.page as unknown).not.toBe(envelope.page);
    const envelopeContent = envelope.content as readonly unknown[];
    expect(published.content[0] as unknown).not.toBe(envelopeContent[0]);
  });

  it("copies every case field without changing one of them", async () => {
    const result = await successState();
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const envelope = lastEnvelope();

    // A projection may not normalize, round, trim or default anything: the row
    // on screen has to be the row Backend sent, field for field.
    expect(result.current.state.data.content).toEqual(envelope.content);
    expect(result.current.state.data.page).toEqual(envelope.page);
  });

  it("carries no traceId in the idle, loading, error or retried states", async () => {
    const { calls } = controlledFetch();

    // idle: no session, so no request and nothing published.
    const idle = render(createFakeAuthClient());
    await settle();
    expect(idle.result.current.state).toEqual({ status: "idle" });
    expect(reachablePropertyNames(idle.result.current.state)).not.toContain("traceId");

    const client = signedIn();
    const { result } = render(client);
    await settle();

    // loading.
    expect(result.current.state.status).toBe("loading");
    expect(reachablePropertyNames(result.current.state)).not.toContain("traceId");

    // error, from a Backend answer whose body and header both carry a trace id.
    await act(async () => {
      calls[0].settle(
        jsonResponse(
          { code: "INTERNAL_ERROR", message: "boom", traceId: TRACE_ID },
          { status: 500, headers: { "X-Trace-Id": TRACE_ID } },
        ),
      );
      await calls[0].promise;
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("error");
    });
    expect(reachablePropertyNames(result.current.state)).not.toContain("traceId");
    expect(JSON.stringify(result.current.state)).not.toContain(TRACE_ID);

    // retry: the state it publishes on the way back to loading.
    act(() => {
      result.current.retry();
    });
    expect(result.current.state.status).toBe("loading");
    expect(reachablePropertyNames(result.current.state)).not.toContain("traceId");
    expect(reachablePropertyNames(result.current)).not.toContain("traceId");
  });
});

/**
 * A provider whose authentication state the test drives directly.
 *
 * `AuthProvider` only leaves `authenticated` through a real callback or a real
 * invalidation, so replacing one signed-in session with another - the case
 * where stale data would be most damaging - cannot be staged through it. This
 * supplies the same context shape with a state the test sets, which is what
 * makes "session A's cases never appear under session B" an assertion rather
 * than an argument.
 */
let setAuthState: ((next: AuthState) => void) | null = null;

const INERT_CLIENT: AuthClient = {
  initialize: () => Promise.resolve({ session: null }),
  signIn: () => Promise.resolve(),
  completeSignIn: () => Promise.reject(new Error("not used")),
  signOut: () => Promise.resolve(),
  onSessionInvalidated: () => () => undefined,
};

function ControlledAuth({ children }: { readonly children: ReactNode }) {
  const [state, setState] = useState<AuthState>({
    status: "authenticated",
    session: SESSION,
  });
  // Published from an effect rather than during render: handing the setter out
  // is a side effect, and doing it in the render body is exactly the impurity
  // the rest of this codebase is written to avoid.
  useEffect(() => {
    setAuthState = setState;
    return () => {
      setAuthState = null;
    };
  }, []);
  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      client: INERT_CLIENT,
      signIn: () => undefined,
      signOut: () => undefined,
      notifyCallbackStarted: () => undefined,
      notifyCallbackSucceeded: () => undefined,
      notifyCallbackFailed: () => undefined,
    }),
    [state],
  );
  return createElement(AuthContext.Provider, { value }, children);
}

function renderControlled(query: CaseListQuery = DEFAULT_QUERY) {
  return renderHook((current: CaseListQuery) => useCaseList(current), {
    initialProps: query,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(StrictMode, null, createElement(ControlledAuth, null, children)),
  });
}

describe("useCaseList session changes", () => {
  beforeEach(() => {
    adapter.client = createFakeAuthClient({ initialSession: SESSION });
    setAuthState = null;
  });

  async function loadOnePage(calls: PendingCall[], result: { current: { state: unknown } }) {
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse(listBody()));
      await calls[0].promise;
    });
    await waitFor(() => {
      expect((result.current.state as { status: string }).status).toBe("success");
    });
  }

  it("removes the page the moment the session becomes unauthenticated", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await loadOnePage(calls, result);

    act(() => {
      setAuthState?.({ status: "unauthenticated" });
    });

    expect(result.current.state).toEqual({ status: "idle" });
  });

  it("removes the page the moment sign-out starts", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await loadOnePage(calls, result);

    act(() => {
      setAuthState?.({ status: "signing-out" });
    });

    expect(result.current.state).toEqual({ status: "idle" });
  });

  it("does not show one session's cases under another session", async () => {
    const { calls, spy } = controlledFetch();
    const { result } = renderControlled();
    await loadOnePage(calls, result);

    act(() => {
      setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });

    // Cleared in the very render that saw the new session, before any effect.
    expect(result.current.state).toEqual({ status: "loading", phase: "initial" });
    await settle();
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("does not let the previous session's answer land on the new one", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await settle();

    act(() => {
      setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });
    await settle();
    expect(calls).toHaveLength(2);

    await act(async () => {
      calls[0].settle(jsonResponse(listBody([listItem({ caseId: OTHER_CASE_ID })])));
      await calls[0].promise;
      await Promise.resolve();
    });

    expect(result.current.state).toEqual({ status: "loading", phase: "initial" });
  });

  it("aborts the previous session's request when the session is replaced", async () => {
    const { calls } = controlledFetch();
    renderControlled();
    await settle();

    act(() => {
      setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });
    await settle();

    expect(calls[0].signal.aborted).toBe(true);
    expect(calls[1].signal.aborted).toBe(false);
  });
});
