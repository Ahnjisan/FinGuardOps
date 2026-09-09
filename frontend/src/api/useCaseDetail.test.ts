import { createElement, StrictMode, useEffect, useMemo, useState, type ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthClient, AuthSession } from "../auth/authClient";
import { AuthContext, type AuthContextValue } from "../auth/authContext";
import { AuthProvider } from "../auth/AuthProvider";
import type { AuthState } from "../auth/authState";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";

/**
 * The adapter the hook reaches for at its own credential boundary.
 *
 * The hook calls `getOidcAuthClient()` inside an effect and nowhere else, so
 * this is the one seam a test has to stand in at. Everything below it is
 * production code: the real `fetchCaseDetail`, the real endpoint registry, the
 * real URL re-verification, the real authenticated transport and the real
 * response validator all run here. Only `fetch` itself and the OIDC adapter
 * are doubles.
 */
const adapter = vi.hoisted(() => ({ client: null as unknown }));

vi.mock("../auth/oidcAuthClient", () => ({
  getOidcAuthClient: () => adapter.client,
}));

/** Every value the hook's own snapshot publisher was handed, in order. */
const publisher = vi.hoisted(() => ({ writes: [] as unknown[] }));

/**
 * A tap on React's `useState`, so "did the cleaned-up subscription publish?" is
 * observed at the setter rather than inferred from the screen.
 *
 * It has to be observed here. A publish from a subscription React has already
 * torn down lands on a fiber React will not re-render, so it leaves no state,
 * no render and no DOM behind - the update is dropped in silence, which is
 * exactly why the defect this file regresses against could sit in a green
 * suite. The seam is the setter React hands out, and the wrapper is stable
 * across renders because the setter it wraps is, so nothing downstream sees a
 * changing identity.
 *
 * Nothing about the hook changes for this: there is no test-only export, no
 * injected publisher and no flag. Only writes shaped like this hook's own
 * `Snapshot` are recorded, so the auth provider's state - the other `useState`
 * in the tree - is not mistaken for one.
 */
vi.mock("react", async (importOriginal) => {
  const actual = await importOriginal<typeof import("react")>();
  const useStateTap = (initial?: unknown): [unknown, (next: unknown) => void] => {
    const [value, set] = actual.useState(initial as never);
    const held = actual.useRef<{
      readonly raw: unknown;
      readonly wrapped: (next: unknown) => void;
    } | null>(null);
    if (held.current === null || held.current.raw !== set) {
      held.current = {
        raw: set,
        wrapped: (next: unknown): void => {
          if (
            typeof next === "object" &&
            next !== null &&
            "session" in next &&
            "caseId" in next &&
            "state" in next
          ) {
            publisher.writes.push(next);
          }
          (set as (value: unknown) => void)(next);
        },
      };
    }
    return [value, held.current.wrapped];
  };
  return { ...actual, useState: useStateTap };
});

const { useCaseDetail } = await import("./useCaseDetail");

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const OTHER_CASE_ID = "6d3e2f10-8b9c-4d0e-8f01-2b3c4d5e6f71";
const TRACE_ID = "trace_demo_case_detail_01";
const ACCESS_TOKEN = "case.detail.access.token";
const ASSIGNEE_REF = "analyst_ref_demo_a7f2";

/** The ten contract fields, in the order the hook's projection writes them. */
const PROJECTION_ORDER: readonly string[] = [
  "caseId",
  "caseStatus",
  "finalDisposition",
  "assigneeRef",
  "relatedTransactionCount",
  "createdAt",
  "reviewStartedAt",
  "closedAt",
  "lastChangedAt",
  "concurrencyVersion",
];

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

function caseFields(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    caseId: CASE_ID,
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: ASSIGNEE_REF,
    relatedTransactionCount: 3,
    createdAt: "2026-07-24T01:15:33Z",
    reviewStartedAt: "2026-07-24T01:25:00Z",
    closedAt: null,
    lastChangedAt: "2026-07-24T02:05:10Z",
    concurrencyVersion: 4,
    ...overrides,
  };
}

function detailBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { case: caseFields(overrides), traceId: TRACE_ID };
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

/**
 * One in-flight request whose *stages* the test settles separately.
 *
 * `controlledFetch` above hands over a whole `Response` at once, which is the
 * right shape for "what does this answer mean". It is the wrong shape for
 * "when, exactly, does this answer reach the hook": a real `Response` reads its
 * body through a stream, and that read does not complete on the microtask queue
 * at all, so no ordering can be fixed around it without waiting on real time.
 *
 * This double replaces the body read with a promise the test settles, so
 * everything between `respond()` and the hook's own callback is a fixed number
 * of microtask turns and nothing else. The abort signal is still ignored, for
 * the same reason it is ignored above.
 */
interface StagedCall {
  readonly request: Request;
  /** Hands over the response line and headers. A non-2xx never reads a body. */
  respond: (status: number) => void;
  /** Hands over the parsed body of a response that has one. */
  resolveJson: (value: unknown) => void;
  /** Fails the body read, without failing the request. */
  rejectJson: (error: unknown) => void;
  /** Fails the request itself, as a transport error would. */
  failFetch: (error: unknown) => void;
}

function stagedFetch(): {
  readonly calls: StagedCall[];
  readonly spy: ReturnType<typeof vi.fn>;
} {
  const calls: StagedCall[] = [];
  const spy = vi.fn().mockImplementation((request: Request) => {
    let respond!: (status: number) => void;
    let failFetch!: (error: unknown) => void;
    let resolveJson!: (value: unknown) => void;
    let rejectJson!: (error: unknown) => void;
    const json = new Promise<unknown>((resolve, reject) => {
      resolveJson = resolve;
      rejectJson = reject;
    });
    json.catch(() => undefined);
    const response = new Promise<Response>((resolve, reject) => {
      failFetch = reject;
      respond = (status: number) => {
        resolve({
          ok: status >= 200 && status < 300,
          status,
          headers: new Headers({ "Content-Type": "application/json" }),
          json: () => json,
        } as unknown as Response);
      };
    });
    response.catch(() => undefined);
    calls.push({ request, respond, resolveJson, rejectJson, failFetch });
    return response;
  });
  vi.stubGlobal("fetch", spy);
  return { calls, spy };
}

/**
 * Advances the microtask queue a fixed number of turns, and does nothing else.
 *
 * No timers, no real waiting and no polling: every ordering asserted in the
 * lifecycle block below is decided by where in this queue a callback was
 * placed, so a test that slept or polled would be asserting something other
 * than the thing that went wrong.
 */
async function flushMicrotasks(turns: number): Promise<void> {
  for (let turn = 0; turn < turns; turn += 1) {
    await Promise.resolve();
  }
}

function providerWrapper(client: AuthClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(StrictMode, null, createElement(AuthProvider, { client, children }));
}

