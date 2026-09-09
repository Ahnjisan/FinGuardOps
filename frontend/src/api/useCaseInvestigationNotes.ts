import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
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
import {
  fetchInvestigationNoteList,
  type InvestigationNote,
  type NoteListSort,
} from "./investigationNoteApi";
import type { PageMetadata } from "./pagination";

/** The only ordering the investigation-notes section requests. */
const NOTE_SORT: NoteListSort = "createdAt,asc";

/** Fields a note reader may receive after request/response case binding. */
export type CaseInvestigationNoteItem = Pick<
  InvestigationNote,
  "noteId" | "authorType" | "authorRef" | "content" | "createdAt"
>;

/** The complete public success projection. No envelope caseId or traceId survives. */
export interface CaseInvestigationNotesView {
  readonly items: readonly CaseInvestigationNoteItem[];
  readonly page: PageMetadata;
}

type CaseInvestigationNotesTerminalState =
  | { readonly status: "success"; readonly data: CaseInvestigationNotesView }
  | { readonly status: "empty"; readonly data: CaseInvestigationNotesView }
  | { readonly status: "not-found" }
  | { readonly status: "forbidden" }
  | { readonly status: "authentication-required" }
  | { readonly status: "timeout" }
  | { readonly status: "network-error" }
  | { readonly status: "invalid-response" }
  | { readonly status: "generic-error" };

export type CaseInvestigationNotesState =
  | { readonly status: "idle" }
  | { readonly status: "loading" }
  | CaseInvestigationNotesTerminalState;

/** Deliberately only these two keys cross the hook boundary. */
export interface UseCaseInvestigationNotesResult {
  readonly state: CaseInvestigationNotesState;
  readonly retry: () => void;
  /** Finds and publishes the latest authoritative last page in at most two GETs. */
  readonly refresh: () => void;
  readonly refreshState: "idle" | "refreshing" | "failed";
}

function projectItem(note: CaseInvestigationNoteItem): CaseInvestigationNoteItem {
  return {
    noteId: note.noteId,
    authorType: note.authorType,
    authorRef: note.authorRef,
    content: note.content,
    createdAt: note.createdAt,
  };
}

/**
 * Separates a validated response, a stored outcome and every subscriber's
 * delivery. The array, page and each item are recreated on every call so a
 * nested mutation at any one boundary cannot reach another.
 */
function projectPage(source: CaseInvestigationNotesView): CaseInvestigationNotesView {
  return {
    items: source.items.map(projectItem),
    page: {
      number: source.page.number,
      size: source.page.size,
      totalElements: source.page.totalElements,
      totalPages: source.page.totalPages,
      first: source.page.first,
      last: source.page.last,
    },
  };
}

function terminalSuccess(view: CaseInvestigationNotesView): CaseInvestigationNotesTerminalState {
  return view.items.length === 0
    ? { status: "empty", data: view }
    : { status: "success", data: view };
}

function classifyFailure(error: unknown): CaseInvestigationNotesTerminalState {
  if (error instanceof TimeoutError) {
    return { status: "timeout" };
  }
  if (error instanceof NetworkError) {
    return { status: "network-error" };
  }
  if (error instanceof InvalidResponseError) {
    return { status: "invalid-response" };
  }
  if (error instanceof ForbiddenError) {
    return { status: "forbidden" };
  }
  if (error instanceof UnauthorizedError || error instanceof AuthenticationRequiredError) {
    return { status: "authentication-required" };
  }
  if (error instanceof HttpError && error.status === 404) {
    return { status: "not-found" };
  }
  if (error instanceof RequestNotAllowedError) {
    return { status: "generic-error" };
  }
  return { status: "generic-error" };
}

function deliverTerminal(
  stored: CaseInvestigationNotesTerminalState,
): CaseInvestigationNotesTerminalState {
  if (stored.status === "success") {
    return { status: "success", data: projectPage(stored.data) };
  }
  if (stored.status === "empty") {
    return { status: "empty", data: projectPage(stored.data) };
  }
  return { status: stored.status };
}

