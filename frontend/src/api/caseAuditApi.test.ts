import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthSession } from "../auth/authClient";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse, mockFetchOnce, mockFetchRejectOnce } from "../test/mockFetch";
import {
  ForbiddenError,
  HttpError,
  InvalidResponseError,
  NetworkError,
  RequestNotAllowedError,
  UnauthorizedError,
} from "./errors";
import { buildQueryValues } from "./pagination";
import { isUtcInstantString } from "./responseValidation";
import {
  fetchCaseAuditList,
  isCaseAuditPage,
  type CaseAuditListQuery,
  type CaseAuditListSort,
} from "./caseAuditApi";

const BASE = "http://localhost:8080";
const CASE_ID = "5c671624-8714-4bd7-871a-a9445e6f453e";
const ASSIGNEE_ID = "2a000000-0000-4000-9000-000000000002";
const OTHER_ASSIGNEE_ID = "3b000000-0000-4000-a000-000000000003";
const NOTE_ID = "10a0b0c0-0d0e-4f00-8a00-0b0c0d0e0f01";
const OTHER_CASE_ID = "7d881624-8714-4bd7-871a-a9445e6f4530";
const TRACE_ID = "trace_demo_case_audit_list_01";

const SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: ["FDS_ANALYST"],
};

function signedIn(): FakeAuthClient {
  return createFakeAuthClient({ initialSession: SESSION });
}

const CREATED_ENTRY = {
  action: "CASE_CREATED",
  reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
  actorType: "SYSTEM",
  changedAt: "2026-07-24T02:05:10Z",
  beforeSummary: null,
  afterSummary: { caseStatus: "OPEN" },
  metadata: {},
} as const;

const LINKED_ENTRY = {
  action: "CASE_TRANSACTION_LINKED",
  reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
  actorType: "SYSTEM",
  changedAt: "2026-07-24T02:05:11Z",
  beforeSummary: null,
  afterSummary: { linked: true },
  metadata: {},
} as const;

const STATUS_ENTRY = {
  action: "CASE_STATUS_CHANGED",
  reasonCode: "CASE_REVIEW_STARTED",
  actorType: "USER",
  changedAt: "2026-07-24T02:06:00Z",
  beforeSummary: { caseStatus: "OPEN", assigneeRef: null },
  afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
  metadata: {},
} as const;

const ASSIGNEE_ENTRY = {
  action: "CASE_ASSIGNEE_CHANGED",
  reasonCode: "CASE_ASSIGNEE_CHANGED",
  actorType: "USER",
  changedAt: "2026-07-24T02:07:00Z",
  beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
  afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: OTHER_ASSIGNEE_ID },
  metadata: {},
} as const;

const RESOLVED_ENTRY = {
  action: "CASE_RESOLVED",
  reasonCode: "CASE_RESOLUTION_COMPLETED",
  actorType: "USER",
  changedAt: "2026-07-24T03:10:00.123456Z",
  beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
  afterSummary: {
    caseStatus: "CLOSED",
    assigneeRef: ASSIGNEE_ID,
    finalDisposition: "CONFIRMED_FRAUD",
  },
  metadata: {},
} as const;

const NOTE_ENTRY = {
  action: "CASE_NOTE_CREATED",
  reasonCode: "CASE_INVESTIGATION_NOTE_ADDED",
  actorType: "USER",
  changedAt: "2026-09-02T00:00:00.123456Z",
  beforeSummary: null,
  afterSummary: null,
  metadata: { noteId: NOTE_ID },
} as const;

const ALL_ENTRIES: readonly Record<string, unknown>[] = [
  CREATED_ENTRY,
  LINKED_ENTRY,
  STATUS_ENTRY,
  ASSIGNEE_ENTRY,
  RESOLVED_ENTRY,
  NOTE_ENTRY,
];

function auditBody(
  content: readonly Record<string, unknown>[] = [CREATED_ENTRY],
  overrides: Record<string, unknown> = {},
  pageOverrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    caseId: CASE_ID,
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
    ...overrides,
  };
}

function entry(base: Record<string, unknown>, overrides: Record<string, unknown>) {
  return { ...base, ...overrides };
}