function render(client: AuthClient, caseId: string | null = CASE_ID) {
  return renderHook((current: string | null) => useCaseDetail(current), {
    initialProps: caseId,
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
  publisher.writes.length = 0;
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("useCaseDetail without something to ask for", () => {
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

  it("makes no request when the route named no case", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client, null);
    await settle();

    expect(result.current.state).toEqual({ status: "idle" });
    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  const malformed: Array<[string, string]> = [
    ["an uppercase UUID", "5C2D1E0F-7A8B-4C9D-9E0F-1A2B3C4D5E60"],
    ["a version 1 UUID", "5c2d1e0f-7a8b-1c9d-9e0f-1a2b3c4d5e60"],
    ["a version 5 UUID", "5c2d1e0f-7a8b-5c9d-9e0f-1a2b3c4d5e60"],
    ["an invalid RFC variant", "5c2d1e0f-7a8b-4c9d-1e0f-1a2b3c4d5e60"],
    ["a trailing space", "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60 "],
    ["a leading space", " 5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60"],
    ["a trailing slash", "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60/"],
    ["an encoded slash", "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60%2f"],
    ["an encoded backslash", "%5c5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60"],
    ["a percent-encoded first digit", "%35c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60"],
    ["an unhyphenated identifier", "5c2d1e0f7a8b4c9d9e0f1a2b3c4d5e60"],
    ["a path of its own", "../../health"],
    ["an absolute URL", "https://evil.example/5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60"],
    ["a truncated identifier", "5c2d1e0f"],
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

describe("useCaseDetail request lifecycle", () => {
  it("issues exactly one request under StrictMode and publishes the case", async () => {
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
    expect(result.current.state.data.caseId).toBe(CASE_ID);
    expect(result.current.state.data.caseStatus).toBe("IN_REVIEW");
    expect(result.current.state.data.concurrencyVersion).toBe(4);
  });

  it("asks the case detail endpoint for exactly that case, with no query", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    render(client);
    await settle();

    const url = new URL(calls[0].request.url);
    expect(url.origin).toBe("http://localhost:8080");
    expect(url.pathname).toBe(`/api/v1/cases/${CASE_ID}`);
    expect(url.search).toBe("");
    expect(calls[0].request.method).toBe("GET");
    expect(calls).toHaveLength(1);
  });

  it("makes no further request while the case id is unchanged", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();

    const { rerender } = render(client);
    await settle();
    rerender(CASE_ID);
    rerender(CASE_ID);
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

  it.each([
    ["a case that does not exist", 404, "not-found"],
    ["a case this session may not read", 403, "forbidden"],
  ])("offers no retry for %s", async (_label, status, expected) => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {}, status);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: expected });
    });

    // A settled answer, not a failure to be tried again: `retry()` is a no-op
    // even when it is called directly.
    act(() => {
      result.current.retry();
    });
    await settle();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(result.current.state).toEqual({ status: expected });
  });
});

describe("useCaseDetail latest request wins", () => {
  it("removes the previous case the moment the route changes", async () => {
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
    rerender(OTHER_CASE_ID);
    expect(result.current.state).toEqual({ status: "loading" });
  });

  it("ignores a late success belonging to a superseded case", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    rerender(OTHER_CASE_ID);
    await settle();
    expect(calls).toHaveLength(2);

    await answerWith(calls[1], detailBody({ caseId: OTHER_CASE_ID }));
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    await answerWith(calls[0], detailBody());

    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.caseId).toBe(OTHER_CASE_ID);
  });

  it("ignores a late failure belonging to a superseded case", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    rerender(OTHER_CASE_ID);
    await settle();

    await answerWith(calls[1], detailBody({ caseId: OTHER_CASE_ID }));
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

  it("ignores a late 404 belonging to a superseded case", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    rerender(OTHER_CASE_ID);
    await settle();

    await answerWith(calls[1], detailBody({ caseId: OTHER_CASE_ID }));
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    // The abandoned case's answer arrives last and says the case is gone. It
    // belongs to a request nobody is waiting for, so the record on screen -
    // which is a different case entirely - stands.
    await answerWith(calls[0], {}, 404);

    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.caseId).toBe(OTHER_CASE_ID);
  });

  it("cancels the superseded request", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { rerender } = render(client);
    await settle();
    expect(calls[0].request.signal.aborted).toBe(false);

    rerender(OTHER_CASE_ID);
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

describe("useCaseDetail session boundaries", () => {
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
    await answerWith(calls[0], { code: "ACCESS_DENIED" }, 403);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "forbidden" });
    });
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });

  it("keeps the session on 404", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], { code: "CASE_NOT_FOUND" }, 404);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "not-found" });
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

describe("useCaseDetail error classification", () => {
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
      case: { ...caseFields(), riskScore: 91 },
      traceId: TRACE_ID,
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
  });

  it("refuses a record carrying an updatedAt this contract does not have", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {
      case: { ...caseFields(), updatedAt: "2026-07-24T02:05:10Z" },
      traceId: TRACE_ID,
    });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
  });

  it("refuses a record whose case status is not a contract member", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody({ caseStatus: "ESCALATED" }));

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
  });

  it("refuses a record whose concurrency version is not an exact integer", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody({ concurrencyVersion: 4.5 }));

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "error", error: "invalid-response" });
    });
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

