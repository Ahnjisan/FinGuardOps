import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { isCanonicalUuidV4 } from "../api/backendEndpoints";
import type { CaseDetail } from "../api/caseApi";
import {
  useCaseDetail,
  type CaseDetailErrorKind,
  type CaseDetailState,
} from "../api/useCaseDetail";
import { CaseAuditSection } from "./cases/CaseAuditSection";
import { CaseInvestigationNotesSection } from "./cases/CaseInvestigationNotesSection";
import {
  CASE_FINAL_DISPOSITION_LABELS,
  CASE_STATUS_LABELS,
  caseStatusTone,
  describeReference,
  formatCaseInstant,
  formatTransactionCount,
  UNASSIGNED_LABEL,
} from "./cases/casePresentation";

/**
 * One fraud case, read only.
 *
 * Everything in the record on this screen comes from the `CaseDetail` contract
 * and nothing else. There is no risk level, no risk score, no detection result,
 * no rule evidence, no related transaction, no investigation note and no AI
 * report, because `GET /api/v1/cases/{caseId}` carries none of them and a
 * console that infers one puts a judgement on screen that no system made.
 *
 * Below the record sit investigation notes and audit history. All three reads
 * mount in the same commit and own independent request, error and paging state;
 * a notes or audit failure therefore removes neither the record nor its sibling.
 *
 * The two settled refusals are the exception, and deliberately so. A case that
 * does not exist and a case this session may not read are answers about the
 * case itself, so both subordinate sections are removed rather than left to
 * repeat the refusal. Unmounting discards their state and blocks late publish.
 *
 * Status, reassignment and resolution remain read only. The sole mutation is
 * the capability- and workflow-gated inline investigation-note composer.
 *
 * `concurrencyVersion` is shown for the same reason the rest of the record is -
 * it is part of the response - and for no other. It is record metadata a reader
 * can quote when reporting a case and the exact optimistic-locking token the
 * note composer sends as `expectedVersion`.
 */

/**
 * One path segment after `/cases/`, taken from the location the browser gave
 * this application.
 *
 * `[^/]+` is a *shape* check, not the decision. The decision is
 * `isCanonicalUuidV4` below, applied to that segment exactly as it reaches
 * here - never decoded, trimmed or case folded: a surviving encoded slash, an
 * encoded backslash, a duplicate separator, whitespace, a trailing slash, an
 * extra segment, an uppercase digit, a non-v4 version nibble and an invalid
 * variant nibble all fail it, and so does anything carrying a `%` at all.
 */
const DETAIL_PATH = /^\/cases\/([^/]+)$/;

interface CaseRouteLocation {
  readonly pathname: string;
  readonly search: string;
  readonly hash: string;
}

/**
 * The case this address names, or `null` when it names none.
 *
 * The input boundary here is the location the browser's own URL parser handed
 * to this application - `location.pathname`, `location.search` and
 * `location.hash` - and nothing earlier than that. Whatever the address bar, a
 * link or a redirect originally carried has already been parsed and
 * canonicalized by the browser before any of this code runs; a representation
 * the browser resolved away is not recoverable here and is not claimed to be.
 *
 * It reads `location.pathname` rather than `useParams()` because React Router
 * hands a route parameter over already percent-decoded: a `%32` that the
 * browser preserved in the path produces a parameter that *is* a canonical
 * UUID while the location is not. Validating the decoded parameter would admit
 * that location; validating the path segment as it arrived refuses it, because
 * that segment still carries its `%32`.
 *
 * A query string or a fragment is refused for the same reason: this route has
 * neither, so a location carrying one is not this route. The application's own
 * fragment - the skip link's `#main-content` - is a same-document navigation
 * the router never observes, so it cannot reach here.
 */
function readCanonicalCaseId(location: CaseRouteLocation): string | null {
  if (location.search !== "" || location.hash !== "") {
    return null;
  }
  const match = DETAIL_PATH.exec(location.pathname);
  if (match === null) {
    return null;
  }
  const candidate = match[1];
  return isCanonicalUuidV4(candidate) ? candidate : null;
}

/**
 * What a case with no final disposition is shown as on this screen.
 *
 * `null` means the investigation has not concluded - not that the case was
 * found normal, and not that the field failed to render. The wording is fixed
 * here rather than derived, so the screen cannot drift into implying either.
 */
