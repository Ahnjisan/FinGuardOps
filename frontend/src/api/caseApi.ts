import type { CredentialAuthClient } from "../auth/authClient";
import { readExactRequestFields, sendAuthorizedBackendRequest } from "./authorizedClient";
import { CASE_FINAL_DISPOSITIONS, CASE_LIST_SORTS, CASE_STATUSES } from "./backendEndpoints";
import { RequestNotAllowedError } from "./errors";
import {
  buildQueryValues,
  isConsistentPageMetadata,
  isPageMetadata,
  type PageMetadata,
} from "./pagination";
import {
  isArrayOf,
  isEnumMember,
  isNullableOpaqueRefString,
  isNullableUtcInstantString,
  isObjectWithExactKeys,
  isSafeLong,
  isTraceIdString,
  isUtcInstantString,
  isUuidV4String,
  resolveTraceId,
  type ApiResult,
} from "./responseValidation";

/**
 * The five approved USER fraud-case endpoints: two reads and the three
 * high-risk writes.
 *
 * Each write is sent at most once. `sendAuthorizedBackendRequest` performs
 * exactly one fetch and retries nothing, which is the whole reason a failed
 * status change, reassignment or resolution cannot be replayed into a second
 * case decision.
 *
 * The two workflow request types are discriminated unions keyed on
 * `reasonCode`, because that is how Backend decides them.
 * `FraudCaseWorkflowService` pairs each transition with exactly one reason and
 * rejects every other, and it refuses an `assigneeRef` *key* on the two
 * transitions that must not carry one. A combination that cannot succeed is
 * refused here, before a credential is requested, rather than spent on a 409 or
 * a 422.
 */

export { CASE_STATUSES, CASE_FINAL_DISPOSITIONS, CASE_LIST_SORTS };

/** `FraudCaseStatus`. */
export type CaseStatus = (typeof CASE_STATUSES)[number];

/** `FraudCaseFinalDisposition`. */
export type CaseFinalDisposition = (typeof CASE_FINAL_DISPOSITIONS)[number];

/** The only sort `FraudCaseQueryValidator` accepts. */
export type CaseListSort = (typeof CASE_LIST_SORTS)[number];

/**
 * The only two states the general status-change API can move a case *into*.
 *
 * The approved transitions are `OPEN → IN_REVIEW`,
 * `IN_REVIEW → ADDITIONAL_INFORMATION_REQUIRED` and
 * `ADDITIONAL_INFORMATION_REQUIRED → IN_REVIEW`, so `OPEN` is never a target
 * and `CLOSED` is reachable only through the resolution endpoint - Backend
 * answers `409 CASE_STATUS_CONFLICT` to both.
 */
export const CASE_STATUS_CHANGE_TARGETS = ["IN_REVIEW", "ADDITIONAL_INFORMATION_REQUIRED"] as const;
export type CaseStatusChangeTarget = (typeof CASE_STATUS_CHANGE_TARGETS)[number];

/** The `AuditReasonCode` values a status change may carry. */
export const CASE_STATUS_CHANGE_REASONS = [
  "CASE_REVIEW_STARTED",
  "CASE_ADDITIONAL_INFORMATION_REQUESTED",
  "CASE_REVIEW_RESUMED",
] as const;
export type CaseStatusChangeReason = (typeof CASE_STATUS_CHANGE_REASONS)[number];

/** The `AuditReasonCode` values an assignee change may carry. */
export const CASE_ASSIGNEE_CHANGE_REASONS = [
  "CASE_ASSIGNEE_ASSIGNED",
  "CASE_ASSIGNEE_CHANGED",
  "CASE_ASSIGNEE_RELEASED",
] as const;
export type CaseAssigneeChangeReason = (typeof CASE_ASSIGNEE_CHANGE_REASONS)[number];

/** Resolution accepts exactly one reason code. */
export const CASE_RESOLUTION_REASON = "CASE_RESOLUTION_COMPLETED";

/**
 * The transition each status reason names, transcribed from
 * `FraudCaseWorkflowService.applyStatusChange`.
 *
 * `assigneeRefRequired` is not a convenience. `OPEN → IN_REVIEW` is the only
 * transition that carries an assignee; the other two are refused for carrying
 * the key at all by `rejectStatusAssigneeCombination`, explicit `null`
 * included.
 */
export const STATUS_CHANGE_CONTRACT: Readonly<
  Record<
    CaseStatusChangeReason,
    { readonly targetStatus: CaseStatusChangeTarget; readonly assigneeRefRequired: boolean }
  >
