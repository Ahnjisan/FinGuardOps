import { createElement, StrictMode, useEffect, useMemo, useState, type ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthClient, AuthSession } from "../auth/authClient";
import { AuthContext, type AuthContextValue } from "../auth/authContext";
import { AuthProvider } from "../auth/AuthProvider";
import type { AuthState } from "../auth/authState";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import type { TransactionDetailState } from "./useTransactionDetail";

/**
 * The adapter the hook reaches for at its own credential boundary.
 *
 * The hook calls `getOidcAuthClient()` inside an effect and nowhere else, so
 * this is the one seam a test has to stand in at. Everything below it is
 * production code: the real `fetchTransactionDetail`, the real endpoint
 * registry, the real URL re-verification, the real authenticated transport and
 * the real response validator all run here. Only `fetch` itself and the OIDC
 * adapter are doubles.
 */
const adapter = vi.hoisted(() => ({ client: null as unknown }));

vi.mock("../auth/oidcAuthClient", () => ({
  getOidcAuthClient: () => adapter.client,
}));

const { useTransactionDetail } = await import("./useTransactionDetail");

const TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const OTHER_TRANSACTION_ID = "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
const TRACE_ID = "trace_demo_tx_detail_01";
const ACCESS_TOKEN = "detail.access.token";

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

const CUSTOMER_REF = "cust_ref_demo_a7f2";

function detailBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    transaction: {
      transactionId: TRANSACTION_ID,
      transactionType: "ACCOUNT_TRANSFER",
      amount: "1250000",
      currencyCode: "KRW",
      occurredAt: "2026-07-23T01:15:30Z",
      externalCustomerRef: CUSTOMER_REF,
      senderAccountRef: "acct_ref_demo_s91c",
      recipientAccountRef: "acct_ref_demo_r44d",
      channel: "MOBILE_BANKING",
      deviceRef: "device_ref_demo_31aa",
      processingStatus: "ADDITIONAL_AUTH_REQUIRED",
      createdAt: "2026-07-23T01:15:31Z",
      updatedAt: "2026-07-23T01:16:02Z",
      ...overrides,
    },
    traceId: TRACE_ID,
  };
}

interface PendingCall {
  readonly promise: Promise<Response>;
  readonly request: Request;
  settle: (response: Response) => void;
  fail: (error: unknown) => void;
}

