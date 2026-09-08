import { useCallback, useEffect, useRef, useState } from "react";
import type { AuthSession } from "../auth/authClient";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useAuth } from "../auth/useAuth";
import { fetchCaseList, type CaseListItem, type CaseListPage, type CaseListQuery } from "./caseApi";
import {
  AuthenticationRequiredError,
  ForbiddenError,
  InvalidResponseError,
  NetworkError,
  RequestNotAllowedError,
  TimeoutError,
  UnauthorizedError,
} from "./errors";

/**
 * The fraud-case list, as a React state machine.
 *
 * The same three responsibilities the transaction list hook owns, held for a
 * different Backend contract: which request is the current one, what happens to
 * a request that is no longer current, and what a screen is allowed to keep
 * showing when the session underneath it changes.
 *
 * The credential boundary is the important part. `getOidcAuthClient()` is
 * called here, inside an effect, and the value it returns never leaves the
 * closure: it is not stored in state, not passed to a component, not put on the
 * returned object and not captured in a context. Everything the React tree can
 * reach from this hook is data - a page of cases, or the kind of an error - so
 * no component, render prop or devtools panel is one property lookup away from
 * something that can sign a request.
 *
 * Written against `fetchCaseList` and nothing else: no raw `fetch`, no second
 * definition of the Backend DTO, and no path around the endpoint registry, the
 * query validator or the response validator. A case row that reaches this
 * hook has already been through `isCaseListPage`.
 *
 * There is no retry of any kind that a person did not ask for. One fetch per
 * request, no replay after a failure, no refresh on an interval, and no second
 * attempt after a 401.
 */

export type CaseListErrorKind =
  /** The request did not answer inside the transport's deadline. */
  | "timeout"
  /** The request never reached the Backend. */
  | "network"
  /** The Backend answered something this client refuses to display. */
  | "invalid-response"
  /** HTTP 403. The session is intact; this user may not read cases. */
  | "access-denied"
  /** HTTP 401, or no usable local session at the moment of sending. */
  | "session-lost"
  /** The client refused to build the request. A filter combination it will not send. */
  | "request-rejected"
  /** Any other Backend status. Nothing about it is shown. */
  | "unknown";

/** Whether a load is the first one for this session or a re-query of it. */
export type CaseLoadPhase = "initial" | "refresh";

/**
 * The page of cases a screen is given, and the whole of it.
 *
 * `CaseListPage` is the API contract: it carries Backend's `traceId` because
 * the transport verifies that value against the response header before it will
 * accept the body at all. That verification is the transport's business and it
 * is finished by the time this hook is handed a result. What a screen needs is
 * the rows and the page metadata, so that is exactly what this type admits -
 * `Pick` rather than a second declaration, so a field added to the Backend
 * contract cannot silently fail to reach the console, and a field removed from
 * it stops compiling here.
 *
 * The `traceId` key is not merely unrendered: it is not present. Nothing in the
 * React tree, in a devtools panel or in `JSON.stringify` of this state can
 * reach a trace identifier, because the value never crosses the state boundary.
 */
export type CaseListView = Pick<CaseListPage, "content" | "page">;

export type CaseListState =
  /** No authenticated session, so no request has been made and none will be. */
  | { readonly status: "idle" }
  | { readonly status: "loading"; readonly phase: CaseLoadPhase }
  | { readonly status: "success"; readonly data: CaseListView }
  | { readonly status: "error"; readonly error: CaseListErrorKind };

export interface UseCaseListResult {
  readonly state: CaseListState;
  /**
   * Re-sends the current query. Does nothing unless the last attempt failed, so
   * it cannot be used to hammer the Backend from a success or a loading state,
   * and it is only ever reached from a control the user operates.
   */
  readonly retry: () => void;
}

/**
 * The screen's copy of one case row.
 *
 * Field by field rather than by spread, and to a fresh object rather than to
 * the validated one, so nothing from the parsed response envelope keeps an
 * identity inside React state. The seven fields are transcribed exactly as the
 * validator admitted them: nothing here rounds, trims, case-folds, re-formats
 * or defaults a value, so a row on screen is the row Backend sent.
 */
function projectCaseListItem(item: CaseListItem): CaseListItem {
  return {
    caseId: item.caseId,
    caseStatus: item.caseStatus,
    finalDisposition: item.finalDisposition,
    assigneeRef: item.assigneeRef,
    relatedTransactionCount: item.relatedTransactionCount,
    createdAt: item.createdAt,
    lastChangedAt: item.lastChangedAt,
  };
}

/**
 * The one place a validated envelope becomes screen state.
 *
 * `isCaseListPage` has already decided that this response is a page of cases
 * and that its `traceId` matches the header the transport saw. This function
 * then keeps the two things a list screen displays and drops the rest, and it
 * does so by construction: a new array, a new page object and a new object per
 * row. No part of the parsed envelope - not the envelope, not its `content`
 * array, not its `page`, not a row - remains reachable from what React holds,
 * so there is no property lookup and no serialization that arrives back at a
 * trace identifier.
 *
 * The validator is not repeated here. Every value below has already been
 * admitted by it, and re-deciding any of them would be a second, divergent
 * contract.
 */
function projectCaseListPage(envelope: CaseListPage): CaseListView {
  return {
    content: envelope.content.map(projectCaseListItem),
    page: {
      number: envelope.page.number,
      size: envelope.page.size,
      totalElements: envelope.page.totalElements,
      totalPages: envelope.page.totalPages,
      first: envelope.page.first,
      last: envelope.page.last,
    },
  };
}

function classifyError(error: unknown): CaseListErrorKind {
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
  readonly query: CaseListQuery;
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
  readonly query: CaseListQuery;
  readonly state: CaseListState;
}

/**
 * Loads one page of fraud cases for the current session.
 *
 * `query` must be referentially stable for as long as its contents are
 * unchanged - build it with `useMemo` in the caller. Identity is what this hook
 * treats as "the same query", so an object rebuilt on every render would issue
 * a request on every render.
 */
export function useCaseList(query: CaseListQuery): UseCaseListResult {
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
  // replaced or invalidated must not leave a single case on screen for even one
  // frame, and an effect runs after the browser has already been given
  // something to paint.
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

    const publish = (next: CaseListState): void => {
      // Latest wins, decided by identity rather than by a timestamp: a late
      // answer belonging to an earlier filter, an earlier page or an earlier
      // session finds itself no longer in the ref and publishes nothing. This
      // check does not depend on the abort having been honoured, so a fetch
      // implementation that ignores the signal entirely changes nothing here.
      if (flightRef.current !== flight) {
        return;
      }
      setSnapshot({ session, query, state: next });
    };

    fetchCaseList(getOidcAuthClient(), query, controller.signal)
      .then(
        (result) => {
          publish({ status: "success", data: projectCaseListPage(result.data) });
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
