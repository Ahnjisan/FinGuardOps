import type { CredentialAuthClient } from "../auth/authClient";
import { sendAuthorizedBackendRequest } from "./authorizedClient";
import {
  TRANSACTION_LIST_SORTS,
  TRANSACTION_PROCESSING_STATUSES,
  TRANSACTION_TYPES,
} from "./backendEndpoints";
import {
  buildQueryValues,
  isConsistentPageMetadata,
  isPageMetadata,
  type PageMetadata,
} from "./pagination";
import {
  isArrayOf,
  isEnumMember,
  isIntegerAmountString,
  isNullableOpaqueRefString,
  isObjectWithExactKeys,
  isOpaqueRefString,
  isTraceIdString,
  isUtcInstantString,
  isUuidV4String,
  resolveTraceId,
  type ApiResult,
} from "./responseValidation";

/**
 * The two approved USER transaction endpoints, as typed calls.
 *
 * `POST /api/v1/transactions` is deliberately unreachable from here: intake is
 * a SERVICE endpoint and has no key in the registry, so no argument to anything
 * in this module can produce it.
 *
 * The filter vocabulary is not defined here. `transaction-list` declares it in
 * the endpoint registry, together with the rule each value must satisfy, so the
 * typed call below and the URL re-verification in the transport and in the
 * credential capability all execute one contract rather than three copies of
 * it.
 */

export { TRANSACTION_TYPES, TRANSACTION_PROCESSING_STATUSES, TRANSACTION_LIST_SORTS };

/** `TransactionType`. */
export type TransactionType = (typeof TRANSACTION_TYPES)[number];

/** `TransactionProcessingStatus`. */
export type TransactionProcessingStatus = (typeof TRANSACTION_PROCESSING_STATUSES)[number];

/** The only two sorts `TransactionQueryValidator` accepts. */
export type TransactionListSort = (typeof TRANSACTION_LIST_SORTS)[number];

/**
 * `TransactionChannel`. Returned by the detail endpoint and never a filter, so
 * unlike the three above it is not part of the registry's query contract.
 */
export const TRANSACTION_CHANNELS = [
  "MOBILE_BANKING",
  "OPEN_BANKING",
  "ATM",
  "CORE_BANKING",
] as const;
export type TransactionChannel = (typeof TRANSACTION_CHANNELS)[number];

/**
 * The only currency the contract defines.
 *
 * `transaction-detection-api.md` admits `KRW` and nothing else, and the intake
 * validator refuses every other code, so a stored row cannot carry one. A
 * response claiming `USD` did not come from this Backend, and a permissive
 * ISO 4217 shape check would let it through as though it had.
 */
export const TRANSACTION_CURRENCY_CODE = "KRW";
export type TransactionCurrencyCode = typeof TRANSACTION_CURRENCY_CODE;

export interface TransactionListItem {
  readonly transactionId: string;
  readonly transactionType: TransactionType;
  /** Decimal integer string, at most fifteen digits. Never parsed into a `number`. */
  readonly amount: string;
  readonly currencyCode: TransactionCurrencyCode;
  readonly occurredAt: string;
  readonly externalCustomerRef: string;
  readonly senderAccountRef: string;
  readonly recipientAccountRef: string | null;
  readonly processingStatus: TransactionProcessingStatus;
  readonly createdAt: string;
}

export interface TransactionListPage {
  readonly content: readonly TransactionListItem[];
  readonly page: PageMetadata;
  readonly traceId: string;
}

export interface TransactionDetail {
  readonly transactionId: string;
  readonly transactionType: TransactionType;
  /** Decimal integer string, at most fifteen digits. Never parsed into a `number`. */
  readonly amount: string;
  readonly currencyCode: TransactionCurrencyCode;
  readonly occurredAt: string;
  readonly externalCustomerRef: string;
  readonly senderAccountRef: string;
  readonly recipientAccountRef: string | null;
  readonly channel: TransactionChannel;
  readonly deviceRef: string | null;
  readonly processingStatus: TransactionProcessingStatus;
  readonly createdAt: string;
  readonly updatedAt: string;
}

