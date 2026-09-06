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

/**
 * `GET /api/v1/transactions`.
 *
 * The filters are validated against the registry's contract and encoded before
 * a URL exists, so a bad page number or an unknown sort costs zero credential
 * lookups and zero fetches. An inverted `occurredAt` range is refused here
 * rather than spent on the 422 the Backend would answer.
 */
export async function fetchTransactionList(
  authClient: CredentialAuthClient,
  query?: TransactionListQuery,
  signal?: AbortSignal,
): Promise<ApiResult<TransactionListPage>> {
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "transaction-list",
    query: buildQueryValues("transaction-list", query),
    expectedStatus: 200,
    validate: isTransactionListPage,
    signal,
  });
  return { data: result.data, traceId: resolveTraceId(result.traceId, result.data.traceId) };
}

/** `GET /api/v1/transactions/{transactionId}`. Accepts no query argument at all. */
export async function fetchTransactionDetail(
  authClient: CredentialAuthClient,
  transactionId: string,
  signal?: AbortSignal,
): Promise<ApiResult<TransactionDetailEnvelope>> {
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "transaction-detail",
    params: { transactionId },
    expectedStatus: 200,
    validate: isTransactionDetailEnvelope,
    signal,
  });
  return { data: result.data, traceId: resolveTraceId(result.traceId, result.data.traceId) };
}
