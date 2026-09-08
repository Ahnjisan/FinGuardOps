import { act, fireEvent, screen, within } from "@testing-library/react";
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

const { CaseListPage } = await import("./CaseListPage");

const TRACE_ID = "trace_demo_case_list_01";
const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const SECOND_CASE_ID = "6d3e2f10-8b9c-4d0e-8f01-2b3c4d5e6f71";
const TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const ASSIGNEE_REF = "analyst_ref_demo_a7f2";
const LONG_ASSIGNEE_REF = "assignee_ref_2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001_desk_0091";

const SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  displayName: "Local Analyst",
  roles: ["FDS_ANALYST"],
};

const ROUTES: RouteObject[] = [{ path: "/cases", element: <CaseListPage /> }];

function listItem(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    caseId: CASE_ID,
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: ASSIGNEE_REF,
    relatedTransactionCount: 3,
    createdAt: "2026-07-23T01:15:30Z",
    lastChangedAt: "2026-07-24T02:20:40Z",
    ...overrides,
  };
}

function listBody(
  content: readonly Record<string, unknown>[] = [listItem()],
  page: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    content,
    page: {
      number: 0,
      size: 20,
      totalElements: content.length,
      totalPages: content.length === 0 ? 0 : 1,
      first: true,
      last: true,
      ...page,
    },
    traceId: TRACE_ID,
  };
}

/**
 * A full page of cases with distinct identifiers.
 *
 * The size matters: `isConsistentPageMetadata` requires every page before the
 * last to be exactly full, so a multi-page fixture built from one row would be
 * refused by the production validator rather than paginated.
 */
function fullPage(count = 20): Record<string, unknown>[] {
  return Array.from({ length: count }, (_unused, index) =>
    listItem({ caseId: `5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e${String(index).padStart(2, "0")}` }),
  );
}