describe("useCaseDetail disclosure boundary", () => {
  it("returns only a state and a retry", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], detailBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    expect(Object.keys(result.current).sort()).toEqual([
      "reconciliationGeneration",
      "refresh",
      "refreshState",
      "retry",
      "state",
    ]);
    expect(typeof result.current.retry).toBe("function");
  });

  it("publishes the ten contract fields alone, with no trace id and no envelope", async () => {
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
    expect(Object.keys(result.current.state.data).sort()).toEqual([...PROJECTION_ORDER].sort());
    // The published state carries exactly two keys: no envelope hides beside
    // the record.
    expect(Object.keys(result.current.state).sort()).toEqual(["data", "status"]);
    const published = JSON.stringify(result.current.state);
    expect(published).not.toContain(TRACE_ID);
    expect(published).not.toContain("traceId");
    expect(published).not.toContain(ACCESS_TOKEN);
    expect(published).not.toContain('"case"');
  });

  it("publishes a fresh object rather than the parsed envelope's own", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    // The Backend's key order, reversed. `JSON.parse` preserves it, so a hook
    // that published the parsed object would publish this order; the hook
    // writes its ten fields itself, so what comes back is the projection's
    // order instead. That difference is the identity separation, observed
    // rather than asserted.
    const reversed: Record<string, unknown> = {};
    const fields = caseFields();
    for (const key of [...PROJECTION_ORDER].reverse()) {
      reversed[key] = fields[key];
    }
    await answerWith(calls[0], { case: reversed, traceId: TRACE_ID });

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(Object.keys(reversed)).toEqual([...PROJECTION_ORDER].reverse());
    expect(Object.keys(result.current.state.data)).toEqual(PROJECTION_ORDER);
  });

  it("keeps every nullable field exactly as the Backend sent it", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(
      calls[0],
      detailBody({
        finalDisposition: null,
        assigneeRef: null,
        reviewStartedAt: null,
        closedAt: null,
      }),
    );

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    // `null` stays `null`: nothing here defaults one into a string, a zero or
    // an absent key.
    expect(result.current.state.data.finalDisposition).toBeNull();
    expect(result.current.state.data.assigneeRef).toBeNull();
    expect(result.current.state.data.reviewStartedAt).toBeNull();
    expect(result.current.state.data.closedAt).toBeNull();
    expect(Object.keys(result.current.state.data)).toEqual(PROJECTION_ORDER);
  });

  it("carries nothing of a failed response into the published state", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(
        jsonResponse(
          { code: "ACCESS_DENIED", message: "authority case:read is required" },
          { status: 403, headers: { "X-Trace-Id": TRACE_ID } },
        ),
      );
      await calls[0].promise;
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe("forbidden");
    });
    const published = JSON.stringify(result.current.state);
    expect(published).toBe('{"status":"forbidden"}');
    expect(published).not.toContain(TRACE_ID);
    expect(published).not.toContain("case:read");
    expect(published).not.toContain(ASSIGNEE_REF);
  });

  it("carries nothing of a not-found response into the published state", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await act(async () => {
      calls[0].settle(
        jsonResponse(
          { code: "CASE_NOT_FOUND", message: "no such case", traceId: TRACE_ID },
          { status: 404, headers: { "X-Trace-Id": TRACE_ID } },
        ),
      );
      await calls[0].promise;
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe("not-found");
    });
    expect(JSON.stringify(result.current.state)).toBe('{"status":"not-found"}');
  });
});

/**
 * A provider whose authentication state the test drives directly.
 *
 * `AuthProvider` only leaves `authenticated` through a real callback or a real
 * invalidation, so replacing one signed-in session with another - the case
 * where a stale record would be most damaging - cannot be staged through it.
 * This supplies the same context shape with a state the test sets, which is
 * what makes "session A's case never appears under session B" an assertion
 * rather than an argument.
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

function renderControlled(caseId: string | null = CASE_ID) {
  return renderHook((current: string | null) => useCaseDetail(current), {
    initialProps: caseId,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(StrictMode, null, createElement(ControlledAuth, null, children)),
  });
}

describe("useCaseDetail session replacement", () => {
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

  it("does not show one session's case under another session", async () => {
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

    await answerWith(calls[0], detailBody({ assigneeRef: "analyst_ref_from_session_a" }));

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
    await answerWith(calls[1], detailBody({ assigneeRef: "analyst_ref_from_session_b" }));
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
    expect(result.current.state.data.assigneeRef).toBe("analyst_ref_from_session_b");
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

/**
 * The window between a subscription ending and its request being released.
 *
 * A flight deliberately outlives the effect that started it, so React's cleanup
 * cannot tear the request down on the spot: it drops the reference count and
 * leaves the decision to a microtask, which is the whole of how StrictMode's
 * setup-cleanup-setup replay costs one network call instead of two. That leaves
 * a gap. An answer that had already settled when the cleanup ran has its
 * callback sitting in the microtask queue *ahead* of that decision, so it runs
 * while the flight is still installed - and a hook that decides who may publish
 * from the flight alone will publish from a subscription React has already
 * discarded.
 *
 * Nothing about that gap is visible from the screen. A publish onto a fiber
 * React has torn down is dropped in silence: no state, no render, no DOM and no
 * warning. So every case below is asserted at the state publisher itself,
 * through the `useState` tap at the top of this file, and the ordering is fixed
 * by counting microtask turns rather than by waiting for anything.
 *
 * The turn counts are swept rather than picked. How many turns separate a
 * response from the hook's callback is an internal detail of the transport, and
 * a test that hard-coded today's count would stop exercising this window the
 * first time a hop is added or removed. Sweeping the range covers the
 * vulnerable turn wherever it currently is - including turn zero, where the
 * answer settles and the subscription ends in one synchronous stack.
 */
const CLEANUP_TURNS = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11] as const;

/** Comfortably more turns than the transport's whole chain takes. */
const FULL_FLUSH = 24;

