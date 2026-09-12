import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { AuthSession } from "../auth/authClient";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useAuth } from "../auth/useAuth";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import { fetchCaseDetail, type CaseDetail } from "./caseApi";
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

/**
 * One fraud case, as a React state machine.
 *
 * Built the same way as `useTransactionDetail`, for a different Backend
 * contract: the hook owns which request is current, what happens to one that is
 * no longer current, and what the screen may keep showing when the session
 * underneath it changes. What it does not own is the request itself - that is
 * `fetchCaseDetail()`, which carries the endpoint registry, the URL
 * re-verification, the authorized transport and the response validator. There
 * is no second definition of the case DTO here, and no path around any of them.
 *
 * The credential boundary is the same one. `getOidcAuthClient()` is called
 * here, inside an effect, and what it returns never leaves the closure: not
 * into state, not onto the returned object, not into a context. Everything the
 * React tree can reach from this hook is data or a fixed error kind - no token,
 * no trace id, no raw response, no error object and no client that could sign
 * one.
 *
 * The returned value is a *fresh* `CaseDetail` built field by field, never the
 * validated object the parser produced and never the envelope around it. The
 * envelope's `traceId` and the transport's own trace id are dropped here rather
 * than in the component, so no screen has the option of rendering one.
 *
 * Nothing retries by itself. One fetch per request, no replay after a failure,
 * no polling, and no second attempt after a 401.
 */

export type CaseDetailErrorKind =
  /** The request did not answer inside the transport's deadline. */
  | "timeout"
  /** The request never reached the Backend. */
  | "network"
  /** The Backend answered something this client refuses to display. */
  | "invalid-response"
  /** HTTP 401, or no usable local session at the moment of sending. */
  | "session-lost"
  /** The client refused to build the request at all. */
  | "request-rejected"
  /** Any other Backend status. Nothing about it is shown. */
  | "unknown";

/**
 * The four answers a request can end at, and the only thing a request is ever
 * allowed to leave behind.
 *
 * They are split out of `CaseDetailState` because they are exactly what a
 * settled request may be *stored* as: `idle` and `loading` describe the hook's
 * own situation and are never an answer, so a request that has finished can
 * only be remembered as one of these four. Each of them is already sanitized -
 * ten transcribed fields, or a fixed status, or one of a closed set of error
 * kinds - so remembering an outcome cannot become a way to keep a response, an
 * error object, a Backend message or a trace id alive.
 *
 * `not-found` and `forbidden` are statuses of their own rather than kinds of
 * `error`, because they are not failures to be tried again: a case that does
 * not exist and a case this session may not read are both settled answers, and
 * modelling them as retryable errors is how a screen ends up offering a button
 * that can only produce the same 403.
 *
 * Module-private on purpose. It is the shape of what a *flight* remembers,
 * which is this file's own bookkeeping; what a screen may see is
 * `CaseDetailState`, and every settled member of it is already reachable
 * through that union. Exporting this alias as well would publish a second name
 * for the same states and invite a component to declare a variable of the
 * stored type - which is the one thing the delivery boundary below exists to
 * keep out of reach.
 */
type CaseDetailTerminalState =
  | { readonly status: "success"; readonly data: CaseDetail }
  /** HTTP 404. No such case, and the session is intact. */
  | { readonly status: "not-found" }
  /** HTTP 403. The session is intact; this user may not read this case. */
  | { readonly status: "forbidden" }
  | { readonly status: "error"; readonly error: CaseDetailErrorKind };

/** The six states this screen distinguishes: two unsettled, four settled. */
export type CaseDetailState =
  /**
   * Nothing to ask for: no authenticated session, or no canonical case id. No
   * request has been made and none will be.
   */
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | CaseDetailTerminalState;

export interface UseCaseDetailResult {
  readonly state: CaseDetailState;
  /**
   * Re-sends the request for the case currently on screen. Does nothing unless
   * the last attempt ended in a retryable `error`, so it cannot be used to
   * hammer the Backend from a success, a loading state, a 404 or a 403, and it
   * is only ever reached from a control the user operates. One call, one fetch.
   */
  readonly retry: () => void;
  /** Re-reads a visible record without replacing it with a loading screen. */
  readonly refresh: (minimumVersion?: number) => void;
  readonly refreshState: "idle" | "refreshing" | "failed";
  /** Changes only when an authoritative detail response is published. */
  readonly reconciliationGeneration: number;
}