const NOT_DECIDED_LABEL = "Not decided";

/** What a case whose review has not begun is shown as. */
const NOT_STARTED_LABEL = "Not started";

/** What a case that is still open is shown as. */
const NOT_CLOSED_LABEL = "Not closed";

/**
 * Fixed messages. Each says what happened and what the analyst can do about it,
 * and none of them carries a status code, a trace id, a response body, a token
 * or the address that was asked for.
 */
const ERROR_COPY: Readonly<
  Record<CaseDetailErrorKind, { readonly title: string; readonly body: string }>
> = Object.freeze({
  timeout: {
    title: "The case took too long to load",
    body: "The backend did not answer in time. Try loading it again.",
  },
  network: {
    title: "The backend could not be reached",
    body: "Check the connection to the FinGuardOps backend, then try again.",
  },
  "invalid-response": {
    title: "The case could not be read",
    body:
      "The backend returned data this console will not display. Nothing is shown rather than " +
      "part of a record. Try again, and report it if it continues.",
  },
  "session-lost": {
    title: "Your session ended",
    body: "Sign in again to continue.",
  },
  "request-rejected": {
    title: "The case was not requested",
    body: "This address is not a case request this console will send.",
  },
  unknown: {
    title: "The case could not be loaded",
    body: "The backend could not return this case. Try again.",
  },
});

/**
 * Errors where sending the identical request again could plausibly succeed.
 *
 * Every kind in `CaseDetailErrorKind` except the two that say something about
 * the session rather than about the request. A 404 and a 403 are not in this
 * set at all: they are statuses of their own, and no retry control is rendered
 * for either.
 */
const RETRYABLE: ReadonlySet<CaseDetailErrorKind> = new Set<CaseDetailErrorKind>([
  "timeout",
  "network",
  "invalid-response",
  "unknown",
]);

const NOT_FOUND_COPY = Object.freeze({
  title: "Case not found",
  body: "No case with this identifier is available. Return to the case list.",
});

const FORBIDDEN_COPY = Object.freeze({
  title: "Access denied",
  body: "You do not have permission to view this case.",
});

const INVALID_ROUTE_COPY = Object.freeze({
  title: "This is not a case address",
  body:
    "The address does not name a case this console can open. Open a case from the case list " +
    "instead.",
});

/** The fixed refusal a settled non-success state shows, or `null` for none. */
function refusalCopy(
  state: CaseDetailState,
): { readonly title: string; readonly body: string } | null {
  if (state.status === "not-found") {
    return NOT_FOUND_COPY;
  }
  if (state.status === "forbidden") {
    return FORBIDDEN_COPY;
  }
  if (state.status === "error") {
    return ERROR_COPY[state.error];
  }
  return null;
}