function sentRequest(): Request {
  return vi.mocked(fetch).mock.calls[0][0] as Request;
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", BASE);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("fetchCaseAuditList — request", () => {
  it("sends GET with the canonical page query", async () => {
    mockFetchOnce(async () => jsonResponse(auditBody()));

    await fetchCaseAuditList(signedIn(), CASE_ID, {
      sort: "changedAt,desc",
      size: 20,
      page: 0,
    });

    expect(sentRequest().url).toBe(
      `${BASE}/api/v1/cases/${CASE_ID}/audit-logs?page=0&size=20&sort=changedAt%2Cdesc`,
    );
    expect(sentRequest().method).toBe("GET");
    expect(sentRequest().body).toBeNull();
  });

  it("refuses a filter this endpoint does not accept", async () => {
    for (const query of [
      { action: "CASE_CREATED" },
      { actorType: "USER" },
      { sort: "createdAt,asc" },
      { sort: "changedAt,DESC" },
      { page: -1 },
      { page: 2147483648 },
      { size: 0 },
      { size: 101 },
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(auditBody()));
      await expect(
        fetchCaseAuditList(client, CASE_ID, query as CaseAuditListQuery),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("refuses a non-canonical case id before any credential lookup", async () => {
    for (const id of [CASE_ID.toUpperCase(), `${CASE_ID}/audit-logs`, "not-a-uuid", ""]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(auditBody()));
      await expect(fetchCaseAuditList(client, id)).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }
  });
});

describe("case audit entries — approved action combinations", () => {
  it("accepts every documented action, summary and metadata combination", async () => {
    for (const approved of ALL_ENTRIES) {
      expect(isCaseAuditPage(auditBody([approved]))).toBe(true);
    }
    expect(
      isCaseAuditPage(auditBody(ALL_ENTRIES, {}, { totalElements: 6, size: 20, totalPages: 1 })),
    ).toBe(true);
  });

  it("accepts an unassigned workflow summary as explicit null", async () => {
    expect(
      isCaseAuditPage(
        auditBody([
          entry(ASSIGNEE_ENTRY, {
            reasonCode: "CASE_ASSIGNEE_RELEASED",
            beforeSummary: {
              caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
              assigneeRef: ASSIGNEE_ID,
            },
            afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
          }),
        ]),
      ),
    ).toBe(true);
  });

  it("accepts an empty page", async () => {
    mockFetchOnce(async () => jsonResponse(auditBody([])));
    const result = await fetchCaseAuditList(signedIn(), CASE_ID);
    expect(result.data.content).toEqual([]);
  });
});

describe("case audit entries — action and reason code pairing", () => {
  it("refuses a reason code that belongs to another action", async () => {
    for (const bad of [
      entry(CREATED_ENTRY, { reasonCode: "CASE_REVIEW_STARTED" }),
      entry(CREATED_ENTRY, { reasonCode: "CASE_RESOLUTION_COMPLETED" }),
      entry(LINKED_ENTRY, { reasonCode: "CASE_INVESTIGATION_NOTE_ADDED" }),
      entry(STATUS_ENTRY, { reasonCode: "CASE_ASSIGNEE_ASSIGNED" }),
      entry(STATUS_ENTRY, { reasonCode: "CASE_REQUIRED_BY_RISK_POLICY" }),
      entry(ASSIGNEE_ENTRY, { reasonCode: "CASE_REVIEW_STARTED" }),
      entry(RESOLVED_ENTRY, { reasonCode: "CASE_REVIEW_RESUMED" }),
      entry(NOTE_ENTRY, { reasonCode: "CASE_RESOLUTION_COMPLETED" }),
      entry(CREATED_ENTRY, { reasonCode: "case_required_by_risk_policy" }),
      entry(CREATED_ENTRY, { reasonCode: "" }),
      entry(CREATED_ENTRY, { reasonCode: null }),
      entry(CREATED_ENTRY, { reasonCode: "RISK_RESPONSE_DECIDED_BY_POLICY" }),
    ]) {
      expect(isCaseAuditPage(auditBody([bad]))).toBe(false);
    }
  });

  it("accepts each reason code with the snapshot its own transition produces", async () => {
    const statusTransitions = [
      {
        reasonCode: "CASE_REVIEW_STARTED",
        beforeSummary: { caseStatus: "OPEN", assigneeRef: null },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
      },
      {
        reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
        afterSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
      },
      {
        reasonCode: "CASE_REVIEW_RESUMED",
        beforeSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
      },
    ];
    for (const transition of statusTransitions) {
      expect(isCaseAuditPage(auditBody([entry(STATUS_ENTRY, transition)]))).toBe(true);
    }

    const assigneeTransitions = [
      {
        reasonCode: "CASE_ASSIGNEE_ASSIGNED",
        beforeSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
        afterSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
      },
      {
        reasonCode: "CASE_ASSIGNEE_CHANGED",
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: OTHER_ASSIGNEE_ID },
      },
      {
        reasonCode: "CASE_ASSIGNEE_RELEASED",
        beforeSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
        afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
      },
    ];
    for (const transition of assigneeTransitions) {
      expect(isCaseAuditPage(auditBody([entry(ASSIGNEE_ENTRY, transition)]))).toBe(true);
    }
  });

  it("refuses an action outside the six a case audit page can carry", async () => {
    for (const action of [
      "TRANSACTION_RISK_RESPONSE_APPLIED",
      "TRANSACTION_STATUS_CHANGED",
      "CASE_DELETED",
      "case_created",
      "",
      null,
    ]) {
      expect(isCaseAuditPage(auditBody([entry(CREATED_ENTRY, { action })]))).toBe(false);
    }
  });
});

describe("case audit entries — summary and metadata pairing", () => {
  it("refuses a summary shape borrowed from another action", async () => {
    for (const bad of [
      // creation must carry only a status summary
      entry(CREATED_ENTRY, { afterSummary: { linked: true } }),
      entry(CREATED_ENTRY, { afterSummary: { caseStatus: "OPEN", assigneeRef: null } }),
      entry(CREATED_ENTRY, { afterSummary: null }),
      // linking must carry only a linked summary
      entry(LINKED_ENTRY, { afterSummary: { caseStatus: "OPEN" } }),
      entry(LINKED_ENTRY, { afterSummary: { linked: "true" } }),
      // a workflow change must carry a workflow summary on both sides
      entry(STATUS_ENTRY, { beforeSummary: null }),
      entry(STATUS_ENTRY, { beforeSummary: { caseStatus: "OPEN" } }),
      entry(STATUS_ENTRY, { afterSummary: { caseStatus: "IN_REVIEW" } }),
      entry(ASSIGNEE_ENTRY, { afterSummary: null }),
      // resolution needs the three-field summary after, workflow before
      entry(RESOLVED_ENTRY, { afterSummary: { caseStatus: "CLOSED", assigneeRef: ASSIGNEE_ID } }),
      entry(RESOLVED_ENTRY, { beforeSummary: { caseStatus: "IN_REVIEW" } }),
      entry(RESOLVED_ENTRY, {
        beforeSummary: {
          caseStatus: "IN_REVIEW",
          assigneeRef: ASSIGNEE_ID,
          finalDisposition: "NORMAL",
        },
      }),
      // a note creation carries no summary at all
      entry(NOTE_ENTRY, { afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: null } }),
      entry(NOTE_ENTRY, { beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: null } }),
    ]) {
      expect(isCaseAuditPage(auditBody([bad]))).toBe(false);
    }
  });

  it("requires the resolution assignee, which cannot be null", async () => {
    expect(
      isCaseAuditPage(
        auditBody([
          entry(RESOLVED_ENTRY, {
            afterSummary: {
              caseStatus: "CLOSED",
              assigneeRef: null,
              finalDisposition: "CONFIRMED_FRAUD",
            },
          }),
        ]),
      ),
    ).toBe(false);
  });

  it("refuses metadata that does not match the action", async () => {
    for (const bad of [
      entry(NOTE_ENTRY, { metadata: {} }),
      entry(NOTE_ENTRY, { metadata: { noteId: NOTE_ID.toUpperCase() } }),
      entry(NOTE_ENTRY, { metadata: { noteId: "not-a-uuid" } }),
      entry(NOTE_ENTRY, { metadata: { noteId: NOTE_ID, extra: 1 } }),
      entry(NOTE_ENTRY, { metadata: null }),
      entry(CREATED_ENTRY, { metadata: { noteId: NOTE_ID } }),
      entry(STATUS_ENTRY, { metadata: { noteId: NOTE_ID } }),
      entry(RESOLVED_ENTRY, { metadata: { any: 1 } }),
      entry(CREATED_ENTRY, { metadata: null }),
    ]) {
      expect(isCaseAuditPage(auditBody([bad]))).toBe(false);
    }
  });

  it("refuses a malformed enum, uuid or instant inside a summary", async () => {
    for (const bad of [
      entry(CREATED_ENTRY, { afterSummary: { caseStatus: "UNKNOWN" } }),
      entry(CREATED_ENTRY, { afterSummary: { caseStatus: "open" } }),
      entry(STATUS_ENTRY, {
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID.toUpperCase() },
      }),
      entry(STATUS_ENTRY, {
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: "analyst_ref_demo_07" },
      }),
      entry(RESOLVED_ENTRY, {
        afterSummary: {
          caseStatus: "CLOSED",
          assigneeRef: ASSIGNEE_ID,
          finalDisposition: "confirmed_fraud",
        },
      }),
      entry(CREATED_ENTRY, { changedAt: "2026-07-24T02:05:10+09:00" }),
      entry(CREATED_ENTRY, { changedAt: "2026-07-24" }),
      entry(CREATED_ENTRY, { changedAt: null }),
      entry(CREATED_ENTRY, { actorType: "ADMIN" }),
      entry(CREATED_ENTRY, { actorType: "system" }),
    ]) {
      expect(isCaseAuditPage(auditBody([bad]))).toBe(false);
    }
  });

  it("refuses a missing or unknown key on an entry", async () => {
    for (const key of Object.keys(CREATED_ENTRY)) {
      const missing = { ...CREATED_ENTRY } as Record<string, unknown>;
      delete missing[key];
      expect(isCaseAuditPage(auditBody([missing]))).toBe(false);
    }
    for (const extra of ["auditId", "actorId", "traceId", "targetId", "transactionId", "id"]) {
      expect(isCaseAuditPage(auditBody([{ ...CREATED_ENTRY, [extra]: "leaked" }]))).toBe(false);
    }
  });

  it("rejects the whole page when a single entry is malformed", async () => {
    const body = auditBody(
      [CREATED_ENTRY, entry(STATUS_ENTRY, { reasonCode: "CASE_ASSIGNEE_ASSIGNED" }), NOTE_ENTRY],
      {},
      { totalElements: 3 },
    );
    expect(isCaseAuditPage(body)).toBe(false);

    mockFetchOnce(async () => jsonResponse(body));
    await expect(fetchCaseAuditList(signedIn(), CASE_ID)).rejects.toBeInstanceOf(
      InvalidResponseError,
    );
  });
});

