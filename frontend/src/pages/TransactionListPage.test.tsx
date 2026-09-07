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

const { TransactionListPage } = await import("./TransactionListPage");

const TRACE_ID = "trace_demo_tx_list_01";
const TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const SECOND_TRANSACTION_ID = "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
const LONG_CUSTOMER_REF = "cust_ref_2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001_branch_0091";

const SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  displayName: "Local Analyst",
  roles: ["FDS_ANALYST"],
};

const ROUTES: RouteObject[] = [{ path: "/transactions", element: <TransactionListPage /> }];

function listItem(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    transactionId: TRANSACTION_ID,
    transactionType: "ACCOUNT_TRANSFER",
    amount: "1250000",
    currencyCode: "KRW",
    occurredAt: "2026-07-23T01:15:30Z",
    externalCustomerRef: "cust_ref_demo_a7f2",
    senderAccountRef: "acct_ref_demo_s91c",
    recipientAccountRef: "acct_ref_demo_r44d",
    processingStatus: "ADDITIONAL_AUTH_REQUIRED",
    createdAt: "2026-07-23T01:15:31Z",
    ...overrides,
  };
}

function listBody(
  content: readonly Record<string, unknown>[] = [listItem()],
  page: Record<string, unknown> = {},
): Record<string, unknown> {
  const size = 20;
  return {
    content,
    page: {
      number: 0,
      size,
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
 * A full page of items with distinct identifiers.
 *
 * The size matters: `isConsistentPageMetadata` requires every page before the
 * last to be exactly full, so a multi-page fixture built from one row would be
 * refused by the production validator rather than paginated.
 */
function fullPage(count = 20): Record<string, unknown>[] {
  return Array.from({ length: count }, (_unused, index) =>
    listItem({
      transactionId: `2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f4300${String(index).padStart(2, "0")}`,
    }),
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
  return renderRoutesWithAuth(ROUTES, { client, initialEntries: ["/transactions"] });
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

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  adapter.client = null;
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("TransactionListPage opening query", () => {
  it("asks for page 0, size 20, newest first", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();

    const query = queryOf(calls[0]);
    expect(query.get("page")).toBe("0");
    expect(query.get("size")).toBe("20");
    expect(query.get("sort")).toBe("occurredAt,desc");
    expect(query.has("occurredAtFrom")).toBe(false);
    expect(query.has("externalCustomerRef")).toBe(false);
  });

  it("shows a heading, the filters and an initial loading state", async () => {
    controlledFetch();
    renderPage(signedIn());
    await settle();

    expect(screen.getByRole("heading", { name: "Transactions" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply filters" })).toBeInTheDocument();
    expect(screen.getByText("Loading transactions...")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Loading transactions");
  });
});

describe("TransactionListPage data", () => {
  it("renders a row with every field the list response carries", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    const row = await screen.findByRole("row", { name: /account transfer/i });
    const cells = within(row).getAllByRole("cell");

    expect(cells[0]).toHaveTextContent("2026-07-23 10:15:30 KST");
    expect(cells[0]).toHaveTextContent("Recorded 2026-07-23 10:15");
    expect(cells[1]).toHaveTextContent("Account transfer");
    expect(cells[2]).toHaveTextContent("1,250,000");
    expect(cells[2]).toHaveTextContent("KRW");
    expect(cells[3]).toHaveTextContent("Auth required");
    expect(cells[4]).toHaveTextContent(TRANSACTION_ID);
    expect(cells[5]).toHaveTextContent("cust_ref_demo_a7f2");
    expect(cells[6]).toHaveTextContent("acct_ref_demo_s91c");
    expect(cells[7]).toHaveTextContent("acct_ref_demo_r44d");
  });

  it("gives the occurrence time a machine-readable UTC value", async () => {
    const { calls } = controlledFetch();
    const { container } = renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    await screen.findByRole("table");
    const times = container.querySelectorAll("time");
    expect(times[0]).toHaveAttribute("datetime", "2026-07-23T01:15:30Z");
    expect(times[1]).toHaveAttribute("datetime", "2026-07-23T01:15:31Z");
    // Displayed in Seoul wall clock, nine hours ahead of the stored instant.
    expect(times[0]).toHaveTextContent("2026-07-23 10:15:30 KST");
  });

  it("keeps a fifteen-digit amount exact", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody([listItem({ amount: "999999999999999" })]));

    const row = await screen.findByRole("row", { name: /account transfer/i });
    expect(within(row).getByText("999,999,999,999,999")).toBeInTheDocument();
  });

  it("says so when a transaction has no recipient account", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody([listItem({ recipientAccountRef: null })]));

    const row = await screen.findByRole("row", { name: /account transfer/i });
    expect(within(row).getByText("None recorded")).toBeInTheDocument();
  });

  it("shows a long reference in full and never duplicates it into an attribute", async () => {
    const { calls } = controlledFetch();
    const { container } = renderPage(signedIn());
    await settle();
    await answerWith(
      calls[0],
      listBody([listItem({ externalCustomerRef: LONG_CUSTOMER_REF })]),
    );

    const row = await screen.findByRole("row", { name: /account transfer/i });
    const cell = within(row).getAllByRole("cell")[5];
    expect(cell).toHaveTextContent(LONG_CUSTOMER_REF);
    expect(cell.className).toContain("cell-ref--long");
    // Nothing repeats the value where it could be read out of the DOM twice.
    expect(container.querySelector(`[title*="${LONG_CUSTOMER_REF}"]`)).toBeNull();
    expect(container.innerHTML.split(LONG_CUSTOMER_REF)).toHaveLength(2);
  });

  it("marks each status with a shape as well as a word, never colour alone", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(
      calls[0],
      listBody([
        listItem({ processingStatus: "APPROVED" }),
        listItem({ transactionId: SECOND_TRANSACTION_ID, processingStatus: "FAILED" }),
      ]),
    );

    await screen.findByRole("table");
    const table = screen.getByRole("table");
    const approved = within(table).getByText("Approved");
    const failed = within(table).getByText("Failed");
    expect(approved.className).toContain("badge--success");
    expect(failed.className).toContain("badge--danger");
    expect(approved.querySelector(".badge__mark")).not.toBeNull();
    expect(failed.querySelector(".badge__mark")).not.toBeNull();
  });

  it("counts the results in a live region", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], firstOfSevenPages());

    const status = await screen.findByRole("status");
    expect(status).toHaveAttribute("aria-live", "polite");
    expect(status).toHaveTextContent("Showing 1-20 of 137 transactions.");
  });

  it("scrolls the sheet sideways instead of dropping columns", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    const region = await screen.findByRole("region", {
      name: "Transaction results, scrollable",
    });
    expect(region).toHaveAttribute("tabindex", "0");
    expect(within(region).getAllByRole("columnheader")).toHaveLength(8);
  });
});

