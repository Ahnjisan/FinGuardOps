import { useCallback, useEffect, useRef, useState } from "react";
import type { AuthSession } from "../auth/authClient";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useAuth } from "../auth/useAuth";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import {
  fetchCaseAuditList,
  type CaseAuditEntry,
  type CaseAuditListSort,
  type CaseAuditPage,
} from "./caseAuditApi";
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
 * One page of one case's audit trail, as a React state machine.
 *
 * Built the same way as `useCaseDetail`, for a paginated read: the hook owns
 * which request is current, what happens to one that is no longer current, and
 * what the section may keep showing when the case, the page, the page size or
 * the session underneath it changes. What it does not own is the request
 * itself - that is `fetchCaseAuditList()`, which carries the endpoint registry,
 * the URL re-verification, the authorized transport and the response validator
 * that decides an audit entry per action *and* reason code. There is no second
 * definition of the audit DTO here, no relaxed copy of that validator, and no
 * path around any of them.
 *
 * The credential boundary is the same one. `getOidcAuthClient()` is called
 * here, inside an effect, and what it returns never leaves the closure: not
 * into state, not onto the returned object, not into a context. Everything the
 * React tree can reach from this hook is data or a fixed error kind - no token,
 * no trace id, no raw response, no error object and no client that could sign
 * one.
 *
 * The returned value is a *fresh* page built entry by entry and field by field,
 * never the validated object the parser produced and never the envelope around
 * it. The envelope's `caseId` and `traceId` and the transport's own trace id
 * are dropped here rather than in the component, so no screen has the option of
 * rendering a support reference it was never meant to show.
 *
 * Nothing retries by itself. One fetch per request, no replay after a failure,
 * no polling, and no second attempt after a 401.
 */

/**
 * The one sort this console asks for, and the only one it will ask for.
 *
 * Newest change first, fixed rather than offered: an audit trail read in
 * ascending order is the same facts in the order least useful to an
 * investigator, and a sort control would be a second query dimension for no
 * stated need. `FraudCaseAuditLogQueryValidator` accepts `changedAt,asc` as
 * well; that this client never sends it is a decision recorded here and
 * enforced by the type.
 */
const CASE_AUDIT_SORT: CaseAuditListSort = "changedAt,desc";

/** The page this section opens with. */
const CASE_AUDIT_INITIAL_PAGE = 0;

/** The page size this section opens with. */
const CASE_AUDIT_INITIAL_SIZE = 20;

/**
 * The page of audit entries a section is given, and the whole of it.
 *
 * `CaseAuditPage` is the API contract: it carries `caseId` because the response
 * is checked against the case that was asked for, and `traceId` because the
 * transport verifies that value against the response header before it will
 * accept the body at all. Both checks are finished by the time this hook is
 * handed a result, and neither value is anything a reader needs, so this type
 * admits exactly the two fields the section displays - `Pick` rather than a
 * second declaration, so a field added to the Backend contract cannot silently
 * fail to reach the console and a field removed from it stops compiling here.
 *
 * The `caseId` and `traceId` keys are not merely unrendered: they are not
 * present. Nothing in the React tree, in a devtools panel or in
 * `JSON.stringify` of this state can reach either, because neither value ever
 * crosses the state boundary.
 */
export type CaseAuditView = Pick<CaseAuditPage, "content" | "page">;

/**
 * The terminal answers a request can end at, and the only thing a request is ever
 * allowed to leave behind.
 *
 * Split out of `CaseAuditState` because they are exactly what a settled request
 * may be *stored* as: `idle` and `loading` describe the hook's own situation
 * and are never an answer. Each of them is already sanitized - a projected page,
 * or a fixed status, or one of a closed set of error kinds - so remembering an
 * outcome cannot become a way to keep a response, an error object, a Backend
 * message or a trace id alive.
 *
 * `not-found` and `forbidden` are statuses of their own rather than kinds of
 * `error`, because they are not failures to be tried again: a case that does
 * not exist and a case this session may not read are both settled answers, and
 * modelling them as retryable errors is how a section ends up offering a button
 * that can only produce the same 403.
 *
 * Module-private on purpose. It is the shape of what a *flight* remembers,
 * which is this file's own bookkeeping; what a section may see is
 * `CaseAuditState`, and every settled member of it is already reachable through
 * that union.
 */
