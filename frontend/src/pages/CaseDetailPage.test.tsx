import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { RouteObject } from "react-router-dom";
import type { AuthSession } from "../auth/authClient";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import { renderRoutesWithAuth } from "../test/renderWithAuth";

const adapter = vi.hoisted(() => ({ client: null as unknown }));

vi.mock("../auth/oidcAuthClient", () => ({
  getOidcAuthClient: () => adapter.client,
}));

const { CaseDetailPage } = await import("./CaseDetailPage");

const TRACE_ID = "trace_demo_case_detail_01";
const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const DETAIL_ROUTE = `/cases/${CASE_ID}`;
const ASSIGNEE_REF = "analyst_ref_demo_a7f2";

/** Backend's own bound on `assigneeRef`: exactly 128 characters. */
const LONG_ASSIGNEE_REF =
  "assignee-reference-0123456789abcdef-" +
  "assignee-reference-0123456789abcdef-" +
  "assignee-reference-0123456789abcdef-" +
  "assignee-reference-0";

const SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  displayName: "Local Analyst",
  roles: ["FDS_ANALYST"],
};

/**
 * The page under its own route, with no shell around it.
 *
 * The shell, the capability guard and the address-bar boundaries are the
 * router's tests. What is exercised here is the screen: its states, what it
 * displays, and what it refuses to display.
 */
const ROUTES: RouteObject[] = [
  { path: "/cases/:caseId", element: <CaseDetailPage /> },
  { path: "/cases", element: <p>Case list stands in here.</p> },
];

function caseBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    case: {
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

function controlledFetch(auditStatus = 200, noteStatus = 200): {
  readonly calls: PendingCall[];
  readonly spy: ReturnType<typeof vi.fn>;
  readonly auditRequests: Request[];
  readonly noteRequests: Request[];
} {
  const calls: PendingCall[] = [];
  const auditRequests: Request[] = [];
  const noteRequests: Request[] = [];
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
  const transport = vi.fn().mockImplementation((request: Request) => {
    const url = new URL(request.url);
    if (url.pathname.endsWith("/notes")) {
      noteRequests.push(request);
      return Promise.resolve(
        jsonResponse(
          noteStatus === 200
            ? {
                items: [],
                page: {
                  number: 0,
                  size: 20,
                  totalElements: 0,
                  totalPages: 0,
                  first: true,
                  last: true,
                },
                traceId: "trace_demo_case_notes_01",
              }
            : {
                code: "NOTES_BACKEND_PRIVATE_CODE",
                message: "notes backend private message",
                traceId: "trace_demo_case_notes_private",
              },
          { status: noteStatus },
        ),
      );
    }
    if (url.pathname.endsWith("/audit-logs")) {
      auditRequests.push(request);
      const caseId = url.pathname.split("/").at(-2) ?? "";
      const body =
        auditStatus === 200
          ? {
              caseId,
              content: [],
              page: {
                number: 0,
                size: 20,
                totalElements: 0,
                totalPages: 0,
                first: true,
                last: true,
              },
              traceId: "trace_demo_case_audit_01",
            }
          : {
              code: "AUDIT_BACKEND_PRIVATE_CODE",
              message: "audit backend private message",
              traceId: "trace_demo_case_audit_private",
            };
      return Promise.resolve(jsonResponse(body, { status: auditStatus }));
    }
    return spy(request);
  });
  vi.stubGlobal("fetch", transport);
  return { calls, spy, auditRequests, noteRequests };
}

function controlledAllCaseReads(): {
  readonly calls: PendingCall[];
  readonly detail: PendingCall[];
  readonly notes: PendingCall[];
  readonly audit: PendingCall[];
  readonly spy: ReturnType<typeof vi.fn>;
} {
  const calls: PendingCall[] = [];
  const detail: PendingCall[] = [];
  const notes: PendingCall[] = [];
  const audit: PendingCall[] = [];
  const spy = vi.fn().mockImplementation((request: Request) => {
    let settleCall!: (response: Response) => void;
    let fail!: (error: unknown) => void;
    const promise = new Promise<Response>((resolve, reject) => {
      settleCall = resolve;
      fail = reject;
    });
    promise.catch(() => undefined);
    const call = { promise, request, settle: settleCall, fail };
    calls.push(call);
    const pathname = new URL(request.url).pathname;
    if (pathname.endsWith("/notes")) {
      notes.push(call);
    } else if (pathname.endsWith("/audit-logs")) {
      audit.push(call);
    } else {
      detail.push(call);
    }
    return promise;
  });
  vi.stubGlobal("fetch", spy);
  return { calls, detail, notes, audit, spy };
}

function notesBody(content = "Visible investigation note"): Record<string, unknown> {
  return {
    items: [
      {
        noteId: "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60",
        caseId: CASE_ID,
        authorType: "USER",
        authorRef: SESSION.subject,
        content,
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
    traceId: "trace_demo_case_notes_visible",
  };
}

function auditBody(): Record<string, unknown> {
  return {
    caseId: CASE_ID,
    content: [
      {
        action: "CASE_CREATED",
        reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
        actorType: "SYSTEM",
        changedAt: "2026-03-08T09:10:11.123456Z",
        beforeSummary: null,
        afterSummary: { caseStatus: "OPEN" },
        metadata: {},
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
    traceId: "trace_demo_case_audit_visible",
  };
}

function createdNoteBody(content: string, concurrencyVersion = 5): Record<string, unknown> {
  return {
    noteId: "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071",
    caseId: CASE_ID,
    authorType: "USER",
    authorRef: "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
    content,
    createdAt: "2026-09-02T01:00:00.123456Z",
    concurrencyVersion,
    traceId: "trace_demo_note_created_floor",
  };
}

function renderPage(client: FakeAuthClient, path: string = DETAIL_ROUTE) {
  return renderRoutesWithAuth(ROUTES, { client, initialEntries: [path] });
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

/** Loads the screen and answers its one request with a record. */
async function showRecord(overrides: Record<string, unknown> = {}): Promise<void> {
  const { calls } = controlledFetch();
  renderPage(signedIn());
  await settle();
  await answerWith(calls[0], caseBody(overrides));
  await waitFor(() => {
    expect(screen.getByRole("heading", { name: "Case", level: 3 })).toBeInTheDocument();
  });
}

/** The `<dd>` that follows the named `<dt>`. */
function valueOf(term: string): HTMLElement {
  const dt = screen.getByText(term, { selector: "dt" });
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

describe("CaseDetailPage request", () => {
  it("asks the detail endpoint for exactly the case in the address", async () => {
    const { calls, spy } = controlledFetch();
    renderPage(signedIn());
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    const url = new URL(calls[0].request.url);
    expect(url.origin).toBe("http://localhost:8080");
    expect(url.pathname).toBe(`/api/v1/cases/${CASE_ID}`);
    expect(url.search).toBe("");
    expect(calls[0].request.method).toBe("GET");
  });

  it("shows a heading naming the case, a way back, and a loading state", async () => {
    controlledFetch();
    renderPage(signedIn());
    await settle();

    expect(
      screen.getByRole("heading", { name: `Case ${CASE_ID}`, level: 2 }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to cases" })).toHaveAttribute(
      "href",
      "/cases",
    );
    expect(screen.getByText("Loading case...")).toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Case record status" })).toHaveTextContent(
      "Loading case",
    );
    expect(screen.getByRole("status", { name: "Case record status" })).toHaveAttribute(
      "aria-live",
      "polite",
    );
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("CaseDetailPage record", () => {
  it("shows every field the detail contract carries, and only those", async () => {
    await showRecord();

    expect(valueOf("Case ID")).toHaveTextContent(CASE_ID);
    expect(valueOf("Case status")).toHaveTextContent("In review");
    expect(valueOf("Final disposition")).toHaveTextContent("Not decided");
    expect(valueOf("Assignee")).toHaveTextContent(ASSIGNEE_REF);
    expect(valueOf("Related transactions")).toHaveTextContent("3");
    expect(valueOf("Created")).toHaveTextContent("2026-07-24 10:15:33 KST");
    expect(valueOf("Review started")).toHaveTextContent("2026-07-24 10:25:00 KST");
    expect(valueOf("Closed")).toHaveTextContent("Not closed");
    expect(valueOf("Last changed")).toHaveTextContent("2026-07-24 11:05:10 KST");
    expect(valueOf("Concurrency version")).toHaveTextContent("4");

    // Ten names for the ten contract fields. An eleventh would be a field this
    // console invented.
    expect(document.querySelectorAll("dt")).toHaveLength(10);
    expect(document.querySelectorAll("dd")).toHaveLength(10);
  });

  it("never names a field the contract does not carry", async () => {
    await showRecord();

    const terms = Array.from(document.querySelectorAll("dt")).map((dt) => dt.textContent);
    expect(terms).toEqual([
      "Case ID",
      "Case status",
      "Final disposition",
      "Assignee",
      "Related transactions",
      "Created",
      "Review started",
      "Closed",
      "Last changed",
      "Concurrency version",
    ]);
    // The change instant is `lastChangedAt`, and it is called that. `Updated`
    // is a name this response does not have.
    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("Updated");
    expect(rendered).not.toContain("updatedAt");
  });

  it("states Seoul wall clock and keeps the untouched UTC value for the machine", async () => {
    await showRecord({ closedAt: "2026-07-25T03:00:00Z", caseStatus: "CLOSED" });

    const created = within(valueOf("Created")).getByText(/KST$/);
    expect(created.tagName).toBe("TIME");
    expect(created).toHaveAttribute("datetime", "2026-07-24T01:15:33Z");
    expect(within(valueOf("Review started")).getByText(/KST$/)).toHaveAttribute(
      "datetime",
      "2026-07-24T01:25:00Z",
    );
    expect(within(valueOf("Closed")).getByText(/KST$/)).toHaveAttribute(
      "datetime",
      "2026-07-25T03:00:00Z",
    );
    expect(within(valueOf("Last changed")).getByText(/KST$/)).toHaveAttribute(
      "datetime",
      "2026-07-24T02:05:10Z",
    );
  });

  it("keeps a fractional-second instant exactly as the Backend wrote it", async () => {
    // The contract admits one to nine fractional digits. The machine-readable
    // value is the Backend's own string; the reading beside it is the second.
    await showRecord({ lastChangedAt: "2026-07-24T02:05:10.123456789Z" });

    const changed = within(valueOf("Last changed")).getByText(/KST$/);
    expect(changed).toHaveAttribute("datetime", "2026-07-24T02:05:10.123456789Z");
    expect(valueOf("Last changed")).toHaveTextContent("2026-07-24 11:05:10 KST");
  });

  it.each([
    ["finalDisposition", "Final disposition", "Not decided"],
    ["assigneeRef", "Assignee", "Unassigned"],
    ["reviewStartedAt", "Review started", "Not started"],
    ["closedAt", "Closed", "Not closed"],
  ])("names the absence of %s rather than leaving a blank", async (field, term, label) => {
    await showRecord({ [field]: null });

    expect(valueOf(term)).toHaveTextContent(label);
    // A phrase, not a time: nothing claims a machine-readable moment for a
    // milestone that has not happened.
    expect(valueOf(term).querySelector("time")).toBeNull();
  });

  it("shows all four fixed phrases together when every nullable field is null", async () => {
    await showRecord({
      finalDisposition: null,
      assigneeRef: null,
      reviewStartedAt: null,
      closedAt: null,
    });

    expect(valueOf("Final disposition")).toHaveTextContent("Not decided");
    expect(valueOf("Assignee")).toHaveTextContent("Unassigned");
    expect(valueOf("Review started")).toHaveTextContent("Not started");
    expect(valueOf("Closed")).toHaveTextContent("Not closed");
    // Still ten fields: a null is a value with a name, not a row that vanishes.
    expect(document.querySelectorAll("dd")).toHaveLength(10);
  });

  it.each([
    ["OPEN", "Open"],
    ["IN_REVIEW", "In review"],
    ["ADDITIONAL_INFORMATION_REQUIRED", "Information required"],
    ["CLOSED", "Closed"],
  ])("labels the %s case status", async (status, label) => {
    await showRecord({ caseStatus: status });

    expect(valueOf("Case status")).toHaveTextContent(label);
  });

  it.each([
    ["NORMAL", "Normal"],
    ["FALSE_POSITIVE", "False positive"],
    ["CONFIRMED_FRAUD", "Confirmed fraud"],
  ])("labels the %s final disposition", async (disposition, label) => {
    await showRecord({ caseStatus: "CLOSED", finalDisposition: disposition });

    expect(valueOf("Final disposition")).toHaveTextContent(label);
  });

  it("shows the case status as a word and a mark, not as colour alone", async () => {
    await showRecord();

    const badge = within(valueOf("Case status")).getByText("In review");
    expect(badge.className).toContain("badge--info");
    expect(badge.querySelector(".badge__mark")).not.toBeNull();
  });

  it("shows the final disposition as a word rather than a colour", async () => {
    await showRecord({ caseStatus: "CLOSED", finalDisposition: "CONFIRMED_FRAUD" });

    const disposition = valueOf("Final disposition");
    expect(disposition).toHaveTextContent("Confirmed fraud");
    // No badge, no tone class: a verdict is read, and nothing about it is
    // carried by colour.
    expect(disposition.querySelector(".badge")).toBeNull();
  });

  it("prints a 128-character assignee reference in full, once, and nowhere else", async () => {
    await showRecord({ assigneeRef: LONG_ASSIGNEE_REF });

    expect(LONG_ASSIGNEE_REF).toHaveLength(128);
    const assignee = valueOf("Assignee");
    expect(assignee).toHaveTextContent(LONG_ASSIGNEE_REF);
    // The wrapping class the stylesheet uses to keep a long value inside its
    // own column instead of widening the document.
    expect(assignee.className).toContain("facts__ref");

    // Once as text, and not repeated into a title, an aria-label, a hidden
    // element or a data attribute.
    const occurrences = document.body.innerHTML.split(LONG_ASSIGNEE_REF).length - 1;
    expect(occurrences).toBe(1);
    for (const element of Array.from(document.querySelectorAll("*"))) {
      for (const attribute of Array.from(element.attributes)) {
        expect(attribute.value).not.toContain(LONG_ASSIGNEE_REF);
      }
    }
  });

  it("prints the identifier in a wrapping element in the heading", async () => {
    await showRecord();

    const heading = screen.getByRole("heading", { name: `Case ${CASE_ID}`, level: 2 });
    const id = within(heading).getByText(CASE_ID);
    expect(id.className).toContain("detail__id");
  });

  it("shows a large related-transaction count exactly as Backend counted it", async () => {
    await showRecord({ relatedTransactionCount: 1234567, concurrencyVersion: 0 });

    // No grouping separator and no abbreviation: the only thing this line may
    // say is the number.
    expect(valueOf("Related transactions")).toHaveTextContent("1234567");
    expect(valueOf("Concurrency version")).toHaveTextContent("0");
  });

  it("adds only investigation notes and audit history without inventing case data", async () => {
    await showRecord();

    const rendered = (document.body.textContent ?? "").toLowerCase();
    for (const forbidden of [
      "risk",
      "score",
      "probability",
      "detection",
      "evidence",
      "ai report",
      "transaction id",
      "escalat",
    ]) {
      expect(rendered).not.toContain(forbidden);
    }
  });

  it("offers only the approved note mutation beside read-only section controls", async () => {
    await showRecord();

    // The inline note composer is the one approved business mutation. The
    // case workflow, assignee and resolution remain read-only here.
    expect(screen.getAllByRole("button").map((button) => button.textContent)).toEqual([
      "Cancel",
      "Add note",
      "Previous",
      "Next",
      "Previous",
      "Next",
    ]);
    expect(document.querySelectorAll("form")).toHaveLength(1);
    expect(screen.getByRole("textbox", { name: "Investigation note" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Notes per page" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Entries per page" })).toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    // One link, and it goes to the list.
    const links = screen.getAllByRole("link");
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "/cases");
  });

  it("starts detail, notes and audit independently in the same commit", async () => {
    const { calls, detail, notes, audit, spy } = controlledAllCaseReads();
    renderPage(signedIn());
    await settle();

    // This is a barrier, not a final-count check: all three promises are still
    // pending when the third request is observed, so no first response could
    // have caused either subordinate request to start.
    expect(spy).toHaveBeenCalledTimes(3);
    expect(calls.every((call) => call.request.signal.aborted === false)).toBe(true);
    expect(detail).toHaveLength(1);
    expect(notes).toHaveLength(1);
    expect(audit).toHaveLength(1);
    expect(screen.getByText("Loading case...")).toBeInTheDocument();
    expect(new URL(notes[0].request.url).pathname).toBe(
      `${DETAIL_ROUTE.replace("/cases", "/api/v1/cases")}/notes`,
    );
    expect(new URL(notes[0].request.url).search).toBe(
      "?page=0&size=20&sort=createdAt%2Casc",
    );
    expect(new URL(audit[0].request.url).pathname).toBe(`${DETAIL_ROUTE.replace("/cases", "/api/v1/cases")}/audit-logs`);
    expect(new URL(audit[0].request.url).search).toBe(
      "?page=0&size=20&sort=changedAt%2Cdesc",
    );
    for (const call of calls) {
      expect(call.request.method).toBe("GET");
    }
  });

  it("keeps the case record when the independent audit read fails", async () => {
    const { calls } = controlledFetch(503);
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], caseBody());

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Case", level: 3 })).toBeInTheDocument();
      expect(screen.getByRole("alert")).toHaveTextContent(
        "The audit history could not be loaded",
      );
    });
    expect(valueOf("Case ID")).toHaveTextContent(CASE_ID);
    expect(document.body.textContent ?? "").not.toContain("AUDIT_BACKEND_PRIVATE_CODE");
    expect(document.body.textContent ?? "").not.toContain("audit backend private message");
    expect(document.body.innerHTML).not.toContain("trace_demo_case_audit_private");
  });

  it("announces the result in the live region and raises no alert", async () => {
    await showRecord();

    const summary = screen.getByRole("status", { name: "Case record status" });
    expect(summary).toHaveTextContent("Showing the full case record.");
    expect(summary).toHaveAttribute("aria-live", "polite");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("groups the record under headings below the page heading", async () => {
    await showRecord();

    const sections = screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent);
    expect(sections).toEqual([
      "Case",
      "Investigation timeline",
      "Record metadata",
      "Investigation notes",
      "Audit history",
    ]);
    expect(screen.getAllByRole("heading", { level: 2 })).toHaveLength(1);
    // Every labelled section really points at a heading that exists once.
    for (const section of Array.from(document.querySelectorAll("[aria-labelledby]"))) {
      const id = section.getAttribute("aria-labelledby") ?? "";
      expect(document.querySelectorAll(`#${CSS.escape(id)}`)).toHaveLength(1);
    }
  });

  it("carries no trace id into the screen", async () => {
    await showRecord();

    expect(document.body.innerHTML).not.toContain(TRACE_ID);
    expect(document.body.innerHTML).not.toContain("traceId");
  });
});

describe("CaseDetailPage note reconciliation", () => {
  it("keeps the composer locked and the version unchanged when detail is below the POST floor", async () => {
    const { detail, notes, audit } = controlledAllCaseReads();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(detail[0], caseBody({ concurrencyVersion: 4 }));
    await answerWith(notes[0], notesBody());
    await answerWith(audit[0], auditBody());

    const submitted = "floor-bound note";
    await user.type(screen.getByRole("textbox", { name: "Investigation note" }), submitted);
    await user.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() => expect(notes).toHaveLength(2));
    await answerWith(notes[1], createdNoteBody(submitted), 201);
    await waitFor(() => {
      expect(detail).toHaveLength(2);
      expect(notes).toHaveLength(3);
      expect(audit).toHaveLength(2);
    });

    await answerWith(detail[1], caseBody({ concurrencyVersion: 4 }));
    const add = screen.getByRole("button", { name: "Add note" });
    expect(add).toBeDisabled();
    expect(valueOf("Concurrency version")).toHaveTextContent("4");
    expect(detail).toHaveLength(2);
    expect(notes.filter((call) => call.request.method === "POST")).toHaveLength(1);
    await user.click(add);
    expect(notes.filter((call) => call.request.method === "POST")).toHaveLength(1);

    await user.click(screen.getByRole("button", { name: "Refresh case information" }));
    await waitFor(() => expect(detail).toHaveLength(3));
    await answerWith(detail[2], caseBody({ concurrencyVersion: 5 }));
    expect(screen.getByRole("button", { name: "Add note" })).toBeEnabled();
    expect(valueOf("Concurrency version")).toHaveTextContent("5");
  });

  it.each([5, 6])("uses authoritative detail version %i for the next note POST", async (version) => {
    const { detail, notes, audit } = controlledAllCaseReads();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(detail[0], caseBody({ concurrencyVersion: 4 }));
    await answerWith(notes[0], notesBody());
    await answerWith(audit[0], auditBody());

    const first = "first floor note";
    await user.type(screen.getByRole("textbox", { name: "Investigation note" }), first);
    await user.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() => expect(notes).toHaveLength(2));
    await answerWith(notes[1], createdNoteBody(first), 201);
    await waitFor(() => expect(detail).toHaveLength(2));
    await answerWith(detail[1], caseBody({ concurrencyVersion: version }));

    const second = `next note at version ${String(version)}`;
    await user.type(screen.getByRole("textbox", { name: "Investigation note" }), second);
    await user.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() =>
      expect(notes.filter((call) => call.request.method === "POST")).toHaveLength(2),
    );
    const nextPost = notes.filter((call) => call.request.method === "POST")[1];
    expect(JSON.parse(await nextPost.request.clone().text())).toEqual({
      content: second,
      expectedVersion: version,
    });
  });

  it("does not render the composer when sufficient reconciliation closes the case", async () => {
    const { detail, notes, audit } = controlledAllCaseReads();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(detail[0], caseBody({ concurrencyVersion: 4 }));
    await answerWith(notes[0], notesBody());
    await answerWith(audit[0], auditBody());

    const submitted = "note before close";
    await user.type(screen.getByRole("textbox", { name: "Investigation note" }), submitted);
    await user.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() => expect(notes).toHaveLength(2));
    await answerWith(notes[1], createdNoteBody(submitted), 201);
    await waitFor(() => expect(detail).toHaveLength(2));
    await answerWith(
      detail[1],
      caseBody({
        concurrencyVersion: 5,
        caseStatus: "CLOSED",
        finalDisposition: "FALSE_POSITIVE",
        closedAt: "2026-09-02T01:00:01Z",
      }),
    );

    expect(screen.queryByRole("textbox", { name: "Investigation note" })).not.toBeInTheDocument();
    expect(screen.getByText("Investigation notes cannot be added while this case is closed.")).toBeVisible();
    expect(notes.filter((call) => call.request.method === "POST")).toHaveLength(1);
  });

  it("does not optimistically insert and refreshes detail, notes, and audit independently", async () => {
    const { detail, notes, audit } = controlledAllCaseReads();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(detail[0], caseBody({ concurrencyVersion: 4 }));
    await answerWith(notes[0], notesBody("authoritative old note"));
    await answerWith(audit[0], auditBody());

    const textarea = screen.getByRole("textbox", { name: "Investigation note" });
    const submitted = "new draft not optimistic";
    await user.type(textarea, submitted);
    await user.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() => expect(notes).toHaveLength(2));
    expect(notes[1].request.method).toBe("POST");

    await answerWith(
      notes[1],
      {
        noteId: "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071",
        caseId: CASE_ID,
        authorType: "USER",
        authorRef: "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
        content: submitted,
        createdAt: "2026-09-02T01:00:00.123456Z",
        concurrencyVersion: 5,
        traceId: "trace_demo_note_created_01",
      },
      201,
    );
    await waitFor(() => {
      expect(detail).toHaveLength(2);
      expect(notes).toHaveLength(3);
      expect(audit).toHaveLength(2);
    });
    expect(screen.getByText("authoritative old note")).toBeVisible();
    expect(screen.queryByText(submitted)).not.toBeInTheDocument();
    expect(screen.getByRole("status", { name: "" })).toHaveTextContent("Investigation note added.");

    await answerWith(detail[1], caseBody({
      concurrencyVersion: 5,
      lastChangedAt: "2026-07-24T02:06:10Z",
    }));
    await answerWith(notes[2], notesBody("authoritative refreshed note"));
    await answerWith(audit[1], {
      ...auditBody(),
      content: [{
        action: "CASE_NOTE_CREATED",
        reasonCode: "CASE_INVESTIGATION_NOTE_ADDED",
        actorType: "USER",
        changedAt: "2026-09-02T01:00:00.123456Z",
        beforeSummary: null,
        afterSummary: null,
        metadata: { noteId: "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071" },
      }],
    });

    expect(valueOf("Concurrency version")).toHaveTextContent("5");
    expect(screen.getByText("authoritative refreshed note")).toBeVisible();
    expect(screen.getByText("CASE_NOTE_CREATED")).toBeVisible();
  });

  it("keeps a successful create and the other authoritative refreshes when audit refresh fails", async () => {
    const { detail, notes, audit } = controlledAllCaseReads();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(detail[0], caseBody({ concurrencyVersion: 4 }));
    await answerWith(notes[0], notesBody("authoritative old note"));
    await answerWith(audit[0], auditBody());

    const submitted = "successful create with isolated audit failure";
    await user.type(screen.getByRole("textbox", { name: "Investigation note" }), submitted);
    await user.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() => expect(notes).toHaveLength(2));
    await answerWith(
      notes[1],
      {
        noteId: "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071",
        caseId: CASE_ID,
        authorType: "USER",
        authorRef: "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
        content: submitted,
        createdAt: "2026-09-02T01:00:00.123456Z",
        concurrencyVersion: 5,
        traceId: "trace_demo_note_created_02",
      },
      201,
    );
    await waitFor(() => {
      expect(detail).toHaveLength(2);
      expect(notes).toHaveLength(3);
      expect(audit).toHaveLength(2);
    });

    await answerWith(detail[1], caseBody({ concurrencyVersion: 5 }));
    await answerWith(notes[2], notesBody("authoritative note despite audit failure"));
    await answerWith(
      audit[1],
      {
        code: "PRIVATE_AUDIT_REFRESH_CODE",
        message: "private audit refresh body",
        traceId: "private-audit-refresh-trace",
      },
      500,
    );

    expect(screen.getByRole("status", { name: "" })).toHaveTextContent("Investigation note added.");
    expect(valueOf("Concurrency version")).toHaveTextContent("5");
    expect(screen.getByText("authoritative note despite audit failure")).toBeVisible();
    expect(screen.getByText("CASE_CREATED")).toBeVisible();
    expect(
      screen.getByRole("heading", { name: "The latest audit history could not be loaded" }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "Add note" })).toBeEnabled();
    expect(document.body.textContent).not.toContain("PRIVATE_AUDIT_REFRESH_CODE");
    expect(document.body.textContent).not.toContain("private-audit-refresh-trace");
  });
});

