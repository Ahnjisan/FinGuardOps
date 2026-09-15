import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthSession } from "../auth/authClient";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import {
  jsonResponse,
  mockFetchOkWithControlledJson,
  mockFetchOnce,
  mockFetchRejectOnce,
} from "../test/mockFetch";
import {
  ForbiddenError,
  HttpError,
  InvalidResponseError,
  NetworkError,
  RequestNotAllowedError,
  UnauthorizedError,
} from "./errors";
import { buildQueryValues } from "./pagination";
import {
  buildCaseAssigneeChangeBody,
  buildCaseStatusChangeBody,
  changeCaseAssignee,
  changeCaseStatus,
  createCaseResolution,
  fetchCaseDetail,
  fetchCaseList,
  isCaseDetailEnvelope,
  isCaseListPage,
  isCaseMutation,
  type CaseAssigneeChangeRequest,
  type CaseDetail,
  type CaseListQuery,
  type CaseListSort,
  type CaseResolutionRequest,
  type CaseStatus,
  type CaseStatusChangeRequest,
} from "./caseApi";

const BASE = "http://localhost:8080";
const CASE_ID = "5c671624-8714-4bd7-871a-a9445e6f453e";
const ASSIGNEE_ID = "2a000000-0000-4000-9000-000000000002";
const CURRENT_ASSIGNEE_ID = "3b000000-0000-4000-a000-000000000003";
const TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const TRACE_ID = "trace_demo_case_list_01";

const SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: ["FDS_ANALYST"],
};

function signedIn(): FakeAuthClient {
  return createFakeAuthClient({ initialSession: SESSION });
}

function listItem(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    caseId: CASE_ID,
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: "analyst_ref_demo_07",
    relatedTransactionCount: 3,
    createdAt: "2026-07-24T01:15:33Z",
    lastChangedAt: "2026-07-24T02:05:10Z",
    ...overrides,
  };
}

function detailItem(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    caseId: CASE_ID,
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: "analyst_ref_demo_07",
    relatedTransactionCount: 3,
    createdAt: "2026-07-24T01:15:33Z",
    reviewStartedAt: "2026-07-24T01:25:00Z",
    closedAt: null,
    lastChangedAt: "2026-07-24T02:05:10Z",
    concurrencyVersion: 4,
    ...overrides,
  };
}

function mutation(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    caseId: CASE_ID,
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: ASSIGNEE_ID,
    reviewStartedAt: "2026-07-24T01:25:00Z",
    closedAt: null,
    lastChangedAt: "2026-07-24T01:25:00Z",
    concurrencyVersion: 1,
    traceId: TRACE_ID,
    ...overrides,
  };
}

function workflowDetail(overrides: Partial<CaseDetail> = {}): CaseDetail {
  return {
    caseId: CASE_ID,
    caseStatus: "IN_REVIEW",
    finalDisposition: null,
    assigneeRef: CURRENT_ASSIGNEE_ID,
    relatedTransactionCount: 3,
    createdAt: "2026-07-24T01:15:33Z",
    reviewStartedAt: "2026-07-24T01:25:00Z",
    closedAt: null,
    lastChangedAt: "2026-07-24T02:05:10Z",
    concurrencyVersion: 5,
    ...overrides,
  };
}

const OPEN_WORKFLOW_DETAIL = workflowDetail({
  caseStatus: "OPEN",
  assigneeRef: null,
  reviewStartedAt: null,
  concurrencyVersion: 0,
});

const REVIEW_WORKFLOW_DETAIL = workflowDetail();

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

function sentRequest(index = 0): Request {
  return vi.mocked(fetch).mock.calls[index][0] as Request;
}

async function sentBody(): Promise<unknown> {
  return JSON.parse(await sentRequest().clone().text()) as unknown;
}

const VALID_STATUS_CHANGE: CaseStatusChangeRequest = {
  targetStatus: "IN_REVIEW",
  assigneeRef: ASSIGNEE_ID,
  reasonCode: "CASE_REVIEW_STARTED",
  expectedVersion: 0,
};

const VALID_ASSIGNEE_CHANGE: CaseAssigneeChangeRequest = {
  assigneeRef: ASSIGNEE_ID,
  reasonCode: "CASE_ASSIGNEE_CHANGED",
  expectedVersion: 5,
};

const VALID_RESOLUTION: CaseResolutionRequest = {
  finalDisposition: "CONFIRMED_FRAUD",
  reasonCode: "CASE_RESOLUTION_COMPLETED",
  expectedVersion: 6,
};

/** 종결 가능한 IN_REVIEW baseline: 담당자·조사 시작 시각이 있고 판정·종결 시각은 아직 없다. */
const RESOLUTION_BASELINE = workflowDetail({ concurrencyVersion: 6 });

/** `RESOLUTION_BASELINE`과 `VALID_RESOLUTION`에 정확히 결합되는 CLOSED successor. */
function resolutionMutation(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return mutation({
    caseStatus: "CLOSED",
    finalDisposition: "CONFIRMED_FRAUD",
    assigneeRef: CURRENT_ASSIGNEE_ID,
    reviewStartedAt: "2026-07-24T01:25:00Z",
    closedAt: "2026-07-24T03:10:00.123456Z",
    lastChangedAt: "2026-07-24T03:10:00.123456Z",
    concurrencyVersion: 7,
    ...overrides,
  });
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", BASE);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("fetchCaseList — query", () => {
  it("encodes every filter into the one canonical query", async () => {
    mockFetchOnce(async () => jsonResponse(listBody()));

    await fetchCaseList(signedIn(), {
      sort: "lastChangedAt,asc",
      size: 20,
      page: 0,
      transactionId: TRANSACTION_ID,
      lastChangedAtTo: "2026-07-25T00:00:00Z",
      lastChangedAtFrom: "2026-07-24T00:00:00Z",
      createdAtTo: "2026-07-25T00:00:00Z",
      createdAtFrom: "2026-07-24T00:00:00Z",
      assigneeRef: "analyst_ref_demo_07",
      finalDisposition: "NORMAL",
      caseStatus: "IN_REVIEW",
    });

    expect(sentRequest().url).toBe(
      `${BASE}/api/v1/cases` +
        "?caseStatus=IN_REVIEW&finalDisposition=NORMAL&assigneeRef=analyst_ref_demo_07" +
        "&createdAtFrom=2026-07-24T00%3A00%3A00Z&createdAtTo=2026-07-25T00%3A00%3A00Z" +
        "&lastChangedAtFrom=2026-07-24T00%3A00%3A00Z&lastChangedAtTo=2026-07-25T00%3A00%3A00Z" +
        `&transactionId=${TRANSACTION_ID}&page=0&size=20&sort=lastChangedAt%2Casc`,
    );
  });

  it("refuses a malformed filter with zero credential lookups and zero fetches", async () => {
    const rejected: readonly unknown[] = [
      { page: -1 },
      { page: 1.5 },
      { page: Number.MAX_SAFE_INTEGER + 1 },
      { size: 0 },
      { size: 101 },
      { sort: "lastChangedAt,DESC" },
      { sort: "createdAt,desc" },
      { caseStatus: "in_review" },
      { caseStatus: "UNKNOWN" },
      { finalDisposition: "normal" },
      { transactionId: TRANSACTION_ID.toUpperCase() },
      { transactionId: "2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001" },
      { createdAtFrom: "2026-07-24T00:00:00+09:00" },
      { assigneeRef: "" },
      { assigneeRef: "ref " },
      { assigneeRef: " ref" },
      { assigneeRef: "a".repeat(129) },
      { unknownFilter: "1" },
      { occurredAtFrom: "2026-07-24T00:00:00Z" },
    ];

    for (const query of rejected) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(listBody()));

      await expect(fetchCaseList(client, query as CaseListQuery)).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("keeps its own assignee bounds, which the transaction references do not share", async () => {
    // `FraudCaseQueryValidator.validateAssigneeRef` adds a 128-character bound
    // and a trim comparison that `TransactionQueryValidator` does not have.
    for (const assigneeRef of [" acct ", "acct ", "a".repeat(129)]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(listBody()));
      await expect(fetchCaseList(client, { assigneeRef })).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }

    // 128 is the bound, so it is a filter; 129 is not.
    mockFetchOnce(async () => jsonResponse(listBody()));
    await fetchCaseList(signedIn(), { assigneeRef: "a".repeat(128) });
    expect(new URL(sentRequest().url).searchParams.get("assigneeRef")).toBe("a".repeat(128));
    vi.unstubAllGlobals();

    const refused = signedIn();
    mockFetchOnce(async () => jsonResponse(listBody()));
    await expect(fetchCaseList(refused, { assigneeRef: "a".repeat(129) })).rejects.toBeInstanceOf(
      RequestNotAllowedError,
    );
    expect(refused.calls.authorizeRequest).toBe(0);
    expect(vi.mocked(fetch)).not.toHaveBeenCalled();
  });

  it("refuses either inverted range independently", async () => {
    for (const query of [
      { createdAtFrom: "2026-07-25T00:00:00Z", createdAtTo: "2026-07-24T00:00:00Z" },
      { lastChangedAtFrom: "2026-07-25T00:00:00Z", lastChangedAtTo: "2026-07-24T00:00:00Z" },
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(listBody()));
      await expect(fetchCaseList(client, query)).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }
  });
});

