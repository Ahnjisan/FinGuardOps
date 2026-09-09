import { StrictMode, type ReactNode } from "react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthSession } from "../../auth/authClient";
import { AuthProvider } from "../../auth/AuthProvider";
import { createFakeAuthClient, type FakeAuthClient } from "../../test/fakeAuthClient";
import { jsonResponse } from "../../test/mockFetch";

const adapter = vi.hoisted(() => ({ client: null as unknown }));

vi.mock("../../auth/oidcAuthClient", () => ({
  getOidcAuthClient: () => adapter.client,
}));

const { CaseAuditSection, CaseAuditPanel } = await import("./CaseAuditSection");

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const TRACE_ID = "trace_demo_case_audit_01";
const ASSIGNEE_A = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
const ASSIGNEE_B = "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071";
const NOTE_ID = "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60";

const SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  displayName: "Local Analyst",
  roles: ["FDS_ANALYST"],
};

const CREATED = {
  action: "CASE_CREATED",
  reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
  actorType: "SYSTEM",
  changedAt: "2026-03-08T09:10:11.123456Z",
  beforeSummary: null,
  afterSummary: { caseStatus: "OPEN" },
  metadata: {},
};

const LINKED = {
  action: "CASE_TRANSACTION_LINKED",
  reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
  actorType: "SYSTEM",
  changedAt: "2026-03-08T09:12:00.000001Z",
  beforeSummary: null,
  afterSummary: { linked: true },
  metadata: {},
};

const REVIEW_STARTED = {
  action: "CASE_STATUS_CHANGED",
  reasonCode: "CASE_REVIEW_STARTED",
  actorType: "USER",
  changedAt: "2026-03-09T00:01:02.000002Z",
  beforeSummary: { caseStatus: "OPEN", assigneeRef: null },
  afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_A },
  metadata: {},
};

const ASSIGNEE_RELEASED = {
  action: "CASE_ASSIGNEE_CHANGED",
  reasonCode: "CASE_ASSIGNEE_RELEASED",
  actorType: "USER",
  changedAt: "2026-03-09T02:03:04.000010Z",
  beforeSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: ASSIGNEE_B },
  afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
  metadata: {},
};

const RESOLVED = {
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

const NOTE_CREATED = {
  action: "CASE_NOTE_CREATED",
  reasonCode: "CASE_INVESTIGATION_NOTE_ADDED",
  actorType: "USER",
  changedAt: "2026-03-10T05:06:07.000000Z",
  beforeSummary: null,
  afterSummary: null,
  metadata: { noteId: NOTE_ID },
};

/** All six actions, and between them every summary shape the contract has. */
const ALL_ACTIONS = [CREATED, LINKED, REVIEW_STARTED, ASSIGNEE_RELEASED, RESOLVED, NOTE_CREATED];

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
  content: readonly Record<string, unknown>[] = [CREATED],
  page: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    caseId: CASE_ID,
    content,
    page: pageMetadata({
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : 1,
      ...page,
    }),
    traceId: TRACE_ID,
  };
}

/** A full page, because a page before the last one must carry `size` entries. */
function fullPage(lead: Record<string, unknown>, size = 20): Record<string, unknown>[] {
  return [lead, ...Array.from({ length: size - 1 }, () => CREATED)];
}

interface PendingCall {
  readonly promise: Promise<Response>;
  readonly request: Request;
  settle: (response: Response) => void;
  fail: (error: unknown) => void;
}

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

function renderSection(client: FakeAuthClient, caseId: string = CASE_ID) {
  const tree: ReactNode = (
    <AuthProvider client={client}>
      <CaseAuditSection caseId={caseId} />
    </AuthProvider>
  );
  return render(<StrictMode>{tree}</StrictMode>);
}

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
  const client = createFakeAuthClient({ initialSession: SESSION });
  adapter.client = client;
  return client;
}

/** Loads the section and answers its one request with a page. */
async function showTrail(
  content: readonly Record<string, unknown>[] = ALL_ACTIONS,
  page: Record<string, unknown> = {},
): Promise<PendingCall[]> {
  const { calls } = controlledFetch();
  renderSection(signedIn());
  await settle();
  await answerWith(calls[0], auditBody(content, page));
  await waitFor(() => {
    expect(screen.queryByText("Loading audit history...")).not.toBeInTheDocument();
  });
  return calls;
}