/** Page 0 of seven, which is what the pagination controls are exercised against. */
function firstOfSevenPages(): Record<string, unknown> {
  return listBody(fullPage(), { totalElements: 137, totalPages: 7, last: false });
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

function renderPage(client: FakeAuthClient) {
  return renderRoutesWithAuth(ROUTES, { client, initialEntries: ["/cases"] });
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

function queryOf(call: PendingCall): URLSearchParams {
  return new URL(call.request.url).searchParams;
}

function pathOf(call: PendingCall): string {
  return new URL(call.request.url).pathname;
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

describe("CaseListPage opening query", () => {
  it("asks the case endpoint for page 0, size 20, most recently changed first", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();

    expect(pathOf(calls[0])).toBe("/api/v1/cases");
    const query = queryOf(calls[0]);
    expect(query.get("page")).toBe("0");
    expect(query.get("size")).toBe("20");
    expect(query.get("sort")).toBe("lastChangedAt,desc");
    // Nothing else. An opening query carrying a filter nobody chose would
    // silently narrow the queue an analyst is meant to be triaging.
    expect([...query.keys()].sort()).toEqual(["page", "size", "sort"]);
  });

  it("shows a heading, the filters and an initial loading state", async () => {
    controlledFetch();
    renderPage(signedIn());
    await settle();

    expect(screen.getByRole("heading", { name: "Cases" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply filters" })).toBeInTheDocument();
    expect(screen.getByText("Loading cases...")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Loading cases");
  });
});

describe("CaseListPage data", () => {
  it("renders a row with every field the list response carries", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    const row = await screen.findByRole("row", { name: /in review/i });
    const cells = within(row).getAllByRole("cell");

    expect(cells).toHaveLength(7);
    expect(cells[0]).toHaveTextContent("2026-07-24 11:20:40 KST");
    expect(cells[1]).toHaveTextContent("In review");
    expect(cells[2]).toHaveTextContent("Not resolved");
    expect(cells[3]).toHaveTextContent(ASSIGNEE_REF);
    expect(cells[4]).toHaveTextContent("3");
    expect(cells[5]).toHaveTextContent("2026-07-23 10:15:30 KST");
    expect(cells[6]).toHaveTextContent(CASE_ID);
  });

  it("gives both instants a machine-readable UTC value", async () => {
    const { calls } = controlledFetch();
    const { container } = renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    await screen.findByRole("table");
    const times = container.querySelectorAll("tbody time");
    expect(times).toHaveLength(2);
    // The stored instants, untouched; the visible text is Seoul wall clock,
    // nine hours ahead.
    expect(times[0]).toHaveAttribute("datetime", "2026-07-24T02:20:40Z");
    expect(times[0]).toHaveTextContent("2026-07-24 11:20:40 KST");
    expect(times[1]).toHaveAttribute("datetime", "2026-07-23T01:15:30Z");
    expect(times[1]).toHaveTextContent("2026-07-23 10:15:30 KST");
  });

  it("says Not resolved for a null final disposition and never a verdict", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody([listItem({ finalDisposition: null })]));

    const row = await screen.findByRole("row", { name: /in review/i });
    expect(within(row).getByText("Not resolved")).toBeInTheDocument();
    const text = row.textContent ?? "";
    expect(text).not.toContain("Normal");
    expect(text).not.toContain("False positive");
    expect(text).not.toContain("Confirmed fraud");
  });

  it("says Unassigned for a null assignee", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody([listItem({ assigneeRef: null })]));

    const row = await screen.findByRole("row", { name: /in review/i });
    expect(within(row).getByText("Unassigned")).toBeInTheDocument();
  });

  it("shows every decided disposition under its own name", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(
      calls[0],
      listBody([
        listItem({ caseId: CASE_ID, caseStatus: "CLOSED", finalDisposition: "NORMAL" }),
        listItem({
          caseId: SECOND_CASE_ID,
          caseStatus: "CLOSED",
          finalDisposition: "FALSE_POSITIVE",
        }),
        listItem({
          caseId: "7e4f3021-9c0d-4e1f-9012-3c4d5e6f7082",
          caseStatus: "CLOSED",
          finalDisposition: "CONFIRMED_FRAUD",
        }),
      ]),
    );

    const table = await screen.findByRole("table");
    expect(within(table).getByText("Normal")).toBeInTheDocument();
    expect(within(table).getByText("False positive")).toBeInTheDocument();
    expect(within(table).getByText("Confirmed fraud")).toBeInTheDocument();
  });

  it("marks each status with a shape as well as a word, never colour alone", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(
      calls[0],
      listBody([
        listItem({ caseId: CASE_ID, caseStatus: "OPEN" }),
        listItem({ caseId: SECOND_CASE_ID, caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }),
        listItem({
          caseId: "7e4f3021-9c0d-4e1f-9012-3c4d5e6f7082",
          caseStatus: "CLOSED",
          finalDisposition: "NORMAL",
        }),
      ]),
    );

    const table = await screen.findByRole("table");
    const open = within(table).getByText("Open");
    const waiting = within(table).getByText("Information required");
    const closed = within(table).getByText("Closed");
    expect(open.className).toContain("badge--neutral");
    expect(waiting.className).toContain("badge--attention");
    expect(closed.className).toContain("badge--success");
    for (const badge of [open, waiting, closed]) {
      expect(badge.querySelector(".badge__mark")).not.toBeNull();
    }
  });

  it("prints the related transaction count exactly as the response carried it", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(
      calls[0],
      listBody([
        listItem({ caseId: CASE_ID, relatedTransactionCount: 0 }),
        listItem({ caseId: SECOND_CASE_ID, relatedTransactionCount: 1234567 }),
      ]),
    );

    const table = await screen.findByRole("table");
    expect(within(table).getByText("0")).toBeInTheDocument();
    expect(within(table).getByText("1234567")).toBeInTheDocument();
  });

  it("shows a long reference in full and never duplicates it into an attribute", async () => {
    const { calls } = controlledFetch();
    const { container } = renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody([listItem({ assigneeRef: LONG_ASSIGNEE_REF })]));

    const row = await screen.findByRole("row", { name: /in review/i });
    const cell = within(row).getAllByRole("cell")[3];
    expect(cell).toHaveTextContent(LONG_ASSIGNEE_REF);
    expect(cell.className).toContain("cell-ref--long");
    // Nothing repeats the value where it could be read out of the DOM twice.
    expect(container.querySelector(`[title*="${LONG_ASSIGNEE_REF}"]`)).toBeNull();
    expect(container.innerHTML.split(LONG_ASSIGNEE_REF)).toHaveLength(2);
  });

  it("prints the case identifier exactly once, as text and not as a link", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    const table = await screen.findByRole("table");
    // Once in the sheet and nowhere else - no anchor, no hidden mirror, no
    // `data-` attribute.
    expect(table.innerHTML.split(CASE_ID).length - 1).toBe(1);
    expect(within(table).queryByRole("link")).not.toBeInTheDocument();
    const idCell = within(screen.getByRole("row", { name: /in review/i })).getAllByRole(
      "cell",
    )[6];
    expect(idCell.className).toContain("cell-ref--id");
    expect(idCell).toHaveTextContent(CASE_ID);
  });

  it("keeps the row a record rather than a control", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    const row = await screen.findByRole("row", { name: /in review/i });
    expect(row.tagName).toBe("TR");
    expect(row.getAttribute("role")).toBeNull();
    expect(row.getAttribute("tabindex")).toBeNull();
    expect(row.onclick).toBeNull();
    for (const cell of within(row).getAllByRole("cell")) {
      expect(cell.onclick).toBeNull();
      expect(cell.getAttribute("role")).toBeNull();
    }
    // Clicking it opens nothing, because there is nothing to open yet.
    await user.click(within(row).getAllByRole("cell")[6]);
    expect(screen.getByRole("table")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("offers no workflow action and shows no risk, priority or SLA", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const rendered = document.body.textContent ?? "";
    // Nothing the response does not carry. "Assignee" is a real field and is
    // deliberately absent from this list; "Assign" as a verb is covered by the
    // control assertion below.
    for (const forbidden of [
      "Risk",
      "risk score",
      "Priority",
      "SLA",
      "Severity",
      "Evidence",
      "Detection",
      "Resolve",
      "Change status",
      "Add note",
      "Copy",
    ]) {
      expect(rendered).not.toContain(forbidden);
    }
    // The only controls on a loaded screen are the filters, the sort header and
    // the pager. Nothing that would change a case.
    const buttonNames = screen
      .getAllByRole("button")
      .map((button) => button.textContent ?? "");
    expect(buttonNames.some((name) => /assign|resolve|close|reopen|note/i.test(name))).toBe(
      false,
    );
  });

  it("counts the results in a live region", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], firstOfSevenPages());

    const status = await screen.findByRole("status");
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(status).toHaveTextContent("Showing 1-20 of 137 cases.");
  });

  it("scrolls the sheet sideways instead of dropping columns", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    const region = await screen.findByRole("region", { name: "Case results, scrollable" });
    expect(region).toHaveAttribute("tabindex", "0");
    expect(within(region).getAllByRole("columnheader")).toHaveLength(7);
  });
});

