import { useCallback, useEffect, useRef, useState } from "react";
import type { AuthSession } from "../auth/authClient";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useAuth } from "../auth/useAuth";
import {
  AuthenticationRequiredError,
  ForbiddenError,
  InvalidResponseError,
  NetworkError,
  RequestNotAllowedError,
  TimeoutError,
  UnauthorizedError,
} from "./errors";
import {
  fetchTransactionList,
  type TransactionListPage,
  type TransactionListQuery,
} from "./transactionApi";

/**
 * The transaction list, as a React state machine.
 *
 * This hook owns three things the typed API module deliberately does not: which
 * request is the current one, what happens to a request that is no longer
 * current, and what a screen is allowed to keep showing when the session
 * underneath it changes.
 *
 * The credential boundary is the important part. `getOidcAuthClient()` is
 * called here, inside an effect, and the value it returns never leaves the
 * closure: it is not stored in state, not passed to a component, not put on the
 * returned object and not captured in a context. Everything the React tree can
 * reach from this hook is data - a page of transactions, or the kind of an
 * error - so no component, render prop or devtools panel is one property lookup
 * away from something that can sign a request.
 *
 * There is no retry of any kind that a person did not ask for. One fetch per
 * request, no replay after a failure, no refresh on an interval, and no second
 * attempt after a 401.
 */

export type TransactionListErrorKind =
  /** The request did not answer inside the transport's deadline. */
  | "timeout"
  /** The request never reached the Backend. */
  | "network"
  /** The Backend answered something this client refuses to display. */
  | "invalid-response"
  /** HTTP 403. The session is intact; this user may not read transactions. */
  | "access-denied"
  /** HTTP 401, or no usable local session at the moment of sending. */
  | "session-lost"
  /** The client refused to build the request. A filter combination it will not send. */
  | "request-rejected"
  /** Any other Backend status. Nothing about it is shown. */
  | "unknown";

/** Whether a load is the first one for this session or a re-query of it. */
export type TransactionLoadPhase = "initial" | "refresh";

export type TransactionListState =
  /** No authenticated session, so no request has been made and none will be. */
  | { readonly status: "idle" }
  | { readonly status: "loading"; readonly phase: TransactionLoadPhase }
  | { readonly status: "success"; readonly data: TransactionListPage }
  | { readonly status: "error"; readonly error: TransactionListErrorKind };

export interface UseTransactionListResult {
  readonly state: TransactionListState;
  /**
   * Re-sends the current query. Does nothing unless the last attempt failed, so
   * it cannot be used to hammer the Backend from a success or a loading state,
   * and it is only ever reached from a control the user operates.
   */
  readonly retry: () => void;
}

function classifyError(error: unknown): TransactionListErrorKind {
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
  return "unknown";
}

/**
 * One outstanding request, together with what it belongs to.
 *
 * `subscribers` is what makes React StrictMode's setup-cleanup-setup replay cost
 * one network call instead of two: the cleanup does not cancel immediately, it
 * schedules the decision as a microtask, and the replayed setup re-joins the
 * same flight before that microtask runs. A genuine unmount has no such replay,
 * so the microtask still finds nobody listening and the request is aborted for
 * real.
 */
interface RequestFlight {
  readonly session: AuthSession;
  readonly query: TransactionListQuery;
  readonly attempt: number;
  readonly controller: AbortController;
  subscribers: number;
  settled: boolean;
}

interface Snapshot {
  /**
   * The session this state was produced for, held by identity. The adapter
   * publishes a new frozen object per session, so a sign-out, a session
   * replacement and a 401 invalidation all change this reference - and the
   * render-phase check below turns that into data leaving the screen before it
   * is painted, rather than one render later.
   */
  readonly session: AuthSession | null;
  /** The query this state was produced for, held by identity. */
  readonly query: TransactionListQuery;
  readonly state: TransactionListState;
}

/**
 * Loads one page of transactions for the current session.
 *
 * `query` must be referentially stable for as long as its contents are
 * unchanged - build it with `useMemo` in the caller. Identity is what this hook
 * treats as "the same query", so an object rebuilt on every render would issue
 * a request on every render.
 */
export function useTransactionList(query: TransactionListQuery): UseTransactionListResult {
  const { state: authState } = useAuth();
  const session = authState.status === "authenticated" ? authState.session : null;

  const [attempt, setAttempt] = useState(0);
  const [snapshot, setSnapshot] = useState<Snapshot>(() => ({
    session: null,
    query,
    state: { status: "idle" },
  }));
  const flightRef = useRef<RequestFlight | null>(null);

  // Adjusted during render on purpose. A session that has just been signed out,
  // replaced or invalidated must not leave a single row of the previous
  // session's data on screen for even one frame, and an effect runs after the
  // browser has already been given something to paint.
  let published = snapshot;
  if (snapshot.session !== session) {
    published = {
      session,
      query,
      state: session === null ? { status: "idle" } : { status: "loading", phase: "initial" },
    };
    setSnapshot(published);
  } else if (session !== null && snapshot.query !== query) {
    published = { session, query, state: { status: "loading", phase: "refresh" } };
    setSnapshot(published);
  }

  useEffect(() => {
    // No session, no request. This is the whole reason an unauthorized visit
    // costs zero Backend calls: the guard removes the screen, and even if it
    // did not, there is nothing here to send.
    if (session === null) {
      return;
    }

    const existing = flightRef.current;
    if (
      existing !== null &&
      !existing.settled &&
      existing.session === session &&
      existing.query === query &&
      existing.attempt === attempt
    ) {
      existing.subscribers += 1;
      return () => {
        releaseFlight(flightRef, existing);
      };
    }

    // A superseded request is cancelled as far as the platform allows, so a
    // filter the analyst has already moved on from stops consuming a connection.
    if (existing !== null && !existing.settled) {
      existing.controller.abort();
    }

    const controller = new AbortController();
    const flight: RequestFlight = {
      session,
      query,
      attempt,
      controller,
      subscribers: 1,
      settled: false,
    };
    flightRef.current = flight;

    const publish = (next: TransactionListState): void => {
      // Latest wins, decided by identity rather than by a timestamp: a late
      // answer belonging to an earlier filter, an earlier page or an earlier
      // session finds itself no longer in the ref and publishes nothing.
      if (flightRef.current !== flight) {
        return;
      }
      setSnapshot({ session, query, state: next });
    };

    fetchTransactionList(getOidcAuthClient(), query, controller.signal)
      .then(
        (result) => {
          publish({ status: "success", data: result.data });
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
  }, [session, query, attempt]);

  const retry = useCallback(() => {
    if (published.state.status !== "error") {
      return;
    }
    setSnapshot({
      session: published.session,
      query: published.query,
      state: { status: "loading", phase: "refresh" },
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