describe("case read responses", () => {
  it("accepts the documented list and detail shapes", async () => {
    expect(isCaseListPage(listBody())).toBe(true);
    expect(isCaseListPage(listBody([]))).toBe(true);
    expect(isCaseDetailEnvelope({ case: detailItem(), traceId: TRACE_ID })).toBe(true);
  });

  it("accepts an unassigned, unresolved and never-reviewed case", async () => {
    expect(
      isCaseListPage(listBody([listItem({ assigneeRef: null, finalDisposition: null })])),
    ).toBe(true);
    expect(
      isCaseDetailEnvelope({
        case: detailItem({ assigneeRef: null, reviewStartedAt: null, closedAt: null }),
        traceId: TRACE_ID,
      }),
    ).toBe(true);
  });

  it("refuses a missing or unknown key", async () => {
    const complete = listItem();
    for (const key of Object.keys(complete)) {
      const missing = { ...complete };
      delete missing[key];
      expect(isCaseListPage(listBody([missing]))).toBe(false);
    }
    expect(isCaseListPage(listBody([{ ...complete, riskLevel: "HIGH" }]))).toBe(false);

    const detail = detailItem();
    for (const key of Object.keys(detail)) {
      const missing = { ...detail };
      delete missing[key];
      expect(isCaseDetailEnvelope({ case: missing, traceId: TRACE_ID })).toBe(false);
    }
    expect(isCaseDetailEnvelope({ case: { ...detail, noteCount: 2 }, traceId: TRACE_ID })).toBe(
      false,
    );
  });

  it("requires the detail envelope to name the field `case`", async () => {
    expect(isCaseDetailEnvelope({ fraudCase: detailItem(), traceId: TRACE_ID })).toBe(false);
    expect(isCaseDetailEnvelope({ case: detailItem() })).toBe(false);
    expect(isCaseDetailEnvelope({ case: detailItem(), traceId: TRACE_ID, extra: 1 })).toBe(false);
  });

  it("refuses a malformed enum, uuid, instant or long", async () => {
    for (const override of [
      { caseStatus: "in_review" },
      { caseStatus: "RESOLVED" },
      { caseStatus: null },
      { finalDisposition: "confirmed_fraud" },
      { caseId: CASE_ID.toUpperCase() },
      { caseId: "not-a-uuid" },
      { createdAt: "2026-07-24T01:15:33+09:00" },
      { lastChangedAt: null },
      { relatedTransactionCount: -1 },
      { relatedTransactionCount: 1.5 },
      { relatedTransactionCount: "3" },
      { relatedTransactionCount: Number.MAX_SAFE_INTEGER + 1 },
      { assigneeRef: " padded" },
      { assigneeRef: 7 },
    ]) {
      expect(isCaseListPage(listBody([listItem(override)]))).toBe(false);
    }

    for (const override of [
      { concurrencyVersion: -1 },
      { concurrencyVersion: 1.5 },
      { concurrencyVersion: "4" },
      { concurrencyVersion: Number.MAX_SAFE_INTEGER + 1 },
      { reviewStartedAt: "2026-07-24" },
      { closedAt: "2026-07-24T01:25:00" },
    ]) {
      expect(isCaseDetailEnvelope({ case: detailItem(override), traceId: TRACE_ID })).toBe(false);
    }
  });

  it("rejects the whole page when one item is malformed", async () => {
    const body = listBody([listItem(), listItem({ caseStatus: "UNKNOWN" })], {
      totalElements: 2,
    });
    expect(isCaseListPage(body)).toBe(false);

    mockFetchOnce(async () => jsonResponse(body));
    await expect(fetchCaseList(signedIn())).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("refuses page metadata that does not add up", async () => {
    for (const pageOverrides of [
      { totalElements: 45, totalPages: 2 },
      { totalElements: 1, totalPages: 1, first: false },
      { totalElements: 1, totalPages: 1, last: false },
      { number: 1, totalElements: 1, totalPages: 1, first: true },
    ]) {
      expect(isCaseListPage(listBody([listItem()], pageOverrides))).toBe(false);
    }
  });

  it("refuses a detail success whose case id is not exactly the requested one", async () => {
    // 요청 path의 caseId와 응답 case.caseId의 결합 검증 (Issue #289).
    // 세 번째 값은 형식 validator만의 판정이다. 다른 canonical UUID v4 행만 형식 검증을 통과하므로
    // binding 검증만 거부할 수 있는 반례이고, 나머지 행은 정규화 없이 기존 형식 계약이 거부하는 회귀 반례다.
    const OTHER_CASE_ID = "7e1d2c3b-4a5f-4b6c-8d7e-9f0a1b2c3d4e";
    const rows: ReadonlyArray<readonly [string, string, boolean]> = [
      ["요청과 다른 canonical UUID v4", OTHER_CASE_ID, true],
      ["대문자 UUID", CASE_ID.toUpperCase(), false],
      ["하이픈 없는 UUID", "5c67162487144bd7871aa9445e6f453e", false],
      ["UUID v4가 아닌 값", "5c671624-8714-1bd7-871a-a9445e6f453e", false],
      ["앞 공백", ` ${CASE_ID}`, false],
      ["뒤 공백", `${CASE_ID} `, false],
      ["내부 공백", "5c671624-8714-4bd7-871a-a9445e6f 453e", false],
    ];

    for (const [name, responseCaseId, formatValid] of rows) {
      const body = { case: detailItem({ caseId: responseCaseId }), traceId: TRACE_ID };
      expect(isCaseDetailEnvelope(body), name).toBe(formatValid);

      const client = signedIn();
      // header trace도 body와 같은 값으로 실어 두 trace 경로 모두 오류에 반사되지 않는지 확인한다.
      mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

      const error = await fetchCaseDetail(client, CASE_ID).catch((thrown: unknown) => thrown);

      expect(error, name).toBeInstanceOf(InvalidResponseError);
      expect(error, name).toMatchObject({
        name: "InvalidResponseError",
        message: "Received an unexpected response shape.",
      });
      expect(vi.mocked(fetch), name).toHaveBeenCalledTimes(1);
      const request = sentRequest();
      expect(request.url, name).toBe(`${BASE}/api/v1/cases/${CASE_ID}`);
      expect(request.method, name).toBe("GET");
      expect(new URL(request.url).search, name).toBe("");
      expect(client.calls.invalidateIfCurrent, name).toBe(0);
      expect(client.calls.notified, name).toBe(0);

      // 오류의 문자열·JSON 표현에 요청·응답 UUID, trace ID, raw body가 남지 않는다.
      const disclosed = `${String(error)} ${JSON.stringify(error)}`;
      for (const secret of [CASE_ID, responseCaseId, TRACE_ID, JSON.stringify(body)]) {
        expect(disclosed, name).not.toContain(secret);
      }
      expect(disclosed, name).not.toMatch(/5c671624|7e1d2c3b|a9445e6f|9f0a1b2c|trace|_ref_demo_/i);
      vi.unstubAllGlobals();
    }
  });
});

describe("fetchCaseList — pagination binding (Issue #299)", () => {
  /** 한 page의 metadata. 모든 행은 `isConsistentPageMetadata()` 산술을 만족하도록 직접 적는다. */
  type PageRow = Record<string, unknown>;

  it("refuses a well-formed page whose number or size is not the requested effective pagination", async () => {
    // 요청 effective page·size와 응답 page.number·page.size의 결합 검증 (Issue #299).
    // page·size를 생략하면 URL에 싣지 않고 Backend 기본값 page=0·size=20을 기대한다. 모든 body는 기존 형식·
    // 산술 validator `isCaseListPage()`를 통과하므로 binding 검증만 거부할 수 있는 반례이다.
    const rows: ReadonlyArray<
      readonly [string, CaseListQuery | undefined, string, boolean, boolean, PageRow]
    > = [
      [
        "명시적 page mismatch",
        { page: 1, size: 20 },
        "?page=1&size=20",
        true,
        true,
        { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true },
      ],
      [
        "명시적 size mismatch",
        { page: 0, size: 50 },
        "?page=0&size=50",
        true,
        true,
        { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true },
      ],
      [
        "page 생략, 응답 number가 기본값 0과 다름",
        { size: 20 },
        "?size=20",
        false,
        true,
        { number: 1, size: 20, totalElements: 21, totalPages: 2, first: false, last: true },
      ],
      [
        "size 생략, 응답 size가 기본값 20과 다름",
        { page: 0 },
        "?page=0",
        true,
        false,
        { number: 0, size: 50, totalElements: 1, totalPages: 1, first: true, last: true },
      ],
      [
        "query 전체 생략, 응답 size가 기본값 20과 다름",
        undefined,
        "",
        false,
        false,
        { number: 0, size: 50, totalElements: 1, totalPages: 1, first: true, last: true },
      ],
    ];

    for (const [name, query, search, hasPage, hasSize, page] of rows) {
      const body = listBody([listItem()], page);
      expect(isCaseListPage(body), name).toBe(true);

      const client = signedIn();
      // header trace도 body와 같은 값으로 실어 두 trace 경로 모두 오류에 반사되지 않는지 확인한다.
      mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

      const error = await fetchCaseList(client, query).catch((thrown: unknown) => thrown);

      expect(error, name).toBeInstanceOf(InvalidResponseError);
      expect(Reflect.ownKeys(error as object).sort(), name).toEqual(["message", "name", "stack"]);
      expect(String(error), name).toBe(
        "InvalidResponseError: Received an unexpected response shape.",
      );
      expect(JSON.stringify(error), name).toBe('{"name":"InvalidResponseError"}');

      expect(vi.mocked(fetch), name).toHaveBeenCalledTimes(1);
      const request = sentRequest();
      expect(request.method, name).toBe("GET");
      expect(request.url, name).toBe(`${BASE}/api/v1/cases${search}`);
      const parsed = new URL(request.url);
      expect(parsed.searchParams.has("page"), name).toBe(hasPage);
      expect(parsed.searchParams.has("size"), name).toBe(hasSize);
      expect(client.calls.authorizeRequest, name).toBe(1);
      expect(client.calls.invalidateIfCurrent, name).toBe(0);
      expect(client.calls.notified, name).toBe(0);

      // raw body 전체, page metadata, trace·사건·담당자 marker가 오류 표현에 남지 않는다.
      const disclosed = `${String(error)} ${JSON.stringify(error)}`;
      for (const secret of [
        JSON.stringify(body),
        JSON.stringify(body.page),
        TRACE_ID,
        CASE_ID,
        "analyst_ref_demo_07",
      ]) {
        expect(disclosed, name).not.toContain(secret);
      }
      expect(disclosed, name).not.toMatch(/trace|_ref_demo_|5c671624|totalElements/i);
      vi.unstubAllGlobals();
    }
  });

  it("accepts a partial last page, empty pages and the omitted defaults when number and size match", async () => {
    // Issue #299 green 회귀: production 수정 전에도 통과하는 의도된 회귀 테스트이다. 응답 number·size가 요청
    // effective 값과 같으면 content가 size보다 적거나 비어 있어도 거부하지 않는다.
    const rows: ReadonlyArray<
      readonly [string, CaseListQuery | undefined, string, number, number, number, PageRow]
    > = [
      [
        "마지막 페이지의 일부 content",
        { page: 2, size: 20 },
        "?page=2&size=20",
        3,
        2,
        20,
        { number: 2, size: 20, totalElements: 43, totalPages: 3, first: false, last: true },
      ],
      [
        "totalPages를 초과한 빈 page",
        { page: 5, size: 20 },
        "?page=5&size=20",
        0,
        5,
        20,
        { number: 5, size: 20, totalElements: 43, totalPages: 3, first: false, last: true },
      ],
      [
        "전체 결과 0건인 빈 page",
        { page: 0, size: 50 },
        "?page=0&size=50",
        0,
        0,
        50,
        { number: 0, size: 50, totalElements: 0, totalPages: 0, first: true, last: true },
      ],
      [
        "query 생략과 Backend 기본값 응답",
        undefined,
        "",
        1,
        0,
        20,
        { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true },
      ],
    ];

    for (const [name, query, search, contentLength, number, size, page] of rows) {
      const body = listBody(
        Array.from({ length: contentLength }, () => listItem()),
        page,
      );
      expect(isCaseListPage(body), name).toBe(true);

      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

      const result = await fetchCaseList(client, query);

      expect(vi.mocked(fetch), name).toHaveBeenCalledTimes(1);
      expect(sentRequest().url, name).toBe(`${BASE}/api/v1/cases${search}`);
      expect(result.data.content, name).toHaveLength(contentLength);
      expect(result.data.page, name).toStrictEqual(page);
      expect(result.data.page.number, name).toBe(number);
      expect(result.data.page.size, name).toBe(size);
      expect(result.traceId, name).toBe(TRACE_ID);
      expect(client.calls.authorizeRequest, name).toBe(1);
      expect(client.calls.invalidateIfCurrent, name).toBe(0);
      vi.unstubAllGlobals();
    }
  });

  it("reads page and size once and binds both the URL and the response to that one snapshot", async () => {
    // Issue #299 A1. page·size getter는 첫 평가에서만 1·20을 주고 다시 평가되면 다른 유효 값 3·50을 준다.
    // 원본을 다시 읽는 구현은 평가 횟수가 2가 되거나 URL·기대값이 갈라진다. getter마다 평가 시점의
    // credential 조회 횟수를 기록해 읽기가 credential 조회 전에 끝났는지도 확인한다.
    const client = signedIn();
    const pageReads: number[] = [];
    const sizeReads: number[] = [];
    const query: CaseListQuery = {
      caseStatus: "IN_REVIEW",
      get page() {
        pageReads.push(client.calls.authorizeRequest);
        return pageReads.length === 1 ? 1 : 3;
      },
      get size() {
        sizeReads.push(client.calls.authorizeRequest);
        return sizeReads.length === 1 ? 20 : 50;
      },
      sort: "lastChangedAt,desc",
    };
    const body = listBody([listItem()], {
      number: 1,
      size: 20,
      totalElements: 21,
      totalPages: 2,
      first: false,
      last: true,
    });
    expect(isCaseListPage(body)).toBe(true);
    mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

    const result = await fetchCaseList(client, query);

    expect(pageReads).toEqual([0]);
    expect(sizeReads).toEqual([0]);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentRequest().url).toBe(
      `${BASE}/api/v1/cases?caseStatus=IN_REVIEW&page=1&size=20&sort=lastChangedAt%2Cdesc`,
    );
    expect(result.data.page.number).toBe(1);
    expect(result.data.page.size).toBe(20);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });

  it("evaluates an identity-dependent sort accessor with the caller's query as the receiver", async () => {
    // Issue #299 A2. sort getter는 원본 query identity로 평가될 때만 lastChangedAt,asc를 주고, 다른 객체를
    // receiver로 평가되면 유효한 다른 값 lastChangedAt,desc를 준다. 기존 `buildQueryValues()` 결과를 기준값으로
    // 기록하고 보정 경로 URL도 정확히 같은 값·순서를 쓰는지 확인한다.
    function identitySortQuery(): CaseListQuery {
      const query: CaseListQuery = {
        page: 0,
        size: 20,
        get sort() {
          return this === query ? "lastChangedAt,asc" : "lastChangedAt,desc";
        },
      };
      return query;
    }
    const baseline = Object.entries(buildQueryValues("case-list", identitySortQuery()) ?? {});
    expect(baseline).toEqual([
      ["page", "0"],
      ["size", "20"],
      ["sort", "lastChangedAt,asc"],
    ]);

    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(listBody(), { headers: { "X-Trace-Id": TRACE_ID } }));

    const result = await fetchCaseList(client, identitySortQuery());

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases?page=0&size=20&sort=lastChangedAt%2Casc`);
    expect([...new URL(sentRequest().url).searchParams]).toEqual(baseline);
    expect(result.data.page.number).toBe(0);
    expect(result.data.page.size).toBe(20);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("keeps a preceding accessor's side effect on this.page in the existing evaluation order", async () => {
    // Issue #299 A3 선행. 기존 `buildQueryValues()`는 contract 순서대로 caseStatus를 page보다 먼저 읽는다.
    // caseStatus getter가 원본 receiver의 `this.page`를 0에서 2로 바꾸므로 이후 page 직렬화는 2를 쓴다. 보정
    // 경로 URL도 기존 경로와 같아야 하고, 응답 number 2가 정상 성공해야 validator 기대값도 URL의 page 2와 같다.
    function precedingQuery(): {
      page: number;
      readonly size: number;
      readonly caseStatus: CaseStatus;
    } {
      return {
        page: 0,
        size: 20,
        get caseStatus(): CaseStatus {
          this.page = 2;
          return "OPEN";
        },
      };
    }
    const baselineQuery = precedingQuery();
    const baseline = Object.entries(buildQueryValues("case-list", baselineQuery) ?? {});
    expect(baseline).toEqual([
      ["caseStatus", "OPEN"],
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
    expect(isCaseListPage(body)).toBe(true);
    mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));
    const query = precedingQuery();

    const result = await fetchCaseList(client, query);

    expect(query.page).toBe(2);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases?caseStatus=OPEN&page=2&size=20`);
    expect([...new URL(sentRequest().url).searchParams]).toEqual(baseline);
    expect(result.data.page.number).toBe(2);
    expect(result.data.page.size).toBe(20);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("binds a trailing accessor's side effect to the page value the URL already used", async () => {
    // Issue #299 A3 후행. sort getter는 contract 순서상 page·size 직렬화 뒤에 평가되며 원본 `this.page`를
    // 0에서 5로 바꾼다. URL은 기존과 같이 page=0을 쓰므로 validator 기대값도 첫 직접 조회 값 0이어야 한다.
    // 바뀐 원본 값 5를 담은 응답은 URL에 쓰이지 않은 좌표이므로 거부해야 한다.
    function trailingQuery(): {
      page: number;
      readonly size: number;
      readonly sort: CaseListSort;
    } {
      return {
        page: 0,
        size: 20,
        get sort(): CaseListSort {
          this.page = 5;
          return "lastChangedAt,desc";
        },
      };
    }
    const baselineQuery = trailingQuery();
    const baseline = Object.entries(buildQueryValues("case-list", baselineQuery) ?? {});
    expect(baseline).toEqual([
      ["page", "0"],
      ["size", "20"],
      ["sort", "lastChangedAt,desc"],
    ]);
    expect(baselineQuery.page).toBe(5);

    const accepted = signedIn();
    mockFetchOnce(async () => jsonResponse(listBody(), { headers: { "X-Trace-Id": TRACE_ID } }));
    const acceptedQuery = trailingQuery();

    const result = await fetchCaseList(accepted, acceptedQuery);

    expect(acceptedQuery.page).toBe(5);
    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases?page=0&size=20&sort=lastChangedAt%2Cdesc`);
    expect([...new URL(sentRequest().url).searchParams]).toEqual(baseline);
    expect(result.data.page.number).toBe(0);
    expect(accepted.calls.authorizeRequest).toBe(1);
    vi.unstubAllGlobals();

    const refused = signedIn();
    const mutatedPageBody = listBody([], {
      number: 5,
      size: 20,
      totalElements: 1,
      totalPages: 1,
      first: false,
      last: true,
    });
    expect(isCaseListPage(mutatedPageBody)).toBe(true);
    mockFetchOnce(async () =>
      jsonResponse(mutatedPageBody, { headers: { "X-Trace-Id": TRACE_ID } }),
    );
    const refusedQuery = trailingQuery();

    const error = await fetchCaseList(refused, refusedQuery).catch((thrown: unknown) => thrown);

    expect(error).toBeInstanceOf(InvalidResponseError);
    expect(JSON.stringify(error)).toBe('{"name":"InvalidResponseError"}');
    expect(refusedQuery.page).toBe(5);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases?page=0&size=20&sort=lastChangedAt%2Cdesc`);
    expect(refused.calls.authorizeRequest).toBe(1);
    expect(refused.calls.invalidateIfCurrent).toBe(0);
  });

  it("keeps an accessor that reads private storage keyed by the caller's query", async () => {
    // Issue #299 A4. Object.prototype을 가진 허용 query의 assigneeRef getter가 원본 query identity를 key로
    // WeakMap private storage를 읽는다. private field brand check처럼 다른 receiver로 평가되면 TypeError를
    // 던진다. 기존 경로와 보정 경로 모두 같은 URL로 성공해야 한다.
    const privateRefs = new WeakMap<object, string>();
    function privateStorageQuery(): CaseListQuery {
      const query: CaseListQuery = {
        get assigneeRef() {
          const value = privateRefs.get(this);
          if (value === undefined) {
            throw new TypeError("Cannot read private member from an object whose class did not declare it");
          }
          return value;
        },
        page: 0,
        size: 20,
      };
      privateRefs.set(query, "analyst_ref_demo_07");
      return query;
    }
    const baselineQuery = privateStorageQuery();
    expect(Object.getPrototypeOf(baselineQuery)).toBe(Object.prototype);
    const baseline = Object.entries(buildQueryValues("case-list", baselineQuery) ?? {});
    expect(baseline).toEqual([
      ["assigneeRef", "analyst_ref_demo_07"],
      ["page", "0"],
      ["size", "20"],
    ]);

    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(listBody(), { headers: { "X-Trace-Id": TRACE_ID } }));

    const result = await fetchCaseList(client, privateStorageQuery());

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases?assigneeRef=analyst_ref_demo_07&page=0&size=20`);
    expect([...new URL(sentRequest().url).searchParams]).toEqual(baseline);
    expect(result.data.content).toHaveLength(1);
    expect(result.traceId).toBe(TRACE_ID);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("does not evaluate page or size getters differently from the existing validation on a refused query", async () => {
    // Issue #299 A5. 기존 `buildQueryValues()`는 배열·prototype·symbol key·허용되지 않은 key를 값 조회 전에
    // 거부하고, contract 순서상 앞선 값이 거부되면 뒤의 page·size를 읽지 않는다. 같은 모양의 query를 기존
    // 경로와 보정 경로에 각각 넘겨 page·size getter 평가 횟수와 credential 조회 0회가 같은지 확인한다.
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
        (reads) => Object.defineProperties({ caseStatus: "UNKNOWN" }, countingPagination(reads, 0)),
        0,
        0,
      ],
      ["page 거부 후 size 미평가", (reads) => Object.defineProperties({}, countingPagination(reads, -1)), 1, 0],
      [
        "contract 순서상 뒤 sort 거부",
        (reads) => Object.defineProperties({ sort: "createdAt,desc" }, countingPagination(reads, 0)),
        1,
        1,
      ],
    ];

    for (const [name, build, pageReads, sizeReads] of rows) {
      const baselineReads: Reads = { page: 0, size: 0 };
      expect(() => buildQueryValues("case-list", build(baselineReads)), name).toThrow(
        RequestNotAllowedError,
      );
      expect(baselineReads, name).toEqual({ page: pageReads, size: sizeReads });

      const reads: Reads = { page: 0, size: 0 };
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(listBody()));

      await expect(fetchCaseList(client, build(reads)), name).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );

      expect(reads, name).toEqual({ page: pageReads, size: sizeReads });
      expect(client.calls.authorizeRequest, name).toBe(0);
      expect(vi.mocked(fetch), name).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });
});

describe("changeCaseStatus", () => {
  it("sends PATCH with a rebuilt body carrying exactly the contract fields", async () => {
    mockFetchOnce(async () => jsonResponse(mutation()));

    await changeCaseStatus(signedIn(), CASE_ID, VALID_STATUS_CHANGE, OPEN_WORKFLOW_DETAIL);

    const request = sentRequest();
    expect(request.method).toBe("PATCH");
    expect(request.url).toBe(`${BASE}/api/v1/cases/${CASE_ID}/status`);
    expect(new URL(request.url).search).toBe("");
    expect(request.headers.get("Content-Type")).toBe("application/json");
    expect(await sentBody()).toEqual({
      targetStatus: "IN_REVIEW",
      reasonCode: "CASE_REVIEW_STARTED",
      expectedVersion: 0,
      assigneeRef: ASSIGNEE_ID,
    });
  });

  it("omits assigneeRef entirely when the caller omits the key", async () => {
    mockFetchOnce(async () =>
      jsonResponse(
        mutation({
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: CURRENT_ASSIGNEE_ID,
          concurrencyVersion: 4,
        }),
      ),
    );

    await changeCaseStatus(signedIn(), CASE_ID, {
      targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
      expectedVersion: 3,
    }, workflowDetail({ concurrencyVersion: 3 }));

    const body = (await sentBody()) as Record<string, unknown>;
    expect(Object.keys(body).sort()).toEqual(["expectedVersion", "reasonCode", "targetStatus"]);
  });

  it("refuses an assigneeRef key on a transition that must not carry one", async () => {
    // `rejectStatusAssigneeCombination` refuses the key, not its value, so an
    // explicit null is refused exactly like a UUID would be.
    for (const request of [
      { targetStatus: "IN_REVIEW", assigneeRef: null, reasonCode: "CASE_REVIEW_RESUMED", expectedVersion: 3 },
      { targetStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID, reasonCode: "CASE_REVIEW_RESUMED", expectedVersion: 3 },
      {
        targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
        assigneeRef: null,
        reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
        expectedVersion: 3,
      },
      {
        targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
        assigneeRef: ASSIGNEE_ID,
        reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
        expectedVersion: 3,
      },
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(mutation()));
      await expect(
        changeCaseStatus(
          client,
          CASE_ID,
          request as unknown as CaseStatusChangeRequest,
          OPEN_WORKFLOW_DETAIL,
        ),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("drops nothing the caller sent and refuses everything it did not contract for", async () => {
    const rejected: readonly unknown[] = [
      { ...VALID_STATUS_CHANGE, actorId: ASSIGNEE_ID },
      { ...VALID_STATUS_CHANGE, actorType: "USER" },
      { ...VALID_STATUS_CHANGE, unknown: 1 },
      { targetStatus: "IN_REVIEW", reasonCode: "CASE_REVIEW_STARTED" },
      { targetStatus: "IN_REVIEW", expectedVersion: 0 },
      { reasonCode: "CASE_REVIEW_STARTED", expectedVersion: 0 },
      { ...VALID_STATUS_CHANGE, targetStatus: "CLOSED" },
      { ...VALID_STATUS_CHANGE, targetStatus: "OPEN" },
      { ...VALID_STATUS_CHANGE, targetStatus: "in_review" },
      { ...VALID_STATUS_CHANGE, reasonCode: "CASE_RESOLUTION_COMPLETED" },
      { ...VALID_STATUS_CHANGE, reasonCode: "CASE_ASSIGNEE_ASSIGNED" },
      { ...VALID_STATUS_CHANGE, expectedVersion: -1 },
      { ...VALID_STATUS_CHANGE, expectedVersion: 1.5 },
      { ...VALID_STATUS_CHANGE, expectedVersion: "0" },
      { ...VALID_STATUS_CHANGE, expectedVersion: Number.MAX_SAFE_INTEGER + 1 },
      { ...VALID_STATUS_CHANGE, assigneeRef: ASSIGNEE_ID.toUpperCase() },
      { ...VALID_STATUS_CHANGE, assigneeRef: "analyst_ref_demo_07" },
      { ...VALID_STATUS_CHANGE, assigneeRef: `${ASSIGNEE_ID} ` },
      Object.assign(Object.create({ targetStatus: "IN_REVIEW" }), {
        reasonCode: "CASE_REVIEW_STARTED",
        expectedVersion: 0,
      }),
      [],
      null,
      "IN_REVIEW",
    ];

    for (const request of rejected) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(mutation()));

      await expect(
        changeCaseStatus(
          client,
          CASE_ID,
          request as CaseStatusChangeRequest,
          OPEN_WORKFLOW_DETAIL,
        ),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("refuses a non-canonical case id before any credential lookup", async () => {
    for (const id of [CASE_ID.toUpperCase(), `${CASE_ID}/notes`, "not-a-uuid", ""]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(mutation()));
      await expect(
        changeCaseStatus(client, id, VALID_STATUS_CHANGE, OPEN_WORKFLOW_DETAIL),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }
  });

  it("returns a fresh nine-field projection that shares no top-level reference with the raw DTO", async () => {
    // 테스트 double의 response.json()은 JSON 재파싱 없이 테스트가 보관한 이 객체 참조를 그대로 돌려준다.
    const rawDto = mutation();
    const { resolveJson } = mockFetchOkWithControlledJson();
    resolveJson(rawDto);
    const expected = {
      caseId: CASE_ID,
      caseStatus: "IN_REVIEW",
      finalDisposition: null,
      assigneeRef: ASSIGNEE_ID,
      reviewStartedAt: "2026-07-24T01:25:00Z",
      closedAt: null,
      lastChangedAt: "2026-07-24T01:25:00Z",
      concurrencyVersion: 1,
      traceId: TRACE_ID,
    };

    const result = await changeCaseStatus(
      signedIn(),
      CASE_ID,
      VALID_STATUS_CHANGE,
      OPEN_WORKFLOW_DETAIL,
    );

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(Object.is(result.data, rawDto)).toBe(false);
    expect(result.data).not.toBe(rawDto);
    expect(Object.getPrototypeOf(result.data)).toBe(Object.prototype);
    expect(result.data).toStrictEqual(expected);
    expect(result.traceId).toBe(TRACE_ID);

    // 반환 뒤 raw DTO의 상태·version을 바꾸거나 unknown field를 붙여도 projection에는 전파되지 않는다.
    rawDto.caseStatus = "ADDITIONAL_INFORMATION_REQUIRED";
    rawDto.concurrencyVersion = 2;
    rawDto.actorId = ASSIGNEE_ID;
    expect(result.data).toStrictEqual(expected);
    expect(result.data).not.toHaveProperty("actorId");
  });

  it("still refuses a held raw DTO carrying an unknown field with the fixed InvalidResponseError", async () => {
    // 기존 exact-shape 검증이 이미 거부하는 green 회귀 테스트다. projection 도입 뒤에도 거부가 유지되어야 한다.
    const rawDto = { ...mutation(), actorId: ASSIGNEE_ID };
    const { resolveJson } = mockFetchOkWithControlledJson();
    resolveJson(rawDto);

    const error = await changeCaseStatus(
      signedIn(),
      CASE_ID,
      VALID_STATUS_CHANGE,
      OPEN_WORKFLOW_DETAIL,
    ).catch((thrown: unknown) => thrown);

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(error).toBeInstanceOf(InvalidResponseError);
    expect(error).toMatchObject({
      name: "InvalidResponseError",
      message: "Received an unexpected response shape.",
    });
    expect(`${String(error)} ${JSON.stringify(error)}`).not.toMatch(
      /5c671624|2a000000|3b000000|trace|IN_REVIEW|actorId|fake\.access\.token|Bearer|\/api\/v1|PATCH/i,
    );
  });
});

describe("changeCaseAssignee", () => {
  it("sends PATCH with exactly the three contract fields", async () => {
    mockFetchOnce(async () => jsonResponse(mutation({ concurrencyVersion: 6 })));

    await changeCaseAssignee(
      signedIn(),
      CASE_ID,
      VALID_ASSIGNEE_CHANGE,
      REVIEW_WORKFLOW_DETAIL,
    );

    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases/${CASE_ID}/assignee`);
    expect(sentRequest().method).toBe("PATCH");
    expect(await sentBody()).toEqual({
      assigneeRef: ASSIGNEE_ID,
      reasonCode: "CASE_ASSIGNEE_CHANGED",
      expectedVersion: 5,
    });
  });

  it("sends an explicit null to release the assignee", async () => {
    mockFetchOnce(async () =>
      jsonResponse(
        mutation({
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: null,
          concurrencyVersion: 6,
        }),
      ),
    );

    await changeCaseAssignee(signedIn(), CASE_ID, {
      assigneeRef: null,
      reasonCode: "CASE_ASSIGNEE_RELEASED",
      expectedVersion: 5,
    }, workflowDetail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }));

    expect(await sentBody()).toEqual({
      assigneeRef: null,
      reasonCode: "CASE_ASSIGNEE_RELEASED",
      expectedVersion: 5,
    });
  });

  it("refuses an omitted assigneeRef rather than treating it as a release", async () => {
    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(mutation()));

    await expect(
      changeCaseAssignee(client, CASE_ID, {
        reasonCode: "CASE_ASSIGNEE_RELEASED",
        expectedVersion: 5,
      } as CaseAssigneeChangeRequest, REVIEW_WORKFLOW_DETAIL),
    ).rejects.toBeInstanceOf(RequestNotAllowedError);
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("refuses a reason code that belongs to another endpoint", async () => {
    for (const reasonCode of [
      "CASE_REVIEW_STARTED",
      "CASE_RESOLUTION_COMPLETED",
      "CASE_INVESTIGATION_NOTE_ADDED",
      "case_assignee_changed",
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(mutation()));
      await expect(
        changeCaseAssignee(client, CASE_ID, {
          ...VALID_ASSIGNEE_CHANGE,
          reasonCode,
        } as CaseAssigneeChangeRequest, REVIEW_WORKFLOW_DETAIL),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }
  });

  it("returns a fresh nine-field projection that shares no top-level reference with the raw DTO", async () => {
    // 테스트 double의 response.json()은 JSON 재파싱 없이 테스트가 보관한 이 객체 참조를 그대로 돌려준다.
    const rawDto = mutation({ concurrencyVersion: 6 });
    const { resolveJson } = mockFetchOkWithControlledJson();
    resolveJson(rawDto);
    const expected = {
      caseId: CASE_ID,
      caseStatus: "IN_REVIEW",
      finalDisposition: null,
      assigneeRef: ASSIGNEE_ID,
      reviewStartedAt: "2026-07-24T01:25:00Z",
      closedAt: null,
      lastChangedAt: "2026-07-24T01:25:00Z",
      concurrencyVersion: 6,
      traceId: TRACE_ID,
    };

    const result = await changeCaseAssignee(
      signedIn(),
      CASE_ID,
      VALID_ASSIGNEE_CHANGE,
      REVIEW_WORKFLOW_DETAIL,
    );

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(Object.is(result.data, rawDto)).toBe(false);
    expect(result.data).not.toBe(rawDto);
    expect(Object.getPrototypeOf(result.data)).toBe(Object.prototype);
    expect(result.data).toStrictEqual(expected);
    expect(result.traceId).toBe(TRACE_ID);

    // 반환 뒤 raw DTO의 담당자·version을 바꾸거나 unknown field를 붙여도 projection에는 전파되지 않는다.
    rawDto.assigneeRef = CURRENT_ASSIGNEE_ID;
    rawDto.concurrencyVersion = 7;
    rawDto.actorId = ASSIGNEE_ID;
    expect(result.data).toStrictEqual(expected);
    expect(result.data).not.toHaveProperty("actorId");
  });

  it("still refuses a held raw DTO carrying an unknown field with the fixed InvalidResponseError", async () => {
    // 기존 exact-shape 검증이 이미 거부하는 green 회귀 테스트다. projection 도입 뒤에도 거부가 유지되어야 한다.
    const rawDto = { ...mutation({ concurrencyVersion: 6 }), actorId: ASSIGNEE_ID };
    const { resolveJson } = mockFetchOkWithControlledJson();
    resolveJson(rawDto);

    const error = await changeCaseAssignee(
      signedIn(),
      CASE_ID,
      VALID_ASSIGNEE_CHANGE,
      REVIEW_WORKFLOW_DETAIL,
    ).catch((thrown: unknown) => thrown);

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(error).toBeInstanceOf(InvalidResponseError);
    expect(error).toMatchObject({
      name: "InvalidResponseError",
      message: "Received an unexpected response shape.",
    });
    expect(`${String(error)} ${JSON.stringify(error)}`).not.toMatch(
      /5c671624|2a000000|3b000000|trace|IN_REVIEW|actorId|fake\.access\.token|Bearer|\/api\/v1|PATCH/i,
    );
  });
});

