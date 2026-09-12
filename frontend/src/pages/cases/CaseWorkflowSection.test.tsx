import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthSession } from "../../auth/authClient";
import { AuthProvider } from "../../auth/AuthProvider";
import type { UserRole } from "../../auth/userRoles";
import { createFakeAuthClient } from "../../test/fakeAuthClient";
import { jsonResponse } from "../../test/mockFetch";
import type { CaseDetail } from "../../api/caseApi";
import type {
  CaseWorkflowReconciliationScope,
} from "../../api/useCaseWorkflowMutations";

const adapter = vi.hoisted(() => ({ client: null as unknown }));
vi.mock("../../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => adapter.client }));

const { CaseWorkflowSection } = await import("./CaseWorkflowSection");

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const OTHER_CASE_ID = "6d3e2f10-8b9c-4d0e-8f01-2b3c4d5e6f71";
const CURRENT_ASSIGNEE = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
const NEXT_ASSIGNEE = "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60";

interface PendingCall {
  readonly request: Request;
  resolve: (response: Response) => void;
  reject: (error: unknown) => void;
}

function session(roles: readonly [UserRole, ...UserRole[]]): AuthSession {
  return {
    subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
    roles,
  };
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

function openDetail(): CaseDetail {
  return detail({ caseStatus: "OPEN", assigneeRef: null, reviewStartedAt: null });
}

function workflowResponse(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    caseId: CASE_ID,
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: NEXT_ASSIGNEE,
    reviewStartedAt: "2026-09-01T01:00:00Z",
    closedAt: null,
    lastChangedAt: "2026-09-01T03:00:00Z",
    concurrencyVersion: 7,
    traceId: "trace_demo_case_workflow_component_01",
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

interface RenderOptions {
  readonly detail?: CaseDetail;
  readonly roles?: readonly [UserRole, ...UserRole[]];
  readonly generation?: number;
  readonly refreshState?: "idle" | "refreshing" | "failed";
  readonly reconcile?: (
    scope: CaseWorkflowReconciliationScope,
    minimumVersion: number,
  ) => void;
}

function renderSection(options: RenderOptions = {}) {
  const client = createFakeAuthClient({
    initialSession: session(options.roles ?? ["FDS_ANALYST"]),
  });
  adapter.client = client;
  const reconcile = options.reconcile ?? vi.fn();
  let currentDetail = options.detail ?? detail();
  let generation = options.generation ?? 3;
  let refreshState = options.refreshState ?? "idle";

  const tree = () => (
    <StrictMode>
      <AuthProvider client={client}>
        <CaseWorkflowSection
          detail={currentDetail}
          reconciliationGeneration={generation}
          detailRefreshState={refreshState}
          onReconcile={reconcile}
        />
      </AuthProvider>
    </StrictMode>
  );
  const rendered = render(tree());
  return {
    client,
    reconcile,
    rerender(next: {
      readonly detail?: CaseDetail;
      readonly generation?: number;
      readonly refreshState?: "idle" | "refreshing" | "failed";
    }) {
      currentDetail = next.detail ?? currentDetail;
      generation = next.generation ?? generation;
      refreshState = next.refreshState ?? refreshState;
      rendered.rerender(tree());
    },
    unmount: rendered.unmount,
  };
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
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("CaseWorkflowSection capability and status matrix", () => {
  it.each([
    [["FDS_ANALYST"] as const, true],
    [["FDS_ANALYST", "FDS_APPROVER"] as const, true],
    [["FDS_VIEWER"] as const, false],
    [["FDS_APPROVER"] as const, false],
    [["RULE_OPERATOR"] as const, false],
    [["RECOVERY_OPERATOR"] as const, false],
    [["PLATFORM_ADMIN"] as const, false],
  ])("applies the case:workflow DOM boundary for %j", async (roles, visible) => {
    controlledFetch();
    const view = renderSection({ roles });
    if (visible) {
      expect(
        await screen.findByRole("heading", { name: "Case workflow", level: 3 }),
      ).toBeVisible();
    } else {
      await waitFor(() => expect(view.client.calls.initialize).toBeGreaterThan(0));
      expect(screen.queryByRole("heading", { name: "Case workflow" })).not.toBeInTheDocument();
      expect(screen.queryByLabelText("Assignee UUID")).not.toBeInTheDocument();
      expect(screen.queryByRole("status", { name: "Case workflow result" })).not.toBeInTheDocument();
    }
    expect(view.client.calls.authorizeRequest).toBe(0);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("renders only start review for OPEN", async () => {
    renderSection({ detail: openDetail() });
    const section = (await screen.findByRole("heading", { name: "Case workflow" })).closest("section");
    expect(section).not.toBeNull();
    const scope = within(section as HTMLElement);
    expect(scope.getByRole("group", { name: "Start review" })).toBeVisible();
    expect(scope.getByRole("button", { name: "Start review" })).toBeVisible();
    expect(scope.getByLabelText("Assignee UUID")).toBeRequired();
    expect(scope.queryByRole("button", { name: "Change assignee" })).not.toBeInTheDocument();
    expect(scope.queryByRole("button", { name: "Release assignee" })).not.toBeInTheDocument();
    expect(scope.queryByRole("button", { name: "Request additional information" })).not.toBeInTheDocument();
  });

  it("renders request-information and non-null reassignment for IN_REVIEW", async () => {
    renderSection();
    await screen.findByRole("heading", { name: "Case workflow" });
    expect(screen.getByRole("button", { name: "Request additional information" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Change assignee" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Release assignee" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Resume review" })).not.toBeInTheDocument();
  });

  it("renders resume, change and explicit release only when additional-information has an assignee", async () => {
    const view = renderSection({ detail: detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }) });
    await screen.findByRole("heading", { name: "Case workflow" });
    expect(screen.getByRole("button", { name: "Resume review" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Change assignee" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Release assignee" })).toBeVisible();

    view.rerender({
      detail: detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null }),
      generation: 4,
    });
    expect(screen.getByText("Assign an analyst before resuming review.")).toBeVisible();
    expect(screen.getByRole("button", { name: "Assign analyst" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Resume review" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Release assignee" })).not.toBeInTheDocument();
  });

  it("renders no mutation control for CLOSED", async () => {
    renderSection({
      detail: detail({
        caseStatus: "CLOSED",
        finalDisposition: "CONFIRMED_FRAUD",
        closedAt: "2026-09-01T04:00:00Z",
      }),
    });
    const heading = await screen.findByRole("heading", { name: "Case workflow" });
    const section = heading.closest("section") as HTMLElement;
    expect(within(section).getByText("Workflow changes are unavailable for a closed case.")).toBeVisible();
    expect(within(section).queryByRole("button")).not.toBeInTheDocument();
    expect(within(section).queryByRole("textbox")).not.toBeInTheDocument();
    expect(within(section).queryByRole("group")).not.toBeInTheDocument();
  });
});

describe("CaseWorkflowSection validation, keyboard and lifecycle UI", () => {
  it("preserves exact invalid input, connects helper/error, marks only the field and focuses it", async () => {
    controlledFetch();
    const user = userEvent.setup();
    const view = renderSection();
    const input = await screen.findByRole("textbox", { name: "Assignee UUID" });
    const invalid = ` ${NEXT_ASSIGNEE.toUpperCase()} `;
    await user.type(input, invalid);
    await user.click(screen.getByRole("button", { name: "Change assignee" }));

    expect(input).toHaveValue(invalid);
    expect(input).toHaveAttribute("aria-invalid", "true");
    const ids = input.getAttribute("aria-describedby")?.split(" ") ?? [];
    expect(ids).toEqual(["case-workflow-assignee-helper", "case-workflow-assignee-error"]);
    for (const id of ids) {
      expect(document.getElementById(id)).not.toBeNull();
    }
    expect(input).toHaveFocus();
    expect(view.client.calls.authorizeRequest).toBe(0);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("does not submit repeated or composing Enter and still submits once by explicit action", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    renderSection();
    const input = await screen.findByRole("textbox", { name: "Assignee UUID" });
    await user.type(input, NEXT_ASSIGNEE);
    fireEvent.compositionStart(input);
    fireEvent.keyDown(input, { key: "Enter", code: "Enter", isComposing: true });
    fireEvent.compositionEnd(input);
    fireEvent.keyDown(input, { key: "Enter", code: "Enter", repeat: true });
    expect(calls).toHaveLength(0);

    await user.dblClick(screen.getByRole("button", { name: "Change assignee" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(JSON.parse(await calls[0].request.clone().text())).toEqual({
      assigneeRef: NEXT_ASSIGNEE,
      reasonCode: "CASE_ASSIGNEE_CHANGED",
      expectedVersion: 6,
    });
  });

  it("never converts an empty draft into release; release is a separate exact null action", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    renderSection({ detail: detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }) });
    await screen.findByRole("heading", { name: "Case workflow" });
    await user.click(screen.getByRole("button", { name: "Change assignee" }));
    expect(calls).toHaveLength(0);
    expect(screen.getByRole("textbox", { name: "Assignee UUID" })).toHaveFocus();

    await user.click(screen.getByRole("button", { name: "Release assignee" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(JSON.parse(await calls[0].request.clone().text())).toEqual({
      assigneeRef: null,
      reasonCode: "CASE_ASSIGNEE_RELEASED",
      expectedVersion: 6,
    });
  });

  it("disables every workflow control and marks the section busy through submit and reconciliation", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const reconcile = vi.fn();
    renderSection({ reconcile });
    const input = await screen.findByRole("textbox", { name: "Assignee UUID" });
    await user.type(input, NEXT_ASSIGNEE);
    await user.click(screen.getByRole("button", { name: "Change assignee" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    const section = screen.getByRole("heading", { name: "Case workflow" }).closest("section") as HTMLElement;
    expect(section).toHaveAttribute("aria-busy", "true");
    for (const control of within(section).getAllByRole("button")) {
      expect(control).toBeDisabled();
    }
    expect(input).toBeDisabled();

    await answer(calls[0], jsonResponse(workflowResponse()));
    expect(section).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Refreshing authoritative case information")).toBeVisible();
    expect(reconcile).toHaveBeenCalledWith("detail-audit", 7);
    expect(calls).toHaveLength(1);
  });

  it("preserves draft and focuses a fixed request error without exposing Backend fields", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    renderSection();
    const input = await screen.findByRole("textbox", { name: "Assignee UUID" });
    await user.type(input, NEXT_ASSIGNEE);
    await user.click(screen.getByRole("button", { name: "Change assignee" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(
      calls[0],
      jsonResponse(
        {
          code: "PRIVATE_CODE",
          message: "PRIVATE_MESSAGE",
          traceId: "PRIVATE_TRACE",
          fieldErrors: ["PRIVATE_FIELD"],
          actorId: "PRIVATE_ACTOR",
        },
        { status: 403 },
      ),
    );

    expect(input).toHaveValue(NEXT_ASSIGNEE);
    const alert = screen.getByRole("alert");
    const heading = within(alert).getByRole("heading", {
      name: "The workflow action was denied",
      level: 4,
    });
    expect(heading).toHaveAttribute("tabindex", "-1");
    expect(heading).toHaveFocus();
    expect(document.body.innerHTML).not.toMatch(/PRIVATE|traceId|fieldErrors|actorId/);
  });

  it("preserves a 409 draft, waits for authoritative detail, then requires explicit resubmit", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const reconcile = vi.fn();
    const view = renderSection({ reconcile });
    const input = await screen.findByRole("textbox", { name: "Assignee UUID" });
    await user.type(input, NEXT_ASSIGNEE);
    await user.click(screen.getByRole("button", { name: "Change assignee" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(calls[0], jsonResponse({ code: "PRIVATE_CONFLICT" }, { status: 409 }));
    expect(input).toHaveValue(NEXT_ASSIGNEE);
    expect(reconcile).toHaveBeenCalledWith("detail-notes-audit", 6);

    view.rerender({ detail: detail({ concurrencyVersion: 7 }), generation: 4 });
    const alert = await screen.findByRole("alert");
    expect(within(alert).getByRole("heading", { name: "The case changed before this action" })).toHaveFocus();
    expect(calls).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "Change assignee" }));
    await waitFor(() => expect(calls).toHaveLength(2));
  });

  it("clears the relevant draft only after authoritative success and returns focus to an available control", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const view = renderSection();
    const input = await screen.findByRole("textbox", { name: "Assignee UUID" });
    await user.type(input, NEXT_ASSIGNEE);
    await user.click(screen.getByRole("button", { name: "Change assignee" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(calls[0], jsonResponse(workflowResponse()));
    expect(input).toHaveValue(NEXT_ASSIGNEE);

    view.rerender({
      detail: detail({ assigneeRef: NEXT_ASSIGNEE, concurrencyVersion: 7 }),
      generation: 4,
    });
    await waitFor(() =>
      expect(screen.getByRole("status", { name: "Case workflow result" })).toHaveTextContent(
        "Assignee updated from authoritative case information.",
      ),
    );
    expect(screen.getByRole("textbox", { name: "Assignee UUID" })).toHaveValue("");
    expect(screen.getByRole("button", { name: "Request additional information" })).toHaveFocus();
    expect(screen.getAllByRole("status", { name: "Case workflow result" })).toHaveLength(1);
  });

  it("clears an assignee draft after an authoritative explicit release", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const view = renderSection({
      detail: detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }),
    });
    const input = await screen.findByRole("textbox", { name: "Assignee UUID" });
    await user.type(input, NEXT_ASSIGNEE);
    await user.click(screen.getByRole("button", { name: "Release assignee" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(
      calls[0],
      jsonResponse(
        workflowResponse({
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: null,
        }),
      ),
    );
    expect(input).toHaveValue(NEXT_ASSIGNEE);

    view.rerender({
      detail: detail({
        caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
        assigneeRef: null,
        concurrencyVersion: 7,
      }),
      generation: 4,
    });
    await waitFor(() => {
      expect(screen.getByRole("status", { name: "Case workflow result" })).toHaveTextContent(
        "Assignee released from authoritative case information.",
      );
      expect(screen.getByRole("textbox", { name: "Assignee UUID" })).toHaveValue("");
    });
  });

  it("drops draft on case identity change but preserves and revalidates it on status/version change", async () => {
    controlledFetch();
    const user = userEvent.setup();
    const view = renderSection();
    const input = await screen.findByRole("textbox", { name: "Assignee UUID" });
    await user.type(input, NEXT_ASSIGNEE);

    view.rerender({
      detail: detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", concurrencyVersion: 7 }),
      generation: 4,
    });
    expect(screen.getByRole("textbox", { name: "Assignee UUID" })).toHaveValue(NEXT_ASSIGNEE);
    expect(fetch).not.toHaveBeenCalled();

    view.rerender({
      detail: detail({ caseId: OTHER_CASE_ID, concurrencyVersion: 1 }),
      generation: 0,
    });
    await waitFor(() =>
      expect(screen.getByRole("textbox", { name: "Assignee UUID" })).toHaveValue(""),
    );
    expect(fetch).not.toHaveBeenCalled();
  });

  it("offers an explicit refresh after a failed reconciliation while keeping actions blocked", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const reconcile = vi.fn();
    const view = renderSection({ reconcile });
    await screen.findByRole("heading", { name: "Case workflow" });
    await user.click(screen.getByRole("button", { name: "Request additional information" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(calls[0], jsonResponse({ code: "PRIVATE" }, { status: 409 }));
    view.rerender({ refreshState: "failed" });

    const refresh = screen.getByRole("button", { name: "Refresh workflow information" });
    expect(refresh).toBeEnabled();
    await user.click(refresh);
    expect(reconcile).toHaveBeenCalledTimes(2);
    expect(screen.getByRole("button", { name: "Request additional information" })).toBeDisabled();
    expect(calls).toHaveLength(1);
  });
});