export interface TransactionDetailEnvelope {
  readonly transaction: TransactionDetail;
  readonly traceId: string;
}

export interface TransactionListQuery {
  readonly occurredAtFrom?: string;
  readonly occurredAtTo?: string;
  readonly transactionType?: TransactionType;
  readonly processingStatus?: TransactionProcessingStatus;
  readonly externalCustomerRef?: string;
  readonly accountRef?: string;
  readonly page?: number;
  readonly size?: number;
  readonly sort?: TransactionListSort;
}

export type { ApiResult };

function isTransactionCurrencyCode(value: unknown): value is TransactionCurrencyCode {
  return value === TRANSACTION_CURRENCY_CODE;
}

const LIST_ITEM_KEYS: readonly string[] = [
  "transactionId",
  "transactionType",
  "amount",
  "currencyCode",
  "occurredAt",
  "externalCustomerRef",
  "senderAccountRef",
  "recipientAccountRef",
  "processingStatus",
  "createdAt",
];

function isTransactionListItem(value: unknown): value is TransactionListItem {
  if (!isObjectWithExactKeys(value, LIST_ITEM_KEYS)) {
    return false;
  }
  return (
    isUuidV4String(value.transactionId) &&
    isEnumMember(value.transactionType, TRANSACTION_TYPES) &&
    isIntegerAmountString(value.amount) &&
    isTransactionCurrencyCode(value.currencyCode) &&
    isUtcInstantString(value.occurredAt) &&
    isOpaqueRefString(value.externalCustomerRef) &&
    isOpaqueRefString(value.senderAccountRef) &&
    isNullableOpaqueRefString(value.recipientAccountRef) &&
    isEnumMember(value.processingStatus, TRANSACTION_PROCESSING_STATUSES) &&
    isUtcInstantString(value.createdAt)
  );
}

const DETAIL_KEYS: readonly string[] = [
  "transactionId",
  "transactionType",
  "amount",
  "currencyCode",
  "occurredAt",
  "externalCustomerRef",
  "senderAccountRef",
  "recipientAccountRef",
  "channel",
  "deviceRef",
  "processingStatus",
  "createdAt",
  "updatedAt",
];

function isTransactionDetail(value: unknown): value is TransactionDetail {
  if (!isObjectWithExactKeys(value, DETAIL_KEYS)) {
    return false;
  }
  return (
    isUuidV4String(value.transactionId) &&
    isEnumMember(value.transactionType, TRANSACTION_TYPES) &&
    isIntegerAmountString(value.amount) &&
    isTransactionCurrencyCode(value.currencyCode) &&
    isUtcInstantString(value.occurredAt) &&
    isOpaqueRefString(value.externalCustomerRef) &&
    isOpaqueRefString(value.senderAccountRef) &&
    isNullableOpaqueRefString(value.recipientAccountRef) &&
    isEnumMember(value.channel, TRANSACTION_CHANNELS) &&
    isNullableOpaqueRefString(value.deviceRef) &&
    isEnumMember(value.processingStatus, TRANSACTION_PROCESSING_STATUSES) &&
    isUtcInstantString(value.createdAt) &&
    isUtcInstantString(value.updatedAt)
  );
}

export function isTransactionListPage(value: unknown): value is TransactionListPage {
  if (!isObjectWithExactKeys(value, ["content", "page", "traceId"])) {
    return false;
  }
  if (
    !isArrayOf(value.content, isTransactionListItem) ||
    !isPageMetadata(value.page) ||
    !isTraceIdString(value.traceId)
  ) {
    return false;
  }
  return isConsistentPageMetadata(value.page, value.content.length);
}

export function isTransactionDetailEnvelope(value: unknown): value is TransactionDetailEnvelope {
  if (!isObjectWithExactKeys(value, ["transaction", "traceId"])) {
    return false;
  }
  return isTransactionDetail(value.transaction) && isTraceIdString(value.traceId);
}