> = Object.freeze({
  CASE_REVIEW_STARTED: { targetStatus: "IN_REVIEW", assigneeRefRequired: true },
  CASE_ADDITIONAL_INFORMATION_REQUESTED: {
    targetStatus: "ADDITIONAL_INFORMATION_REQUIRED",
    assigneeRefRequired: false,
  },
  CASE_REVIEW_RESUMED: { targetStatus: "IN_REVIEW", assigneeRefRequired: false },
});

export interface CaseListItem {
  readonly caseId: string;
  readonly caseStatus: CaseStatus;
  readonly finalDisposition: CaseFinalDisposition | null;
  readonly assigneeRef: string | null;
  readonly relatedTransactionCount: number;
  readonly createdAt: string;
  readonly lastChangedAt: string;
}

export interface CaseListPage {
  readonly content: readonly CaseListItem[];
  readonly page: PageMetadata;
  readonly traceId: string;
}

export interface CaseDetail {
  readonly caseId: string;
  readonly caseStatus: CaseStatus;
  readonly finalDisposition: CaseFinalDisposition | null;
  readonly assigneeRef: string | null;
  readonly relatedTransactionCount: number;
  readonly createdAt: string;
  readonly reviewStartedAt: string | null;
  readonly closedAt: string | null;
  readonly lastChangedAt: string;
  readonly concurrencyVersion: number;
}

export interface CaseDetailEnvelope {
  /** Backend names this field `case`, which is a reserved word in Java but not in JSON. */
  readonly case: CaseDetail;
  readonly traceId: string;
}

/** `FraudCaseMutationResponse`, shared by all three writes. */
export interface CaseMutation {
  readonly caseId: string;
  readonly caseStatus: CaseStatus;
  readonly finalDisposition: CaseFinalDisposition | null;
  readonly assigneeRef: string | null;
  readonly reviewStartedAt: string | null;
  readonly closedAt: string | null;
  readonly lastChangedAt: string;
  readonly concurrencyVersion: number;
  readonly traceId: string;
}

export interface CaseListQuery {
  readonly caseStatus?: CaseStatus;
  readonly finalDisposition?: CaseFinalDisposition;
  readonly assigneeRef?: string;
  readonly createdAtFrom?: string;
  readonly createdAtTo?: string;
  readonly lastChangedAtFrom?: string;
  readonly lastChangedAtTo?: string;
  readonly transactionId?: string;
  readonly page?: number;
  readonly size?: number;
  readonly sort?: CaseListSort;
}

/**
 * The three status-change requests Backend can actually accept.
 *
 * `assigneeRef?: never` on the last two is not decoration: it makes
 * `{ reasonCode: "CASE_REVIEW_RESUMED", assigneeRef: null }` a compile error,
 * matching a Backend that refuses the key rather than its value.
 */
export type CaseStatusChangeRequest =
  | {
      readonly reasonCode: "CASE_REVIEW_STARTED";
      readonly targetStatus: "IN_REVIEW";
      /** Required: `OPEN → IN_REVIEW` is the transition that assigns the case. */
      readonly assigneeRef: string;
      readonly expectedVersion: number;
    }
  | {
      readonly reasonCode: "CASE_ADDITIONAL_INFORMATION_REQUESTED";
      readonly targetStatus: "ADDITIONAL_INFORMATION_REQUIRED";
      readonly assigneeRef?: never;
      readonly expectedVersion: number;
    }
  | {
      readonly reasonCode: "CASE_REVIEW_RESUMED";
      readonly targetStatus: "IN_REVIEW";
      readonly assigneeRef?: never;
      readonly expectedVersion: number;
    };

/**
 * The two assignee-change requests Backend can accept.
 *
 * `null` is the release command and only `CASE_ASSIGNEE_RELEASED` may carry it.
 * A UUID is an assignment or a reassignment, and which of the two applies
 * depends on the case's *current* assignee - something Backend knows and this
 * client does not - so both of those reasons stay open to a UUID.
 */
export type CaseAssigneeChangeRequest =
  | {
      readonly reasonCode: "CASE_ASSIGNEE_ASSIGNED" | "CASE_ASSIGNEE_CHANGED";
      readonly assigneeRef: string;
      readonly expectedVersion: number;
    }
  | {
      readonly reasonCode: "CASE_ASSIGNEE_RELEASED";
      readonly assigneeRef: null;
      readonly expectedVersion: number;
    };

export interface CaseResolutionRequest {
  readonly finalDisposition: CaseFinalDisposition;
  readonly reasonCode: typeof CASE_RESOLUTION_REASON;
  readonly expectedVersion: number;
}

