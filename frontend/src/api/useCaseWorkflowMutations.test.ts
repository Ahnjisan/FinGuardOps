import { createElement, StrictMode, useEffect, type ReactNode } from "react";
import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthClient, AuthSession } from "../auth/authClient";
import { AuthProvider } from "../auth/AuthProvider";
import { useAuth } from "../auth/useAuth";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import type { CaseDetail } from "./caseApi";
import { RequestNotAllowedError } from "./errors";
import type {
  CaseWorkflowReconciliationScope,
} from "./useCaseWorkflowMutations";

const adapter = vi.hoisted(() => ({ client: null as unknown }));
vi.mock("../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => adapter.client }));

const { bindCredentialLookupToFlight, useCaseWorkflowMutations } = await import(
  "./useCaseWorkflowMutations"
);
type CaseWorkflowAction = Parameters<
  ReturnType<typeof useCaseWorkflowMutations>["submit"]
>[0];

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const OTHER_CASE_ID = "6d3e2f10-8b9c-4d0e-8f01-2b3c4d5e6f71";
const CURRENT_ASSIGNEE = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
const NEXT_ASSIGNEE = "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60";
const SESSION_A: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: ["FDS_ANALYST"],
};
const SESSION_B: AuthSession = {
  subject: "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071",
  roles: ["FDS_ANALYST", "FDS_APPROVER"],
};

interface Props {
  readonly caseId: string | null;
  readonly detail: CaseDetail | null;
  readonly generation: number;
  readonly refreshState: "idle" | "refreshing" | "failed";
  readonly reconcile: (
    scope: CaseWorkflowReconciliationScope,
    minimumVersion: number,
  ) => void;
}

interface PendingCall {
  readonly request: Request;
  resolve: (response: Response) => void;
  reject: (error: unknown) => void;
}

const sessionControl: {
  current: null | {
    readonly start: () => void;
    readonly succeed: (session: AuthSession) => void;
    /** 현재 게시된 session 객체. 같은 객체로 복구하는 반례에 사용한다. */
    readonly session: AuthSession | null;
  };
} = { current: null };