describe("CaseListPage draft and committed filters", () => {
  it("sends nothing while the analyst is still typing", async () => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    await user.type(screen.getByLabelText("Assignee reference"), "analyst");
    fireEvent.change(screen.getByLabelText("Opened from (KST)"), {
      target: { value: "2026-07-01T00:00" },
    });
    await user.selectOptions(screen.getByLabelText("Case status"), "OPEN");
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(
      screen.getByText("Edits are not applied yet. Apply them to search."),
    ).toBeInTheDocument();
  });

  it("commits every filter on Apply, converting KST to UTC and resetting to page 0", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], firstOfSevenPages());
    await screen.findByRole("table");

    await user.click(screen.getByRole("button", { name: "Next page" }));
    await settle();
    expect(queryOf(calls[1]).get("page")).toBe("1");
    await answerWith(
      calls[1],
      listBody(fullPage(), {
        number: 1,
        totalElements: 137,
        totalPages: 7,
        first: false,
        last: false,
      }),
    );

    await user.selectOptions(screen.getByLabelText("Case status"), "CLOSED");
    await user.selectOptions(screen.getByLabelText("Final disposition"), "CONFIRMED_FRAUD");
    fireEvent.change(screen.getByLabelText("Assignee reference"), {
      target: { value: ASSIGNEE_REF },
    });
    fireEvent.change(screen.getByLabelText("Related transaction ID"), {
      target: { value: TRANSACTION_ID },
    });
    fireEvent.change(screen.getByLabelText("Opened from (KST)"), {
      target: { value: "2026-07-23T10:15" },
    });
    fireEvent.change(screen.getByLabelText("Opened to (KST)"), {
      target: { value: "2026-07-24T10:15" },
    });
    fireEvent.change(screen.getByLabelText("Changed from (KST)"), {
      target: { value: "2026-08-01T09:00" },
    });
    fireEvent.change(screen.getByLabelText("Changed to (KST)"), {
      target: { value: "2026-08-02T09:00" },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    const query = queryOf(calls[2]);
    expect(Object.fromEntries(query)).toEqual({
      caseStatus: "CLOSED",
      finalDisposition: "CONFIRMED_FRAUD",
      assigneeRef: ASSIGNEE_REF,
      createdAtFrom: "2026-07-23T01:15:00Z",
      createdAtTo: "2026-07-24T01:15:00Z",
      lastChangedAtFrom: "2026-08-01T00:00:00Z",
      lastChangedAtTo: "2026-08-02T00:00:00Z",
      transactionId: TRANSACTION_ID,
      // A new filter set is a new result set, so the page index does not
      // survive it.
      page: "0",
      size: "20",
      sort: "lastChangedAt,desc",
    });
  }, 30_000);

  it("treats the two time ranges as independent filters", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    // Only the last-changed range. The opened range must not be filled in on
    // its behalf, and must not be required for it.
    fireEvent.change(screen.getByLabelText("Changed from (KST)"), {
      target: { value: "2026-08-01T09:00" },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    const query = queryOf(calls[1]);
    expect(query.get("lastChangedAtFrom")).toBe("2026-08-01T00:00:00Z");
    expect(query.has("lastChangedAtTo")).toBe(false);
    expect(query.has("createdAtFrom")).toBe(false);
    expect(query.has("createdAtTo")).toBe(false);
  });

  it("refuses a reversed opened range without sending anything", async () => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    const client = signedIn();
    renderPage(client);
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");
    const lookupsAfterLoad = client.calls.authorizeRequest;

    fireEvent.change(screen.getByLabelText("Opened from (KST)"), {
      target: { value: "2026-07-31T00:00" },
    });
    fireEvent.change(screen.getByLabelText("Opened to (KST)"), {
      target: { value: "2026-07-01T00:00" },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(client.calls.authorizeRequest).toBe(lookupsAfterLoad);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("These filters cannot be searched");
    expect(alert).toHaveTextContent(
      "The start of the opened time range must not be later than the end.",
    );
    // The other range is not implicated.
    expect(alert).not.toHaveTextContent("last-changed time range");
    expect(alert).toHaveFocus();
  });

  it("refuses a reversed last-changed range on its own merits", async () => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    // The opened range is valid; only the last-changed one runs backwards. An
    // implementation that checked one range and reused the answer for both
    // would let this through.
    fireEvent.change(screen.getByLabelText("Opened from (KST)"), {
      target: { value: "2026-07-01T00:00" },
    });
    fireEvent.change(screen.getByLabelText("Opened to (KST)"), {
      target: { value: "2026-07-31T00:00" },
    });
    fireEvent.change(screen.getByLabelText("Changed from (KST)"), {
      target: { value: "2026-08-31T00:00" },
    });
    fireEvent.change(screen.getByLabelText("Changed to (KST)"), {
      target: { value: "2026-08-01T00:00" },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent(
      "The start of the last-changed time range must not be later than the end.",
    );
    expect(alert).not.toHaveTextContent("opened time range");
  });

  it.each([
    ["an uppercase UUID", "2F4C0A4E-8A9D-4C2F-9A1B-7D6E5F430001"],
    ["a version 1 UUID", "2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001"],
    ["a UUID with no hyphens", "2f4c0a4e8a9d4c2f9a1b7d6e5f430001"],
    ["a padded UUID", " 2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001 "],
    ["a bare number", "1"],
  ])(
    "refuses %s as a transaction filter before any credential is looked up",
    async (_label, value) => {
      const { calls, spy } = controlledFetch();
      const user = userEvent.setup();
      const client = signedIn();
      renderPage(client);
      await settle();
      await answerWith(calls[0], listBody());
      await screen.findByRole("table");
      const lookupsAfterLoad = client.calls.authorizeRequest;

      fireEvent.change(screen.getByLabelText("Related transaction ID"), {
        target: { value },
      });
      await user.click(screen.getByRole("button", { name: "Apply filters" }));
      await settle();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(client.calls.authorizeRequest).toBe(lookupsAfterLoad);
      const alert = screen.getByRole("alert");
      expect(alert).toHaveTextContent(
        "Enter the related transaction ID as a canonical lowercase UUID",
      );
      // The refused value is not repeated back into the explanation.
      expect(alert.textContent ?? "").not.toContain(value.trim());
    },
  );

  it.each([
    ["a blank reference", "   ", "Enter an assignee reference, or leave the field empty."],
    [
      "a padded reference",
      " analyst_ref ",
      "The assignee reference must not begin or end with a space.",
    ],
    [
      "a 129-character reference",
      "a".repeat(129),
      "The assignee reference must be 128 characters or fewer.",
    ],
  ])("refuses %s without sending anything", async (_label, value, message) => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    const client = signedIn();
    renderPage(client);
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");
    const lookupsAfterLoad = client.calls.authorizeRequest;

    fireEvent.change(screen.getByLabelText("Assignee reference"), { target: { value } });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(client.calls.authorizeRequest).toBe(lookupsAfterLoad);
    expect(screen.getByRole("alert")).toHaveTextContent(message);
  });

  it("sends a 128-character reference, which Backend accepts", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const boundary = "a".repeat(128);
    fireEvent.change(screen.getByLabelText("Assignee reference"), {
      target: { value: boundary },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    expect(queryOf(calls[1]).get("assigneeRef")).toBe(boundary);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("restores the opening query on Reset", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    await user.selectOptions(screen.getByLabelText("Case status"), "OPEN");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(queryOf(calls[1]).get("caseStatus")).toBe("OPEN");
    await answerWith(calls[1], listBody());

    await user.click(screen.getByRole("button", { name: "Reset filters" }));
    await settle();

    const query = queryOf(calls[2]);
    // The exact opening contract, stated as literals rather than as "whatever
    // the first request was".
    expect(Object.fromEntries(query)).toEqual({
      page: "0",
      size: "20",
      sort: "lastChangedAt,desc",
    });
    expect(screen.getByLabelText("Case status")).toHaveValue("");
  }, 20_000);

  it("keeps reference filters out of the address bar and out of web storage", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    fireEvent.change(screen.getByLabelText("Assignee reference"), {
      target: { value: LONG_ASSIGNEE_REF },
    });
    fireEvent.change(screen.getByLabelText("Related transaction ID"), {
      target: { value: TRANSACTION_ID },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    expect(queryOf(calls[1]).get("assigneeRef")).toBe(LONG_ASSIGNEE_REF);
    expect(queryOf(calls[1]).get("transactionId")).toBe(TRANSACTION_ID);
    for (const value of [LONG_ASSIGNEE_REF, TRANSACTION_ID]) {
      expect(window.location.href).not.toContain(value);
      expect(JSON.stringify({ ...window.localStorage })).not.toContain(value);
      expect(JSON.stringify({ ...window.sessionStorage })).not.toContain(value);
    }
    expect(window.location.search).toBe("");
    expect(JSON.stringify(window.history.state ?? null)).not.toContain(LONG_ASSIGNEE_REF);
  }, 20_000);
});

describe("CaseListPage sorting and pagination", () => {
  it("reports the sort direction with aria-sort and toggles it", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    const header = await screen.findByRole("columnheader", { name: /last changed/i });
    expect(header).toHaveAttribute("aria-sort", "descending");

    await user.click(within(header).getByRole("button"));
    await settle();

    expect(queryOf(calls[1]).get("sort")).toBe("lastChangedAt,asc");
    expect(queryOf(calls[1]).get("page")).toBe("0");
    await answerWith(calls[1], listBody());
    expect(screen.getByRole("columnheader", { name: /last changed/i })).toHaveAttribute(
      "aria-sort",
      "ascending",
    );
  });

  it("disables the page controls at the ends of the result set", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    expect(await screen.findByRole("button", { name: "Previous page" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
    expect(screen.getByText("Page 1 of 1")).toBeInTheDocument();
  });

  it("returns to page 0 when the page size changes", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], firstOfSevenPages());
    await screen.findByRole("table");

    await user.click(screen.getByRole("button", { name: "Next page" }));
    await settle();
    await answerWith(
      calls[1],
      listBody(fullPage(), {
        number: 1,
        totalElements: 137,
        totalPages: 7,
        first: false,
        last: false,
      }),
    );

    await user.selectOptions(screen.getByLabelText("Rows per page"), "50");
    await settle();

    expect(queryOf(calls[2]).get("size")).toBe("50");
    expect(queryOf(calls[2]).get("page")).toBe("0");
  }, 20_000);
});

describe("CaseListPage empty and error states", () => {
  it("invites the analyst to widen an empty filtered result", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    await user.selectOptions(screen.getByLabelText("Case status"), "CLOSED");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    await answerWith(calls[1], listBody([]));

    expect(await screen.findByText("No cases match these filters")).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();

    await user.click(
      within(screen.getByText("No cases match these filters").parentElement as HTMLElement)
        .getByRole("button", { name: "Reset filters" }),
    );
    await settle();
    expect(queryOf(calls[2]).has("caseStatus")).toBe(false);
  }, 20_000);

  it("says there is nothing to show when no filter is applied", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody([]));

    expect(await screen.findByText("There are no cases to show yet.")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("No cases found.");
  });

  it("distinguishes a timeout from a network failure", async () => {
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

    expect(screen.getByRole("alert")).toHaveTextContent("The search took too long");
    vi.useRealTimers();
  });

  it("reports a network failure and retries only when asked", async () => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await act(async () => {
      calls[0].fail(new TypeError("connection refused"));
      await calls[0].promise.catch(() => undefined);
    });

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The backend could not be reached");
    expect(alert).toHaveFocus();
    expect(spy).toHaveBeenCalledTimes(1);

    await user.click(within(alert).getByRole("button", { name: "Try again" }));
    await settle();
    // Exactly one more request, not one and a replay of it.
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("refuses a malformed page in full", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], {
      content: [listItem(), listItem({ caseId: SECOND_CASE_ID, caseStatus: "ESCALATED" })],
      page: {
        number: 0,
        size: 20,
        totalElements: 2,
        totalPages: 1,
        first: true,
        last: true,
      },
      traceId: TRACE_ID,
    });

    expect(await screen.findByRole("alert")).toHaveTextContent("The results could not be read");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    // Not even the row that was well formed. A partial case list is a wrong
    // case list.
    expect(screen.queryByText(CASE_ID)).not.toBeInTheDocument();
    expect(screen.queryByText(ASSIGNEE_REF)).not.toBeInTheDocument();
  });

  it("shows a fixed access-denied state on 403, with no retry", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();
    renderPage(client);
    await settle();
    await answerWith(calls[0], { code: "ACCESS_DENIED", message: "missing case:read" }, 403);

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Access denied");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    // The session survives a 403: nothing was invalidated and nobody was told.
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("case:read");
    expect(rendered).not.toContain("ACCESS_DENIED");
    expect(rendered).not.toContain("403");
  });

  it("shows nothing of a 500 but the fixed sentence", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(
      calls[0],
      { code: "INTERNAL_ERROR", message: "boom", traceId: TRACE_ID },
      500,
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("The search failed");
    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("500");
    expect(rendered).not.toContain("INTERNAL_ERROR");
    expect(rendered).not.toContain(TRACE_ID);
  });

  it("clears the sheet when a 401 ends the session", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();
    renderPage(client);
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    await act(async () => {
      client.emitSessionInvalidated();
    });

    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText(ASSIGNEE_REF)).not.toBeInTheDocument();
    expect(screen.queryByText(CASE_ID)).not.toBeInTheDocument();
  });

  it("never renders a trace id, a claim or a credential", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain(TRACE_ID);
    expect(rendered).not.toContain(SESSION.subject);
    expect(rendered).not.toContain("FDS_ANALYST");
    expect(rendered).not.toContain("case:view");
    expect(document.body.innerHTML).not.toContain(TRACE_ID);
    expect(document.body.innerHTML).not.toMatch(/bearer|access_token|id_token/i);
  });
});

