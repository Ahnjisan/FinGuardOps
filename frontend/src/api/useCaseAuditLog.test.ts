import { createElement, StrictMode, useEffect, useMemo, useState, type ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthClient, AuthSession } from "../auth/authClient";
import { AuthContext, type AuthContextValue } from "../auth/authContext";
import { AuthProvider } from "../auth/AuthProvider";
import type { AuthState } from "../auth/authState";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import * as caseAuditApi from "./caseAuditApi";
import { TimeoutError } from "./errors";

/**
 * The adapter the hook reaches for at its own credential boundary.
 *
 * The hook calls `getOidcAuthClient()` inside an effect and nowhere else, so
 * this is the one seam a test has to stand in at. Everything below it is
 * production code: the real `fetchCaseAuditList`, the real endpoint registry,
 * the real query builder, the real URL re-verification, the real authenticated
 * transport and the real audit response validator all run here. Only `fetch`
 * itself and the OIDC adapter are doubles.
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
 * exactly why a defect of that shape can sit in a green suite. The seam is the
 * setter React hands out, and the wrapper is stable across renders because the
 * setter it wraps is, so nothing downstream sees a changing identity.
 *
 * Nothing about the hook changes for this: there is no test-only export, no
 * injected publisher and no flag. Only writes shaped like this hook's own
 * `Snapshot` are recorded, so neither the auth provider's state nor the hook's
 * own cursor is mistaken for one.
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
            "page" in next &&
            "size" in next &&
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

const { useCaseAuditLog } = await import("./useCaseAuditLog");

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const OTHER_CASE_ID = "6d3e2f10-8b9c-4d0e-8f01-2b3c4d5e6f71";
const TRACE_ID = "trace_demo_case_audit_01";
const ACCESS_TOKEN = "case.audit.access.token";
const ASSIGNEE_A = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
const ASSIGNEE_B = "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071";
const NOTE_ID = "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60";

/** The exact target the section sends, path and query, in the builder's order. */
const AUDIT_PATH = `/api/v1/cases/${CASE_ID}/audit-logs`;
const AUDIT_QUERY = "?page=0&size=20&sort=changedAt%2Cdesc";

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

/** The seven keys an audit entry carries, in the order the projection writes. */
const ENTRY_ORDER: readonly string[] = [
  "action",
  "reasonCode",
  "actorType",
  "changedAt",
  "beforeSummary",
  "afterSummary",
  "metadata",
];

function created(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    action: "CASE_CREATED",
    reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
    actorType: "SYSTEM",
    changedAt: "2026-03-08T09:10:11.123456Z",
    beforeSummary: null,
    afterSummary: { caseStatus: "OPEN" },
    metadata: {},
    ...overrides,
  };
}

const LINKED: Record<string, unknown> = {
  action: "CASE_TRANSACTION_LINKED",
  reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
  actorType: "SYSTEM",
  changedAt: "2026-03-08T09:12:00.000001Z",
  beforeSummary: null,
  afterSummary: { linked: true },
  metadata: {},
};

const REVIEW_STARTED: Record<string, unknown> = {
  action: "CASE_STATUS_CHANGED",
  reasonCode: "CASE_REVIEW_STARTED",
  actorType: "USER",
  changedAt: "2026-03-09T00:01:02.000002Z",
  beforeSummary: { caseStatus: "OPEN", assigneeRef: null },
  afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_A },
  metadata: {},
};

const ASSIGNEE_RELEASED: Record<string, unknown> = {
  action: "CASE_ASSIGNEE_CHANGED",
  reasonCode: "CASE_ASSIGNEE_RELEASED",
  actorType: "USER",
  changedAt: "2026-03-09T02:03:04.000010Z",
  beforeSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: ASSIGNEE_B },
  afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
  metadata: {},
};

const RESOLVED: Record<string, unknown> = {
  action: "CASE_RESOLVED",
  reasonCode: "CASE_RESOLUTION_COMPLETED",
  actorType: "USER",
  changedAt: "2026-03-10T04:05:06.999999Z",
  beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_A },
  afterSummary: {
    caseStatus: "CLOSED",
    assigneeRef: ASSIGNEE_A,
    finalDisposition: "CONFIRMED_FRAUD",
  },
  metadata: {},
};

const NOTE_CREATED: Record<string, unknown> = {
  action: "CASE_NOTE_CREATED",
  reasonCode: "CASE_INVESTIGATION_NOTE_ADDED",
  actorType: "USER",
  changedAt: "2026-03-10T05:06:07.000000Z",
  beforeSummary: null,
  afterSummary: null,
  metadata: { noteId: NOTE_ID },
};

/** All six actions, and between them every summary shape the contract has. */
const ALL_ACTIONS: readonly Record<string, unknown>[] = [
  created(),
  LINKED,
  REVIEW_STARTED,
  ASSIGNEE_RELEASED,
  RESOLVED,
  NOTE_CREATED,
];

/**
 * A full page of `size` entries whose first one is the given entry.
 *
 * `isConsistentPageMetadata` requires a page before the last one to be full, so
 * a mid-trail page cannot be faked with one row. The leading entry is what
 * tells two pages apart in the assertions below.
 */
function fullPage(lead: Record<string, unknown>, size = 20): Record<string, unknown>[] {
  return [lead, ...Array.from({ length: size - 1 }, () => created())];
}

function pageMetadata(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    number: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
    ...overrides,
  };
}

function auditBody(
  content: readonly Record<string, unknown>[] = [created()],
  page: Record<string, unknown> = {},
  caseId: string = CASE_ID,
): Record<string, unknown> {
  return {
    caseId,
    content,
    page: pageMetadata({ totalElements: content.length, totalPages: content.length === 0 ? 0 : 1, ...page }),
    traceId: TRACE_ID,
  };
}

/**
 * One otherwise-valid entry whose validated `changedAt` read is observable.
 *
 * The response validator destructures this field once. A live hook then reads
 * it once more while building the stored projection; subscriber deliveries
 * read only that stored copy. This gives the lifecycle tests a real production
 * projection boundary without exporting or flagging anything in the hook.
 */
function projectionObservedEntry(onRead: () => void): Record<string, unknown> {
  const entry = created();
  const changedAt = entry.changedAt;
  Object.defineProperty(entry, "changedAt", {
    configurable: true,
    enumerable: true,
    get: () => {
      onRead();
      return changedAt;
    },
  });
  return entry;
}

function successfulPublishCount(): number {
  return publisher.writes.filter(
    (write) =>
      typeof write === "object" &&
      write !== null &&
      "state" in write &&
      typeof write.state === "object" &&
      write.state !== null &&
      "status" in write.state &&
      write.state.status === "success",
  ).length;
}

