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
  type CaseListQuery,
  type CaseResolutionRequest,
  type CaseStatusChangeRequest,
} from "./caseApi";

const BASE = "http://localhost:8080";
const CASE_ID = "5c671624-8714-4bd7-871a-a9445e6f453e";
const ASSIGNEE_ID = "2a000000-0000-4000-9000-000000000002";
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
    concurrencyVersion: 2,
    traceId: TRACE_ID,
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
});

describe("changeCaseStatus", () => {
  it("sends PATCH with a rebuilt body carrying exactly the contract fields", async () => {
    mockFetchOnce(async () => jsonResponse(mutation()));

    await changeCaseStatus(signedIn(), CASE_ID, VALID_STATUS_CHANGE);

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
    mockFetchOnce(async () => jsonResponse(mutation()));

    await changeCaseStatus(signedIn(), CASE_ID, {
      targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
      expectedVersion: 3,
    });

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
        changeCaseStatus(client, CASE_ID, request as unknown as CaseStatusChangeRequest),
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
        changeCaseStatus(client, CASE_ID, request as CaseStatusChangeRequest),
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
      await expect(changeCaseStatus(client, id, VALID_STATUS_CHANGE)).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }
  });
});

describe("changeCaseAssignee", () => {
  it("sends PATCH with exactly the three contract fields", async () => {
    mockFetchOnce(async () => jsonResponse(mutation()));

    await changeCaseAssignee(signedIn(), CASE_ID, VALID_ASSIGNEE_CHANGE);

    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases/${CASE_ID}/assignee`);
    expect(sentRequest().method).toBe("PATCH");
    expect(await sentBody()).toEqual({
      assigneeRef: ASSIGNEE_ID,
      reasonCode: "CASE_ASSIGNEE_CHANGED",
      expectedVersion: 5,
    });
  });

  it("sends an explicit null to release the assignee", async () => {
    mockFetchOnce(async () => jsonResponse(mutation({ assigneeRef: null })));

    await changeCaseAssignee(signedIn(), CASE_ID, {
      assigneeRef: null,
      reasonCode: "CASE_ASSIGNEE_RELEASED",
      expectedVersion: 5,
    });

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
      } as CaseAssigneeChangeRequest),
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
        } as CaseAssigneeChangeRequest),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }
  });
});

describe("createCaseResolution", () => {
  it("sends POST and expects 200, not 201", async () => {
    mockFetchOnce(async () =>
      jsonResponse(
        mutation({
          caseStatus: "CLOSED",
          finalDisposition: "CONFIRMED_FRAUD",
          closedAt: "2026-07-24T03:10:00.123456Z",
          lastChangedAt: "2026-07-24T03:10:00.123456Z",
          concurrencyVersion: 7,
        }),
      ),
    );

    const result = await createCaseResolution(signedIn(), CASE_ID, VALID_RESOLUTION);

    expect(sentRequest().method).toBe("POST");
    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases/${CASE_ID}/resolution`);
    expect(await sentBody()).toEqual({
      finalDisposition: "CONFIRMED_FRAUD",
      reasonCode: "CASE_RESOLUTION_COMPLETED",
      expectedVersion: 6,
    });
    expect(result.data.caseStatus).toBe("CLOSED");
    expect(result.data.concurrencyVersion).toBe(7);
  });

  it("refuses a 201 for a resolution", async () => {
    mockFetchOnce(async () => jsonResponse(mutation(), { status: 201 }));
    await expect(
      createCaseResolution(signedIn(), CASE_ID, VALID_RESOLUTION),
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
      mockFetchOnce(async () => jsonResponse(mutation()));
      await expect(
        createCaseResolution(client, CASE_ID, request as CaseResolutionRequest),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }
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
    ["status", () => changeCaseStatus(signedIn(), CASE_ID, VALID_STATUS_CHANGE)],
    ["assignee", () => changeCaseAssignee(signedIn(), CASE_ID, VALID_ASSIGNEE_CHANGE)],
    ["resolution", () => createCaseResolution(signedIn(), CASE_ID, VALID_RESOLUTION)],
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

    const error = await createCaseResolution(signedIn(), CASE_ID, VALID_RESOLUTION).catch(
      (thrown: unknown) => thrown,
    );
    expect(error).toBeInstanceOf(HttpError);
    expect((error as HttpError).status).toBe(409);
    expect(JSON.stringify(error)).not.toContain("leaked");
  });

  it("invalidates on 401 and leaves the session alone on 403, for a write too", async () => {
    const unauthorized = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 401 }));
    await expect(
      changeCaseStatus(unauthorized, CASE_ID, VALID_STATUS_CHANGE),
    ).rejects.toBeInstanceOf(UnauthorizedError);
    expect(unauthorized.calls.invalidateIfCurrent).toBe(1);
    vi.unstubAllGlobals();

    const forbidden = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 403 }));
    await expect(changeCaseStatus(forbidden, CASE_ID, VALID_STATUS_CHANGE)).rejects.toBeInstanceOf(
      ForbiddenError,
    );
    expect(forbidden.calls.invalidateIfCurrent).toBe(0);
  });

  it("refuses a mutation whose header trace id disagrees with its body", async () => {
    mockFetchOnce(async () =>
      jsonResponse(mutation(), { headers: { "X-Trace-Id": "trace_demo_other_01" } }),
    );
    await expect(
      changeCaseStatus(signedIn(), CASE_ID, VALID_STATUS_CHANGE),
    ).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("sends nothing when there is no session", async () => {
    const client = createFakeAuthClient({ initialSession: null });
    mockFetchOnce(async () => jsonResponse(mutation()));

    await expect(changeCaseStatus(client, CASE_ID, VALID_STATUS_CHANGE)).rejects.toThrow();
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
            } as unknown as CaseStatusChangeRequest),
        ],
        [
          "review resumed carrying an assignee",
          (client) =>
            changeCaseStatus(client, CASE_ID, {
              targetStatus: "IN_REVIEW",
              assigneeRef: ASSIGNEE_ID,
              reasonCode: "CASE_REVIEW_RESUMED",
              expectedVersion: 5,
            } as unknown as CaseStatusChangeRequest),
        ],
        [
          "information requested into the wrong state",
          (client) =>
            changeCaseStatus(client, CASE_ID, {
              targetStatus: "IN_REVIEW",
              reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
              expectedVersion: 4,
            } as unknown as CaseStatusChangeRequest),
        ],
        [
          "release naming an assignee",
          (client) =>
            changeCaseAssignee(client, CASE_ID, {
              assigneeRef: ASSIGNEE_ID,
              reasonCode: "CASE_ASSIGNEE_RELEASED",
              expectedVersion: 5,
            } as unknown as CaseAssigneeChangeRequest),
        ],
        [
          "assignment releasing instead",
          (client) =>
            changeCaseAssignee(client, CASE_ID, {
              assigneeRef: null,
              reasonCode: "CASE_ASSIGNEE_ASSIGNED",
              expectedVersion: 5,
            } as unknown as CaseAssigneeChangeRequest),
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
    mockFetchOnce(async () => jsonResponse(mutation()));
    await changeCaseStatus(signedIn(), CASE_ID, {
      targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
      expectedVersion: 4,
    });
    expect(await sentBody()).toEqual({
      targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED",
      expectedVersion: 4,
    });
    vi.unstubAllGlobals();

    mockFetchOnce(async () => jsonResponse(mutation({ assigneeRef: null })));
    await changeCaseAssignee(signedIn(), CASE_ID, {
      assigneeRef: null,
      reasonCode: "CASE_ASSIGNEE_RELEASED",
      expectedVersion: 5,
    });
    expect(await sentBody()).toEqual({
      assigneeRef: null,
      reasonCode: "CASE_ASSIGNEE_RELEASED",
      expectedVersion: 5,
    });
  });
});