describe("useCaseDetail publishes nothing from a subscription that is gone", () => {
  it("publishes no record when the screen goes away after the answer settled", async () => {
    for (const turns of CLEANUP_TURNS) {
      const { calls, spy } = stagedFetch();
      const client = signedIn();
      const { unmount } = render(client);
      await settle();
      expect(spy).toHaveBeenCalledTimes(1);

      calls[0].respond(200);
      calls[0].resolveJson(detailBody());
      await flushMicrotasks(turns);

      // The subscription ends here, synchronously, while its own answer may
      // already be queued behind this call.
      unmount();
      const settledPublishes = publisher.writes.length;
      await flushMicrotasks(FULL_FLUSH);

      expect(
        publisher.writes,
        `unmounted ${turns} microtasks after the answer settled`,
      ).toHaveLength(settledPublishes);
      publisher.writes.length = 0;
    }
  });

  it("publishes no failure and reaches no session subscriber when the screen goes away after a 401 settled", async () => {
    for (const turns of CLEANUP_TURNS) {
      const { calls } = stagedFetch();
      const client = signedIn();
      const { unmount } = render(client);
      await settle();

      // A 401 never reads a body, so the response line alone settles it.
      calls[0].respond(401);
      await flushMicrotasks(turns);

      unmount();
      const settledPublishes = publisher.writes.length;
      const settledNotifications = client.calls.notified;
      await flushMicrotasks(FULL_FLUSH);

      expect(
        publisher.writes,
        `unmounted ${turns} microtasks after the 401 settled`,
      ).toHaveLength(settledPublishes);
      // Nothing of the abandoned answer reaches the session either: no
      // subscriber is told anything once the tree that held them is gone.
      expect(client.calls.notified, `unmounted ${turns} microtasks in`).toBe(
        settledNotifications,
      );
      publisher.writes.length = 0;
    }
  });

  it("publishes no failure when the screen goes away after the request itself failed", async () => {
    for (const turns of CLEANUP_TURNS) {
      const { calls } = stagedFetch();
      const client = signedIn();
      const { unmount } = render(client);
      await settle();

      calls[0].failFetch(new TypeError("connection refused"));
      await flushMicrotasks(turns);

      unmount();
      const settledPublishes = publisher.writes.length;
      await flushMicrotasks(FULL_FLUSH);

      expect(
        publisher.writes,
        `unmounted ${turns} microtasks after the failure settled`,
      ).toHaveLength(settledPublishes);
      publisher.writes.length = 0;
    }
  });

  it("publishes nothing from the subscription a route change to no case replaced", async () => {
    for (const turns of CLEANUP_TURNS) {
      const { calls } = stagedFetch();
      const client = signedIn();
      const view = render(client);
      await settle();

      calls[0].respond(200);
      calls[0].resolveJson(detailBody());
      await flushMicrotasks(turns);

      // The route stops naming a case, so the effect is cleaned up and nothing
      // replaces the flight it leaves behind - which is exactly the shape that
      // leaves an abandoned request installed and still current.
      view.rerender(null);
      const settledPublishes = publisher.writes.length;
      await flushMicrotasks(FULL_FLUSH);

      expect(
        publisher.writes,
        `route cleared ${turns} microtasks after the answer settled`,
      ).toHaveLength(settledPublishes);
      expect(view.result.current.state).toEqual({ status: "idle" });
      view.unmount();
      publisher.writes.length = 0;
    }
  });

  it("publishes nothing from the subscription a lost session replaced", async () => {
    for (const turns of CLEANUP_TURNS) {
      adapter.client = createFakeAuthClient({
        initialSession: SESSION,
        accessToken: ACCESS_TOKEN,
      });
      const { calls } = stagedFetch();
      const view = renderControlled();
      await settle();

      calls[0].respond(200);
      calls[0].resolveJson(detailBody());
      await flushMicrotasks(turns);

      act(() => {
        setAuthState?.({ status: "unauthenticated" });
      });
      const settledPublishes = publisher.writes.length;
      await flushMicrotasks(FULL_FLUSH);

      expect(
        publisher.writes,
        `session lost ${turns} microtasks after the answer settled`,
      ).toHaveLength(settledPublishes);
      expect(view.result.current.state).toEqual({ status: "idle" });
      view.unmount();
      publisher.writes.length = 0;
    }
  });

  it("hands the answer to the subscription that replaced a cleaned-up one, on one request", async () => {
    for (const turns of CLEANUP_TURNS) {
      const { calls, spy } = stagedFetch();
      const client = signedIn();
      const view = render(client);
      await settle();
      const before = publisher.writes.length;

      calls[0].respond(200);
      calls[0].resolveJson(detailBody());
      await flushMicrotasks(turns);

      if (publisher.writes.length > before) {
        // The answer was already published by a subscription that was still
        // live, so there is no replacement to observe at this turn.
        view.unmount();
        publisher.writes.length = 0;
        continue;
      }

      // Cleanup and re-subscription to the same request, in one synchronous
      // stack with no microtask between them: the shape of StrictMode's replay.
      // The subscription that was cleaned up must stay silent, and the one that
      // replaced it must still receive this same answer - the request is
      // shared, the permission to publish from it is not.
      view.rerender(null);
      view.rerender(CASE_ID);
      await flushMicrotasks(FULL_FLUSH);

      await waitFor(() => {
        expect(view.result.current.state.status).toBe("success");
      });
      if (view.result.current.state.status !== "success") {
        throw new Error("unreachable");
      }
      expect(view.result.current.state.data.caseId, `re-subscribed at turn ${turns}`).toBe(
        CASE_ID,
      );
      expect(spy, `re-subscribed at turn ${turns}`).toHaveBeenCalledTimes(1);
      view.unmount();
      publisher.writes.length = 0;
    }
  });

  it("publishes nothing from the subscription a different case replaced", async () => {
    for (const turns of CLEANUP_TURNS) {
      const { calls } = stagedFetch();
      const client = signedIn();
      const view = render(client);
      await settle();

      calls[0].respond(200);
      calls[0].resolveJson(detailBody());
      await flushMicrotasks(turns);

      view.rerender(OTHER_CASE_ID);
      const settledPublishes = publisher.writes.length;
      await flushMicrotasks(FULL_FLUSH);

      // The second case's own request is never answered, so anything published
      // here could only have come from the subscription the swap replaced.
      expect(
        publisher.writes,
        `case swapped ${turns} microtasks after the answer settled`,
      ).toHaveLength(settledPublishes);
      expect(view.result.current.state).toEqual({ status: "loading" });
      view.unmount();
      publisher.writes.length = 0;
    }
  });

  it("publishes nothing from the subscription a different session replaced", async () => {
    for (const turns of CLEANUP_TURNS) {
      adapter.client = createFakeAuthClient({
        initialSession: SESSION,
        accessToken: ACCESS_TOKEN,
      });
      const { calls } = stagedFetch();
      const view = renderControlled();
      await settle();

      calls[0].respond(200);
      calls[0].resolveJson(detailBody({ assigneeRef: "analyst_ref_from_session_a" }));
      await flushMicrotasks(turns);

      act(() => {
        setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
      });
      const settledPublishes = publisher.writes.length;
      await flushMicrotasks(FULL_FLUSH);

      expect(
        publisher.writes,
        `session swapped ${turns} microtasks after the answer settled`,
      ).toHaveLength(settledPublishes);
      expect(view.result.current.state).toEqual({ status: "loading" });
      view.unmount();
      publisher.writes.length = 0;
    }
  });
});

/**
 * The gap between a request answering and anyone being there to hear it.
 *
 * The block above establishes that a subscription React has torn down publishes
 * nothing. This one is about the other half of that sentence, which does not
 * follow from it: the *answer* must survive even though the subscription did
 * not. A cleanup and the subscription that replaces it are separated by a
 * microtask, and a request that settles inside that microtask settles with an
 * empty listener list - so a hook that decides "nobody is listening, therefore
 * nothing happened" throws away the only answer its own request will ever
 * produce, and the screen that arrives half a turn later waits for it forever.
 *
 * Nothing about that is visible from the request count either: exactly one
 * fetch was made, exactly one response came back, and the spinner never stops.
 *
 * The ordering is built rather than waited for. The re-subscription is queued
 * *before* the cleanup runs, so it lands after the answer's own continuation -
 * which was already queued - and ahead of the release microtask the cleanup
 * schedules. That is the exact window, reproduced by microtask position alone,
 * with no timer and no real time anywhere in it.
 *
 * The turn it opens on is swept rather than picked, for the same reason the
 * block above sweeps: how many hops separate a staged response from the hook's
 * own callback is an internal detail of the transport, and it differs per
 * outcome - a 404 never reads a body, a transport failure never produces a
 * response at all. Every turn in the sweep must end in the same place, so the
 * assertions never depend on where the window currently sits.
 */

/** How a staged call is answered, so one ordering can be replayed per outcome. */
type Answer = (call: StagedCall) => void;

function respondWithCase(body: unknown = detailBody()): Answer {
  return (call: StagedCall): void => {
    call.respond(200);
    call.resolveJson(body);
  };
}

/** A 404 never reads a body, so the response line alone settles it. */
const respondNotFound: Answer = (call: StagedCall): void => {
  call.respond(404);
};

const failWithNetworkError: Answer = (call: StagedCall): void => {
  call.failFetch(new TypeError("connection refused"));
};