/**
 * A fetch double whose every call is settled by the test, in order, and which
 * deliberately ignores the abort signal.
 *
 * Ignoring it is the point: a cooperative fetch would make "a superseded
 * request answers late" untestable, and that is precisely the case the hook has
 * to survive. The request is still recorded, so cancellation and the exact
 * target can be asserted separately from what the answer does.
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
    calls.push({ promise, request, settle, fail });
    return promise;
  });
  vi.stubGlobal("fetch", spy);
  return { calls, spy };
}

function providerWrapper(client: AuthClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(StrictMode, null, createElement(AuthProvider, { client, children }));
}

function render(client: AuthClient, transactionId: string | null = TRANSACTION_ID) {
  return renderHook((current: string | null) => useTransactionDetail(current), {
    initialProps: transactionId,
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

async function answerWith(call: PendingCall, body: unknown, status = 200): Promise<void> {
  await act(async () => {
    call.settle(jsonResponse(body, { status }));
    await call.promise;
    await Promise.resolve();
  });
}

function signedIn(): FakeAuthClient {
  const client = createFakeAuthClient({ initialSession: SESSION, accessToken: ACCESS_TOKEN });
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

describe("useTransactionDetail without something to ask for", () => {
  it("makes no request at all without a session", async () => {
    const { spy } = controlledFetch();
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    const { result } = render(client);
    await settle();

    expect(result.current.state).toEqual({ status: "idle" });
    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
    expect(client.calls.signIn).toHaveLength(0);
  });

  it("makes no request when the route named no transaction", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client, null);
    await settle();

    expect(result.current.state).toEqual({ status: "idle" });
    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  const malformed: Array<[string, string]> = [
    ["an uppercase UUID", "2F4C0A4E-8A9D-4C2F-9A1B-7D6E5F430001"],
    ["a version 1 UUID", "2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001"],
    ["a version 5 UUID", "2f4c0a4e-8a9d-5c2f-9a1b-7d6e5f430001"],
    ["an invalid RFC variant", "2f4c0a4e-8a9d-4c2f-1a1b-7d6e5f430001"],
    ["a trailing space", "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001 "],
    ["a leading space", " 2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["a trailing slash", "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001/"],
    ["an encoded slash", "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001%2f"],
    ["an encoded backslash", "%5c2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["a percent-encoded first digit", "%32f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["a path of its own", "../../health"],
    ["an absolute URL", "https://evil.example/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["a truncated identifier", "2f4c0a4e"],
    ["an empty string", ""],
  ];

  it.each(malformed)(
    "refuses %s before a credential or a fetch exists",
    async (_label, candidate) => {
      const { spy } = controlledFetch();
      const client = signedIn();

      const { result } = render(client, candidate);
      await settle();

      expect(result.current.state).toEqual({ status: "idle" });
      expect(spy).not.toHaveBeenCalled();
      expect(client.calls.authorizeRequest).toBe(0);
    },
  );
});

describe("useTransactionDetail request lifecycle", () => {
  it("issues exactly one request under StrictMode and publishes the transaction", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();

    expect(result.current.state).toEqual({ status: "loading" });
    expect(spy).toHaveBeenCalledTimes(1);

    await answerWith(calls[0], detailBody());

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    expect(spy).toHaveBeenCalledTimes(1);
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.transactionId).toBe(TRANSACTION_ID);
    expect(result.current.state.data.channel).toBe("MOBILE_BANKING");
    expect(result.current.state.data.updatedAt).toBe("2026-07-23T01:16:02Z");
  });

  it("asks the detail endpoint for exactly that transaction, with no query", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    render(client);
    await settle();

    const url = new URL(calls[0].request.url);
    expect(url.origin).toBe("http://localhost:8080");
    expect(url.pathname).toBe(`/api/v1/transactions/${TRANSACTION_ID}`);
    expect(url.search).toBe("");
    expect(calls[0].request.method).toBe("GET");
    expect(calls).toHaveLength(1);
  });

  it("makes no further request while the transaction id is unchanged", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();

    const { rerender } = render(client);
    await settle();
    rerender(TRANSACTION_ID);
    rerender(TRANSACTION_ID);
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("does not retry, replay or poll a failure on its own", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], { code: "X" }, 500);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "unknown" });
    });
    await settle();
    await settle();
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("re-sends exactly once per explicit retry", async () => {
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
    expect(result.current.state).toEqual({ status: "loading" });

    await answerWith(calls[1], detailBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("ignores a retry from a success state", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody());
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

  it("ignores a retry while a request is still in flight", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();

    act(() => {
      result.current.retry();
    });
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
  });
});

describe("useTransactionDetail latest request wins", () => {
  it("removes the previous transaction the moment the route changes", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    await answerWith(calls[0], detailBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    // No effect has run yet: the previous record is already gone in the very
    // render that first carries the new identifier.
    rerender(OTHER_TRANSACTION_ID);
    expect(result.current.state).toEqual({ status: "loading" });
  });

  it("ignores a late success belonging to a superseded transaction", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    rerender(OTHER_TRANSACTION_ID);
    await settle();
    expect(calls).toHaveLength(2);

    await answerWith(calls[1], detailBody({ transactionId: OTHER_TRANSACTION_ID }));
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    await answerWith(calls[0], detailBody());

    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.transactionId).toBe(OTHER_TRANSACTION_ID);
  });

  it("ignores a late failure belonging to a superseded transaction", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    rerender(OTHER_TRANSACTION_ID);
    await settle();

    await answerWith(calls[1], detailBody({ transactionId: OTHER_TRANSACTION_ID }));
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
    expect(calls[0].request.signal.aborted).toBe(false);

    rerender(OTHER_TRANSACTION_ID);
    await settle();

    expect(calls[0].request.signal.aborted).toBe(true);
    expect(calls[1].request.signal.aborted).toBe(false);
  });

  it("publishes nothing after the screen has gone away", async () => {
    const errors = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, unmount } = render(client);
    await settle();
    const beforeUnmount = result.current.state;

    unmount();
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(calls[0].request.signal.aborted).toBe(true);

    await answerWith(calls[0], detailBody());

    expect(result.current.state).toBe(beforeUnmount);
    expect(errors).not.toHaveBeenCalled();
  });
});

describe("useTransactionDetail session boundaries", () => {
  it("clears the record and drops the session on a current-session 401", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {}, 401);

    await waitFor(() => {
      expect(result.current.state.status).not.toBe("loading");
    });
    // The port invalidated the session that signed the request, so the provider
    // has already moved to unauthenticated and no record is left on screen.
    expect(result.current.state).toEqual({ status: "idle" });
    expect(client.calls.invalidateIfCurrent).toBe(1);
    expect(client.calls.notified).toBe(1);
  });

  it("does not let a stale 401 tear down the session that replaced it", async () => {
    const { calls } = controlledFetch();
    const client = createFakeAuthClient({
      initialSession: SESSION,
      completeSignInResult: { session: SECOND_SESSION, returnTo: "/" },
      accessToken: ACCESS_TOKEN,
    });
    adapter.client = client;

    const { result } = render(client);
    await settle();

    // The session is replaced at the port while its request is still in flight.
    await act(async () => {
      await client.completeSignIn("https://app.example/auth/callback?code=x&state=y");
    });
    const notifiedBefore = client.calls.notified;

    await answerWith(calls[0], {}, 401);

    // The 401 belonged to a session that no longer owns anything, so the port
    // treated it as a no-op: no subscriber was told, and the newer session
    // stands.
    expect(client.calls.invalidateIfCurrent).toBe(1);
    expect(client.calls.notified).toBe(notifiedBefore);
    expect(result.current.state.status).not.toBe("idle");
  });

  it("removes the record immediately when the user signs out", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    await act(async () => {
      void client.signOut();
      await Promise.resolve();
    });

    expect(result.current.state).toEqual({ status: "idle" });
  });

  it("removes the record when the port invalidates the session", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    act(() => {
      client.emitSessionInvalidated();
    });

    expect(result.current.state).toEqual({ status: "idle" });
  });

  it("keeps the session on 403", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {}, 403);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "access-denied" });
    });
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });

  it("keeps the session on 404", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], { code: "TRANSACTION_NOT_FOUND" }, 404);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "not-found" });
    });
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });

  it("reports a session that has nothing to sign with, without sending anything", async () => {
    const { spy } = controlledFetch();
    // Signed in, but the port has no credential to authorize with. The refusal
    // is decided in the browser, so it costs zero fetches.
    const client = createFakeAuthClient({ initialSession: SESSION, accessToken: "" });
    adapter.client = client;

    const { result } = render(client);
    await settle();

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "session-lost" });
    });
    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(1);
  });
});

describe("useTransactionDetail error classification", () => {
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

  it("refuses a record carrying a field outside the contract", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {
      transaction: { ...(detailBody().transaction as object), riskScore: 91 },
      traceId: TRACE_ID,
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
  });

  it("refuses a record whose amount arrived as a number", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody({ amount: 1250000 }));

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
  });

  it("refuses a record whose channel is not a contract member", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody({ channel: "BRANCH_TELLER" }));

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
  });

  it("refuses a well-formed record belonging to a transaction it did not ask for", async () => {
    // 요청 A에 형식상 유효한 거래 B가 응답돼도 API 경계가 고정 invalid-response로 거부한다 (Issue #287).
    // render마다 공개 state를 기록해 success·data가 한 frame도 게시되지 않았음을 확인한다.
    const { calls, spy } = controlledFetch();
    const client = signedIn();
    const rendered: TransactionDetailState[] = [];
    const mismatched = detailBody({ transactionId: OTHER_TRANSACTION_ID });

    const { result } = renderHook(
      (current: string | null) => {
        const value = useTransactionDetail(current);
        rendered.push(value.state);
        return value;
      },
      { initialProps: TRANSACTION_ID, wrapper: providerWrapper(client) },
    );
    await settle();
    expect(calls).toHaveLength(1);

    await act(async () => {
      calls[0].settle(jsonResponse(mismatched, { headers: { "X-Trace-Id": TRACE_ID } }));
      await calls[0].promise;
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
    expect(JSON.stringify(result.current.state)).toBe(
      '{"status":"error","error":"invalid-response"}',
    );
    expect(rendered.filter((state) => state.status === "success")).toHaveLength(0);
    expect(rendered.some((state) => "data" in state)).toBe(false);
    const everPublished = JSON.stringify(rendered);
    for (const secret of [
      TRANSACTION_ID,
      OTHER_TRANSACTION_ID,
      TRACE_ID,
      CUSTOMER_REF,
      "traceId",
      JSON.stringify(mismatched),
    ]) {
      expect(everPublished).not.toContain(secret);
    }
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);

    // 자동 retry·polling이 없으므로 settle 이후에도 fetch는 정확히 1회다.
    await settle();
    await settle();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(1);
    expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
  });

  it("reports an unmapped Backend status as the generic failure", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], { code: "SERVICE_UNAVAILABLE" }, 503);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "unknown" });
    });
  });
});

describe("useTransactionDetail disclosure boundary", () => {
  it("returns only a state and a retry", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    expect(Object.keys(result.current).sort()).toEqual(["retry", "state"]);
    expect(typeof result.current.retry).toBe("function");
  });

  it("publishes the transaction alone, with no trace id and no envelope", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(Object.keys(result.current.state.data).sort()).toEqual([
      "amount",
      "channel",
      "createdAt",
      "currencyCode",
      "deviceRef",
      "externalCustomerRef",
      "occurredAt",
      "processingStatus",
      "recipientAccountRef",
      "senderAccountRef",
      "transactionId",
      "transactionType",
      "updatedAt",
    ]);
    const published = JSON.stringify(result.current.state);
    expect(published).not.toContain(TRACE_ID);
    expect(published).not.toContain("traceId");
    expect(published).not.toContain(ACCESS_TOKEN);
    expect(published).not.toContain("transaction\":{\"transaction");
  });

  it("carries nothing of a failed response into the published state", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(
        jsonResponse(
          { code: "ACCESS_DENIED", message: "authority transaction:read is required" },
          { status: 403, headers: { "X-Trace-Id": TRACE_ID } },
        ),
      );
      await calls[0].promise;
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe("error");
    });
    const published = JSON.stringify(result.current.state);
    expect(published).toBe('{"status":"error","error":"access-denied"}');
    expect(published).not.toContain(TRACE_ID);
    expect(published).not.toContain("transaction:read");
    expect(published).not.toContain(CUSTOMER_REF);
  });
});

/**
 * A provider whose authentication state the test drives directly.
 *
 * `AuthProvider` only leaves `authenticated` through a real callback or a real
 * invalidation, so replacing one signed-in session with another - the case
 * where a stale record would be most damaging - cannot be staged through it.
 * This supplies the same context shape with a state the test sets, which is
 * what makes "session A's transaction never appears under session B" an
 * assertion rather than an argument.
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

function renderControlled(transactionId: string | null = TRANSACTION_ID) {
  return renderHook((current: string | null) => useTransactionDetail(current), {
    initialProps: transactionId,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(StrictMode, null, createElement(ControlledAuth, null, children)),
  });
}

describe("useTransactionDetail session replacement", () => {
  beforeEach(() => {
    adapter.client = createFakeAuthClient({
      initialSession: SESSION,
      accessToken: ACCESS_TOKEN,
    });
    setAuthState = null;
  });

  async function loadOneRecord(
    calls: PendingCall[],
    result: { current: { state: { status: string } } },
  ): Promise<void> {
    await settle();
    await answerWith(calls[0], detailBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
  }

  it("removes the record the moment the session becomes unauthenticated", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await loadOneRecord(calls, result);

    act(() => {
      setAuthState?.({ status: "unauthenticated" });
    });

    expect(result.current.state).toEqual({ status: "idle" });
  });

  it("removes the record the moment sign-out starts", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await loadOneRecord(calls, result);

    act(() => {
      setAuthState?.({ status: "signing-out" });
    });

    expect(result.current.state).toEqual({ status: "idle" });
  });

  it("does not show one session's transaction under another session", async () => {
    const { calls, spy } = controlledFetch();
    const { result } = renderControlled();
    await loadOneRecord(calls, result);

    act(() => {
      setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });

    // Cleared in the very render that saw the new session, before any effect.
    expect(result.current.state).toEqual({ status: "loading" });
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

    await answerWith(calls[0], detailBody({ externalCustomerRef: "cust_ref_from_session_a" }));

    expect(result.current.state).toEqual({ status: "loading" });
  });

  it("keeps the new session's record when the previous session answers 401", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await settle();

    act(() => {
      setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });
    await settle();

    // The new session's own request answers with the record.
    await answerWith(calls[1], detailBody({ externalCustomerRef: "cust_ref_from_session_b" }));
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    // Only now does the abandoned session's request answer 401. It belongs to a
    // request nobody is waiting for, so it removes neither the session nor the
    // record on screen.
    await answerWith(calls[0], {}, 401);

    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.externalCustomerRef).toBe("cust_ref_from_session_b");
  });

  it("publishes a lost session rather than a record when the Backend answers 401", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await settle();

    await answerWith(calls[0], {}, 401);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "session-lost" });
    });
  });
});

type MutableRecord = Record<string, unknown>;

/** 테스트가 보관하고 사후에 직접 변경하는 wire detail body. */
interface HeldDetailBody extends MutableRecord {
  transaction: MutableRecord;
  traceId: string;
}

