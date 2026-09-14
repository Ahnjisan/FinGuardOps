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
import { buildQueryValues } from "./pagination";
import {
  fetchTransactionDetail,
  fetchTransactionList,
  isTransactionDetailEnvelope,
  isTransactionListPage,
  type TransactionListQuery,
  type TransactionType,
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

  it("refuses a well-formed page whose number or size is not the requested effective pagination", async () => {
    // 요청 effective page·size와 응답 page.number·page.size의 결합 검증 (Issue #291).
    // page·size를 생략하면 URL에 싣지 않고 Backend 기본값 page=0·size=20을 기대한다. 모든 body는 기존 형식·
    // 산술 validator를 통과하므로 binding 검증만 거부할 수 있는 반례이다.
    const rows: ReadonlyArray<
      readonly [string, TransactionListQuery | undefined, string, boolean, Record<string, unknown>]
    > = [
      [
        "명시적 page mismatch",
        { page: 1, size: 20 },
        "?page=1&size=20",
        false,
        { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true },
      ],
      [
        "명시적 size mismatch",
        { page: 0, size: 50 },
        "?page=0&size=50",
        false,
        { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true },
      ],
      [
        "page·size 생략, 응답 number가 기본값 0과 다름",
        undefined,
        "",
        true,
        { number: 1, size: 20, totalElements: 21, totalPages: 2, first: false, last: true },
      ],
      [
        "page·size 생략, 응답 size가 기본값 20과 다름",
        { sort: "occurredAt,desc" },
        "?sort=occurredAt%2Cdesc",
        true,
        { number: 0, size: 50, totalElements: 1, totalPages: 1, first: true, last: true },
      ],
    ];

    for (const [name, query, search, paginationOmitted, page] of rows) {
      const body = listBody([listItem()], page);
      expect(isTransactionListPage(body), name).toBe(true);

      const client = signedIn();
      // header trace도 body와 같은 값으로 실어 두 trace 경로 모두 오류에 반사되지 않는지 확인한다.
      mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

      const error = await fetchTransactionList(client, query).catch((thrown: unknown) => thrown);

      expect(error, name).toBeInstanceOf(InvalidResponseError);
      expect(Reflect.ownKeys(error as object).sort(), name).toEqual(["message", "name", "stack"]);
      expect(String(error), name).toBe(
        "InvalidResponseError: Received an unexpected response shape.",
      );
      expect(JSON.stringify(error), name).toBe('{"name":"InvalidResponseError"}');

      expect(vi.mocked(fetch), name).toHaveBeenCalledTimes(1);
      const request = vi.mocked(fetch).mock.calls[0][0] as Request;
      expect(request.method, name).toBe("GET");
      expect(request.url, name).toBe(`${BASE}/api/v1/transactions${search}`);
      const parsed = new URL(request.url);
      expect(parsed.searchParams.has("page"), name).toBe(!paginationOmitted);
      expect(parsed.searchParams.has("size"), name).toBe(!paginationOmitted);
      expect(client.calls.authorizeRequest, name).toBe(1);
      expect(client.calls.invalidateIfCurrent, name).toBe(0);
      expect(client.calls.notified, name).toBe(0);

      // 일반 숫자 부분 문자열 대신 raw body 전체와 고유 trace·거래·reference marker의 비반사를 확인한다.
      const disclosed = `${String(error)} ${JSON.stringify(error)}`;
      for (const secret of [
        JSON.stringify(body),
        JSON.stringify(body.page),
        TRACE_ID,
        TRANSACTION_ID,
        "cust_ref_demo_a7f2",
        "acct_ref_demo_s91c",
        "acct_ref_demo_r44d",
      ]) {
        expect(disclosed, name).not.toContain(secret);
      }
      expect(disclosed, name).not.toMatch(/trace|_ref_demo_|2f4c0a4e|totalElements/i);
      vi.unstubAllGlobals();
    }
  });

  it("accepts a partial last page and empty pages whose number and size are the requested ones", async () => {
    // Issue #291 green 회귀: production 수정 전에도 통과하는 의도된 회귀 테스트이다. 응답 number·size가 요청
    // effective 값과 같으면 content가 size보다 적거나 비어 있어도 거부하지 않는다. binding 구현이
    // `content.length === size` 같은 잘못된 조건을 추가하지 않았음을 보호한다.
    const rows: ReadonlyArray<
      readonly [string, TransactionListQuery, string, number, Record<string, unknown>]
    > = [
      [
        "마지막 페이지의 일부 content",
        { page: 2, size: 20 },
        "?page=2&size=20",
        3,
        { number: 2, size: 20, totalElements: 43, totalPages: 3, first: false, last: true },
      ],
      [
        "totalPages를 초과한 빈 page",
        { page: 5, size: 20 },
        "?page=5&size=20",
        0,
        { number: 5, size: 20, totalElements: 43, totalPages: 3, first: false, last: true },
      ],
      [
        "전체 결과 0건인 빈 page",
        { page: 0, size: 50 },
        "?page=0&size=50",
        0,
        { number: 0, size: 50, totalElements: 0, totalPages: 0, first: true, last: true },
      ],
    ];

    for (const [name, query, search, contentLength, page] of rows) {
      const body = listBody(Array.from({ length: contentLength }, () => listItem()), page);
      expect(isTransactionListPage(body), name).toBe(true);

      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

      const result = await fetchTransactionList(client, query);

      expect(vi.mocked(fetch), name).toHaveBeenCalledTimes(1);
      expect(sentUrl(), name).toBe(`${BASE}/api/v1/transactions${search}`);
      expect(result.data.content, name).toHaveLength(contentLength);
      expect(result.data.page, name).toStrictEqual(page);
      expect(result.data.page.number, name).toBe(query.page);
      expect(result.data.page.size, name).toBe(query.size);
      expect(result.traceId, name).toBe(TRACE_ID);
      expect(client.calls.authorizeRequest, name).toBe(1);
      expect(client.calls.invalidateIfCurrent, name).toBe(0);
      vi.unstubAllGlobals();
    }
  });

  it("reads page and size once and binds both the URL and the response to that one snapshot", async () => {
    // Issue #291 단일 pagination snapshot 반례. 원본 page·size getter는 첫 평가에서만 1·20을 주고, 다시
    // 평가되면 다른 유효 값 3·50을 준다. 원본을 다시 읽는 구현은 평가 횟수가 2가 되고 URL이 3·50으로 바뀐다.
    // 응답 metadata는 첫 snapshot 1·20과 같으므로 validator가 같은 snapshot을 쓰면 정상 성공한다.
    // getter마다 평가 시점의 credential 조회 횟수를 기록해, 읽기가 credential 조회 전에 끝났는지도 확인한다.
    const client = signedIn();
    const pageReads: number[] = [];
    const sizeReads: number[] = [];
    const query: TransactionListQuery = {
      get page() {
        pageReads.push(client.calls.authorizeRequest);
        return pageReads.length === 1 ? 1 : 3;
      },
      get size() {
        sizeReads.push(client.calls.authorizeRequest);
        return sizeReads.length === 1 ? 20 : 50;
      },
      sort: "occurredAt,desc",
    };
    const body = listBody([listItem()], {
      number: 1,
      size: 20,
      totalElements: 21,
      totalPages: 2,
      first: false,
      last: true,
    });
    expect(isTransactionListPage(body)).toBe(true);
    mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

    const result = await fetchTransactionList(client, query);

    expect(pageReads).toEqual([0]);
    expect(sizeReads).toEqual([0]);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    const request = vi.mocked(fetch).mock.calls[0][0] as Request;
    expect(request.method).toBe("GET");
    expect(request.url).toBe(`${BASE}/api/v1/transactions?page=1&size=20&sort=occurredAt%2Cdesc`);
    expect(result.data.page.number).toBe(1);
    expect(result.data.page.size).toBe(20);
    expect(result.traceId).toBe(TRACE_ID);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });

  it("evaluates an identity-dependent sort accessor with the caller's query as the receiver", async () => {
    // Issue #291 accessor 의미 보존 반례 A. sort getter는 원본 query identity로 평가될 때만 occurredAt,asc를
    // 주고, 다른 객체를 receiver로 평가되면 유효한 다른 값 occurredAt,desc를 준다. 기존 원본 query 경로인
    // `buildQueryValues()` 결과를 기준값으로 기록하고, 보정 경로 URL도 정확히 같은 값·순서를 쓰는지 확인한다.
    function identitySortQuery(): TransactionListQuery {
      const query: TransactionListQuery = {
        page: 0,
        size: 20,
        get sort() {
          return this === query ? "occurredAt,asc" : "occurredAt,desc";
        },
      };
      return query;
    }
    const baseline = Object.entries(buildQueryValues("transaction-list", identitySortQuery()) ?? {});
    expect(baseline).toEqual([
      ["page", "0"],
      ["size", "20"],
      ["sort", "occurredAt,asc"],
    ]);

    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(listBody(), { headers: { "X-Trace-Id": TRACE_ID } }));

    const result = await fetchTransactionList(client, identitySortQuery());

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentUrl()).toBe(`${BASE}/api/v1/transactions?page=0&size=20&sort=occurredAt%2Casc`);
    expect([...new URL(sentUrl()).searchParams]).toEqual(baseline);
    expect(result.data.page.number).toBe(0);
    expect(result.data.page.size).toBe(20);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("keeps a preceding accessor's side effect on this.page in the existing evaluation order", async () => {
    // Issue #291 accessor 의미 보존 반례 B. 기존 `buildQueryValues()`는 contract 순서대로 transactionType을
    // page보다 먼저 읽는다. transactionType getter가 원본 receiver의 `this.page`를 0에서 2로 바꾸므로 이후
    // page 직렬화는 2를 쓴다. 보정 경로 URL도 기존 경로와 같아야 하고, 응답 number 2가 정상 성공해야
    // validator 기대값도 URL에 실제 사용된 page 2와 같다.
    function sideEffectQuery(): {
      page: number;
      readonly size: number;
      readonly transactionType: TransactionType;
    } {
      return {
        page: 0,
        size: 20,
        get transactionType(): TransactionType {
          this.page = 2;
          return "ACCOUNT_TRANSFER";
        },
      };
    }
    const baselineQuery = sideEffectQuery();
    const baseline = Object.entries(buildQueryValues("transaction-list", baselineQuery) ?? {});
    expect(baseline).toEqual([
      ["transactionType", "ACCOUNT_TRANSFER"],
      ["page", "2"],
      ["size", "20"],
    ]);
    expect(baselineQuery.page).toBe(2);

    const client = signedIn();
    const body = listBody([listItem()], {
      number: 2,
      size: 20,
      totalElements: 41,
      totalPages: 3,
      first: false,
      last: true,
    });
    expect(isTransactionListPage(body)).toBe(true);
    mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));
    const query = sideEffectQuery();

    const result = await fetchTransactionList(client, query);

    expect(query.page).toBe(2);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentUrl()).toBe(
      `${BASE}/api/v1/transactions?transactionType=ACCOUNT_TRANSFER&page=2&size=20`,
    );
    expect([...new URL(sentUrl()).searchParams]).toEqual(baseline);
    expect(result.data.page.number).toBe(2);
    expect(result.data.page.size).toBe(20);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("keeps an accessor that reads private storage keyed by the caller's query", async () => {
    // Issue #291 accessor 의미 보존 반례 C. Object.prototype을 가진 허용 query의 accountRef getter가 원본 query
    // identity를 key로 WeakMap private storage를 읽는다. private field brand check처럼 다른 receiver로
    // 평가되면 TypeError를 던진다. 기존 경로와 보정 경로 모두 같은 URL로 성공해야 한다.
    const privateRefs = new WeakMap<object, string>();
    function privateStorageQuery(): TransactionListQuery {
      const query: TransactionListQuery = {
        get accountRef() {
          const value = privateRefs.get(this);
          if (value === undefined) {
            throw new TypeError("Cannot read private member from an object whose class did not declare it");
          }
          return value;
        },
        page: 0,
        size: 20,
      };
      privateRefs.set(query, "acct_ref_demo_s91c");
      return query;
    }
    const baselineQuery = privateStorageQuery();
    expect(Object.getPrototypeOf(baselineQuery)).toBe(Object.prototype);
    const baseline = Object.entries(buildQueryValues("transaction-list", baselineQuery) ?? {});
    expect(baseline).toEqual([
      ["accountRef", "acct_ref_demo_s91c"],
      ["page", "0"],
      ["size", "20"],
    ]);

    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(listBody(), { headers: { "X-Trace-Id": TRACE_ID } }));

    const result = await fetchTransactionList(client, privateStorageQuery());

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentUrl()).toBe(`${BASE}/api/v1/transactions?accountRef=acct_ref_demo_s91c&page=0&size=20`);
    expect([...new URL(sentUrl()).searchParams]).toEqual(baseline);
    expect(result.data.content).toHaveLength(1);
    expect(result.traceId).toBe(TRACE_ID);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("does not evaluate page or size getters earlier than the existing validation on a refused query", async () => {
    // Issue #291 invalid query 회귀. 기존 `buildQueryValues()`는 배열·prototype·symbol key·허용되지 않은 key를
    // 값 조회 전에 거부하고, contract 순서상 앞선 값이 거부되면 뒤의 page·size를 읽지 않는다. 같은 모양의
    // query를 기존 경로와 보정 경로에 각각 넘겨 page·size getter 평가 횟수가 기존과 같은지 확인한다.
    interface Reads {
      page: number;
      size: number;
    }
    function countingPagination(reads: Reads, page: unknown): PropertyDescriptorMap {
      return {
        page: {
          enumerable: true,
          get: () => {
            reads.page += 1;
            return page;
          },
        },
        size: {
          enumerable: true,
          get: () => {
            reads.size += 1;
            return 20;
          },
        },
      };
    }
    const rows: ReadonlyArray<readonly [string, (reads: Reads) => object, number, number]> = [
      ["배열", (reads) => Object.defineProperties([], countingPagination(reads, 0)), 0, 0],
      [
        "custom prototype",
        (reads) => Object.defineProperties(Object.create({ inherited: 1 }), countingPagination(reads, 0)),
        0,
        0,
      ],
      [
        "symbol key",
        (reads) => Object.defineProperties({ [Symbol("marker")]: 1 }, countingPagination(reads, 0)),
        0,
        0,
      ],
      [
        "허용되지 않은 key",
        (reads) => Object.defineProperties({ unknownFilter: "1" }, countingPagination(reads, 0)),
        0,
        0,
      ],
      [
        "contract 순서상 앞선 filter 거부",
        (reads) =>
          Object.defineProperties({ transactionType: "UNKNOWN_TYPE" }, countingPagination(reads, 0)),
        0,
        0,
      ],
      ["page 거부 후 size 미평가", (reads) => Object.defineProperties({}, countingPagination(reads, -1)), 1, 0],
    ];

    for (const [name, build, pageReads, sizeReads] of rows) {
      const baselineReads: Reads = { page: 0, size: 0 };
      expect(() => buildQueryValues("transaction-list", build(baselineReads)), name).toThrow(
        RequestNotAllowedError,
      );
      expect(baselineReads, name).toEqual({ page: pageReads, size: sizeReads });

      const reads: Reads = { page: 0, size: 0 };
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(listBody()));

      await expect(fetchTransactionList(client, build(reads)), name).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );

      expect(reads, name).toEqual({ page: pageReads, size: sizeReads });
      expect(client.calls.authorizeRequest, name).toBe(0);
      expect(vi.mocked(fetch), name).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
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

  it("refuses a detail success whose transaction id is not exactly the requested one", async () => {
    // 요청 path의 transactionId와 응답 transaction.transactionId의 결합 검증 (Issue #287).
    // 세 번째 값은 형식 validator만의 판정이다. 다른 canonical UUID v4 행만 형식 검증을 통과하므로
    // binding 검증만 거부할 수 있는 반례이고, 나머지 행은 정규화 없이 기존 형식 계약이 거부한다.
    const OTHER_TRANSACTION_ID = "3a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d";
    const rows: ReadonlyArray<readonly [string, string, boolean]> = [
      ["요청과 다른 canonical UUID v4", OTHER_TRANSACTION_ID, true],
      ["대문자 UUID", TRANSACTION_ID.toUpperCase(), false],
      ["하이픈 없는 UUID", "2f4c0a4e8a9d4c2f9a1b7d6e5f430001", false],
      ["UUID v4가 아닌 값", "2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001", false],
      ["앞 공백", ` ${TRANSACTION_ID}`, false],
      ["뒤 공백", `${TRANSACTION_ID} `, false],
      ["내부 공백", "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f43 0001", false],
    ];

    for (const [name, responseTransactionId, formatValid] of rows) {
      const body = detailBody({ transactionId: responseTransactionId });
      expect(isTransactionDetailEnvelope(body), name).toBe(formatValid);

      const client = signedIn();
      // header trace도 body와 같은 값으로 실어 두 trace 경로 모두 오류에 반사되지 않는지 확인한다.
      mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

      const error = await fetchTransactionDetail(client, TRANSACTION_ID).catch(
        (thrown: unknown) => thrown,
      );

      expect(error, name).toBeInstanceOf(InvalidResponseError);
      expect(error, name).toMatchObject({
        name: "InvalidResponseError",
        message: "Received an unexpected response shape.",
      });
      expect(vi.mocked(fetch), name).toHaveBeenCalledTimes(1);
      const request = vi.mocked(fetch).mock.calls[0][0] as Request;
      expect(request.url, name).toBe(`${BASE}/api/v1/transactions/${TRANSACTION_ID}`);
      expect(request.method, name).toBe("GET");
      expect(new URL(request.url).search, name).toBe("");
      expect(client.calls.invalidateIfCurrent, name).toBe(0);
      expect(client.calls.notified, name).toBe(0);

      // 오류의 문자열·JSON 표현에 요청·응답 UUID, trace ID, raw body가 남지 않는다.
      const disclosed = `${String(error)} ${JSON.stringify(error)}`;
      for (const secret of [TRANSACTION_ID, responseTransactionId, TRACE_ID, JSON.stringify(body)]) {
        expect(disclosed, name).not.toContain(secret);
      }
      expect(disclosed, name).not.toMatch(/2f4c0a4e|3a1b2c3d|7d6e5f43|9e0f1a2b|trace|_ref_demo_/i);
      vi.unstubAllGlobals();
    }
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