describe("case audit page envelope", () => {
  it("refuses an envelope with a missing, unknown or malformed top-level key", async () => {
    const complete = auditBody();
    for (const key of Object.keys(complete)) {
      const missing = { ...complete };
      delete missing[key];
      expect(isCaseAuditPage(missing)).toBe(false);
    }
    expect(isCaseAuditPage({ ...complete, extra: 1 })).toBe(false);
    expect(isCaseAuditPage({ ...complete, caseId: CASE_ID.toUpperCase() })).toBe(false);
    expect(isCaseAuditPage({ ...complete, traceId: "short" })).toBe(false);
    expect(isCaseAuditPage(null)).toBe(false);
    expect(isCaseAuditPage([complete])).toBe(false);
  });

  it("refuses a page whose caseId is not the one that was asked for", async () => {
    mockFetchOnce(async () => jsonResponse(auditBody([CREATED_ENTRY], { caseId: OTHER_CASE_ID })));
    await expect(fetchCaseAuditList(signedIn(), CASE_ID)).rejects.toBeInstanceOf(
      InvalidResponseError,
    );
  });

  it("refuses page metadata that does not add up", async () => {
    for (const pageOverrides of [
      { totalElements: 45, totalPages: 2 },
      { totalElements: 1, totalPages: 1, first: false },
      { totalElements: 1, totalPages: 1, last: false },
      { totalElements: 2, totalPages: 1 },
      { totalElements: Number.MAX_SAFE_INTEGER + 1, totalPages: 1 },
      { number: -1, totalElements: 1, totalPages: 1 },
    ]) {
      expect(isCaseAuditPage(auditBody([CREATED_ENTRY], {}, pageOverrides))).toBe(false);
    }
  });

  it("accepts an out-of-range page as an empty result", async () => {
    expect(
      isCaseAuditPage(
        auditBody([], {}, { number: 5, totalElements: 1, totalPages: 1, first: false, last: true }),
      ),
    ).toBe(true);
  });
});