describe("CaseDetailPage failures", () => {
  async function failWith(status: number, body: unknown = {}): Promise<PendingCall[]> {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], body, status);
    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });
    return calls;
  }

  it("reports a 404 as a case that is not there, and offers no retry", async () => {
    await failWith(404, { code: "CASE_NOT_FOUND", message: "no such case", traceId: TRACE_ID });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Case not found");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    expect(screen.queryByText("Loading case...")).not.toBeInTheDocument();
    expect(screen.getByRole("status", { name: "Case record status" })).toHaveTextContent(
      "No record shown.",
    );
    // Not one field of a case that does not exist.
    expect(document.querySelectorAll("dd")).toHaveLength(0);
    expect(document.body.innerHTML).not.toContain(TRACE_ID);
    expect(alert.textContent ?? "").not.toContain("404");
    expect(alert.textContent ?? "").not.toContain("CASE_NOT_FOUND");
    expect(screen.queryByRole("heading", { name: "Audit history" })).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Investigation notes" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByRole("navigation", { name: "Audit history pages" }),
    ).not.toBeInTheDocument();
  });

  it("reports a 403 as a fixed refusal, and offers no retry", async () => {
    await failWith(403, { code: "ACCESS_DENIED", message: "case:read required" });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Access denied");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    expect(alert.textContent ?? "").not.toContain("case:read");
    expect(alert.textContent ?? "").not.toContain("ACCESS_DENIED");
    expect(document.querySelectorAll("dd")).toHaveLength(0);
    expect(screen.queryByRole("heading", { name: "Audit history" })).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Investigation notes" }),
    ).not.toBeInTheDocument();
  });

  it.each([
    ["success", 404],
    ["failure", 404],
    ["success", 403],
    ["failure", 403],
  ] as const)(
    "publishes nothing from late subordinate %s settlements after detail %s",
    async (subordinateOutcome, detailStatus) => {
      const { detail, notes, audit, spy } = controlledAllCaseReads();
      const client = signedIn();
      const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
      renderPage(client);
      await settle();
      expect(detail).toHaveLength(1);
      expect(notes).toHaveLength(1);
      expect(audit).toHaveLength(1);

      await answerWith(
        detail[0],
        {
          code: `RAW_DETAIL_${String(detailStatus)}`,
          message: "private detail payload",
          traceId: "trace_private_detail",
        },
        detailStatus,
      );
      await waitFor(() => {
        expect(screen.getByRole("alert")).toHaveTextContent(
          detailStatus === 404 ? "Case not found" : "Access denied",
        );
      });
      expect(screen.queryByRole("heading", { name: "Investigation notes" })).not.toBeInTheDocument();
      expect(screen.queryByRole("heading", { name: "Audit history" })).not.toBeInTheDocument();
      expect(document.querySelectorAll("dd")).toHaveLength(0);

      if (subordinateOutcome === "success") {
        await answerWith(notes[0], notesBody("LATE_NOTES_SUCCESS_SENTINEL"));
        await answerWith(audit[0], auditBody());
      } else {
        await answerWith(
          notes[0],
          { code: "LATE_NOTES_FAILURE_SENTINEL", message: "late notes private body" },
          503,
        );
        await answerWith(
          audit[0],
          { code: "LATE_AUDIT_FAILURE_SENTINEL", message: "late audit private body" },
          503,
        );
      }
      await settle();

      expect(screen.queryByRole("heading", { name: "Investigation notes" })).not.toBeInTheDocument();
      expect(screen.queryByRole("heading", { name: "Audit history" })).not.toBeInTheDocument();
      expect(document.body.textContent ?? "").not.toMatch(
        /LATE_NOTES|LATE_AUDIT|private detail payload|late notes private body|late audit private body/,
      );
      expect(document.body.innerHTML).not.toContain("trace_private_detail");
      expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
      expect(client.calls.notified).toBe(0);
      expect(client.calls.invalidateIfCurrent).toBe(0);
      expect(spy).toHaveBeenCalledTimes(3);
      expect(
        consoleError.mock.calls.some((call) =>
          call.some((value) => /state update|unmounted component/i.test(String(value))),
        ),
      ).toBe(false);
    },
  );

  it.each(["notes", "audit"] as const)(
    "keeps the other two sections and retries only the failed %s read",
    async (failedSection) => {
      const user = userEvent.setup();
      const { detail, notes, audit, calls } = controlledAllCaseReads();
      renderPage(signedIn());
      await settle();

      await answerWith(detail[0], caseBody());
      if (failedSection === "notes") {
        await answerWith(
          notes[0],
          { code: "PRIVATE_NOTES_FAILURE", message: "notes private body" },
          503,
        );
        await answerWith(audit[0], auditBody());
      } else {
        await answerWith(notes[0], notesBody());
        await answerWith(
          audit[0],
          { code: "PRIVATE_AUDIT_FAILURE", message: "audit private body" },
          503,
        );
      }

      await waitFor(() => {
        expect(screen.getByRole("heading", { name: "Case", level: 3 })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "Investigation notes" })).toBeInTheDocument();
        expect(screen.getByRole("heading", { name: "Audit history" })).toBeInTheDocument();
      });
      expect(valueOf("Case ID")).toHaveTextContent(CASE_ID);
      if (failedSection === "notes") {
        expect(screen.getByText("CASE_CREATED")).toBeInTheDocument();
        expect(screen.getByRole("alert")).toHaveTextContent(
          "The investigation notes could not be loaded",
        );
        expect(screen.getByRole("alert")).not.toHaveTextContent("audit history");
      } else {
        expect(screen.getByText("Visible investigation note")).toBeInTheDocument();
        expect(screen.getByRole("alert")).toHaveTextContent("The audit history could not be loaded");
        expect(screen.getByRole("alert")).not.toHaveTextContent("investigation notes");
      }
      expect(document.body.textContent ?? "").not.toMatch(
        /PRIVATE_NOTES_FAILURE|PRIVATE_AUDIT_FAILURE|notes private body|audit private body/,
      );

      await user.click(
        within(screen.getByRole("alert")).getByRole("button", {
          name: /Try loading .* again/,
        }),
      );
      await settle();
      expect(detail).toHaveLength(1);
      expect(notes).toHaveLength(failedSection === "notes" ? 2 : 1);
      expect(audit).toHaveLength(failedSection === "audit" ? 2 : 1);
      expect(calls.every((call) => call.request.method === "GET")).toBe(true);
      expect(
        calls.every((call) => !/ai|report|status|assignee|resolution/i.test(new URL(call.request.url).pathname)),
      ).toBe(true);
    },
  );

  it("keeps the case record and audit section when only notes fail", async () => {
    const { calls } = controlledFetch(200, 503);
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], caseBody());

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Case", level: 3 })).toBeInTheDocument();
      expect(screen.getByRole("heading", { name: "Audit history", level: 3 })).toBeInTheDocument();
      expect(screen.getByRole("alert")).toHaveTextContent(
        "The investigation notes could not be loaded",
      );
    });
    expect(valueOf("Case ID")).toHaveTextContent(CASE_ID);
    expect(document.body.textContent ?? "").not.toContain("NOTES_BACKEND_PRIVATE_CODE");
    expect(document.body.textContent ?? "").not.toContain("notes backend private message");
    expect(document.body.innerHTML).not.toContain("trace_demo_case_notes_private");
  });

  it("reports an unmapped Backend status without naming it", async () => {
    await failWith(503, { code: "SERVICE_UNAVAILABLE", message: "upstream down" });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("The case could not be loaded");
    expect(alert.textContent ?? "").not.toContain("503");
    expect(alert.textContent ?? "").not.toContain("upstream");
    expect(within(alert).getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("reports a network failure", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await act(async () => {
      calls[0].fail(new TypeError("connection refused"));
      await calls[0].promise.catch(() => undefined);
    });

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("The backend could not be reached");
    });
    expect(screen.getByRole("button", { name: "Try again" })).toBeInTheDocument();
  });

  it("reports a timeout separately from a network failure", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => new Promise<Response>(() => {})),
    );
    renderPage(signedIn());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });

    expect(
      screen
        .getAllByRole("alert")
        .some((alert) => alert.textContent?.includes("The case took too long to load")),
    ).toBe(true);
    vi.useRealTimers();
  });

  it("refuses a record carrying a field outside the contract, showing none of it", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], {
      case: { ...(caseBody().case as object), riskLevel: "HIGH" },
      traceId: TRACE_ID,
    });

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("The case could not be read");
    });
    // Not one field of a refused record is displayed.
    expect(document.body.textContent ?? "").not.toContain(ASSIGNEE_REF);
    expect(document.body.textContent ?? "").not.toContain("HIGH");
    expect(document.querySelectorAll("dd")).toHaveLength(0);
  });

  it("removes the previous record when a retry fails", async () => {
    const calls = await failWith(500);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(screen.getByText("Loading case...")).toBeInTheDocument();
    expect(calls).toHaveLength(2);

    await answerWith(calls[1], caseBody());
    await waitFor(() => {
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });
    expect(valueOf("Case ID")).toHaveTextContent(CASE_ID);
  });

  it("sends exactly one request per press of Try again", async () => {
    const calls = await failWith(500);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Try again" }));
    await settle();
    expect(calls).toHaveLength(2);

    await answerWith(calls[1], {}, 500);
    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await settle();
    expect(calls).toHaveLength(3);
  });

  it("does not retry, replay or poll on its own", async () => {
    const calls = await failWith(500);

    await settle();
    await settle();
    expect(calls).toHaveLength(1);
  });

  it.each([
    ["a case that does not exist", 404],
    ["a case this session may not read", 403],
  ])("does not retry or poll after %s", async (_label, status) => {
    const calls = await failWith(status);

    await settle();
    await settle();
    expect(calls).toHaveLength(1);
    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });

  it("keeps the way back to the list on every failure", async () => {
    await failWith(404);

    expect(screen.getByRole("link", { name: "Back to cases" })).toHaveAttribute(
      "href",
      "/cases",
    );
  });

  it("moves focus to a new error summary, and does not steal it again", async () => {
    const calls = await failWith(500);
    const user = userEvent.setup();

    expect(screen.getByRole("alert")).toHaveFocus();

    // The same failure again: focus is left where the analyst put it.
    await user.click(screen.getByRole("button", { name: "Try again" }));
    await answerWith(calls[1], {}, 500);
    await waitFor(() => {
      expect(screen.getByRole("alert")).toBeInTheDocument();
    });
    screen.getByRole("button", { name: "Try again" }).focus();
    await settle();
    expect(screen.getByRole("button", { name: "Try again" })).toHaveFocus();
  });

  it("moves focus to the refusal when a case is not found", async () => {
    await failWith(404);

    expect(screen.getByRole("alert")).toHaveFocus();
  });
});