function SessionControl({ children }: { readonly children: ReactNode }) {
  const auth = useAuth();
  const session = auth.state.status === "authenticated" ? auth.state.session : null;
  useEffect(() => {
    sessionControl.current = {
      start: auth.notifyCallbackStarted,
      succeed: auth.notifyCallbackSucceeded,
      session,
    };
    return () => {
      sessionControl.current = null;
    };
  }, [auth.notifyCallbackStarted, auth.notifyCallbackSucceeded, session]);
  return children;
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

function signedIn(session: AuthSession = SESSION_A): FakeAuthClient {
  const client = createFakeAuthClient({ initialSession: session });
  adapter.client = client;
  return client;
}

function detail(overrides: Partial<CaseDetail> = {}): CaseDetail {
  return {
    caseId: CASE_ID,
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: CURRENT_ASSIGNEE,
    relatedTransactionCount: 2,
    createdAt: "2026-09-01T00:00:00Z",
    reviewStartedAt: "2026-09-01T01:00:00Z",
    closedAt: null,
    lastChangedAt: "2026-09-01T02:00:00Z",
    concurrencyVersion: 6,
    ...overrides,
  };
}

function openDetail(version = 6): CaseDetail {
  return detail({
    caseStatus: "OPEN",
    assigneeRef: null,
    reviewStartedAt: null,
    concurrencyVersion: version,
  });
}

function mutationResponse(
  baseline: CaseDetail,
  action: CaseWorkflowAction,
  overrides: Record<string, unknown> = {},
): Record<string, unknown> {
  let caseStatus = baseline.caseStatus;
  let assigneeRef = baseline.assigneeRef;
  let reviewStartedAt = baseline.reviewStartedAt;
  if (action.kind === "start-review") {
    caseStatus = "IN_REVIEW";
    assigneeRef = action.assigneeRef;
    reviewStartedAt = "2026-09-01T03:00:00Z";
  } else if (action.kind === "request-additional-information") {
    caseStatus = "ADDITIONAL_INFORMATION_REQUIRED";
  } else if (action.kind === "resume-review") {
    caseStatus = "IN_REVIEW";
  } else if (action.kind === "change-assignee") {
    assigneeRef = action.assigneeRef;
  } else {
    assigneeRef = null;
  }
  return {
    caseId: baseline.caseId,
    caseStatus,
    finalDisposition: baseline.finalDisposition,
    assigneeRef,
    reviewStartedAt,
    closedAt: baseline.closedAt,
    lastChangedAt: "2026-09-01T03:00:00Z",
    concurrencyVersion: baseline.concurrencyVersion + 1,
    traceId: "trace_demo_case_workflow_hook_01",
    ...overrides,
  };
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

function renderMutation(client = signedIn(), overrides: Partial<Props> = {}) {
  const initialProps: Props = {
    caseId: CASE_ID,
    detail: detail(),
    generation: 3,
    refreshState: "idle",
    reconcile: vi.fn(),
    ...overrides,
  };
  return renderHook(
    (props: Props) => {
      const context = {
        caseId: props.caseId,
        detail: props.detail,
        reconciliationGeneration: props.generation,
        detailRefreshState: props.refreshState,
        onReconcile: props.reconcile,
      };
      return useCaseWorkflowMutations(context);
    },
    { initialProps, wrapper: providerWrapper(client) },
  );
}

async function settle(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function answer(call: PendingCall, response: Response): Promise<void> {
  await act(async () => {
    call.resolve(response);
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

describe("useCaseWorkflowMutations request matrix", () => {
  const rows: ReadonlyArray<{
    readonly name: string;
    readonly baseline: CaseDetail;
    readonly action: CaseWorkflowAction;
    readonly suffix: string;
    readonly body: Record<string, unknown>;
  }> = [
    {
      name: "OPEN to IN_REVIEW",
      baseline: openDetail(),
      action: { kind: "start-review", assigneeRef: NEXT_ASSIGNEE },
      suffix: "/status",
      body: {
        targetStatus: "IN_REVIEW",
        assigneeRef: NEXT_ASSIGNEE,
        reasonCode: "CASE_REVIEW_STARTED",
        expectedVersion: 6,
      },
    },
    {
      name: "IN_REVIEW to ADDITIONAL_INFORMATION_REQUIRED",
      baseline: detail(),
      action: { kind: "request-additional-information" },
      suffix: "/status",
      body: {
        targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
        reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
        expectedVersion: 6,
      },
    },
    {
      name: "ADDITIONAL_INFORMATION_REQUIRED to IN_REVIEW",
      baseline: detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }),
      action: { kind: "resume-review" },
      suffix: "/status",
      body: {
        targetStatus: "IN_REVIEW",
        reasonCode: "CASE_REVIEW_RESUMED",
        expectedVersion: 6,
      },
    },
    {
      name: "IN_REVIEW reassignment",
      baseline: detail(),
      action: { kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE },
      suffix: "/assignee",
      body: {
        assigneeRef: NEXT_ASSIGNEE,
        reasonCode: "CASE_ASSIGNEE_CHANGED",
        expectedVersion: 6,
      },
    },
    {
      name: "ADDITIONAL_INFORMATION_REQUIRED assignment",
      baseline: detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null }),
      action: { kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE },
      suffix: "/assignee",
      body: {
        assigneeRef: NEXT_ASSIGNEE,
        reasonCode: "CASE_ASSIGNEE_ASSIGNED",
        expectedVersion: 6,
      },
    },
    {
      name: "ADDITIONAL_INFORMATION_REQUIRED release",
      baseline: detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }),
      action: { kind: "release-assignee" },
      suffix: "/assignee",
      body: {
        assigneeRef: null,
        reasonCode: "CASE_ASSIGNEE_RELEASED",
        expectedVersion: 6,
      },
    },
  ];

  it.each(rows)("sends one exact $name request", async ({ baseline, action, suffix, body }) => {
    const calls = controlledFetch();
    const client = signedIn();
    const view = renderMutation(client, { detail: baseline });
    await settle();

    act(() => view.result.current.submit(action));
    await waitFor(() => expect(calls).toHaveLength(1));

    expect(calls[0].request.method).toBe("PATCH");
    expect(new URL(calls[0].request.url).pathname).toBe(
      `/api/v1/cases/${CASE_ID}${suffix}`,
    );
    expect(new URL(calls[0].request.url).search).toBe("");
    expect(JSON.parse(await calls[0].request.clone().text())).toEqual(body);
    expect(client.calls.authorizeRequest).toBe(1);
    view.unmount();
  });

  it("rejects forbidden state/action combinations and MAX_SAFE_INTEGER locally", async () => {
    controlledFetch();
    const client = signedIn();
    const view = renderMutation(client, { detail: openDetail() });
    await settle();

    const rows: ReadonlyArray<readonly [CaseDetail, CaseWorkflowAction]> = [
      [openDetail(), { kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE }],
      [detail(), { kind: "resume-review" }],
      [detail(), { kind: "release-assignee" }],
      [detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null }), { kind: "resume-review" }],
      [detail({ caseStatus: "CLOSED", finalDisposition: "NORMAL", closedAt: "2026-09-01T04:00:00Z" }), { kind: "request-additional-information" }],
      [openDetail(Number.MAX_SAFE_INTEGER), { kind: "start-review", assigneeRef: NEXT_ASSIGNEE }],
    ];
    for (const [baseline, action] of rows) {
      view.rerender({
        caseId: CASE_ID,
        detail: baseline,
        generation: 3,
        refreshState: "idle",
        reconcile: vi.fn(),
      });
      act(() => view.result.current.submit(action));
      expect(view.result.current.state.status).toBe("validation-error");
    }
    expect(client.calls.authorizeRequest).toBe(0);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("rejects uppercase, unhyphenated, whitespace, non-v4, malformed and duplicate UUIDs", async () => {
    controlledFetch();
    const client = signedIn();
    const view = renderMutation(client);
    await settle();
    for (const assigneeRef of [
      NEXT_ASSIGNEE.toUpperCase(),
      NEXT_ASSIGNEE.replaceAll("-", ""),
      ` ${NEXT_ASSIGNEE}`,
      `${NEXT_ASSIGNEE} `,
      "8d2e3f40-5b6c-3d7e-9f01-1b2c3d4e5f60",
      "not-a-uuid",
      "",
      CURRENT_ASSIGNEE,
    ]) {
      act(() => view.result.current.submit({ kind: "change-assignee", assigneeRef }));
      expect(view.result.current.state).toMatchObject({
        status: "validation-error",
        field: "assignee",
      });
    }
    expect(client.calls.authorizeRequest).toBe(0);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("rejects viewer, invalid case binding and missing session before credential lookup", async () => {
    controlledFetch();
    const viewer = signedIn({ ...SESSION_A, roles: ["FDS_VIEWER"] });
    const denied = renderMutation(viewer);
    await settle();
    act(() => denied.result.current.submit({ kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE }));
    expect(denied.result.current.state.status).toBe("forbidden");
    expect(viewer.calls.authorizeRequest).toBe(0);
    denied.unmount();

    const analyst = signedIn();
    const mismatch = renderMutation(analyst, {
      caseId: CASE_ID,
      detail: detail({ caseId: OTHER_CASE_ID }),
    });
    await settle();
    act(() => mismatch.result.current.submit({ kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE }));
    expect(mismatch.result.current.state.status).toBe("request-rejected");
    expect(analyst.calls.authorizeRequest).toBe(0);
    mismatch.unmount();

    const signedOut = createFakeAuthClient({ initialSession: null });
    adapter.client = signedOut;
    const missing = renderMutation(signedOut);
    await settle();
    act(() => missing.result.current.submit({ kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE }));
    expect(missing.result.current.state.status).toBe("authentication-required");
    expect(signedOut.calls.authorizeRequest).toBe(0);
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe("useCaseWorkflowMutations shared lane and lifecycle", () => {
  it("allows one shared Status/Assignee flight under clicks and repeated submits", async () => {
    const calls = controlledFetch();
    const view = renderMutation();
    await settle();
    act(() => {
      view.result.current.submit({ kind: "request-additional-information" });
      view.result.current.submit({ kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE });
      view.result.current.submit({ kind: "request-additional-information" });
    });
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(new URL(calls[0].request.url).pathname).toMatch(/\/status$/);
    expect(view.result.current.busy).toBe(true);
  });

  it("revalidates status and version at submit time after a render replacement", async () => {
    controlledFetch();
    const client = signedIn();
    const view = renderMutation(client);
    await settle();
    view.rerender({
      caseId: CASE_ID,
      detail: detail({ caseStatus: "CLOSED", finalDisposition: "NORMAL", closedAt: "2026-09-01T04:00:00Z", concurrencyVersion: Number.MAX_SAFE_INTEGER }),
      generation: 4,
      refreshState: "idle",
      reconcile: vi.fn(),
    });
    act(() => view.result.current.submit({ kind: "request-additional-information" }));
    expect(client.calls.authorizeRequest).toBe(0);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("releases and aborts on status/version replacement and ignores an abort-resistant late answer", async () => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const baseline = detail();
    const action: CaseWorkflowAction = { kind: "request-additional-information" };
    const view = renderMutation(signedIn(), { detail: baseline, reconcile });
    await settle();
    act(() => view.result.current.submit(action));
    await waitFor(() => expect(calls).toHaveLength(1));

    view.rerender({
      caseId: CASE_ID,
      detail: detail({ concurrencyVersion: 7 }),
      generation: 4,
      refreshState: "idle",
      reconcile,
    });
    expect(calls[0].request.signal.aborted).toBe(true);
    await answer(calls[0], jsonResponse(mutationResponse(baseline, action)));
    expect(reconcile).not.toHaveBeenCalled();
    expect(view.result.current.state.status).toBe("idle");
  });

  it("aborts on unmount and publishes no late success or error", async () => {
    for (const outcome of ["success", "error"] as const) {
      const calls = controlledFetch();
      const reconcile = vi.fn();
      const baseline = detail();
      const action: CaseWorkflowAction = { kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE };
      const view = renderMutation(signedIn(), { detail: baseline, reconcile });
      await settle();
      act(() => view.result.current.submit(action));
      await waitFor(() => expect(calls).toHaveLength(1));
      view.unmount();
      expect(calls[0].request.signal.aborted).toBe(true);
      if (outcome === "success") {
        await answer(calls[0], jsonResponse(mutationResponse(baseline, action)));
      } else {
        await act(async () => {
          calls[0].reject(new TypeError("PRIVATE_NETWORK_FAILURE"));
          await Promise.resolve();
          await Promise.resolve();
        });
      }
      expect(reconcile).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it.each([401, 403, 404, 409])(
    "drops a stale session-A %i outcome after session B replaces it",
    async (status) => {
      const calls = controlledFetch();
      const client = createFakeAuthClient({
        initialSession: SESSION_A,
        completeSignInResult: { session: SESSION_B, returnTo: "/" },
      });
      adapter.client = client;
      const reconcile = vi.fn();
      const view = renderMutation(client, { reconcile });
      await settle();
      act(() => view.result.current.submit({ kind: "request-additional-information" }));
      await waitFor(() => expect(calls).toHaveLength(1));

      act(() => client.emitSessionInvalidated());
      await settle();
      const completed = await client.completeSignIn(
        "http://localhost/auth/callback?code=x&state=y",
      );
      act(() => {
        sessionControl.current?.start();
        sessionControl.current?.succeed(completed.session);
      });
      await settle();
      expect(view.result.current.state.status).toBe("idle");
      await answer(
        calls[0],
        jsonResponse(
          { code: "PRIVATE_CODE", message: "PRIVATE_MESSAGE", traceId: "private_trace" },
          { status },
        ),
      );
      expect(reconcile).not.toHaveBeenCalled();
      expect(view.result.current.state.status).toBe("idle");
      if (status === 401) {
        // The stale request may invoke its request-scoped invalidator, but the
        // fake port keeps the replacement session live.
        expect(client.calls.notified).toBe(0);
      }
    },
  );

  it.each(["success", "network-error"] as const)(
    "drops a stale session-A %s after session B replaces it",
    async (outcome) => {
      const calls = controlledFetch();
      const client = createFakeAuthClient({
        initialSession: SESSION_A,
        completeSignInResult: { session: SESSION_B, returnTo: "/" },
      });
      adapter.client = client;
      const reconcile = vi.fn();
      const baseline = detail();
      const action: CaseWorkflowAction = { kind: "request-additional-information" };
      const view = renderMutation(client, { reconcile });
      await settle();
      act(() => view.result.current.submit(action));
      await waitFor(() => expect(calls).toHaveLength(1));

      act(() => client.emitSessionInvalidated());
      await settle();
      const completed = await client.completeSignIn(
        "http://localhost/auth/callback?code=x&state=y",
      );
      act(() => {
        sessionControl.current?.start();
        sessionControl.current?.succeed(completed.session);
      });
      await settle();

      if (outcome === "success") {
        await answer(calls[0], jsonResponse(mutationResponse(baseline, action)));
      } else {
        await act(async () => {
          calls[0].reject(new TypeError("PRIVATE_STALE_NETWORK_FAILURE"));
          await Promise.resolve();
          await Promise.resolve();
        });
      }
      expect(reconcile).not.toHaveBeenCalled();
      expect(view.result.current.state.status).toBe("idle");
    },
  );

  it("lets the authorized transport invalidate only the current credential on 401", async () => {
    const calls = controlledFetch();
    const client = signedIn();
    const reconcile = vi.fn();
    const view = renderMutation(client, { reconcile });
    await settle();
    act(() => view.result.current.submit({ kind: "request-additional-information" }));
    await waitFor(() => expect(calls).toHaveLength(1));

    await answer(calls[0], jsonResponse({ code: "PRIVATE_UNAUTHORIZED" }, { status: 401 }));
    await waitFor(() => expect(view.result.current.state.status).toBe("idle"));
    expect(client.calls.invalidateIfCurrent).toBe(1);
    expect(client.calls.notified).toBe(1);
    expect(reconcile).not.toHaveBeenCalled();
  });

  it("releases on logout/null session and allows no stale publication", async () => {
    const calls = controlledFetch();
    const client = signedIn();
    const reconcile = vi.fn();
    const baseline = detail();
    const action: CaseWorkflowAction = { kind: "request-additional-information" };
    const view = renderMutation(client, { reconcile });
    await settle();
    act(() => view.result.current.submit(action));
    await waitFor(() => expect(calls).toHaveLength(1));
    act(() => client.emitSessionInvalidated());
    await waitFor(() => expect(view.result.current.state.status).toBe("idle"));
    await answer(calls[0], jsonResponse(mutationResponse(baseline, action)));
    expect(reconcile).not.toHaveBeenCalled();
  });
});

describe("useCaseWorkflowMutations credential and dispatch boundaries", () => {
  it("re-checks flight ownership before credential lookup and right after authorization", async () => {
    const client = createFakeAuthClient({ initialSession: SESSION_A });
    const request = new Request(`http://localhost:8080/api/v1/cases/${CASE_ID}/status`, {
      method: "PATCH",
    });

    // 1단계: credential 조회 직전에 소유권이 없으면 인증 port를 호출하지 않는다.
    const refused = bindCredentialLookupToFlight(client, () => false);
    await expect(refused.authorizeRequest(request)).rejects.toBeInstanceOf(RequestNotAllowedError);
    expect(client.calls.authorizeRequest).toBe(0);

    // 2단계(이중 방어): 조회 중 소유권이 사라지면 발급된 요청을 transport에 돌려주지 않고 보관하지도
    // 않는다. 실제 fetch 직전 최종 검사는 이 wrapper가 아니라 아래 통합 call path 반례가 고정한다.
    let checks = 0;
    const dropped = bindCredentialLookupToFlight(client, () => {
      checks += 1;
      return checks === 1;
    });
    const droppedOutcome = await dropped.authorizeRequest(request).then(
      () => null,
      (error: unknown) => error,
    );
    expect(droppedOutcome).toBeInstanceOf(RequestNotAllowedError);
    expect(JSON.stringify(droppedOutcome)).not.toMatch(/Bearer|fake\.access\.token/);
    expect((droppedOutcome as Error).message).not.toMatch(/Bearer|fake\.access\.token/);
    expect(checks).toBe(2);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);

    // 두 확인을 모두 통과하면 인증 port의 결과를 바꾸지 않고 그대로 위임한다.
    const current = bindCredentialLookupToFlight(client, () => true);
    const authorized = await current.authorizeRequest(request);
    expect(authorized?.request.headers.get("Authorization")).toBe("Bearer fake.access.token");
    expect(client.calls.authorizeRequest).toBe(2);
  });

  it.each(["version", "capability-restored", "generation", "session-aba-generation"] as const)(
    "sends no PATCH when the %s changes during credential lookup even if abort is ignored",
    async (change) => {
      const calls = controlledFetch();
      const base = createFakeAuthClient({ initialSession: SESSION_A });
      let releaseLookup!: () => void;
      const lookupGate = new Promise<void>((resolveGate) => {
        releaseLookup = resolveGate;
      });
      let lookups = 0;
      const client = Object.create(base, {
        authorizeRequest: {
          value: async (request: Request) => {
            lookups += 1;
            await lookupGate;
            return base.authorizeRequest(request);
          },
        },
      }) as FakeAuthClient;
      adapter.client = client;
      // transport가 AbortSignal을 무시하는 경우를 재현한다. 새 경계는 abort와 무관하게 fetch를 막아야 한다.
      vi.spyOn(AbortController.prototype, "abort").mockImplementation(() => undefined);
      const reconcile = vi.fn();
      const baseline = detail();
      const view = renderMutation(client, { detail: baseline, generation: 3, reconcile });
      await settle();
      const originalSession = sessionControl.current?.session ?? null;
      expect(originalSession).not.toBeNull();

      act(() => view.result.current.submit({ kind: "request-additional-information" }));
      await waitFor(() => expect(lookups).toBe(1));

      if (change === "version") {
        view.rerender({
          caseId: CASE_ID,
          detail: detail({ concurrencyVersion: 7 }),
          generation: 4,
          refreshState: "idle",
          reconcile,
        });
      } else if (change === "generation") {
        // 같은 detail 객체에서 authoritative generation만 바뀌어도 submitting flight는 stale이다.
        view.rerender({
          caseId: CASE_ID,
          detail: baseline,
          generation: 4,
          refreshState: "idle",
          reconcile,
        });
      } else {
        // capability가 사라졌다가 정확히 같은 session 객체와 역할로 복구되어도 이전 flight는 되살아나지 않는다.
        act(() => base.emitSessionInvalidated());
        await waitFor(() => expect(sessionControl.current?.session ?? null).toBeNull());
        act(() => {
          sessionControl.current?.start();
          sessionControl.current?.succeed(originalSession as AuthSession);
        });
        await waitFor(() => expect(sessionControl.current?.session).toBe(originalSession));
        if (change === "session-aba-generation") {
          view.rerender({
            caseId: CASE_ID,
            detail: baseline,
            generation: 4,
            refreshState: "idle",
            reconcile,
          });
        }
      }
      await settle();

      await act(async () => {
        releaseLookup();
        for (let turn = 0; turn < 10; turn += 1) {
          await Promise.resolve();
        }
      });
      await settle();

      expect(base.calls.authorizeRequest).toBe(1);
      expect(calls).toHaveLength(0);
      expect(fetch).not.toHaveBeenCalled();
      expect(base.calls.invalidateIfCurrent).toBe(0);
      expect(reconcile).not.toHaveBeenCalled();
      expect(view.result.current.state.status).toBe("idle");
      expect(JSON.stringify(view.result.current)).not.toMatch(/Bearer|fake\.access\.token/);
    },
  );
});

/**
 * 인증 port가 credential을 발급한 뒤, authorized transport가 발급 결과를 모두 읽고 prepare를 반환하기
 * 직전에 `onHandOff`를 한 번 동기 실행한다.
 *
 * 이 지점은 authorize 직후 검사(2단계)가 이미 통과한 뒤이고, transport의 prepare 반환·deadline·abort
 * 검사와 실제 dispatch 사이 microtask 구간보다 앞이다. 발급은 `release` 전까지 보류되므로 테스트는 act
 * 밖에서 발급을 끝내고, hand-off 지점의 rerender는 dispatch 전에 즉시 commit된다.
 */
function createHandOffClient(base: FakeAuthClient, onHandOff: () => void) {
  let releaseLookup!: () => void;
  const lookupGate = new Promise<void>((resolveGate) => {
    releaseLookup = resolveGate;
  });
  let lookups = 0;
  let handOffs = 0;
  const client: FakeAuthClient = Object.create(base, {
    authorizeRequest: {
      value: async (request: Request) => {
        lookups += 1;
        await lookupGate;
        const authorized = await base.authorizeRequest(request);
        if (authorized === null) {
          return null;
        }
        const invalidateIfCurrent = authorized.invalidateIfCurrent;
        return {
          request: authorized.request,
          get invalidateIfCurrent() {
            if (handOffs === 0) {
              handOffs += 1;
              onHandOff();
            }
            return invalidateIfCurrent;
          },
        };
      },
    },
  });
  return {
    client,
    release: () => releaseLookup(),
    lookups: () => lookups,
    handOffs: () => handOffs,
  };
}

describe("useCaseWorkflowMutations submitting-phase flight identity", () => {
  it.each(["generation", "version"] as const)(
    "sends no PATCH when the %s changes after authorization but before dispatch, even if abort is ignored",
    async (change) => {
      const calls = controlledFetch();
      const base = createFakeAuthClient({ initialSession: SESSION_A });
      const reconcile = vi.fn();
      const baseline = detail();
      let rerender: ((props: Props) => void) | null = null;
      const handOff = createHandOffClient(base, () => {
        rerender?.({
          caseId: CASE_ID,
          detail: change === "generation" ? baseline : detail({ concurrencyVersion: 7 }),
          generation: 4,
          refreshState: "idle",
          reconcile,
        });
      });
      adapter.client = handOff.client;
      // transport가 AbortSignal을 무시하는 경우를 재현한다. 최종 guard는 abort와 무관하게 fetch를 막아야 한다.
      vi.spyOn(AbortController.prototype, "abort").mockImplementation(() => undefined);
      const view = renderMutation(handOff.client, { detail: baseline, generation: 3, reconcile });
      rerender = (props) => view.rerender(props);
      await settle();

      act(() => view.result.current.submit({ kind: "request-additional-information" }));
      await waitFor(() => expect(handOff.lookups()).toBe(1));
      await settle();
      expect(view.result.current.state.status).toBe("submitting");

      // act 밖에서 발급을 끝내야 hand-off 지점의 rerender가 dispatch 전에 즉시 commit된다.
      handOff.release();
      for (let turn = 0; turn < 50; turn += 1) {
        await Promise.resolve();
      }
      await settle();

      expect(handOff.handOffs()).toBe(1);
      expect(base.calls.authorizeRequest).toBe(1);
      expect(calls).toHaveLength(0);
      expect(fetch).not.toHaveBeenCalled();
      expect(base.calls.invalidateIfCurrent).toBe(0);
      expect(base.calls.notified).toBe(0);
      expect(reconcile).not.toHaveBeenCalled();
      expect(view.result.current.state.status).toBe("idle");
      expect(JSON.stringify(view.result.current)).not.toMatch(/Bearer|fake\.access\.token/);
    },
  );

  it.each(["success", "conflict", "network-error"] as const)(
    "publishes and reconciles nothing when only the generation changes after PATCH dispatch (%s)",
    async (outcome) => {
      const calls = controlledFetch();
      const client = signedIn();
      const reconcile = vi.fn();
      const baseline = detail();
      const action: CaseWorkflowAction = { kind: "request-additional-information" };
      vi.spyOn(AbortController.prototype, "abort").mockImplementation(() => undefined);
      const view = renderMutation(client, { detail: baseline, generation: 3, reconcile });
      await settle();
      act(() => view.result.current.submit(action));
      await waitFor(() => expect(calls).toHaveLength(1));

      // 같은 detail 객체에서 generation만 바뀐다. 응답은 아직 도착하지 않았다.
      view.rerender({
        caseId: CASE_ID,
        detail: baseline,
        generation: 4,
        refreshState: "idle",
        reconcile,
      });
      await settle();
      expect(view.result.current.state.status).toBe("idle");

      if (outcome === "success") {
        await answer(calls[0], jsonResponse(mutationResponse(baseline, action)));
      } else if (outcome === "conflict") {
        await answer(calls[0], jsonResponse({ code: "PRIVATE_CONFLICT" }, { status: 409 }));
      } else {
        await act(async () => {
          calls[0].reject(new TypeError("PRIVATE_STALE_NETWORK_FAILURE"));
          await Promise.resolve();
          await Promise.resolve();
        });
      }
      await settle();

      expect(reconcile).not.toHaveBeenCalled();
      expect(view.result.current.state.status).toBe("idle");
      expect(calls).toHaveLength(1);
      expect(client.calls.invalidateIfCurrent).toBe(0);
      expect(client.calls.notified).toBe(0);
    },
  );

  it("drops a late answer after a same-object session ABA combined with a generation change, even if abort is ignored", async () => {
    const calls = controlledFetch();
    const client = signedIn();
    const reconcile = vi.fn();
    const baseline = detail();
    const action: CaseWorkflowAction = { kind: "request-additional-information" };
    vi.spyOn(AbortController.prototype, "abort").mockImplementation(() => undefined);
    const view = renderMutation(client, { detail: baseline, generation: 3, reconcile });
    await settle();
    const originalSession = sessionControl.current?.session ?? null;
    expect(originalSession).not.toBeNull();
    act(() => view.result.current.submit(action));
    await waitFor(() => expect(calls).toHaveLength(1));

    act(() => client.emitSessionInvalidated());
    await waitFor(() => expect(sessionControl.current?.session ?? null).toBeNull());
    act(() => {
      sessionControl.current?.start();
      sessionControl.current?.succeed(originalSession as AuthSession);
    });
    await waitFor(() => expect(sessionControl.current?.session).toBe(originalSession));
    view.rerender({
      caseId: CASE_ID,
      detail: baseline,
      generation: 4,
      refreshState: "idle",
      reconcile,
    });
    await settle();

    await answer(calls[0], jsonResponse(mutationResponse(baseline, action)));
    await settle();

    expect(reconcile).not.toHaveBeenCalled();
    expect(view.result.current.state.status).toBe("idle");
    expect(calls).toHaveLength(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });
});

describe("useCaseWorkflowMutations reconciling-phase generation", () => {
  it("treats an authoritative generation advance during reconciliation as reconciliation, not as a stale submit", async () => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const baseline = detail({ concurrencyVersion: 6 });
    const action: CaseWorkflowAction = { kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE };
    const view = renderMutation(signedIn(), { detail: baseline, generation: 3, reconcile });
    await settle();
    act(() => view.result.current.submit(action));
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(calls[0], jsonResponse(mutationResponse(baseline, action)));

    expect(view.result.current.state).toMatchObject({ status: "reconciling", result: "success" });
    expect(reconcile).toHaveBeenCalledTimes(1);
    expect(reconcile).toHaveBeenCalledWith("detail-audit", 7);

    // floor(7) 미만 authoritative detail이 generation을 올려도 lane은 잠긴 채이고 새 PATCH를 만들지 않는다.
    for (const generation of [4, 5]) {
      view.rerender({
        caseId: CASE_ID,
        detail: detail({ assigneeRef: NEXT_ASSIGNEE, concurrencyVersion: 6 }),
        generation,
        refreshState: "idle",
        reconcile,
      });
      expect(view.result.current.state).toMatchObject({ status: "reconciling", result: "success" });
      expect(view.result.current.busy).toBe(true);
      act(() => view.result.current.submit({ kind: "request-additional-information" }));
      expect(view.result.current.state).toMatchObject({ status: "reconciling", result: "success" });
    }
    await settle();
    expect(calls).toHaveLength(1);
    expect(reconcile).toHaveBeenCalledTimes(1);

    // floor 이상 authoritative detail에서만 성공으로 해제되고, 다음 submit은 그 version으로 새 flight를 만든다.
    view.rerender({
      caseId: CASE_ID,
      detail: detail({ assigneeRef: NEXT_ASSIGNEE, concurrencyVersion: 7 }),
      generation: 6,
      refreshState: "idle",
      reconcile,
    });
    await waitFor(() => expect(view.result.current.state.status).toBe("success"));
    act(() => view.result.current.submit({ kind: "request-additional-information" }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(JSON.parse(await calls[1].request.clone().text())).toEqual({
      targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
      expectedVersion: 7,
    });
  });
});

describe("useCaseWorkflowMutations reconciliation and projection", () => {
  it("keeps success reconciling through lower and same-generation detail, then accepts equal or higher authoritative versions", async () => {
    for (const authoritativeVersion of [7, 8]) {
      const calls = controlledFetch();
      const reconcile = vi.fn();
      const baseline = detail({ concurrencyVersion: 6 });
      const action: CaseWorkflowAction = { kind: "change-assignee", assigneeRef: NEXT_ASSIGNEE };
      const view = renderMutation(signedIn(), { detail: baseline, generation: 3, reconcile });
      await settle();
      act(() => view.result.current.submit(action));
      await waitFor(() => expect(calls).toHaveLength(1));
      await answer(calls[0], jsonResponse(mutationResponse(baseline, action)));

      expect(view.result.current.state).toMatchObject({ status: "reconciling", result: "success" });
      expect(reconcile).toHaveBeenCalledTimes(1);
      expect(reconcile).toHaveBeenCalledWith("detail-audit", 7);

      view.rerender({
        caseId: CASE_ID,
        detail: baseline,
        generation: 3,
        refreshState: "idle",
        reconcile,
      });
      expect(view.result.current.state.status).toBe("reconciling");
      view.rerender({
        caseId: CASE_ID,
        detail: detail({ assigneeRef: NEXT_ASSIGNEE, concurrencyVersion: 6 }),
        generation: 4,
        refreshState: "failed",
        reconcile,
      });
      expect(view.result.current.state.status).toBe("reconciling");
      view.rerender({
        caseId: CASE_ID,
        detail: detail({ assigneeRef: NEXT_ASSIGNEE, concurrencyVersion: authoritativeVersion }),
        generation: 5,
        refreshState: "idle",
        reconcile,
      });
      await waitFor(() => expect(view.result.current.state.status).toBe("success"));
      expect(calls).toHaveLength(1);
      vi.unstubAllGlobals();
    }
  });

  it.each([
    ["conflict", 409] as const,
    ["ambiguous", 0] as const,
    ["invalid-success", 200] as const,
  ])("reconciles %s with all three reads and never retries PATCH", async (kind, status) => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const baseline = detail();
    const action: CaseWorkflowAction = { kind: "request-additional-information" };
    const view = renderMutation(signedIn(), { detail: baseline, generation: 3, reconcile });
    await settle();
    act(() => view.result.current.submit(action));
    await waitFor(() => expect(calls).toHaveLength(1));

    if (kind === "ambiguous") {
      await act(async () => {
        calls[0].reject(new TypeError("PRIVATE_NETWORK_VALUE"));
        await Promise.resolve();
        await Promise.resolve();
      });
    } else if (kind === "invalid-success") {
      await answer(
        calls[0],
        jsonResponse(mutationResponse(baseline, action, { concurrencyVersion: 99 })),
      );
    } else {
      await answer(calls[0], jsonResponse({ code: "PRIVATE", message: "PRIVATE" }, { status }));
    }

    expect(view.result.current.state).toMatchObject({
      status: "reconciling",
      result: kind === "conflict" ? "conflict" : "ambiguous",
    });
    expect(reconcile).toHaveBeenCalledWith("detail-notes-audit", 6);
    expect(calls).toHaveLength(1);

    view.rerender({
      caseId: CASE_ID,
      detail: detail({ concurrencyVersion: 7 }),
      generation: 4,
      refreshState: "idle",
      reconcile,
    });
    await waitFor(() =>
      expect(view.result.current.state.status).toBe(kind === "conflict" ? "conflict" : "ambiguous"),
    );
    act(() => view.result.current.submit(action));
    await waitFor(() => expect(calls).toHaveLength(2));
  });

  it("treats the deterministic request deadline as ambiguous and starts all read reconciliation", async () => {
    vi.useFakeTimers();
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const view = renderMutation(signedIn(), { reconcile });
    await settle();
    act(() => view.result.current.submit({ kind: "request-additional-information" }));
    await settle();
    expect(calls).toHaveLength(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(5_000);
    });

    expect(view.result.current.state).toMatchObject({
      status: "reconciling",
      result: "ambiguous",
    });
    expect(reconcile).toHaveBeenCalledWith("detail-notes-audit", 6);
    expect(calls).toHaveLength(1);
  });

  it("keeps failed reconciliation isolated and provides an explicit read refresh", async () => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const baseline = detail();
    const action: CaseWorkflowAction = { kind: "request-additional-information" };
    const view = renderMutation(signedIn(), { detail: baseline, reconcile });
    await settle();
    act(() => view.result.current.submit(action));
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(calls[0], jsonResponse({ code: "PRIVATE" }, { status: 409 }));
    view.rerender({
      caseId: CASE_ID,
      detail: baseline,
      generation: 3,
      refreshState: "failed",
      reconcile,
    });
    act(() => view.result.current.retryReconciliation());
    expect(reconcile).toHaveBeenCalledTimes(2);
    expect(reconcile).toHaveBeenLastCalledWith("detail-notes-audit", 6);
    expect(calls).toHaveLength(1);
  });

  it("classifies fixed terminal failures without retaining bodies, traces or credentials", async () => {
    const rows = [
      [403, "forbidden"],
      [404, "not-found"],
      [422, "server-error"],
      [500, "server-error"],
      [503, "server-error"],
    ] as const;
    for (const [status, expected] of rows) {
      const calls = controlledFetch();
      const reconcile = vi.fn();
      const view = renderMutation(signedIn(), { reconcile });
      await settle();
      act(() => view.result.current.submit({ kind: "request-additional-information" }));
      await waitFor(() => expect(calls).toHaveLength(1));
      await answer(
        calls[0],
        jsonResponse(
          {
            code: "PRIVATE_BACKEND_CODE",
            message: "PRIVATE_BACKEND_MESSAGE",
            traceId: "PRIVATE_TRACE",
            fieldErrors: [{ field: "PRIVATE_FIELD" }],
            actorId: "PRIVATE_ACTOR",
          },
          { status },
        ),
      );
      expect(view.result.current.state.status).toBe(expected);
      const serialized = JSON.stringify(view.result.current);
      expect(serialized).not.toMatch(/PRIVATE|trace|actor|credential|token|subject|fieldErrors/i);
      expect(reconcile).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("separates stored outcome from repeated root and nested deliveries", async () => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const baseline = detail();
    const action: CaseWorkflowAction = { kind: "request-additional-information" };
    const view = renderMutation(signedIn(), { detail: baseline, reconcile });
    await settle();
    act(() => view.result.current.submit(action));
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(calls[0], jsonResponse(mutationResponse(baseline, action)));

    const first = view.result.current.state;
    expect(first.status).toBe("reconciling");
    if (first.status !== "reconciling") {
      throw new Error("Expected a reconciliation delivery.");
    }
    (first.notice as { action: CaseWorkflowAction["kind"] }).action = "release-assignee";
    act(() => view.result.current.retryReconciliation());
    const second = view.result.current.state;
    expect(second).not.toBe(first);
    if (second.status !== "reconciling") {
      throw new Error("Expected a second reconciliation delivery.");
    }
    expect(second.notice).not.toBe(first.notice);
    expect(second.notice.action).toBe("request-additional-information");
    (second.notice as { action: CaseWorkflowAction["kind"] }).action = "start-review";
    act(() => view.result.current.retryReconciliation());
    const third = view.result.current.state;
    if (third.status !== "reconciling") {
      throw new Error("Expected a third reconciliation delivery.");
    }
    expect(third).not.toBe(second);
    expect(third.notice).not.toBe(second.notice);
    expect(third.notice.action).toBe("request-additional-information");

    const recursiveKeys = (value: unknown, keys = new Set<PropertyKey>()): Set<PropertyKey> => {
      if (typeof value !== "object" || value === null) {
        return keys;
      }
      for (const key of Reflect.ownKeys(value)) {
        keys.add(key);
        recursiveKeys((value as Record<PropertyKey, unknown>)[key], keys);
      }
      return keys;
    };
    expect([...recursiveKeys(third)].sort()).toEqual([
      "action",
      "notice",
      "result",
      "status",
      "submission",
    ]);
    expect(JSON.stringify(third)).toBe(
      '{"status":"reconciling","submission":1,"result":"success","notice":{"action":"request-additional-information"}}',
    );
  });
});