function isNullableFinalDisposition(value: unknown): value is CaseFinalDisposition | null {
  return value === null || isEnumMember(value, CASE_FINAL_DISPOSITIONS);
}

/** A Java `long` count: exact, non-negative, and never silently rounded. */
function isNonNegativeLong(value: unknown): value is number {
  return isSafeLong(value) && value >= 0;
}

const LIST_ITEM_KEYS: readonly string[] = [
  "caseId",
  "caseStatus",
  "finalDisposition",
  "assigneeRef",
  "relatedTransactionCount",
  "createdAt",
  "lastChangedAt",
];

function isCaseListItem(value: unknown): value is CaseListItem {
  if (!isObjectWithExactKeys(value, LIST_ITEM_KEYS)) {
    return false;
  }
  return (
    isUuidV4String(value.caseId) &&
    isEnumMember(value.caseStatus, CASE_STATUSES) &&
    isNullableFinalDisposition(value.finalDisposition) &&
    isNullableOpaqueRefString(value.assigneeRef) &&
    isNonNegativeLong(value.relatedTransactionCount) &&
    isUtcInstantString(value.createdAt) &&
    isUtcInstantString(value.lastChangedAt)
  );
}

const DETAIL_KEYS: readonly string[] = [
  "caseId",
  "caseStatus",
  "finalDisposition",
  "assigneeRef",
  "relatedTransactionCount",
  "createdAt",
  "reviewStartedAt",
  "closedAt",
  "lastChangedAt",
  "concurrencyVersion",
];

function isCaseDetail(value: unknown): value is CaseDetail {
  if (!isObjectWithExactKeys(value, DETAIL_KEYS)) {
    return false;
  }
  return (
    isUuidV4String(value.caseId) &&
    isEnumMember(value.caseStatus, CASE_STATUSES) &&
    isNullableFinalDisposition(value.finalDisposition) &&
    isNullableOpaqueRefString(value.assigneeRef) &&
    isNonNegativeLong(value.relatedTransactionCount) &&
    isUtcInstantString(value.createdAt) &&
    isNullableUtcInstantString(value.reviewStartedAt) &&
    isNullableUtcInstantString(value.closedAt) &&
    isUtcInstantString(value.lastChangedAt) &&
    isNonNegativeLong(value.concurrencyVersion)
  );
}

const MUTATION_KEYS: readonly string[] = [
  "caseId",
  "caseStatus",
  "finalDisposition",
  "assigneeRef",
  "reviewStartedAt",
  "closedAt",
  "lastChangedAt",
  "concurrencyVersion",
  "traceId",
];

export function isCaseMutation(value: unknown): value is CaseMutation {
  if (!isObjectWithExactKeys(value, MUTATION_KEYS)) {
    return false;
  }
  return (
    isUuidV4String(value.caseId) &&
    isEnumMember(value.caseStatus, CASE_STATUSES) &&
    isNullableFinalDisposition(value.finalDisposition) &&
    isNullableOpaqueRefString(value.assigneeRef) &&
    isNullableUtcInstantString(value.reviewStartedAt) &&
    isNullableUtcInstantString(value.closedAt) &&
    isUtcInstantString(value.lastChangedAt) &&
    isNonNegativeLong(value.concurrencyVersion) &&
    isTraceIdString(value.traceId)
  );
}

export function isCaseListPage(value: unknown): value is CaseListPage {
  if (!isObjectWithExactKeys(value, ["content", "page", "traceId"])) {
    return false;
  }
  if (
    !isArrayOf(value.content, isCaseListItem) ||
    !isPageMetadata(value.page) ||
    !isTraceIdString(value.traceId)
  ) {
    return false;
  }
  return isConsistentPageMetadata(value.page, value.content.length);
}

export function isCaseDetailEnvelope(value: unknown): value is CaseDetailEnvelope {
  if (!isObjectWithExactKeys(value, ["case", "traceId"])) {
    return false;
  }
  return isCaseDetail(value.case) && isTraceIdString(value.traceId);
}

/**
 * The optimistic-locking token the caller read from a case detail or a previous
 * mutation. A non-integer, a negative value or one past
 * `Number.MAX_SAFE_INTEGER` is refused: an approximate version is not a version,
 * and sending one would ask Backend to compare against a number the client
 * never actually held.
 */
function requireExpectedVersion(value: unknown): number {
  if (!isSafeLong(value) || value < 0) {
    throw new RequestNotAllowedError();
  }
  return value;
}

/**
 * A new-write assignee reference: exactly 36 ASCII characters of canonical
 * lowercase UUID v4, never trimmed or lowercased into shape.
 */