function recursiveOwnKeys(value: unknown): Set<PropertyKey> {
  const keys = new Set<PropertyKey>();
  const visited = new WeakSet<object>();
  const visit = (candidate: unknown): void => {
    if (typeof candidate !== "object" || candidate === null || visited.has(candidate)) {
      return;
    }
    visited.add(candidate);
    for (const key of Reflect.ownKeys(candidate)) {
      keys.add(key);
      visit(Reflect.get(candidate, key));
    }
  };
  visit(value);
  return keys;
}

function recursiveContainsError(value: unknown): boolean {
  const visited = new WeakSet<object>();
  const visit = (candidate: unknown): boolean => {
    if (typeof candidate !== "object" || candidate === null || visited.has(candidate)) {
      return false;
    }
    if (candidate instanceof Error) {
      return true;
    }
    visited.add(candidate);
    return Reflect.ownKeys(candidate).some((key) => visit(Reflect.get(candidate, key)));
  };
  return visit(value);
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
 * `controlledFetch` hands over a whole `Response` at once, which is the right
 * shape for "what does this answer mean". It is the wrong shape for "when,
 * exactly, does this answer reach the hook": a real `Response` reads its body
 * through a stream, and that read does not complete on the microtask queue at
 * all, so no ordering can be fixed around it without waiting on real time.
 */
interface StagedCall {
  readonly request: Request;
  respond: (status: number) => void;
  resolveJson: (value: unknown) => void;
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
    const json = new Promise<unknown>((resolve) => {
      resolveJson = resolve;
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
    calls.push({ request, respond, resolveJson, failFetch });
    return response;
  });
  vi.stubGlobal("fetch", spy);
  return { calls, spy };
}

/**
 * Advances the microtask queue a fixed number of turns, and does nothing else.
 *
 * No timers, no real waiting and no polling: every ordering asserted in the
 * lifecycle blocks below is decided by where in this queue a callback was
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
  return renderHook((current: string | null) => useCaseAuditLog(current), {
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

describe("useCaseAuditLog without something to ask for", () => {
  it("makes no request at all without a session", async () => {
    const { spy } = controlledFetch();
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    const { result } = render(client);
    await settle();

    expect(result.current.state).toEqual({ status: "idle" });
    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
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
    ["an invalid RFC variant", "5c2d1e0f-7a8b-4c9d-1e0f-1a2b3c4d5e60"],
    ["a trailing space", "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60 "],
    ["a trailing slash", "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60/"],
    ["an encoded slash", "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60%2f"],
    ["an audit suffix of its own", "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60/audit-logs"],
    ["an unhyphenated identifier", "5c2d1e0f7a8b4c9d9e0f1a2b3c4d5e60"],
    ["a path of its own", "../../health"],
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

describe("useCaseAuditLog request contract", () => {
  it("issues exactly one request under StrictMode and publishes the page", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();

    expect(result.current.state).toEqual({ status: "loading" });
    expect(spy).toHaveBeenCalledTimes(1);

    await answerWith(calls[0], auditBody(ALL_ACTIONS, { totalElements: 6 }));

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    expect(spy).toHaveBeenCalledTimes(1);
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content).toHaveLength(6);
  });

  it("asks the audit endpoint with page, size and the fixed descending sort", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    render(client);
    await settle();

    const url = new URL(calls[0].request.url);
    expect(url.origin).toBe("http://localhost:8080");
    expect(url.pathname).toBe(AUDIT_PATH);
    // The three names the audit query contract declares, in the builder's own
    // order and encoding. Nothing else is sent, and no case filter reaches an
    // endpoint that does not declare one.
    expect(url.search).toBe(AUDIT_QUERY);
    expect([...url.searchParams.keys()]).toEqual(["page", "size", "sort"]);
    expect(calls[0].request.method).toBe("GET");
    expect(calls).toHaveLength(1);
  });

  it("opens on the first page at twenty entries", async () => {
    const client = signedIn();
    controlledFetch();

    const { result } = render(client);
    await settle();

    expect(result.current.page).toBe(0);
    expect(result.current.size).toBe(20);
  });

  it("makes no further request while nothing it asks for has changed", async () => {
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
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {}, 500);

    await waitFor(() => {
      expect(result.current.state.status).toBe("generic-error");
    });
    await settle();
    await settle();

    expect(calls).toHaveLength(1);
  });

  it("re-sends exactly once per explicit retry", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {}, 500);
    await waitFor(() => {
      expect(result.current.state.status).toBe("generic-error");
    });

    act(() => {
      result.current.retry();
    });
    await settle();
    expect(calls).toHaveLength(2);
    expect(result.current.state).toEqual({ status: "loading" });

    await answerWith(calls[1], auditBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    expect(calls).toHaveLength(2);
  });

  it("keeps the same page and size across a retry", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    act(() => {
      result.current.setSize(50);
    });
    await settle();
    await answerWith(calls[1], {}, 503);
    await waitFor(() => {
      expect(result.current.state.status).toBe("generic-error");
    });

    act(() => {
      result.current.retry();
    });
    await settle();

    expect(new URL(calls[2].request.url).search).toBe("?page=0&size=50&sort=changedAt%2Cdesc");
    expect(result.current.size).toBe(50);
  });

  it("does not publish a retry answer after the page identity changes", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();
    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {}, 503);
    await waitFor(() => expect(result.current.state.status).toBe("generic-error"));

    act(() => result.current.retry());
    await settle();
    act(() => result.current.setPage(1));
    await settle();
    expect(calls).toHaveLength(3);

    await answerWith(calls[1], auditBody([created()]));
    expect(result.current.state).toEqual({ status: "loading" });
    expect(result.current.page).toBe(1);
  });

  it.each([
    ["a success", 200, auditBody()],
    ["a case that does not exist", 404, {}],
    ["a case this session may not read", 403, {}],
  ])("ignores a retry from %s", async (_label, status, body) => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], body, status);
    await waitFor(() => {
      expect(result.current.state.status).not.toBe("loading");
    });
    const settledState = result.current.state;

    act(() => {
      result.current.retry();
    });
    await settle();

    expect(calls).toHaveLength(1);
    expect(result.current.state).toEqual(settledState);
  });

  it("ignores a retry while a request is still in flight", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();

    act(() => {
      result.current.retry();
    });
    await settle();

    expect(calls).toHaveLength(1);
    expect(result.current.state).toEqual({ status: "loading" });
  });
});

describe("useCaseAuditLog pagination", () => {
  async function firstPage(): Promise<{
    readonly calls: PendingCall[];
    readonly result: { current: ReturnType<typeof useCaseAuditLog> };
  }> {
    const { calls } = controlledFetch();
    const client = signedIn();
    const { result } = render(client);
    await settle();
    await answerWith(
      calls[0],
      auditBody(fullPage(created()), { totalElements: 137, totalPages: 7, last: false }),
    );
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    return { calls, result };
  }

  it("asks for the page it was told to ask for", async () => {
    const { calls, result } = await firstPage();

    act(() => {
      result.current.setPage(3);
    });
    await settle();

    expect(calls).toHaveLength(2);
    expect(new URL(calls[1].request.url).search).toBe("?page=3&size=20&sort=changedAt%2Cdesc");
    expect(result.current.page).toBe(3);
  });

  it("removes the previous page while the next one loads", async () => {
    const { result } = await firstPage();

    act(() => {
      result.current.setPage(1);
    });

    // The counterexample this exists for. Keeping the old entries on screen
    // under a new page number would show page 1 labelled as page 2.
    expect(result.current.state).toEqual({ status: "loading" });
  });

  it("returns to the first page when the size changes", async () => {
    const { calls, result } = await firstPage();

    act(() => {
      result.current.setPage(3);
    });
    await settle();
    await answerWith(
      calls[1],
      auditBody(fullPage(created()), {
        number: 3,
        totalElements: 137,
        totalPages: 7,
        first: false,
        last: false,
      }),
    );
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    act(() => {
      result.current.setSize(100);
    });
    await settle();

    // Page 3 of 20 and page 3 of 100 are different windows. Keeping the number
    // would move the reader somewhere they did not ask to go.
    expect(result.current.page).toBe(0);
    expect(result.current.size).toBe(100);
    expect(new URL(calls[2].request.url).search).toBe("?page=0&size=100&sort=changedAt%2Cdesc");
  });

  it("does not correct an out-of-range page with a second request", async () => {
    const { calls, result } = await firstPage();

    act(() => {
      result.current.setPage(99);
    });
    await settle();
    await answerWith(
      calls[1],
      auditBody([], { number: 99, totalElements: 137, totalPages: 7, first: false, last: true }),
    );
    await waitFor(() => {
      expect(result.current.state.status).toBe("empty");
    });
    await settle();

    // An empty page is an answer, not a problem to be fixed behind the reader's
    // back. Re-asking for the last page here would be a request nobody made.
    expect(calls).toHaveLength(2);
    if (result.current.state.status !== "empty") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content).toHaveLength(0);
    expect(result.current.state.data.page.totalElements).toBe(137);
  });

  it("ignores a late answer belonging to a page the reader has left", async () => {
    const { calls, result } = await firstPage();

    act(() => {
      result.current.setPage(1);
    });
    await settle();
    act(() => {
      result.current.setPage(2);
    });
    await settle();
    expect(calls).toHaveLength(3);

    // Page 1 answers last, and its answer belongs to nothing on screen.
    await answerWith(
      calls[2],
      auditBody(fullPage(RESOLVED), {
        number: 2,
        totalElements: 137,
        totalPages: 7,
        first: false,
        last: false,
      }),
    );
    await answerWith(
      calls[1],
      auditBody(fullPage(LINKED), {
        number: 1,
        totalElements: 137,
        totalPages: 7,
        first: false,
        last: false,
      }),
    );

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.page.number).toBe(2);
    expect(result.current.state.data.content[0].action).toBe("CASE_RESOLVED");
  });

  it("keeps the newest flight across a page 0 to 1 to 0 race", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();
    const { result } = render(client);
    await settle();

    act(() => result.current.setPage(1));
    await settle();
    act(() => result.current.setPage(0));
    await settle();
    expect(calls).toHaveLength(3);

    await answerWith(calls[0], auditBody([created({ actorType: "SYSTEM" })]));
    expect(result.current.state).toEqual({ status: "loading" });
    await answerWith(calls[2], auditBody([created({ actorType: "USER" })]));
    await waitFor(() => expect(result.current.state.status).toBe("success"));
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content[0].actorType).toBe("USER");
  });

  it("cancels the superseded page request", async () => {
    const { calls, result } = await firstPage();

    act(() => {
      result.current.setPage(1);
    });
    await settle();
    act(() => {
      result.current.setPage(2);
    });
    await settle();

    expect(calls[1].request.signal.aborted).toBe(true);
    expect(calls[2].request.signal.aborted).toBe(false);
  });

  it("cancels the superseded size request", async () => {
    const { calls, result } = await firstPage();

    act(() => {
      result.current.setSize(50);
    });
    await settle();
    act(() => {
      result.current.setSize(100);
    });
    await settle();

    expect(calls[1].request.signal.aborted).toBe(true);
    expect(new URL(calls[2].request.url).search).toBe("?page=0&size=100&sort=changedAt%2Cdesc");
  });
});

describe("useCaseAuditLog latest request wins", () => {
  it("removes the previous trail the moment the case changes", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    await answerWith(calls[0], auditBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });

    rerender(OTHER_CASE_ID);

    expect(result.current.state).toEqual({ status: "loading" });
  });

  it("starts the new case on the first page at the opening size", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    act(() => {
      result.current.setPage(4);
    });
    act(() => {
      result.current.setSize(100);
    });
    await settle();

    rerender(OTHER_CASE_ID);
    await settle();

    // A page number that outlived its case would ask page 4 of a trail the
    // reader has only just opened.
    expect(result.current.page).toBe(0);
    expect(result.current.size).toBe(20);
    const last = calls[calls.length - 1];
    expect(new URL(last.request.url).pathname).toBe(`/api/v1/cases/${OTHER_CASE_ID}/audit-logs`);
    expect(new URL(last.request.url).search).toBe(AUDIT_QUERY);
  });

  it("ignores a late success belonging to a superseded case", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    rerender(OTHER_CASE_ID);
    await settle();
    expect(calls).toHaveLength(2);

    await answerWith(calls[0], auditBody([created()], {}, CASE_ID));

    expect(result.current.state).toEqual({ status: "loading" });
  });

  it("ignores a late failure belonging to a superseded case", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result, rerender } = render(client);
    await settle();
    rerender(OTHER_CASE_ID);
    await settle();

    await answerWith(calls[0], {}, 500);

    expect(result.current.state).toEqual({ status: "loading" });
  });

  it.each([403, 404])("ignores a stale %s belonging to a superseded case", async (status) => {
    const { calls } = controlledFetch();
    const client = signedIn();
    const { result, rerender } = render(client);
    await settle();
    rerender(OTHER_CASE_ID);
    await settle();

    await answerWith(calls[0], {}, status);

    expect(result.current.state).toEqual({ status: "loading" });
    expect(client.calls.notified).toBe(0);
  });

  it("publishes nothing after the section has gone away", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { unmount } = render(client);
    await settle();
    unmount();
    const before = publisher.writes.length;

    await answerWith(calls[0], auditBody());
    await flushMicrotasks(24);

    expect(publisher.writes).toHaveLength(before);
  });
});

describe("useCaseAuditLog session boundaries", () => {
  it("clears the trail and drops the session on a current-session 401", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {}, 401);

    await waitFor(() => {
      expect(result.current.state.status).not.toBe("loading");
    });
    // The port invalidated the session that signed the request, so the provider
    // has already moved to unauthenticated and no entry is left on screen.
    expect(result.current.state).toEqual({ status: "idle" });
    expect(client.calls.invalidateIfCurrent).toBe(1);
    expect(client.calls.notified).toBe(1);
    expect(calls).toHaveLength(1);
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

  it("keeps the session on 403", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], {}, 403);

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
    await answerWith(calls[0], {}, 404);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "not-found" });
    });
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });
});

describe("useCaseAuditLog error classification", () => {
  it("reports an authenticated session with no usable credential explicitly", async () => {
    const { spy } = controlledFetch();
    const client = createFakeAuthClient({ initialSession: SESSION, accessToken: "" });
    adapter.client = client;
    const { result } = render(client);
    await settle();

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "authentication-required" });
    });
    expect(spy).not.toHaveBeenCalled();
  });

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
      expect(result.current.state).toEqual({ status: "network-error" });
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

    expect(result.current.state).toEqual({ status: "timeout" });
    vi.useRealTimers();
  });

  it("reports an unmapped Backend status as the generic failure", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], { code: "SERVICE_UNAVAILABLE" }, 503);

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "generic-error" });
    });
  });

  it("returns an empty page as an explicit empty state with its page metadata", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], auditBody([], { totalElements: 0, totalPages: 0, last: true }));

    await waitFor(() => {
      expect(result.current.state.status).toBe("empty");
    });
    if (result.current.state.status !== "empty") {
      throw new Error("unreachable");
    }
    expect(result.current.state.data.content).toEqual([]);
    expect(result.current.state.data.page.totalElements).toBe(0);
  });

  const refused: Array<[string, Record<string, unknown>]> = [
    [
      "an action outside the contract",
      { content: [created({ action: "CASE_ARCHIVED" })] },
    ],
    [
      "a reason code that belongs to another action",
      { content: [created({ reasonCode: "CASE_RESOLUTION_COMPLETED" })] },
    ],
    [
      "a creation that did not create an open case",
      { content: [created({ afterSummary: { caseStatus: "CLOSED" } })] },
    ],
    [
      "a nanosecond changedAt the mapper could not have written",
      { content: [created({ changedAt: "2026-03-08T09:10:11.123456789Z" })] },
    ],
    [
      "an entry carrying a field outside the contract",
      { content: [{ ...created(), actorId: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f" }] },
    ],
    [
      "page metadata that contradicts its own arithmetic",
      { page: pageMetadata({ totalElements: 1, totalPages: 4, last: true }) },
    ],
  ];

  it.each(refused)("refuses a page carrying %s", async (_label, overrides) => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(calls[0], { ...auditBody(), ...overrides });

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "invalid-response" });
    });
  });

  it("refuses a page belonging to another case", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    // A page of another case's audit trail is a disclosure, not a display
    // quirk. One good entry is still a refused page.
    await answerWith(calls[0], auditBody([created()], {}, OTHER_CASE_ID));

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "invalid-response" });
    });
  });

  it("refuses the whole page when one entry of six is malformed", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(
      calls[0],
      auditBody(
        [created(), LINKED, REVIEW_STARTED, ASSIGNEE_RELEASED, RESOLVED, { ...NOTE_CREATED, metadata: {} }],
        { totalElements: 6 },
      ),
    );

    await waitFor(() => {
      expect(result.current.state).toEqual({ status: "invalid-response" });
    });
  });
});

describe("useCaseAuditLog disclosure boundary", () => {
  async function loadAll(): Promise<{ current: ReturnType<typeof useCaseAuditLog> }> {
    const { calls } = controlledFetch();
    const client = signedIn();
    const { result } = render(client);
    await settle();
    await answerWith(calls[0], auditBody(ALL_ACTIONS, { totalElements: 6 }));
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    return result;
  }

  it("returns a state, a position and three operations, and nothing else", async () => {
    const result = await loadAll();

    expect(Object.keys(result.current).sort()).toEqual([
      "page",
      "refresh",
      "refreshState",
      "retry",
      "setPage",
      "setSize",
      "size",
      "state",
    ]);
  });

  it("publishes the entries and the page metadata alone", async () => {
    const result = await loadAll();
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }

    // Two keys. `caseId` and `traceId` are not merely unrendered: they are not
    // present, so no serialization of this state arrives back at either.
    expect(Object.keys(result.current.state.data)).toEqual(["content", "page"]);
    for (const entry of result.current.state.data.content) {
      expect(Object.keys(entry)).toEqual(ENTRY_ORDER);
    }
  });

  it("carries no trace id, case id, envelope or token into the published state", async () => {
    const result = await loadAll();
    const serialized = JSON.stringify(result.current.state);

    expect(serialized).not.toContain(TRACE_ID);
    expect(serialized).not.toContain("traceId");
    expect(serialized).not.toContain(CASE_ID);
    expect(serialized).not.toContain("caseId");
    expect(serialized).not.toContain(ACCESS_TOKEN);
  });

  it("publishes a fresh page rather than the parsed envelope's own", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();
    const { result } = render(client);
    await settle();

    // The Backend key order reversed. What is published is the projection's
    // order, which is only possible if the object was rebuilt rather than
    // handed on.
    const reversed = Object.fromEntries(Object.entries(created()).reverse());
    await answerWith(calls[0], auditBody([reversed]));
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }

    expect(Object.keys(reversed)).not.toEqual(ENTRY_ORDER);
    expect(Object.keys(result.current.state.data.content[0])).toEqual(ENTRY_ORDER);
  });

  it("keeps every nullable field exactly as the Backend sent it", async () => {
    const result = await loadAll();
    if (result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const entries = result.current.state.data.content;

    expect(entries[0].beforeSummary).toBeNull();
    expect(entries[3].afterSummary).toEqual({
      caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      assigneeRef: null,
    });
    expect(entries[5].beforeSummary).toBeNull();
    expect(entries[5].afterSummary).toBeNull();
    expect(entries[5].metadata).toEqual({ noteId: NOTE_ID });
  });

  it("carries nothing of a failed response into the published state", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();

    const { result } = render(client);
    await settle();
    await answerWith(
      calls[0],
      { code: "CASE_NOT_FOUND", message: "no such case", traceId: TRACE_ID },
      404,
    );
    await waitFor(() => {
      expect(result.current.state.status).toBe("not-found");
    });

    expect(JSON.stringify(result.current.state)).toBe('{"status":"not-found"}');
  });
});

describe("useCaseAuditLog projection isolation", () => {
  it("does not let an edit to a published page reach the next subscription", async () => {
    const { calls, spy } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();

    calls[0].respond(200);
    calls[0].resolveJson(auditBody(ALL_ACTIONS, { totalElements: 6 }));
    await flushMicrotasks(24);
    await waitFor(() => {
      expect(view.result.current.state.status).toBe("success");
    });
    if (view.result.current.state.status !== "success") {
      throw new Error("unreachable");
    }

    // A consumer writing into the record it was handed. React state is not
    // immutable at runtime, so this really is possible; what must not happen is
    // the edit reaching the flight's own memory of the answer.
    const delivered = view.result.current.state.data;
    (delivered.content as unknown as Record<string, unknown>[])[0].action = "TAMPERED";
    Object.assign(delivered.content[2].beforeSummary ?? {}, { caseStatus: "CLOSED" });
    Object.assign(delivered.content[5].metadata, { noteId: OTHER_CASE_ID });
    (delivered.page as unknown as Record<string, unknown>).totalElements = 999;

    // A cleanup immediately followed by a re-subscription to the same request:
    // the shape of StrictMode's replay, and the path a stored answer is replayed
    // along.
    view.rerender(null);
    view.rerender(CASE_ID);
    await flushMicrotasks(24);

    await waitFor(() => {
      expect(view.result.current.state.status).toBe("success");
    });
    if (view.result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    expect(view.result.current.state.data.content[0].action).toBe("CASE_CREATED");
    expect(view.result.current.state.data.content[2].beforeSummary).toEqual({
      caseStatus: "OPEN",
      assigneeRef: null,
    });
    expect(view.result.current.state.data.content[5].metadata).toEqual({ noteId: NOTE_ID });
    expect(view.result.current.state.data.page.totalElements).toBe(6);
    // Still one network call: the answer was replayed, not re-fetched.
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("hands a replayed subscription a page of its own, equal to the first", async () => {
    const { calls } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();

    calls[0].respond(200);
    calls[0].resolveJson(auditBody(ALL_ACTIONS, { totalElements: 6 }));
    await flushMicrotasks(24);
    await waitFor(() => {
      expect(view.result.current.state.status).toBe("success");
    });
    if (view.result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const first = view.result.current.state.data;

    view.rerender(null);
    view.rerender(CASE_ID);
    await flushMicrotasks(24);
    await waitFor(() => {
      expect(view.result.current.state.status).toBe("success");
    });
    if (view.result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const second = view.result.current.state.data;

    expect(second).toEqual(first);
    expect(second).not.toBe(first);
    expect(second.content).not.toBe(first.content);
    expect(second.content[0]).not.toBe(first.content[0]);
    expect(second.content[2].beforeSummary).not.toBe(first.content[2].beforeSummary);
    expect(second.page).not.toBe(first.page);
  });

  it("keeps raw, stored and three subscriber deliveries isolated after nested mutation", async () => {
    const rawBefore = { caseStatus: "OPEN", assigneeRef: null };
    const rawAfter = { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_A };
    const rawWorkflowMetadata: Record<string, unknown> = {};
    const rawWorkflow: Record<string, unknown> = {
      action: "CASE_STATUS_CHANGED",
      reasonCode: "CASE_REVIEW_STARTED",
      actorType: "USER",
      changedAt: "2026-03-09T00:01:02.000002Z",
      beforeSummary: rawBefore,
      afterSummary: rawAfter,
      metadata: rawWorkflowMetadata,
    };
    const rawNoteMetadata = { noteId: NOTE_ID };
    const rawNote: Record<string, unknown> = {
      action: "CASE_NOTE_CREATED",
      reasonCode: "CASE_INVESTIGATION_NOTE_ADDED",
      actorType: "USER",
      changedAt: "2026-03-10T05:06:07.000000Z",
      beforeSummary: null,
      afterSummary: null,
      metadata: rawNoteMetadata,
    };
    const rawContent: Record<string, unknown>[] = [rawWorkflow, rawNote];
    const rawEnvelope = auditBody(rawContent, { totalElements: 2 });
    const rawPage = rawEnvelope.page as Record<string, unknown>;

    const { calls, spy } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();
    const publishesBefore = successfulPublishCount();

    await act(async () => {
      calls[0].respond(200);
      calls[0].resolveJson(rawEnvelope);
      await flushMicrotasks(FULL_FLUSH);
    });
    expect(view.result.current.state.status).toBe("success");
    expect(successfulPublishCount()).toBe(publishesBefore + 1);
    if (view.result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const firstState = view.result.current.state;
    const first = firstState.data;
    const firstItem = first.content[0];
    const firstBefore = firstItem.beforeSummary;
    const firstAfter = firstItem.afterSummary;
    const firstMetadata = first.content[1].metadata;

    expect(firstState).not.toBe(rawEnvelope);
    expect(first).not.toBe(rawEnvelope);
    expect(first.content).not.toBe(rawContent);
    expect(first.page).not.toBe(rawPage);
    expect(firstItem).not.toBe(rawWorkflow);
    expect(firstBefore).not.toBe(rawBefore);
    expect(firstAfter).not.toBe(rawAfter);
    expect(firstMetadata).not.toBe(rawNoteMetadata);

    // Mutate every raw layer after validation and every mutable layer of the
    // first delivery. Neither set of edits may reach the retained outcome.
    rawEnvelope.caseId = OTHER_CASE_ID;
    rawEnvelope.traceId = "trace_mutated_after_projection";
    rawEnvelope.unknownAuditField = "raw_unknown";
    rawContent.push(created());
    rawWorkflow.action = "RAW_TAMPERED";
    rawBefore.caseStatus = "CLOSED";
    rawAfter.caseStatus = "CLOSED";
    rawWorkflowMetadata.rawOnly = true;
    rawNoteMetadata.noteId = OTHER_CASE_ID;
    rawPage.totalElements = 999;

    (first.content as unknown as Record<string, unknown>[]).splice(0, 1);
    (firstItem as unknown as Record<string, unknown>).action = "FIRST_TAMPERED";
    Object.assign(firstBefore ?? {}, { caseStatus: "CLOSED" });
    Object.assign(firstAfter ?? {}, { assigneeRef: OTHER_CASE_ID });
    Object.assign(firstMetadata, { noteId: OTHER_CASE_ID, deliveredOnly: true });
    (first.page as unknown as Record<string, unknown>).totalElements = 777;

    view.rerender(null);
    view.rerender(CASE_ID);
    await flushMicrotasks(FULL_FLUSH);
    expect(view.result.current.state.status).toBe("success");
    expect(successfulPublishCount()).toBe(publishesBefore + 2);
    if (view.result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const secondState = view.result.current.state;
    const second = secondState.data;
    expect(second.content).toHaveLength(2);
    expect(second.content[0].action).toBe("CASE_STATUS_CHANGED");
    expect(second.content[0].beforeSummary).toEqual({ caseStatus: "OPEN", assigneeRef: null });
    expect(second.content[0].afterSummary).toEqual({
      caseStatus: "IN_REVIEW",
      assigneeRef: ASSIGNEE_A,
    });
    expect(second.content[1].metadata).toEqual({ noteId: NOTE_ID });
    expect(second.page.totalElements).toBe(2);

    expect(secondState).not.toBe(firstState);
    expect(second).not.toBe(first);
    expect(second.content).not.toBe(first.content);
    expect(second.page).not.toBe(first.page);
    expect(second.content[0]).not.toBe(firstItem);
    expect(second.content[0].beforeSummary).not.toBe(firstBefore);
    expect(second.content[0].afterSummary).not.toBe(firstAfter);
    expect(second.content[1].metadata).not.toBe(firstMetadata);
    expect(second.content).not.toBe(rawContent);
    expect(second.page).not.toBe(rawPage);
    expect(second.content[0]).not.toBe(rawWorkflow);
    expect(second.content[0].beforeSummary).not.toBe(rawBefore);
    expect(second.content[0].afterSummary).not.toBe(rawAfter);
    expect(second.content[1].metadata).not.toBe(rawNoteMetadata);

    const secondItem = second.content[0];
    const secondBefore = secondItem.beforeSummary;
    const secondAfter = secondItem.afterSummary;
    const secondMetadata = second.content[1].metadata;
    (second.content as unknown as Record<string, unknown>[]).reverse();
    (secondItem as unknown as Record<string, unknown>).action = "SECOND_TAMPERED";
    Object.assign(secondBefore ?? {}, { caseStatus: "CLOSED" });
    Object.assign(secondAfter ?? {}, { assigneeRef: OTHER_CASE_ID });
    Object.assign(secondMetadata, { noteId: OTHER_CASE_ID, replayOnly: true });
    (second.page as unknown as Record<string, unknown>).totalPages = 99;

    view.rerender(null);
    view.rerender(CASE_ID);
    await flushMicrotasks(FULL_FLUSH);
    expect(view.result.current.state.status).toBe("success");
    expect(successfulPublishCount()).toBe(publishesBefore + 3);
    if (view.result.current.state.status !== "success") {
      throw new Error("unreachable");
    }
    const thirdState = view.result.current.state;
    const third = thirdState.data;
    expect(third.content).toHaveLength(2);
    expect(third.content[0].action).toBe("CASE_STATUS_CHANGED");
    expect(third.content[0].beforeSummary).toEqual({ caseStatus: "OPEN", assigneeRef: null });
    expect(third.content[0].afterSummary).toEqual({
      caseStatus: "IN_REVIEW",
      assigneeRef: ASSIGNEE_A,
    });
    expect(third.content[1].metadata).toEqual({ noteId: NOTE_ID });
    expect(third.page).toEqual({
      number: 0,
      size: 20,
      totalElements: 2,
      totalPages: 1,
      first: true,
      last: true,
    });

    expect(thirdState).not.toBe(secondState);
    expect(third).not.toBe(second);
    expect(third.content).not.toBe(second.content);
    expect(third.page).not.toBe(second.page);
    expect(third.content[0]).not.toBe(secondItem);
    expect(third.content[0].beforeSummary).not.toBe(secondBefore);
    expect(third.content[0].afterSummary).not.toBe(secondAfter);
    expect(third.content[1].metadata).not.toBe(secondMetadata);

    for (const deliveredState of [secondState, thirdState]) {
      const serialized = JSON.stringify(deliveredState);
      expect(serialized).not.toContain(TRACE_ID);
      expect(serialized).not.toContain("trace_mutated_after_projection");
      expect(serialized).not.toContain("unknownAuditField");
      const ownKeys = recursiveOwnKeys(deliveredState);
      for (const forbiddenKey of ["traceId", "caseId", "unknownAuditField", "message", "body", "headers"]) {
        expect(ownKeys.has(forbiddenKey)).toBe(false);
      }
      expect(recursiveContainsError(deliveredState)).toBe(false);
    }
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("forgets a settled answer once the last subscription is gone, and asks again", async () => {
    const { calls, spy } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();

    calls[0].respond(200);
    calls[0].resolveJson(auditBody());
    await flushMicrotasks(24);
    view.unmount();
    await flushMicrotasks(24);

    const second = render(client);
    await settle();

    expect(spy).toHaveBeenCalledTimes(2);
    expect(second.result.current.state).toEqual({ status: "loading" });
  });
});

describe("useCaseAuditLog lazy terminal outcome", () => {
  it("uses the raw DTO once for validation and once for a live stored projection", async () => {
    let changedAtReads = 0;
    const { calls, spy } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();

    await act(async () => {
      calls[0].respond(200);
      calls[0].resolveJson(
        auditBody([projectionObservedEntry(() => {
          changedAtReads += 1;
        })]),
      );
      await flushMicrotasks(FULL_FLUSH);
    });

    expect(view.result.current.state.status).toBe("success");
    expect(changedAtReads).toBe(2);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("does not project a late success after the final subscriber released the flight", async () => {
    let changedAtReads = 0;
    const { calls, spy } = stagedFetch();
    const client = signedIn();
    const view = render(client);
    await settle();
    expect(spy).toHaveBeenCalledTimes(1);

    view.unmount();
    await flushMicrotasks(FULL_FLUSH);
    const writesAfterRelease = publisher.writes.length;

    calls[0].respond(200);
    calls[0].resolveJson(
      auditBody([projectionObservedEntry(() => {
        changedAtReads += 1;
      })]),
    );
    await flushMicrotasks(FULL_FLUSH);

    // One validator read is the positive proof that the ignored transport did
    // resolve. The second read belongs to the Hook projection and stays zero.
    expect(changedAtReads).toBe(1);
    expect(publisher.writes).toHaveLength(writesAfterRelease);
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("does not classify a late rejection after release even when abort is ignored", async () => {
    const originalHasInstance = Object.getOwnPropertyDescriptor(TimeoutError, Symbol.hasInstance);
    const classificationChecks = vi.fn(() => false);
    Object.defineProperty(TimeoutError, Symbol.hasInstance, {
      configurable: true,
      value: classificationChecks,
    });

    try {
      // Positive control: a live failure crosses the real classification
      // boundary and asks whether it is a TimeoutError.
      const liveFetch = controlledFetch();
      const liveClient = signedIn();
      const live = render(liveClient);
      await settle();
      await act(async () => {
        liveFetch.calls[0].fail(new TypeError("live network failure"));
        await flushMicrotasks(FULL_FLUSH);
      });
      expect(live.result.current.state).toEqual({ status: "network-error" });
      expect(classificationChecks).toHaveBeenCalledTimes(1);
      live.unmount();
      await flushMicrotasks(FULL_FLUSH);
      classificationChecks.mockClear();

      // Keep the external signal un-aborted so phase, not signal state, is what
      // rejects this continuation after the release microtask.
      const releasedFetch = controlledFetch();
      const releasedClient = signedIn();
      const released = render(releasedClient);
      await settle();
      const ignoredAbort = vi
        .spyOn(AbortController.prototype, "abort")
        .mockImplementation(() => undefined);
      released.unmount();
      await flushMicrotasks(FULL_FLUSH);
      expect(ignoredAbort).toHaveBeenCalled();
      ignoredAbort.mockRestore();
      const writesAfterRelease = publisher.writes.length;

      await act(async () => {
        releasedFetch.calls[0].fail(new TypeError("released raw network failure"));
        await flushMicrotasks(FULL_FLUSH);
      });
      expect(classificationChecks).not.toHaveBeenCalled();
      expect(publisher.writes).toHaveLength(writesAfterRelease);
      expect(JSON.stringify(publisher.writes)).not.toContain("released raw network failure");
      expect(releasedFetch.spy).toHaveBeenCalledTimes(1);
    } finally {
      if (originalHasInstance === undefined) {
        Reflect.deleteProperty(TimeoutError, Symbol.hasInstance);
      } else {
        Object.defineProperty(TimeoutError, Symbol.hasInstance, originalHasInstance);
      }
    }
  });

  it("keeps the first stored outcome when a transport attempts a duplicate settle", async () => {
    type AuditResult = Awaited<ReturnType<typeof caseAuditApi.fetchCaseAuditList>>;
    let continueWithSuccess: ((result: AuditResult) => void) | null = null;
    const duplicateContinuation = {
      then: (onSuccess: (result: AuditResult) => void): Promise<void> => {
        continueWithSuccess = onSuccess;
        return Promise.resolve();
      },
    };
    const apiSpy = vi
      .spyOn(caseAuditApi, "fetchCaseAuditList")
      .mockReturnValue(duplicateContinuation as unknown as Promise<AuditResult>);
    const client = signedIn();
    const view = render(client);
    await settle();
    const publishesBefore = successfulPublishCount();
    expect(apiSpy).toHaveBeenCalledTimes(1);
    if (continueWithSuccess === null) {
      throw new Error("The Hook did not install its success continuation.");
    }

    await act(async () => {
      continueWithSuccess?.({
        data: auditBody([created()]) as unknown as AuditResult["data"],
        traceId: TRACE_ID,
      });
      await flushMicrotasks(FULL_FLUSH);
    });
    expect(view.result.current.state.status).toBe("success");
    const firstState = view.result.current.state;
    expect(successfulPublishCount()).toBe(publishesBefore + 1);

    let duplicatePayloadReads = 0;
    const duplicateResult: Record<string, unknown> = { traceId: "trace_duplicate_must_not_survive" };
    Object.defineProperty(duplicateResult, "data", {
      configurable: true,
      get: () => {
        duplicatePayloadReads += 1;
        return auditBody([created({ action: "CASE_NOTE_CREATED" })]);
      },
    });
    await act(async () => {
      continueWithSuccess?.(duplicateResult as unknown as AuditResult);
      await flushMicrotasks(FULL_FLUSH);
    });

    expect(duplicatePayloadReads).toBe(0);
    expect(view.result.current.state).toBe(firstState);
    expect(successfulPublishCount()).toBe(publishesBefore + 1);
    expect(apiSpy).toHaveBeenCalledTimes(1);
    expect(JSON.stringify(view.result.current.state)).not.toContain("trace_duplicate_must_not_survive");
  });
});

/**
 * The window between a subscription ending and the answer it was waiting for
 * arriving.
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
 * first time a hop is added or removed.
 */
const CLEANUP_TURNS = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11] as const;