function isRetryable(state: CaseInvestigationNotesState): boolean {
  return (
    state.status === "timeout" ||
    state.status === "network-error" ||
    state.status === "invalid-response" ||
    state.status === "generic-error"
  );
}

interface Subscription {
  active: boolean;
  delivered: boolean;
  readonly publish: (stored: CaseInvestigationNotesTerminalState) => void;
}

type FlightPhase =
  | { readonly kind: "pending" }
  | { readonly kind: "settled"; readonly outcome: CaseInvestigationNotesTerminalState }
  | { readonly kind: "released" };

interface RequestFlight {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly page: number;
  readonly size: number;
  readonly sort: NoteListSort;
  readonly attempt: number;
  readonly controller: AbortController;
  subscribers: number;
  phase: FlightPhase;
  readonly listeners: Set<Subscription>;
}

interface Snapshot {
  readonly session: AuthSession | null;
  readonly caseId: string | null;
  readonly page: number;
  readonly size: number;
  readonly state: CaseInvestigationNotesState;
  readonly refreshState: "idle" | "refreshing" | "failed";
}

interface RefreshFlight {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly page: number;
  readonly size: number;
  readonly generation: number;
  readonly paginationIntent: number;
  readonly controller: AbortController;
  phase: "pending" | "settled" | "released";
}

interface AuthoritativeRefreshPage {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly page: number;
  readonly size: number;
}

interface PaginationIdentity {
  readonly session: AuthSession | null;
  readonly caseId: string | null;
  readonly page: number;
  readonly size: number;
}

/**
 * Loads exactly one requested page for the current authenticated session.
 * Pagination remains the section's local UI state; this hook receives that
 * position as request identity and exposes no cursor mutator of its own.
 */
