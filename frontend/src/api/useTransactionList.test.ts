import { createElement, StrictMode, useEffect, useMemo, useState, type ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthClient, AuthSession } from "../auth/authClient";
import { AuthContext, type AuthContextValue } from "../auth/authContext";
import { AuthProvider } from "../auth/AuthProvider";
import type { AuthState } from "../auth/authState";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import type { TransactionListQuery } from "./transactionApi";

/**
 * The adapter the hook reaches for at its own credential boundary.
 *
 * The hook calls `getOidcAuthClient()` inside an effect and nowhere else, so
 * this is the one seam a test has to stand in at. Everything below it is
 * production code: the real `fetchTransactionList`, the real query builder, the
 * real URL re-verification, the real authenticated transport and the real
 * response validator all run here. Only `fetch` itself and the OIDC adapter are
 * doubles.
 */
const adapter = vi.hoisted(() => ({ client: null as unknown }));

vi.mock("../auth/oidcAuthClient", () => ({
  getOidcAuthClient: () => adapter.client,
}));

const { useTransactionList } = await import("./useTransactionList");

const TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const OTHER_TRANSACTION_ID = "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
const TRACE_ID = "trace_demo_tx_list_01";

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

const DEFAULT_QUERY: TransactionListQuery = Object.freeze({
  page: 0,
  size: 20,
  sort: "occurredAt,desc",
});

const FILTERED_QUERY: TransactionListQuery = Object.freeze({
  page: 0,
  size: 20,
  sort: "occurredAt,desc",
  processingStatus: "HELD",
});

function listItem(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    transactionId: TRANSACTION_ID,
    transactionType: "ACCOUNT_TRANSFER",
    amount: "1250000",
    currencyCode: "KRW",
    occurredAt: "2026-07-23T01:15:30Z",
    externalCustomerRef: "cust_ref_demo_a7f2",
    senderAccountRef: "acct_ref_demo_s91c",
    recipientAccountRef: "acct_ref_demo_r44d",
    processingStatus: "ADDITIONAL_AUTH_REQUIRED",
    createdAt: "2026-07-23T01:15:31Z",
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
    calls.push({ promise, signal: request.signal, settle, fail });
    return promise;
  });
  vi.stubGlobal("fetch", spy);
  return { calls, spy };
}

function providerWrapper(client: AuthClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(StrictMode, null, createElement(AuthProvider, { client, children }));
}

function render(client: AuthClient, query: TransactionListQuery = DEFAULT_QUERY) {
  return renderHook((current: TransactionListQuery) => useTransactionList(current), {
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

describe("useTransactionList without a session", () => {
  it("makes no request at all and stays idle", async () => {
    const { spy } = controlledFetch();
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    const { result } = render(client);
    await settle();

    expect(result.current.state).toEqual({ status: "idle" });
    expect(spy).not.toHaveBeenCalled();
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

describe("useTransactionList request lifecycle", () => {
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
    expect(result.current.state.data.content[0].transactionId).toBe(TRANSACTION_ID);
  });

  it("sends the committed query as approved parameters", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    render(client, FILTERED_QUERY);
    await settle();

    const sent = spy.mock.calls[0][0] as Request;
    const url = new URL(sent.url);
    expect(url.origin + url.pathname).toBe("http://localhost:8080/api/v1/transactions");
    expect(url.searchParams.get("processingStatus")).toBe("HELD");
    expect(url.searchParams.get("page")).toBe("0");
    expect(url.searchParams.get("size")).toBe("20");
    expect(url.searchParams.get("sort")).toBe("occurredAt,desc");
    expect(calls).toHaveLength(1);
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

  it("re-sends only when the user asks", async () => {
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
});

describe("useTransactionList latest request wins", () => {
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

    // The newest query answers first, then the abandoned one answers late.
    await act(async () => {
      calls[1].settle(jsonResponse(listBody([listItem()])));
      await calls[1].promise;
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    await act(async () => {
      calls[0].settle(
        jsonResponse(listBody([listItem({ transactionId: OTHER_TRANSACTION_ID })])),
      );
      await calls[0].promise;
      await Promise.resolve();
    });

    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content[0].transactionId).toBe(TRANSACTION_ID);
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
});

describe("useTransactionList 401 and 403", () => {
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
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });
});

describe("useTransactionList error classification", () => {
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
      // One bad item among two. The whole response is refused.
      calls[0].settle(
        jsonResponse({
          content: [listItem(), listItem({ amount: "-5" })],
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

  it("accepts a page whose recipient account is null", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(jsonResponse(listBody([listItem({ recipientAccountRef: null })])));
      await calls[0].promise;
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content[0].recipientAccountRef).toBeNull();
  });

  it("carries no response body, trace id or credential into the error value", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(
        jsonResponse(
          { code: "ACCESS_DENIED", message: "role FDS_ANALYST lacks transaction:read" },
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
  });
});

/**
 * A provider whose authentication state the test drives directly.
 *
 * `AuthProvider` only leaves `authenticated` through a real callback or a real
 * invalidation, so replacing one signed-in session with another - the case
 * where stale data would be most damaging - cannot be staged through it. This
 * supplies the same context shape with a state the test sets, which is what
 * makes "session A's rows never appear under session B" an assertion rather
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

function renderControlled(query: TransactionListQuery = DEFAULT_QUERY) {
  return renderHook((current: TransactionListQuery) => useTransactionList(current), {
    initialProps: query,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(StrictMode, null, createElement(ControlledAuth, null, children)),
  });
}

describe("useTransactionList session changes", () => {
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

  it("does not show one session's rows under another session", async () => {
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
      calls[0].settle(
        jsonResponse(listBody([listItem({ transactionId: OTHER_TRANSACTION_ID })])),
      );
      await calls[0].promise;
      await Promise.resolve();
    });

    expect(result.current.state).toEqual({ status: "loading", phase: "initial" });
  });
});