describe("fetchCaseAuditList — pagination binding (Issue #299)", () => {
  /** 한 page의 metadata. 모든 행은 `isConsistentPageMetadata()` 산술을 만족하도록 직접 적는다. */
  type PageRow = Record<string, unknown>;

  const AUDIT_URL = `${BASE}/api/v1/cases/${CASE_ID}/audit-logs`;

  it("refuses a well-formed page whose number or size is not the requested effective pagination", async () => {
    // 요청 effective page·size와 응답 page.number·page.size의 결합 검증 (Issue #299).
    // page·size를 생략하면 URL에 싣지 않고 Backend 기본값 page=0·size=20을 기대한다. 모든 body는 기존 형식·
    // 산술 validator `isCaseAuditPage()`와 기존 caseId 결합을 통과하므로 pagination binding만 거부할 수 있다.
    const rows: ReadonlyArray<
      readonly [string, CaseAuditListQuery | undefined, string, boolean, boolean, PageRow]
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
      const body = auditBody([NOTE_ENTRY], {}, page);
      expect(isCaseAuditPage(body), name).toBe(true);
      expect(body.caseId, name).toBe(CASE_ID);

      const client = signedIn();
      // header trace도 body와 같은 값으로 실어 두 trace 경로 모두 오류에 반사되지 않는지 확인한다.
      mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

      const error = await fetchCaseAuditList(client, CASE_ID, query).catch(
        (thrown: unknown) => thrown,
      );

      expect(error, name).toBeInstanceOf(InvalidResponseError);
      expect(Reflect.ownKeys(error as object).sort(), name).toEqual(["message", "name", "stack"]);
      expect(String(error), name).toBe(
        "InvalidResponseError: Received an unexpected response shape.",
      );
      expect(JSON.stringify(error), name).toBe('{"name":"InvalidResponseError"}');

      expect(vi.mocked(fetch), name).toHaveBeenCalledTimes(1);
      const request = sentRequest();
      expect(request.method, name).toBe("GET");
      expect(request.url, name).toBe(`${AUDIT_URL}${search}`);
      const parsed = new URL(request.url);
      expect(parsed.searchParams.has("page"), name).toBe(hasPage);
      expect(parsed.searchParams.has("size"), name).toBe(hasSize);
      expect(client.calls.authorizeRequest, name).toBe(1);
      expect(client.calls.invalidateIfCurrent, name).toBe(0);
      expect(client.calls.notified, name).toBe(0);

      // raw body 전체, page metadata, trace·사건·note marker가 오류 표현에 남지 않는다.
      const disclosed = `${String(error)} ${JSON.stringify(error)}`;
      for (const secret of [
        JSON.stringify(body),
        JSON.stringify(body.page),
        TRACE_ID,
        CASE_ID,
        NOTE_ID,
      ]) {
        expect(disclosed, name).not.toContain(secret);
      }
      expect(disclosed, name).not.toMatch(/trace|5c671624|10a0b0c0|totalElements/i);
      vi.unstubAllGlobals();
    }
  });

  it("accepts a partial last page, empty pages and the omitted defaults when number and size match", async () => {
    // Issue #299 green 회귀: production 수정 전에도 통과하는 의도된 회귀 테스트이다. 응답 number·size가 요청
    // effective 값과 같으면 content가 size보다 적거나 비어 있어도 거부하지 않는다.
    const rows: ReadonlyArray<
      readonly [
        string,
        CaseAuditListQuery | undefined,
        string,
        readonly Record<string, unknown>[],
        number,
        number,
        PageRow,
      ]
    > = [
      [
        "마지막 페이지의 일부 content",
        { page: 2, size: 20 },
        "?page=2&size=20",
        [CREATED_ENTRY, LINKED_ENTRY, NOTE_ENTRY],
        2,
        20,
        { number: 2, size: 20, totalElements: 43, totalPages: 3, first: false, last: true },
      ],
      [
        "totalPages를 초과한 빈 page",
        { page: 5, size: 20 },
        "?page=5&size=20",
        [],
        5,
        20,
        { number: 5, size: 20, totalElements: 43, totalPages: 3, first: false, last: true },
      ],
      [
        "전체 결과 0건인 빈 page",
        { page: 0, size: 50 },
        "?page=0&size=50",
        [],
        0,
        50,
        { number: 0, size: 50, totalElements: 0, totalPages: 0, first: true, last: true },
      ],
      [
        "query 생략과 Backend 기본값 응답",
        undefined,
        "",
        [CREATED_ENTRY],
        0,
        20,
        { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true },
      ],
    ];

    for (const [name, query, search, content, number, size, page] of rows) {
      const body = auditBody(content, {}, page);
      expect(isCaseAuditPage(body), name).toBe(true);

      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

      const result = await fetchCaseAuditList(client, CASE_ID, query);

      expect(vi.mocked(fetch), name).toHaveBeenCalledTimes(1);
      expect(sentRequest().url, name).toBe(`${AUDIT_URL}${search}`);
      expect(result.data.caseId, name).toBe(CASE_ID);
      expect(result.data.content, name).toHaveLength(content.length);
      expect(result.data.page, name).toStrictEqual(page);
      expect(result.data.page.number, name).toBe(number);
      expect(result.data.page.size, name).toBe(size);
      expect(result.traceId, name).toBe(TRACE_ID);
      expect(client.calls.authorizeRequest, name).toBe(1);
      expect(client.calls.invalidateIfCurrent, name).toBe(0);
      vi.unstubAllGlobals();
    }
  });

  it("keeps refusing another case's page even when its pagination matches", async () => {
    // Issue #299 회귀: pagination 결합을 추가해도 기존 요청 caseId 결합은 그대로 유지된다.
    const body = auditBody([CREATED_ENTRY], { caseId: OTHER_CASE_ID });
    expect(isCaseAuditPage(body)).toBe(true);
    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

    const error = await fetchCaseAuditList(client, CASE_ID, { page: 0, size: 20 }).catch(
      (thrown: unknown) => thrown,
    );

    expect(error).toBeInstanceOf(InvalidResponseError);
    expect(JSON.stringify(error)).toBe('{"name":"InvalidResponseError"}');
    expect(`${String(error)} ${JSON.stringify(error)}`).not.toMatch(/5c671624|7d881624|trace/i);
    expect(sentRequest().url).toBe(`${AUDIT_URL}?page=0&size=20`);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("reads page and size once and binds both the URL and the response to that one snapshot", async () => {
    // Issue #299 A1. page·size getter는 첫 평가에서만 1·20을 주고 다시 평가되면 다른 유효 값 3·50을 준다.
    // getter마다 평가 시점의 credential 조회 횟수를 기록해 읽기가 credential 조회 전에 끝났는지도 확인한다.
    const client = signedIn();
    const pageReads: number[] = [];
    const sizeReads: number[] = [];
    const query: CaseAuditListQuery = {
      get page() {
        pageReads.push(client.calls.authorizeRequest);
        return pageReads.length === 1 ? 1 : 3;
      },
      get size() {
        sizeReads.push(client.calls.authorizeRequest);
        return sizeReads.length === 1 ? 20 : 50;
      },
      sort: "changedAt,desc",
    };
    const body = auditBody([CREATED_ENTRY], {}, {
      number: 1,
      size: 20,
      totalElements: 21,
      totalPages: 2,
      first: false,
      last: true,
    });
    expect(isCaseAuditPage(body)).toBe(true);
    mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));

    const result = await fetchCaseAuditList(client, CASE_ID, query);

    expect(pageReads).toEqual([0]);
    expect(sizeReads).toEqual([0]);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentRequest().url).toBe(`${AUDIT_URL}?page=1&size=20&sort=changedAt%2Cdesc`);
    expect(result.data.page.number).toBe(1);
    expect(result.data.page.size).toBe(20);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.notified).toBe(0);
  });

  it("evaluates an identity-dependent sort accessor with the caller's query as the receiver", async () => {
    // Issue #299 A2. sort getter는 원본 query identity로 평가될 때만 changedAt,asc를 주고, 다른 객체를
    // receiver로 평가되면 유효한 다른 값 changedAt,desc를 준다. 기존 `buildQueryValues()` 결과를 기준값으로
    // 기록하고 보정 경로 URL도 정확히 같은 값·순서를 쓰는지 확인한다.
    function identitySortQuery(): CaseAuditListQuery {
      const query: CaseAuditListQuery = {
        page: 0,
        size: 20,
        get sort() {
          return this === query ? "changedAt,asc" : "changedAt,desc";
        },
      };
      return query;
    }
    const baseline = Object.entries(
      buildQueryValues("case-audit-list", identitySortQuery()) ?? {},
    );
    expect(baseline).toEqual([
      ["page", "0"],
      ["size", "20"],
      ["sort", "changedAt,asc"],
    ]);

    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(auditBody(), { headers: { "X-Trace-Id": TRACE_ID } }));

    const result = await fetchCaseAuditList(client, CASE_ID, identitySortQuery());

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentRequest().url).toBe(`${AUDIT_URL}?page=0&size=20&sort=changedAt%2Casc`);
    expect([...new URL(sentRequest().url).searchParams]).toEqual(baseline);
    expect(result.data.page.number).toBe(0);
    expect(result.data.page.size).toBe(20);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("keeps a preceding accessor's side effect on this.size in the existing evaluation order", async () => {
    // Issue #299 A3 선행. 기존 `buildQueryValues()`는 contract 순서대로 page를 size보다 먼저 읽는다. page
    // getter가 원본 receiver의 `this.size`를 20에서 50으로 바꾸므로 이후 size 직렬화는 50을 쓴다. 보정 경로
    // URL도 기존 경로와 같아야 하고, 응답 size 50이 정상 성공해야 validator 기대값도 URL의 size 50과 같다.
    function precedingQuery(): { readonly page: number; size: number } {
      return {
        get page(): number {
          this.size = 50;
          return 1;
        },
        size: 20,
      };
    }
    const baselineQuery = precedingQuery();
    const baseline = Object.entries(buildQueryValues("case-audit-list", baselineQuery) ?? {});
    expect(baseline).toEqual([
      ["page", "1"],
      ["size", "50"],
    ]);
    expect(baselineQuery.size).toBe(50);

    const client = signedIn();
    const body = auditBody([CREATED_ENTRY], {}, {
      number: 1,
      size: 50,
      totalElements: 51,
      totalPages: 2,
      first: false,
      last: true,
    });
    expect(isCaseAuditPage(body)).toBe(true);
    mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));
    const query = precedingQuery();

    const result = await fetchCaseAuditList(client, CASE_ID, query);

    expect(query.size).toBe(50);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentRequest().url).toBe(`${AUDIT_URL}?page=1&size=50`);
    expect([...new URL(sentRequest().url).searchParams]).toEqual(baseline);
    expect(result.data.page.number).toBe(1);
    expect(result.data.page.size).toBe(50);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("binds a trailing accessor's side effect to the pagination values the URL already used", async () => {
    // Issue #299 A3 후행. sort getter는 contract 순서상 page·size 직렬화 뒤에 평가되며 원본 `this.page`를 2로,
    // `this.size`를 50으로 바꾼다. URL은 기존과 같이 page=0·size=20을 쓰므로 validator 기대값도 첫 직접 조회
    // 값이어야 한다. 바뀐 원본 값을 담은 응답은 URL에 쓰이지 않은 좌표이므로 거부해야 한다.
    function trailingQuery(): { page: number; size: number; readonly sort: CaseAuditListSort } {
      return {
        page: 0,
        size: 20,
        get sort(): CaseAuditListSort {
          this.page = 2;
          this.size = 50;
          return "changedAt,desc";
        },
      };
    }
    const baselineQuery = trailingQuery();
    const baseline = Object.entries(buildQueryValues("case-audit-list", baselineQuery) ?? {});
    expect(baseline).toEqual([
      ["page", "0"],
      ["size", "20"],
      ["sort", "changedAt,desc"],
    ]);
    expect(baselineQuery.page).toBe(2);
    expect(baselineQuery.size).toBe(50);

    const accepted = signedIn();
    mockFetchOnce(async () => jsonResponse(auditBody(), { headers: { "X-Trace-Id": TRACE_ID } }));
    const acceptedQuery = trailingQuery();

    const result = await fetchCaseAuditList(accepted, CASE_ID, acceptedQuery);

    expect(acceptedQuery.page).toBe(2);
    expect(acceptedQuery.size).toBe(50);
    expect(sentRequest().url).toBe(`${AUDIT_URL}?page=0&size=20&sort=changedAt%2Cdesc`);
    expect([...new URL(sentRequest().url).searchParams]).toEqual(baseline);
    expect(result.data.page.number).toBe(0);
    expect(result.data.page.size).toBe(20);
    expect(accepted.calls.authorizeRequest).toBe(1);
    vi.unstubAllGlobals();

    const mutatedRows: ReadonlyArray<readonly [string, Record<string, unknown>]> = [
      [
        "바뀐 원본 page 2를 담은 응답",
        auditBody([], {}, {
          number: 2,
          size: 20,
          totalElements: 1,
          totalPages: 1,
          first: false,
          last: true,
        }),
      ],
      [
        "바뀐 원본 size 50을 담은 응답",
        auditBody([CREATED_ENTRY], {}, {
          number: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
          first: true,
          last: true,
        }),
      ],
    ];
    for (const [name, body] of mutatedRows) {
      expect(isCaseAuditPage(body), name).toBe(true);
      const refused = signedIn();
      mockFetchOnce(async () => jsonResponse(body, { headers: { "X-Trace-Id": TRACE_ID } }));
      const refusedQuery = trailingQuery();

      const error = await fetchCaseAuditList(refused, CASE_ID, refusedQuery).catch(
        (thrown: unknown) => thrown,
      );

      expect(error, name).toBeInstanceOf(InvalidResponseError);
      expect(JSON.stringify(error), name).toBe('{"name":"InvalidResponseError"}');
      expect(refusedQuery.page, name).toBe(2);
      expect(refusedQuery.size, name).toBe(50);
      expect(vi.mocked(fetch), name).toHaveBeenCalledTimes(1);
      expect(sentRequest().url, name).toBe(`${AUDIT_URL}?page=0&size=20&sort=changedAt%2Cdesc`);
      expect(refused.calls.authorizeRequest, name).toBe(1);
      expect(refused.calls.invalidateIfCurrent, name).toBe(0);
      vi.unstubAllGlobals();
    }
  });

  it("keeps an accessor that reads private storage keyed by the caller's query", async () => {
    // Issue #299 A4. Object.prototype을 가진 허용 query의 sort getter가 원본 query identity를 key로 WeakMap
    // private storage를 읽는다. private field brand check처럼 다른 receiver로 평가되면 TypeError를 던진다.
    // 기존 경로와 보정 경로 모두 같은 URL로 성공해야 한다.
    const privateSorts = new WeakMap<object, CaseAuditListSort>();
    function privateStorageQuery(): CaseAuditListQuery {
      const query: CaseAuditListQuery = {
        page: 0,
        size: 20,
        get sort() {
          const value = privateSorts.get(this);
          if (value === undefined) {
            throw new TypeError("Cannot read private member from an object whose class did not declare it");
          }
          return value;
        },
      };
      privateSorts.set(query, "changedAt,asc");
      return query;
    }
    const baselineQuery = privateStorageQuery();
    expect(Object.getPrototypeOf(baselineQuery)).toBe(Object.prototype);
    const baseline = Object.entries(buildQueryValues("case-audit-list", baselineQuery) ?? {});
    expect(baseline).toEqual([
      ["page", "0"],
      ["size", "20"],
      ["sort", "changedAt,asc"],
    ]);

    const client = signedIn();
    mockFetchOnce(async () => jsonResponse(auditBody(), { headers: { "X-Trace-Id": TRACE_ID } }));

    const result = await fetchCaseAuditList(client, CASE_ID, privateStorageQuery());

    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    expect(sentRequest().url).toBe(`${AUDIT_URL}?page=0&size=20&sort=changedAt%2Casc`);
    expect([...new URL(sentRequest().url).searchParams]).toEqual(baseline);
    expect(result.data.content).toHaveLength(1);
    expect(result.traceId).toBe(TRACE_ID);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(client.calls.invalidateIfCurrent).toBe(0);
  });

  it("does not evaluate page or size getters differently from the existing validation on a refused request", async () => {
    // Issue #299 A5. 기존 `buildQueryValues()`는 배열·prototype·symbol key·허용되지 않은 key를 값 조회 전에
    // 거부하고, page가 거부되면 size를 읽지 않는다. 같은 모양의 query를 기존 경로와 보정 경로에 각각 넘겨
    // page·size getter 평가 횟수와 credential 조회 0회가 같은지 확인한다. 기존 fetch 경로는 query 값을 먼저
    // 평가한 뒤 URL builder에서 caseId를 거부하므로, non-canonical caseId 요청의 평가 순서도 함께 고정한다.
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
        (reads) => Object.defineProperties({ action: "CASE_CREATED" }, countingPagination(reads, 0)),
        0,
        0,
      ],
      ["page 거부 후 size 미평가", (reads) => Object.defineProperties({}, countingPagination(reads, -1)), 1, 0],
      [
        "contract 순서상 뒤 sort 거부",
        (reads) => Object.defineProperties({ sort: "createdAt,asc" }, countingPagination(reads, 0)),
        1,
        1,
      ],
    ];

    for (const [name, build, pageReads, sizeReads] of rows) {
      const baselineReads: Reads = { page: 0, size: 0 };
      expect(() => buildQueryValues("case-audit-list", build(baselineReads)), name).toThrow(
        RequestNotAllowedError,
      );
      expect(baselineReads, name).toEqual({ page: pageReads, size: sizeReads });

      const reads: Reads = { page: 0, size: 0 };
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(auditBody()));

      await expect(fetchCaseAuditList(client, CASE_ID, build(reads)), name).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );

      expect(reads, name).toEqual({ page: pageReads, size: sizeReads });
      expect(client.calls.authorizeRequest, name).toBe(0);
      expect(vi.mocked(fetch), name).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }

    // 유효한 query와 non-canonical caseId: 기존과 같이 query 값은 한 번씩 평가된 뒤 caseId가 거부된다.
    const baselineReads: Reads = { page: 0, size: 0 };
    expect(
      Object.entries(
        buildQueryValues(
          "case-audit-list",
          Object.defineProperties({}, countingPagination(baselineReads, 0)),
        ) ?? {},
      ),
    ).toEqual([
      ["page", "0"],
      ["size", "20"],
    ]);
    expect(baselineReads).toEqual({ page: 1, size: 1 });
    for (const id of [CASE_ID.toUpperCase(), "not-a-uuid"]) {
      const reads: Reads = { page: 0, size: 0 };
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(auditBody()));

      await expect(
        fetchCaseAuditList(client, id, Object.defineProperties({}, countingPagination(reads, 0))),
        id,
      ).rejects.toBeInstanceOf(RequestNotAllowedError);

      expect(reads, id).toEqual({ page: 1, size: 1 });
      expect(client.calls.authorizeRequest, id).toBe(0);
      expect(vi.mocked(fetch), id).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });
});