interface RecordedWrite {
  readonly session: unknown;
  readonly caseId: unknown;
  readonly state: { readonly status: string; readonly data?: Record<string, unknown> };
}

/** The settled statuses. `idle` and `loading` are the hook talking about itself. */
const TERMINAL_STATUSES: readonly string[] = ["success", "not-found", "forbidden", "error"];

/** Every settled state handed to the snapshot publisher since `from`, in order. */
function terminalPublishes(from: number): RecordedWrite[] {
  return publisher.writes.slice(from).filter((write): write is RecordedWrite => {
    const state = (write as RecordedWrite).state;
    return (
      typeof state === "object" && state !== null && TERMINAL_STATUSES.includes(state.status)
    );
  });
}

interface ReplayRun {
  readonly view: ReturnType<typeof render>;
  readonly spy: ReturnType<typeof vi.fn>;
  readonly client: FakeAuthClient;
  /** Publisher writes recorded before the answer was staged. */
  readonly from: number;
  /** Settled publishes that had already happened when the cleanup ran. */
  readonly beforeCleanup: number;
  /**
   * Settled publishes counted the instant the replacement subscription was
   * installed, before anything else was allowed to run.
   *
   * This is what makes the window observable from outside the hook. With
   * `beforeCleanup` at zero and this at one, the answer can only have arrived
   * while no subscription existed and been handed over by the act of
   * re-subscribing: there was nobody to publish to before, and nothing else
   * had a turn since.
   */
  readonly duringResubscribe: number;
}

/**
 * Runs one request, ends its only subscription at a chosen microtask, and
 * re-subscribes from inside the window that opens.
 */
async function resubscribeAcrossTheAnswer(
  answer: Answer,
  turns: number,
  resubscribeTo: string | null = CASE_ID,
): Promise<ReplayRun> {
  const { calls, spy } = stagedFetch();
  const client = signedIn();
  const view = render(client);
  await settle();
  expect(spy).toHaveBeenCalledTimes(1);

  const from = publisher.writes.length;
  answer(calls[0]);
  await flushMicrotasks(turns);
  const beforeCleanup = terminalPublishes(from).length;

  // Queued ahead of the cleanup's own release microtask, and behind the
  // answer's continuation. Both halves matter: the flight must still be
  // installed when this runs, and the answer must have had its turn first.
  let duringResubscribe = 0;
  const resubscribed = new Promise<void>((resolve) => {
    queueMicrotask(() => {
      view.rerender(resubscribeTo);
      duringResubscribe = terminalPublishes(from).length;
      resolve();
    });
  });
  view.rerender(null);
  await resubscribed;
  await flushMicrotasks(FULL_FLUSH);

  return { view, spy, client, from, beforeCleanup, duringResubscribe };
}

/**
 * Replays the cleanup-and-replacement ordering at every turn in the sweep, and
 * reports how many of them landed in the window where the answer settles with
 * nobody listening.
 */
async function sweepTheResubscriptionWindow(
  answer: Answer,
  resubscribeTo: string | null,
  check: (run: ReplayRun, at: string) => Promise<void>,
): Promise<number> {
  let windows = 0;
  for (const turns of CLEANUP_TURNS) {
    const run = await resubscribeAcrossTheAnswer(answer, turns, resubscribeTo);
    if (run.beforeCleanup === 0 && run.duringResubscribe === 1) {
      windows += 1;
    }
    await check(run, `re-subscribed at turn ${turns}`);
    run.view.unmount();
    publisher.writes.length = 0;
  }
  return windows;
}

