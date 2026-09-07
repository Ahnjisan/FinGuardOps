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

const { TransactionDetailPage } = await import("./TransactionDetailPage");

const TRACE_ID = "trace_demo_tx_detail_01";
const TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const DETAIL_ROUTE = `/transactions/${TRANSACTION_ID}`;
const LONG_DEVICE_REF = "device_ref_2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001_branch_0091";

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
  { path: "/transactions/:transactionId", element: <TransactionDetailPage /> },
  { path: "/transactions", element: <p>Transaction list stands in here.</p> },
];

function transactionBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    transaction: {
      transactionId: TRANSACTION_ID,
      transactionType: "ACCOUNT_TRANSFER",
      amount: "1250000",
      currencyCode: "KRW",
      occurredAt: "2026-07-23T01:15:30Z",
      externalCustomerRef: "cust_ref_demo_a7f2",
      senderAccountRef: "acct_ref_demo_s91c",
      recipientAccountRef: "acct_ref_demo_r44d",
      channel: "MOBILE_BANKING",
      deviceRef: "device_ref_demo_31aa",
      processingStatus: "ADDITIONAL_AUTH_REQUIRED",
      createdAt: "2026-07-23T01:15:31Z",
      updatedAt: "2026-07-23T02:40:07Z",
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
  await answerWith(calls[0], transactionBody(overrides));
  await waitFor(() => {
    expect(screen.getByRole("heading", { name: "Transaction", level: 3 })).toBeInTheDocument();
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

describe("TransactionDetailPage request", () => {
  it("asks the detail endpoint for exactly the transaction in the address", async () => {
    const { calls, spy } = controlledFetch();
    renderPage(signedIn());
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    const url = new URL(calls[0].request.url);
    expect(url.origin).toBe("http://localhost:8080");
    expect(url.pathname).toBe(`/api/v1/transactions/${TRANSACTION_ID}`);
    expect(url.search).toBe("");
    expect(calls[0].request.method).toBe("GET");
  });

  it("shows a heading naming the transaction, a way back, and a loading state", async () => {
    controlledFetch();
    renderPage(signedIn());
    await settle();

    expect(
      screen.getByRole("heading", { name: `Transaction ${TRANSACTION_ID}`, level: 2 }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to transactions" })).toHaveAttribute(
      "href",
      "/transactions",
    );
    expect(screen.getByText("Loading transaction...")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("Loading transaction");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });
});

describe("TransactionDetailPage record", () => {
  it("shows every field the detail contract carries, and only those", async () => {
    await showRecord();

    expect(valueOf("Transaction ID")).toHaveTextContent(TRANSACTION_ID);
    expect(valueOf("Type")).toHaveTextContent("Account transfer");
    expect(valueOf("Channel")).toHaveTextContent("Mobile banking");
    expect(valueOf("Processing status")).toHaveTextContent("Auth required");
    expect(valueOf("Amount")).toHaveTextContent("1,250,000");
    expect(valueOf("Amount")).toHaveTextContent("KRW");
    expect(valueOf("Occurred")).toHaveTextContent("2026-07-23 10:15:30 KST");
    expect(valueOf("Customer reference")).toHaveTextContent("cust_ref_demo_a7f2");
    expect(valueOf("From account")).toHaveTextContent("acct_ref_demo_s91c");
    expect(valueOf("To account")).toHaveTextContent("acct_ref_demo_r44d");
    expect(valueOf("Device")).toHaveTextContent("device_ref_demo_31aa");
    expect(valueOf("Recorded")).toHaveTextContent("2026-07-23 10:15:31 KST");
    expect(valueOf("Last updated")).toHaveTextContent("2026-07-23 11:40:07 KST");

    // Twelve names for the thirteen contract fields: `currencyCode` is read
    // beside the amount rather than as a line of its own. A thirteenth name
    // would be a field this console invented.
    expect(document.querySelectorAll("dt")).toHaveLength(12);
    expect(document.querySelectorAll("dd")).toHaveLength(12);
  });

  it("states Seoul wall clock and keeps the untouched UTC value for the machine", async () => {
    await showRecord();

    const occurred = within(valueOf("Occurred")).getByText(/KST$/);
    expect(occurred.tagName).toBe("TIME");
    expect(occurred).toHaveAttribute("datetime", "2026-07-23T01:15:30Z");
    expect(within(valueOf("Recorded")).getByText(/KST$/)).toHaveAttribute(
      "datetime",
      "2026-07-23T01:15:31Z",
    );
    expect(within(valueOf("Last updated")).getByText(/KST$/)).toHaveAttribute(
      "datetime",
      "2026-07-23T02:40:07Z",
    );
  });

  it("keeps all fifteen digits of the largest contract amount", async () => {
    await showRecord({ amount: "999999999999999" });

    expect(valueOf("Amount")).toHaveTextContent("999,999,999,999,999");
  });

  it("names the absence of a recipient account and a device rather than leaving a blank", async () => {
    await showRecord({ recipientAccountRef: null, deviceRef: null });

    expect(valueOf("To account")).toHaveTextContent("None recorded");
    expect(valueOf("Device")).toHaveTextContent("None recorded");
  });

  it("prints a long reference in full, once, and nowhere else in the DOM", async () => {
    await showRecord({ deviceRef: LONG_DEVICE_REF });

    const device = valueOf("Device");
    expect(device).toHaveTextContent(LONG_DEVICE_REF);
    expect(device.className).toContain("facts__ref");

    // Once as text, and not repeated into a title, an aria-label, a hidden
    // element or a data attribute.
    const occurrences = document.body.innerHTML.split(LONG_DEVICE_REF).length - 1;
    expect(occurrences).toBe(1);
    for (const element of Array.from(document.querySelectorAll("*"))) {
      for (const attribute of Array.from(element.attributes)) {
        expect(attribute.value).not.toContain(LONG_DEVICE_REF);
      }
    }
  });

  it("shows the processing status as a word and a mark, not as colour alone", async () => {
    await showRecord();

    const badge = within(valueOf("Processing status")).getByText("Auth required");
    expect(badge.className).toContain("badge--attention");
    expect(badge.querySelector(".badge__mark")).not.toBeNull();
  });

  it.each([
    ["RECEIVED", "Received"],
    ["ANALYZING", "Analyzing"],
    ["ANALYZED", "Analyzed"],
    ["APPROVED", "Approved"],
    ["ADDITIONAL_AUTH_REQUIRED", "Auth required"],
    ["HELD", "Held"],
    ["FAILED", "Failed"],
  ])("labels the %s processing status", async (status, label) => {
    await showRecord({ processingStatus: status });

    expect(valueOf("Processing status")).toHaveTextContent(label);
  });

  it.each([
    ["ACCOUNT_TRANSFER", "Account transfer"],
    ["OPEN_BANKING_TRANSFER", "Open banking transfer"],
    ["ATM_WITHDRAWAL", "ATM withdrawal"],
    ["LOAN_DISBURSED", "Loan disbursed"],
  ])("labels the %s transaction type", async (type, label) => {
    await showRecord({ transactionType: type });

    expect(valueOf("Type")).toHaveTextContent(label);
  });

  it.each([
    ["MOBILE_BANKING", "Mobile banking"],
    ["OPEN_BANKING", "Open banking"],
    ["ATM", "ATM"],
    ["CORE_BANKING", "Core banking"],
  ])("labels the %s channel", async (channel, label) => {
    await showRecord({ channel });

    expect(valueOf("Channel")).toHaveTextContent(label);
  });

  it("shows no risk, detection, case or business action anywhere", async () => {
    await showRecord();

    const rendered = (document.body.textContent ?? "").toLowerCase();
    for (const forbidden of [
      "risk",
      "score",
      "probability",
      "detection",
      "evidence",
      "fraud case",
      "reprocess",
      "copy",
    ]) {
      expect(rendered).not.toContain(forbidden);
    }
    // A read-only screen: nothing to press, and no form to submit.
    expect(screen.queryAllByRole("button")).toHaveLength(0);
    expect(document.querySelectorAll("form")).toHaveLength(0);
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });

  it("announces the result in the live region and raises no alert", async () => {
    await showRecord();

    const summary = screen.getByRole("status");
    expect(summary).toHaveTextContent("Showing the full transaction record.");
    expect(summary).toHaveAttribute("aria-live", "polite");
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("groups the record under headings below the page heading", async () => {
    await showRecord();

    const sections = screen.getAllByRole("heading", { level: 3 }).map((h) => h.textContent);
    expect(sections).toEqual([
      "Transaction",
      "Customer, accounts and device",
      "Ledger record",
    ]);
    // Every labelled section really points at a heading that exists once.
    for (const section of Array.from(document.querySelectorAll("[aria-labelledby]"))) {
      const id = section.getAttribute("aria-labelledby") ?? "";
      expect(document.querySelectorAll(`#${CSS.escape(id)}`)).toHaveLength(1);
    }
  });
});

describe("TransactionDetailPage failures", () => {
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

  it("reports a 404 as a transaction that is not there, and offers no retry", async () => {
    await failWith(404, { code: "TRANSACTION_NOT_FOUND" });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Transaction not found");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    expect(screen.queryByText("Loading transaction...")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("No record shown.");
  });

  it("reports a 403 as a fixed refusal, and offers no retry", async () => {
    await failWith(403, { code: "ACCESS_DENIED", message: "transaction:read required" });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Access denied");
    expect(within(alert).queryByRole("button")).not.toBeInTheDocument();
    expect(alert.textContent ?? "").not.toContain("transaction:read");
  });

  it("reports an unmapped Backend status without naming it", async () => {
    await failWith(503, { code: "SERVICE_UNAVAILABLE", message: "upstream down" });

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("The transaction could not be loaded");
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

    expect(screen.getByRole("alert")).toHaveTextContent(
      "The transaction took too long to load",
    );
    vi.useRealTimers();
  });

  it("refuses a record carrying a field outside the contract, showing none of it", async () => {
    const { calls } = controlledFetch();
    renderPage(signedIn());
    await settle();
    await answerWith(calls[0], {
      transaction: { ...(transactionBody().transaction as object), riskScore: 91 },
      traceId: TRACE_ID,
    });

    await waitFor(() => {
      expect(screen.getByRole("alert")).toHaveTextContent("The transaction could not be read");
    });
    // Not one field of a refused record is displayed.
    expect(document.body.textContent ?? "").not.toContain("cust_ref_demo_a7f2");
    expect(document.body.textContent ?? "").not.toContain("91");
    expect(document.querySelectorAll("dd")).toHaveLength(0);
  });

  it("carries no trace id into the screen", async () => {
    await failWith(500, { code: "INTERNAL_ERROR" });

    expect(document.body.innerHTML).not.toContain(TRACE_ID);
  });

  it("removes the previous record when a retry fails", async () => {
    const calls = await failWith(500);
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(screen.getByText("Loading transaction...")).toBeInTheDocument();
    expect(calls).toHaveLength(2);

    await answerWith(calls[1], transactionBody());
    await waitFor(() => {
      expect(screen.queryByRole("alert")).not.toBeInTheDocument();
    });
    expect(valueOf("Transaction ID")).toHaveTextContent(TRANSACTION_ID);
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
});

describe("TransactionDetailPage malformed address", () => {
  it("refuses the address, sends nothing, and echoes none of it", async () => {
    const { spy } = controlledFetch();
    const client = signedIn();
    renderPage(client, "/transactions/%32f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001");
    await settle();

    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("This is not a transaction address");
    expect(alert).toHaveFocus();
    expect(spy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
    expect(document.body.innerHTML).not.toContain("2f4c0a4e");
    expect(document.querySelectorAll("dd")).toHaveLength(0);
  });

  it("keeps the page heading free of the address and still offers the way back", async () => {
    controlledFetch();
    renderPage(signedIn(), "/transactions/not-a-uuid");
    await settle();

    expect(screen.getByRole("heading", { name: "Transaction", level: 2 })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to transactions" })).toHaveAttribute(
      "href",
      "/transactions",
    );
    expect(document.body.textContent ?? "").not.toContain("not-a-uuid");
    expect(screen.getByRole("status")).toHaveTextContent("This is not a transaction address.");
  });
});

describe("TransactionDetailPage navigation back to the list", () => {
  it("returns to the list with no state and no query", async () => {
    await showRecord();
    const user = userEvent.setup();

    await user.click(screen.getByRole("link", { name: "Back to transactions" }));

    expect(await screen.findByText("Transaction list stands in here.")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^Transaction / })).not.toBeInTheDocument();
  });
});