/** The `<article>` for one entry, by its position in the list. */
function entryAt(index: number): HTMLElement {
  const articles = screen.getAllByRole("article");
  return articles[index];
}

/** The `<dd>` that follows the named `<dt>` inside one entry. */
function valueOf(entry: HTMLElement, term: string): HTMLElement {
  const dt = within(entry).getByText(term, { selector: "dt" });
  const dd = dt.nextElementSibling;
  if (!(dd instanceof HTMLElement) || dd.tagName !== "DD") {
    throw new Error(`No value found for ${term}`);
  }
  return dd;
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

describe("CaseAuditSection request", () => {
  it("asks the audit endpoint for exactly this case, with the fixed sort", async () => {
    const { calls, spy } = controlledFetch();
    renderSection(signedIn());
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    const url = new URL(calls[0].request.url);
    expect(url.origin).toBe("http://localhost:8080");
    expect(url.pathname).toBe(`/api/v1/cases/${CASE_ID}/audit-logs`);
    expect(url.search).toBe("?page=0&size=20&sort=changedAt%2Cdesc");
    expect(calls[0].request.method).toBe("GET");
  });

  it("shows a heading and a loading state before the answer", async () => {
    controlledFetch();
    renderSection(signedIn());
    await settle();

    expect(
      screen.getByRole("heading", { name: "Audit history", level: 3 }),
    ).toBeInTheDocument();
    expect(screen.getByText("Loading audit history...")).toBeInTheDocument();
    const status = screen.getByRole("status", { name: "Audit history status" });
    expect(status).toHaveTextContent("Loading audit history");
    expect(status).toHaveAttribute("aria-live", "polite");
  });

  it("makes no request and shows nothing to read without a session", async () => {
    const { spy } = controlledFetch();
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    render(
      <StrictMode>
        <AuthProvider client={client}>
          <CaseAuditSection caseId={CASE_ID} />
        </AuthProvider>
      </StrictMode>,
    );
    await settle();

    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("makes no request for an address that does not name a case", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();

    renderSection(client, "5C2D1E0F-7A8B-4C9D-9E0F-1A2B3C4D5E60");
    await settle();

    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });
});

describe("CaseAuditSection entries", () => {
  it("renders one article per entry inside an ordered list", async () => {
    await showTrail();

    expect(screen.getAllByRole("article")).toHaveLength(6);
    expect(document.querySelectorAll("ol.audit__list > li")).toHaveLength(6);
    expect(screen.getAllByRole("list").some((list) => list.tagName === "OL")).toBe(true);
  });

  it("shows every action as the Backend enum code, newest first", async () => {
    await showTrail();

    const actions = screen
      .getAllByRole("heading", { level: 4 })
      .map((heading) => heading.textContent);
    expect(actions).toEqual([
      "CASE_CREATED",
      "CASE_TRANSACTION_LINKED",
      "CASE_STATUS_CHANGED",
      "CASE_ASSIGNEE_CHANGED",
      "CASE_RESOLVED",
      "CASE_NOTE_CREATED",
    ]);
  });

  it("shows the reason code and the actor type as raw codes", async () => {
    await showTrail();

    expect(valueOf(entryAt(0), "Reason code")).toHaveTextContent("CASE_REQUIRED_BY_RISK_POLICY");
    expect(valueOf(entryAt(0), "Actor type")).toHaveTextContent("SYSTEM");
    expect(valueOf(entryAt(2), "Reason code")).toHaveTextContent("CASE_REVIEW_STARTED");
    expect(valueOf(entryAt(2), "Actor type")).toHaveTextContent("USER");
  });

  it("never translates a status code into the label the record screen uses", async () => {
    await showTrail();

    // The counterexample for the section's own rule. `IN_REVIEW` reads as "In
    // review" on the case record; here it stays the code.
    const after = valueOf(entryAt(2), "After");
    expect(after).toHaveTextContent("IN_REVIEW");
    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("In review");
    expect(rendered).not.toContain("Confirmed fraud");
  });

  it("states Seoul wall clock and keeps the untouched UTC value for the machine", async () => {
    await showTrail();

    const changed = within(valueOf(entryAt(2), "Changed")).getByText(/KST$/);
    expect(changed.tagName).toBe("TIME");
    expect(changed).toHaveAttribute("datetime", "2026-03-09T00:01:02.000002Z");
    expect(changed).toHaveTextContent("2026-03-09 09:01:02 KST");
    expect(
      within(entryAt(2)).getByRole("heading", {
        name: "CASE_STATUS_CHANGED, changed 2026-03-09 09:01:02 KST",
      }),
    ).toBeInTheDocument();
  });

  it("names the before and the after side separately, always both", async () => {
    await showTrail();

    for (const index of [0, 1, 2, 3, 4, 5]) {
      expect(within(entryAt(index)).getByText("Before", { selector: "dt" })).toBeInTheDocument();
      expect(within(entryAt(index)).getByText("After", { selector: "dt" })).toBeInTheDocument();
    }
  });

  it("shows every summary shape the contract has", async () => {
    await showTrail();

    // CaseStatusSummary.
    expect(valueOf(entryAt(0), "After")).toHaveTextContent("Case status");
    expect(valueOf(entryAt(0), "After")).toHaveTextContent("OPEN");
    // LinkedSummary.
    expect(valueOf(entryAt(1), "After")).toHaveTextContent("Linked");
    expect(valueOf(entryAt(1), "After")).toHaveTextContent("true");
    // WorkflowSummary on both sides.
    expect(valueOf(entryAt(2), "Before")).toHaveTextContent("OPEN");
    expect(valueOf(entryAt(2), "After")).toHaveTextContent(ASSIGNEE_A);
    // ResolutionSummary, the only three-field shape.
    const resolved = valueOf(entryAt(4), "After");
    expect(resolved).toHaveTextContent("CLOSED");
    expect(resolved).toHaveTextContent(ASSIGNEE_A);
    expect(resolved).toHaveTextContent("CONFIRMED_FRAUD");
  });

  it("names an absent summary rather than leaving it blank", async () => {
    await showTrail();

    expect(valueOf(entryAt(0), "Before")).toHaveTextContent("Not applicable");
    expect(valueOf(entryAt(5), "Before")).toHaveTextContent("Not applicable");
    expect(valueOf(entryAt(5), "After")).toHaveTextContent("Not applicable");
  });

  it("names an absent assignee differently from an absent summary", async () => {
    await showTrail();

    // Two absences, two words. Sharing one would say a released assignee and a
    // missing before-state were the same fact.
    expect(valueOf(entryAt(3), "After")).toHaveTextContent("Unassigned");
    expect(valueOf(entryAt(3), "After")).not.toHaveTextContent("Not applicable");
    expect(valueOf(entryAt(2), "Before")).toHaveTextContent("Unassigned");
  });

  it("shows a note identifier as text and never as a link", async () => {
    await showTrail();

    const note = valueOf(entryAt(5), "Note ID");
    expect(note).toHaveTextContent(NOTE_ID);
    expect(note.querySelector("a")).toBeNull();
    expect(screen.queryAllByRole("link")).toHaveLength(0);
  });

  it("gives the note identifier only to the action that carries one", async () => {
    await showTrail();

    for (const index of [0, 1, 2, 3, 4]) {
      expect(within(entryAt(index)).queryByText("Note ID")).not.toBeInTheDocument();
    }
  });

  it("never repeats the case identifier on an entry", async () => {
    await showTrail();

    // The whole section belongs to one case, which the page heading already
    // names. Repeating it once per entry would be noise, and it is not in the
    // published state at all.
    expect(document.body.innerHTML).not.toContain(CASE_ID);
  });

  it("carries no trace id into the section", async () => {
    await showTrail();

    expect(document.body.innerHTML).not.toContain(TRACE_ID);
    expect(document.body.innerHTML).not.toContain("traceId");
  });

  it("prints a long reference in full, once, and in no attribute", async () => {
    await showTrail();

    const occurrences = document.body.innerHTML.split(ASSIGNEE_A).length - 1;
    // Twice: the after-state of the review start, and both sides of the
    // resolution. Each is a distinct recorded value, not a duplicate of one.
    expect(occurrences).toBe(3);
    for (const element of Array.from(document.querySelectorAll("*"))) {
      for (const attribute of Array.from(element.attributes)) {
        expect(attribute.value).not.toContain(ASSIGNEE_A);
      }
    }
  });

  it("offers no mutation control", async () => {
    await showTrail();

    // The pager is not a mutation: it asks the same read for a different page.
    expect(document.querySelectorAll("form")).toHaveLength(0);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    const buttons = screen.getAllByRole("button").map((button) => button.textContent);
    expect(buttons).toEqual(["Previous", "Next"]);
    expect(screen.getAllByRole("combobox")).toHaveLength(1);
    expect(screen.getByRole("combobox")).toHaveAccessibleName("Entries per page");
  });

  it("labels its own headings and articles with ids that exist once", async () => {
    await showTrail();

    for (const element of Array.from(document.querySelectorAll("[aria-labelledby]"))) {
      const id = element.getAttribute("aria-labelledby") ?? "";
      expect(document.querySelectorAll(`#${CSS.escape(id)}`)).toHaveLength(1);
    }
  });
});

describe("CaseAuditSection empty states", () => {
  it("says a case has no audit history at all", async () => {
    await showTrail([], { totalElements: 0, totalPages: 0, last: true });

    expect(screen.getByText("No audit history recorded.")).toBeInTheDocument();
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    expect(screen.getByRole("status", { name: "Audit history status" })).toHaveTextContent(
      "No audit entries.",
    );
  });

  it("says a page is past the end without denying the trail exists", async () => {
    render(
      <CaseAuditPanel
        state={{
          status: "empty",
          data: {
            content: [],
            page: {
              number: 99,
              size: 20,
              totalElements: 137,
              totalPages: 7,
              first: false,
              last: true,
            },
          },
        }}
        onPageChange={() => undefined}
        onPageSizeChange={() => undefined}
        onRetry={() => undefined}
      />,
    );

    expect(screen.getByText("No audit entries on this page.")).toBeInTheDocument();
    // The counterexample the two sentences exist for.
    expect(screen.queryByText("No audit history recorded.")).not.toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Audit history status" })).toHaveTextContent(
      "No entries on this page of 137.",
    );
  });
});

describe("CaseAuditSection pagination", () => {
  async function firstOfSeven(): Promise<PendingCall[]> {
    const { calls } = controlledFetch();
    renderSection(signedIn());
    await settle();
    await answerWith(
      calls[0],
      auditBody(fullPage(CREATED), { totalElements: 137, totalPages: 7, last: false }),
    );
    await waitFor(() => {
      expect(screen.getAllByRole("article")).toHaveLength(20);
    });
    return calls;
  }

  it("names its pager so it cannot be mistaken for the case list pager", async () => {
    await firstOfSeven();

    const pager = screen.getByRole("navigation", { name: "Audit history pages" });
    expect(pager).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Case pages" })).not.toBeInTheDocument();
  });

  it("states the window and the position it is showing", async () => {
    await firstOfSeven();

    expect(screen.getByRole("status", { name: "Audit history status" })).toHaveTextContent(
      "Showing 1-20 of 137.",
    );
    expect(screen.getByText("Page 1 of 7")).toBeInTheDocument();
  });

  it("disables Previous on the first page and Next on the last", async () => {
    const calls = await firstOfSeven();

    expect(screen.getByRole("button", { name: "Previous" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next" })).toBeEnabled();

    const user = userEvent.setup();
    await user.click(screen.getByRole("button", { name: "Next" }));
    await settle();
    await answerWith(
      calls[1],
      auditBody(Array.from({ length: 17 }, () => LINKED), {
        number: 6,
        totalElements: 137,
        totalPages: 7,
        first: false,
        last: true,
      }),
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Next" })).toBeDisabled();
    });
    expect(screen.getByRole("button", { name: "Previous" })).toBeEnabled();
  });

  it("asks for the next page and shows nothing of the previous one while it loads", async () => {
    const calls = await firstOfSeven();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Next" }));

    // The counterexample this exists for. Keeping page 1 on screen under the
    // page 2 heading would show entries that are not the ones asked for.
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    expect(screen.getByText("Loading audit history...")).toBeInTheDocument();
    await settle();
    expect(new URL(calls[1].request.url).search).toBe("?page=1&size=20&sort=changedAt%2Cdesc");
  });

  it("offers exactly three page sizes and returns to the first page on a change", async () => {
    const calls = await firstOfSeven();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Next" }));
    await settle();
    await answerWith(
      calls[1],
      auditBody(fullPage(CREATED), {
        number: 1,
        totalElements: 137,
        totalPages: 7,
        first: false,
        last: false,
      }),
    );
    await waitFor(() => {
      expect(screen.getByText("Page 2 of 7")).toBeInTheDocument();
    });

    const sizes = within(screen.getByRole("combobox"))
      .getAllByRole("option")
      .map((option) => option.textContent);
    expect(sizes).toEqual(["20", "50", "100"]);

    await user.selectOptions(screen.getByRole("combobox"), "100");
    await settle();

    expect(new URL(calls[2].request.url).search).toBe("?page=0&size=100&sort=changedAt%2Cdesc");
  });

  it("changes no address bar entry when the reader pages", async () => {
    const before = window.location.href;
    await firstOfSeven();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Next" }));
    await settle();

    expect(window.location.href).toBe(before);
  });
});

describe("CaseAuditSection failures", () => {
  async function failWith(status: number, body: unknown = {}): Promise<PendingCall[]> {
    const { calls } = controlledFetch();
    renderSection(signedIn());
    await settle();
    await answerWith(calls[0], body, status);
    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });
    return calls;
  }

  it("reports a 404 without offering a retry", async () => {
    await failWith(404, { code: "CASE_NOT_FOUND", message: "no such case", traceId: TRACE_ID });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Audit history not found");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    expect(alert.textContent ?? "").not.toContain("404");
    expect(alert.textContent ?? "").not.toContain("CASE_NOT_FOUND");
    expect(alert.textContent ?? "").not.toContain("no such case");
    expect(document.body.innerHTML).not.toContain(TRACE_ID);
  });

  it("reports a 403 without offering a retry", async () => {
    await failWith(403, { code: "ACCESS_DENIED", message: "case:read required" });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Access denied");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    expect(alert.textContent ?? "").not.toContain("case:read");
  });

  it("reports a locally missing credential as authentication required without retry", async () => {
    const { spy } = controlledFetch();
    const client = createFakeAuthClient({ initialSession: SESSION, accessToken: "" });
    adapter.client = client;
    renderSection(client);
    await settle();

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("Your session ended");
    });
    expect(within(screen.getByRole("alert")).queryByRole("button")).not.toBeInTheDocument();
    expect(spy).not.toHaveBeenCalled();
  });

  it("reports an unmapped Backend status without naming it", async () => {
    await failWith(503, { code: "SERVICE_UNAVAILABLE", message: "upstream down" });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("The audit history could not be loaded");
    expect(alert.textContent ?? "").not.toContain("503");
    expect(alert.textContent ?? "").not.toContain("upstream");
    expect(
      within(alert).getByRole("button", { name: "Try loading the audit history again" }),
    ).toBeInTheDocument();
  });

  it("reports a network failure", async () => {
    const { calls } = controlledFetch();
    renderSection(signedIn());
    await settle();
    await act(async () => {
      calls[0].fail(new TypeError("connection refused"));
      await calls[0].promise.catch(() => undefined);
    });

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("The backend could not be reached");
    });
  });

  it("reports a timeout separately from a network failure", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => new Promise<Response>(() => {})),
    );
    renderSection(signedIn());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });

    expect(screen.getByRole("alert")).toHaveTextContent(
      "The audit history took too long to load",
    );
    vi.useRealTimers();
  });

  it("refuses a page carrying one malformed entry, showing none of it", async () => {
    const { calls } = controlledFetch();
    renderSection(signedIn());
    await settle();
    await answerWith(
      calls[0],
      auditBody([CREATED, { ...REVIEW_STARTED, reasonCode: "CASE_RESOLUTION_COMPLETED" }]),
    );

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent(
        "The audit history could not be read",
      );
    });
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    expect(document.body.textContent ?? "").not.toContain("CASE_CREATED");
  });

  it("sends exactly one request per press of the retry control", async () => {
    const calls = await failWith(500);
    const user = userEvent.setup();

    await user.click(
      screen.getByRole("button", { name: "Try loading the audit history again" }),
    );
    await settle();
    expect(calls).toHaveLength(2);

    await answerWith(calls[1], {}, 500);
    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });
    await user.click(
      screen.getByRole("button", { name: "Try loading the audit history again" }),
    );
    await settle();
    expect(calls).toHaveLength(3);
  });

  it("focuses each newly published retryable error heading once", async () => {
    const calls = await failWith(503);
    const heading = screen.getByRole("heading", {
      name: "The audit history could not be loaded",
      level: 4,
    });
    expect(heading).toHaveFocus();

    const user = userEvent.setup();
    const retry = screen.getByRole("button", { name: "Try loading the audit history again" });
    retry.focus();
    await user.click(retry);
    await settle();
    await answerWith(calls[1], {}, 503);

    await waitFor(() => {
      expect(
        screen.getByRole("heading", {
          name: "The audit history could not be loaded",
          level: 4,
        }),
      ).toHaveFocus();
    });
  });

  it("does not retry or poll on its own", async () => {
    const calls = await failWith(500);

    await settle();
    await settle();

    expect(calls).toHaveLength(1);
  });

  it("shows no pager while the section is not showing a page", async () => {
    await failWith(500);

    expect(
      screen.queryByRole("navigation", { name: "Audit history pages" }),
    ).not.toBeInTheDocument();
  });
});

