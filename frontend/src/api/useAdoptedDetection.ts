import { useCallback, useEffect, useState } from "react";
import type { AuthSession } from "../auth/authClient";
import { useAuth } from "../auth/useAuth";
import { useCapabilities } from "../auth/useCapabilities";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import { fetchAdoptedDetection, type AdoptedDetectionResponse } from "./adoptedDetectionApi";
import { AuthenticationRequiredError, ForbiddenError, HttpError, InvalidResponseError,
  NetworkError, RequestNotAllowedError, TimeoutError, UnauthorizedError } from "./errors";

export type AdoptedDetectionState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly data: AdoptedDetectionResponse }
  | { readonly status: "forbidden" | "not-found" | "authentication-required" }
  | { readonly status: "error"; readonly kind: "timeout" | "network" | "invalid-response" | "unknown" };

interface Snapshot {
  readonly session: AuthSession;
  readonly transactionId: string;
  readonly attempt: number;
  readonly state: AdoptedDetectionState;
}

function failure(error: unknown): AdoptedDetectionState {
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

export function useAdoptedDetection(transactionId: string | null) {
  const { state: auth } = useAuth();
  const capabilities = useCapabilities();
  const session = auth.status === "authenticated" && capabilities.has("transaction:view") &&
    capabilities.has("detection:view") ? auth.session : null;
  const requestedId = transactionId !== null && isCanonicalUuidV4(transactionId) ? transactionId : null;
  const [attempt, setAttempt] = useState(0);
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const state: AdoptedDetectionState = session === null || requestedId === null ? { status: "idle" }
    : snapshot !== null && snapshot.session === session && snapshot.transactionId === requestedId &&
      snapshot.attempt === attempt ? snapshot.state : { status: "loading" };

  useEffect(() => {
    if (session === null || requestedId === null) return;
    let active = true;
    const controller = new AbortController();
    // Defer dispatch so StrictMode's setup/cleanup pair does not double-request.
    queueMicrotask(() => {
      if (!active) return;
      fetchAdoptedDetection(getOidcAuthClient(), requestedId, controller.signal).then(
        (data) => {
          if (active) setSnapshot({ session, transactionId: requestedId, attempt,
            state: { status: "success", data } });
        },
        (error: unknown) => {
          if (active) setSnapshot({ session, transactionId: requestedId, attempt, state: failure(error) });
        },
      );
    });
    return () => { active = false; controller.abort(); };
  }, [session, requestedId, attempt]);

  const retry = useCallback(() => {
    if (state.status === "error") setAttempt((current) => current + 1);
  }, [state.status]);
  return { state, retry };
}
