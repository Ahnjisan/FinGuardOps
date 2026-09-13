import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthSession } from "../../auth/authClient";
import { AuthProvider } from "../../auth/AuthProvider";
import type { UserRole } from "../../auth/userRoles";
import { createFakeAuthClient } from "../../test/fakeAuthClient";
import { jsonResponse } from "../../test/mockFetch";
import type { CaseDetail, CaseFinalDisposition } from "../../api/caseApi";
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

/** 종결할 수 없는 사건 상태에서 승인 담당자에게 보이는 고정 안내. */
const RESOLUTION_UNAVAILABLE = "Case resolution is not available for the current case state.";

function closedDetail(
  finalDisposition: CaseFinalDisposition = "CONFIRMED_FRAUD",
  version = 7,
): CaseDetail {
  return detail({
    caseStatus: "CLOSED",
    finalDisposition,
    closedAt: "2026-09-01T03:00:00Z",
    lastChangedAt: "2026-09-01T03:00:00Z",
    concurrencyVersion: version,
  });
}

/** 기본 `detail()` baseline(v6)에 정확히 결합되는 CLOSED successor. */
function resolutionResponse(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return workflowResponse({
    caseStatus: "CLOSED",
    finalDisposition: "CONFIRMED_FRAUD",
    assigneeRef: CURRENT_ASSIGNEE,
    closedAt: "2026-09-01T03:00:00Z",
    lastChangedAt: "2026-09-01T03:00:00Z",
    ...overrides,
  });
}

function workflowSection(): HTMLElement {
  const section = screen
    .getByRole("heading", { name: "Case workflow", level: 3 })
    .closest("section");
  if (!(section instanceof HTMLElement)) {
    throw new Error("Case workflow section이 렌더되지 않았다.");
  }
  return section;
}

async function chooseAndResolve(
  user: ReturnType<typeof userEvent.setup>,
  disposition: string,
): Promise<void> {
  await user.click(await screen.findByRole("radio", { name: disposition }));
  await user.click(screen.getByRole("button", { name: "Resolve case" }));
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
  /** USER session이 게시되지 않은 상태. SERVICE principal도 Frontend에서는 이 상태와 같다. */
  readonly signedOut?: boolean;
  readonly generation?: number;
  readonly refreshState?: "idle" | "refreshing" | "failed";
  readonly reconcile?: (
    scope: CaseWorkflowReconciliationScope,
    minimumVersion: number,
  ) => void;
}

