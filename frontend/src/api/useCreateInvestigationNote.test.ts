import { createElement, StrictMode, useEffect, type ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthClient, AuthSession } from "../auth/authClient";
import { AuthProvider } from "../auth/AuthProvider";
import { useAuth } from "../auth/useAuth";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import { isNoteContentString } from "./responseValidation";

const adapter = vi.hoisted(() => ({ client: null as unknown }));
vi.mock("../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => adapter.client }));

const { countInvestigationNoteCodePoints, useCreateInvestigationNote } = await import(
  "./useCreateInvestigationNote"
);

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const OTHER_CASE_ID = "6d3e2f10-8b9c-4d0e-8f01-2b3c4d5e6f71";
const NOTE_ID = "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60";
const USER_REF = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
const SESSION: AuthSession = { subject: USER_REF, roles: ["FDS_ANALYST"] };
const SECOND_SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: ["FDS_ANALYST"],
};

const sessionControl: {
  current: null | {
    readonly start: () => void;
    readonly succeed: (session: AuthSession) => void;
  };
} = { current: null };

interface Props {
  readonly caseId: string;
  readonly status: "IN_REVIEW" | "ADDITIONAL_INFORMATION_REQUIRED" | "OPEN" | "CLOSED";
  readonly version: number;
  readonly generation: number;
  readonly reconcile: () => void;
}

interface PendingCall {
  readonly request: Request;
  resolve: (response: Response) => void;
  reject: (error: unknown) => void;
}

function controlledFetch(): PendingCall[] {
  const calls: PendingCall[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((request: Request) => {
      let resolve!: (response: Response) => void;
      let reject!: (error: unknown) => void;
      const promise = new Promise<Response>((resolvePromise, rejectPromise) => {
        resolve = resolvePromise;
        reject = rejectPromise;
      });
      promise.catch(() => undefined);
      calls.push({ request, resolve, reject });
      return promise;
    }),
  );
  return calls;
}

function providerWrapper(client: AuthClient) {
  return ({ children }: { children: ReactNode }) =>
    createElement(
      StrictMode,
      null,
      createElement(AuthProvider, {
        client,
        children: createElement(SessionControl, { children }),
      }),
    );
}

function SessionControl({ children }: { readonly children: ReactNode }) {
  const auth = useAuth();
  useEffect(() => {
    sessionControl.current = {
      start: auth.notifyCallbackStarted,
      succeed: auth.notifyCallbackSucceeded,
    };
    return () => {
      sessionControl.current = null;
    };
  }, [auth.notifyCallbackStarted, auth.notifyCallbackSucceeded]);
  return children;
}

function signedIn(): FakeAuthClient {
  const client = createFakeAuthClient({ initialSession: SESSION });
  adapter.client = client;
  return client;
}

function renderMutation(client = signedIn(), overrides: Partial<Props> = {}) {
  const initialProps: Props = {
    caseId: CASE_ID,
    status: "IN_REVIEW",
    version: 6,
    generation: 1,
    reconcile: vi.fn(),
    ...overrides,
  };
  return renderHook(
    (props: Props) =>
      useCreateInvestigationNote({
        caseId: props.caseId,
        caseStatus: props.status,
        expectedVersion: props.version,
        reconciliationGeneration: props.generation,
        onReconcile: props.reconcile,
      }),
    { initialProps, wrapper: providerWrapper(client) },
  );
}

function created(content: string, version = 7): Record<string, unknown> {
  return {
    noteId: NOTE_ID,
    caseId: CASE_ID,
    authorType: "USER",
    authorRef: USER_REF,
    content,
    createdAt: "2026-09-02T00:00:00.123456Z",
    concurrencyVersion: version,
    traceId: "trace_demo_note_create_hook_01",
  };
}