describe("CaseListPage accessibility", () => {
  it("gives the screen a labelled section, a heading and a table caption", async () => {
    const { calls } = controlledFetch();
    const { container } = renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const heading = screen.getByRole("heading", { name: "Cases", level: 2 });
    expect(heading).toHaveAttribute("id", "cases-heading");
    const section = container.querySelector('[aria-labelledby="cases-heading"]');
    expect(section).not.toBeNull();
    expect(section?.tagName).toBe("SECTION");
  });

  it("resolves every aria-labelledby to exactly one element, with no duplicate id", async () => {
    const { calls } = controlledFetch();
    const { container } = renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const ids = Array.from(container.querySelectorAll("[id]")).map((element) => element.id);
    expect(new Set(ids).size).toBe(ids.length);

    const referring = Array.from(container.querySelectorAll("[aria-labelledby]"));
    expect(referring.length).toBeGreaterThan(0);
    for (const element of referring) {
      for (const id of (element.getAttribute("aria-labelledby") ?? "").split(/\s+/)) {
        expect(container.querySelectorAll(`#${CSS.escape(id)}`)).toHaveLength(1);
      }
    }
  });

  it("gives every filter control a visible label inside a legend group", async () => {
    const { calls } = controlledFetch();
    const { container } = renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const labels = [
      "Opened from (KST)",
      "Opened to (KST)",
      "Changed from (KST)",
      "Changed to (KST)",
      "Case status",
      "Final disposition",
      "Assignee reference",
      "Related transaction ID",
    ];
    for (const label of labels) {
      const control = screen.getByLabelText(label);
      expect(control).toBeInTheDocument();
      expect(control.closest("fieldset")).not.toBeNull();
      // A visible label, not a `visually-hidden` one or a bare placeholder.
      const rendered = container.querySelector(`label[for="${control.id}"]`);
      expect(rendered).not.toBeNull();
      expect(rendered?.className ?? "").not.toContain("visually-hidden");
    }
    expect(container.querySelectorAll("fieldset > legend").length).toBe(4);
  }, 20_000);

  it("reaches every filter control, Apply and Reset with the keyboard alone", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const order = [
      screen.getByLabelText("Opened from (KST)"),
      screen.getByLabelText("Opened to (KST)"),
      screen.getByLabelText("Changed from (KST)"),
      screen.getByLabelText("Changed to (KST)"),
      screen.getByLabelText("Case status"),
      screen.getByLabelText("Final disposition"),
      screen.getByLabelText("Assignee reference"),
      screen.getByLabelText("Related transaction ID"),
      screen.getByRole("button", { name: "Apply filters" }),
      screen.getByRole("button", { name: "Reset filters" }),
    ];

    order[0].focus();
    expect(order[0]).toHaveFocus();
    for (let index = 1; index < order.length; index += 1) {
      await user.tab();
      expect(order[index]).toHaveFocus();
    }
  }, 30_000);

  it("applies the filters when Enter is pressed inside a field", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const field = screen.getByLabelText("Assignee reference");
    await user.click(field);
    await user.keyboard(`${ASSIGNEE_REF}{Enter}`);
    await settle();

    expect(queryOf(calls[1]).get("assigneeRef")).toBe(ASSIGNEE_REF);
  }, 20_000);

  it("re-sorts and paginates from the keyboard", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], firstOfSevenPages());

    const header = await screen.findByRole("columnheader", { name: /last changed/i });
    within(header).getByRole("button").focus();
    await user.keyboard("{Enter}");
    await settle();
    expect(queryOf(calls[1]).get("sort")).toBe("lastChangedAt,asc");
    await answerWith(calls[1], firstOfSevenPages());

    screen.getByRole("button", { name: "Next page" }).focus();
    await user.keyboard("{Enter}");
    await settle();
    expect(queryOf(calls[2]).get("page")).toBe("1");
  }, 20_000);
});

