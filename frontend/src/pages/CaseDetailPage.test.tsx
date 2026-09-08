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
    expect(screen.getByRole("status")).toHaveTextContent("Loading case");
    expect(screen.getByRole("status")).toHaveAttribute("aria-live", "polite");
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

  it("shows no risk, detection, evidence, note, audit or AI report anywhere", async () => {
    await showRecord();

    const rendered = (document.body.textContent ?? "").toLowerCase();
    for (const forbidden of [
      "risk",
      "score",
      "probability",
      "detection",
      "evidence",
      "note",
      "audit",
      "ai report",
      "transaction id",
      "escalat",
    ]) {
      expect(rendered).not.toContain(forbidden);
    }
  });

  it("offers no mutation control of any kind", async () => {
    await showRecord();

    // A read-only screen: nothing to press, no form to submit, no field to
    // type in and no state, assignee or resolution to choose.
    expect(screen.queryAllByRole("button")).toHaveLength(0);
    expect(document.querySelectorAll("form")).toHaveLength(0);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("combobox")).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    // One link, and it goes to the list.
    const links = screen.getAllByRole("link");
    expect(links).toHaveLength(1);
    expect(links[0]).toHaveAttribute("href", "/cases");
  });

  it("sends nothing beyond the one read, whatever the record says", async () => {
    const { calls, spy } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], caseBody());
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Case", level: 3 })).toBeInTheDocument();
    });
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    for (const call of calls) {
      expect(call.request.method).toBe("GET");
    }
  });

  it("announces the result in the live region and raises no alert", async () => {
    await showRecord();

    const summary = screen.getByRole("status");
    expect(summary).toHaveTextContent("Showing the full case record.");
    expect(summary).toHaveAttribute("aria-live", "polite");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("groups the record under headings below the page heading", async () => {
    await showRecord();

    const sections = screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent);
    expect(sections).toEqual(["Case", "Investigation timeline", "Record metadata"]);
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
    expect(screen.getByRole("status")).toHaveTextContent("No record shown.");
    // Not one field of a case that does not exist.
    expect(document.querySelectorAll("dd")).toHaveLength(0);
    expect(document.body.innerHTML).not.toContain(TRACE_ID);
    expect(alert.textContent ?? "").not.toContain("404");
    expect(alert.textContent ?? "").not.toContain("CASE_NOT_FOUND");
  });

  it("reports a 403 as a fixed refusal, and offers no retry", async () => {
    await failWith(403, { code: "ACCESS_DENIED", message: "case:read required" });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Access denied");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    expect(alert.textContent ?? "").not.toContain("case:read");
    expect(alert.textContent ?? "").not.toContain("ACCESS_DENIED");
    expect(document.querySelectorAll("dd")).toHaveLength(0);
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

    expect(screen.getByRole("alert")).toHaveTextContent("The case took too long to load");
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
    expect(screen.getByRole("status")).toHaveTextContent("This is not a case address.");
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