export function CaseDetailPage() {
  const location = useLocation();
  const caseId = readCanonicalCaseId(location);
  const {
    state,
    retry,
    refresh,
    refreshState,
    reconciliationGeneration,
  } = useCaseDetail(caseId);
  const [subordinateRefreshSignal, setSubordinateRefreshSignal] = useState(0);
  const reconcileNoteMutation = useCallback((minimumDetailVersion?: number) => {
    refresh(minimumDetailVersion);
    setSubordinateRefreshSignal((current) => current + 1);
  }, [refresh]);

  const errorRef = useRef<HTMLDivElement | null>(null);

  // Focus moves to the summary once per refusal, never on every render. A
  // malformed address is one fixed refusal; a Backend answer is identified by
  // what it was, so a retry that fails the same way again does not steal focus
  // a second time from someone who is already reading it.
  const errorSignature =
    caseId === null
      ? "route"
      : state.status === "error"
        ? `request:${state.error}`
        : state.status === "not-found" || state.status === "forbidden"
          ? `request:${state.status}`
          : "";
  const lastFocusedSignature = useRef("");
  useEffect(() => {
    if (errorSignature === "" || errorSignature === lastFocusedSignature.current) {
      lastFocusedSignature.current = errorSignature;
      return;
    }
    lastFocusedSignature.current = errorSignature;
    errorRef.current?.focus();
  }, [errorSignature]);

  const refusal = caseId === null ? INVALID_ROUTE_COPY : refusalCopy(state);
  const retryable = caseId !== null && state.status === "error" && RETRYABLE.has(state.error);

  return (
    <section className="detail" aria-labelledby="case-detail-heading">
      <div className="page-head">
        <p className="detail__back">
          <Link to="/cases">Back to cases</Link>
        </p>
        <h2 id="case-detail-heading">
          Case
          {caseId !== null && (
            <>
              {" "}
              <span className="detail__id">{caseId}</span>
            </>
          )}
        </h2>
        <p>
          A read-only investigation record. Times are Korea Standard Time (UTC+09:00).
        </p>
      </div>

      {/*
        Named, because both subordinate sections have live regions of their own.
      */}
      <div
        className="result-line"
        role="status"
        aria-live="polite"
        aria-label="Case record status"
      >
        <DetailSummary invalidRoute={caseId === null} state={state} />
      </div>

      {refusal !== null && (
        <div className="notice notice--error" role="alert" tabIndex={-1} ref={errorRef}>
          <p className="notice__title">{refusal.title}</p>
          <p className="notice__body">{refusal.body}</p>
          {retryable && (
            <button className="button" type="button" onClick={retry}>
              Try again
            </button>
          )}
        </div>
      )}

      {caseId !== null && state.status === "loading" && (
        <p className="loading-panel">Loading case...</p>
      )}

      {caseId !== null && state.status === "success" && <CaseRecord detail={state.data} />}

      {caseId !== null && state.status === "success" && refreshState === "failed" && (
        <div className="notice notice--error case-detail__refresh" role="alert">
          <h3 className="notice__title">The latest case information could not be loaded</h3>
          <p className="notice__body">
            The note submission result is unchanged. Refresh the case before adding another note.
          </p>
          <button className="button" type="button" onClick={() => refresh()}>
            Refresh case information
          </button>
        </div>
      )}

      {/*
        Both sections mount from the first render of a canonical address. Their
        effects therefore start beside the detail request in the same commit.
        A detail 403/404 unmounts both and their lifecycle gates refuse any late
        notes or audit settlement.
      */}
      {caseId !== null && showsSubordinateSections(state) && (
        <>
          <CaseInvestigationNotesSection
            caseId={caseId}
            caseStatus={state.status === "success" ? state.data.caseStatus : null}
            expectedVersion={state.status === "success" ? state.data.concurrencyVersion : null}
            reconciliationGeneration={reconciliationGeneration}
            refreshSignal={subordinateRefreshSignal}
            onReconcile={reconcileNoteMutation}
          />
          <CaseAuditSection caseId={caseId} refreshSignal={subordinateRefreshSignal} />
        </>
      )}
    </section>
  );
}

/**
 * Whether the audit history section belongs on screen for this detail state.
 *
 * Present while the record is loading, present when it arrives, and present
 * when the record failed in a way that says nothing about the case - a timeout,
 * a network failure, an unreadable response. Absent for the two settled
 * refusals, which are answers about the case itself.
 */
function showsSubordinateSections(state: CaseDetailState): boolean {
  return state.status !== "not-found" && state.status !== "forbidden";
}

function DetailSummary({
  invalidRoute,
  state,
}: {
  readonly invalidRoute: boolean;
  readonly state: CaseDetailState;
}) {
  if (invalidRoute) {
    return <span>{INVALID_ROUTE_COPY.title}.</span>;
  }
  if (state.status === "loading") {
    return <span>Loading case</span>;
  }
  if (state.status === "success") {
    return <span>Showing the full case record.</span>;
  }
  const refusal = refusalCopy(state);
  if (refusal !== null) {
    return <span>No record shown. {refusal.title}.</span>;
  }
  return null;
}

/**
 * The record itself, as three sections of definitions.
 *
 * Ten names for the ten fields the contract carries, in three groups: what the
 * case currently is, when it moved, and the metadata of the row itself. A
 * definition list rather than a grid of cards - every line here is a name and
 * one value, and wrapping each pair in its own bordered surface would add
 * decoration without adding a single fact.
 */