describe("useCaseDetail keeps an answer that settled with no one listening", () => {
  it("hands a success to the subscription that replaced the one it settled without", async () => {
    const windows = await sweepTheResubscriptionWindow(
      respondWithCase(),
      CASE_ID,
      async (run, at) => {
        await waitFor(() => {
          expect(run.view.result.current.state.status, at).toBe("success");
        });
        if (run.view.result.current.state.status !== "success") {
          throw new Error("unreachable");
        }
        expect(Object.keys(run.view.result.current.state.data), at).toEqual(PROJECTION_ORDER);
        expect(run.view.result.current.state.data.caseId, at).toBe(CASE_ID);
        expect(run.view.result.current.state.data.concurrencyVersion, at).toBe(4);

        // At most one settled publish per subscription: the one that was live
        // when the answer landed, if there was one, plus the one that replaced
        // it. Never twice to the same subscription, and never none.
        const published = terminalPublishes(run.from);
        expect(published, at).toHaveLength(run.beforeCleanup + 1);
        expect(published[published.length - 1].caseId, at).toBe(CASE_ID);
        // One request answered one screen. Nothing was asked again to recover.
        expect(run.spy, at).toHaveBeenCalledTimes(1);
      },
    );
    // The sweep straddles the window rather than sitting to one side of it.
    expect(windows).toBeGreaterThan(0);
  });

  it("hands a not-found to the subscription that replaced the one it settled without", async () => {
    const windows = await sweepTheResubscriptionWindow(
      respondNotFound,
      CASE_ID,
      async (run, at) => {
        await waitFor(() => {
          expect(run.view.result.current.state.status, at).toBe("not-found");
        });
        expect(terminalPublishes(run.from), at).toHaveLength(run.beforeCleanup + 1);
        expect(run.spy, at).toHaveBeenCalledTimes(1);
        // A 404 is a settled answer, so nothing is tried again and the session
        // is left exactly as it was.
        expect(run.client.calls.notified, at).toBe(0);
        expect(JSON.stringify(run.view.result.current.state), at).toBe('{"status":"not-found"}');
      },
    );
    expect(windows).toBeGreaterThan(0);
  });

  it("hands a retryable failure to the subscription that replaced the one it settled without", async () => {
    const windows = await sweepTheResubscriptionWindow(
      failWithNetworkError,
      CASE_ID,
      async (run, at) => {
        await waitFor(() => {
          expect(run.view.result.current.state, at).toEqual({
            status: "error",
            error: "network",
          });
        });
        expect(terminalPublishes(run.from), at).toHaveLength(run.beforeCleanup + 1);
        // Nothing re-sent itself on the way here.
        expect(run.spy, at).toHaveBeenCalledTimes(1);
        // Nothing of the thrown error survived into the state.
        expect(JSON.stringify(run.view.result.current.state), at).toBe(
          '{"status":"error","error":"network"}',
        );

        // The recovered state is a working one: the manual retry is still the
        // only thing that re-sends, and it re-sends once.
        act(() => {
          run.view.result.current.retry();
        });
        await settle();
        expect(run.spy, at).toHaveBeenCalledTimes(2);
        expect(run.view.result.current.state, at).toEqual({ status: "loading" });
      },
    );
    expect(windows).toBeGreaterThan(0);
  });

  it("carries no trace id, envelope or Backend text into a replayed success", async () => {
    // The Backend's key order, reversed: an answer kept as the parsed envelope
    // would come back in this order, and the projection's own order is what
    // proves the kept value is a fresh object rather than that one.
    const reversed: Record<string, unknown> = {};
    const fields = caseFields();
    for (const key of [...PROJECTION_ORDER].reverse()) {
      reversed[key] = fields[key];
    }
    expect(Object.keys(reversed)).toEqual([...PROJECTION_ORDER].reverse());

    const windows = await sweepTheResubscriptionWindow(
      respondWithCase({ case: reversed, traceId: TRACE_ID }),
      CASE_ID,
      async (run, at) => {
        await waitFor(() => {
          expect(run.view.result.current.state.status, at).toBe("success");
        });
        if (run.view.result.current.state.status !== "success") {
          throw new Error("unreachable");
        }
        expect(Object.keys(run.view.result.current.state.data), at).toEqual(PROJECTION_ORDER);
        expect(Object.keys(run.view.result.current.state).sort(), at).toEqual([
          "data",
          "status",
        ]);

        const published = JSON.stringify(run.view.result.current.state);
        expect(published, at).not.toContain(TRACE_ID);
        expect(published, at).not.toContain("traceId");
        expect(published, at).not.toContain('"case"');
        expect(published, at).not.toContain(ACCESS_TOKEN);
      },
    );
    expect(windows).toBeGreaterThan(0);
  });

  it("publishes one answer once when a subscription is live as it settles", async () => {
    const { calls, spy } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();

    const from = publisher.writes.length;
    respondWithCase()(calls[0]);
    await flushMicrotasks(FULL_FLUSH);

    await waitFor(() => {
      expect(view.result.current.state.status).toBe("success");
    });
    expect(terminalPublishes(from)).toHaveLength(1);
    expect(spy).toHaveBeenCalledTimes(1);
    view.unmount();
  });

  it("publishes to the live subscription alone when a cleaned-up one shares the flight", async () => {
    const { calls, spy } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();

    const from = publisher.writes.length;
    // Cleanup and re-subscription in one synchronous stack, ahead of the
    // answer: the flight is left holding a subscription that may not speak
    // alongside one that may.
    view.rerender(null);
    view.rerender(CASE_ID);
    respondWithCase()(calls[0]);
    await flushMicrotasks(FULL_FLUSH);

    await waitFor(() => {
      expect(view.result.current.state.status).toBe("success");
    });
    expect(terminalPublishes(from)).toHaveLength(1);
    expect(spy).toHaveBeenCalledTimes(1);
    view.unmount();
  });

  it("forgets a settled answer once the last subscription is gone, and asks again", async () => {
    const { calls, spy } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();

    respondWithCase()(calls[0]);
    await flushMicrotasks(FULL_FLUSH);
    await waitFor(() => {
      expect(view.result.current.state.status).toBe("success");
    });

    // The route stops naming a case. Nothing re-subscribes, so the release
    // microtask finds an empty flight and takes the kept answer with it.
    await act(async () => {
      view.rerender(null);
      await flushMicrotasks(FULL_FLUSH);
    });
    expect(view.result.current.state).toEqual({ status: "idle" });

    const from = publisher.writes.length;
    await act(async () => {
      view.rerender(CASE_ID);
      await flushMicrotasks(FULL_FLUSH);
    });

    // A second request, because there was nothing left to replay: an answer
    // that outlived every subscriber would have published here without one.
    expect(spy).toHaveBeenCalledTimes(2);
    expect(view.result.current.state).toEqual({ status: "loading" });
    expect(terminalPublishes(from)).toHaveLength(0);
    view.unmount();
  });

  it("never hands a kept answer to a different case", async () => {
    // Every turn of the same sweep, including the window turn the tests above
    // establish is inside it: a kept answer belongs to the case it was asked
    // for and to no other.
    await sweepTheResubscriptionWindow(respondWithCase(), OTHER_CASE_ID, async (run, at) => {
      // The second case's own request is never answered, so nothing may settle
      // after the swap: whatever the first case published while it was still on
      // screen is all there is, and none of it changes heading.
      expect(run.duringResubscribe, at).toBe(run.beforeCleanup);
      const published = terminalPublishes(run.from);
      expect(published, at).toHaveLength(run.beforeCleanup);
      for (const write of published) {
        expect(write.caseId, at).toBe(CASE_ID);
      }
      expect(run.view.result.current.state, at).toEqual({ status: "loading" });
      expect(run.spy, at).toHaveBeenCalledTimes(2);
    });
  });

  it("never hands a kept answer to a different session", async () => {
    for (const turns of CLEANUP_TURNS) {
      adapter.client = createFakeAuthClient({
        initialSession: SESSION,
        accessToken: ACCESS_TOKEN,
      });
      const { calls, spy } = stagedFetch();
      const view = renderControlled();
      await settle();
      expect(spy).toHaveBeenCalledTimes(1);

      const from = publisher.writes.length;
      respondWithCase()(calls[0]);
      await flushMicrotasks(turns);
      const beforeSwap = terminalPublishes(from).length;

      const resubscribed = new Promise<void>((resolve) => {
        queueMicrotask(() => {
          act(() => {
            setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
          });
          resolve();
        });
      });
      act(() => {
        setAuthState?.({ status: "unauthenticated" });
      });
      await resubscribed;
      await flushMicrotasks(FULL_FLUSH);

      // The second session's request is never answered. The first session's
      // answer sits on a flight that is no longer the current one, and it stays
      // there: identity, not recency, decides who an answer belongs to.
      const published = terminalPublishes(from);
      expect(published, `session swapped at turn ${turns}`).toHaveLength(beforeSwap);
      for (const write of published) {
        expect(write.session, `session swapped at turn ${turns}`).toBe(SESSION);
      }
      expect(view.result.current.state, `session swapped at turn ${turns}`).toEqual({
        status: "loading",
      });
      expect(spy, `session swapped at turn ${turns}`).toHaveBeenCalledTimes(2);
      view.unmount();
      publisher.writes.length = 0;
    }
  });
});

/**
 * The record a screen is handed, and what it is allowed to do to it.
 *
 * The block above is about an answer *surviving* the subscription it settled
 * without. That leaves one question it does not answer: whether the answer that
 * survives is the same object the screen before it was given. A flight
 * remembers one outcome and may hand it out more than once - to the broadcast
 * when it settles, and to every subscription that joins afterwards - so if
 * "remembered" and "delivered" were one object, two screens would be holding
 * one record, and React state is not immutable at runtime. A consumer that
 * writes into the record it was handed would then be writing into the hook's
 * memory, and the next screen would be shown an edit as though the Backend had
 * sent it.
 *
 * Nothing about that is visible from values: every assertion here that matters
 * is `toBe` or `not.toBe` on a real reference, taken from the objects the
 * snapshot publisher was actually handed, and the propagation checks are run
 * against a genuine runtime mutation rather than a claim about one. Freezing is
 * deliberately not what is asserted - a frozen shared record would still be one
 * shared record, and the property being defended is isolation, not rigidity.
 *
 * The sharing path exercised is the real one, and it is the only one this hook
 * has. `flightRef` is a `useRef`, so a flight belongs to a single hook instance
 * and two mounted screens never meet on one; within one instance React creates
 * a replacement subscription only after the cleanup that cleared the one it
 * replaces, so a flight can hold two *listeners* - one cleaned up, one live -
 * but never two live ones. Sequential subscriptions over one settled flight is
 * therefore where every copy this hook makes is observable.
 */