function requireAssigneeRef(value: unknown): string {
  if (!isUuidV4String(value)) {
    throw new RequestNotAllowedError();
  }
  return value;
}

function requireChoice<T extends string>(value: unknown, allowed: readonly T[]): T {
  if (!isEnumMember(value, allowed)) {
    throw new RequestNotAllowedError();
  }
  return value;
}

/** Backend가 사건 목록 page 생략 시 적용하는 기본값. URL에는 싣지 않고 응답 결합 기대값으로만 쓴다 (Issue #299). */
const DEFAULT_CASE_LIST_PAGE = 0;

/** Backend가 사건 목록 size 생략 시 적용하는 기본값. URL에는 싣지 않고 응답 결합 기대값으로만 쓴다 (Issue #299). */
const DEFAULT_CASE_LIST_SIZE = 20;

/**
 * 사건 목록 요청 1건의 URL query 생성에 쓰는 request-local read-through view (Issue #299, Issue #291 방식).
 *
 * 원본 query를 복제·동결·변경하지 않는다. view는 property 값 조회(`get`)만 가로채고 prototype·own key·
 * descriptor·property 존재 여부 조회는 원본에 그대로 위임하므로, `buildQueryValues()`의 구조 검증 결과와
 * 평가 순서는 원본을 직접 넘길 때와 같다. 모든 값은 원본 query를 receiver로 평가하므로 다른 accessor의
 * `this`·identity·private storage·side effect도 바뀌지 않는다.
 *
 * page·size만 view에서 처음 직접 읽힌 값을 `consumed`에 저장하고, 같은 요청에서 다시 직접 읽히면 그 값을
 * 돌려준다. 다른 accessor가 원본 receiver에서 내부적으로 읽는 `this.page`는 가로채지 않는다. filter·sort
 * 값은 저장하지 않는다. `consumed`에 없거나 `undefined`인 page·size는 URL에서 빠진 값이다.
 *
 * 객체가 아닌 query는 Proxy로 감쌀 수 없고 읽을 property도 없으므로 그대로 넘겨 기존 거부 경로를 유지한다.
 */
function createCaseListQueryView(query: CaseListQuery | undefined): {
  readonly view: unknown;
  readonly consumed: ReadonlyMap<"page" | "size", unknown>;
} {
  const consumed = new Map<"page" | "size", unknown>();
  if (typeof query !== "object" || query === null) {
    return { view: query, consumed };
  }
  const view = new Proxy(query, {
    get(target, property) {
      if (property !== "page" && property !== "size") {
        const value: unknown = Reflect.get(target, property, target);
        return value;
      }
      if (!consumed.has(property)) {
        const value: unknown = Reflect.get(target, property, target);
        consumed.set(property, value);
      }
      return consumed.get(property);
    },
  });
  return { view, consumed };
}

/**
 * `GET /api/v1/cases`.
 *
 * 성공 응답은 형식 검증을 통과한 뒤 요청의 effective pagination에 결합한다 (Issue #299). URL query는
 * `createCaseListQueryView()`의 request-local view로 만들고, validator는 그 URL 생성에 실제 사용된
 * page·size만 기대한다. 두 값은 credential 조회 전에 확정된다. 생략되거나 `undefined`인 page·size는
 * 기존처럼 URL에서 빠지고 validator만 Backend 기본값 page=0·size=20을 기대한다. 응답
 * `page.number`·`page.size`는 숫자 `===`로만 비교하고 문자열 변환·clamp·반올림·정규화를 하지 않는다.
 * 형식이 유효한 다른 page·size 응답도 원문을 반사하지 않는 고정 `InvalidResponseError`가 된다.
 * 공개 `isCaseListPage()`는 형식 검증 전용으로 그대로 둔다.
 */
export async function fetchCaseList(
  authClient: CredentialAuthClient,
  query?: CaseListQuery,
  signal?: AbortSignal,
): Promise<ApiResult<CaseListPage>> {
  const { view, consumed } = createCaseListQueryView(query);
  const requestQuery = buildQueryValues("case-list", view);
  const consumedPage = consumed.get("page");
  const consumedSize = consumed.get("size");
  const expectedPage = consumedPage === undefined ? DEFAULT_CASE_LIST_PAGE : consumedPage;
  const expectedSize = consumedSize === undefined ? DEFAULT_CASE_LIST_SIZE : consumedSize;
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "case-list",
    query: requestQuery,
    expectedStatus: 200,
    validate: (body): body is CaseListPage =>
      isCaseListPage(body) &&
      body.page.number === expectedPage &&
      body.page.size === expectedSize,
    signal,
  });
  return { data: result.data, traceId: resolveTraceId(result.traceId, result.data.traceId) };
}

