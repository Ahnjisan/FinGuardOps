import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthSession, CredentialAuthClient } from "../auth/authClient";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse, mockFetchOnce, mockFetchRejectOnce } from "../test/mockFetch";
import {
  ForbiddenError,
  HttpError,
  InvalidResponseError,
  NetworkError,
  RequestNotAllowedError,
  TimeoutError,
  UnauthorizedError,
} from "./errors";
import {
  fetchTransactionDetail,
  fetchTransactionList,
  isTransactionDetailEnvelope,
  isTransactionListPage,
  type TransactionListQuery,
} from "./transactionApi";

const BASE = "http://localhost:8080";
const TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const TRACE_ID = "trace_demo_tx_list_01";

const SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: ["FDS_ANALYST"],
};

function signedIn(): FakeAuthClient {
  return createFakeAuthClient({ initialSession: SESSION });
}

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

function detailItem(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    transactionId: TRANSACTION_ID,
    transactionType: "ACCOUNT_TRANSFER",
    amount: "1250000",
    currencyCode: "KRW",
    occurredAt: "2026-07-23T01:15:30Z",
    externalCustomerRef: "cust_ref_demo_a7f2",
    senderAccountRef: "acct_ref_demo_s91c",
    recipientAccountRef: "acct_ref_demo_r44d",
    channel: "MOBILE_BANKING",
    deviceRef: "device_ref_demo_18b3",
    processingStatus: "ADDITIONAL_AUTH_REQUIRED",
    createdAt: "2026-07-23T01:15:31Z",
    updatedAt: "2026-07-23T01:15:32Z",
    ...overrides,
  };
}

function listBody(
  content: readonly Record<string, unknown>[] = [listItem()],
  pageOverrides: Record<string, unknown> = {},
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
      ...pageOverrides,
    },
    traceId: TRACE_ID,
  };
}

function detailBody(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return { transaction: detailItem(overrides), traceId: TRACE_ID };
}