/** Comfortably more turns than the transport's whole chain takes. */
const FULL_FLUSH = 24;

describe("useCaseAuditLog publishes nothing from a subscription that is gone", () => {
  it("publishes no page when the section goes away after the answer settled", async () => {
    for (const turns of CLEANUP_TURNS) {
      const { calls, spy } = stagedFetch();
      const client = signedIn();
      const { unmount } = render(client);
      await settle();
      expect(spy).toHaveBeenCalledTimes(1);

      calls[0].respond(200);
      calls[0].resolveJson(auditBody());
      await flushMicrotasks(turns);

      unmount();
      const settledPublishes = publisher.writes.length;
      await flushMicrotasks(FULL_FLUSH);

      expect(
        publisher.writes,
        `unmounted ${String(turns)} microtasks after the answer settled`,
      ).toHaveLength(settledPublishes);
      publisher.writes.length = 0;
    }
  });

  it("publishes nothing from the subscription a different page replaced", async () => {
    for (const turns of CLEANUP_TURNS) {
      const { calls } = stagedFetch();
      const client = signedIn();
      const view = render(client);
      await settle();

      calls[0].respond(200);
      calls[0].resolveJson(auditBody([created()], { totalElements: 137, totalPages: 7, last: false }));
      await flushMicrotasks(turns);

      act(() => {
        view.result.current.setPage(4);
      });
      await flushMicrotasks(FULL_FLUSH);

      // Whatever the first page's answer did, the screen is on page 4 and is
      // loading it. It is never showing page 0's entries under page 4.
      expect(view.result.current.state, `page changed at turn ${String(turns)}`).toEqual({
        status: "loading",
      });
      expect(view.result.current.page).toBe(4);
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
      calls[0].resolveJson(auditBody());
      await flushMicrotasks(turns);

      if (publisher.writes.length > before) {
        // The answer was already published by a subscription that was still
        // live, so there is no replacement to observe at this turn.
        view.unmount();
        publisher.writes.length = 0;
        continue;
      }

      // Cleanup and re-subscription to the same request, in one synchronous
      // stack with no microtask between them. The subscription that was cleaned
      // up must stay silent, and the one that replaced it must still receive
      // this same answer: the request is shared, the permission to publish from
      // it is not.
      view.rerender(null);
      view.rerender(CASE_ID);
      await flushMicrotasks(FULL_FLUSH);

      await waitFor(() => {
        expect(view.result.current.state.status).toBe("success");
      });
      expect(spy, `re-subscribed at turn ${String(turns)}`).toHaveBeenCalledTimes(1);
      view.unmount();
      publisher.writes.length = 0;
    }
  });
});