describe("case workflow request-response semantic binding", () => {
  it("binds all three status transitions to their submitted snapshot", async () => {
    const rows: ReadonlyArray<{
      readonly baseline: CaseDetail;
      readonly request: CaseStatusChangeRequest;
      readonly response: Record<string, unknown>;
    }> = [
      {
        baseline: OPEN_WORKFLOW_DETAIL,
        request: VALID_STATUS_CHANGE,
        response: mutation(),
      },
      {
        baseline: REVIEW_WORKFLOW_DETAIL,
        request: {
          targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
          expectedVersion: 5,
        },
        response: mutation({
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: CURRENT_ASSIGNEE_ID,
          concurrencyVersion: 6,
        }),
      },
      {
        baseline: workflowDetail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }),
        request: {
          targetStatus: "IN_REVIEW",
          reasonCode: "CASE_REVIEW_RESUMED",
          expectedVersion: 5,
        },
        response: mutation({ assigneeRef: CURRENT_ASSIGNEE_ID, concurrencyVersion: 6 }),
      },
    ];

    for (const row of rows) {
      mockFetchOnce(async () => jsonResponse(row.response));
      await expect(
        changeCaseStatus(signedIn(), CASE_ID, row.request, row.baseline),
      ).resolves.toBeDefined();
      vi.unstubAllGlobals();
    }
  });

  it("rejects a status success that does not preserve its request and workflow fields", async () => {
    const OTHER_CASE_ID = "6d782735-9825-4ce8-982b-b0556f705e4f";
    for (const overrides of [
      { caseId: OTHER_CASE_ID },
      { concurrencyVersion: 0 },
      { concurrencyVersion: 2 },
      { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" },
      { assigneeRef: CURRENT_ASSIGNEE_ID },
      { reviewStartedAt: null },
      { finalDisposition: "NORMAL" },
      { closedAt: "2026-07-24T01:25:00Z" },
    ]) {
      mockFetchOnce(async () => jsonResponse(mutation(overrides)));
      const error = await changeCaseStatus(
        signedIn(),
        CASE_ID,
        VALID_STATUS_CHANGE,
        OPEN_WORKFLOW_DETAIL,
      ).catch((thrown: unknown) => thrown);
      expect(error).toBeInstanceOf(InvalidResponseError);
      expect(error).toMatchObject({
        name: "InvalidResponseError",
        message: "Received an unexpected response shape.",
      });
      expect(JSON.stringify(error)).not.toMatch(/6d782735|3b000000|trace|IN_REVIEW/i);
      vi.unstubAllGlobals();
    }
  });

  it("binds assignment, reassignment and explicit release to the current status", async () => {
    const rows: ReadonlyArray<{
      readonly baseline: CaseDetail;
      readonly request: CaseAssigneeChangeRequest;
      readonly response: Record<string, unknown>;
    }> = [
      {
        baseline: REVIEW_WORKFLOW_DETAIL,
        request: VALID_ASSIGNEE_CHANGE,
        response: mutation({ concurrencyVersion: 6 }),
      },
      {
        baseline: workflowDetail({
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: null,
        }),
        request: {
          assigneeRef: ASSIGNEE_ID,
          reasonCode: "CASE_ASSIGNEE_ASSIGNED",
          expectedVersion: 5,
        },
        response: mutation({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", concurrencyVersion: 6 }),
      },
      {
        baseline: workflowDetail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }),
        request: VALID_ASSIGNEE_CHANGE,
        response: mutation({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", concurrencyVersion: 6 }),
      },
      {
        baseline: workflowDetail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }),
        request: {
          assigneeRef: null,
          reasonCode: "CASE_ASSIGNEE_RELEASED",
          expectedVersion: 5,
        },
        response: mutation({
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: null,
          concurrencyVersion: 6,
        }),
      },
    ];

    for (const row of rows) {
      mockFetchOnce(async () => jsonResponse(row.response));
      await expect(
        changeCaseAssignee(signedIn(), CASE_ID, row.request, row.baseline),
      ).resolves.toBeDefined();
      vi.unstubAllGlobals();
    }
  });

  it("rejects forbidden baseline states, stale versions, duplicate assignees and wrong reasons before credential lookup", async () => {
    const rows: ReadonlyArray<readonly [CaseDetail, CaseAssigneeChangeRequest]> = [
      [OPEN_WORKFLOW_DETAIL, VALID_ASSIGNEE_CHANGE],
      [workflowDetail({ caseStatus: "CLOSED", finalDisposition: "NORMAL", closedAt: "2026-07-24T03:00:00Z" }), VALID_ASSIGNEE_CHANGE],
      [REVIEW_WORKFLOW_DETAIL, { ...VALID_ASSIGNEE_CHANGE, expectedVersion: 4 }],
      [REVIEW_WORKFLOW_DETAIL, { ...VALID_ASSIGNEE_CHANGE, assigneeRef: CURRENT_ASSIGNEE_ID }],
      [
        workflowDetail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null }),
        { ...VALID_ASSIGNEE_CHANGE, reasonCode: "CASE_ASSIGNEE_CHANGED" },
      ],
      [
        workflowDetail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }),
        { assigneeRef: null, reasonCode: "CASE_ASSIGNEE_RELEASED", expectedVersion: 4 },
      ],
    ];

    for (const [baseline, request] of rows) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(mutation({ concurrencyVersion: 6 })));
      await expect(
        changeCaseAssignee(client, CASE_ID, request, baseline),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      expect(fetch).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("rejects assignee success drift in status, timestamp and closure fields", async () => {
    for (const overrides of [
      { concurrencyVersion: 5 },
      { concurrencyVersion: 7 },
      { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" },
      { assigneeRef: CURRENT_ASSIGNEE_ID },
      { reviewStartedAt: "2026-07-24T01:26:00Z" },
      { finalDisposition: "FALSE_POSITIVE" },
      { closedAt: "2026-07-24T03:00:00Z" },
    ]) {
      mockFetchOnce(async () => jsonResponse(mutation({ concurrencyVersion: 6, ...overrides })));
      await expect(
        changeCaseAssignee(
          signedIn(),
          CASE_ID,
          VALID_ASSIGNEE_CHANGE,
          REVIEW_WORKFLOW_DETAIL,
        ),
      ).rejects.toBeInstanceOf(InvalidResponseError);
      vi.unstubAllGlobals();
    }
  });

  it("rejects MAX_SAFE_INTEGER because the required successor is not safe", async () => {
    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(mutation()));
    await expect(
      changeCaseStatus(
        client,
        CASE_ID,
        { ...VALID_STATUS_CHANGE, expectedVersion: Number.MAX_SAFE_INTEGER },
        workflowDetail({
          caseStatus: "OPEN",
          assigneeRef: null,
          reviewStartedAt: null,
          concurrencyVersion: Number.MAX_SAFE_INTEGER,
        }),
      ),
    ).rejects.toBeInstanceOf(RequestNotAllowedError);
    expect(client.calls.authorizeRequest).toBe(0);
    expect(fetch).not.toHaveBeenCalled();
  });
});

