import { useCallback, useEffect, useRef, useState } from "react";
import { resolveCapabilities } from "../auth/capabilities";
import type { AuthSession } from "../auth/authClient";
import { getOidcAuthClient } from "../auth/oidcAuthClient";
import { useAuth } from "../auth/useAuth";
import { isCanonicalUuidV4 } from "./backendEndpoints";
import type { CaseStatus } from "./caseApi";
import {
  AuthenticationRequiredError,
  ForbiddenError,
  HttpError,
  NetworkError,
  TimeoutError,
  UnauthorizedError,
} from "./errors";
import { createInvestigationNote } from "./investigationNoteApi";
import { isNoteContentString } from "./responseValidation";

export const INVESTIGATION_NOTE_VALIDATION_MESSAGE =
  "Enter 1–4,000 Unicode characters and include at least one non-whitespace character.";

export const INVESTIGATION_NOTE_SUCCESS_MESSAGE = "Investigation note added.";

export const INVESTIGATION_NOTE_FAILURE_MESSAGE =
  "The investigation note could not be added. Your text has been kept.";

export const INVESTIGATION_NOTE_CONFLICT_MESSAGE =
  "This case changed. Review the latest case information, then submit again.";

export type CreateInvestigationNoteState =
  | { readonly status: "idle" }
  | { readonly status: "submitting"; readonly submission: number }
  | { readonly status: "success"; readonly submission: number }
  | { readonly status: "validation-error"; readonly submission: number }
  | { readonly status: "conflict"; readonly submission: number }
  | { readonly status: "forbidden"; readonly submission: number }
  | { readonly status: "not-found"; readonly submission: number }
  | { readonly status: "authentication-required"; readonly submission: number }
  | { readonly status: "timeout"; readonly submission: number }
  | { readonly status: "network-error"; readonly submission: number }
  | { readonly status: "server-error"; readonly submission: number };

export interface CreateInvestigationNoteContext {
  readonly caseId: string | null;
  readonly caseStatus: CaseStatus | null;
  readonly expectedVersion: number | null;
  /** Increments only after an authoritative detail read is published. */
  readonly reconciliationGeneration: number;
  /** Starts the independent notes/detail/audit read reconciliation. */
  readonly onReconcile: (minimumDetailVersion?: number) => void;
}

export interface UseCreateInvestigationNoteResult {
  readonly state: CreateInvestigationNoteState;
  readonly submit: (content: string) => void;
  readonly reset: () => void;
  readonly waitingForReconciliation: boolean;
}

interface CurrentContext extends CreateInvestigationNoteContext {
  readonly session: AuthSession | null;
  readonly mayWrite: boolean;
}

type TerminalState = Exclude<CreateInvestigationNoteState, { readonly status: "idle" | "submitting" }>;

type FlightPhase = "pending" | "settled" | "released";

interface MutationFlight {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly expectedVersion: number;
  readonly submission: number;
  readonly reconciliationGeneration: number;
  readonly controller: AbortController;
  phase: FlightPhase;
}

interface ReconciliationBlock {
  readonly session: AuthSession;
  readonly caseId: string;
  readonly generation: number;
  readonly minimumDetailVersion: number | null;
  readonly submission: number;
}

export function countInvestigationNoteCodePoints(content: string): number {
  return Array.from(content).length;
}

export function isWritableCaseStatus(status: CaseStatus | null): boolean {
  return status === "IN_REVIEW" || status === "ADDITIONAL_INFORMATION_REQUIRED";
}

function classifyFailure(error: unknown, submission: number): TerminalState {
  if (error instanceof TimeoutError) {
    return { status: "timeout", submission };
  }
  if (error instanceof NetworkError) {
    return { status: "network-error", submission };
  }
  if (error instanceof ForbiddenError) {
    return { status: "forbidden", submission };
  }
  if (error instanceof UnauthorizedError || error instanceof AuthenticationRequiredError) {
    return { status: "authentication-required", submission };
  }
  if (error instanceof HttpError && error.status === 404) {
    return { status: "not-found", submission };
  }
  if (error instanceof HttpError && error.status === 409) {
    return { status: "conflict", submission };
  }
  return { status: "server-error", submission };
}

function needsReadReconciliation(status: TerminalState["status"]): boolean {
  return (
    status === "success" ||
    status === "conflict" ||
    status === "timeout" ||
    status === "network-error"
  );
}

/**
 * Owns one append-only note POST at a time.
 *
 * The raw content remains only in the caller's textarea state and in the
 * request closure passed directly to the API client. It is never copied into a
 * flight, terminal state, callback argument or error value.
 */
