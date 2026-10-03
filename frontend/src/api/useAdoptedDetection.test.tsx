import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "../auth/authContext";
import type { AuthSession } from "../auth/authClient";
import { useAdoptedDetection } from "./useAdoptedDetection";

const mocked = vi.hoisted(() => ({ fetch: vi.fn() }));
vi.mock("./adoptedDetectionApi", () => ({ fetchAdoptedDetection: mocked.fetch }));
vi.mock("../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => ({}) }));

const ID_A = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const ID_B = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430002";
const session: AuthSession = { subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f", roles: ["FDS_VIEWER"] };
const client = { initialize: async () => ({ session }), signIn: async () => undefined,
  completeSignIn: async () => ({ session, returnTo: "/" }), signOut: async () => undefined,
  onSessionInvalidated: () => () => undefined };
const base: AuthContextValue = { state: { status: "authenticated", session }, client,
  signIn: () => undefined, signOut: () => undefined, notifyCallbackStarted: () => undefined,
  notifyCallbackSucceeded: () => undefined, notifyCallbackFailed: () => undefined };
const body = (transactionId: string) => ({ transactionId, availability: "NO_HISTORY",
  latestDetectionResultVersion: null, latestAnalysisStatus: null, adoptedResult: null });
function deferred<T>() { let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }
afterEach(() => mocked.fetch.mockReset());

it("discards a late response when the transaction changes", async () => {
  const first = deferred<ReturnType<typeof body>>();
  mocked.fetch.mockReturnValueOnce(first.promise).mockResolvedValueOnce(body(ID_B));
  const wrapper = ({ children }: { children: ReactNode }) =>
    <AuthContext.Provider value={base}>{children}</AuthContext.Provider>;
  const rendered = renderHook(({ id }) => useAdoptedDetection(id), { initialProps: { id: ID_A }, wrapper });
  await waitFor(() => expect(mocked.fetch).toHaveBeenCalledTimes(1));
  rendered.rerender({ id: ID_B });
  expect(rendered.result.current.state.status).toBe("loading");
  await waitFor(() => expect(rendered.result.current.state).toMatchObject({ status: "success", data: body(ID_B) }));
  await act(async () => { first.resolve(body(ID_A)); await first.promise; });
  expect(rendered.result.current.state).toMatchObject({ status: "success", data: body(ID_B) });
});

it("clears old data on session replacement and withholds requests from a role without detection read", async () => {
  mocked.fetch.mockResolvedValue(body(ID_A));
  let value: AuthContextValue = base;
  const wrapper = ({ children }: { children: ReactNode }) =>
    <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
  const rendered = renderHook(() => useAdoptedDetection(ID_A), { wrapper });
  await waitFor(() => expect(rendered.result.current.state.status).toBe("success"));
  value = { ...base, state: { status: "authenticated", session: {
    ...session, subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e70", roles: ["PLATFORM_ADMIN"] } } };
  rendered.rerender();
  expect(rendered.result.current.state.status).toBe("idle");
  expect(mocked.fetch).toHaveBeenCalledTimes(1);
});

it("retries only after an error", async () => {
  mocked.fetch.mockRejectedValueOnce(new Error("private error"))
    .mockResolvedValueOnce(body(ID_A));
  const wrapper = ({ children }: { children: ReactNode }) =>
    <AuthContext.Provider value={base}>{children}</AuthContext.Provider>;
  const rendered = renderHook(() => useAdoptedDetection(ID_A), { wrapper });
  await waitFor(() => expect(rendered.result.current.state.status).toBe("error"));
  act(() => rendered.result.current.retry());
  await waitFor(() => expect(rendered.result.current.state.status).toBe("success"));
  expect(mocked.fetch).toHaveBeenCalledTimes(2);
});
