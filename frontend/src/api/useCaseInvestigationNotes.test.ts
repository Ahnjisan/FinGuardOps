import { createElement, StrictMode, useEffect, useMemo, useState, type ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthClient, AuthSession } from "../auth/authClient";
import { AuthContext, type AuthContextValue } from "../auth/authContext";
import { AuthProvider } from "../auth/AuthProvider";
import type { AuthState } from "../auth/authState";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import * as noteApi from "./investigationNoteApi";
import {
  ForbiddenError,
  HttpError,
  TimeoutError,
} from "./errors";

const adapter = vi.hoisted(() => ({ client: null as unknown }));

vi.mock("../auth/oidcAuthClient", () => ({
  getOidcAuthClient: () => adapter.client,
}));

/** Records the hook's own snapshot writes, including writes to an inactive fiber. */
const publisher = vi.hoisted(() => ({ writes: [] as unknown[] }));

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

const { useCaseInvestigationNotes } = await import("./useCaseInvestigationNotes");

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const OTHER_CASE_ID = "6d3e2f10-8b9c-4d0e-8f01-2b3c4d5e6f71";
const NOTE_ID = "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60";
const OTHER_NOTE_ID = "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071";
const USER_REF = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
const TRACE_ID = "trace_demo_case_notes_hook_01";

const SESSION: AuthSession = {
  subject: USER_REF,
  displayName: "Local Analyst",
  roles: ["FDS_ANALYST"],
};

const SECOND_SESSION: AuthSession = {
  subject: "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60",
  displayName: "Second Analyst",
  roles: ["FDS_ANALYST"],
};

function note(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    noteId: NOTE_ID,
    caseId: CASE_ID,
    authorType: "USER",
    authorRef: USER_REF,
    content: "  first line\r\nsecond  line <b>plain</b> https://example.invalid  ",
    createdAt: "2026-09-02T00:00:00.123456Z",
    ...overrides,
  };
}

