import type { CredentialAuthClient } from "../auth/authClient";
import { sendAuthorizedBackendRequest } from "./authorizedClient";
import { buildQueryValues, isConsistentPageMetadata, isPageMetadata, type PageMetadata } from "./pagination";
import { isArrayOf, isObjectWithExactKeys, isUuidV4String, isTraceIdString } from "./responseValidation";

export interface CaseTransactionPage {
  readonly caseId: string;
  readonly content: readonly { readonly transactionId: string }[];
  readonly page: PageMetadata;
  readonly traceId: string;
}

export interface CaseTransactionQuery {
  readonly page?: number;
  readonly size?: number;
}

export function isCaseTransactionPage(value: unknown): value is CaseTransactionPage {
  if (!isObjectWithExactKeys(value, ["caseId", "content", "page", "traceId"])) return false;
  return isUuidV4String(value.caseId) &&
    isArrayOf(value.content, (item): item is { transactionId: string } =>
      isObjectWithExactKeys(item, ["transactionId"]) && isUuidV4String(item.transactionId)) &&
    isPageMetadata(value.page) &&
    isConsistentPageMetadata(value.page, value.content.length) &&
    isTraceIdString(value.traceId) &&
    new Set(value.content.map((item) => item.transactionId)).size === value.content.length;
}

export async function fetchCaseTransactionPage(
  authClient: CredentialAuthClient,
  caseId: string,
  query: CaseTransactionQuery = {},
  signal?: AbortSignal,
): Promise<CaseTransactionPage> {
  const values = buildQueryValues("case-transaction-list", query);
  const requestedPage = query.page ?? 0;
  const requestedSize = query.size ?? 20;
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "case-transaction-list",
    params: { caseId },
    query: values,
    expectedStatus: 200,
    validate: (body: unknown): body is CaseTransactionPage =>
      isCaseTransactionPage(body) && body.caseId === caseId &&
      body.page.number === requestedPage && body.page.size === requestedSize,
    signal,
  });
  const data = result.data;
  return {
    caseId: data.caseId,
    content: data.content.map((item) => ({ transactionId: item.transactionId })),
    page: { ...data.page },
    traceId: data.traceId,
  };
}