describe("CaseAuditPanel as the geometry fixture mounts it", () => {
  /**
   * The pure panel, with no hook, no session and no transport under it.
   *
   * This is the shape the browser geometry fixture renders, so the fixture is
   * exercising a component this suite has already tested rather than a copy of
   * one.
   */
  it("renders a settled page with no auth context at all", () => {
    render(
      <CaseAuditPanel
        state={{
          status: "success",
          data: {
            content: [
              {
                action: "CASE_NOTE_CREATED",
                reasonCode: "CASE_INVESTIGATION_NOTE_ADDED",
                actorType: "USER",
                changedAt: "2026-03-10T05:06:07.000000Z",
                beforeSummary: null,
                afterSummary: null,
                metadata: { noteId: NOTE_ID },
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
        }}
        onPageChange={() => undefined}
        onPageSizeChange={() => undefined}
        onRetry={() => undefined}
      />,
    );

    expect(screen.getByRole("heading", { name: "Audit history", level: 3 })).toBeInTheDocument();
    expect(screen.getAllByRole("article")).toHaveLength(1);
    expect(screen.getByText(NOTE_ID)).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "Audit history pages" })).toBeInTheDocument();
  });

  it("keeps the authoritative audit page beside an isolated refresh failure", async () => {
    const user = userEvent.setup();
    const onRefresh = vi.fn();
    render(
      <CaseAuditPanel
        state={{
          status: "success",
          data: {
            content: [{
              action: "CASE_NOTE_CREATED",
              reasonCode: "CASE_INVESTIGATION_NOTE_ADDED",
              actorType: "USER",
              changedAt: "2026-03-10T05:06:07.000000Z",
              beforeSummary: null,
              afterSummary: null,
              metadata: { noteId: NOTE_ID },
            }],
            page: { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true },
          },
        }}
        onPageChange={() => undefined}
        onPageSizeChange={() => undefined}
        onRetry={() => undefined}
        refreshState="failed"
        onRefresh={onRefresh}
      />,
    );
    expect(screen.getByRole("article")).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent("submission result is unchanged");
    await user.click(screen.getByRole("button", { name: "Refresh audit history" }));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });

  it("renders each settled refusal without a page under it", () => {
    const { unmount } = render(
      <CaseAuditPanel
        state={{ status: "forbidden" }}
        onPageChange={() => undefined}
        onPageSizeChange={() => undefined}
        onRetry={() => undefined}
      />,
    );

    expect(screen.getByRole("alert")).toHaveTextContent("Access denied");
    expect(screen.queryAllByRole("article")).toHaveLength(0);
    unmount();

    render(
      <CaseAuditPanel
        state={{ status: "idle" }}
        onPageChange={() => undefined}
        onPageSizeChange={() => undefined}
        onRetry={() => undefined}
      />,
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Audit history status" })).toHaveTextContent(
      "No audit history requested.",
    );
  });
});
