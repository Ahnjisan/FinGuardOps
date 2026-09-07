import { useCallback, useEffect, useRef, useState } from "react";
import type { AuthSession } from "../auth/authClient";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useAuth } from "../auth/useAuth";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import {
  AuthenticationRequiredError,
  ForbiddenError,
  HttpError,
  InvalidResponseError,
  NetworkError,
  RequestNotAllowedError,
  TimeoutError,
  UnauthorizedError,
} from "./errors";
import { fetchTransactionDetail, type TransactionDetail } from "./transactionApi";

/**
 * One transaction, as a React state machine.
 *
 * The twin of `useTransactionList`, and deliberately built the same way: the
 * hook owns which request is current, what happens to one that is no longer
 * current, and what the screen may keep showing when the session underneath it
 * changes. What it does not own is the request itself - that is
 * `fetchTransactionDetail()`, which carries the endpoint registry, the URL
 * re-verification, the authorized transport and the response validator.
 *
 * The credential boundary is the same one. `getOidcAuthClient()` is called
 * here, inside an effect, and what it returns never leaves the closure: not
 * into state, not onto the returned object, not into a context. Everything the
 * React tree can reach from this hook is data or a fixed error kind - no token,
 * no trace id, no raw response, no error object and no client that could sign
 * one.
 *
 * The returned value is the validated `TransactionDetail` alone. The envelope's
 * `traceId` and the transport's own trace id are dropped here rather than in
 * the component, so no screen has the option of rendering one.
 *
 * Nothing retries by itself. One fetch per request, no replay after a failure,
 * no polling, and no second attempt after a 401.
 */

export type TransactionDetailErrorKind =
  /** The request did not answer inside the transport's deadline. */
  | "timeout"
  /** The request never reached the Backend. */
  | "network"
  /** The Backend answered something this client refuses to display. */
  | "invalid-response"
  /** HTTP 404. No such transaction, and the session is intact. */
  | "not-found"
  /** HTTP 403. The session is intact; this user may not read this transaction. */
  | "access-denied"
  /** HTTP 401, or no usable local session at the moment of sending. */
  | "session-lost"
  /** The client refused to build the request at all. */
  | "request-rejected"
  /** Any other Backend status. Nothing about it is shown. */
  | "unknown";

export type TransactionDetailState =
  /**
   * Nothing to ask for: no authenticated session, or no canonical transaction
   * id. No request has been made and none will be.
   */
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | { readonly status: "success"; readonly data: TransactionDetail }
  | { readonly status: "error"; readonly error: TransactionDetailErrorKind };

export interface UseTransactionDetailResult {
  readonly state: TransactionDetailState;
  /**
   * Re-sends the request for the transaction currently on screen. Does nothing
   * unless the last attempt failed, so it cannot be used to hammer the Backend
   * from a success or a loading state, and it is only ever reached from a
   * control the user operates. One call, one fetch.
   */
  readonly retry: () => void;
}

function classifyError(error: unknown): TransactionDetailErrorKind {
  if (error instanceof TimeoutError) {
    return "timeout";
  }
  if (error instanceof NetworkError) {
    return "network";
  }
  if (error instanceof InvalidResponseError) {
    return "invalid-response";
  }
  if (error instanceof ForbiddenError) {
    return "access-denied";
  }
  // Both mean "this session cannot make this request": one is the Backend's
  // verdict, the other is the local port having nothing to sign with. Neither
  // carries a body, a token or a claim into this value - only the kind survives.
  if (error instanceof UnauthorizedError || error instanceof AuthenticationRequiredError) {
    return "session-lost";
  }
  if (error instanceof RequestNotAllowedError) {
    return "request-rejected";
  }
  // 404 is the one remaining status this screen has a meaning for. Every other
  // one collapses into `unknown`, which shows a fixed sentence and no number.
  if (error instanceof HttpError && error.status === 404) {
    return "not-found";
  }
  return "unknown";
}

/**
 * One outstanding request, together with what it belongs to.
 *
 * `subscribers` is what makes React StrictMode's setup-cleanup-setup replay
 * cost one network call instead of two: the cleanup does not cancel
 * immediately, it schedules the decision as a microtask, and the replayed setup
 * re-joins the same flight before that microtask runs. A genuine unmount has no
 * such replay, so the microtask still finds nobody listening and the request is
 * aborted for real.
 */
interface RequestFlight {
  readonly session: AuthSession;
  readonly transactionId: string;
  readonly attempt: number;
  readonly controller: AbortController;
  subscribers: number;
  settled: boolean;
}

interface Snapshot {
  /**
   * The session this state was produced for, held by identity. The adapter
   * publishes a new frozen object per session, so a sign-out, a session
   * replacement and a 401 invalidation all change this reference.
   */
  readonly session: AuthSession | null;
  /** The transaction this state was produced for. */
  readonly transactionId: string | null;
  readonly state: TransactionDetailState;
}