/**
 * `GET /api/v1/cases/{caseId}`. Accepts no query argument at all.
 *
 * 성공 응답은 형식 검증을 통과한 뒤 요청 path의 `caseId`에 결합한다 (Issue #289). 응답 `case.caseId`를
 * 요청 원문과 `===`로만 비교하고 trim·대소문자 변환·UUID 재파싱을 하지 않으므로, 형식이 유효한 다른
 * 사건도 원문을 반사하지 않는 고정 `InvalidResponseError`가 된다.
 */
export async function fetchCaseDetail(
  authClient: CredentialAuthClient,
  caseId: string,
  signal?: AbortSignal,
): Promise<ApiResult<CaseDetailEnvelope>> {
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "case-detail",
    params: { caseId },
    expectedStatus: 200,
    validate: (body): body is CaseDetailEnvelope =>
      isCaseDetailEnvelope(body) && body.case.caseId === caseId,
    signal,
  });
  return { data: result.data, traceId: resolveTraceId(result.traceId, result.data.traceId) };
}

/**
 * Rebuilds a status-change body from validated values, enforcing the
 * reason/target/assignee combination Backend actually accepts.
 *
 * Exported so the combination table can be exercised directly, including every
 * combination that must never reach a URL.
 */
export function buildCaseStatusChangeBody(request: unknown): Record<string, unknown> {
  const fields = readExactRequestFields(
    request,
    ["targetStatus", "reasonCode", "expectedVersion"],
    ["assigneeRef"],
  );

  const reasonCode = requireChoice(fields.reasonCode, CASE_STATUS_CHANGE_REASONS);
  const contract = STATUS_CHANGE_CONTRACT[reasonCode];
  const expectedVersion = requireExpectedVersion(fields.expectedVersion);

  // The reason names the transition, so a mismatched target is not a
  // different-but-valid request - it is one Backend answers 409 to.
  if (fields.targetStatus !== contract.targetStatus) {
    throw new RequestNotAllowedError();
  }

  const assigneeRefPresent = Object.prototype.hasOwnProperty.call(fields, "assigneeRef");
  if (contract.assigneeRefRequired) {
    if (!assigneeRefPresent) {
      throw new RequestNotAllowedError();
    }
    return {
      targetStatus: contract.targetStatus,
      assigneeRef: requireAssigneeRef(fields.assigneeRef),
      reasonCode,
      expectedVersion,
    };
  }

  // Presence alone is the violation here, matching
  // `rejectStatusAssigneeCombination`: an explicit `null` is refused too.
  if (assigneeRefPresent) {
    throw new RequestNotAllowedError();
  }
  return { targetStatus: contract.targetStatus, reasonCode, expectedVersion };
}

/**
 * Rebuilds an assignee-change body, enforcing the reason/value pairing.
 *
 * `null` releases the assignee and only `CASE_ASSIGNEE_RELEASED` may carry it;
 * a UUID may carry either `CASE_ASSIGNEE_ASSIGNED` or `CASE_ASSIGNEE_CHANGED`.
 */
export function buildCaseAssigneeChangeBody(request: unknown): Record<string, unknown> {
  const fields = readExactRequestFields(request, [
    "assigneeRef",
    "reasonCode",
    "expectedVersion",
  ]);

  const reasonCode = requireChoice(fields.reasonCode, CASE_ASSIGNEE_CHANGE_REASONS);
  const expectedVersion = requireExpectedVersion(fields.expectedVersion);
  const releasing = fields.assigneeRef === null;

  if (releasing !== (reasonCode === "CASE_ASSIGNEE_RELEASED")) {
    throw new RequestNotAllowedError();
  }

  return {
    assigneeRef: releasing ? null : requireAssigneeRef(fields.assigneeRef),
    reasonCode,
    expectedVersion,
  };
}

/** Rebuilds a resolution body. One disposition, one reason code, one version. */
export function buildCaseResolutionBody(request: unknown): Record<string, unknown> {
  const fields = readExactRequestFields(request, [
    "finalDisposition",
    "reasonCode",
    "expectedVersion",
  ]);

  return {
    finalDisposition: requireChoice(fields.finalDisposition, CASE_FINAL_DISPOSITIONS),
    reasonCode: requireChoice(fields.reasonCode, [CASE_RESOLUTION_REASON] as const),
    expectedVersion: requireExpectedVersion(fields.expectedVersion),
  };
}