const SORTED_DETAIL_KEYS = [
  "amount",
  "channel",
  "createdAt",
  "currencyCode",
  "deviceRef",
  "externalCustomerRef",
  "occurredAt",
  "processingStatus",
  "recipientAccountRef",
  "senderAccountRef",
  "transactionId",
  "transactionType",
  "updatedAt",
];

/** 매번 새로 만드는 raw wire detail body. */
function heldDetailBody(): HeldDetailBody {
  return {
    transaction: {
      transactionId: TRANSACTION_ID,
      transactionType: "ACCOUNT_TRANSFER",
      amount: "1250000",
      currencyCode: "KRW",
      occurredAt: "2026-07-23T01:15:30Z",
      externalCustomerRef: CUSTOMER_REF,
      senderAccountRef: "acct_ref_demo_s91c",
      recipientAccountRef: "acct_ref_demo_r44d",
      channel: "MOBILE_BANKING",
      deviceRef: "device_ref_demo_31aa",
      processingStatus: "ADDITIONAL_AUTH_REQUIRED",
      createdAt: "2026-07-23T01:15:31Z",
      updatedAt: "2026-07-23T01:16:02Z",
    },
    traceId: TRACE_ID,
  };
}

/** `heldDetailBody()`가 게시돼야 할 13개 필드. raw에서 파생하지 않고 literal로 고정한다. */
function expectedTransaction(): MutableRecord {
  return {
    transactionId: TRANSACTION_ID,
    transactionType: "ACCOUNT_TRANSFER",
    amount: "1250000",
    currencyCode: "KRW",
    occurredAt: "2026-07-23T01:15:30Z",
    externalCustomerRef: CUSTOMER_REF,
    senderAccountRef: "acct_ref_demo_s91c",
    recipientAccountRef: "acct_ref_demo_r44d",
    channel: "MOBILE_BANKING",
    deviceRef: "device_ref_demo_31aa",
    processingStatus: "ADDITIONAL_AUTH_REQUIRED",
    createdAt: "2026-07-23T01:15:31Z",
    updatedAt: "2026-07-23T01:16:02Z",
  };
}