function sentUrl(): string {
  return (vi.mocked(fetch).mock.calls[0][0] as Request).url;
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", BASE);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("fetchTransactionList — request", () => {
  it("sends GET to the list path with a credential and no query by default", async () => {
    mockFetchOnce(async () => jsonResponse(listBody()));

    const result = await fetchTransactionList(signedIn());

    const request = vi.mocked(fetch).mock.calls[0][0] as Request;
    expect(request.method).toBe("GET");
    expect(request.url).toBe(`${BASE}/api/v1/transactions`);
    expect(request.headers.get("Authorization")).toMatch(/^Bearer /);
    expect(request.body).toBeNull();
    expect(result.traceId).toBe(TRACE_ID);
    expect(result.data.content).toHaveLength(1);
  });

  it("encodes every filter into the one canonical query", async () => {
    mockFetchOnce(async () => jsonResponse(listBody()));

    await fetchTransactionList(signedIn(), {
      sort: "occurredAt,desc",
      size: 20,
      page: 0,
      accountRef: "acct_ref_demo_s91c",
      externalCustomerRef: "cust_ref_demo_a7f2",
      processingStatus: "HELD",
      transactionType: "ACCOUNT_TRANSFER",
      occurredAtTo: "2026-07-24T00:00:00Z",
      occurredAtFrom: "2026-07-23T00:00:00Z",
    });

    expect(sentUrl()).toBe(
      `${BASE}/api/v1/transactions` +
        "?occurredAtFrom=2026-07-23T00%3A00%3A00Z" +
        "&occurredAtTo=2026-07-24T00%3A00%3A00Z" +
        "&transactionType=ACCOUNT_TRANSFER" +
        "&processingStatus=HELD" +
        "&externalCustomerRef=cust_ref_demo_a7f2" +
        "&accountRef=acct_ref_demo_s91c" +
        "&page=0&size=20&sort=occurredAt%2Cdesc",
    );
  });

  it("keeps an injected separator inside an opaque reference", async () => {
    for (const hostile of ["acct&page=99", "acct=evil", "acct#top", "acct%2Fnotes"]) {
      mockFetchOnce(async () => jsonResponse(listBody()));
      await fetchTransactionList(signedIn(), { accountRef: hostile, page: 0 });

      const parsed = new URL(sentUrl());
      expect(parsed.pathname).toBe("/api/v1/transactions");
      expect(parsed.hash).toBe("");
      expect([...parsed.searchParams.keys()].sort()).toEqual(["accountRef", "page"]);
      expect(parsed.searchParams.get("accountRef")).toBe(hostile);
      vi.unstubAllGlobals();
    }
  });

  it("refuses a malformed filter before any credential lookup or fetch", async () => {
    const rejected: readonly unknown[] = [
      { page: -1 },
      { page: 0.5 },
      { page: Number.MAX_SAFE_INTEGER + 1 },
      { page: 2147483648 },
      { page: Number.NaN },
      { page: "0" },
      { size: 0 },
      { size: 101 },
      { size: 20.5 },
      { sort: "occurredAt,ASC" },
      { sort: "occurredAt" },
      { sort: "createdAt,asc" },
      { sort: " occurredAt,asc" },
      { transactionType: "account_transfer" },
      { transactionType: "UNKNOWN_TYPE" },
      { processingStatus: "held" },
      { occurredAtFrom: "2026-07-23" },
      { occurredAtFrom: "2026-07-23T00:00:00+09:00" },
      { occurredAtFrom: "2026-02-30T00:00:00Z" },
      { accountRef: "" },
      { accountRef: "   " },
      { unknownFilter: "1" },
    ];

    for (const query of rejected) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(listBody()));

      await expect(
        fetchTransactionList(client, query as TransactionListQuery),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);

      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("keeps a padded transaction reference verbatim, as Backend does", async () => {
    // `TransactionQueryValidator.validateReference` refuses blank and nothing
    // else, so this is an exact-match filter for a stored reference that really
    // does carry those spaces.
    mockFetchOnce(async () => jsonResponse(listBody()));

    await fetchTransactionList(signedIn(), { accountRef: " acct " });

    const parsed = new URL(sentUrl());
    expect(parsed.searchParams.get("accountRef")).toBe(" acct ");
    expect(parsed.search).toBe("?accountRef=+acct+");
  });

  it("accepts a non-blank transaction reference of any length", async () => {
    // `TransactionQueryValidator.validateReference` imposes no length bound, so
    // neither the shared structural floor nor this rule may invent one.
    for (const [name, value] of [
      ["externalCustomerRef", "a".repeat(129)],
      ["externalCustomerRef", "a".repeat(257)],
      ["accountRef", "b".repeat(257)],
      ["accountRef", "c".repeat(4096)],
    ] as ReadonlyArray<readonly [string, string]>) {
      mockFetchOnce(async () => jsonResponse(listBody()));

      await fetchTransactionList(signedIn(), { [name]: value });

      expect(new URL(sentUrl()).searchParams.get(name)).toBe(value);
      vi.unstubAllGlobals();
    }
  });

  it("refuses a blank transaction reference before any credential lookup", async () => {
    for (const value of ["", " ", "   ", "\u3000", "\u2028"]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(listBody()));

      await expect(fetchTransactionList(client, { accountRef: value })).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("keeps a long reference out of the error a failed request produces", async () => {
    const secret = "s".repeat(300);
    mockFetchOnce(async () => jsonResponse({ code: "X" }, { status: 500 }));

    const error = await fetchTransactionList(signedIn(), { accountRef: secret }).catch(
      (thrown: unknown) => thrown,
    );

    expect(error).toBeInstanceOf(HttpError);
    expect((error as Error).message).not.toContain(secret);
    expect(JSON.stringify(error)).not.toContain(secret);
  });

  it("refuses an inverted occurredAt range without spending a request", async () => {
    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(listBody()));

    await expect(
      fetchTransactionList(client, {
        occurredAtFrom: "2026-07-24T00:00:00Z",
        occurredAtTo: "2026-07-23T00:00:00Z",
      }),
    ).rejects.toBeInstanceOf(RequestNotAllowedError);

    expect(client.calls.authorizeRequest).toBe(0);
    expect(vi.mocked(fetch)).not.toHaveBeenCalled();
  });

  it("allows an empty range where the bounds are equal", async () => {
    mockFetchOnce(async () => jsonResponse(listBody([])));

    await expect(
      fetchTransactionList(signedIn(), {
        occurredAtFrom: "2026-07-23T00:00:00Z",
        occurredAtTo: "2026-07-23T00:00:00Z",
      }),
    ).resolves.toBeDefined();
  });
});

describe("fetchTransactionList — response", () => {
  it("accepts an empty page", async () => {
    mockFetchOnce(async () => jsonResponse(listBody([])));
    const result = await fetchTransactionList(signedIn());
    expect(result.data.content).toEqual([]);
    expect(result.data.page.totalPages).toBe(0);
  });

  it("keeps the amount as the contract string rather than a number", async () => {
    // Past Number.MAX_SAFE_INTEGER, so a client that parsed it would already
    // be showing a different amount.
    const amount = "900719925474099";
    expect(Number(amount)).toBeLessThan(Number.MAX_SAFE_INTEGER);
    mockFetchOnce(async () => jsonResponse(listBody([listItem({ amount })])));
    const result = await fetchTransactionList(signedIn());
    expect(result.data.content[0].amount).toBe(amount);
    expect(typeof result.data.content[0].amount).toBe("string");
  });

  it("accepts fifteen digits and refuses sixteen", async () => {
    expect(isTransactionListPage(listBody([listItem({ amount: "999999999999999" })]))).toBe(
      true,
    );
    expect(isTransactionListPage(listBody([listItem({ amount: "1000000000000000" })]))).toBe(
      false,
    );
    expect(
      isTransactionDetailEnvelope(detailBody({ amount: "999999999999999" })),
    ).toBe(true);
    expect(
      isTransactionDetailEnvelope(detailBody({ amount: "1000000000000000" })),
    ).toBe(false);
  });

  it("accepts KRW only", async () => {
    expect(isTransactionListPage(listBody([listItem({ currencyCode: "KRW" })]))).toBe(true);
    for (const currencyCode of ["USD", "JPY", "EUR", "krw", "KRW ", " KRW", "KR", "KRWW", "", null, 0]) {
      expect(isTransactionListPage(listBody([listItem({ currencyCode })]))).toBe(false);
      expect(isTransactionDetailEnvelope(detailBody({ currencyCode }))).toBe(false);
    }
  });

  it("refuses a missing or unknown key on an item", async () => {
    const complete = listItem();
    for (const key of Object.keys(complete)) {
      const missing = { ...complete };
      delete missing[key];
      expect(isTransactionListPage(listBody([missing]))).toBe(false);
    }
    expect(isTransactionListPage(listBody([{ ...complete, riskLevel: "HIGH" }]))).toBe(false);
    expect(isTransactionListPage(listBody([{ ...complete, version: 1 }]))).toBe(false);
  });

  it("refuses a malformed enum, uuid, instant or amount", async () => {
    for (const override of [
      { transactionType: "UNKNOWN" },
      { transactionType: "account_transfer" },
      { processingStatus: null },
      { transactionId: TRANSACTION_ID.toUpperCase() },
      { transactionId: "not-a-uuid" },
      { occurredAt: "2026-07-23T01:15:30+09:00" },
      { occurredAt: "2026-07-23" },
      { createdAt: null },
      { amount: 1250000 },
      { amount: "0" },
      { amount: "1250.5" },
      { amount: "-1250000" },
      { amount: "1.25e6" },
      { currencyCode: "USD" },
      { currencyCode: "krw" },
      { externalCustomerRef: null },
      { externalCustomerRef: " padded" },
      { senderAccountRef: "" },
    ]) {
      expect(isTransactionListPage(listBody([listItem(override)]))).toBe(false);
    }
  });

  it("accepts an explicit null only where the contract allows one", async () => {
    expect(isTransactionListPage(listBody([listItem({ recipientAccountRef: null })]))).toBe(true);
    expect(isTransactionDetailEnvelope(detailBody({ deviceRef: null }))).toBe(true);
    expect(isTransactionDetailEnvelope(detailBody({ channel: null }))).toBe(false);
  });

  it("rejects the whole page when a single item is malformed", async () => {
    const body = listBody([listItem(), listItem({ amount: "0" }), listItem()], {
      totalElements: 3,
    });
    expect(isTransactionListPage(body)).toBe(false);

    mockFetchOnce(async () => jsonResponse(body));
    await expect(fetchTransactionList(signedIn())).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("refuses page metadata whose arithmetic does not hold", async () => {
    for (const pageOverrides of [
      { totalElements: 45, totalPages: 2 },
      { totalElements: 1, totalPages: 1, first: false },
      { totalElements: 1, totalPages: 1, last: false },
      { totalElements: 0, totalPages: 0, size: 0 },
      { totalElements: -1, totalPages: 0 },
      { totalElements: Number.MAX_SAFE_INTEGER + 1, totalPages: 1 },
      { totalElements: 1.5, totalPages: 1 },
      { number: -1, totalElements: 1, totalPages: 1 },
      { size: 101, totalElements: 1, totalPages: 1 },
    ]) {
      expect(isTransactionListPage(listBody([listItem()], pageOverrides))).toBe(false);
    }
  });

  it("refuses a content length that contradicts the page metadata", async () => {
    // says two elements over one page, but carries one item
    expect(
      isTransactionListPage(listBody([listItem()], { totalElements: 2, totalPages: 1 })),
    ).toBe(false);
    // says empty, but carries an item
    expect(
      isTransactionListPage(listBody([listItem()], { totalElements: 0, totalPages: 0 })),
    ).toBe(false);
  });

  it("refuses an envelope with a missing, extra or malformed top-level key", async () => {
    expect(isTransactionListPage({ content: [], page: listBody([]).page })).toBe(false);
    expect(isTransactionListPage({ ...listBody([]), extra: 1 })).toBe(false);
    expect(isTransactionListPage({ ...listBody([]), traceId: "short" })).toBe(false);
    expect(isTransactionListPage({ ...listBody([]), content: {} })).toBe(false);
    expect(isTransactionListPage(null)).toBe(false);
    expect(isTransactionListPage([listBody([])])).toBe(false);
  });
});

describe("fetchTransactionDetail", () => {
  it("sends GET to the detail path with no query", async () => {
    mockFetchOnce(async () => jsonResponse(detailBody()));

    const result = await fetchTransactionDetail(signedIn(), TRANSACTION_ID);

    expect(sentUrl()).toBe(`${BASE}/api/v1/transactions/${TRANSACTION_ID}`);
    expect(new URL(sentUrl()).search).toBe("");
    expect(result.data.transaction.transactionId).toBe(TRANSACTION_ID);
  });

  it("refuses a non-canonical transaction id before any credential lookup", async () => {
    for (const id of [
      TRANSACTION_ID.toUpperCase(),
      "2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001",
      "2f4c0a4e-8a9d-4c2f-ca1b-7d6e5f430001",
      `${TRANSACTION_ID} `,
      `${TRANSACTION_ID}/notes`,
      `${TRANSACTION_ID}%2Fnotes`,
      "../actuator",
      "",
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(detailBody()));

      await expect(fetchTransactionDetail(client, id)).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("refuses a detail envelope with a missing or unknown key", async () => {
    const complete = detailItem();
    for (const key of Object.keys(complete)) {
      const missing = { ...complete };
      delete missing[key];
      expect(isTransactionDetailEnvelope({ transaction: missing, traceId: TRACE_ID })).toBe(false);
    }
    expect(
      isTransactionDetailEnvelope({
        transaction: { ...complete, riskLevel: "HIGH" },
        traceId: TRACE_ID,
      }),
    ).toBe(false);
    expect(isTransactionDetailEnvelope({ ...detailBody(), extra: 1 })).toBe(false);
    expect(isTransactionDetailEnvelope({ transaction: detailItem() })).toBe(false);
  });
});

describe("transaction API — status, trace and failure boundaries", () => {
  it("accepts 200 only", async () => {
    for (const status of [201, 202, 204]) {
      mockFetchOnce(async () =>
        status === 204 ? new Response(null, { status }) : jsonResponse(listBody(), { status }),
      );
      await expect(fetchTransactionList(signedIn())).rejects.toBeInstanceOf(InvalidResponseError);
      vi.unstubAllGlobals();
    }
  });

  it("accepts a matching header trace id and refuses a mismatched one", async () => {
    mockFetchOnce(async () => jsonResponse(listBody(), { headers: { "X-Trace-Id": TRACE_ID } }));
    await expect(fetchTransactionList(signedIn())).resolves.toMatchObject({ traceId: TRACE_ID });
    vi.unstubAllGlobals();

    mockFetchOnce(async () =>
      jsonResponse(listBody(), { headers: { "X-Trace-Id": "trace_demo_other_01" } }),
    );
    await expect(fetchTransactionList(signedIn())).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("refuses a success whose trace header is present but malformed", async () => {
    // Absent is fine - a proxy may strip it - but a 2xx carrying a value
    // outside the trace contract did not come from TraceIdFilter intact.
    for (const traceId of ["short", "a".repeat(65), "_leading", "has space", "trace/id", "trace id 01"]) {
      mockFetchOnce(async () => jsonResponse(listBody(), { headers: { "X-Trace-Id": traceId } }));
      await expect(fetchTransactionList(signedIn())).rejects.toBeInstanceOf(
        InvalidResponseError,
      );
      vi.unstubAllGlobals();
    }
  });

  it("still accepts a success with no trace header at all", async () => {
    mockFetchOnce(async () => jsonResponse(listBody()));
    await expect(fetchTransactionList(signedIn())).resolves.toMatchObject({ traceId: TRACE_ID });
  });

  it("maps 401 and 403 without reading the response body", async () => {
    const leaky = {
      code: "ACCESS_DENIED",
      message: "leaked",
      traceId: "leaked_body_trace_01",
      fieldErrors: [],
    };

    mockFetchOnce(async () => jsonResponse(leaky, { status: 401 }));
    await expect(fetchTransactionList(signedIn())).rejects.toBeInstanceOf(UnauthorizedError);
    vi.unstubAllGlobals();

    mockFetchOnce(async () => jsonResponse(leaky, { status: 403 }));
    const forbidden = await fetchTransactionList(signedIn()).catch((error: unknown) => error);
    expect(forbidden).toBeInstanceOf(ForbiddenError);
    expect(JSON.stringify(forbidden)).not.toContain("leaked");
    expect((forbidden as ForbiddenError).message).not.toContain("leaked");
  });

  it("invalidates the session on 401 and leaves it alone on 403", async () => {
    const unauthorizedClient = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 401 }));
    await expect(fetchTransactionList(unauthorizedClient)).rejects.toBeInstanceOf(
      UnauthorizedError,
    );
    expect(unauthorizedClient.calls.invalidateIfCurrent).toBe(1);
    vi.unstubAllGlobals();

    const forbiddenClient = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 403 }));
    await expect(fetchTransactionList(forbiddenClient)).rejects.toBeInstanceOf(ForbiddenError);
    expect(forbiddenClient.calls.invalidateIfCurrent).toBe(0);
  });

  it("keeps every other status opaque and performs exactly one fetch", async () => {
    for (const status of [400, 404, 409, 422, 500, 503]) {
      mockFetchOnce(async () => jsonResponse({ code: "X", message: "leaked" }, { status }));
      const error = await fetchTransactionList(signedIn()).catch((thrown: unknown) => thrown);
      expect(error).toBeInstanceOf(HttpError);
      expect((error as HttpError).status).toBe(status);
      expect((error as HttpError).message).not.toContain("leaked");
      expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
      vi.unstubAllGlobals();
    }
  });

  it("does not retry a failed network call", async () => {
    mockFetchRejectOnce(new TypeError("connection refused"));
    await expect(fetchTransactionList(signedIn())).rejects.toBeInstanceOf(NetworkError);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
  });

  it("is bounded by the shared five-second deadline", async () => {
    vi.useFakeTimers();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => new Promise<Response>(() => {})),
    );

    const pending = fetchTransactionList(signedIn());
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(5000);
    await assertion;
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);

    vi.useRealTimers();
  });

  it("sends nothing at all when there is no session", async () => {
    const client: CredentialAuthClient = createFakeAuthClient({ initialSession: null });
    mockFetchOnce(async () => jsonResponse(listBody()));

    await expect(fetchTransactionList(client)).rejects.toThrow();
    expect(vi.mocked(fetch)).not.toHaveBeenCalled();
  });
});