/**
 * Re-validates the detail snapshot a workflow command is being bound to.
 *
 * The snapshot has already crossed `useCaseDetail` in production, but the API
 * client is also a public boundary and must fail closed when called from
 * untyped code. Requiring the exact ten-field detail shape before credential
 * lookup means a caller cannot use a partial or decorated object to relax the
 * response binding below.
 */
function requireWorkflowBaseline(caseId: string, value: unknown): CaseDetail {
  if (!isCaseDetail(value) || value.caseId !== caseId) {
    throw new RequestNotAllowedError();
  }
  return value;
}

/**
 * 사건 최종 판정을 보낼 수 있는 detail인지 판정한다.
 *
 * Backend `FraudCaseWorkflowService.resolve`와 `FraudCase.resolve`의 사전조건을 옮긴다:
 * `IN_REVIEW`, 담당자와 최초 조사 시각 존재, 아직 없는 판정과 종결 시각. 여기에 successor
 * version(`expectedVersion + 1`)도 JavaScript safe integer여야 한다는 client 조건을 더한다.
 * 화면 노출, hook submit 검증, API baseline 검증이 이 판정 하나를 공유하며 Backend의 최종
 * authorization·업무 검증·optimistic concurrency를 대체하지 않는다.
 */
export function isResolvableCaseDetail(detail: CaseDetail): boolean {
  return (
    detail.caseStatus === "IN_REVIEW" &&
    detail.assigneeRef !== null &&
    detail.reviewStartedAt !== null &&
    detail.finalDisposition === null &&
    detail.closedAt === null &&
    isSafeLong(detail.concurrencyVersion) &&
    detail.concurrencyVersion >= 0 &&
    detail.concurrencyVersion < Number.MAX_SAFE_INTEGER
  );
}

function sameNullableString(left: string | null, right: string | null): boolean {
  return left === right;
}

/** Refuses a status command that is not valid for the submitted snapshot. */
function assertStatusCommandMatchesBaseline(
  baseline: CaseDetail,
  body: Readonly<Record<string, unknown>>,
): void {
  if (
    body.expectedVersion !== baseline.concurrencyVersion ||
    (body.expectedVersion as number) >= Number.MAX_SAFE_INTEGER
  ) {
    throw new RequestNotAllowedError();
  }

  const reasonCode = body.reasonCode;
  const allowed =
    (baseline.caseStatus === "OPEN" && reasonCode === "CASE_REVIEW_STARTED") ||
    (baseline.caseStatus === "IN_REVIEW" &&
      reasonCode === "CASE_ADDITIONAL_INFORMATION_REQUESTED") ||
    (baseline.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" &&
      baseline.assigneeRef !== null &&
      reasonCode === "CASE_REVIEW_RESUMED");
  if (!allowed) {
    throw new RequestNotAllowedError();
  }
}

/** Refuses an assignee command that is not valid for the submitted snapshot. */
function assertAssigneeCommandMatchesBaseline(
  baseline: CaseDetail,
  body: Readonly<Record<string, unknown>>,
): void {
  if (
    body.expectedVersion !== baseline.concurrencyVersion ||
    (body.expectedVersion as number) >= Number.MAX_SAFE_INTEGER ||
    baseline.caseStatus === "OPEN" ||
    baseline.caseStatus === "CLOSED"
  ) {
    throw new RequestNotAllowedError();
  }

  const after = body.assigneeRef;
  const before = baseline.assigneeRef;
  const reasonCode = body.reasonCode;
  if (after === null) {
    if (
      baseline.caseStatus !== "ADDITIONAL_INFORMATION_REQUIRED" ||
      before === null ||
      reasonCode !== "CASE_ASSIGNEE_RELEASED"
    ) {
      throw new RequestNotAllowedError();
    }
    return;
  }

  if (typeof after !== "string" || after === before) {
    throw new RequestNotAllowedError();
  }
  if (baseline.caseStatus === "IN_REVIEW" && before === null) {
    throw new RequestNotAllowedError();
  }
  const expectedReason = before === null ? "CASE_ASSIGNEE_ASSIGNED" : "CASE_ASSIGNEE_CHANGED";
  if (reasonCode !== expectedReason) {
    throw new RequestNotAllowedError();
  }
}

/** 제출 snapshot에서 성공할 수 없는 resolution 명령을 credential 조회 전에 거부한다. */
function assertResolutionCommandMatchesBaseline(
  baseline: CaseDetail,
  body: Readonly<Record<string, unknown>>,
): void {
  if (body.expectedVersion !== baseline.concurrencyVersion || !isResolvableCaseDetail(baseline)) {
    throw new RequestNotAllowedError();
  }
}