/**
 * workflow PATCH의 마지막 optional options 객체.
 *
 * 기존 positional 인자(`signal` 포함)의 순서는 그대로이고, guard는 실제 authorized transport와
 * HTTP client를 거쳐 credential 발급 뒤 fetch 직전에 실행된다.
 */
describe("case workflow writes — final dispatch guard option", () => {
  function send(
    kind: "status" | "assignee" | "resolution",
    client: FakeAuthClient,
    assertDispatchAllowed: () => void,
  ) {
    switch (kind) {
      case "status":
        return changeCaseStatus(client, CASE_ID, VALID_STATUS_CHANGE, OPEN_WORKFLOW_DETAIL, undefined, {
          assertDispatchAllowed,
        });
      case "assignee":
        return changeCaseAssignee(
          client,
          CASE_ID,
          VALID_ASSIGNEE_CHANGE,
          REVIEW_WORKFLOW_DETAIL,
          undefined,
          { assertDispatchAllowed },
        );
      case "resolution":
        // Resolution POST도 status/assignee와 같은 positional 순서와 options 객체를 사용한다.
        return createCaseResolution(
          client,
          CASE_ID,
          VALID_RESOLUTION,
          RESOLUTION_BASELINE,
          undefined,
          { assertDispatchAllowed },
        );
    }
  }

  function successBody(kind: "status" | "assignee" | "resolution"): Record<string, unknown> {
    switch (kind) {
      case "status":
        return mutation();
      case "assignee":
        return mutation({ concurrencyVersion: 6 });
      case "resolution":
        return resolutionMutation();
    }
  }

  it.each(["status", "assignee", "resolution"] as const)(
    "forwards the named guard for a %s change and runs it after authorization, immediately before fetch",
    async (kind) => {
      const order: string[] = [];
      vi.stubGlobal(
        "fetch",
        vi.fn().mockImplementation(() => {
          order.push("fetch");
          return Promise.resolve(jsonResponse(successBody(kind)));
        }),
      );
      const client = signedIn();

      await send(kind, client, () => {
        order.push(`guard:authorized=${String(client.calls.authorizeRequest)}`);
        queueMicrotask(() => order.push("microtask"));
      });

      expect(order).toEqual(["guard:authorized=1", "fetch", "microtask"]);
    },
  );

  it.each(["status", "assignee", "resolution"] as const)(
    "sends no %s write and returns the guard's refusal unchanged",
    async (kind) => {
      mockFetchOnce(async () => jsonResponse(successBody(kind)));
      const client = signedIn();
      const refusal = new RequestNotAllowedError();

      const error = await send(kind, client, () => {
        throw refusal;
      }).catch((caught: unknown) => caught);

      expect(error).toBe(refusal);
      expect(client.calls.authorizeRequest).toBe(1);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      expect(client.calls.invalidateIfCurrent).toBe(0);
      expect(client.calls.notified).toBe(0);
    },
  );
});