describe("case audit API — status, trace and failure boundaries", () => {
  it("accepts 200 only", async () => {
    for (const status of [201, 202, 204]) {
      mockFetchOnce(async () =>
        status === 204 ? new Response(null, { status }) : jsonResponse(auditBody(), { status }),
      );
      await expect(fetchCaseAuditList(signedIn(), CASE_ID)).rejects.toBeInstanceOf(
        InvalidResponseError,
      );
      vi.unstubAllGlobals();
    }
  });

  it("refuses a header trace id that disagrees with the body", async () => {
    mockFetchOnce(async () => jsonResponse(auditBody(), { headers: { "X-Trace-Id": TRACE_ID } }));
    await expect(fetchCaseAuditList(signedIn(), CASE_ID)).resolves.toMatchObject({
      traceId: TRACE_ID,
    });
    vi.unstubAllGlobals();

    mockFetchOnce(async () =>
      jsonResponse(auditBody(), { headers: { "X-Trace-Id": "trace_demo_other_01" } }),
    );
    await expect(fetchCaseAuditList(signedIn(), CASE_ID)).rejects.toBeInstanceOf(
      InvalidResponseError,
    );
  });

  it("keeps a 500 opaque and performs exactly one fetch", async () => {
    mockFetchOnce(async () =>
      jsonResponse({ code: "INTERNAL_ERROR", message: "leaked" }, { status: 500 }),
    );
    const error = await fetchCaseAuditList(signedIn(), CASE_ID).catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(HttpError);
    expect((error as HttpError).status).toBe(500);
    expect(JSON.stringify(error)).not.toContain("leaked");
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
  });

  it("invalidates on 401 and leaves the session alone on 403", async () => {
    const unauthorized = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 401 }));
    await expect(fetchCaseAuditList(unauthorized, CASE_ID)).rejects.toBeInstanceOf(
      UnauthorizedError,
    );
    expect(unauthorized.calls.invalidateIfCurrent).toBe(1);
    vi.unstubAllGlobals();

    const forbidden = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 403 }));
    await expect(fetchCaseAuditList(forbidden, CASE_ID)).rejects.toBeInstanceOf(ForbiddenError);
    expect(forbidden.calls.invalidateIfCurrent).toBe(0);
  });

  it("does not retry a failed network call", async () => {
    mockFetchRejectOnce(new TypeError("connection refused"));
    await expect(fetchCaseAuditList(signedIn(), CASE_ID)).rejects.toBeInstanceOf(NetworkError);
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
  });
});