function isBoundStatusMutation(
  value: unknown,
  requestedCaseId: string,
  baseline: CaseDetail,
  body: Readonly<Record<string, unknown>>,
): value is CaseMutation {
  if (!isCaseMutation(value)) {
    return false;
  }
  const expectedVersion = body.expectedVersion as number;
  if (
    value.caseId !== requestedCaseId ||
    value.concurrencyVersion !== expectedVersion + 1 ||
    value.caseStatus !== body.targetStatus ||
    value.finalDisposition !== baseline.finalDisposition ||
    !sameNullableString(value.closedAt, baseline.closedAt)
  ) {
    return false;
  }

  if (body.reasonCode === "CASE_REVIEW_STARTED") {
    return (
      value.assigneeRef === body.assigneeRef &&
      (baseline.reviewStartedAt === null
        ? value.reviewStartedAt !== null
        : value.reviewStartedAt === baseline.reviewStartedAt)
    );
  }

  return (
    value.assigneeRef === baseline.assigneeRef &&
    value.reviewStartedAt === baseline.reviewStartedAt
  );
}

function isBoundAssigneeMutation(
  value: unknown,
  requestedCaseId: string,
  baseline: CaseDetail,
  body: Readonly<Record<string, unknown>>,
): value is CaseMutation {
  if (!isCaseMutation(value)) {
    return false;
  }
  const expectedVersion = body.expectedVersion as number;
  return (
    value.caseId === requestedCaseId &&
    value.concurrencyVersion === expectedVersion + 1 &&
    value.caseStatus === baseline.caseStatus &&
    value.assigneeRef === body.assigneeRef &&
    value.reviewStartedAt === baseline.reviewStartedAt &&
    value.finalDisposition === baseline.finalDisposition &&
    value.closedAt === baseline.closedAt
  );
}

/**
 * resolution 성공 응답을 요청과 baseline에 결합한다.
 *
 * `FraudCase.resolve`는 CLOSED, 요청한 판정, 같은 시각의 `closedAt`·`lastChangedAt`을 만들고
 * 담당자와 최초 조사 시각은 바꾸지 않는다. baseline version은 이미 명령의 `expectedVersion`과
 * 같음이 확인되었으므로 successor는 baseline version + 1이다.
 */
function isBoundResolutionMutation(
  value: unknown,
  requestedCaseId: string,
  baseline: CaseDetail,
  body: Readonly<Record<string, unknown>>,
): value is CaseMutation {
  if (!isCaseMutation(value)) {
    return false;
  }
  return (
    value.caseId === requestedCaseId &&
    value.concurrencyVersion === baseline.concurrencyVersion + 1 &&
    value.caseStatus === "CLOSED" &&
    value.finalDisposition !== null &&
    value.finalDisposition === body.finalDisposition &&
    value.closedAt !== null &&
    value.closedAt === value.lastChangedAt &&
    value.assigneeRef === baseline.assigneeRef &&
    value.reviewStartedAt === baseline.reviewStartedAt
  );
}

/**
 * 상태·담당자 PATCH와 resolution POST의 마지막 optional options 객체. 기존 positional 인자의 순서는
 * 바꾸지 않는다.
 */
export interface CaseWorkflowRequestOptions {
  /**
   * authorized transport에 이름 있는 option으로 그대로 전달하는 실제 fetch 직전 최종 guard.
   * 이 module은 guard를 직접 실행하거나 destination·credential 판정을 다시 구현하지 않는다.
   */
  readonly assertDispatchAllowed?: () => void;
}

/**
 * `PATCH /api/v1/cases/{caseId}/status`.
 *
 * The body sent is a fresh plain object built from validated values, not the
 * caller's object: whatever else that object carried - an inherited field, a
 * getter, a stray `actorId` - has no path onto the wire.
 */
export async function changeCaseStatus(
  authClient: CredentialAuthClient,
  caseId: string,
  request: CaseStatusChangeRequest,
  currentDetail: CaseDetail,
  signal?: AbortSignal,
  options?: CaseWorkflowRequestOptions,
): Promise<ApiResult<CaseMutation>> {
  const body = buildCaseStatusChangeBody(request);
  const baseline = requireWorkflowBaseline(caseId, currentDetail);
  assertStatusCommandMatchesBaseline(baseline, body);
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "case-status-change",
    params: { caseId },
    body,
    expectedStatus: 200,
    validate: (value): value is CaseMutation =>
      isBoundStatusMutation(value, caseId, baseline, body),
    signal,
    assertDispatchAllowed: options?.assertDispatchAllowed,
  });
  // 검증을 통과한 9개 필드만 새 plain object에 복사해 transport가 돌려준 raw DTO와 참조를 공유하지 않는다.
  const validated = result.data;
  const data: CaseMutation = {
    caseId: validated.caseId,
    caseStatus: validated.caseStatus,
    finalDisposition: validated.finalDisposition,
    assigneeRef: validated.assigneeRef,
    reviewStartedAt: validated.reviewStartedAt,
    closedAt: validated.closedAt,
    lastChangedAt: validated.lastChangedAt,
    concurrencyVersion: validated.concurrencyVersion,
    traceId: validated.traceId,
  };
  return { data, traceId: resolveTraceId(result.traceId, data.traceId) };
}