/**
 * The screen's copy of one case.
 *
 * Field by field rather than by spread, and to a fresh object rather than to
 * the validated one, so no part of the parsed response envelope keeps an
 * identity inside React state - which is what makes "the trace id is not
 * merely unrendered, it is unreachable" a structural claim rather than a
 * promise. The ten fields are transcribed exactly as the validator admitted
 * them: nothing here rounds, trims, case-folds, re-formats or defaults a value,
 * so a case on screen is the case Backend sent.
 */
function projectCaseDetail(detail: CaseDetail): CaseDetail {
  return {
    caseId: detail.caseId,
    caseStatus: detail.caseStatus,
    finalDisposition: detail.finalDisposition,
    assigneeRef: detail.assigneeRef,
    relatedTransactionCount: detail.relatedTransactionCount,
    createdAt: detail.createdAt,
    reviewStartedAt: detail.reviewStartedAt,
    closedAt: detail.closedAt,
    lastChangedAt: detail.lastChangedAt,
    concurrencyVersion: detail.concurrencyVersion,
  };
}

/**
 * One subscriber's own copy of a settled answer.
 *
 * A flight remembers exactly one outcome and may hand it to more than one
 * subscription - the broadcast when it settles, and the replay a subscription
 * that joined afterwards is given - so "what is remembered" and "what is
 * delivered" have to be different objects, not one object seen twice. This is
 * where they part: nothing stored on a flight is ever passed to `setSnapshot`,
 * and every publish builds a new state, with a new `data` object under it, out
 * of the stored one.
 *
 * That matters because React state is not enforced as immutable at runtime.
 * A consumer that writes to the record it was handed - a sort in place, a
 * normalization, an `Object.assign` - would otherwise be writing into the
 * flight's memory, and the next subscription to join that same flight would be
 * handed the edit as though the Backend had sent it. Freezing would turn that
 * into a silent no-op or a thrown error rather than a private edit, which is a
 * worse trade: the isolation here costs one object per publish and leaves an
 * ordinary mutable object on the other side of the boundary, where a caller
 * may do as it likes with something nobody else can see.
 *
 * The success case is the ten fields again, written out one by one, so a
 * delivered record shares no object with the stored one, with the parsed
 * envelope, or with any other subscriber's copy. The other three carry a fixed
 * status and, for a failure, one member of a closed set of error kinds -
 * copied by value into a new object rather than passed along by reference.
 */
function deliverTerminalState(outcome: CaseDetailTerminalState): CaseDetailTerminalState {
  switch (outcome.status) {
    case "success":
      return { status: "success", data: projectCaseDetail(outcome.data) };
    case "not-found":
      return { status: "not-found" };
    case "forbidden":
      return { status: "forbidden" };
    case "error":
      return { status: "error", error: outcome.error };
  }
}

/**
 * The settled state a failed request produces.
 *
 * Nothing of the failure itself survives: not the error object, not a status
 * code, not a body, not a header and not a trace id. What comes back is one of
 * a closed set of values a screen has fixed copy for.
 */
function classifyFailure(error: unknown): CaseDetailTerminalState {
  if (error instanceof TimeoutError) {
    return { status: "error", error: "timeout" };
  }
  if (error instanceof NetworkError) {
    return { status: "error", error: "network" };
  }
  if (error instanceof InvalidResponseError) {
    return { status: "error", error: "invalid-response" };
  }
  // 403 leaves the session alone. The Backend re-decided authorization from the
  // access token and said no to this read; that is not evidence about the
  // session, and there is nothing to try again.
  if (error instanceof ForbiddenError) {
    return { status: "forbidden" };
  }
  // Both mean "this session cannot make this request": one is the Backend's
  // verdict, the other is the local port having nothing to sign with. Neither
  // carries a body, a token or a claim into this value - only the kind
  // survives. The invalidation itself belongs to the transport, which acts on
  // the session that signed *this* request and on no other, so a 401 answering
  // an abandoned request tears down nothing.
  if (error instanceof UnauthorizedError || error instanceof AuthenticationRequiredError) {
    return { status: "error", error: "session-lost" };
  }
  if (error instanceof RequestNotAllowedError) {
    return { status: "error", error: "request-rejected" };
  }
  // 404 is the one remaining status this screen has a meaning for. Every other
  // one collapses into `unknown`, which shows a fixed sentence and no number.
  if (error instanceof HttpError && error.status === 404) {
    return { status: "not-found" };
  }
  return { status: "error", error: "unknown" };
}