function renderSection(options: RenderOptions = {}) {
  const client = createFakeAuthClient({
    initialSession: options.signedOut === true ? null : session(options.roles ?? ["FDS_ANALYST"]),
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
  // D2: section은 case:workflow 또는 case:resolve에 열리고, 두 capability는 서로의 control을 열지 않는다.
  it.each([
    [["FDS_ANALYST"] as const, true, false],
    [["FDS_ANALYST", "FDS_APPROVER"] as const, true, true],
    [["FDS_APPROVER"] as const, false, true],
    [["FDS_VIEWER"] as const, false, false],
    [["RULE_OPERATOR"] as const, false, false],
    [["RECOVERY_OPERATOR"] as const, false, false],
    [["PLATFORM_ADMIN"] as const, false, false],
  ])(
    "applies the case:workflow and case:resolve DOM boundaries for %j",
    async (roles, workflow, resolution) => {
      controlledFetch();
      const view = renderSection({ roles });
      await waitFor(() => expect(view.client.calls.initialize).toBeGreaterThan(0));
      if (workflow || resolution) {
        expect(
          await screen.findByRole("heading", { name: "Case workflow", level: 3 }),
        ).toBeVisible();
        expect(screen.getAllByRole("status", { name: "Case workflow result" })).toHaveLength(1);
      } else {
        await act(async () => {
          await Promise.resolve();
        });
        expect(screen.queryByRole("heading", { name: "Case workflow" })).not.toBeInTheDocument();
        expect(screen.queryByLabelText("Assignee UUID")).not.toBeInTheDocument();
        expect(screen.queryByRole("status", { name: "Case workflow result" })).not.toBeInTheDocument();
      }
      expect(screen.queryAllByRole("textbox", { name: "Assignee UUID" })).toHaveLength(workflow ? 1 : 0);
      expect(
        screen.queryAllByRole("button", { name: "Request additional information" }),
      ).toHaveLength(workflow ? 1 : 0);
      expect(screen.queryAllByRole("button", { name: "Change assignee" })).toHaveLength(workflow ? 1 : 0);
      expect(screen.queryAllByRole("group", { name: "Case resolution" })).toHaveLength(resolution ? 1 : 0);
      expect(screen.queryAllByRole("radio")).toHaveLength(resolution ? 3 : 0);
      expect(screen.queryAllByRole("button", { name: "Resolve case" })).toHaveLength(resolution ? 1 : 0);
      expect(screen.queryAllByText(/cannot be undone/)).toHaveLength(resolution ? 1 : 0);
      expect(view.client.calls.authorizeRequest).toBe(0);
      expect(fetch).not.toHaveBeenCalled();
    },
  );

  it("renders no workflow or resolution DOM and sends nothing without a USER session", async () => {
    // SERVICE principal은 Frontend USER session으로 게시되지 않으므로 이 상태와 같다.
    controlledFetch();
    const view = renderSection({ signedOut: true });
    await waitFor(() => expect(view.client.calls.initialize).toBeGreaterThan(0));
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.queryByRole("heading", { name: "Case workflow" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Resolve case" })).not.toBeInTheDocument();
    expect(screen.queryByText(RESOLUTION_UNAVAILABLE)).not.toBeInTheDocument();
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

describe("CaseWorkflowSection resolution form", () => {
  it("offers an FDS_APPROVER only the resolution fieldset with three unselected native radios, a fixed irreversible warning and no confirm step", async () => {
    const calls = controlledFetch();
    const confirm = vi.fn(() => true);
    vi.stubGlobal("confirm", confirm);
    const user = userEvent.setup();
    renderSection({ roles: ["FDS_APPROVER"] });

    const group = await screen.findByRole("group", { name: "Case resolution" });
    const radiogroup = within(group).getByRole("radiogroup", { name: "Final disposition" });
    const radios = within(radiogroup).getAllByRole("radio");
    expect(radios.map((radio) => radio.getAttribute("value"))).toEqual([
      "NORMAL",
      "FALSE_POSITIVE",
      "CONFIRMED_FRAUD",
    ]);
    for (const [index, name] of ["Normal", "False positive", "Confirmed fraud"].entries()) {
      const radio = within(radiogroup).getByRole("radio", { name });
      expect(radio).toBe(radios[index]);
      expect(radio).toBeInstanceOf(HTMLInputElement);
      expect(radio).toHaveAttribute("type", "radio");
      expect(radio).not.toBeChecked();
    }
    expect(group).toHaveTextContent("cannot be undone");
    expect(group).toHaveTextContent("CASE_RESOLUTION_COMPLETED");
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Request additional information" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Change assignee" })).not.toBeInTheDocument();

    await chooseAndResolve(user, "Confirmed fraud");
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(calls[0].request.method).toBe("POST");
    expect(new URL(calls[0].request.url).pathname).toBe(`/api/v1/cases/${CASE_ID}/resolution`);
    expect(JSON.parse(await calls[0].request.clone().text())).toEqual({
      finalDisposition: "CONFIRMED_FRAUD",
      reasonCode: "CASE_RESOLUTION_COMPLETED",
      expectedVersion: 6,
    });
    expect(confirm).not.toHaveBeenCalled();
  });

  it("resolves by keyboard alone", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    renderSection({ roles: ["FDS_APPROVER"] });
    const first = await screen.findByRole("radio", { name: "Normal" });

    await user.tab();
    expect(first).toHaveFocus();
    await user.keyboard(" ");
    expect(first).toBeChecked();
    const submitButton = screen.getByRole("button", { name: "Resolve case" });
    for (let step = 0; step < 4 && document.activeElement !== submitButton; step += 1) {
      await user.tab();
    }
    expect(submitButton).toHaveFocus();
    await user.keyboard("{Enter}");

    await waitFor(() => expect(calls).toHaveLength(1));
    expect(JSON.parse(await calls[0].request.clone().text())).toMatchObject({
      finalDisposition: "NORMAL",
    });
  });

  it.each([
    ["OPEN", openDetail()],
    ["ADDITIONAL_INFORMATION_REQUIRED", detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" })],
    ["IN_REVIEW without an assignee", detail({ assigneeRef: null })],
    ["IN_REVIEW without reviewStartedAt", detail({ reviewStartedAt: null })],
  ])("shows an FDS_APPROVER a fixed notice and no resolution control for %s", async (_name, baseline) => {
    controlledFetch();
    renderSection({ roles: ["FDS_APPROVER"], detail: baseline });

    expect(await screen.findByText(RESOLUTION_UNAVAILABLE)).toBeVisible();
    const section = workflowSection();
    expect(within(section).queryByRole("radio")).not.toBeInTheDocument();
    expect(within(section).queryByRole("button")).not.toBeInTheDocument();
    expect(within(section).queryByRole("group")).not.toBeInTheDocument();
    expect(section.textContent ?? "").not.toMatch(/reviewStartedAt|assigneeRef|finalDisposition|closedAt/);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("keeps the closed-case notice for an FDS_APPROVER and renders no resolution control", async () => {
    controlledFetch();
    renderSection({ roles: ["FDS_APPROVER"], detail: closedDetail("NORMAL", 6) });

    expect(await screen.findByText("Workflow changes are unavailable for a closed case.")).toBeVisible();
    const section = workflowSection();
    expect(within(section).queryByText(RESOLUTION_UNAVAILABLE)).not.toBeInTheDocument();
    expect(within(section).queryByRole("radio")).not.toBeInTheDocument();
    expect(within(section).queryByRole("button")).not.toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });

  it.each([
    ["IN_REVIEW", detail()],
    ["OPEN", openDetail()],
    ["ADDITIONAL_INFORMATION_REQUIRED", detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" })],
    ["CLOSED", closedDetail()],
  ])("never renders resolution UI for an Analyst-only session in %s", async (_name, baseline) => {
    controlledFetch();
    renderSection({ roles: ["FDS_ANALYST"], detail: baseline });

    await screen.findByRole("heading", { name: "Case workflow", level: 3 });
    expect(screen.queryByRole("group", { name: "Case resolution" })).not.toBeInTheDocument();
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Resolve case" })).not.toBeInTheDocument();
    expect(screen.queryByText(RESOLUTION_UNAVAILABLE)).not.toBeInTheDocument();
    expect(screen.queryByText(/cannot be undone/)).not.toBeInTheDocument();
  });

  it("focuses the first radio, connects a fixed error and sends nothing when no disposition is selected", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const view = renderSection({ roles: ["FDS_APPROVER"] });

    await user.click(await screen.findByRole("button", { name: "Resolve case" }));

    const radiogroup = screen.getByRole("radiogroup", { name: "Final disposition" });
    expect(screen.getByRole("radio", { name: "Normal" })).toHaveFocus();
    expect(radiogroup).toHaveAttribute("aria-invalid", "true");
    expect(radiogroup).toHaveAttribute("aria-required", "true");
    const ids = radiogroup.getAttribute("aria-describedby")?.split(" ") ?? [];
    expect(ids).toEqual(["case-resolution-helper", "case-resolution-error"]);
    for (const id of ids) {
      expect(document.getElementById(id)).not.toBeNull();
    }
    expect(document.getElementById("case-resolution-error")).toHaveTextContent(
      "Choose a final disposition before resolving the case.",
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(view.client.calls.authorizeRequest).toBe(0);
    expect(calls).toHaveLength(0);

    await user.click(screen.getByRole("radio", { name: "False positive" }));
    expect(radiogroup).not.toHaveAttribute("aria-invalid");
    expect(radiogroup).toHaveAttribute("aria-describedby", "case-resolution-helper");
    expect(document.getElementById("case-resolution-error")).toBeNull();
  });

  it("disables the resolution form and marks the section busy through submit and reconciliation, sending one POST under double submit", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const reconcile = vi.fn();
    renderSection({ roles: ["FDS_APPROVER"], reconcile });

    await user.click(await screen.findByRole("radio", { name: "Normal" }));
    await user.dblClick(screen.getByRole("button", { name: "Resolve case" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    const section = workflowSection();
    expect(section).toHaveAttribute("aria-busy", "true");
    for (const radio of screen.getAllByRole("radio")) {
      expect(radio).toBeDisabled();
    }
    expect(screen.getByRole("button", { name: "Resolve case" })).toBeDisabled();

    await answer(calls[0], jsonResponse(resolutionResponse({ finalDisposition: "NORMAL" })));
    expect(section).toHaveAttribute("aria-busy", "true");
    expect(screen.getByText("Refreshing authoritative case information")).toBeVisible();
    expect(reconcile).toHaveBeenCalledWith("detail-audit", 7);
    expect(screen.getByRole("radio", { name: "Normal" })).toBeDisabled();
    expect(calls).toHaveLength(1);
  });

  it("keeps the selected disposition and focuses a fixed resolution error heading on 403", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const reconcile = vi.fn();
    renderSection({ roles: ["FDS_APPROVER"], reconcile });

    await chooseAndResolve(user, "Confirmed fraud");
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

    expect(screen.getAllByRole("alert")).toHaveLength(1);
    const heading = within(screen.getByRole("alert")).getByRole("heading", {
      name: "The resolution was denied",
      level: 4,
    });
    expect(heading).toHaveAttribute("tabindex", "-1");
    expect(heading).toHaveFocus();
    expect(screen.getByRole("radio", { name: "Confirmed fraud" })).toBeChecked();
    expect(document.body.innerHTML).not.toMatch(/PRIVATE|traceId|fieldErrors|actorId/);
    expect(reconcile).not.toHaveBeenCalled();
  });

  it.each([400, 422, 500, 503])(
    "shows fixed resolution server-error copy for %i without reconciliation, keeps the draft and allows only an explicit resubmit",
    async (status) => {
      const calls = controlledFetch();
      const user = userEvent.setup();
      const reconcile = vi.fn();
      renderSection({ roles: ["FDS_APPROVER"], reconcile });

      await chooseAndResolve(user, "False positive");
      await waitFor(() => expect(calls).toHaveLength(1));
      await answer(calls[0], jsonResponse({ code: "PRIVATE", message: "PRIVATE" }, { status }));

      const alert = screen.getByRole("alert");
      expect(
        within(alert).getByRole("heading", { name: "The resolution could not be completed", level: 4 }),
      ).toHaveFocus();
      expect(alert).toHaveTextContent("Your selected disposition has been kept.");
      expect(screen.getByRole("radio", { name: "False positive" })).toBeChecked();
      expect(reconcile).not.toHaveBeenCalled();
      await act(async () => {
        await Promise.resolve();
      });
      expect(calls).toHaveLength(1);

      await user.click(screen.getByRole("button", { name: "Resolve case" }));
      await waitFor(() => expect(calls).toHaveLength(2));
    },
  );

  it("reconciles a resolution conflict, keeps the draft through an eligible refresh and requires an explicit resubmit", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const reconcile = vi.fn();
    const view = renderSection({ roles: ["FDS_APPROVER"], reconcile });

    await chooseAndResolve(user, "Confirmed fraud");
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(calls[0], jsonResponse({ code: "PRIVATE_CONFLICT" }, { status: 409 }));
    expect(reconcile).toHaveBeenCalledWith("detail-notes-audit", 6);

    // D7: eligibility를 유지하는 version refresh와 담당자 변경은 draft를 보존한다.
    view.rerender({
      detail: detail({ concurrencyVersion: 7, assigneeRef: NEXT_ASSIGNEE }),
      generation: 4,
    });
    const alert = await screen.findByRole("alert");
    expect(
      within(alert).getByRole("heading", { name: "The case changed before the resolution" }),
    ).toHaveFocus();
    expect(screen.getByRole("radio", { name: "Confirmed fraud" })).toBeChecked();
    expect(calls).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Resolve case" }));
    await waitFor(() => expect(calls).toHaveLength(2));
    expect(JSON.parse(await calls[1].request.clone().text())).toMatchObject({ expectedVersion: 7 });
  });

  it.each([[["FDS_APPROVER"] as const], [["FDS_ANALYST", "FDS_APPROVER"] as const]])(
    "announces an authoritative resolution once, clears the draft and moves focus to the section heading for %j",
    async (roles) => {
      const calls = controlledFetch();
      const user = userEvent.setup();
      const view = renderSection({ roles });

      await chooseAndResolve(user, "Confirmed fraud");
      await waitFor(() => expect(calls).toHaveLength(1));
      await answer(calls[0], jsonResponse(resolutionResponse()));
      expect(screen.getByRole("status", { name: "Case workflow result" }).textContent).toBe("");

      view.rerender({ detail: closedDetail(), generation: 4 });
      await waitFor(() =>
        expect(screen.getByRole("status", { name: "Case workflow result" })).toHaveTextContent(
          "Case resolved from authoritative case information.",
        ),
      );
      await waitFor(() =>
        expect(screen.getByRole("heading", { name: "Case workflow", level: 3 })).toHaveFocus(),
      );
      expect(screen.getAllByRole("status", { name: "Case workflow result" })).toHaveLength(1);
      expect(screen.getByText("Workflow changes are unavailable for a closed case.")).toBeVisible();
      expect(screen.queryByRole("radio")).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Resolve case" })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Request additional information" })).not.toBeInTheDocument();
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
      expect(calls).toHaveLength(1);
    },
  );

  it("keeps the lane blocked with a fixed error and an explicit refresh when the floor-meeting record does not confirm the resolution", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    const reconcile = vi.fn();
    const view = renderSection({ roles: ["FDS_APPROVER"], reconcile });

    await chooseAndResolve(user, "Confirmed fraud");
    await waitFor(() => expect(calls).toHaveLength(1));
    await answer(calls[0], jsonResponse(resolutionResponse()));

    // floor(7)는 충족했지만 CLOSED가 아니므로 성공을 발표하지 않는다.
    view.rerender({ detail: detail({ concurrencyVersion: 7 }), generation: 4 });
    const alert = await screen.findByRole("alert");
    await waitFor(() =>
      expect(
        within(alert).getByRole("heading", {
          name: "The resolution is not confirmed by the latest case record",
          level: 4,
        }),
      ).toHaveFocus(),
    );
    expect(workflowSection()).toHaveAttribute("aria-busy", "true");
    expect(screen.getByRole("button", { name: "Resolve case" })).toBeDisabled();
    expect(screen.getByRole("status", { name: "Case workflow result" }).textContent).toBe("");
    expect(screen.queryByText("Refreshing authoritative case information")).not.toBeInTheDocument();

    const refresh = within(alert).getByRole("button", { name: "Refresh workflow information" });
    expect(refresh).toBeEnabled();
    await user.click(refresh);
    expect(reconcile).toHaveBeenCalledTimes(2);
    expect(reconcile).toHaveBeenLastCalledWith("detail-audit", 7);
    expect(calls).toHaveLength(1);

    // 요청과 다른 판정의 CLOSED record도 확정하지 않는다.
    view.rerender({ detail: closedDetail("NORMAL"), generation: 5 });
    expect(
      within(screen.getByRole("alert")).getByRole("heading", {
        name: "The resolution is not confirmed by the latest case record",
      }),
    ).toBeVisible();
    expect(screen.getByRole("status", { name: "Case workflow result" }).textContent).toBe("");

    view.rerender({ detail: closedDetail("CONFIRMED_FRAUD"), generation: 6 });
    await waitFor(() =>
      expect(screen.getByRole("status", { name: "Case workflow result" })).toHaveTextContent(
        "Case resolved from authoritative case information.",
      ),
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(calls).toHaveLength(1);
  });

  it("shares the lane with workflow controls for an Analyst+Approver session", async () => {
    const resolutionCalls = controlledFetch();
    const user = userEvent.setup();
    const resolving = renderSection({ roles: ["FDS_ANALYST", "FDS_APPROVER"] });

    await chooseAndResolve(user, "Normal");
    await waitFor(() => expect(resolutionCalls).toHaveLength(1));
    expect(screen.getByRole("button", { name: "Request additional information" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Change assignee" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Assignee UUID" })).toBeDisabled();
    resolving.unmount();
    vi.unstubAllGlobals();

    const statusCalls = controlledFetch();
    renderSection({ roles: ["FDS_ANALYST", "FDS_APPROVER"] });
    await user.click(await screen.findByRole("button", { name: "Request additional information" }));
    await waitFor(() => expect(statusCalls).toHaveLength(1));
    expect(statusCalls[0].request.method).toBe("PATCH");
    for (const radio of screen.getAllByRole("radio")) {
      expect(radio).toBeDisabled();
    }
    expect(screen.getByRole("button", { name: "Resolve case" })).toBeDisabled();
    expect(statusCalls).toHaveLength(1);
  });

  it("resets the draft on case change and eligibility loss but keeps it through an eligible version or assignee refresh", async () => {
    controlledFetch();
    const user = userEvent.setup();
    const view = renderSection({ roles: ["FDS_APPROVER"] });

    await user.click(await screen.findByRole("radio", { name: "False positive" }));
    view.rerender({
      detail: detail({ concurrencyVersion: 7, assigneeRef: NEXT_ASSIGNEE }),
      generation: 4,
    });
    expect(screen.getByRole("radio", { name: "False positive" })).toBeChecked();

    view.rerender({
      detail: detail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", concurrencyVersion: 8 }),
      generation: 5,
    });
    expect(screen.queryByRole("radio")).not.toBeInTheDocument();
    expect(screen.getByText(RESOLUTION_UNAVAILABLE)).toBeVisible();

    view.rerender({ detail: detail({ concurrencyVersion: 9 }), generation: 6 });
    await waitFor(() => expect(screen.getAllByRole("radio")).toHaveLength(3));
    for (const radio of screen.getAllByRole("radio")) {
      expect(radio).not.toBeChecked();
    }

    await user.click(screen.getByRole("radio", { name: "Normal" }));
    expect(screen.getByRole("radio", { name: "Normal" })).toBeChecked();
    view.rerender({
      detail: detail({ caseId: OTHER_CASE_ID, concurrencyVersion: 1 }),
      generation: 0,
    });
    await waitFor(() => {
      for (const radio of screen.getAllByRole("radio")) {
        expect(radio).not.toBeChecked();
      }
    });
    expect(fetch).not.toHaveBeenCalled();
  });
});