describe("CaseDetailPage malformed address", () => {
  /**
   * Locations a browser really can hand this screen, none of which is a case
   * address. Each costs zero credential lookups and zero fetches.
   */
  const malformedAddresses: Array<[string, string]> = [
    ["an uppercase UUID", "/cases/5C2D1E0F-7A8B-4C9D-9E0F-1A2B3C4D5E60"],
    ["a version 1 UUID", "/cases/5c2d1e0f-7a8b-1c9d-9e0f-1a2b3c4d5e60"],
    ["an invalid RFC variant", "/cases/5c2d1e0f-7a8b-4c9d-1e0f-1a2b3c4d5e60"],
    ["an unhyphenated UUID", "/cases/5c2d1e0f7a8b4c9d9e0f1a2b3c4d5e60"],
    ["a numeric identifier", "/cases/1"],
    ["a percent-encoded first digit", "/cases/%35c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60"],
    ["an encoded slash", `${DETAIL_ROUTE}%2fnotes`],
    ["an encoded backslash", `${DETAIL_ROUTE}%5cnotes`],
    ["a trailing slash", `${DETAIL_ROUTE}/`],
    ["a query string", `${DETAIL_ROUTE}?tab=raw`],
    ["a fragment", `${DETAIL_ROUTE}#assignee`],
  ];

  it.each(malformedAddresses)(
    "refuses %s, sends nothing, and echoes none of it",
    async (_label, path) => {
      const { spy } = controlledFetch();
      const client = signedIn();
      renderPage(client, path);
      await settle();

      const alert = screen.getByRole("alert");
      expect(alert).toHaveTextContent("This is not a case address");
      expect(spy).not.toHaveBeenCalled();
      expect(client.calls.authorizeRequest).toBe(0);
      expect(document.querySelectorAll("dd")).toHaveLength(0);
    },
  );

  it("takes focus, echoes no part of the address, and still offers the way back", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();
    renderPage(client, "/cases/%35c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60");
    await settle();

    expect(screen.getByRole("alert")).toHaveFocus();
    expect(spy).not.toHaveBeenCalled();
    expect(document.body.innerHTML).not.toContain("5c2d1e0f");
    expect(screen.getByRole("heading", { name: "Case", level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to cases" })).toHaveAttribute(
      "href",
      "/cases",
    );
    expect(screen.getByRole("status", { name: "Case record status" })).toHaveTextContent(
      "This is not a case address.",
    );
  });
});

describe("CaseDetailPage navigation back to the list", () => {
  it("returns to the list with no state and no query", async () => {
    await showRecord();
    const user = userEvent.setup();

    await user.click(screen.getByRole("link", { name: "Back to cases" }));

    expect(await screen.findByText("Case list stands in here.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^Case / })).not.toBeInTheDocument();
  });
});