/** The states a manual retry may be started from. */
function isRetryable(state: CaseDetailState): boolean {
  return state.status === "error";
}

/**
 * One effect's claim on one flight, and the boundary an answer has to cross to
 * reach React state.
 *
 * A flight deliberately outlives the effect that started it - that is the whole
 * of the StrictMode deduplication below - so "is this request still the current
 * one" and "is this listener still mounted" are two different questions, and
 * only the first of them can be answered from the flight. `active` answers the
 * second, and it is cleared synchronously, inside the call stack React runs the
 * cleanup in. There is therefore no window between a subscription ending and
 * its answers being refused: an id change, a session change and an unmount all
 * close it at the instant they happen.
 *
 * Without it, an answer that had already settled when the cleanup ran would
 * still find its flight installed - the flight is only removed a microtask
 * later - and would publish into a subscription that no longer exists.
 */
interface Subscription {
  /** Cleared by this effect's cleanup, synchronously and exactly once. */
  active: boolean;
  /**
   * Set by the first accepted publish, and never cleared.
   *
   * One flight has one answer, and a subscription may hear it from either of
   * two places: the broadcast the request makes when it settles, or the replay
   * a later subscription gets when it joins a flight that had already settled.
   * Neither path can be dropped - the first is how a live screen is answered,
   * the second is how a screen that arrived a microtask too late is - so the
   * exactly-once boundary is drawn here, per subscription, rather than by
   * hoping the two paths never overlap.
   */
  delivered: boolean;
  /**
   * Delivers one settled outcome, if this subscription may still speak.
   *
   * What is passed in is the flight's stored answer; what goes out to React is
   * a copy of it made here, so no two subscriptions - and no subscription and
   * the flight - ever hold the same object.
   */
  readonly publish: (stored: CaseDetailTerminalState) => void;
}

/**
 * What a flight has to say for itself, and the only thing it remembers.
 *
 * `pending` is a request still in the air. `settled` is a request that has
 * produced its one answer, already reduced to something the hook could publish
 * as-is: the ten projected fields, or `not-found`, or `forbidden`, or one fixed
 * error kind. Nothing of the transport crosses that line - no `Response`, no
 * envelope, no `Error`, no status code, no header, no Backend code or message,
 * no trace id and no abort signal - so a remembered outcome is a remembered
 * *screen state* and cannot be anything else.
 *
 * A settled outcome is the flight's own memory and is never handed out as it
 * stands: every publish copies it. That is what lets it be remembered at all -
 * a record two screens shared would be one in-place edit away from showing a
 * case the Backend never sent.
 *
 * `released` is a flight nobody is listening to any more. It is a phase rather
 * than a deletion because the flight object outlives its own removal from the
 * ref - late callbacks still close over it - and this is what says the answer
 * is gone rather than merely unreachable.
 */
type FlightPhase =
  | { readonly kind: "pending" }
  | { readonly kind: "settled"; readonly outcome: CaseDetailTerminalState }
  | { readonly kind: "released" };

/**
 * One outstanding request, together with what it belongs to.
 *
 * `subscribers` is what makes React StrictMode's setup-cleanup-setup replay
 * cost one network call instead of two: the cleanup does not cancel
 * immediately, it schedules the decision as a microtask, and the replayed setup
 * re-joins the same flight before that microtask runs. A genuine unmount has no
 * such replay, so the microtask still finds nobody listening and the request is
 * aborted for real.
 *
 * `listeners` is who the answer is *for*, which is not the same list. The
 * replayed setup takes a subscription of its own rather than inheriting the
 * first one's, so the request is shared while the permission to publish is not:
 * the subscription that was cleaned up stays silent even though the flight it
 * joined is still the current one, and the subscription that replaced it
 * receives the same answer in its own right.
 *
 * `phase` is what makes that second sentence survive the narrow case where the
 * answer arrives *between* the two: the request settles while its only listener
 * has just been torn down and its replacement has not yet joined. The answer is
 * still computed, still sanitized and still kept - on the flight, not on a
 * subscription - so the replacement finds it waiting instead of waiting forever
 * on a request that will never speak again.
 */