/**
 * The value relationships `AuditMetadataPolicy` asserts, exercised as mutations
 * of an otherwise valid entry.
 *
 * Every case below keeps the action, the reason code, the key sets and the item
 * count intact and changes only a value, so nothing but a per-reason semantic
 * check can refuse it. A validator that reused one broad "workflow summary"
 * shape across the reasons would accept all of them.
 */
describe("case audit entries — value relationships per reason code", () => {
  it("refuses a creation that did not start OPEN", async () => {
    for (const caseStatus of ["CLOSED", "IN_REVIEW", "ADDITIONAL_INFORMATION_REQUIRED"]) {
      expect(isCaseAuditPage(auditBody([entry(CREATED_ENTRY, { afterSummary: { caseStatus } })])))
        .toBe(false);
    }
    expect(isCaseAuditPage(auditBody([CREATED_ENTRY]))).toBe(true);
  });

  it("refuses a transaction link that claims linked=false", async () => {
    expect(
      isCaseAuditPage(auditBody([entry(LINKED_ENTRY, { afterSummary: { linked: false } })])),
    ).toBe(false);
    expect(
      isCaseAuditPage(auditBody([entry(LINKED_ENTRY, { afterSummary: { linked: "true" } })])),
    ).toBe(false);
    expect(isCaseAuditPage(auditBody([LINKED_ENTRY]))).toBe(true);
  });

  it("refuses a CASE_REVIEW_STARTED whose states or assignee are wrong", async () => {
    const bad = [
      // did not come from OPEN
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: null },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
      },
      // did not arrive at IN_REVIEW
      {
        beforeSummary: { caseStatus: "OPEN", assigneeRef: null },
        afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: ASSIGNEE_ID },
      },
      // started review without assigning anyone
      {
        beforeSummary: { caseStatus: "OPEN", assigneeRef: null },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: null },
      },
      // claims an assignee the case already had while still OPEN
      {
        beforeSummary: { caseStatus: "OPEN", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
      },
      // no state change at all
      {
        beforeSummary: { caseStatus: "OPEN", assigneeRef: null },
        afterSummary: { caseStatus: "OPEN", assigneeRef: ASSIGNEE_ID },
      },
    ];
    for (const overrides of bad) {
      expect(
        isCaseAuditPage(
          auditBody([entry(STATUS_ENTRY, { reasonCode: "CASE_REVIEW_STARTED", ...overrides })]),
        ),
      ).toBe(false);
    }
  });

  it("refuses a CASE_ADDITIONAL_INFORMATION_REQUESTED that moved the wrong way or changed hands", async () => {
    const bad = [
      // wrong origin
      {
        beforeSummary: { caseStatus: "OPEN", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: ASSIGNEE_ID },
      },
      // wrong destination
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "CLOSED", assigneeRef: ASSIGNEE_ID },
      },
      // silently reassigned while only the status was supposed to move
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
        afterSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: OTHER_ASSIGNEE_ID,
        },
      },
      // silently released the assignee
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
      },
      // was never assigned in the first place
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: null },
        afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
      },
    ];
    for (const overrides of bad) {
      expect(
        isCaseAuditPage(
          auditBody([
            entry(STATUS_ENTRY, {
              reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
              ...overrides,
            }),
          ]),
        ),
      ).toBe(false);
    }
  });

  it("refuses a CASE_REVIEW_RESUMED that did not come from ADDITIONAL_INFORMATION_REQUIRED", async () => {
    const bad = [
      {
        beforeSummary: { caseStatus: "OPEN", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
      },
      {
        beforeSummary: { caseStatus: "CLOSED", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
      },
      // resumed into the wrong state
      {
        beforeSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
        afterSummary: { caseStatus: "CLOSED", assigneeRef: ASSIGNEE_ID },
      },
      // resumed with nobody assigned
      {
        beforeSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: null },
      },
      // changed hands on the way back in
      {
        beforeSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: OTHER_ASSIGNEE_ID },
      },
    ];
    for (const overrides of bad) {
      expect(
        isCaseAuditPage(
          auditBody([entry(STATUS_ENTRY, { reasonCode: "CASE_REVIEW_RESUMED", ...overrides })]),
        ),
      ).toBe(false);
    }
  });

  it("refuses an assignee change that also moved the status, or moved it out of an editable state", async () => {
    const bad = [
      // status moved during an assignee change
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
        afterSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: OTHER_ASSIGNEE_ID,
        },
      },
      // not an editable state
      {
        beforeSummary: { caseStatus: "OPEN", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "OPEN", assigneeRef: OTHER_ASSIGNEE_ID },
      },
      {
        beforeSummary: { caseStatus: "CLOSED", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "CLOSED", assigneeRef: OTHER_ASSIGNEE_ID },
      },
    ];
    for (const overrides of bad) {
      expect(
        isCaseAuditPage(
          auditBody([entry(ASSIGNEE_ENTRY, { reasonCode: "CASE_ASSIGNEE_CHANGED", ...overrides })]),
        ),
      ).toBe(false);
    }
  });

  it("refuses a CASE_ASSIGNEE_RELEASED whose assignee is still there afterwards", async () => {
    const bad = [
      // the whole point of the reason code did not happen
      {
        beforeSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
        afterSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
      },
      {
        beforeSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
        afterSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: OTHER_ASSIGNEE_ID,
        },
      },
      // released from a state that cannot release
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: null },
      },
      // nothing to release
      {
        beforeSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
        afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
      },
    ];
    for (const overrides of bad) {
      expect(
        isCaseAuditPage(
          auditBody([
            entry(ASSIGNEE_ENTRY, { reasonCode: "CASE_ASSIGNEE_RELEASED", ...overrides }),
          ]),
        ),
      ).toBe(false);
    }
  });

  it("refuses a CASE_ASSIGNEE_ASSIGNED that did not go from nobody to somebody in ADDITIONAL_INFORMATION_REQUIRED", async () => {
    const bad = [
      // already had an assignee
      {
        beforeSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
        afterSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: OTHER_ASSIGNEE_ID,
        },
      },
      // assigned nobody
      {
        beforeSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
        afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: null },
      },
      // IN_REVIEW cannot reach an unassigned state, so it cannot be assigned from one
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: null },
        afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
      },
    ];
    for (const overrides of bad) {
      expect(
        isCaseAuditPage(
          auditBody([
            entry(ASSIGNEE_ENTRY, { reasonCode: "CASE_ASSIGNEE_ASSIGNED", ...overrides }),
          ]),
        ),
      ).toBe(false);
    }
  });

  it("refuses a resolution that is not IN_REVIEW to CLOSED with the same assignee", async () => {
    const bad = [
      // did not come from IN_REVIEW
      {
        beforeSummary: {
          caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
          assigneeRef: ASSIGNEE_ID,
        },
        afterSummary: {
          caseStatus: "CLOSED",
          assigneeRef: ASSIGNEE_ID,
          finalDisposition: "CONFIRMED_FRAUD",
        },
      },
      {
        beforeSummary: { caseStatus: "OPEN", assigneeRef: ASSIGNEE_ID },
        afterSummary: {
          caseStatus: "CLOSED",
          assigneeRef: ASSIGNEE_ID,
          finalDisposition: "NORMAL",
        },
      },
      // did not actually close
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
        afterSummary: {
          caseStatus: "IN_REVIEW",
          assigneeRef: ASSIGNEE_ID,
          finalDisposition: "NORMAL",
        },
      },
      // changed hands while closing
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_ID },
        afterSummary: {
          caseStatus: "CLOSED",
          assigneeRef: OTHER_ASSIGNEE_ID,
          finalDisposition: "NORMAL",
        },
      },
      // closed with nobody on it
      {
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: null },
        afterSummary: {
          caseStatus: "CLOSED",
          assigneeRef: ASSIGNEE_ID,
          finalDisposition: "NORMAL",
        },
      },
    ];
    for (const overrides of bad) {
      expect(isCaseAuditPage(auditBody([entry(RESOLVED_ENTRY, overrides)]))).toBe(false);
    }
    expect(isCaseAuditPage(auditBody([RESOLVED_ENTRY]))).toBe(true);
  });

  it("refuses a page whose action and item count are right but whose meaning is not", async () => {
    // Six entries, six valid actions, a consistent page envelope - and one
    // release that never released anyone.
    const tampered = [
      CREATED_ENTRY,
      LINKED_ENTRY,
      STATUS_ENTRY,
      entry(ASSIGNEE_ENTRY, {
        reasonCode: "CASE_ASSIGNEE_RELEASED",
        beforeSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: ASSIGNEE_ID },
        afterSummary: { caseStatus: "ADDITIONAL_INFORMATION_REQUIRED", assigneeRef: ASSIGNEE_ID },
      }),
      RESOLVED_ENTRY,
      NOTE_ENTRY,
    ];
    const body = auditBody(tampered, {}, { totalElements: 6, totalPages: 1 });
    expect(isCaseAuditPage(body)).toBe(false);

    mockFetchOnce(async () => jsonResponse(body));
    await expect(fetchCaseAuditList(signedIn(), CASE_ID)).rejects.toBeInstanceOf(
      InvalidResponseError,
    );
  });
});