/** Backend가 page 생략 시 적용하는 기본값. URL에는 싣지 않고 응답 결합 기대값으로만 쓴다 (Issue #291). */
const DEFAULT_TRANSACTION_LIST_PAGE = 0;

/** Backend가 size 생략 시 적용하는 기본값. URL에는 싣지 않고 응답 결합 기대값으로만 쓴다 (Issue #291). */
const DEFAULT_TRANSACTION_LIST_SIZE = 20;

/**
 * 요청 1건의 URL query 생성에 쓰는 request-local read-through view (Issue #291).
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
function createTransactionListQueryView(query: TransactionListQuery | undefined): {
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
 * `GET /api/v1/transactions`.
 *
 * The filters are validated against the registry's contract and encoded before
 * a URL exists, so a bad page number or an unknown sort costs zero credential
 * lookups and zero fetches. An inverted `occurredAt` range is refused here
 * rather than spent on the 422 the Backend would answer.
 *
 * 성공 응답은 형식 검증을 통과한 뒤 요청의 effective pagination에 결합한다 (Issue #291). URL query는
 * `createTransactionListQueryView()`의 request-local view로 만들고, validator는 그 URL 생성에 실제 사용된
 * page·size만 기대한다. 두 값은 credential 조회 전에 확정된다. 생략되거나 `undefined`인 page·size는
 * 기존처럼 URL에서 빠지고 validator만 Backend 기본값 page=0·size=20을 기대한다. 응답
 * `page.number`·`page.size`는 숫자 `===`로만 비교하고 문자열 변환·clamp·반올림·정규화를 하지 않으므로
 * `-0` 요청은 `0` 응답과 같다. 형식이 유효한 다른 page·size 응답도 원문을 반사하지 않는 고정
 * `InvalidResponseError`가 된다.
 */
export async function fetchTransactionList(
  authClient: CredentialAuthClient,
  query?: TransactionListQuery,
  signal?: AbortSignal,
): Promise<ApiResult<TransactionListPage>> {
  const { view, consumed } = createTransactionListQueryView(query);
  const requestQuery = buildQueryValues("transaction-list", view);
  const consumedPage = consumed.get("page");
  const consumedSize = consumed.get("size");
  const expectedPage = consumedPage === undefined ? DEFAULT_TRANSACTION_LIST_PAGE : consumedPage;
  const expectedSize = consumedSize === undefined ? DEFAULT_TRANSACTION_LIST_SIZE : consumedSize;
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "transaction-list",
    query: requestQuery,
    expectedStatus: 200,
    validate: (body): body is TransactionListPage =>
      isTransactionListPage(body) &&
      body.page.number === expectedPage &&
      body.page.size === expectedSize,
    signal,
  });
  return { data: result.data, traceId: resolveTraceId(result.traceId, result.data.traceId) };
}

/**
 * `GET /api/v1/transactions/{transactionId}`. Accepts no query argument at all.
 *
 * 성공 응답은 형식 검증을 통과한 뒤 요청 path의 `transactionId`에 결합한다 (Issue #287). 응답
 * `transaction.transactionId`를 요청 원문과 `===`로만 비교하고 trim·대소문자 변환·UUID 재파싱을 하지
 * 않으므로, 형식이 유효한 다른 거래도 원문을 반사하지 않는 고정 `InvalidResponseError`가 된다.
 */
export async function fetchTransactionDetail(
  authClient: CredentialAuthClient,
  transactionId: string,
  signal?: AbortSignal,
): Promise<ApiResult<TransactionDetailEnvelope>> {
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "transaction-detail",
    params: { transactionId },
    expectedStatus: 200,
    validate: (body): body is TransactionDetailEnvelope =>
      isTransactionDetailEnvelope(body) && body.transaction.transactionId === transactionId,
    signal,
  });
  return { data: result.data, traceId: resolveTraceId(result.traceId, result.data.traceId) };
}