describe("createCaseResolution", () => {
  it("sends one exact POST bound to an eligible IN_REVIEW baseline and expects 200, not 201", async () => {
    mockFetchOnce(async () => jsonResponse(resolutionMutation()));
    const client = signedIn();

    const result = await createCaseResolution(
      client,
      CASE_ID,
      VALID_RESOLUTION,
      RESOLUTION_BASELINE,
    );

    expect(sentRequest().method).toBe("POST");
    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases/${CASE_ID}/resolution`);
    expect(await sentBody()).toEqual({
      finalDisposition: "CONFIRMED_FRAUD",
      reasonCode: "CASE_RESOLUTION_COMPLETED",
      expectedVersion: 6,
    });
    expect(result.data.caseStatus).toBe("CLOSED");
    expect(result.data.finalDisposition).toBe("CONFIRMED_FRAUD");
    expect(result.data.concurrencyVersion).toBe(7);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
  });

  it.each(["NORMAL", "FALSE_POSITIVE", "CONFIRMED_FRAUD"] as const)(
    "binds a %s resolution to its own CLOSED successor",
    async (finalDisposition) => {
      mockFetchOnce(async () => jsonResponse(resolutionMutation({ finalDisposition })));
      const result = await createCaseResolution(
        signedIn(),
        CASE_ID,
        { ...VALID_RESOLUTION, finalDisposition },
        RESOLUTION_BASELINE,
      );
      expect(await sentBody()).toEqual({
        finalDisposition,
        reasonCode: "CASE_RESOLUTION_COMPLETED",
        expectedVersion: 6,
      });
      expect(result.data.finalDisposition).toBe(finalDisposition);
    },
  );

  it("refuses a 201 for a resolution", async () => {
    mockFetchOnce(async () => jsonResponse(resolutionMutation(), { status: 201 }));
    await expect(
      createCaseResolution(signedIn(), CASE_ID, VALID_RESOLUTION, RESOLUTION_BASELINE),
    ).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("accepts only CASE_RESOLUTION_COMPLETED and a known disposition", async () => {
    for (const request of [
      { ...VALID_RESOLUTION, reasonCode: "CASE_REVIEW_STARTED" },
      { ...VALID_RESOLUTION, reasonCode: "case_resolution_completed" },
      { ...VALID_RESOLUTION, finalDisposition: "confirmed_fraud" },
      { ...VALID_RESOLUTION, finalDisposition: "UNKNOWN" },
      { ...VALID_RESOLUTION, finalDisposition: null },
      { reasonCode: "CASE_RESOLUTION_COMPLETED", expectedVersion: 6 },
      { ...VALID_RESOLUTION, reason: "free text" },
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(resolutionMutation()));
      await expect(
        createCaseResolution(
          client,
          CASE_ID,
          request as CaseResolutionRequest,
          RESOLUTION_BASELINE,
        ),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      expect(fetch).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("refuses an ineligible, stale, unsafe or foreign baseline before credential lookup", async () => {
    const OTHER_CASE_ID = "6d782735-9825-4ce8-982b-b0556f705e4f";
    // 타입상 CaseDetail이지만 exact 10-field 계약을 벗어난 caller 객체다.
    const decoratedBaseline = { ...RESOLUTION_BASELINE, actorId: ASSIGNEE_ID };
    const rows: ReadonlyArray<readonly [string, CaseDetail, CaseResolutionRequest]> = [
      [
        "OPEN",
        workflowDetail({
          caseStatus: "OPEN",
          assigneeRef: null,
          reviewStartedAt: null,
          concurrencyVersion: 6,
        }),
        VALID_RESOLUTION,
      ],
      [
        "ADDITIONAL_INFORMATION_REQUIRED",
        workflowDetail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", concurrencyVersion: 6 }),
        VALID_RESOLUTION,
      ],
      [
        "CLOSED",
        workflowDetail({
          caseStatus: "CLOSED",
          finalDisposition: "NORMAL",
          closedAt: "2026-07-24T03:00:00Z",
          concurrencyVersion: 6,
        }),
        VALID_RESOLUTION,
      ],
      ["IN_REVIEW without an assignee", workflowDetail({ assigneeRef: null, concurrencyVersion: 6 }), VALID_RESOLUTION],
      [
        "IN_REVIEW without reviewStartedAt",
        workflowDetail({ reviewStartedAt: null, concurrencyVersion: 6 }),
        VALID_RESOLUTION,
      ],
      [
        "IN_REVIEW with a disposition",
        workflowDetail({ finalDisposition: "NORMAL", concurrencyVersion: 6 }),
        VALID_RESOLUTION,
      ],
      [
        "IN_REVIEW with closedAt",
        workflowDetail({ closedAt: "2026-07-24T03:00:00Z", concurrencyVersion: 6 }),
        VALID_RESOLUTION,
      ],
      ["a stale expectedVersion", RESOLUTION_BASELINE, { ...VALID_RESOLUTION, expectedVersion: 5 }],
      [
        "MAX_SAFE_INTEGER",
        workflowDetail({ concurrencyVersion: Number.MAX_SAFE_INTEGER }),
        { ...VALID_RESOLUTION, expectedVersion: Number.MAX_SAFE_INTEGER },
      ],
      ["another case", workflowDetail({ caseId: OTHER_CASE_ID, concurrencyVersion: 6 }), VALID_RESOLUTION],
      ["a decorated baseline", decoratedBaseline, VALID_RESOLUTION],
    ];

    for (const [name, baseline, request] of rows) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(resolutionMutation()));
      const error = await createCaseResolution(client, CASE_ID, request, baseline).catch(
        (thrown: unknown) => thrown,
      );
      expect(error, name).toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest, name).toBe(0);
      expect(fetch, name).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("rejects every resolution success that does not bind to its request and baseline", async () => {
    const OTHER_CASE_ID = "6d782735-9825-4ce8-982b-b0556f705e4f";
    const extraField = { ...resolutionMutation(), actorId: ASSIGNEE_ID };
    const missingField = resolutionMutation();
    delete missingField.traceId;
    const rows: ReadonlyArray<readonly [string, Record<string, unknown>]> = [
      ["another case", resolutionMutation({ caseId: OTHER_CASE_ID })],
      ["the unchanged version", resolutionMutation({ concurrencyVersion: 6 })],
      ["a skipped version", resolutionMutation({ concurrencyVersion: 8 })],
      ["an IN_REVIEW status", resolutionMutation({ caseStatus: "IN_REVIEW" })],
      [
        "an ADDITIONAL_INFORMATION_REQUIRED status",
        resolutionMutation({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }),
      ],
      ["another disposition", resolutionMutation({ finalDisposition: "NORMAL" })],
      ["no disposition", resolutionMutation({ finalDisposition: null })],
      ["no closure time", resolutionMutation({ closedAt: null })],
      ["another assignee", resolutionMutation({ assigneeRef: ASSIGNEE_ID })],
      ["a released assignee", resolutionMutation({ assigneeRef: null })],
      ["a moved review start", resolutionMutation({ reviewStartedAt: "2026-07-24T01:26:00Z" })],
      ["a cleared review start", resolutionMutation({ reviewStartedAt: null })],
      [
        "closedAt different from lastChangedAt",
        resolutionMutation({ lastChangedAt: "2026-07-24T03:10:00.123457Z" }),
      ],
      ["a tenth field", extraField],
      ["an eighth field set", missingField],
    ];

    for (const [name, body] of rows) {
      mockFetchOnce(async () => jsonResponse(body));
      const error = await createCaseResolution(
        signedIn(),
        CASE_ID,
        VALID_RESOLUTION,
        RESOLUTION_BASELINE,
      ).catch((thrown: unknown) => thrown);
      expect(error, name).toBeInstanceOf(InvalidResponseError);
      expect(error, name).toMatchObject({
        name: "InvalidResponseError",
        message: "Received an unexpected response shape.",
      });
      expect(`${String(error)} ${JSON.stringify(error)}`, name).not.toMatch(
        /6d782735|2a000000|3b000000|trace|NORMAL|CONFIRMED|CLOSED|IN_REVIEW|actorId/i,
      );
      vi.unstubAllGlobals();
    }
  });

  it("returns a fresh nine-field projection that shares no top-level reference with the raw DTO", async () => {
    // 테스트 double의 response.json()은 JSON 재파싱 없이 테스트가 보관한 이 객체 참조를 그대로 돌려준다.
    const rawDto = resolutionMutation();
    const { resolveJson } = mockFetchOkWithControlledJson();
    resolveJson(rawDto);
    const expected = {
      caseId: CASE_ID,
      caseStatus: "CLOSED",
      finalDisposition: "CONFIRMED_FRAUD",
      assigneeRef: CURRENT_ASSIGNEE_ID,
      reviewStartedAt: "2026-07-24T01:25:00Z",
      closedAt: "2026-07-24T03:10:00.123456Z",
      lastChangedAt: "2026-07-24T03:10:00.123456Z",
      concurrencyVersion: 7,
      traceId: TRACE_ID,
    };

    const result = await createCaseResolution(
      signedIn(),
      CASE_ID,
      VALID_RESOLUTION,
      RESOLUTION_BASELINE,
    );

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(Object.is(result.data, rawDto)).toBe(false);
    expect(result.data).not.toBe(rawDto);
    expect(Object.getPrototypeOf(result.data)).toBe(Object.prototype);
    expect(result.data).toStrictEqual(expected);
    expect(result.traceId).toBe(TRACE_ID);

    // 반환 뒤 raw DTO를 바꾸거나 unknown field를 붙여도 projection에는 전파되지 않는다.
    rawDto.caseStatus = "IN_REVIEW";
    rawDto.concurrencyVersion = 8;
    rawDto.actorId = ASSIGNEE_ID;
    expect(result.data).toStrictEqual(expected);
    expect(result.data).not.toHaveProperty("actorId");
  });

  it("still refuses a held raw DTO carrying an unknown field with the fixed InvalidResponseError", async () => {
    const rawDto = { ...resolutionMutation(), actorId: ASSIGNEE_ID };
    const { resolveJson } = mockFetchOkWithControlledJson();
    resolveJson(rawDto);

    const error = await createCaseResolution(
      signedIn(),
      CASE_ID,
      VALID_RESOLUTION,
      RESOLUTION_BASELINE,
    ).catch((thrown: unknown) => thrown);

    expect(error).toBeInstanceOf(InvalidResponseError);
    expect(error).toMatchObject({
      name: "InvalidResponseError",
      message: "Received an unexpected response shape.",
    });
    expect(`${String(error)} ${JSON.stringify(error)}`).not.toMatch(
      /2a000000|3b000000|trace|CONFIRMED|CLOSED|actorId/i,
    );
  });
});

describe("case mutation responses", () => {
  it("accepts the documented nine-key mutation shape", async () => {
    expect(isCaseMutation(mutation())).toBe(true);
    expect(
      isCaseMutation(
        mutation({
          caseStatus: "CLOSED",
          finalDisposition: "NORMAL",
          closedAt: "2026-07-24T03:10:00.123456Z",
        }),
      ),
    ).toBe(true);
  });

  it("refuses a missing, unknown or malformed field", async () => {
    const complete = mutation();
    for (const key of Object.keys(complete)) {
      const missing = { ...complete };
      delete missing[key];
      expect(isCaseMutation(missing)).toBe(false);
    }
    expect(isCaseMutation({ ...complete, actorId: ASSIGNEE_ID })).toBe(false);
    expect(isCaseMutation({ ...complete, relatedTransactionCount: 3 })).toBe(false);

    for (const override of [
      { caseStatus: "UNKNOWN" },
      { concurrencyVersion: -1 },
      { concurrencyVersion: Number.MAX_SAFE_INTEGER + 1 },
      { lastChangedAt: null },
      { closedAt: "2026-07-24" },
      { traceId: "short" },
      { traceId: null },
      { caseId: CASE_ID.toUpperCase() },
    ]) {
      expect(isCaseMutation(mutation(override))).toBe(false);
    }
  });
});

describe("case API — failure boundaries", () => {
  const WRITES: ReadonlyArray<readonly [string, () => Promise<unknown>]> = [
    [
      "status",
      () => changeCaseStatus(signedIn(), CASE_ID, VALID_STATUS_CHANGE, OPEN_WORKFLOW_DETAIL),
    ],
    [
      "assignee",
      () =>
        changeCaseAssignee(
          signedIn(),
          CASE_ID,
          VALID_ASSIGNEE_CHANGE,
          REVIEW_WORKFLOW_DETAIL,
        ),
    ],
    [
      "resolution",
      () => createCaseResolution(signedIn(), CASE_ID, VALID_RESOLUTION, RESOLUTION_BASELINE),
    ],
  ];

  it("performs exactly one fetch per write, whatever the failure", async () => {
    for (const [, run] of WRITES) {
      for (const status of [400, 401, 403, 404, 409, 422, 500, 503]) {
        mockFetchOnce(async () => jsonResponse({ code: "X", message: "leaked" }, { status }));
        await expect(run()).rejects.toBeInstanceOf(Error);
        expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
        vi.unstubAllGlobals();
      }

      mockFetchRejectOnce(new TypeError("connection refused"));
      await expect(run()).rejects.toBeInstanceOf(NetworkError);
      expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
      vi.unstubAllGlobals();
    }
  });

  it("keeps a 409 opaque, so no conflict body reaches the caller", async () => {
    mockFetchOnce(async () =>
      jsonResponse(
        {
          code: "CONCURRENT_MODIFICATION",
          message: "leaked",
          traceId: "leaked_body_trace_01",
          fieldErrors: [],
        },
        { status: 409 },
      ),
    );

    const error = await createCaseResolution(
      signedIn(),
      CASE_ID,
      VALID_RESOLUTION,
      RESOLUTION_BASELINE,
    ).catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(HttpError);
    expect((error as HttpError).status).toBe(409);
    expect(JSON.stringify(error)).not.toContain("leaked");
  });

  it("invalidates on 401 and leaves the session alone on 403, for a write too", async () => {
    const unauthorized = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 401 }));
    await expect(
      changeCaseStatus(unauthorized, CASE_ID, VALID_STATUS_CHANGE, OPEN_WORKFLOW_DETAIL),
    ).rejects.toBeInstanceOf(UnauthorizedError);
    expect(unauthorized.calls.invalidateIfCurrent).toBe(1);
    vi.unstubAllGlobals();

    const forbidden = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 403 }));
    await expect(
      changeCaseStatus(forbidden, CASE_ID, VALID_STATUS_CHANGE, OPEN_WORKFLOW_DETAIL),
    ).rejects.toBeInstanceOf(ForbiddenError);
    expect(forbidden.calls.invalidateIfCurrent).toBe(0);
  });

  it("refuses a mutation whose header trace id disagrees with its body", async () => {
    mockFetchOnce(async () =>
      jsonResponse(mutation(), { headers: { "X-Trace-Id": "trace_demo_other_01" } }),
    );
    await expect(
      changeCaseStatus(signedIn(), CASE_ID, VALID_STATUS_CHANGE, OPEN_WORKFLOW_DETAIL),
    ).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("sends nothing when there is no session", async () => {
    const client = createFakeAuthClient({ initialSession: null });
    mockFetchOnce(async () => jsonResponse(mutation()));

    await expect(
      changeCaseStatus(client, CASE_ID, VALID_STATUS_CHANGE, OPEN_WORKFLOW_DETAIL),
    ).rejects.toThrow();
    expect(vi.mocked(fetch)).not.toHaveBeenCalled();
  });

  it("sends GET reads with no body at all", async () => {
    mockFetchOnce(async () => jsonResponse({ case: detailItem(), traceId: TRACE_ID }));
    await fetchCaseDetail(signedIn(), CASE_ID);
    expect(sentRequest().body).toBeNull();
    expect(sentRequest().headers.get("Content-Type")).toBeNull();
  });
});

/**
 * The reason/target/assignee combinations `FraudCaseWorkflowService` accepts,
 * exercised as a matrix rather than one example each.
 *
 * The body builders are called directly here so the whole table can be covered
 * without a fetch mock per row; the `changeCase*` wrappers are shown separately
 * to cost zero credential lookups on a refusal.
 */
describe("case write combinations — status change", () => {
  const ACCEPTED: ReadonlyArray<readonly [string, Record<string, unknown>]> = [
    [
      "OPEN to IN_REVIEW assigns the case",
      {
        targetStatus: "IN_REVIEW",
        assigneeRef: ASSIGNEE_ID,
        reasonCode: "CASE_REVIEW_STARTED",
        expectedVersion: 0,
      },
    ],
    [
      "IN_REVIEW to ADDITIONAL_INFORMATION_REQUIRED carries no assignee",
      {
        targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
        reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
        expectedVersion: 4,
      },
    ],
    [
      "ADDITIONAL_INFORMATION_REQUIRED back to IN_REVIEW carries no assignee",
      { targetStatus: "IN_REVIEW", reasonCode: "CASE_REVIEW_RESUMED", expectedVersion: 5 },
    ],
  ];

  it("accepts exactly the three approved combinations", () => {
    for (const [name, request] of ACCEPTED) {
      expect(() => buildCaseStatusChangeBody(request), name).not.toThrow();
    }

    expect(
      buildCaseStatusChangeBody({
        targetStatus: "IN_REVIEW",
        assigneeRef: ASSIGNEE_ID,
        reasonCode: "CASE_REVIEW_STARTED",
        expectedVersion: 0,
      }),
    ).toEqual({
      targetStatus: "IN_REVIEW",
      assigneeRef: ASSIGNEE_ID,
      reasonCode: "CASE_REVIEW_STARTED",
      expectedVersion: 0,
    });

    expect(
      buildCaseStatusChangeBody({
        targetStatus: "IN_REVIEW",
        reasonCode: "CASE_REVIEW_RESUMED",
        expectedVersion: 5,
      }),
    ).toEqual({
      targetStatus: "IN_REVIEW",
      reasonCode: "CASE_REVIEW_RESUMED",
      expectedVersion: 5,
    });
  });

  it("refuses a reason paired with the wrong target status", () => {
    for (const request of [
      // review starts into IN_REVIEW, never anywhere else
      {
        targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
        assigneeRef: ASSIGNEE_ID,
        reasonCode: "CASE_REVIEW_STARTED",
        expectedVersion: 0,
      },
      // information is requested into ADDITIONAL_INFORMATION_REQUIRED
      {
        targetStatus: "IN_REVIEW",
        reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
        expectedVersion: 4,
      },
      // resuming lands in IN_REVIEW
      {
        targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
        reasonCode: "CASE_REVIEW_RESUMED",
        expectedVersion: 5,
      },
      // neither CLOSED nor OPEN is ever a target of this endpoint
      {
        targetStatus: "CLOSED",
        assigneeRef: ASSIGNEE_ID,
        reasonCode: "CASE_REVIEW_STARTED",
        expectedVersion: 0,
      },
      {
        targetStatus: "OPEN",
        reasonCode: "CASE_REVIEW_RESUMED",
        expectedVersion: 5,
      },
    ]) {
      expect(() => buildCaseStatusChangeBody(request)).toThrow(RequestNotAllowedError);
    }
  });

  it("requires an assignee UUID for CASE_REVIEW_STARTED and refuses null there", () => {
    for (const assigneeRef of [null, "", "analyst_ref_demo_07", ASSIGNEE_ID.toUpperCase(), 7]) {
      expect(() =>
        buildCaseStatusChangeBody({
          targetStatus: "IN_REVIEW",
          assigneeRef,
          reasonCode: "CASE_REVIEW_STARTED",
          expectedVersion: 0,
        }),
      ).toThrow(RequestNotAllowedError);
    }
    // and omitting the key entirely is not an assignment either
    expect(() =>
      buildCaseStatusChangeBody({
        targetStatus: "IN_REVIEW",
        reasonCode: "CASE_REVIEW_STARTED",
        expectedVersion: 0,
      }),
    ).toThrow(RequestNotAllowedError);
  });

  it("refuses the assigneeRef key on the two transitions that must not carry it", () => {
    for (const reasonCode of ["CASE_ADDITIONAL_INFORMATION_REQUESTED", "CASE_REVIEW_RESUMED"]) {
      const targetStatus =
        reasonCode === "CASE_REVIEW_RESUMED" ? "IN_REVIEW" : "ADDITIONAL_INFORMATION_REQUIRED";
      for (const assigneeRef of [null, ASSIGNEE_ID]) {
        expect(() =>
          buildCaseStatusChangeBody({
            targetStatus,
            assigneeRef,
            reasonCode,
            expectedVersion: 4,
          }),
        ).toThrow(RequestNotAllowedError);
      }
      // without the key, the same request is fine
      expect(() =>
        buildCaseStatusChangeBody({ targetStatus, reasonCode, expectedVersion: 4 }),
      ).not.toThrow();
    }
  });

  it("refuses a reason code that belongs to another endpoint", () => {
    for (const reasonCode of [
      "CASE_ASSIGNEE_ASSIGNED",
      "CASE_ASSIGNEE_CHANGED",
      "CASE_ASSIGNEE_RELEASED",
      "CASE_RESOLUTION_COMPLETED",
      "CASE_INVESTIGATION_NOTE_ADDED",
      "CASE_REQUIRED_BY_RISK_POLICY",
      "case_review_started",
    ]) {
      expect(() =>
        buildCaseStatusChangeBody({
          targetStatus: "IN_REVIEW",
          assigneeRef: ASSIGNEE_ID,
          reasonCode,
          expectedVersion: 0,
        }),
      ).toThrow(RequestNotAllowedError);
    }
  });
});

describe("case write combinations — assignee change", () => {
  it("pairs null with RELEASED and a UUID with ASSIGNED or CHANGED", () => {
    expect(
      buildCaseAssigneeChangeBody({
        assigneeRef: null,
        reasonCode: "CASE_ASSIGNEE_RELEASED",
        expectedVersion: 5,
      }),
    ).toEqual({ assigneeRef: null, reasonCode: "CASE_ASSIGNEE_RELEASED", expectedVersion: 5 });

    for (const reasonCode of ["CASE_ASSIGNEE_ASSIGNED", "CASE_ASSIGNEE_CHANGED"]) {
      expect(
        buildCaseAssigneeChangeBody({
          assigneeRef: ASSIGNEE_ID,
          reasonCode,
          expectedVersion: 5,
        }),
      ).toEqual({ assigneeRef: ASSIGNEE_ID, reasonCode, expectedVersion: 5 });
    }
  });

  it("refuses a UUID under RELEASED and null under ASSIGNED or CHANGED", () => {
    expect(() =>
      buildCaseAssigneeChangeBody({
        assigneeRef: ASSIGNEE_ID,
        reasonCode: "CASE_ASSIGNEE_RELEASED",
        expectedVersion: 5,
      }),
    ).toThrow(RequestNotAllowedError);

    for (const reasonCode of ["CASE_ASSIGNEE_ASSIGNED", "CASE_ASSIGNEE_CHANGED"]) {
      expect(() =>
        buildCaseAssigneeChangeBody({ assigneeRef: null, reasonCode, expectedVersion: 5 }),
      ).toThrow(RequestNotAllowedError);
    }
  });

  it("refuses a malformed assignee reference under every reason", () => {
    for (const reasonCode of ["CASE_ASSIGNEE_ASSIGNED", "CASE_ASSIGNEE_CHANGED"]) {
      for (const assigneeRef of [
        "",
        "analyst_ref_demo_07",
        ASSIGNEE_ID.toUpperCase(),
        `${ASSIGNEE_ID} `,
        undefined,
        7,
      ]) {
        expect(() =>
          buildCaseAssigneeChangeBody({ assigneeRef, reasonCode, expectedVersion: 5 }),
        ).toThrow(RequestNotAllowedError);
      }
    }
  });

  it("still refuses an omitted assigneeRef key, which is not a release", () => {
    expect(() =>
      buildCaseAssigneeChangeBody({
        reasonCode: "CASE_ASSIGNEE_RELEASED",
        expectedVersion: 5,
      }),
    ).toThrow(RequestNotAllowedError);
  });
});

describe("case write combinations — refused before any credential", () => {
  it("spends no credential lookup and no fetch on an impossible combination", async () => {
    const impossible: ReadonlyArray<readonly [string, (client: FakeAuthClient) => Promise<unknown>]> =
      [
        [
          "review started without an assignee",
          (client) =>
            changeCaseStatus(client, CASE_ID, {
              targetStatus: "IN_REVIEW",
              reasonCode: "CASE_REVIEW_STARTED",
              expectedVersion: 0,
            } as unknown as CaseStatusChangeRequest, OPEN_WORKFLOW_DETAIL),
        ],
        [
          "review resumed carrying an assignee",
          (client) =>
            changeCaseStatus(client, CASE_ID, {
              targetStatus: "IN_REVIEW",
              assigneeRef: ASSIGNEE_ID,
              reasonCode: "CASE_REVIEW_RESUMED",
              expectedVersion: 5,
            } as unknown as CaseStatusChangeRequest, REVIEW_WORKFLOW_DETAIL),
        ],
        [
          "information requested into the wrong state",
          (client) =>
            changeCaseStatus(client, CASE_ID, {
              targetStatus: "IN_REVIEW",
              reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
              expectedVersion: 4,
            } as unknown as CaseStatusChangeRequest, workflowDetail({ concurrencyVersion: 4 })),
        ],
        [
          "release naming an assignee",
          (client) =>
            changeCaseAssignee(client, CASE_ID, {
              assigneeRef: ASSIGNEE_ID,
              reasonCode: "CASE_ASSIGNEE_RELEASED",
              expectedVersion: 5,
            } as unknown as CaseAssigneeChangeRequest, REVIEW_WORKFLOW_DETAIL),
        ],
        [
          "assignment releasing instead",
          (client) =>
            changeCaseAssignee(client, CASE_ID, {
              assigneeRef: null,
              reasonCode: "CASE_ASSIGNEE_ASSIGNED",
              expectedVersion: 5,
            } as unknown as CaseAssigneeChangeRequest, REVIEW_WORKFLOW_DETAIL),
        ],
      ];

    for (const [name, run] of impossible) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(mutation()));

      await expect(run(client), name).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest, name).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("sends the approved combinations it does accept", async () => {
    mockFetchOnce(async () =>
      jsonResponse(
        mutation({
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: CURRENT_ASSIGNEE_ID,
          concurrencyVersion: 5,
        }),
      ),
    );
    await changeCaseStatus(signedIn(), CASE_ID, {
      targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
      expectedVersion: 4,
    }, workflowDetail({ concurrencyVersion: 4 }));
    expect(await sentBody()).toEqual({
      targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
      expectedVersion: 4,
    });
    vi.unstubAllGlobals();

    mockFetchOnce(async () =>
      jsonResponse(
        mutation({
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: null,
          concurrencyVersion: 6,
        }),
      ),
    );
    await changeCaseAssignee(signedIn(), CASE_ID, {
      assigneeRef: null,
      reasonCode: "CASE_ASSIGNEE_RELEASED",
      expectedVersion: 5,
    }, workflowDetail({ caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }));
    expect(await sentBody()).toEqual({
      assigneeRef: null,
      reasonCode: "CASE_ASSIGNEE_RELEASED",
      expectedVersion: 5,
    });
  });
});