/**
 * `changedAt` is microsecond-resolution on the audit column, and
 * `FraudCaseAuditLogMapper` fails the whole page rather than project a finer
 * value. Only this field carries the extra bound; every other DTO instant keeps
 * the shared validator.
 */
describe("case audit entries — changedAt precision", () => {
  it("accepts second, millisecond and microsecond precision", async () => {
    for (const changedAt of [
      "2026-07-24T02:05:10Z",
      "2026-07-24T02:05:10.1Z",
      "2026-07-24T02:05:10.123Z",
      "2026-07-24T02:05:10.123456Z",
      "2026-07-24T02:05:10.000001Z",
      "2026-07-24T02:05:10.000000Z",
    ]) {
      expect(isCaseAuditPage(auditBody([entry(CREATED_ENTRY, { changedAt })])), changedAt).toBe(
        true,
      );
    }
  });

  it("refuses a value finer than a microsecond", async () => {
    for (const changedAt of [
      "2026-07-24T02:05:10.000000001Z",
      "2026-07-24T02:05:10.1234567Z",
      "2026-07-24T02:05:10.12345678Z",
      "2026-07-24T02:05:10.123456789Z",
      "2026-07-24T02:05:10.000000100Z",
    ]) {
      expect(isCaseAuditPage(auditBody([entry(CREATED_ENTRY, { changedAt })])), changedAt).toBe(
        false,
      );
    }
  });

  it("applies the bound to every action, not only case creation", async () => {
    const tooFine = "2026-07-24T02:05:10.000000001Z";
    for (const base of [
      CREATED_ENTRY,
      LINKED_ENTRY,
      STATUS_ENTRY,
      ASSIGNEE_ENTRY,
      RESOLVED_ENTRY,
      NOTE_ENTRY,
    ]) {
      expect(isCaseAuditPage(auditBody([entry(base, { changedAt: tooFine })]))).toBe(false);
    }
  });

  it("refuses the whole page when one otherwise valid entry is too precise", async () => {
    const body = auditBody(
      [
        CREATED_ENTRY,
        entry(STATUS_ENTRY, { changedAt: "2026-07-24T02:06:00.000000001Z" }),
        NOTE_ENTRY,
      ],
      {},
      { totalElements: 3, totalPages: 1 },
    );
    expect(isCaseAuditPage(body)).toBe(false);

    mockFetchOnce(async () => jsonResponse(body));
    await expect(fetchCaseAuditList(signedIn(), CASE_ID)).rejects.toBeInstanceOf(
      InvalidResponseError,
    );
  });

  it("leaves the other DTO instants on the shared validator", async () => {
    // A nanosecond-precision instant is refused on an audit `changedAt` but is
    // still an ordinary UTC instant everywhere Backend does not narrow it.
    expect(isUtcInstantString("2026-07-24T02:05:10.000000001Z")).toBe(true);
  });
});