interface OneScreenRun {
  readonly view: ReturnType<typeof render>;
  readonly calls: StagedCall[];
  readonly spy: ReturnType<typeof vi.fn>;
  /** Publisher writes recorded before the answer was staged. */
  readonly from: number;
}

/** Runs one request through to a settled answer, with a live subscription. */
async function deliverToOneScreen(answer: Answer): Promise<OneScreenRun> {
  const { calls, spy } = stagedFetch();
  const client = signedIn();
  const view = render(client);
  await settle();
  expect(spy).toHaveBeenCalledTimes(1);

  const from = publisher.writes.length;
  answer(calls[0]);
  await flushMicrotasks(FULL_FLUSH);
  return { view, calls, spy, from };
}

/**
 * Ends the current subscription and takes a new one in the same synchronous
 * stack, so the replacement joins the settled flight instead of a fresh one.
 *
 * No `await` between the two: the release the cleanup schedules is a microtask,
 * and letting it run would tear the flight down before anything could re-join
 * it. That is the ordering the block above builds, reached from the other side
 * - here the answer is already delivered, and what is under test is what the
 * *second* screen is given.
 */
function resubscribeInPlace(view: ReturnType<typeof render>): void {
  view.rerender(null);
  view.rerender(CASE_ID);
}

/** The delivered record of one recorded success, as a plain object. */
function successData(write: RecordedWrite): Record<string, unknown> {
  expect(write.state.status).toBe("success");
  const data = write.state.data;
  if (data === undefined) {
    throw new Error("unreachable");
  }
  return data;
}

describe("useCaseDetail hands each subscription its own answer", () => {
  it("gives the replay subscription a record of its own, equal to the first one", async () => {
    const run = await deliverToOneScreen(respondWithCase());
    expect(terminalPublishes(run.from)).toHaveLength(1);

    resubscribeInPlace(run.view);

    const published = terminalPublishes(run.from);
    expect(published).toHaveLength(2);
    const firstState = published[0].state;
    const replayState = published[1].state;
    const first = successData(published[0]);
    const replay = successData(published[1]);

    // Reference identity, not shape. Two screens holding one object is the
    // defect; two screens holding equal objects is the fix.
    expect(replayState).not.toBe(firstState);
    expect(replay).not.toBe(first);
    expect(replay).toEqual(first);
    expect(replay).toEqual(caseFields());
    expect(Object.keys(first)).toEqual(PROJECTION_ORDER);
    expect(Object.keys(replay)).toEqual(PROJECTION_ORDER);
    expect(Object.keys(replayState).sort()).toEqual(["data", "status"]);
    // One request answered both screens.
    expect(run.spy).toHaveBeenCalledTimes(1);
    run.view.unmount();
  });

  it("keeps neither the parsed envelope's record nor an edit made to it", async () => {
    const raw = caseFields();
    const run = await deliverToOneScreen(respondWithCase({ case: raw, traceId: TRACE_ID }));

    const first = successData(terminalPublishes(run.from)[0]);
    expect(first).not.toBe(raw);

    // The parsed envelope, edited from outside the hook after the answer was
    // taken. Nothing kept on the flight points at it, so nothing changes.
    Object.assign(raw, { caseStatus: "CLOSED", concurrencyVersion: 99 });
    expect(first.caseStatus).toBe("IN_REVIEW");

    resubscribeInPlace(run.view);
    const replay = successData(terminalPublishes(run.from)[1]);
    expect(replay).not.toBe(raw);
    expect(replay.caseStatus).toBe("IN_REVIEW");
    expect(replay.concurrencyVersion).toBe(4);
    expect(run.spy).toHaveBeenCalledTimes(1);
    run.view.unmount();
  });

  it("does not let one screen's edit reach the answer the next screen is given", async () => {
    const run = await deliverToOneScreen(respondWithCase());

    // A consumer writing into the record it was handed. Nothing at runtime
    // stops it, which is the whole reason the hook may not hand out what it
    // kept.
    const first = successData(terminalPublishes(run.from)[0]);
    Object.assign(first, { caseStatus: "CLOSED" });
    expect(first.caseStatus).toBe("CLOSED");

    resubscribeInPlace(run.view);
    const second = successData(terminalPublishes(run.from)[1]);
    expect(second).not.toBe(first);
    expect(second.caseStatus).toBe("IN_REVIEW");
    expect(second).toEqual(caseFields());

    // And the edit did not reach what the flight kept either. The second copy
    // is edited too, and a third subscription is still answered with what the
    // Backend sent - so the object the flight remembers can be neither of the
    // two that were written to.
    Object.assign(second, { caseStatus: "CLOSED" });
    resubscribeInPlace(run.view);
    const third = successData(terminalPublishes(run.from)[2]);
    expect(third).not.toBe(first);
    expect(third).not.toBe(second);
    expect(third.caseStatus).toBe("IN_REVIEW");
    expect(third).toEqual(caseFields());

    // Three screens, one request. Nothing was re-asked to undo the edit.
    expect(run.spy).toHaveBeenCalledTimes(1);
    run.view.unmount();
  });

  it("gives the live subscription a record of its own when a cleaned-up one shares the flight", async () => {
    const { calls, spy } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();

    const from = publisher.writes.length;
    // The one shape of sharing this hook can produce: a subscription that has
    // been cleaned up and its replacement, both listeners on one pending
    // flight, with the answer arriving afterwards.
    resubscribeInPlace(view);
    const raw = caseFields();
    respondWithCase({ case: raw, traceId: TRACE_ID })(calls[0]);
    await flushMicrotasks(FULL_FLUSH);

    const published = terminalPublishes(from);
    // Exactly once, to the live subscription alone.
    expect(published).toHaveLength(1);
    const delivered = successData(published[0]);
    expect(delivered).not.toBe(raw);
    Object.assign(delivered, { caseStatus: "CLOSED" });

    resubscribeInPlace(view);
    const replay = successData(terminalPublishes(from)[1]);
    expect(replay).not.toBe(delivered);
    expect(replay.caseStatus).toBe("IN_REVIEW");
    expect(spy).toHaveBeenCalledTimes(1);
    view.unmount();
  });

  it("gives the replay subscription its own not-found state", async () => {
    const run = await deliverToOneScreen(respondNotFound);
    expect(terminalPublishes(run.from)).toHaveLength(1);

    resubscribeInPlace(run.view);

    const published = terminalPublishes(run.from);
    expect(published).toHaveLength(2);
    expect(published[1].state).not.toBe(published[0].state);
    expect(published[0].state).toEqual({ status: "not-found" });
    expect(published[1].state).toEqual({ status: "not-found" });
    // Still nothing of the response behind it: one key, and no body, header or
    // trace id smuggled alongside.
    expect(JSON.stringify(published[1].state)).toBe('{"status":"not-found"}');
    expect(run.spy).toHaveBeenCalledTimes(1);
    run.view.unmount();
  });

  it("gives the replay subscription its own failure state, carrying no error object", async () => {
    const run = await deliverToOneScreen(failWithNetworkError);
    expect(terminalPublishes(run.from)).toHaveLength(1);

    resubscribeInPlace(run.view);

    const published = terminalPublishes(run.from);
    expect(published).toHaveLength(2);
    expect(published[1].state).not.toBe(published[0].state);
    const expected = { status: "error", error: "network" };
    expect(published[0].state).toEqual(expected);
    expect(published[1].state).toEqual(expected);
    // The fixed kind was copied across; the thrown `TypeError` was not.
    expect(Object.keys(published[1].state).sort()).toEqual(["error", "status"]);
    expect(JSON.stringify(published[1].state)).toBe('{"status":"error","error":"network"}');
    expect(JSON.stringify(published[1].state)).not.toContain("connection refused");
    expect(run.spy).toHaveBeenCalledTimes(1);
    run.view.unmount();
  });

  it("forgets an edited record with the last subscription, and asks again", async () => {
    const run = await deliverToOneScreen(respondWithCase());
    const first = successData(terminalPublishes(run.from)[0]);
    Object.assign(first, { caseStatus: "CLOSED" });

    // Nothing re-subscribes inside the window this time, so the release
    // microtask finds an empty flight and takes the kept answer with it.
    await act(async () => {
      run.view.rerender(null);
      await flushMicrotasks(FULL_FLUSH);
    });
    expect(run.view.result.current.state).toEqual({ status: "idle" });

    const from = publisher.writes.length;
    await act(async () => {
      run.view.rerender(CASE_ID);
      await flushMicrotasks(FULL_FLUSH);
    });

    // A second request rather than a replay: there was nothing left to replay,
    // edited or otherwise.
    expect(run.spy).toHaveBeenCalledTimes(2);
    expect(terminalPublishes(from)).toHaveLength(0);
    expect(run.view.result.current.state).toEqual({ status: "loading" });

    await act(async () => {
      respondWithCase()(run.calls[1]);
      await flushMicrotasks(FULL_FLUSH);
    });
    const second = successData(terminalPublishes(from)[0]);
    expect(second).not.toBe(first);
    expect(second.caseStatus).toBe("IN_REVIEW");
    expect(second).toEqual(caseFields());
    run.view.unmount();
  });
});