async function settle(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  adapter.client = null;
  sessionControl.current = null;
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("useCreateInvestigationNote validation", () => {
  it("counts Unicode code points and matches Backend-compatible boundaries", () => {
    expect(countInvestigationNoteCodePoints("")).toBe(0);
    expect(countInvestigationNoteCodePoints("😀")).toBe(1);
    expect(countInvestigationNoteCodePoints("😀".repeat(4000))).toBe(4000);
    expect(countInvestigationNoteCodePoints("😀".repeat(4001))).toBe(4001);

    for (const accepted of ["a", "😀", " a ", "line one\r\nline two", "\ufeff"]) {
      expect(isNoteContentString(accepted)).toBe(true);
    }
    for (const refused of ["", " ", "\u00a0\u3000", "a".repeat(4001), "x\u0000", "x\u007f"]) {
      expect(isNoteContentString(refused)).toBe(false);
    }
  });

  it("rejects invalid content locally without credential or transport work", async () => {
    controlledFetch();
    const client = signedIn();
    const view = renderMutation(client);
    await settle();

    act(() => view.result.current.submit("\u00a0\u3000"));

    expect(view.result.current.state.status).toBe("validation-error");
    expect(client.calls.authorizeRequest).toBe(0);
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe("useCreateInvestigationNote lifecycle", () => {
  it("rechecks current capability, status, case, and version at submit time", async () => {
    controlledFetch();
    const viewer = createFakeAuthClient({
      initialSession: { subject: USER_REF, roles: ["FDS_VIEWER"] },
    });
    adapter.client = viewer;
    const denied = renderMutation(viewer);
    await settle();
    act(() => denied.result.current.submit("valid text"));
    expect(fetch).not.toHaveBeenCalled();

    denied.unmount();
    vi.unstubAllGlobals();
    controlledFetch();
    const view = renderMutation(signedIn());
    await settle();
    view.rerender({
      caseId: CASE_ID,
      status: "CLOSED",
      version: Number.MAX_SAFE_INTEGER,
      generation: 1,
      reconcile: vi.fn(),
    });
    act(() => view.result.current.submit("valid text"));
    expect(fetch).not.toHaveBeenCalled();
  });

  it("reports a missing current credential without sending the POST", async () => {
    controlledFetch();
    const client = createFakeAuthClient({ initialSession: SESSION, accessToken: "" });
    adapter.client = client;
    const view = renderMutation(client);
    await settle();

    act(() => view.result.current.submit("valid text"));

    await waitFor(() => expect(view.result.current.state.status).toBe("authentication-required"));
    expect(client.calls.authorizeRequest).toBe(1);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("sends one exact POST, blocks repeated submit, and reconciles only after a bound 201", async () => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const content = "  exact 😀\r\nsecond  line  ";
    const view = renderMutation(signedIn(), { reconcile });
    await settle();

    act(() => {
      view.result.current.submit(content);
      view.result.current.submit(content);
      view.result.current.submit(content);
    });
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].request.method).toBe("POST");
    expect(new URL(calls[0].request.url).search).toBe("");
    expect(JSON.parse(await calls[0].request.clone().text())).toEqual({
      content,
      expectedVersion: 6,
    });

    await act(async () => {
      calls[0].resolve(jsonResponse(created(content), { status: 201 }));
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(view.result.current.state.status).toBe("success");
    expect(reconcile).toHaveBeenCalledTimes(1);
    expect(reconcile).toHaveBeenCalledWith(7);
    expect(JSON.stringify(view.result.current)).not.toContain(content);

    act(() => view.result.current.submit(content));
    expect(calls).toHaveLength(1);
    view.rerender({ caseId: CASE_ID, status: "IN_REVIEW", version: 6, generation: 2, reconcile });
    act(() => view.result.current.submit(content));
    expect(calls).toHaveLength(1);
    view.rerender({ caseId: CASE_ID, status: "IN_REVIEW", version: 7, generation: 2, reconcile });
    act(() => view.result.current.submit(content));
    await waitFor(() => expect(calls).toHaveLength(2));
  });

  it.each([
    [403, "forbidden", false],
    [404, "not-found", false],
    [409, "conflict", true],
    [422, "server-error", false],
    [500, "server-error", false],
    [503, "server-error", false],
  ] as const)("classifies HTTP %i without retaining response data", async (status, expected, reconciles) => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const view = renderMutation(signedIn(), { reconcile });
    await settle();
    act(() => view.result.current.submit("kept only by textarea"));
    await waitFor(() => expect(calls).toHaveLength(1));

    await act(async () => {
      calls[0].resolve(
        jsonResponse({ code: "PRIVATE", message: "private-body", traceId: "private-trace" }, { status }),
      );
      await Promise.resolve();
      await Promise.resolve();
    });

    expect(view.result.current.state.status).toBe(expected);
    expect(reconcile).toHaveBeenCalledTimes(reconciles ? 1 : 0);
    expect(JSON.stringify(view.result.current)).not.toContain("private");
  });

  it("treats a network failure as an ambiguous commit and never retries it", async () => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const view = renderMutation(signedIn(), { reconcile });
    await settle();
    act(() => view.result.current.submit("network boundary"));
    await waitFor(() => expect(calls).toHaveLength(1));
    await act(async () => {
      calls[0].reject(new TypeError("private network detail"));
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(view.result.current.state.status).toBe("network-error");
    expect(calls).toHaveLength(1);
    expect(reconcile).toHaveBeenCalledTimes(1);
  });

  it("treats timeout as an ambiguous commit without retrying the POST", async () => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const view = renderMutation(signedIn(), { reconcile });
    await settle();
    vi.useFakeTimers();
    act(() => view.result.current.submit("timeout boundary"));
    await settle();
    expect(calls).toHaveLength(1);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });
    expect(view.result.current.state.status).toBe("timeout");
    expect(calls).toHaveLength(1);
    expect(reconcile).toHaveBeenCalledTimes(1);
  });

  it("blocks late success after case/version replacement and after unmount", async () => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const content = "stale content";
    const view = renderMutation(signedIn(), { reconcile });
    await settle();
    act(() => view.result.current.submit(content));
    await waitFor(() => expect(calls).toHaveLength(1));

    view.rerender({ caseId: OTHER_CASE_ID, status: "IN_REVIEW", version: 9, generation: 1, reconcile });
    await act(async () => {
      calls[0].resolve(jsonResponse(created(content), { status: 201 }));
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(reconcile).not.toHaveBeenCalled();

    const second = renderMutation(signedIn(), { reconcile });
    await settle();
    act(() => second.result.current.submit(content));
    await waitFor(() => expect(calls).toHaveLength(2));
    second.unmount();
    await act(async () => {
      calls[1].resolve(jsonResponse(created(content), { status: 201 }));
      await Promise.resolve();
    });
    expect(reconcile).not.toHaveBeenCalled();
  });

  it("lets the authorized transport invalidate the current credential on 401", async () => {
    const calls = controlledFetch();
    const client = signedIn();
    const view = renderMutation(client);
    await settle();
    act(() => view.result.current.submit("current credential"));
    await waitFor(() => expect(calls).toHaveLength(1));
    await act(async () => {
      calls[0].resolve(jsonResponse({}, { status: 401 }));
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(client.calls.invalidateIfCurrent).toBe(1);
    expect(client.calls.notified).toBe(1);
    expect(view.result.current.state.status).toBe("idle");
  });

  it("does not let a stale credential 401 invalidate the session that replaced it", async () => {
    const calls = controlledFetch();
    const client = createFakeAuthClient({
      initialSession: SESSION,
      completeSignInResult: { session: SECOND_SESSION, returnTo: "/" },
    });
    adapter.client = client;
    const view = renderMutation(client);
    await settle();
    act(() => view.result.current.submit("stale credential"));
    await waitFor(() => expect(calls).toHaveLength(1));

    act(() => client.emitSessionInvalidated());
    await settle();
    const completed = await client.completeSignIn("http://localhost/auth/callback?code=x&state=y");
    act(() => {
      sessionControl.current?.start();
      sessionControl.current?.succeed(completed.session);
    });
    await settle();
    expect(view.result.current.state.status).toBe("idle");

    await act(async () => {
      calls[0].resolve(jsonResponse({}, { status: 401 }));
      await Promise.resolve();
      await Promise.resolve();
    });
    expect(client.calls.invalidateIfCurrent).toBe(1);
    expect(client.calls.notified).toBe(0);
    expect(view.result.current.state.status).toBe("idle");
  });
});