function page(
  items: readonly Record<string, unknown>[] = [note()],
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    items,
    page: {
      number: 0,
      size: 20,
      totalElements: items.length,
      totalPages: items.length === 0 ? 0 : 1,
      first: true,
      last: true,
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

function controlledFetch(): { readonly calls: PendingCall[]; readonly spy: ReturnType<typeof vi.fn> } {
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

interface StagedCall {
  readonly request: Request;
  respond: (status: number) => void;
  resolveJson: (value: unknown) => void;
  failFetch: (error: unknown) => void;
}

function stagedFetch(): { readonly calls: StagedCall[]; readonly spy: ReturnType<typeof vi.fn> } {
  const calls: StagedCall[] = [];
  const spy = vi.fn().mockImplementation((request: Request) => {
    let respond!: (status: number) => void;
    let resolveJson!: (value: unknown) => void;
    let failFetch!: (error: unknown) => void;
    const json = new Promise<unknown>((resolve) => {
      resolveJson = resolve;
    });
    json.catch(() => undefined);
    const response = new Promise<Response>((resolve, reject) => {
      failFetch = reject;
      respond = (status: number): void => {
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

function providerWrapper(client: AuthClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(StrictMode, null, createElement(AuthProvider, { client, children }));
}

interface HookProps {
  readonly caseId: string | null;
  readonly page: number;
  readonly size: number;
}

function render(client: AuthClient, props: HookProps = { caseId: CASE_ID, page: 0, size: 20 }) {
  return renderHook(
    (current: HookProps) =>
      useCaseInvestigationNotes(current.caseId, current.page, current.size),
    { initialProps: props, wrapper: providerWrapper(client) },
  );
}

function signedIn(accessToken: string | null = "notes.access.token"): FakeAuthClient {
  const client = createFakeAuthClient({ initialSession: SESSION, accessToken });
  adapter.client = client;
  return client;
}

async function settle(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function flushMicrotasks(turns = 24): Promise<void> {
  for (let turn = 0; turn < turns; turn += 1) {
    await Promise.resolve();
  }
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

function recursiveValues(value: unknown): Set<unknown> {
  const values = new Set<unknown>();
  const visited = new WeakSet<object>();
  const visit = (candidate: unknown): void => {
    values.add(candidate);
    if (typeof candidate !== "object" || candidate === null || visited.has(candidate)) {
      return;
    }
    visited.add(candidate);
    for (const key of Reflect.ownKeys(candidate)) {
      visit(Reflect.get(candidate, key));
    }
  };
  visit(value);
  return values;
}

async function answer(call: PendingCall, body: unknown, status = 200): Promise<void> {
  await act(async () => {
    call.settle(jsonResponse(body, { status }));
    await call.promise;
    await flushMicrotasks();
  });
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

describe("useCaseInvestigationNotes request identity", () => {
  it("fetches once under StrictMode with the exact initial target", async () => {
    const { calls, spy } = controlledFetch();
    const view = render(signedIn());
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(calls[0].request.url).toBe(
      `http://localhost:8080/api/v1/cases/${CASE_ID}/notes?page=0&size=20&sort=createdAt%2Casc`,
    );
    expect(view.result.current.state).toEqual({ status: "loading" });

    await answer(calls[0], page());
    expect(view.result.current.state.status).toBe("success");
    expect(Object.keys(view.result.current).sort()).toEqual(["retry", "state"]);
  });

  it("does no credential work without a session, canonical case, or valid position", async () => {
    for (const [client, props] of [
      [createFakeAuthClient(), { caseId: CASE_ID, page: 0, size: 20 }],
      [signedIn(), { caseId: null, page: 0, size: 20 }],
      [signedIn(), { caseId: OTHER_CASE_ID.toUpperCase(), page: 0, size: 20 }],
      [signedIn(), { caseId: CASE_ID, page: -1, size: 20 }],
      [signedIn(), { caseId: CASE_ID, page: 0, size: 101 }],
    ] as const) {
      const fetchSpy = vi.fn();
      vi.stubGlobal("fetch", fetchSpy);
      adapter.client = client;
      const view = render(client, props);
      await settle();
      expect(view.result.current.state).toEqual({ status: "idle" });
      expect(client.calls.authorizeRequest).toBe(0);
      expect(fetchSpy).not.toHaveBeenCalled();
      view.unmount();
      vi.unstubAllGlobals();
    }
  });

  it("clears stale content and aborts when page, size, case, or attempt changes", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await answer(calls[0], page());
    expect(view.result.current.state.status).toBe("success");

    view.rerender({ caseId: CASE_ID, page: 1, size: 20 });
    expect(view.result.current.state).toEqual({ status: "loading" });
    await settle();
    // The first request had already settled; there is nothing left to abort.
    expect(calls[0].request.signal.aborted).toBe(false);
    expect(calls[1].request.url).toContain("page=1&size=20&sort=createdAt%2Casc");

    view.rerender({ caseId: OTHER_CASE_ID, page: 0, size: 50 });
    expect(view.result.current.state).toEqual({ status: "loading" });
    await settle();
    expect(calls[1].request.signal.aborted).toBe(true);
    expect(calls[2].request.url).toContain(`/cases/${OTHER_CASE_ID}/notes?page=0&size=50`);
  });

  it("does not publish an independently resolved success from a superseded request", async () => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    view.rerender({ caseId: OTHER_CASE_ID, page: 0, size: 20 });
    await settle();

    await answer(calls[0], page());
    expect(view.result.current.state).toEqual({ status: "loading" });

    await answer(calls[1], page([note({ caseId: OTHER_CASE_ID })]));
    expect(view.result.current.state.status).toBe("success");
  });

  it.each([
    ["retryable failure", new TypeError("stale independent network failure")],
    ["403", new ForbiddenError("trace_stale_forbidden")],
    ["404", new HttpError(404)],
  ])("does not publish an independently rejected %s from a superseded request", async (_label, error) => {
    const { calls } = controlledFetch();
    const view = render(signedIn());
    await settle();
    view.rerender({ caseId: OTHER_CASE_ID, page: 0, size: 20 });
    await settle();

    await act(async () => {
      calls[0].fail(error);
      await calls[0].promise.catch(() => undefined);
      await flushMicrotasks();
    });
    expect(view.result.current.state).toEqual({ status: "loading" });

    await answer(calls[1], page([note({ caseId: OTHER_CASE_ID })]));
    expect(view.result.current.state.status).toBe("success");
    expect(JSON.stringify(view.result.current.state)).not.toMatch(
      /stale independent network failure|trace_stale_forbidden/,
    );
  });
});

describe("useCaseInvestigationNotes outcomes", () => {
  it.each([
    [403, "forbidden"],
    [404, "not-found"],
    [401, "idle"],
    [500, "generic-error"],
  ] as const)("classifies HTTP %s without response disclosure", async (status, expected) => {
    const { calls } = controlledFetch();
    const client = signedIn();
    const view = render(client);
    await settle();
    await answer(
      calls[0],
      { code: "PRIVATE", message: "private backend message", traceId: "trace_private" },
      status,
    );

    await waitFor(() => {
      expect(view.result.current.state.status).toBe(expected);
    });
    expect(JSON.stringify(view.result.current.state)).not.toMatch(
      /PRIVATE|private backend message|trace_private/,
    );
    expect(client.calls.invalidateIfCurrent).toBe(status === 401 ? 1 : 0);
  });

  it("keeps 403/404 settled and retries only retryable failures on explicit calls", async () => {
    const { calls, spy } = controlledFetch();
    const view = render(signedIn());
    await settle();
    await act(async () => {
      calls[0].fail(new TypeError("offline"));
      await flushMicrotasks();
    });
    expect(view.result.current.state).toEqual({ status: "network-error" });
    await flushMicrotasks();
    expect(spy).toHaveBeenCalledTimes(1);

    act(() => view.result.current.retry());
    expect(view.result.current.state).toEqual({ status: "loading" });
    await settle();
    expect(spy).toHaveBeenCalledTimes(2);
    act(() => view.result.current.retry());
    expect(spy).toHaveBeenCalledTimes(2);

    await answer(calls[1], { code: "DENIED", message: "hidden", traceId: "trace_hidden" }, 403);
    act(() => view.result.current.retry());
    await settle();
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("turns the real transport deadline into one timeout outcome with one fetch", async () => {
    vi.useFakeTimers();
    const { spy } = controlledFetch();
    const view = render(signedIn());
    try {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0);
      });
      expect(spy).toHaveBeenCalledTimes(1);

      await act(async () => {
        await vi.advanceTimersByTimeAsync(5_000);
      });
      expect(view.result.current.state).toEqual({ status: "timeout" });
      await act(async () => {
        await vi.advanceTimersByTimeAsync(10_000);
      });
      expect(spy).toHaveBeenCalledTimes(1);
    } finally {
      view.unmount();
      vi.useRealTimers();
    }
  });

  it("publishes an empty page and refuses mixed-case pages as invalid-response", async () => {
    const first = controlledFetch();
    const empty = render(signedIn());
    await settle();
    await answer(first.calls[0], page([]));
    expect(empty.result.current.state).toEqual({
      status: "empty",
      data: {
        items: [],
        page: {
          number: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          first: true,
          last: true,
        },
      },
    });
    empty.unmount();
    await flushMicrotasks();
    vi.unstubAllGlobals();

    const second = controlledFetch();
    const mixed = render(signedIn());
    await settle();
    await answer(
      second.calls[0],
      page([
        note(),
        note({ noteId: OTHER_NOTE_ID, caseId: OTHER_CASE_ID }),
      ], { totalElements: 2 }),
    );
    expect(mixed.result.current.state).toEqual({ status: "invalid-response" });
  });
});

describe("useCaseInvestigationNotes stored and delivered projections", () => {
  it("drops envelope-only data and isolates raw, stored, and three nested deliveries", async () => {
    const rawEnvelopeSentinel = "raw_envelope_only_sentinel";
    const credentialSentinel = "synthetic_credential_token_sentinel";
    const expectedView = {
      items: [
        {
          noteId: NOTE_ID,
          authorType: "USER",
          authorRef: USER_REF,
          content: "  first line\r\nsecond  line <b>plain</b> https://example.invalid  ",
          createdAt: "2026-09-02T00:00:00.123456Z",
        },
      ],
      page: {
        number: 0,
        size: 20,
        totalElements: 1,
        totalPages: 1,
        first: true,
        last: true,
      },
    } as const;
    const rawItem = note();
    const rawItems = [rawItem];
    const rawEnvelope = page(rawItems);
    const rawPage = rawEnvelope.page as Record<string, unknown>;
    const { calls, spy } = stagedFetch();
    const view = render(signedIn());
    await settle();
    await act(async () => {
      calls[0].respond(200);
      calls[0].resolveJson(rawEnvelope);
      await flushMicrotasks();
    });
    if (view.result.current.state.status !== "success") {
      throw new Error("Expected the first delivery.");
    }
    const firstState = view.result.current.state;
    const first = firstState.data;
    const firstItems = first.items;
    const firstItem = first.items[0];
    const firstPage = first.page;
    expect(first).toEqual(expectedView);
    expect(Object.keys(first.items[0])).toEqual([
      "noteId",
      "authorType",
      "authorRef",
      "content",
      "createdAt",
    ]);
    expect(first.items).not.toBe(rawItems);
    expect(first.items[0]).not.toBe(rawItem);
    expect(first.page).not.toBe(rawPage);
    expect(JSON.stringify(first)).not.toMatch(/caseId|traceId/);

    rawEnvelope.traceId = "trace_mutated_after_projection";
    rawEnvelope.rawEnvelopeOnlyKey = rawEnvelopeSentinel;
    rawEnvelope.headers = { Authorization: credentialSentinel };
    rawItem.content = "raw mutation";
    rawItem.token = credentialSentinel;
    rawItems.push(note({ noteId: OTHER_NOTE_ID }));
    rawPage.totalElements = 99;
    rawPage.rawPageOnlyKey = rawEnvelopeSentinel;
    (firstState as unknown as Record<string, unknown>).status = "empty";
    (firstItems as unknown as Array<Record<string, unknown>>).push({ content: "array mutation" });
    (firstItem as unknown as Record<string, unknown>).content = "first mutation";
    (firstPage as unknown as Record<string, unknown>).totalElements = 88;
    (first as unknown as Record<string, unknown>).page = { ...firstPage };

    view.rerender({ caseId: null, page: 0, size: 20 });
    view.rerender({ caseId: CASE_ID, page: 0, size: 20 });
    await flushMicrotasks();
    if (view.result.current.state.status !== "success") {
      throw new Error("Expected the replayed delivery.");
    }
    const secondState = view.result.current.state;
    const second = secondState.data;
    const secondItems = second.items;
    const secondItem = second.items[0];
    const secondPage = second.page;
    expect(second).toEqual(expectedView);
    expect(second.items).toHaveLength(1);
    expect(second.items[0].content).toContain("first line");
    expect(second.page.totalElements).toBe(1);
    expect(secondState).not.toBe(firstState);
    expect(second).not.toBe(first);
    expect(second.items).not.toBe(firstItems);
    expect(second.items[0]).not.toBe(firstItem);
    expect(second.page).not.toBe(firstPage);

    (secondState as unknown as Record<string, unknown>).status = "empty";
    (secondItems as unknown as Array<Record<string, unknown>>).push({ content: "array mutation" });
    (secondItem as unknown as Record<string, unknown>).content = "second mutation";
    (secondPage as unknown as Record<string, unknown>).totalPages = 77;
    (second as unknown as Record<string, unknown>).page = { ...secondPage };
    view.rerender({ caseId: null, page: 0, size: 20 });
    view.rerender({ caseId: CASE_ID, page: 0, size: 20 });
    await flushMicrotasks();
    if (view.result.current.state.status !== "success") {
      throw new Error("Expected the third delivery.");
    }
    const thirdState = view.result.current.state;
    const third = thirdState.data;
    expect(third.items[0].content).toContain("first line");
    expect(third.page.totalPages).toBe(1);
    expect(thirdState).not.toBe(firstState);
    expect(thirdState).not.toBe(secondState);
    expect(third).not.toBe(first);
    expect(third).not.toBe(second);
    expect(third.items).not.toBe(firstItems);
    expect(third.items).not.toBe(secondItems);
    expect(third.page).not.toBe(firstPage);
    expect(third.page).not.toBe(secondPage);
    expect(third.page).not.toBe(first.page);
    expect(third.page).not.toBe(second.page);
    expect(third.items[0]).not.toBe(firstItem);
    expect(third.items[0]).not.toBe(secondItem);
    expect(third).toEqual(expectedView);
    for (const deliveredState of [firstState, secondState, thirdState]) {
      const serialized = JSON.stringify(deliveredState);
      expect(serialized).not.toMatch(/traceId|caseId|rawEnvelopeOnlyKey|rawPageOnlyKey/);
      expect(serialized).not.toContain(rawEnvelopeSentinel);
      expect(serialized).not.toContain(credentialSentinel);
      const keys = recursiveOwnKeys(deliveredState);
      for (const forbiddenKey of [
        "traceId",
        "caseId",
        "rawEnvelopeOnlyKey",
        "rawPageOnlyKey",
        "headers",
        "body",
        "credential",
        "token",
        "authorization",
      ]) {
        expect(keys.has(forbiddenKey)).toBe(false);
      }
      const values = recursiveValues(deliveredState);
      expect(values.has(rawEnvelopeSentinel)).toBe(false);
      expect(values.has(credentialSentinel)).toBe(false);
      expect([...values].some((value) => value instanceof Error || value instanceof Response)).toBe(
        false,
      );
    }
    expect(spy).toHaveBeenCalledTimes(1);
  });

  it("forgets a settled outcome after the final subscriber is released", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();
    const first = render(client);
    await settle();
    await answer(calls[0], page());
    first.unmount();
    await flushMicrotasks();

    const second = render(client);
    await settle();
    expect(second.result.current.state).toEqual({ status: "loading" });
    expect(spy).toHaveBeenCalledTimes(2);
  });
});

describe("useCaseInvestigationNotes lazy terminal lifecycle", () => {
  it.each([
    {
      label: "success",
      status: "success",
      transport: { kind: "response", status: 200, body: page() },
    },
    {
      label: "404",
      status: "not-found",
      transport: {
        kind: "response",
        status: 404,
        body: { code: "RAW_404", message: "raw not-found body", traceId: "trace_raw_404" },
      },
    },
    {
      label: "403",
      status: "forbidden",
      transport: {
        kind: "response",
        status: 403,
        body: { code: "RAW_403", message: "raw forbidden body", traceId: "trace_raw_403" },
      },
    },
    {
      label: "network",
      status: "network-error",
      transport: { kind: "network" },
    },
    {
      label: "timeout",
      status: "timeout",
      transport: { kind: "timeout" },
    },
    {
      label: "invalid response",
      status: "invalid-response",
      transport: {
        kind: "response",
        status: 200,
        body: { rawEnvelopeOnlyKey: "raw invalid response body", traceId: "trace_raw_invalid" },
      },
    },
    {
      label: "generic error",
      status: "generic-error",
      transport: {
        kind: "response",
        status: 500,
        body: { code: "RAW_500", message: "raw generic body", traceId: "trace_raw_500" },
      },
    },
  ] as const)(
    "stores and replays a zero-listener $label terminal outcome exactly once",
    async ({ status, transport }) => {
      type Result = Awaited<ReturnType<typeof noteApi.fetchInvestigationNoteList>>;
      const originalFetchNotes = noteApi.fetchInvestigationNoteList;
      let releaseContinuation: (() => void) | null = null;
      const apiSpy = vi
        .spyOn(noteApi, "fetchInvestigationNoteList")
        .mockImplementation((...args) => {
          const realTransport = originalFetchNotes(...args);
          return {
            then: (
              onSuccess: (result: Result) => void,
              onFailure: (error: unknown) => void,
            ): Promise<void> => {
              realTransport.then(
                (result) => {
                  releaseContinuation = () => onSuccess(result);
                },
                (error: unknown) => {
                  releaseContinuation = () => onFailure(error);
                },
              );
              return Promise.resolve();
            },
          } as unknown as Promise<Result>;
        });
      if (transport.kind === "timeout") {
        vi.useFakeTimers();
      }
      const { calls, spy: fetchSpy } = controlledFetch();
      const view = render(signedIn());
      try {
        await settle();
        expect(apiSpy).toHaveBeenCalledTimes(1);
        expect(fetchSpy).toHaveBeenCalledTimes(1);
        expect(calls).toHaveLength(1);

        if (transport.kind === "network") {
          calls[0].fail(new TypeError("raw network sentinel"));
          await calls[0].promise.catch(() => undefined);
        } else if (transport.kind === "timeout") {
          vi.advanceTimersByTime(5_000);
        } else {
          calls[0].settle(jsonResponse(transport.body, { status: transport.status }));
          await calls[0].promise;
        }

        let continuationTurns = 0;
        while (releaseContinuation === null && continuationTurns < 24) {
          continuationTurns += 1;
          await Promise.resolve();
        }
        expect(continuationTurns).toBeGreaterThan(0);
        expect(releaseContinuation).not.toBeNull();
        const publishTerminal = releaseContinuation as unknown as () => void;

        // A real fetch has settled and the wrapper has captured the Hook
        // continuation. Cleanup removes the old listener and queues release;
        // zero-listener settlement and same-key setup then happen synchronously
        // before that release microtask can run.
        expect(view.result.current.state).toEqual({ status: "loading" });
        view.rerender({ caseId: null, page: 0, size: 20 });
        const writesAfterCleanup = publisher.writes.length;
        publishTerminal();
        expect(publisher.writes).toHaveLength(writesAfterCleanup);
        view.rerender({ caseId: CASE_ID, page: 0, size: 20 });
        await act(async () => flushMicrotasks());

        expect(view.result.current.state.status).toBe(status);
        if (status === "success") {
          expect(view.result.current.state).toEqual({
            status: "success",
            data: {
              items: [
                {
                  noteId: NOTE_ID,
                  authorType: "USER",
                  authorRef: USER_REF,
                  content: "  first line\r\nsecond  line <b>plain</b> https://example.invalid  ",
                  createdAt: "2026-09-02T00:00:00.123456Z",
                },
              ],
              page: {
                number: 0,
                size: 20,
                totalElements: 1,
                totalPages: 1,
                first: true,
                last: true,
              },
            },
          });
        } else {
          expect(view.result.current.state).toEqual({ status });
        }
        const terminalWrites = publisher.writes.slice(writesAfterCleanup).filter((write) => {
          if (typeof write !== "object" || write === null || !("state" in write)) {
            return false;
          }
          const state = write.state;
          return (
            typeof state === "object" &&
            state !== null &&
            "status" in state &&
            state.status === status
          );
        });
        expect(terminalWrites).toHaveLength(1);
        expect(JSON.stringify(view.result.current.state)).not.toMatch(
          /RAW_|raw network sentinel|raw .* body|trace_raw|traceId|caseId|credential|token|headers|body/,
        );
        expect(apiSpy).toHaveBeenCalledTimes(1);
        expect(fetchSpy).toHaveBeenCalledTimes(1);

        view.unmount();
        await flushMicrotasks();
        const writesAfterRelease = publisher.writes.length;
        publishTerminal();
        await flushMicrotasks();
        expect(publisher.writes).toHaveLength(writesAfterRelease);

        const remount = render(adapter.client as AuthClient);
        await settle();
        expect(apiSpy).toHaveBeenCalledTimes(2);
        expect(fetchSpy).toHaveBeenCalledTimes(2);
        expect(remount.result.current.state).toEqual({ status: "loading" });
        remount.unmount();
      } finally {
        view.unmount();
        if (transport.kind === "timeout") {
          vi.useRealTimers();
        }
      }
    },
  );

  it("does not classify a released rejection when abort is ignored", async () => {
    type Result = Awaited<ReturnType<typeof noteApi.fetchInvestigationNoteList>>;
    const continuation: { current: ((error: unknown) => void) | null } = { current: null };
    const thenable = {
      then: (
        _onSuccess: (result: Result) => void,
        onFailure: (error: unknown) => void,
      ): Promise<void> => {
        continuation.current = onFailure;
        return Promise.resolve();
      },
    };
    vi.spyOn(noteApi, "fetchInvestigationNoteList").mockReturnValue(
      thenable as unknown as Promise<Result>,
    );
    const originalHasInstance = Object.getOwnPropertyDescriptor(TimeoutError, Symbol.hasInstance);
    const classificationChecks = vi.fn(() => false);
    Object.defineProperty(TimeoutError, Symbol.hasInstance, {
      configurable: true,
      value: classificationChecks,
    });

    try {
      const view = render(signedIn());
      await settle();
      if (continuation.current === null) {
        throw new Error("The failure continuation was not installed.");
      }
      const reject = continuation.current;
      const ignoredAbort = vi
        .spyOn(AbortController.prototype, "abort")
        .mockImplementation(() => undefined);
      view.unmount();
      await flushMicrotasks();
      expect(ignoredAbort).toHaveBeenCalled();

      reject(new TypeError("released raw failure"));
      await flushMicrotasks();
      expect(classificationChecks).not.toHaveBeenCalled();
      ignoredAbort.mockRestore();
    } finally {
      if (originalHasInstance === undefined) {
        Reflect.deleteProperty(TimeoutError, Symbol.hasInstance);
      } else {
        Object.defineProperty(TimeoutError, Symbol.hasInstance, originalHasInstance);
      }
    }
  });

  it("does not inspect a late success after release and ignores duplicate settle", async () => {
    type Result = Awaited<ReturnType<typeof noteApi.fetchInvestigationNoteList>>;
    const continuation: { current: ((result: Result) => void) | null } = { current: null };
    const duplicateThenable = {
      then: (onSuccess: (result: Result) => void): Promise<void> => {
        continuation.current = onSuccess;
        return Promise.resolve();
      },
    };
    vi.spyOn(noteApi, "fetchInvestigationNoteList").mockReturnValue(
      duplicateThenable as unknown as Promise<Result>,
    );
    const view = render(signedIn());
    await settle();
    if (continuation.current === null) {
      throw new Error("The success continuation was not installed.");
    }
    const continueSuccess = continuation.current;

    continueSuccess({ data: page() as unknown as Result["data"], traceId: TRACE_ID });
    await act(async () => flushMicrotasks());
    expect(view.result.current.state.status).toBe("success");
    const firstState = view.result.current.state;

    let duplicateReads = 0;
    const duplicate: Record<string, unknown> = { traceId: "trace_duplicate" };
    Object.defineProperty(duplicate, "data", {
      get: () => {
        duplicateReads += 1;
        return page([note({ content: "duplicate" })]);
      },
    });
    continueSuccess(duplicate as unknown as Result);
    await act(async () => flushMicrotasks());
    expect(duplicateReads).toBe(0);
    expect(view.result.current.state).toBe(firstState);

    view.unmount();
    await flushMicrotasks();
    let releasedReads = 0;
    const released: Record<string, unknown> = { traceId: "trace_released" };
    Object.defineProperty(released, "data", {
      get: () => {
        releasedReads += 1;
        return page();
      },
    });
    continueSuccess(released as unknown as Result);
    await flushMicrotasks();
    expect(releasedReads).toBe(0);
  });
});

let setControlledAuthState: ((state: AuthState) => void) | null = null;

function ControlledAuth({
  children,
  client,
}: {
  readonly children?: ReactNode;
  readonly client: AuthClient;
}) {
  const [state, setState] = useState<AuthState>({ status: "authenticated", session: SESSION });
  useEffect(() => {
    setControlledAuthState = setState;
    return () => {
      setControlledAuthState = null;
    };
  }, []);
  useEffect(
    () => client.onSessionInvalidated(() => setState({ status: "unauthenticated" })),
    [client],
  );
  const value = useMemo<AuthContextValue>(
    () => ({
      state,
      client,
      signIn: () => undefined,
      signOut: () => undefined,
      notifyCallbackStarted: () => undefined,
      notifyCallbackSucceeded: () => undefined,
      notifyCallbackFailed: () => undefined,
    }),
    [client, state],
  );
  return createElement(AuthContext.Provider, { value }, children);
}

function renderControlled(client: AuthClient) {
  return renderHook(() => useCaseInvestigationNotes(CASE_ID, 0, 20), {
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(
        StrictMode,
        null,
        createElement(ControlledAuth, { client }, children),
      ),
  });
}

describe("useCaseInvestigationNotes session replacement", () => {
  beforeEach(() => {
    setControlledAuthState = null;
  });

  function replacementClient(): FakeAuthClient {
    const client = createFakeAuthClient({
      initialSession: SESSION,
      accessToken: "synthetic-session-bound-token",
      completeSignInResult: { session: SECOND_SESSION, returnTo: "/" },
    });
    adapter.client = client;
    return client;
  }

  async function replaceWithSecondSession(client: FakeAuthClient): Promise<void> {
    await client.completeSignIn("https://app.invalid/auth/callback?code=x&state=y");
    act(() => {
      setControlledAuthState?.({ status: "authenticated", session: SECOND_SESSION });
    });
    await settle();
  }

  it.each([
    ["success", 200, page()],
    ["retryable error", 503, { code: "RAW_RETRY", message: "raw retry body" }],
    ["403", 403, { code: "RAW_FORBIDDEN", message: "raw forbidden body" }],
    ["404", 404, { code: "RAW_NOT_FOUND", message: "raw not found body" }],
  ] as const)("publishes zero late %s outcomes from session A into session B", async (_label, status, body) => {
    const { calls, spy } = controlledFetch();
    const client = replacementClient();
    const view = renderControlled(client);
    await settle();
    expect(calls).toHaveLength(1);

    await replaceWithSecondSession(client);
    expect(calls).toHaveLength(2);
    const writesBeforeStaleSettlement = publisher.writes.length;
    await answer(calls[0], body, status);

    expect(view.result.current.state).toEqual({ status: "loading" });
    expect(publisher.writes).toHaveLength(writesBeforeStaleSettlement);
    expect(JSON.stringify(view.result.current.state)).not.toMatch(
      /RAW_RETRY|RAW_FORBIDDEN|RAW_NOT_FOUND|raw retry body|raw forbidden body|raw not found body/,
    );

    await answer(calls[1], page());
    expect(view.result.current.state.status).toBe("success");
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("does not let a stale credential's 401 invalidate session B, but current B's 401 does", async () => {
    const { calls } = controlledFetch();
    const client = replacementClient();
    const view = renderControlled(client);
    await settle();
    const ignoredAbort = vi
      .spyOn(AbortController.prototype, "abort")
      .mockImplementation(() => undefined);
    try {
      await replaceWithSecondSession(client);
      expect(calls).toHaveLength(2);
      await answer(calls[0], { code: "RAW_OLD_401", message: "old credential body" }, 401);

      expect(client.calls.invalidateIfCurrent).toBe(1);
      expect(client.calls.notified).toBe(0);
      expect(view.result.current.state).toEqual({ status: "loading" });

      await answer(calls[1], { code: "RAW_CURRENT_401", message: "current credential body" }, 401);
      await waitFor(() => expect(view.result.current.state).toEqual({ status: "idle" }));
      expect(client.calls.invalidateIfCurrent).toBe(2);
      expect(client.calls.notified).toBe(1);
      expect(JSON.stringify(publisher.writes)).not.toMatch(
        /RAW_OLD_401|RAW_CURRENT_401|old credential body|current credential body/,
      );
    } finally {
      ignoredAbort.mockRestore();
    }
  });

  it.each([
    ["success", 200, page()],
    ["retryable error", 503, { code: "RAW_LOGOUT_RETRY", message: "logout retry body" }],
    ["403", 403, { code: "RAW_LOGOUT_FORBIDDEN", message: "logout forbidden body" }],
    ["404", 404, { code: "RAW_LOGOUT_NOT_FOUND", message: "logout not found body" }],
  ] as const)("publishes zero late %s outcomes after logout/null session", async (_label, status, body) => {
    const { calls } = controlledFetch();
    const client = replacementClient();
    const view = renderControlled(client);
    await settle();
    expect(calls).toHaveLength(1);

    act(() => {
      setControlledAuthState?.({ status: "unauthenticated" });
    });
    const writesAfterLogout = publisher.writes.length;
    await answer(calls[0], body, status);

    expect(view.result.current.state).toEqual({ status: "idle" });
    expect(publisher.writes).toHaveLength(writesAfterLogout);
    expect(JSON.stringify(publisher.writes)).not.toMatch(
      /RAW_LOGOUT|logout retry body|logout forbidden body|logout not found body/,
    );
  });
});