/**
 * A session the test replaces directly, without going through the adapter.
 *
 * The provider publishes one frozen session object per session, and the hook
 * treats that identity as "the session this state belongs to". Driving the
 * context here is what lets a replacement be timed exactly against an answer
 * that is already in flight.
 */
let setAuthState: ((state: AuthState) => void) | null = null;

const INERT_CLIENT: AuthClient = {
  initialize: () => Promise.resolve({ session: null }),
  signIn: () => Promise.resolve(),
  completeSignIn: () => Promise.reject(new Error("not used")),
  signOut: () => Promise.resolve(),
  onSessionInvalidated: () => () => undefined,
};

function ControlledAuth({ children }: { readonly children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: "authenticated", session: SESSION });
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
  return renderHook((current: string | null) => useCaseAuditLog(current), {
    initialProps: caseId,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(StrictMode, null, createElement(ControlledAuth, null, children)),
  });
}

describe("useCaseAuditLog session replacement", () => {
  beforeEach(() => {
    adapter.client = createFakeAuthClient({
      initialSession: SESSION,
      accessToken: ACCESS_TOKEN,
    });
    setAuthState = null;
  });

  async function loadOnePage(
    calls: PendingCall[],
    result: { current: { state: { status: string } } },
  ): Promise<void> {
    await settle();
    await answerWith(calls[0], auditBody());
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
  }

  it("removes the trail the moment the session becomes unauthenticated", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await loadOnePage(calls, result);

    act(() => {
      setAuthState?.({ status: "unauthenticated" });
    });

    expect(result.current.state).toEqual({ status: "idle" });
  });

  it("removes the trail the moment sign-out starts", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await loadOnePage(calls, result);

    act(() => {
      setAuthState?.({ status: "signing-out" });
    });

    expect(result.current.state).toEqual({ status: "idle" });
  });

  it("does not show one session's trail under another session", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await loadOnePage(calls, result);

    act(() => {
      setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });

    expect(result.current.state).toEqual({ status: "loading" });
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

    await answerWith(calls[0], auditBody(ALL_ACTIONS, { totalElements: 6 }));

    expect(result.current.state).toEqual({ status: "loading" });
  });

  it("does not publish a retry answer after the session identity changes", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await settle();
    await answerWith(calls[0], {}, 503);
    await waitFor(() => expect(result.current.state.status).toBe("generic-error"));

    act(() => result.current.retry());
    await settle();
    act(() => {
      setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });
    await settle();
    expect(calls).toHaveLength(3);

    await answerWith(calls[1], auditBody());
    expect(result.current.state).toEqual({ status: "loading" });
  });

  it("returns the new session to the first page at the opening size", async () => {
    const { calls } = controlledFetch();
    const { result } = renderControlled();
    await loadOnePage(calls, result);

    act(() => {
      result.current.setSize(100);
    });
    await settle();

    act(() => {
      setAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });
    await settle();

    expect(result.current.page).toBe(0);
    expect(result.current.size).toBe(20);
    expect(new URL(calls[calls.length - 1].request.url).search).toBe(AUDIT_QUERY);
  });
});

