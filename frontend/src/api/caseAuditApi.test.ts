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
import { isUtcInstantString } from "./responseValidation";
import {
  fetchCaseAuditList,
  isCaseAuditPage,
  type CaseAuditListQuery,
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
