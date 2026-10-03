import { useCallback, useEffect, useState } from "react";
import type { AuthSession } from "../auth/authClient";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useCapabilities } from "../auth/useCapabilities";
import { useAuth } from "../auth/useAuth";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import { fetchCaseTransactionPage } from "./caseTransactionApi";
import { AuthenticationRequiredError, ForbiddenError, HttpError, InvalidResponseError,
  NetworkError, RequestNotAllowedError, TimeoutError, UnauthorizedError } from "./errors";
import type { PageMetadata } from "./pagination";

export type CaseTransactionsState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "not-found" }
  | { readonly status: "forbidden" }
  | { readonly status: "authentication-required" }
  | { readonly status: "success"; readonly ids: readonly string[]; readonly page: PageMetadata }
  | { readonly status: "error"; readonly kind: "timeout" | "network" | "invalid-response" | "unknown" };

interface Position { readonly session: AuthSession | null; readonly caseId: string | null;
  readonly page: number; readonly attempt: number }
interface Snapshot extends Position { readonly state: CaseTransactionsState }

function failure(error: unknown): CaseTransactionsState {
  if (error instanceof ForbiddenError) return { status: "forbidden" };
  if (error instanceof HttpError && error.status === 404) return { status: "not-found" };
  if (error instanceof UnauthorizedError || error instanceof AuthenticationRequiredError) {
    return { status: "authentication-required" };
  }
  if (error instanceof TimeoutError) return { status: "error", kind: "timeout" };
  if (error instanceof NetworkError) return { status: "error", kind: "network" };
  if (error instanceof InvalidResponseError) return { status: "error", kind: "invalid-response" };
  if (error instanceof RequestNotAllowedError) return { status: "error", kind: "unknown" };
  return { status: "error", kind: "unknown" };
}

export function useCaseTransactions(caseId: string | null) {
  const { state: auth } = useAuth();
  const capabilities = useCapabilities();
  const session = auth.status === "authenticated" && capabilities.has("case:view") ? auth.session : null;
  const requestedId = caseId !== null && isCanonicalUuidV4(caseId) ? caseId : null;
  const [position, setPosition] = useState<Position>({ session, caseId: requestedId, page: 0, attempt: 0 });
  const sameScope = position.session === session && position.caseId === requestedId;
  const page = sameScope ? position.page : 0;
  const attempt = sameScope ? position.attempt : 0;
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const state: CaseTransactionsState = session === null || requestedId === null
    ? { status: "idle" }
    : snapshot !== null && snapshot.session === session && snapshot.caseId === requestedId &&
        snapshot.page === page && snapshot.attempt === attempt
      ? snapshot.state : { status: "loading" };

  useEffect(() => {
    if (session === null || requestedId === null) return;
    let active = true;
    const controller = new AbortController();
    // Deferring dispatch coalesces StrictMode's initial setup/cleanup pair.
    queueMicrotask(() => {
      if (!active) return;
      fetchCaseTransactionPage(getOidcAuthClient(), requestedId, { page, size: 20 }, controller.signal)
        .then((data) => {
          if (!active) return;
          setSnapshot({ session, caseId: requestedId, page, attempt, state: {
            status: "success",
            ids: data.content.map((item) => item.transactionId),
            page: { ...data.page },
          } });
        }, (error: unknown) => {
          if (active) setSnapshot({ session, caseId: requestedId, page, attempt, state: failure(error) });
        });
    });
    return () => { active = false; controller.abort(); };
  }, [session, requestedId, page, attempt]);

  const setPage = useCallback((number: number) => {
    if (session === null || requestedId === null || !Number.isInteger(number) || number < 0 ||
        number > 2147483647 || number * 20 > 2147483647) return;
    setPosition({ session, caseId: requestedId, page: number, attempt: 0 });
  }, [session, requestedId]);
  const retry = useCallback(() => {
    if (state.status === "error") setPosition({ session, caseId: requestedId,
      page, attempt: attempt + 1 });
  }, [state.status, session, requestedId, page, attempt]);
  return { state, page, setPage, retry };
}