describe("TransactionListPage draft and committed filters", () => {
  it("sends nothing while the analyst is still typing", async () => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    await user.type(screen.getByLabelText("Customer reference"), "cust");
    fireEvent.change(screen.getByLabelText("From (KST)"), {
      target: { value: "2026-07-01T00:00" },
    });
    await user.selectOptions(screen.getByLabelText("Processing status"), "HELD");
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Edits are not applied yet. Apply them to search.")).toBeInTheDocument();
  });

  it("commits the draft on Apply, converting KST to UTC and resetting to page 0", async () => {
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

    fireEvent.change(screen.getByLabelText("From (KST)"), {
      target: { value: "2026-07-23T10:15" },
    });
    fireEvent.change(screen.getByLabelText("To (KST)"), {
      target: { value: "2026-07-24T10:15" },
    });
    await user.selectOptions(screen.getByLabelText("Transaction type"), "ATM_WITHDRAWAL");
    fireEvent.change(screen.getByLabelText("Account reference"), {
      target: { value: " acct with spaces " },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    const query = queryOf(calls[2]);
    expect(query.get("occurredAtFrom")).toBe("2026-07-23T01:15:00Z");
    expect(query.get("occurredAtTo")).toBe("2026-07-24T01:15:00Z");
    expect(query.get("transactionType")).toBe("ATM_WITHDRAWAL");
    // Sent exactly as typed: not trimmed, not case folded.
    expect(query.get("accountRef")).toBe(" acct with spaces ");
    expect(query.get("page")).toBe("0");
  }, 20_000);

  it("refuses a reversed time range without sending anything", async () => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    fireEvent.change(screen.getByLabelText("From (KST)"), {
      target: { value: "2026-07-31T00:00" },
    });
    fireEvent.change(screen.getByLabelText("To (KST)"), {
      target: { value: "2026-07-01T00:00" },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("These filters cannot be searched");
    expect(alert).toHaveTextContent("The start of the time range must not be later than the end.");
    expect(alert).toHaveFocus();
  });

  it("refuses a reference of nothing but spaces without sending anything", async () => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    await user.type(screen.getByLabelText("Customer reference"), "   ");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Enter a customer reference, or leave the field empty.",
    );
  });

  it("restores the opening query on Reset", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    await user.selectOptions(screen.getByLabelText("Processing status"), "HELD");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(queryOf(calls[1]).get("processingStatus")).toBe("HELD");
    await answerWith(calls[1], listBody());

    await user.click(screen.getByRole("button", { name: "Reset filters" }));
    await settle();

    const query = queryOf(calls[2]);
    expect(query.has("processingStatus")).toBe(false);
    expect(query.get("page")).toBe("0");
    expect(query.get("size")).toBe("20");
    expect(query.get("sort")).toBe("occurredAt,desc");
    expect(screen.getByLabelText("Processing status")).toHaveValue("");
  });

  it("keeps reference filters out of the address bar and out of web storage", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    fireEvent.change(screen.getByLabelText("Customer reference"), {
      target: { value: LONG_CUSTOMER_REF },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    expect(queryOf(calls[1]).get("externalCustomerRef")).toBe(LONG_CUSTOMER_REF);
    expect(window.location.href).not.toContain(LONG_CUSTOMER_REF);
    expect(window.location.search).toBe("");
    expect(JSON.stringify({ ...window.localStorage })).not.toContain(LONG_CUSTOMER_REF);
    expect(JSON.stringify({ ...window.sessionStorage })).not.toContain(LONG_CUSTOMER_REF);
  });
});