export function useCaseInvestigationNotes(
  caseId: string | null,
  page: number,
  size: number,
  onAuthoritativePage?: (page: number) => void,
): UseCaseInvestigationNotesResult {
  const { state: authState } = useAuth();
  const session = authState.status === "authenticated" ? authState.session : null;
  const requestedId = caseId !== null && isCanonicalUuidV4(caseId) ? caseId : null;
  const validPosition =
    Number.isSafeInteger(page) && page >= 0 && Number.isSafeInteger(size) && size >= 1 && size <= 100;

  const [attempt, setAttempt] = useState(0);
  const [snapshot, setSnapshot] = useState<Snapshot>(() => ({
    session: null,
    caseId: requestedId,
    page,
    size,
    state: { status: "idle" },
    refreshState: "idle",
  }));
  const flightRef = useRef<RequestFlight | null>(null);
  const refreshFlightRef = useRef<RefreshFlight | null>(null);
  const refreshGenerationRef = useRef(0);
  const paginationIntentRef = useRef(0);
  const authoritativeRefreshRef = useRef<AuthoritativeRefreshPage | null>(null);
  const authoritativePageCallbackRef = useRef(onAuthoritativePage);
  const paginationIdentityRef = useRef<PaginationIdentity>({
    session,
    caseId: requestedId,
    page,
    size,
  });
  const currentIdentityRef = useRef({
    session,
    caseId: requestedId,
    page,
    size,
    paginationIntent: 0,
  });

  useLayoutEffect(() => {
    const previousPagination = paginationIdentityRef.current;
    if (
      previousPagination.session !== session ||
      previousPagination.caseId !== requestedId ||
      previousPagination.page !== page ||
      previousPagination.size !== size
    ) {
      const authoritative = authoritativeRefreshRef.current;
      const refreshOwnedTransition =
        authoritative !== null &&
        authoritative.session === session &&
        authoritative.caseId === requestedId &&
        authoritative.page === page &&
        authoritative.size === size;
      if (!refreshOwnedTransition) {
        paginationIntentRef.current += 1;
        const refreshFlight = refreshFlightRef.current;
        if (refreshFlight !== null && refreshFlight.phase === "pending") {
          refreshFlight.phase = "released";
          refreshFlight.controller.abort();
          refreshFlightRef.current = null;
        }
        authoritativeRefreshRef.current = null;
      }
      paginationIdentityRef.current = { session, caseId: requestedId, page, size };
    }
    currentIdentityRef.current = {
      session,
      caseId: requestedId,
      page,
      size,
      paginationIntent: paginationIntentRef.current,
    };
  }, [session, requestedId, page, size]);

  useEffect(() => {
    authoritativePageCallbackRef.current = onAuthoritativePage;
  }, [onAuthoritativePage]);

  // Clear stale content during render. Waiting for effect cleanup would allow
  // one case, page, size or session to be painted under another identity.
  let published = snapshot;
  if (
    snapshot.session !== session ||
    snapshot.caseId !== requestedId ||
    snapshot.page !== page ||
    snapshot.size !== size
  ) {
    published = {
      session,
      caseId: requestedId,
      page,
      size,
      state:
        session === null || requestedId === null || !validPosition
          ? { status: "idle" }
          : { status: "loading" },
      refreshState: "idle",
    };
    setSnapshot(published);
  }

  useEffect(() => {
    if (session === null || requestedId === null || !validPosition) {
      return;
    }

    const authoritative = authoritativeRefreshRef.current;
    if (
      authoritative !== null &&
      authoritative.session === session &&
      authoritative.caseId === requestedId &&
      authoritative.page === page &&
      authoritative.size === size
    ) {
      authoritativeRefreshRef.current = null;
      return;
    }

    const existing = flightRef.current;
    const reusable =
      existing !== null &&
      existing.phase.kind !== "released" &&
      existing.session === session &&
      existing.caseId === requestedId &&
      existing.page === page &&
      existing.size === size &&
      existing.sort === NOTE_SORT &&
      existing.attempt === attempt
        ? existing
        : null;

    if (existing !== null && reusable === null && existing.phase.kind === "pending") {
      existing.controller.abort();
    }

    const flight = reusable ?? createFlight(session, requestedId, page, size, attempt);
    if (reusable === null) {
      flightRef.current = flight;
    } else {
      flight.subscribers += 1;
    }

    const subscription: Subscription = {
      active: true,
      delivered: false,
      publish: (stored): void => {
        if (!subscription.active || subscription.delivered) {
          return;
        }
        const current = flightRef.current;
        if (
          current !== flight ||
          current.session !== session ||
          current.caseId !== requestedId ||
          current.page !== page ||
          current.size !== size ||
          current.sort !== NOTE_SORT ||
          current.attempt !== attempt
        ) {
          return;
        }
        subscription.delivered = true;
        setSnapshot({
          session,
          caseId: requestedId,
          page,
          size,
          state: deliverTerminal(stored),
          refreshState: "idle",
        });
      },
    };
    flight.listeners.add(subscription);

    if (reusable === null) {
      fetchInvestigationNoteList(
        getOidcAuthClient(),
        requestedId,
        { page, size, sort: flight.sort },
        flight.controller.signal,
      ).then(
        (result) => {
          // caseId binding was already enforced by the API client. Project the
          // five display fields and page metadata before storing an outcome.
          settleFlight(flight, () =>
            terminalSuccess(projectPage({ items: result.data.items, page: result.data.page })),
          );
        },
        (error: unknown) => {
          if (flight.controller.signal.aborted) {
            return;
          }
          settleFlight(flight, () => classifyFailure(error));
        },
      );
    } else if (flight.phase.kind === "settled") {
      subscription.publish(flight.phase.outcome);
    }

    return () => {
      subscription.active = false;
      flight.listeners.delete(subscription);
      releaseFlight(flightRef, flight);
    };
  }, [session, requestedId, page, size, validPosition, attempt]);

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
      authoritativeRefreshRef.current = null;
    };
  }, [session, requestedId, size]);

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
      refreshState: "idle",
    });
    setAttempt((current) => current + 1);
  }, [published]);

  const refresh = useCallback(() => {
    if (
      published.session === null ||
      published.caseId === null ||
      !validPosition ||
      refreshFlightRef.current?.phase === "pending"
    ) {
      return;
    }
    const ordinary = flightRef.current;
    if (ordinary !== null && ordinary.phase.kind === "pending") {
      ordinary.phase = { kind: "released" };
      ordinary.listeners.clear();
      ordinary.controller.abort();
      flightRef.current = null;
    }
    refreshGenerationRef.current += 1;
    const flight: RefreshFlight = {
      session: published.session,
      caseId: published.caseId,
      page: published.page,
      size: published.size,
      generation: refreshGenerationRef.current,
      paginationIntent: paginationIntentRef.current,
      controller: new AbortController(),
      phase: "pending",
    };
    refreshFlightRef.current = flight;
    setSnapshot({ ...published, refreshState: "refreshing" });

    const stillCurrent = (): boolean => {
      const current = currentIdentityRef.current;
      return (
        flight.phase === "pending" &&
        refreshFlightRef.current === flight &&
        current.session === flight.session &&
        current.caseId === flight.caseId &&
        current.page === flight.page &&
        current.size === flight.size &&
        current.paginationIntent === flight.paginationIntent
      );
    };

    const fail = (): void => {
      if (!stillCurrent() || flight.controller.signal.aborted) {
        return;
      }
      flight.phase = "settled";
      refreshFlightRef.current = null;
      setSnapshot((latest) =>
        latest.session === flight.session && latest.caseId === flight.caseId
          ? { ...latest, refreshState: "failed" }
          : latest,
      );
    };

    const publish = (view: CaseInvestigationNotesView): void => {
      if (!stillCurrent()) {
        return;
      }
      const projected = terminalSuccess(projectPage(view));
      const resolvedPage = view.page.number;
      flight.phase = "settled";
      refreshFlightRef.current = null;
      authoritativeRefreshRef.current = {
        session: flight.session,
        caseId: flight.caseId,
        page: resolvedPage,
        size: flight.size,
      };
      setSnapshot({
        session: flight.session,
        caseId: flight.caseId,
        page: resolvedPage,
        size: flight.size,
        state: deliverTerminal(projected),
        refreshState: "idle",
      });
      authoritativePageCallbackRef.current?.(resolvedPage);
      if (page === resolvedPage) {
        authoritativeRefreshRef.current = null;
      }
    };

    fetchInvestigationNoteList(
      getOidcAuthClient(),
      flight.caseId,
      { page: 0, size: flight.size, sort: NOTE_SORT },
      flight.controller.signal,
    ).then(
      (metadataResult) => {
        if (!stillCurrent()) {
          return;
        }
        const latestPage = Math.max(0, metadataResult.data.page.totalPages - 1);
        if (latestPage === 0) {
          publish({ items: metadataResult.data.items, page: metadataResult.data.page });
          return;
        }
        fetchInvestigationNoteList(
          getOidcAuthClient(),
          flight.caseId,
          { page: latestPage, size: flight.size, sort: NOTE_SORT },
          flight.controller.signal,
        ).then(
          (pageResult) => {
            publish({ items: pageResult.data.items, page: pageResult.data.page });
          },
          fail,
        );
      },
      fail,
    );
  }, [page, published, validPosition]);

  return {
    state: published.state,
    retry,
    refresh,
    refreshState: published.refreshState,
  };
}

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
    sort: NOTE_SORT,
    attempt,
    controller: new AbortController(),
    subscribers: 1,
    phase: { kind: "pending" },
    listeners: new Set<Subscription>(),
  };
}

/**
 * The terminal factory is lazy: released and duplicate settlements return
 * before projection or classification. A zero-listener flight still inside
 * its grace window stores the answer for a same-key replay.
 */
function settleFlight(
  flight: RequestFlight,
  createOutcome: () => CaseInvestigationNotesTerminalState,
): void {
  if (flight.phase.kind !== "pending") {
    return;
  }
  const outcome = createOutcome();
  flight.phase = { kind: "settled", outcome };
  for (const subscription of [...flight.listeners]) {
    if (subscription.active) {
      subscription.publish(outcome);
    }
  }
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