type CaseAuditTerminalState =
  | { readonly status: "success"; readonly data: CaseAuditView }
  | { readonly status: "empty"; readonly data: CaseAuditView }
  /** HTTP 404. No such case, and the session is intact. */
  | { readonly status: "not-found" }
  /** HTTP 403. The session is intact; this user may not read this trail. */
  | { readonly status: "forbidden" }
  /** HTTP 401, or no usable local credential at send time. */
  | { readonly status: "authentication-required" }
  | { readonly status: "timeout" }
  | { readonly status: "network-error" }
  | { readonly status: "invalid-response" }
  /** Request rejection and every other Backend status. */
  | { readonly status: "generic-error" };

/** Every UI state in the Issue contract, with no secondary error discriminator. */
export type CaseAuditState =
  /**
   * Nothing to ask for: no authenticated session, or no canonical case id. No
   * request has been made and none will be.
   */
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | CaseAuditTerminalState;

export interface UseCaseAuditLogResult {
  readonly state: CaseAuditState;
  /** The page number currently asked for, zero-based. */
  readonly page: number;
  /** The page size currently asked for. */
  readonly size: number;
  /** Asks for another page of the same case at the same size. */
  readonly setPage: (pageNumber: number) => void;
  /**
   * Asks for another page size. The page number returns to the first page,
   * because a page number means nothing across a change of size: page 3 of 20
   * and page 3 of 100 are different windows onto the same trail, and keeping
   * the number would silently move the reader somewhere they did not ask to go.
   */
  readonly setSize: (size: number) => void;
  /**
   * Re-sends the request for the page currently on screen. Does nothing unless
   * the last attempt ended in a retryable `error`, so it cannot be used to
   * hammer the Backend from a success, a loading state, a 404 or a 403, and it
   * is only ever reached from a control the user operates. One call, one fetch.
   */
  readonly retry: () => void;
}

/**
 * The section's copy of one audit entry.
 *
 * Written per action rather than by spread, and to a fresh object rather than
 * to the validated one, so nothing from the parsed envelope - not an entry, not
 * a summary, not a metadata object - keeps an identity inside React state. The
 * switch is exhaustive over the six actions by type, which is also what keeps
 * the discriminated union intact on the way out: a `CASE_NOTE_CREATED` cannot
 * be rebuilt carrying a summary, and a `CASE_CREATED` cannot be rebuilt
 * carrying a note id, because neither branch has anywhere to put one.
 *
 * Nothing here rounds, trims, case-folds, re-formats or defaults a value, so an
 * entry on screen is the entry Backend sent.
 */
function projectEntry(entry: CaseAuditEntry): CaseAuditEntry {
  switch (entry.action) {
    case "CASE_CREATED":
      return {
        action: entry.action,
        reasonCode: entry.reasonCode,
        actorType: entry.actorType,
        changedAt: entry.changedAt,
        beforeSummary: null,
        afterSummary: { caseStatus: entry.afterSummary.caseStatus },
        metadata: {},
      };
    case "CASE_TRANSACTION_LINKED":
      return {
        action: entry.action,
        reasonCode: entry.reasonCode,
        actorType: entry.actorType,
        changedAt: entry.changedAt,
        beforeSummary: null,
        afterSummary: { linked: entry.afterSummary.linked },
        metadata: {},
      };
    // The two workflow actions are written out separately rather than sharing
    // one branch. They carry different reason enums, and a shared branch would
    // have to widen its result back to the whole union - which is exactly the
    // narrowing this projection exists to keep.
    case "CASE_STATUS_CHANGED":
      return {
        action: entry.action,
        reasonCode: entry.reasonCode,
        actorType: entry.actorType,
        changedAt: entry.changedAt,
        beforeSummary: {
          caseStatus: entry.beforeSummary.caseStatus,
          assigneeRef: entry.beforeSummary.assigneeRef,
        },
        afterSummary: {
          caseStatus: entry.afterSummary.caseStatus,
          assigneeRef: entry.afterSummary.assigneeRef,
        },
        metadata: {},
      };
    case "CASE_ASSIGNEE_CHANGED":
      return {
        action: entry.action,
        reasonCode: entry.reasonCode,
        actorType: entry.actorType,
        changedAt: entry.changedAt,
        beforeSummary: {
          caseStatus: entry.beforeSummary.caseStatus,
          assigneeRef: entry.beforeSummary.assigneeRef,
        },
        afterSummary: {
          caseStatus: entry.afterSummary.caseStatus,
          assigneeRef: entry.afterSummary.assigneeRef,
        },
        metadata: {},
      };
    case "CASE_RESOLVED":
      return {
        action: entry.action,
        reasonCode: entry.reasonCode,
        actorType: entry.actorType,
        changedAt: entry.changedAt,
        beforeSummary: {
          caseStatus: entry.beforeSummary.caseStatus,
          assigneeRef: entry.beforeSummary.assigneeRef,
        },
        afterSummary: {
          caseStatus: entry.afterSummary.caseStatus,
          assigneeRef: entry.afterSummary.assigneeRef,
          finalDisposition: entry.afterSummary.finalDisposition,
        },
        metadata: {},
      };
    case "CASE_NOTE_CREATED":
      return {
        action: entry.action,
        reasonCode: entry.reasonCode,
        actorType: entry.actorType,
        changedAt: entry.changedAt,
        beforeSummary: null,
        afterSummary: null,
        metadata: { noteId: entry.metadata.noteId },
      };
  }
}