describe("TransactionListPage sorting and pagination", () => {
  it("reports the sort direction with aria-sort and toggles it", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    const header = await screen.findByRole("columnheader", { name: /occurred/i });
    expect(header).toHaveAttribute("aria-sort", "descending");

    await user.click(within(header).getByRole("button"));
    await settle();

    expect(queryOf(calls[1]).get("sort")).toBe("occurredAt,asc");
    expect(queryOf(calls[1]).get("page")).toBe("0");
    await answerWith(calls[1], listBody());
    expect(
      screen.getByRole("columnheader", { name: /occurred/i }),
    ).toHaveAttribute("aria-sort", "ascending");
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

describe("TransactionListPage empty and error states", () => {
  it("invites the analyst to widen an empty filtered result", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    await user.selectOptions(screen.getByLabelText("Processing status"), "HELD");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    await answerWith(calls[1], listBody([]));

    expect(
      await screen.findByText("No transactions match these filters"),
    ).toBeInTheDocument();
    expect(screen.queryByRole("table")).not.toBeInTheDocument();

    await user.click(
      within(screen.getByText("No transactions match these filters").parentElement as HTMLElement)
        .getByRole("button", { name: "Reset filters" }),
    );
    await settle();
    expect(queryOf(calls[2]).has("processingStatus")).toBe(false);
  }, 20_000);

  it("says there is nothing to show when no filter is applied", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody([]));

    expect(await screen.findByText("There are no transactions to show yet.")).toBeInTheDocument();
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
    expect(spy).toHaveBeenCalledTimes(2);
  });

  it("refuses a malformed page in full", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(
      calls[0],
      {
        content: [listItem(), listItem({ currencyCode: "USD" })],
        page: {
          number: 0,
          size: 20,
          totalElements: 2,
          totalPages: 1,
          first: true,
          last: true,
        },
        traceId: TRACE_ID,
      },
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("The results could not be read");
    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("cust_ref_demo_a7f2")).not.toBeInTheDocument();
  });

  it("shows a fixed access-denied state on 403, with no retry", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();
    renderPage(client);
    await settle();
    await answerWith(
      calls[0],
      { code: "ACCESS_DENIED", message: "missing transaction:read" },
      403,
    );

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Access denied");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    expect(client.calls.notified).toBe(0);
    expect(document.body.textContent ?? "").not.toContain("transaction:read");
  });

  it("clears the table when a 401 ends the session", async () => {
    const { calls } = controlledFetch();
    const client = signedIn();
    renderPage(client);
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    // A second query, answered with 401.
    await act(async () => {
      client.emitSessionInvalidated();
    });

    expect(screen.queryByRole("table")).not.toBeInTheDocument();
    expect(screen.queryByText("cust_ref_demo_a7f2")).not.toBeInTheDocument();
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
    expect(document.body.innerHTML).not.toMatch(/bearer|access_token|id_token/i);
  });
});