export function useCreateInvestigationNote(
  context: CreateInvestigationNoteContext,
): UseCreateInvestigationNoteResult {
  const { state: authState } = useAuth();
  const session = authState.status === "authenticated" ? authState.session : null;
  const mayWrite =
    session !== null && resolveCapabilities(session.roles).has("case:note-write");

  const currentRef = useRef<CurrentContext>({ ...context, session, mayWrite });
  const mountedRef = useRef(false);
  const flightRef = useRef<MutationFlight | null>(null);
  const submissionRef = useRef(0);
  const reconciliationBlockRef = useRef<ReconciliationBlock | null>(null);
  const stateIdentityRef = useRef({ session, caseId: context.caseId });
  const [state, setState] = useState<CreateInvestigationNoteState>({ status: "idle" });
  const [reconciliationBlock, setReconciliationBlock] =
    useState<ReconciliationBlock | null>(null);

  useEffect(() => {
    currentRef.current = { ...context, session, mayWrite };
  }, [context, session, mayWrite]);

  useEffect(() => {
    const previous = stateIdentityRef.current;
    if (previous.session !== session || previous.caseId !== context.caseId) {
      stateIdentityRef.current = { session, caseId: context.caseId };
      reconciliationBlockRef.current = null;
      setReconciliationBlock(null);
      setState({ status: "idle" });
    }
  }, [session, context.caseId]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      const flight = flightRef.current;
      if (flight !== null && flight.phase === "pending") {
        flight.phase = "released";
        flight.controller.abort();
      }
      if (flightRef.current === flight) {
        flightRef.current = null;
      }
    };
  }, []);

  // A session, case, status or version change releases a request belonging to
  // the old form identity. The callback gates below remain the correctness
  // boundary even if the platform cannot cancel the transport.
  useEffect(() => {
    const flight = flightRef.current;
    if (
      flight !== null &&
      flight.phase === "pending" &&
      (flight.session !== session ||
        flight.caseId !== context.caseId ||
        flight.expectedVersion !== context.expectedVersion)
    ) {
      flight.phase = "released";
      flight.controller.abort();
      flightRef.current = null;
    }
  }, [session, context.caseId, context.caseStatus, context.expectedVersion]);

  const settleFlight = useCallback(
    (
      flight: MutationFlight,
      createTerminal: () => TerminalState,
      minimumDetailVersion: number | null = null,
    ): void => {
      if (flight.phase !== "pending") {
        return;
      }
      const current = currentRef.current;
      if (
        !mountedRef.current ||
        flightRef.current !== flight ||
        current.session !== flight.session ||
        current.caseId !== flight.caseId ||
        current.expectedVersion !== flight.expectedVersion
      ) {
        flight.phase = "released";
        return;
      }
      const terminal = createTerminal();
      flight.phase = "settled";
      flightRef.current = null;
      setState(terminal);
      if (needsReadReconciliation(terminal.status)) {
        reconciliationBlockRef.current = {
          session: flight.session,
          caseId: flight.caseId,
          generation: flight.reconciliationGeneration,
          minimumDetailVersion,
          submission: flight.submission,
        };
        setReconciliationBlock(reconciliationBlockRef.current);
        current.onReconcile(minimumDetailVersion ?? undefined);
      }
    },
    [],
  );

  const submit = useCallback((content: string) => {
    const current = currentRef.current;
    const existing = flightRef.current;
    if (existing !== null && existing.phase === "pending") {
      return;
    }

    const nextSubmission = submissionRef.current + 1;
    submissionRef.current = nextSubmission;

    if (
      current.session === null ||
      !current.mayWrite ||
      current.caseId === null ||
      !isCanonicalUuidV4(current.caseId) ||
      !isWritableCaseStatus(current.caseStatus) ||
      !Number.isSafeInteger(current.expectedVersion) ||
      current.expectedVersion === null ||
      current.expectedVersion < 0 ||
      current.expectedVersion >= Number.MAX_SAFE_INTEGER
    ) {
      const status = current.session === null ? "authentication-required" : "forbidden";
      setState({ status, submission: nextSubmission });
      return;
    }

    const blocked = reconciliationBlockRef.current;
    if (
      blocked !== null &&
      blocked.session === current.session &&
      blocked.caseId === current.caseId &&
      blocked.generation === current.reconciliationGeneration
    ) {
      return;
    }

    if (!isNoteContentString(content)) {
      setState({ status: "validation-error", submission: nextSubmission });
      return;
    }

    const flight: MutationFlight = {
      session: current.session,
      caseId: current.caseId,
      expectedVersion: current.expectedVersion,
      submission: nextSubmission,
      reconciliationGeneration: current.reconciliationGeneration,
      controller: new AbortController(),
      phase: "pending",
    };
    flightRef.current = flight;
    setState({ status: "submitting", submission: nextSubmission });

    createInvestigationNote(
      getOidcAuthClient(),
      flight.caseId,
      { content, expectedVersion: flight.expectedVersion },
      flight.controller.signal,
    ).then(
      (result) => {
        const minimumDetailVersion = result.data.concurrencyVersion;
        settleFlight(
          flight,
          () => ({ status: "success", submission: flight.submission }),
          minimumDetailVersion,
        );
      },
      (error: unknown) => {
        if (flight.controller.signal.aborted) {
          return;
        }
        settleFlight(flight, () => classifyFailure(error, flight.submission));
      },
    );
  }, [settleFlight]);

  const reset = useCallback(() => {
    const current = state;
    if (current.status === "submitting" || current.status === "success") {
      return;
    }
    setState({ status: "idle" });
  }, [state]);

  const waitingForReconciliation =
    reconciliationBlock !== null &&
    reconciliationBlock.session === session &&
    reconciliationBlock.caseId === context.caseId &&
    (reconciliationBlock.generation === context.reconciliationGeneration ||
      (reconciliationBlock.minimumDetailVersion !== null &&
        (context.expectedVersion === null ||
          context.expectedVersion < reconciliationBlock.minimumDetailVersion)));

  return { state, submit, reset, waitingForReconciliation };
}