/**
 * 테스트가 보관한 raw 객체 참조를 그대로 돌려주는 200 응답 double.
 *
 * `jsonResponse()`는 body를 문자열로 직렬화하므로 transport의 `response.json()`이 매번 새 객체를
 * 만들어 identity 비교가 불가능하다. 이 double의 `json()`은 재파싱 없이 전달받은 참조를 반환하므로,
 * 실제 transport·validator를 통과한 뒤 hook이 게시한 객체가 raw transaction과 같은 객체인지 직접
 * 관찰할 수 있다. body와 같은 `X-Trace-Id` header를 실어 기존 trace 대조 검증도 그대로 거친다.
 */
function heldJsonResponse(raw: unknown): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ "Content-Type": "application/json", "X-Trace-Id": TRACE_ID }),
    json: () => Promise.resolve(raw),
  } as unknown as Response;
}

async function answerWithHeld(call: PendingCall, raw: unknown): Promise<void> {
  await act(async () => {
    call.settle(heldJsonResponse(raw));
    await call.promise;
    await Promise.resolve();
  });
}

/**
 * Hook이 React state에 게시하는 transaction 객체의 소유권 (Issue #285).
 *
 * hook은 wire envelope 대신 transaction만 게시하되, 검증된 13개 필드를 복사한 새 plain object여야
 * 한다. raw transaction이나 이전 delivery의 state를 사후에 바꿔도 이미 게시된 state와 후속
 * delivery에 전파되지 않아야 한다. 모든 성공 응답은 `heldJsonResponse()`로 전달해 JSON 재파싱에
 * 의한 우연한 분리를 배제한다.
 */