describe("TransactionListPage keyboard use", () => {
  it("reaches every filter control, Apply and Reset with the keyboard alone", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const order = [
      screen.getByLabelText("From (KST)"),
      screen.getByLabelText("To (KST)"),
      screen.getByLabelText("Transaction type"),
      screen.getByLabelText("Processing status"),
      screen.getByLabelText("Customer reference"),
      screen.getByLabelText("Account reference"),
      screen.getByRole("button", { name: "Apply filters" }),
      screen.getByRole("button", { name: "Reset filters" }),
    ];

    order[0].focus();
    expect(order[0]).toHaveFocus();
    for (let index = 1; index < order.length; index += 1) {
      await user.tab();
      expect(order[index]).toHaveFocus();
    }
  }, 20_000);

  it("applies the filters when Enter is pressed inside a field", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const field = screen.getByLabelText("Customer reference");
    await user.click(field);
    await user.keyboard("cust{Enter}");
    await settle();

    expect(queryOf(calls[1]).get("externalCustomerRef")).toBe("cust");
  });

  it("re-sorts from the keyboard", async () => {
    const { calls } = controlledFetch();
    const user = userEvent.setup();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());

    const header = await screen.findByRole("columnheader", { name: /occurred/i });
    within(header).getByRole("button").focus();
    await user.keyboard("{Enter}");
    await settle();

    expect(queryOf(calls[1]).get("sort")).toBe("occurredAt,asc");
  });
});