describe("useCaseDetail authoritative background refresh", () => {
  it("keeps a reconciliation floor across a stale response until an explicit refresh reaches it", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], detailBody({ concurrencyVersion: 4 }));
    const generation = view.result.current.reconciliationGeneration;

    act(() => view.result.current.refresh(5));
    await waitFor(() => expect(calls).toHaveLength(2));
    await answerWith(calls[1], detailBody({ concurrencyVersion: 4 }));

    expect(view.result.current.refreshState).toBe("failed");
    expect(view.result.current.reconciliationGeneration).toBe(generation);
    expect(view.result.current.state).toEqual({
      status: "success",
      data: caseFields({ concurrencyVersion: 4 }),
    });
    expect(calls).toHaveLength(2);

    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(3));
    await answerWith(calls[2], detailBody({ concurrencyVersion: 5 }));
    expect(view.result.current.refreshState).toBe("idle");
    expect(view.result.current.reconciliationGeneration).toBe(generation + 1);
    if (view.result.current.state.status === "success") {
      expect(view.result.current.state.data.concurrencyVersion).toBe(5);
    }
  });

  it.each([5, 6])("accepts detail version %i at or above the reconciliation floor", async (version) => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], detailBody({ concurrencyVersion: 4 }));
    const generation = view.result.current.reconciliationGeneration;

    act(() => view.result.current.refresh(5));
    await waitFor(() => expect(calls).toHaveLength(2));
    await answerWith(calls[1], detailBody({ concurrencyVersion: version }));

    expect(view.result.current.refreshState).toBe("idle");
    expect(view.result.current.reconciliationGeneration).toBe(generation + 1);
    if (view.result.current.state.status === "success") {
      expect(view.result.current.state.data.concurrencyVersion).toBe(version);
    }
  });

  it("releases a floor-bound refresh on case replacement and ignores its late answer", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], detailBody({ concurrencyVersion: 4 }));
    act(() => view.result.current.refresh(5));
    await waitFor(() => expect(calls).toHaveLength(2));

    view.rerender(OTHER_CASE_ID);
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[1].request.signal.aborted).toBe(true);
    await answerWith(calls[1], detailBody({ concurrencyVersion: 9 }));
    expect(view.result.current.state).toEqual({ status: "loading" });

    await answerWith(
      calls[2],
      detailBody({ caseId: OTHER_CASE_ID, concurrencyVersion: 1 }),
    );
    if (view.result.current.state.status === "success") {
      expect(view.result.current.state.data.caseId).toBe(OTHER_CASE_ID);
      expect(view.result.current.state.data.concurrencyVersion).toBe(1);
    }
  });

  it("keeps the record visible and publishes a newer version with a new generation", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], detailBody({ concurrencyVersion: 4 }));
    const generation = view.result.current.reconciliationGeneration;

    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(view.result.current.state.status).toBe("success");
    expect(view.result.current.refreshState).toBe("refreshing");

    await answerWith(
      calls[1],
      detailBody({ concurrencyVersion: 5, lastChangedAt: "2026-07-24T02:06:10Z" }),
    );
    expect(view.result.current.state.status).toBe("success");
    if (view.result.current.state.status !== "success") {
      throw new Error("Expected refreshed detail.");
    }
    expect(view.result.current.state.data.concurrencyVersion).toBe(5);
    expect(view.result.current.refreshState).toBe("idle");
    expect(view.result.current.reconciliationGeneration).toBe(generation + 1);
  });

  it("keeps the authoritative record when refresh fails and exposes an explicit retry boundary", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], detailBody({ concurrencyVersion: 4 }));

    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(2));
    await act(async () => {
      calls[1].fail(new TypeError("private network detail"));
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(view.result.current.refreshState).toBe("failed");
    expect(view.result.current.state.status).toBe("success");
    if (view.result.current.state.status === "success") {
      expect(view.result.current.state.data.concurrencyVersion).toBe(4);
    }

    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(3));
  });
});