describe("useCaseAuditLog authoritative background refresh", () => {
  it("keeps a newer user page when the older page-zero refresh settles", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], auditBody([created()]));

    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(2));
    act(() => view.result.current.setPage(1));
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[1].request.signal.aborted).toBe(true);
    expect(calls[2].request.signal.aborted).toBe(false);

    await answerWith(calls[1], auditBody([NOTE_CREATED]));
    expect(view.result.current.page).toBe(1);
    expect(calls[2].request.signal.aborted).toBe(false);
    await answerWith(calls[2], auditBody([created()], {
      number: 1,
      totalElements: 21,
      totalPages: 2,
      first: false,
      last: true,
    }));
    expect(view.result.current.page).toBe(1);
    if (view.result.current.state.status === "success") {
      expect(view.result.current.state.data.page.number).toBe(1);
      expect(view.result.current.state.data.content[0].action).toBe("CASE_CREATED");
    }
  });

  it("keeps a newer size and page-zero intent when an old-size refresh answers", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], auditBody([created()]));
    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(2));

    act(() => view.result.current.setSize(50));
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(calls[1].request.signal.aborted).toBe(true);
    expect(calls[2].request.signal.aborted).toBe(false);
    await answerWith(calls[1], auditBody([NOTE_CREATED]));
    expect(view.result.current.page).toBe(0);
    expect(view.result.current.size).toBe(50);
    expect(calls[2].request.signal.aborted).toBe(false);

    await answerWith(calls[2], auditBody([created()], { size: 50 }));
    if (view.result.current.state.status === "success") {
      expect(view.result.current.state.data.page.size).toBe(50);
      expect(view.result.current.state.data.content[0].action).toBe("CASE_CREATED");
    }
  });

  it("publishes only the latest user request across stale refresh success and failure", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], auditBody([created()]));
    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(2));
    act(() => view.result.current.setPage(1));
    await waitFor(() => expect(calls).toHaveLength(3));

    await answerWith(calls[2], auditBody([NOTE_CREATED], {
      number: 1,
      totalElements: 21,
      totalPages: 2,
      first: false,
      last: true,
    }));
    await act(async () => {
      calls[1].fail(new TypeError("STALE_AUDIT_REFRESH_ERROR"));
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(view.result.current.refreshState).toBe("idle");
    expect(view.result.current.page).toBe(1);
    if (view.result.current.state.status === "success") {
      expect(view.result.current.state.data.content[0].action).toBe("CASE_NOTE_CREATED");
    }
  });

  it("isolates a released refresh from combined pagination and case replacement", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], auditBody([created()]));
    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(2));
    act(() => view.result.current.setPage(1));
    await waitFor(() => expect(calls).toHaveLength(3));
    view.rerender(OTHER_CASE_ID);
    await waitFor(() => expect(calls).toHaveLength(4));
    expect(calls[1].request.signal.aborted).toBe(true);
    expect(calls[2].request.signal.aborted).toBe(true);

    await answerWith(calls[1], auditBody([NOTE_CREATED]));
    await answerWith(calls[2], auditBody([NOTE_CREATED], {
      number: 1,
      totalElements: 21,
      totalPages: 2,
      first: false,
      last: true,
    }));
    expect(view.result.current.page).toBe(0);
    expect(view.result.current.state).toEqual({ status: "loading" });
    await answerWith(calls[3], auditBody([created()], {}, OTHER_CASE_ID));
    if (view.result.current.state.status === "success") {
      expect(view.result.current.state.data.page.number).toBe(0);
      expect(view.result.current.state.data.content[0].action).toBe("CASE_CREATED");
    }
  });

  it("keeps the current trail while refreshing and returns to page zero", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], auditBody([created()]));

    act(() => view.result.current.setPage(1));
    await waitFor(() => expect(calls).toHaveLength(2));
    await answerWith(
      calls[1],
      auditBody([created({ changedAt: "2026-03-08T09:11:11.123456Z" })], {
        number: 1,
        totalElements: 21,
        totalPages: 2,
        first: false,
        last: true,
      }),
    );
    expect(view.result.current.page).toBe(1);

    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(3));
    expect(view.result.current.state.status).toBe("success");
    expect(view.result.current.refreshState).toBe("refreshing");
    await answerWith(calls[2], auditBody([NOTE_CREATED]));

    expect(view.result.current.page).toBe(0);
    expect(view.result.current.refreshState).toBe("idle");
    expect(calls).toHaveLength(3);
    if (view.result.current.state.status === "success") {
      expect(view.result.current.state.data.content[0].action).toBe("CASE_NOTE_CREATED");
    }
  });

  it("isolates a refresh failure from the visible trail and permits explicit refresh", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answerWith(calls[0], auditBody([created()]));
    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(2));
    await act(async () => {
      calls[1].fail(new TypeError("private"));
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(view.result.current.refreshState).toBe("failed");
    expect(view.result.current.state.status).toBe("success");
    act(() => view.result.current.refresh());
    await waitFor(() => expect(calls).toHaveLength(3));
  });
});