describe("TransactionListPage validation refusal focus", () => {
  /** Puts the draft into a state `commitDraft` refuses for exactly one reason. */
  function reverseTheRange(): void {
    fireEvent.change(screen.getByLabelText("From (KST)"), {
      target: { value: "2026-07-31T00:00" },
    });
    fireEvent.change(screen.getByLabelText("To (KST)"), {
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

    // A reversed time range: one problem.
    reverseTheRange();
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    const first = screen.getByRole("alert");
    // Exactly one entry, so the refusal cannot be quietly carrying a second
    // problem that the text assertion below would still be satisfied by.
    const firstProblems = within(first).getAllByRole("listitem");
    expect(firstProblems).toHaveLength(1);
    expect(firstProblems[0]).toHaveTextContent(
      "The start of the time range must not be later than the end.",
    );
    expect(first).toHaveTextContent("The start of the time range must not be later than the end.");
    expect(first).toHaveFocus();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(client.calls.authorizeRequest).toBe(lookupsAfterFirstLoad);

    // The analyst leaves the explanation and carries on in the form. Focus has
    // to genuinely belong to another control before the second Apply: if it
    // were still sitting on the summary, the summary would "have focus" after
    // the second refusal whether or not focus was ever moved there again.
    const customerRef = screen.getByLabelText("Customer reference");
    await user.click(customerRef);
    expect(document.activeElement).toBe(customerRef);
    expect(first).not.toHaveFocus();

    // The range is fixed and a different single problem is introduced, so the
    // refusal still carries exactly one entry. A signature built from that
    // count would not change here, and focus would stay where the analyst left
    // it instead of moving to the new explanation.
    fireEvent.change(screen.getByLabelText("To (KST)"), {
      target: { value: "2026-08-01T00:00" },
    });
    await user.type(customerRef, "   ");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    // Applying put focus on the Apply button, and the summary is the very same
    // node as before, so it is focused again only because the effect ran a
    // second time for the second refusal.
    const second = screen.getByRole("alert");
    expect(second).toBe(first);
    const secondProblems = within(second).getAllByRole("listitem");
    expect(secondProblems).toHaveLength(1);
    expect(secondProblems[0]).toHaveTextContent(
      "Enter a customer reference, or leave the field empty.",
    );
    expect(second).toHaveTextContent("Enter a customer reference, or leave the field empty.");
    expect(second).not.toHaveTextContent("The start of the time range");
    expect(within(second).getAllByRole("listitem")).toHaveLength(1);
    expect(second).toHaveFocus();

    // Neither refusal cost a credential lookup or a request.
    expect(spy).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(1);
    expect(client.calls.authorizeRequest).toBe(lookupsAfterFirstLoad);
  }, 20_000);

  it("moves focus again when the identical refusal is submitted twice", async () => {
    const { calls, spy } = controlledFetch();
    const client = signedIn();
    const user = userEvent.setup();
    renderPage(client);
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    const lookupsAfterFirstLoad = client.calls.authorizeRequest;
    reverseTheRange();
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(screen.getByRole("alert")).toHaveFocus();

    // The analyst carries on somewhere else and asks again without changing
    // anything. The same explanation is still the answer, so it is announced
    // again rather than left behind.
    screen.getByLabelText("Customer reference").focus();
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

    reverseTheRange();
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(screen.getByRole("alert")).toHaveFocus();

    // A search that is accepted clears the refusal and moves focus nowhere: it
    // stays on the control the analyst just used.
    const resetButton = screen.getByRole("button", { name: "Reset filters" });
    await user.click(resetButton);
    await settle();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(resetButton).toHaveFocus();
    // Reset restored a query identical to the one already on screen, so it
    // asked the Backend for nothing.
    expect(calls).toHaveLength(1);

    await user.selectOptions(screen.getByLabelText("Processing status"), "HELD");
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(queryOf(calls[1]).get("processingStatus")).toBe("HELD");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply filters" })).toHaveFocus();
    await answerWith(calls[1], listBody());

    // And the refusal that follows is announced on its own merits, not skipped
    // because an earlier one looked the same.
    reverseTheRange();
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();
    expect(screen.getByRole("alert")).toHaveFocus();
  }, 30_000);

  it("keeps the typed reference out of the refusal and out of every other node", async () => {
    const { calls, spy } = controlledFetch();
    const user = userEvent.setup();
    const { container } = renderPage(signedIn());
    await settle();
    await answerWith(calls[0], listBody());
    await screen.findByRole("table");

    reverseTheRange();
    fireEvent.change(screen.getByLabelText("Customer reference"), {
      target: { value: LONG_CUSTOMER_REF },
    });
    await user.click(screen.getByRole("button", { name: "Apply filters" }));
    await settle();

    const alert = screen.getByRole("alert");
    expect(alert).toHaveFocus();
    expect(alert.textContent ?? "").not.toContain(LONG_CUSTOMER_REF);

    // The field the analyst typed into is the one place the value belongs. It
    // is excluded here, and nothing else in the tree - no text node, no title,
    // no data attribute, no hidden input - is allowed to carry it.
    const field = screen.getByLabelText("Customer reference");
    const elsewhere = Array.from(container.querySelectorAll("*")).filter((element) => {
      if (element === field) {
        return false;
      }
      const inAttribute = Array.from(element.attributes).some((attribute) =>
        attribute.value.includes(LONG_CUSTOMER_REF),
      );
      const inText = Array.from(element.childNodes).some(
        (node) => (node.nodeValue ?? "").includes(LONG_CUSTOMER_REF),
      );
      return inAttribute || inText;
    });
    expect(elsewhere).toEqual([]);
    expect(window.location.href).not.toContain(LONG_CUSTOMER_REF);
    expect(JSON.stringify({ ...window.localStorage })).not.toContain(LONG_CUSTOMER_REF);
    expect(JSON.stringify({ ...window.sessionStorage })).not.toContain(LONG_CUSTOMER_REF);
    expect(spy).toHaveBeenCalledTimes(1);
  }, 20_000);
});