describe("CaseListPage validation refusal focus", () => {
  /** Puts the draft into a state `commitDraft` refuses for exactly one reason. */
  function reverseTheOpenedRange(): void {
    fireEvent.change(screen.getByLabelText("Opened from (KST)"), {
      target: { value: "2026-07-31T00:00" },
    });
    fireEvent.change(screen.getByLabelText("Opened to (KST)"), {
      target: { value: "2026-07-01T00:00" },
    });
  }

  it("moves focus to each of two consecutive refusals, sending nothing either time", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();
    const user = userEvent.setup();
    renderPage(client);
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const lookupsAfterFirstLoad = client.calls.authorizeRequest;
    expect(spy).toHaveBeenCalledTimes(1);

    // A reversed opened range: one problem.
    reverseTheOpenedRange();
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    const first = screen.getByRole("alert");
    // Exactly one entry, so the refusal cannot be quietly carrying a second
    // problem that the text assertion below would still be satisfied by.
    expect(within(first).getAllByRole("listitem")).toHaveLength(1);
    expect(first).toHaveTextContent(
      "The start of the opened time range must not be later than the end.",
    );
    expect(first).toHaveFocus();

    // The analyst leaves the explanation and carries on in the form. Focus has
    // to genuinely belong to another control before the second Apply: if it
    // were still sitting on the summary, the summary would "have focus" after
    // the second refusal whether or not focus was ever moved there again.
    const assigneeField = screen.getByLabelText("Assignee reference");
    await user.click(assigneeField);
    expect(document.activeElement).toBe(assigneeField);
    expect(first).not.toHaveFocus();

    // The range is fixed and a different single problem is introduced, so the
    // refusal still carries exactly one entry. A signature built from that
    // count would not change here, and focus would stay where the analyst left
    // it instead of moving to the new explanation.
    fireEvent.change(screen.getByLabelText("Opened to (KST)"), {
      target: { value: "2026-08-01T00:00" },
    });
    fireEvent.change(assigneeField, { target: { value: "   " } });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    const second = screen.getByRole("alert");
    expect(second).toBe(first);
    expect(within(second).getAllByRole("listitem")).toHaveLength(1);
    expect(second).toHaveTextContent("Enter an assignee reference, or leave the field empty.");
    expect(second).not.toHaveTextContent("opened time range");
    expect(second).toHaveFocus();

    // Neither refusal cost a credential lookup or a request.
    expect(spy).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(1);
    expect(client.calls.authorizeRequest).toBe(lookupsAfterFirstLoad);
  }, 30_000);

  it("moves focus again when the identical refusal is submitted twice", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();
    const user = userEvent.setup();
    renderPage(client);
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const lookupsAfterFirstLoad = client.calls.authorizeRequest;
    reverseTheOpenedRange();
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(screen.getByRole("alert")).toHaveFocus();

    // The analyst carries on somewhere else and asks again without changing
    // anything. The same explanation is still the answer, so it is announced
    // again rather than left behind.
    screen.getByLabelText("Assignee reference").focus();
    expect(screen.getByRole("alert")).not.toHaveFocus();
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    expect(screen.getByRole("alert")).toHaveFocus();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(client.calls.authorizeRequest).toBe(lookupsAfterFirstLoad);
  }, 20_000);

  it("does not reuse a spent refusal after a successful Apply or a Reset", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    reverseTheOpenedRange();
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(screen.getByRole("alert")).toHaveFocus();

    const resetButton = screen.getByRole("button", { name: "Reset filters" });
    await user.click(resetButton);
    await settle();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(resetButton).toHaveFocus();
    // Reset restored a query identical to the one already on screen, so it
    // asked the Backend for nothing.
    expect(calls).toHaveLength(1);

    await user.selectOptions(screen.getByLabelText("Case status"), "OPEN");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(queryOf(calls[1]).get("caseStatus")).toBe("OPEN");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    await answerWith(calls[1], listBody());

    // And the refusal that follows is announced on its own merits, not skipped
    // because an earlier one looked the same.
    reverseTheOpenedRange();
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(screen.getByRole("alert")).toHaveFocus();
  }, 30_000);

  it("keeps the typed references out of the refusal and out of every other node", async () => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    const { container } = renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    reverseTheOpenedRange();
    fireEvent.change(screen.getByLabelText("Assignee reference"), {
      target: { value: LONG_ASSIGNEE_REF },
    });
    fireEvent.change(screen.getByLabelText("Related transaction ID"), {
      target: { value: TRANSACTION_ID },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    const alert = screen.getByRole("alert");
    expect(alert).toHaveFocus();
    expect(alert.textContent ?? "").not.toContain(LONG_ASSIGNEE_REF);
    expect(alert.textContent ?? "").not.toContain(TRANSACTION_ID);

    // The fields the analyst typed into are the one place these values belong.
    // Nothing else in the tree - no text node, no title, no data attribute, no
    // hidden input - is allowed to carry them.
    const fields = [
      screen.getByLabelText("Assignee reference"),
      screen.getByLabelText("Related transaction ID"),
    ];
    for (const needle of [LONG_ASSIGNEE_REF, TRANSACTION_ID]) {
      const elsewhere = Array.from(container.querySelectorAll("*")).filter((element) => {
        if (fields.includes(element as HTMLElement)) {
          return false;
        }
        const inAttribute = Array.from(element.attributes).some((attribute) =>
          attribute.value.includes(needle),
        );
        const inText = Array.from(element.childNodes).some((node) =>
          (node.nodeValue ?? "").includes(needle),
        );
        return inAttribute || inText;
      });
      expect(elsewhere).toEqual([]);
      expect(window.location.href).not.toContain(needle);
      expect(JSON.stringify({ ...window.localStorage })).not.toContain(needle);
      expect(JSON.stringify({ ...window.sessionStorage })).not.toContain(needle);
    }
    expect(spy).toHaveBeenCalledTimes(1);
  }, 30_000);
});