interface RequestFlight {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly attempt: number;
  readonly controller: AbortController;
  subscribers: number;
  phase: FlightPhase;
  readonly listeners: Set<Subscription>;
}

interface Snapshot {
  /**
   * The session this state was produced for, held by identity. The adapter
   * publishes a new frozen object per session, so a sign-out, a session
   * replacement and a 401 invalidation all change this reference.
   */
  readonly session: AuthSession | null;
  /** The case this state was produced for. */
  readonly caseId: string | null;
  readonly state: CaseDetailState;
  readonly refreshState: "idle" | "refreshing" | "failed";
  readonly reconciliationGeneration: number;
}

interface RefreshFlight {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly generation: number;
  readonly controller: AbortController;
  phase: "pending" | "settled" | "released";
}

interface ReconciliationFloor {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly minimumVersion: number;
}

/**
 * Loads one fraud case for the current session.
 *
 * `caseId` must already be the canonical lowercase UUID v4 the route carries,
 * or `null` when the address is not one. It is re-checked here anyway: this
 * hook is the last place before a credential is fetched, and a value that
 * reached it from untyped data must cost zero credential lookups and zero
 * fetches rather than be spent on the 400 the Backend would answer.
 */
export function useCaseDetail(caseId: string | null): UseCaseDetailResult {
  const { state: authState } = useAuth();
  const session = authState.status === "authenticated" ? authState.session : null;

  // A non-canonical id is the same as no id at all: nothing is requested, and
  // the value never reaches the URL builder, the transport or an error string.
  const requestedId = caseId !== null && isCanonicalUuidV4(caseId) ? caseId : null;

  const [attempt, setAttempt] = useState(0);
  const [snapshot, setSnapshot] = useState<Snapshot>(() => ({
    session: null,
    caseId: requestedId,
    state: { status: "idle" },
    refreshState: "idle",
    reconciliationGeneration: 0,
  }));
  const flightRef = useRef<RequestFlight | null>(null);
  const refreshFlightRef = useRef<RefreshFlight | null>(null);
  const refreshGenerationRef = useRef(0);
  const reconciliationGenerationRef = useRef(0);
  const reconciliationFloorRef = useRef<ReconciliationFloor | null>(null);
  const currentIdentityRef = useRef({ session, caseId: requestedId });
  const currentPublishedRef = useRef<Snapshot | null>(null);
  const mountedRef = useRef(false);

  useLayoutEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useLayoutEffect(() => {
    currentIdentityRef.current = { session, caseId: requestedId };
    reconciliationGenerationRef.current = 0;
    reconciliationFloorRef.current = null;
  }, [session, requestedId]);

  // Adjusted during render on purpose. Case A's record must not appear under
  // case B's heading for even one frame, and a session that has just been
  // signed out, replaced or invalidated must take its data with it
  // immediately. An effect runs after the browser has already been given
  // something to paint, so waiting for cleanup would show exactly that frame.
  let published = snapshot;
  if (snapshot.session !== session || snapshot.caseId !== requestedId) {
    published = {
      session,
      caseId: requestedId,
      state:
        session === null || requestedId === null ? { status: "idle" } : { status: "loading" },
      refreshState: "idle",
      reconciliationGeneration: 0,
    };
    setSnapshot(published);
  }
  useLayoutEffect(() => {
    currentPublishedRef.current = published;
  }, [published]);

  useEffect(() => {
    // No session or no canonical id, no request. This is why an unauthorized
    // visit and a malformed address both cost zero Backend calls and zero
    // credential lookups: there is nothing here to send.
    if (session === null || requestedId === null) {
      return;
    }

    // A flight is reused for what it is *for* - this session, this case, this
    // attempt - and not for whether it has answered yet. A request that settled
    // while nobody was listening is still this screen's request and still has
    // this screen's answer; refusing to reuse it would spend a second Backend
    // call to be told the same thing. A released flight is the one exception:
    // its answer has been discarded on purpose, so there is nothing to rejoin.
    const existing = flightRef.current;
    const reusable =
      existing !== null &&
      existing.phase.kind !== "released" &&
      existing.session === session &&
      existing.caseId === requestedId &&
      existing.attempt === attempt
        ? existing
        : null;

    // A superseded request is cancelled as far as the platform allows, so a
    // case the analyst has already navigated away from stops consuming a
    // connection. Cancellation is a courtesy, not the correctness argument:
    // the gates in `publish` below stand whether or not the abort is honoured.
    if (existing !== null && reusable === null && existing.phase.kind === "pending") {
      existing.controller.abort();
    }

    const flight = reusable ?? createFlight(session, requestedId, attempt);
    if (reusable === null) {
      flightRef.current = flight;
    } else {
      flight.subscribers += 1;
    }

    // This effect's own permission to publish, and no other effect's. The
    // subscription is made per setup rather than per flight, so a replayed or
    // replacing setup that joins the same request cannot lend its liveness to
    // the setup it replaced.
    const subscription: Subscription = {
      active: true,
      delivered: false,
      publish: (stored: CaseDetailTerminalState): void => {
        // Four independent gates, and every one of them is load-bearing.
        //
        // The first is this subscription's own lifetime. Cleanup clears it
        // synchronously, while the flight it joined is deliberately still
        // installed - the flight is only released a microtask later - so an
        // answer that had already settled when the cleanup ran, and whose
        // callback is therefore queued ahead of that microtask, publishes
        // nothing.
        //
        // The second is exactly-once. A settled flight can reach a
        // subscription twice - once as the broadcast, once as the replay a
        // late joiner is given - and this is where the second one stops.
        //
        // The third is latest-wins, decided by identity rather than by a
        // timestamp: a late answer belonging to an earlier case or an earlier
        // session finds itself no longer in the ref and publishes nothing at
        // all.
        //
        // The fourth re-reads what the installed flight is *for*. It is the
        // same triple this effect closed over, checked again at the instant of
        // publishing, so a stored answer cannot be handed to another case,
        // another session or another attempt even if it somehow outlived them.
        if (!subscription.active) {
          return;
        }
        if (subscription.delivered) {
          return;
        }
        const current = flightRef.current;
        if (current !== flight) {
          return;
        }
        if (
          current.session !== session ||
          current.caseId !== requestedId ||
          current.attempt !== attempt
        ) {
          return;
        }
        subscription.delivered = true;
        // Past the gates, and the one step here that is not one. `stored` is
        // the flight's own memory of its answer - the same object every
        // subscription of this flight is offered, and the object a later
        // replay will be built from - so what is published is a copy of it and
        // never it. Two screens sharing one record would otherwise be one
        // in-place edit away from disagreeing with the Backend.
        const reconciliationGeneration =
          stored.status === "success"
            ? reconciliationGenerationRef.current + 1
            : reconciliationGenerationRef.current;
        reconciliationGenerationRef.current = reconciliationGeneration;
        setSnapshot({
          session,
          caseId: requestedId,
          state: deliverTerminalState(stored),
          refreshState: "idle",
          reconciliationGeneration,
        });
      },
    };
    flight.listeners.add(subscription);

    if (reusable === null) {
      fetchCaseDetail(getOidcAuthClient(), requestedId, flight.controller.signal).then(
        (result) => {
          // The envelope's trace id stops here, and so does the envelope: what
          // is settled on is a new object carrying the ten contract fields, so
          // no screen can render a support reference it was never meant to show
          // and no serialization of this state arrives back at one.
          settleFlight(flight, {
            status: "success",
            data: projectCaseDetail(result.data.case),
          });
        },
        (error: unknown) => {
          // An abort is this hook's own decision, not an outcome to report.
          if (flight.controller.signal.aborted) {
            return;
          }
          settleFlight(flight, classifyFailure(error));
        },
      );
    } else {
      // Joining a request that has already answered. The answer was kept on the
      // flight precisely for this: a subscription that arrives after the
      // broadcast is handed the same sanitized outcome here, once, instead of
      // sitting in `loading` behind a request that has nothing left to say.
      //
      // Published synchronously, inside the effect rather than from a queued
      // callback: this subscription is provably live at this point - it was
      // created three statements ago and only its own cleanup can end it - so
      // deferring would open the very window this exists to close.
      const phase = flight.phase;
      if (phase.kind === "settled") {
        subscription.publish(phase.outcome);
      }
    }

    return () => {
      subscription.active = false;
      flight.listeners.delete(subscription);
      releaseFlight(flightRef, flight);
    };
  }, [session, requestedId, attempt]);

  useEffect(() => {
    return () => {
      const flight = refreshFlightRef.current;
      if (flight !== null && flight.phase === "pending") {
        flight.phase = "released";
        flight.controller.abort();
      }
      if (refreshFlightRef.current === flight) {
        refreshFlightRef.current = null;
      }
    };
  }, [session, requestedId]);

  const retry = useCallback(() => {
    if (!isRetryable(published.state)) {
      return;
    }
    setSnapshot({
      session: published.session,
      caseId: published.caseId,
      state: { status: "loading" },
      refreshState: "idle",
      reconciliationGeneration: published.reconciliationGeneration,
    });
    setAttempt((current) => current + 1);
  }, [published]);

  const refresh = useCallback((minimumVersion?: number) => {
    if (
      !mountedRef.current ||
      currentPublishedRef.current !== published ||
      published.state.status !== "success" ||
      published.session === null ||
      published.caseId === null
    ) {
      return;
    }
    if (
      minimumVersion !== undefined &&
      Number.isSafeInteger(minimumVersion) &&
      minimumVersion >= 0
    ) {
      const existingFloor = reconciliationFloorRef.current;
      reconciliationFloorRef.current = {
        session: published.session,
        caseId: published.caseId,
        minimumVersion:
          existingFloor !== null &&
          existingFloor.session === published.session &&
          existingFloor.caseId === published.caseId
            ? Math.max(existingFloor.minimumVersion, minimumVersion)
            : minimumVersion,
      };
    }
    if (refreshFlightRef.current?.phase === "pending") {
      return;
    }
    refreshGenerationRef.current += 1;
    const flight: RefreshFlight = {
      session: published.session,
      caseId: published.caseId,
      generation: refreshGenerationRef.current,
      controller: new AbortController(),
      phase: "pending",
    };
    refreshFlightRef.current = flight;
    setSnapshot({ ...published, refreshState: "refreshing" });

    fetchCaseDetail(getOidcAuthClient(), flight.caseId, flight.controller.signal).then(
      (result) => {
        if (flight.phase !== "pending") {
          return;
        }
        const current = currentIdentityRef.current;
        if (
          !mountedRef.current ||
          refreshFlightRef.current !== flight ||
          refreshGenerationRef.current !== flight.generation ||
          current.session !== flight.session ||
          current.caseId !== flight.caseId
        ) {
          flight.phase = "released";
          return;
        }
        const data = projectCaseDetail(result.data.case);
        const floor = reconciliationFloorRef.current;
        if (
          floor !== null &&
          floor.session === flight.session &&
          floor.caseId === flight.caseId &&
          data.concurrencyVersion < floor.minimumVersion
        ) {
          flight.phase = "settled";
          refreshFlightRef.current = null;
          setSnapshot((latest) =>
            latest.session === flight.session && latest.caseId === flight.caseId
              ? { ...latest, refreshState: "failed" }
              : latest,
          );
          return;
        }
        flight.phase = "settled";
        refreshFlightRef.current = null;
        if (
          floor !== null &&
          floor.session === flight.session &&
          floor.caseId === flight.caseId
        ) {
          reconciliationFloorRef.current = null;
        }
        const reconciliationGeneration = reconciliationGenerationRef.current + 1;
        reconciliationGenerationRef.current = reconciliationGeneration;
        setSnapshot({
          session: flight.session,
          caseId: flight.caseId,
          state: { status: "success", data },
          refreshState: "idle",
          reconciliationGeneration,
        });
      },
      (error: unknown) => {
        if (flight.phase !== "pending" || flight.controller.signal.aborted) {
          return;
        }
        const current = currentIdentityRef.current;
        if (
          !mountedRef.current ||
          refreshFlightRef.current !== flight ||
          refreshGenerationRef.current !== flight.generation ||
          current.session !== flight.session ||
          current.caseId !== flight.caseId
        ) {
          flight.phase = "released";
          return;
        }
        // Classification is deliberately after every current-flight and
        // mounted-subscriber gate. A released request, an older refresh intent
        // or a request for another session/case must not even construct a
        // terminal projection, let alone remove the record now on screen.
        const outcome = classifyFailure(error);
        flight.phase = "settled";
        refreshFlightRef.current = null;
        if (outcome.status === "forbidden" || outcome.status === "not-found") {
          // A current 403/404 is an authoritative visibility answer, including
          // when it arrives during a minimum-version reconciliation. Forget
          // both the protected success snapshot and its floor; the fixed
          // refusal carries no previous record or refresh metadata.
          const floor = reconciliationFloorRef.current;
          if (
            floor !== null &&
            floor.session === flight.session &&
            floor.caseId === flight.caseId
          ) {
            reconciliationFloorRef.current = null;
          }
          reconciliationGenerationRef.current = 0;
          setSnapshot({
            session: flight.session,
            caseId: flight.caseId,
            state: deliverTerminalState(outcome),
            refreshState: "idle",
            reconciliationGeneration: 0,
          });
          return;
        }
        setSnapshot((latest) =>
          latest.session === flight.session && latest.caseId === flight.caseId
            ? { ...latest, refreshState: "failed" }
            : latest,
        );
      },
    );
  }, [published]);

  return {
    state: published.state,
    retry,
    refresh,
    refreshState: published.refreshState,
    reconciliationGeneration: published.reconciliationGeneration,
  };
}

