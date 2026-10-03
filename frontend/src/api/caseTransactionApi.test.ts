import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse } from "../test/mockFetch";
import { InvalidResponseError, RequestNotAllowedError } from "./errors";
import { fetchCaseTransactionPage, isCaseTransactionPage } from "./caseTransactionApi";

const CASE_ID = "20000000-0000-4000-9000-000000000003";
const TRANSACTION_ID = "91a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5003";
const client = () => createFakeAuthClient({ initialSession: {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f", roles: ["FDS_VIEWER"],
} });
const body = (items = [TRANSACTION_ID], page = 0) => ({
  caseId: CASE_ID,
  content: items.map((transactionId) => ({ transactionId })),
  page: { number: page, size: 20, totalElements: items.length,
    totalPages: items.length === 0 ? 0 : 1, first: page === 0, last: true },
  traceId: "trace_case_transactions_01",
});

beforeEach(() => vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080"));
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); });

it("accepts the exact ID-only page and sends only page and size", async () => {
  const fetcher = vi.fn().mockResolvedValue(jsonResponse(body()));
  vi.stubGlobal("fetch", fetcher);
  const page = await fetchCaseTransactionPage(client(), CASE_ID, { page: 0, size: 20 });
  expect(page.content).toEqual([{ transactionId: TRANSACTION_ID }]);
  const request = fetcher.mock.calls[0][0] as Request;
  expect(new URL(request.url).pathname).toBe(`/api/v1/cases/${CASE_ID}/transactions`);
  expect(new URL(request.url).search).toBe("?page=0&size=20");
});

it("rejects extra fields, mismatched case, duplicate IDs and inconsistent count", async () => {
  for (const invalid of [
    { ...body(), content: [{ transactionId: TRANSACTION_ID, linkedAt: "secret" }] },
    { ...body(), caseId: "20000000-0000-4000-9000-000000000099" },
    { ...body(), content: [{ transactionId: TRANSACTION_ID }, { transactionId: TRANSACTION_ID }] },
    { ...body(), page: { ...body().page, totalElements: 2 } },
  ]) {
    expect(isCaseTransactionPage(invalid) && invalid.caseId === CASE_ID).toBe(false);
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(invalid)));
    await expect(fetchCaseTransactionPage(client(), CASE_ID)).rejects.toBeInstanceOf(InvalidResponseError);
  }
});

it("rejects invalid and overflowing query without a network request", async () => {
  const fetcher = vi.fn();
  vi.stubGlobal("fetch", fetcher);
  await expect(fetchCaseTransactionPage(client(), CASE_ID, { page: 1073741824, size: 2 }))
    .rejects.toBeInstanceOf(RequestNotAllowedError);
  await expect(fetchCaseTransactionPage(client(), CASE_ID, { page: -1 }))
    .rejects.toBeInstanceOf(RequestNotAllowedError);
  expect(fetcher).not.toHaveBeenCalled();
});