/**
 * The one place a validated envelope becomes section state.
 *
 * `isCaseAuditPage` has already decided that this response is a page of audit
 * entries for the case that was asked for, and that its `traceId` matches the
 * header the transport saw. This function then keeps the two things the section
 * displays and drops the rest, and it does so by construction: a new array, a
 * new page object, a new object per entry and a new object per summary. No part
 * of the parsed envelope remains reachable from what React holds, so there is
 * no property lookup and no serialization that arrives back at a trace
 * identifier or at the echoed case id.
 *
 * The validator is not repeated here. Every value below has already been
 * admitted by it, and re-deciding any of them would be a second, divergent
 * contract.
 *
 * It is run a second time on the way *out* of a stored outcome, which is why
 * the parameter is the narrow view rather than the envelope: a validated
 * `CaseAuditPage` is accepted as one, and the two extra keys it carries have no
 * way through. Projecting twice is not redundant - the first copy separates
 * React state from the parsed response, the second separates one subscriber's
 * copy from the flight's own memory of it.
 */
function projectAuditPage(envelope: CaseAuditView): CaseAuditView {
  return {
    content: envelope.content.map(projectEntry),
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

/**
 * One subscriber's own copy of a settled answer.
 *
 * A flight remembers exactly one outcome and may hand it to more than one
 * subscription - the broadcast when it settles, and the replay a subscription
 * that joined afterwards is given - so "what is remembered" and "what is
 * delivered" have to be different objects, not one object seen twice. This is
 * where they part: nothing stored on a flight is ever passed to `setSnapshot`,
 * and every publish builds a new state, with a new page under it, out of the
 * stored one.
 *
 * That matters because React state is not enforced as immutable at runtime. A
 * consumer that wrote into the page it was handed - a sort in place, a
 * normalization, an `Object.assign` onto a summary - would otherwise be writing
 * into the flight's memory, and the next subscription to join that same flight
 * would be handed the edit as though the Backend had sent it. Freezing would
 * turn that into a silent no-op or a thrown error rather than a private edit,
 * which is a worse trade: the isolation here costs one projection per publish
 * and leaves an ordinary mutable value on the other side of the boundary, where
 * a caller may do as it likes with something nobody else can see.
 */
function deliverTerminalState(outcome: CaseAuditTerminalState): CaseAuditTerminalState {
  switch (outcome.status) {
    case "success":
      return { status: "success", data: projectAuditPage(outcome.data) };
    case "empty":
      return { status: "empty", data: projectAuditPage(outcome.data) };
    case "not-found":
      return { status: "not-found" };
    case "forbidden":
      return { status: "forbidden" };
    case "authentication-required":
      return { status: "authentication-required" };
    case "timeout":
      return { status: "timeout" };
    case "network-error":
      return { status: "network-error" };
    case "invalid-response":
      return { status: "invalid-response" };
    case "generic-error":
      return { status: "generic-error" };
  }
}

/**
 * The settled state a failed request produces.
 *
 * Nothing of the failure itself survives: not the error object, not a status
 * code, not a body, not a header and not a trace id. What comes back is one of
 * a closed set of values a section has fixed copy for.
 */
function classifyFailure(error: unknown): CaseAuditTerminalState {
  if (error instanceof TimeoutError) {
    return { status: "timeout" };
  }
  if (error instanceof NetworkError) {
    return { status: "network-error" };
  }
  if (error instanceof InvalidResponseError) {
    return { status: "invalid-response" };
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
    return { status: "authentication-required" };
  }
  if (error instanceof RequestNotAllowedError) {
    return { status: "generic-error" };
  }
  // 404 is the one remaining status this section has a meaning for. Every other
  // one collapses into the fixed generic state, which shows no status number.
  if (error instanceof HttpError && error.status === 404) {
    return { status: "not-found" };
  }
  return { status: "generic-error" };
}

function terminalSuccess(view: CaseAuditView): CaseAuditTerminalState {
  return view.content.length === 0
    ? { status: "empty", data: view }
    : { status: "success", data: view };
}

function isRetryable(state: CaseAuditState): boolean {
  return (
    state.status === "timeout" ||
    state.status === "network-error" ||
    state.status === "invalid-response" ||
    state.status === "generic-error"
  );
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
 * its answers being refused: a case change, a page change, a size change, a
 * session change and an unmount all close it at the instant they happen.
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
   * Neither path can be dropped - the first is how a live section is answered,
   * the second is how a section that arrived a microtask too late is - so the
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
  readonly publish: (stored: CaseAuditTerminalState) => void;
}

/**
 * What a flight has to say for itself, and the only thing it remembers.
 *
 * `pending` is a request still in the air. `settled` is a request that has
 * produced its one answer, already reduced to something the hook could publish
 * as-is: a projected page, or `not-found`, or `forbidden`, or one fixed error
 * kind. Nothing of the transport crosses that line - no `Response`, no
 * envelope, no `Error`, no status code, no header, no Backend code or message,
 * no trace id and no abort signal - so a remembered outcome is a remembered
 * *section state* and cannot be anything else.
 *
 * A settled outcome is the flight's own memory and is never handed out as it
 * stands: every publish copies it. That is what lets it be remembered at all -
 * a page two subscriptions shared would be one in-place edit away from showing
 * a trail the Backend never sent.
 *
 * `released` is a flight nobody is listening to any more. It is a phase rather
 * than a deletion because the flight object outlives its own removal from the
 * ref - late callbacks still close over it - and this is what says the answer
 * is gone rather than merely unreachable.
 */
type FlightPhase =
  | { readonly kind: "pending" }
  | { readonly kind: "settled"; readonly outcome: CaseAuditTerminalState }
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
 * first one's, so the request is shared while the permission to publish is not.
 *
 * `phase` is what makes that survive the narrow case where the answer arrives
 * *between* the two: the request settles while its only listener has just been
 * torn down and its replacement has not yet joined. The answer is still
 * computed, still sanitized and still kept - on the flight, not on a
 * subscription - so the replacement finds it waiting instead of waiting forever
 * on a request that will never speak again.
 */
interface RequestFlight {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly page: number;
  readonly size: number;
  readonly sort: CaseAuditListSort;
  readonly attempt: number;
  readonly controller: AbortController;
  subscribers: number;
  phase: FlightPhase;
  readonly listeners: Set<Subscription>;
}

/** Where in the trail the section is currently looking. */
interface Position {
  readonly page: number;
  readonly size: number;
}

const INITIAL_POSITION: Position = Object.freeze({
  page: CASE_AUDIT_INITIAL_PAGE,
  size: CASE_AUDIT_INITIAL_SIZE,
});

/**
 * The position, together with what it is a position *in*.
 *
 * Held as one value so that a case or a session change resets the page and the
 * size in the same step that invalidates the state they belong to. A page
 * number that outlived its case would ask page 4 of a trail the reader has only
 * just opened.
 */
interface Cursor {
  readonly session: AuthSession | null;
  readonly caseId: string | null;
  readonly position: Position;
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
  /**
   * The page and size this state was produced for, held by *value* rather than
   * by the cursor's object identity.
   *
   * The session and the case are compared by reference because that is what
   * they are - one frozen session object per session, one string per case. A
   * position is neither: the cursor builds a new object every time the reader
   * pages, and a publish builds one of its own. Comparing those by identity
   * would make every publish look like a position change, which would reset the
   * state it had just published and ask again, forever.
   */
  readonly page: number;
  readonly size: number;
  readonly state: CaseAuditState;
}

/**
 * Loads one page of one case's audit trail for the current session.
 *
 * `caseId` must already be the canonical lowercase UUID v4 the route carries,
 * or `null` when the address is not one. It is re-checked here anyway: this
 * hook is the last place before a credential is fetched, and a value that
 * reached it from untyped data must cost zero credential lookups and zero
 * fetches rather than be spent on the 400 the Backend would answer.
 *
 * The page and the size live here rather than in the address bar. They are a
 * reading position inside one section of one screen, not a different screen: a
 * shared `/cases/{caseId}?auditPage=3` would promise a stable view it cannot
 * keep, because the trail grows underneath it.
 */
export function useCaseAuditLog(caseId: string | null): UseCaseAuditLogResult {
  const { state: authState } = useAuth();
  const session = authState.status === "authenticated" ? authState.session : null;

  // A non-canonical id is the same as no id at all: nothing is requested, and
  // the value never reaches the URL builder, the transport or an error string.
  const requestedId = caseId !== null && isCanonicalUuidV4(caseId) ? caseId : null;

  const [attempt, setAttempt] = useState(0);
  const [cursor, setCursor] = useState<Cursor>(() => ({
    session: null,
    caseId: requestedId,
    position: INITIAL_POSITION,
  }));
  const [snapshot, setSnapshot] = useState<Snapshot>(() => ({
    session: null,
    caseId: requestedId,
    page: INITIAL_POSITION.page,
    size: INITIAL_POSITION.size,
    state: { status: "idle" },
  }));
  const flightRef = useRef<RequestFlight | null>(null);

  // Both adjustments below are made during render on purpose. Case A's trail
  // must not appear under case B's heading for even one frame, page 1 must not
  // stay on screen while page 2 loads, and a session that has just been signed
  // out, replaced or invalidated must take its data with it immediately. An
  // effect runs after the browser has already been given something to paint, so
  // waiting for cleanup would show exactly those frames.
  let currentCursor = cursor;
  if (cursor.session !== session || cursor.caseId !== requestedId) {
    currentCursor = { session, caseId: requestedId, position: INITIAL_POSITION };
    setCursor(currentCursor);
  }
  const page = currentCursor.position.page;
  const size = currentCursor.position.size;

  let published = snapshot;
  if (snapshot.session !== session || snapshot.caseId !== requestedId || snapshot.page !== page || snapshot.size !== size) {
    published = {
      session,
      caseId: requestedId,
      page,
      size,
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

    // A flight is reused for what it is *for* - this session, this case, this
    // page, this size, the fixed sort, this attempt - and not for whether it
    // has answered yet.
    // A request that settled while nobody was listening is still this section's
    // request and still has this section's answer; refusing to reuse it would
    // spend a second Backend call to be told the same thing. A released flight
    // is the one exception: its answer has been discarded on purpose, so there
    // is nothing to rejoin.
    const existing = flightRef.current;
    const reusable =
      existing !== null &&
      existing.phase.kind !== "released" &&
      existing.session === session &&
      existing.caseId === requestedId &&
      existing.page === page &&
      existing.size === size &&
      existing.sort === CASE_AUDIT_SORT &&
      existing.attempt === attempt
        ? existing
        : null;

    // A superseded request is cancelled as far as the platform allows, so a
    // page the analyst has already moved off stops consuming a connection.
    // Cancellation is a courtesy, not the correctness argument: the gates in
    // `publish` below stand whether or not the abort is honoured.
    if (existing !== null && reusable === null && existing.phase.kind === "pending") {
      existing.controller.abort();
    }

    const flight = reusable ?? createFlight(session, requestedId, page, size, attempt);
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
      publish: (stored: CaseAuditTerminalState): void => {
        // Four independent gates, and every one of them is load-bearing.
        //
        // The first is this subscription's own lifetime. Cleanup clears it
        // synchronously, while the flight it joined is deliberately still
        // installed - the flight is only released a microtask later - so an
        // answer that had already settled when the cleanup ran, and whose
        // callback is therefore queued ahead of that microtask, publishes
        // nothing.
        //
        // The second is exactly-once. A settled flight can reach a subscription
        // twice - once as the broadcast, once as the replay a late joiner is
        // given - and this is where the second one stops.
        //
        // The third is latest-wins, decided by identity rather than by a
        // timestamp: a late answer belonging to an earlier page, an earlier
        // case or an earlier session finds itself no longer in the ref and
        // publishes nothing at all.
        //
        // The fourth re-reads what the installed flight is *for*. It is the
        // same six values this effect closed over, checked again at the
        // instant of publishing, so a stored answer cannot be handed to another
        // page, another case, another session or another attempt even if it
        // somehow outlived them.
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
          current.page !== page ||
          current.size !== size ||
          current.sort !== CASE_AUDIT_SORT ||
          current.attempt !== attempt
        ) {
          return;
        }
        subscription.delivered = true;
        // Past the gates, and the one step here that is not one. `stored` is
        // the flight's own memory of its answer - the same object every
        // subscription of this flight is offered, and the object a later replay
        // will be built from - so what is published is a copy of it and never
        // it.
        setSnapshot({
          session,
          caseId: requestedId,
          page,
          size,
          state: deliverTerminalState(stored),
        });
      },
    };
    flight.listeners.add(subscription);

    if (reusable === null) {
      fetchCaseAuditList(
        getOidcAuthClient(),
        requestedId,
        { page, size, sort: flight.sort },
        flight.controller.signal,
      ).then(
        (result) => {
          // The envelope's trace id stops here, and so does the envelope and
          // the case id it echoed: what is settled on is a new page carrying
          // the entries and the page metadata alone, so no section can render a
          // support reference it was never meant to show.
          settleFlight(flight, () => terminalSuccess(projectAuditPage(result.data)));
        },
        (error: unknown) => {
          // An abort is this hook's own decision, not an outcome to report.
          if (flight.controller.signal.aborted) {
            return;
          }
          settleFlight(flight, () => classifyFailure(error));
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
  }, [session, requestedId, page, size, attempt]);

  const setPage = useCallback((pageNumber: number) => {
    setCursor((current) => ({
      session: current.session,
      caseId: current.caseId,
      position: { page: pageNumber, size: current.position.size },
    }));
  }, []);

  const setSize = useCallback((nextSize: number) => {
    setCursor((current) => ({
      session: current.session,
      caseId: current.caseId,
      // Back to the first page. See `UseCaseAuditLogResult.setSize`.
      position: { page: CASE_AUDIT_INITIAL_PAGE, size: nextSize },
    }));
  }, []);

  const retry = useCallback(() => {
    if (!isRetryable(published.state)) {
      return;
    }
    setSnapshot({
      session: published.session,
      caseId: published.caseId,
      page: published.page,
      size: published.size,
      state: { status: "loading" },
    });
    setAttempt((current) => current + 1);
  }, [published]);

  return { state: published.state, page, size, setPage, setSize, retry };
}

/**
 * Starts one request's bookkeeping. Nothing is sent from here: the caller
 * installs the flight and then makes exactly one `fetchCaseAuditList()` call
 * for it, so "one flight, one network call" stays a property of one place.
 */
function createFlight(
  session: AuthSession,
  caseId: string,
  page: number,
  size: number,
  attempt: number,
): RequestFlight {
  return {
    session,
    caseId,
    page,
    size,
    sort: CASE_AUDIT_SORT,
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
 * The order is the point. The outcome is computed and stored *before* anyone
 * is asked whether they are listening, but only after the flight proves it is
 * still pending. "Nobody is listening at this instant" and "nobody will ever
 * listen" are different statements: a pending flight in the cleanup grace
 * window must retain its answer for the replacement subscription, while a
 * released flight must not inspect a late payload or classify a late failure.
 *
 * Keeping it costs nothing that mattered. The conversion runs exactly once and
 * is pure - a field-by-field projection or a match against a closed set of
 * error types - so it writes nothing, calls nothing back, touches no session
 * and reaches no console or metric.
 *
 * Publishing is still gated on liveness, per subscription and at the moment of
 * publishing: storing an answer is not the same as showing one, and a
 * subscription React has torn down hears nothing here.
 */
function settleFlight(
  flight: RequestFlight,
  createOutcome: () => CaseAuditTerminalState,
): void {
  if (flight.phase.kind !== "pending") {
    return;
  }
  // No asynchronous boundary belongs between the phase check and storing the
  // answer. A released or already-settled flight therefore never invokes the
  // factory, while a zero-listener flight still inside its grace window does.
  const outcome = createOutcome();
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
 * the same page - re-joins the flight before it is dismantled, which is why the
 * replay costs one network call rather than two.
 *
 * When the microtask does find nobody, the teardown is unconditional. A settled
 * flight is torn down exactly like a pending one: the ref is cleared, the
 * listeners are dropped and the stored answer goes with them. Whether it had
 * answered decides only what still needs cancelling - there is no request left
 * to abort once it has - never whether the flight may stay. Nothing of this
 * trail survives a section that is gone, so the next mount asks again rather
 * than being handed a page no live subscription ever asked for.
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