/**
 * `PATCH /api/v1/cases/{caseId}/assignee`.
 *
 * `assigneeRef` is required and explicitly nullable: `null` releases the
 * assignee, and omitting the key is a different request that Backend refuses
 * outright. Nothing here turns one into the other.
 */
export async function changeCaseAssignee(
  authClient: CredentialAuthClient,
  caseId: string,
  request: CaseAssigneeChangeRequest,
  currentDetail: CaseDetail,
  signal?: AbortSignal,
  options?: CaseWorkflowRequestOptions,
): Promise<ApiResult<CaseMutation>> {
  const body = buildCaseAssigneeChangeBody(request);
  const baseline = requireWorkflowBaseline(caseId, currentDetail);
  assertAssigneeCommandMatchesBaseline(baseline, body);
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "case-assignee-change",
    params: { caseId },
    body,
    expectedStatus: 200,
    validate: (value): value is CaseMutation =>
      isBoundAssigneeMutation(value, caseId, baseline, body),
    signal,
    assertDispatchAllowed: options?.assertDispatchAllowed,
  });
  // 검증을 통과한 9개 필드만 새 plain object에 복사해 transport가 돌려준 raw DTO와 참조를 공유하지 않는다.
  const validated = result.data;
  const data: CaseMutation = {
    caseId: validated.caseId,
    caseStatus: validated.caseStatus,
    finalDisposition: validated.finalDisposition,
    assigneeRef: validated.assigneeRef,
    reviewStartedAt: validated.reviewStartedAt,
    closedAt: validated.closedAt,
    lastChangedAt: validated.lastChangedAt,
    concurrencyVersion: validated.concurrencyVersion,
    traceId: validated.traceId,
  };
  return { data, traceId: resolveTraceId(result.traceId, data.traceId) };
}

/**
 * `POST /api/v1/cases/{caseId}/resolution`.
 *
 * Answers 200, not 201: the resolution is a state change on an existing case
 * rather than a new addressable resource, and Backend returns no `Location`.
 *
 * 상태·담당자 PATCH와 같은 positional 순서(현재 detail baseline, AbortSignal, options)를 쓴다.
 * 종결 가능한 baseline과 그 version에 결합된 명령만 credential 조회 전에 통과하고, 성공 응답은
 * 요청 caseId·successor version·CLOSED·요청 판정·종결 시각과 담당자·최초 조사 시각 보존에
 * 결합해 검증한다. 불일치는 원문을 반사하지 않는 고정 `InvalidResponseError`다.
 */
export async function createCaseResolution(
  authClient: CredentialAuthClient,
  caseId: string,
  request: CaseResolutionRequest,
  currentDetail: CaseDetail,
  signal?: AbortSignal,
  options?: CaseWorkflowRequestOptions,
): Promise<ApiResult<CaseMutation>> {
  const body = buildCaseResolutionBody(request);
  const baseline = requireWorkflowBaseline(caseId, currentDetail);
  assertResolutionCommandMatchesBaseline(baseline, body);
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "case-resolution-create",
    params: { caseId },
    body,
    expectedStatus: 200,
    validate: (value): value is CaseMutation =>
      isBoundResolutionMutation(value, caseId, baseline, body),
    signal,
    assertDispatchAllowed: options?.assertDispatchAllowed,
  });
  // 검증을 통과한 9개 필드만 새 plain object에 복사해 transport가 돌려준 raw DTO와 참조를 공유하지 않는다.
  const validated = result.data;
  const data: CaseMutation = {
    caseId: validated.caseId,
    caseStatus: validated.caseStatus,
    finalDisposition: validated.finalDisposition,
    assigneeRef: validated.assigneeRef,
    reviewStartedAt: validated.reviewStartedAt,
    closedAt: validated.closedAt,
    lastChangedAt: validated.lastChangedAt,
    concurrencyVersion: validated.concurrencyVersion,
    traceId: validated.traceId,
  };
  return { data, traceId: resolveTraceId(result.traceId, data.traceId) };
}