/**
 * Loads one transaction for the current session.
 *
 * `transactionId` must already be the canonical lowercase UUID v4 the route
 * carries, or `null` when the address is not one. It is re-checked here anyway:
 * this hook is the last place before a credential is fetched, and a value that
 * reached it from untyped data must cost zero credential lookups and zero
 * fetches rather than be spent on the 400 the Backend would answer.
 */
export function useTransactionDetail(
  transactionId: string | null,
): UseTransactionDetailResult {
  const { state: authState } = useAuth();
  const session = authState.status === "authenticated" ? authState.session : null;

  // A non-canonical id is the same as no id at all: nothing is requested, and
  // the value never reaches the URL builder, the transport or an error string.
  const requestedId =
    transactionId !== null && isCanonicalUuidV4(transactionId) ? transactionId : null;

  const [attempt, setAttempt] = useState(0);
  const [snapshot, setSnapshot] = useState<Snapshot>(() => ({
    session: null,
    transactionId: requestedId,
    state: { status: "idle" },
  }));
  const flightRef = useRef<RequestFlight | null>(null);

  // Adjusted during render on purpose. Transaction A's record must not appear
  // under transaction B's heading for even one frame, and a session that has
  // just been signed out, replaced or invalidated must take its data with it
  // immediately. An effect runs after the browser has already been given
  // something to paint, so waiting for cleanup would show exactly that frame.
  let published = snapshot;
  if (snapshot.session !== session || snapshot.transactionId !== requestedId) {
    published = {
      session,
      transactionId: requestedId,
      state:
        session === null || requestedId === null ? { status: "idle" } : { status: "loading" },
    };
    setSnapshot(published);
  }

  useEffect(() => {
    // No session or no canonical id, no request. This is why an unauthorized
    // visit and a malformed address both cost zero Backend calls and zero
    // credential lookups: there is nothing here to send.
    if (session === null || requestedId === null) {
      return;
    }

    const existing = flightRef.current;
    if (
      existing !== null &&
      !existing.settled &&
      existing.session === session &&
      existing.transactionId === requestedId &&
      existing.attempt === attempt
    ) {
      existing.subscribers += 1;
      return () => {
        releaseFlight(flightRef, existing);
      };
    }

    // A superseded request is cancelled as far as the platform allows, so a
    // transaction the analyst has already navigated away from stops consuming a
    // connection. Cancellation is a courtesy, not the correctness argument:
    // the generation check below stands whether or not the abort is honoured.
    if (existing !== null && !existing.settled) {
      existing.controller.abort();
    }

    const controller = new AbortController();
    const flight: RequestFlight = {
      session,
      transactionId: requestedId,
      attempt,
      controller,
      subscribers: 1,
      settled: false,
    };
    flightRef.current = flight;

    const publish = (next: TransactionDetailState): void => {
      // Latest wins, decided by identity rather than by a timestamp: a late
      // answer belonging to an earlier transaction or an earlier session finds
      // itself no longer in the ref and publishes nothing at all.
      if (flightRef.current !== flight) {
        return;
      }
      setSnapshot({ session, transactionId: requestedId, state: next });
    };

    fetchTransactionDetail(getOidcAuthClient(), requestedId, controller.signal)
      .then(
        (result) => {
          // The envelope's trace id stops here. Only the validated transaction
          // is published, so no screen can render a support reference it was
          // never meant to show.
          publish({ status: "success", data: result.data.transaction });
        },
        (error: unknown) => {
          // An abort is this hook's own decision, not an outcome to report.
          if (controller.signal.aborted) {
            return;
          }
          publish({ status: "error", error: classifyError(error) });
        },
      )
      .finally(() => {
        flight.settled = true;
      });

    return () => {
      releaseFlight(flightRef, flight);
    };
  }, [session, requestedId, attempt]);

  const retry = useCallback(() => {
    if (published.state.status !== "error") {
      return;
    }
    setSnapshot({
      session: published.session,
      transactionId: published.transactionId,
      state: { status: "loading" },
    });
    setAttempt((current) => current + 1);
  }, [published]);

  return { state: published.state, retry };
}

function releaseFlight(
  flightRef: { current: RequestFlight | null },
  flight: RequestFlight,
): void {
  if (flight.subscribers <= 0) {
    return;
  }
  flight.subscribers -= 1;
  if (flight.subscribers > 0) {
    return;
  }
  queueMicrotask(() => {
    if (flight.subscribers > 0 || flight.settled) {
      return;
    }
    if (flightRef.current === flight) {
      flightRef.current = null;
    }
    flight.controller.abort();
  });
}