describe("useTransactionDetail published object graph", () => {
  beforeEach(() => {
    adapter.client = createFakeAuthClient({
      initialSession: SESSION,
      accessToken: ACCESS_TOKEN,
    });
    setAuthState = null;
  });

  it("publishes a fresh 13-field transaction rather than the raw transaction object", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();
    const raw = heldDetailBody();

    const { result } = render(client);
    await settle();
    await answerWithHeld(calls[0], raw);
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const published = result.current.state.data;

    expect(published as unknown).not.toBe(raw.transaction);
    expect(published as unknown).not.toBe(raw);
    expect(Object.getPrototypeOf(published)).toBe(Object.prototype);
    expect(Object.keys(published).sort()).toEqual(SORTED_DETAIL_KEYS);
    expect(published).toStrictEqual(expectedTransaction());
  });

  it("keeps the published transaction unchanged when the raw transaction is mutated afterwards", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();
    const raw = heldDetailBody();

    const { result } = render(client);
    await settle();
    await answerWithHeld(calls[0], raw);
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    // primitive 필드 변경.
    raw.transaction.amount = "1";
    raw.transaction.processingStatus = "HELD";
    raw.transaction.updatedAt = "2026-07-24T00:00:00Z";
    // nullable 필드 변경.
    raw.transaction.recipientAccountRef = null;
    raw.transaction.deviceRef = null;
    // 사후 unknown field 추가.
    raw.transaction.riskScore = 91;

    expect(result.current.state).toStrictEqual({ status: "success", data: expectedTransaction() });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect("riskScore" in result.current.state.data).toBe(false);
  });

  it("gives the same raw transaction, delivered again after a session replacement, its own object", async () => {
    const { calls } = controlledFetch();
    const raw = heldDetailBody();

    const { result } = renderControlled();
    await settle();
    await answerWithHeld(calls[0], raw);
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const first = result.current.state.data;

    // 이전 state를 계약 안의 값으로 바꾼다. raw나 새 delivery에 닿으면 안 된다.
    const mutableFirst = first as unknown as MutableRecord;
    mutableFirst.amount = "777";
    mutableFirst.deviceRef = null;

    // session을 교체하고, 새 session의 요청에 같은 raw 객체를 다시 전달한다.
    act(() => {
      setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });
    await settle();
    expect(calls).toHaveLength(2);
    await answerWithHeld(calls[1], raw);
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const second = result.current.state.data;

    expect(second as unknown).not.toBe(first);
    expect(second as unknown).not.toBe(raw.transaction);
    expect(second).toStrictEqual(expectedTransaction());

    // 새 delivery 뒤 이전 state와 raw를 다시 바꿔도 새 state는 그대로다.
    mutableFirst.processingStatus = "HELD";
    mutableFirst.riskScore = 91;
    raw.transaction.amount = "2";
    raw.transaction.recipientAccountRef = null;
    raw.transaction.channel = "ATM";
    raw.transaction.unknownAfterDelivery = true;
    expect(result.current.state).toStrictEqual({ status: "success", data: expectedTransaction() });
  });
});