/**
 * Starts one request's bookkeeping. Nothing is sent from here: the caller
 * installs the flight and then makes exactly one `fetchCaseDetail()` call for
 * it, so "one flight, one network call" stays a property of one place.
 */
function createFlight(session: AuthSession, caseId: string, attempt: number): RequestFlight {
  return {
    session,
    caseId,
    attempt,
    controller: new AbortController(),
    subscribers: 1,
    phase: { kind: "pending" },
    listeners: new Set<Subscription>(),
  };
}

/**
 * Records one settled answer on its flight, then hands it to the subscriptions
 * entitled to hear it.
 *
 * The order is the point, and it is the opposite of the obvious one. The
 * outcome is computed and stored *before* anyone is asked whether they are
 * listening, because "nobody is listening at this instant" and "nobody will
 * ever listen" are different statements: a cleanup and its replacement
 * subscription are separated by a microtask, and an answer that lands in that
 * gap belongs to the replacement. Discarding it there is how a screen ends up
 * loading forever behind a request that already succeeded.
 *
 * Keeping it costs nothing that mattered. The conversion runs exactly once and
 * is pure - a field-by-field projection or a match against a closed set of
 * error types - so it writes nothing, calls nothing back, touches no session
 * and reaches no console or metric. What it produces is a value the hook could
 * have published as-is, and the raw result it was made from is not referenced
 * from the flight at all.
 *
 * Publishing is still gated on liveness, per subscription and at the moment of
 * publishing: storing an answer is not the same as showing one, and a
 * subscription React has torn down hears nothing here. What each live
 * subscription is handed is its own object, built from the stored outcome at
 * the instant of publishing - the outcome itself stays on the flight, where
 * only this file can reach it.
 */
