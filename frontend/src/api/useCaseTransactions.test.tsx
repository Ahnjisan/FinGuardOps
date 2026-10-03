import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, expect, it, vi } from "vitest";
import type { AuthSession } from "../auth/authClient";
import { AuthContext, type AuthContextValue } from "../auth/authContext";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { ForbiddenError, HttpError, UnauthorizedError } from "./errors";
import type { CaseTransactionPage } from "./caseTransactionApi";

const mocked = vi.hoisted(() => ({ fetch: vi.fn(), authClient: {} }));
vi.mock("./caseTransactionApi", () => ({ fetchCaseTransactionPage: mocked.fetch }));
vi.mock("../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => mocked.authClient }));
import { useCaseTransactions } from "./useCaseTransactions";

const CASE_A = "20000000-0000-4000-9000-000000000003";
const CASE_B = "20000000-0000-4000-9000-000000000004";
const ID_A = "91a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5003";
const ID_B = "12a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5004";
const session: AuthSession = { subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: ["FDS_VIEWER"] };
const authClient = createFakeAuthClient({ initialSession: session });
const baseValue: AuthContextValue = { state: { status: "authenticated", session },
  client: authClient, signIn: () => undefined, signOut: () => undefined,
  notifyCallbackStarted: () => undefined, notifyCallbackSucceeded: () => undefined,
  notifyCallbackFailed: () => undefined };
function page(caseId: string, id: string): CaseTransactionPage {
  return { caseId, content: [{ transactionId: id }], page: {
    number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true,
  }, traceId: "trace_test_case_transactions" };
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
afterEach(() => mocked.fetch.mockReset());

it("removes old IDs on case change and ignores the late prior response", async () => {
  const first = deferred<CaseTransactionPage>();
  mocked.fetch.mockReturnValueOnce(first.promise).mockResolvedValueOnce(page(CASE_B, ID_B));
  const wrapper = ({ children }: { children: ReactNode }) =>
    <AuthContext.Provider value={baseValue}>{children}</AuthContext.Provider>;
  const rendered = renderHook(({ caseId }) => useCaseTransactions(caseId), {
    initialProps: { caseId: CASE_A }, wrapper,
  });
  await waitFor(() => expect(mocked.fetch).toHaveBeenCalledTimes(1));
  rendered.rerender({ caseId: CASE_B });
  expect(rendered.result.current.state.status).toBe("loading");
  await waitFor(() => expect(rendered.result.current.state.status).toBe("success"));
  await act(async () => { first.resolve(page(CASE_A, ID_A)); await first.promise; });
  expect(rendered.result.current.state).toMatchObject({ status: "success", ids: [ID_B] });
});

it("does not request after logout or for a noncanonical case ID", async () => {
  let value: AuthContextValue = baseValue;
  const wrapper = ({ children }: { children: ReactNode }) =>
    <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
  const rendered = renderHook(({ caseId }) => useCaseTransactions(caseId), {
    initialProps: { caseId: "INVALID" }, wrapper,
  });
  expect(rendered.result.current.state.status).toBe("idle");
  value = { ...baseValue, state: { status: "unauthenticated" } };
  rendered.rerender({ caseId: CASE_A });
  expect(rendered.result.current.state.status).toBe("idle");
  expect(mocked.fetch).not.toHaveBeenCalled();
});

it("changes page and retries only after a request error", async () => {
  mocked.fetch.mockRejectedValueOnce(new Error("secret"))
    .mockResolvedValueOnce(page(CASE_A, ID_A));
  const wrapper = ({ children }: { children: ReactNode }) =>
    <AuthContext.Provider value={baseValue}>{children}</AuthContext.Provider>;
  const rendered = renderHook(() => useCaseTransactions(CASE_A), { wrapper });
  await waitFor(() => expect(rendered.result.current.state.status).toBe("error"));
  act(() => rendered.result.current.retry());
  await waitFor(() => expect(rendered.result.current.state.status).toBe("success"));
  expect(mocked.fetch).toHaveBeenCalledTimes(2);
});

it("loads distinct pages and discards the old page while the next page is pending", async () => {
  const next = deferred<CaseTransactionPage>();
  mocked.fetch.mockResolvedValueOnce(page(CASE_A, ID_A)).mockReturnValueOnce(next.promise);
  const wrapper = ({ children }: { children: ReactNode }) =>
    <AuthContext.Provider value={baseValue}>{children}</AuthContext.Provider>;
  const rendered = renderHook(() => useCaseTransactions(CASE_A), { wrapper });
  await waitFor(() => expect(rendered.result.current.state).toMatchObject({ status: "success", ids: [ID_A] }));
  act(() => rendered.result.current.setPage(1));
  expect(rendered.result.current.state.status).toBe("loading");
  await waitFor(() => expect(mocked.fetch).toHaveBeenCalledTimes(2));
  const second = { ...page(CASE_A, ID_B), page: { number: 1, size: 20,
    totalElements: 21, totalPages: 2, first: false, last: true } };
  await act(async () => { next.resolve(second); await next.promise; });
  expect(rendered.result.current.state).toMatchObject({ status: "success", ids: [ID_B],
    page: { number: 1 } });
  expect(mocked.fetch.mock.calls.map((call) => call[2])).toEqual([
    { page: 0, size: 20 }, { page: 1, size: 20 },
  ]);
});

it("clears old IDs on session replacement and on logout", async () => {
  const replacement: AuthSession = { ...session, subject: CASE_B };
  const next = deferred<CaseTransactionPage>();
  mocked.fetch.mockResolvedValueOnce(page(CASE_A, ID_A)).mockReturnValueOnce(next.promise);
  let value: AuthContextValue = baseValue;
  const wrapper = ({ children }: { children: ReactNode }) =>
    <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
  const rendered = renderHook(() => useCaseTransactions(CASE_A), { wrapper });
  await waitFor(() => expect(rendered.result.current.state).toMatchObject({ status: "success", ids: [ID_A] }));
  value = { ...baseValue, state: { status: "authenticated", session: replacement } };
  rendered.rerender();
  expect(rendered.result.current.state.status).toBe("loading");
  await waitFor(() => expect(mocked.fetch).toHaveBeenCalledTimes(2));
  value = { ...baseValue, state: { status: "unauthenticated" } };
  rendered.rerender();
  expect(rendered.result.current.state.status).toBe("idle");
  await act(async () => { next.resolve(page(CASE_A, ID_B)); await next.promise; });
  expect(rendered.result.current.state.status).toBe("idle");
});

it.each([
  [new ForbiddenError(), "forbidden"],
  [new HttpError(404), "not-found"],
  [new UnauthorizedError(), "authentication-required"],
] as const)("maps HTTP refusal %s without publishing stale IDs", async (error, status) => {
  mocked.fetch.mockRejectedValueOnce(error);
  const wrapper = ({ children }: { children: ReactNode }) =>
    <AuthContext.Provider value={baseValue}>{children}</AuthContext.Provider>;
  const rendered = renderHook(() => useCaseTransactions(CASE_A), { wrapper });
  await waitFor(() => expect(rendered.result.current.state.status).toBe(status));
  expect("ids" in rendered.result.current.state).toBe(false);
});