function CaseRecord({ detail }: { readonly detail: CaseDetail }) {
  const tone = caseStatusTone(detail.caseStatus);
  const assignee = describeReference(detail.assigneeRef);

  return (
    <div className="detail__record">
      <section className="panel" aria-labelledby="case-summary-heading">
        <h3 id="case-summary-heading">Case</h3>
        <dl className="facts">
          <dt>Case ID</dt>
          <dd className="facts__ref">{detail.caseId}</dd>

          <dt>Case status</dt>
          <dd>
            {/*
              Shape, word and colour together, exactly as the sheet renders it.
              This is where the case has reached in the investigation workflow -
              not a risk level, and not a verdict about the case.
            */}
            <span className={`badge badge--${tone}`}>
              <span className="badge__mark" aria-hidden="true" />
              {CASE_STATUS_LABELS[detail.caseStatus]}
            </span>
          </dd>

          <dt>Final disposition</dt>
          <dd>
            {detail.finalDisposition === null ? (
              // A word rather than an empty cell, and a word that means "not
              // concluded yet" rather than one that could be read as a verdict.
              <span className="facts__absent">{NOT_DECIDED_LABEL}</span>
            ) : (
              CASE_FINAL_DISPOSITION_LABELS[detail.finalDisposition]
            )}
          </dd>

          <dt>Assignee</dt>
          <dd className="facts__ref">
            {assignee.absent ? (
              <span className="facts__absent">{UNASSIGNED_LABEL}</span>
            ) : (
              assignee.text
            )}
          </dd>

          <dt>Related transactions</dt>
          {/*
            The number Backend counted, printed exactly as the validator
            admitted it. The transactions themselves are a separate endpoint
            this console does not call, so the count is a count and not a link.
          */}
          <dd>{formatTransactionCount(detail.relatedTransactionCount)}</dd>
        </dl>
      </section>

      <section className="panel" aria-labelledby="case-timeline-heading">
        <h3 id="case-timeline-heading">Investigation timeline</h3>
        <dl className="facts">
          <dt>Created</dt>
          <dd>
            <KstInstant utcInstant={detail.createdAt} />
          </dd>

          <dt>Review started</dt>
          <dd>
            <NullableInstant utcInstant={detail.reviewStartedAt} absentLabel={NOT_STARTED_LABEL} />
          </dd>

          <dt>Closed</dt>
          <dd>
            <NullableInstant utcInstant={detail.closedAt} absentLabel={NOT_CLOSED_LABEL} />
          </dd>

          {/*
            The contract's own field name is `lastChangedAt`, and this is what
            it is called on screen. There is no `updatedAt` in this response and
            none is invented here.
          */}
          <dt>Last changed</dt>
          <dd>
            <KstInstant utcInstant={detail.lastChangedAt} />
          </dd>
        </dl>
      </section>

      <section className="panel" aria-labelledby="case-metadata-heading">
        <h3 id="case-metadata-heading">Record metadata</h3>
        <dl className="facts">
          <dt>Concurrency version</dt>
          <dd>{formatTransactionCount(detail.concurrencyVersion)}</dd>
        </dl>
      </section>
    </div>
  );
}

/**
 * Seoul wall clock for the reader, the Backend's untouched UTC value for the
 * machine. The conversion is a fixed +09:00 applied to the instant, so the
 * workstation's own time zone takes no part in it.
 */
function KstInstant({ utcInstant }: { readonly utcInstant: string }) {
  const shown = formatCaseInstant(utcInstant);
  if (shown === null) {
    // Unreachable through the validated contract, and still not a place to
    // print the raw value: a time that cannot be read is reported as one.
    return <span className="facts__absent">Not a readable time</span>;
  }
  return <time dateTime={utcInstant}>{shown} KST</time>;
}

/**
 * An instant the contract allows to be `null`, and the fixed phrase that stands
 * for its absence.
 *
 * The phrase says which milestone has not happened yet. It is not a time, so it
 * is not wrapped in a `<time>`: an element with no `datetime` would be a
 * machine-readable claim about a moment that does not exist.
 */
function NullableInstant({
  utcInstant,
  absentLabel,
}: {
  readonly utcInstant: string | null;
  readonly absentLabel: string;
}) {
  if (utcInstant === null) {
    return <span className="facts__absent">{absentLabel}</span>;
  }
  return <KstInstant utcInstant={utcInstant} />;
}