function settleFlight(flight: RequestFlight, outcome: CaseDetailTerminalState): void {
  if (flight.phase.kind !== "pending") {
    return;
  }
  flight.phase = { kind: "settled", outcome };
  for (const subscription of [...flight.listeners]) {
    if (!subscription.active) {
      continue;
    }
    subscription.publish(outcome);
  }
}

/**
 * Drops one subscription's claim on a flight, and tears the flight down once
 * the last claim is gone.
 *
 * The decision is deferred by a microtask so StrictMode's setup-cleanup-setup
 * replay - and any other cleanup immediately followed by a re-subscription to
 * the same case - re-joins the flight before it is dismantled, which is why the
 * replay costs one network call rather than two.
 *
 * When the microtask does find nobody, the teardown is unconditional. A settled
 * flight is torn down exactly like a pending one: the ref is cleared, the
 * listeners are dropped and the stored answer goes with them. Whether it had
 * answered decides only what still needs cancelling - there is no request left
 * to abort once it has - never whether the flight may stay. Nothing of this
 * case survives a screen that is gone, so the next mount asks again rather than
 * being handed a record no live subscription ever asked for.
 */
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
    if (flight.subscribers > 0) {
      return;
    }
    if (flightRef.current === flight) {
      flightRef.current = null;
    }
    flight.listeners.clear();
    if (flight.phase.kind === "pending") {
      flight.controller.abort();
    }
    flight.phase = { kind: "released" };
  });
}
