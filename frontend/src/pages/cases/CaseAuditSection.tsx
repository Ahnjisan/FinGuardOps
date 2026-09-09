import { useEffect, useRef } from "react";
import type { CaseAuditEntry } from "../../api/caseAuditApi";
import {
  useCaseAuditLog,
  type CaseAuditState,
  type CaseAuditView,
} from "../../api/useCaseAuditLog";
import {
  AUDIT_PAGE_SIZE_OPTIONS,
  describeAuditNoteId,
  describeAuditPosition,
  describeAuditRange,
  describeAuditSummary,
  describeAuditWindow,
  formatAuditInstant,
  NO_AUDIT_ENTRIES_ON_PAGE_MESSAGE,
  NO_AUDIT_HISTORY_MESSAGE,
  type AuditSummaryDisplay,
} from "./caseAuditPresentation";

/**
 * The audit history of one fraud case, read only.
 *
 * A section of the case detail screen rather than a screen of its own: an audit
 * trail is a property of the case in front of the reader, and a second route
 * would make the analyst leave the record to check how it got there. It sits
 * below the record and owns its own loading, empty, error and paging states, so
 * a trail that fails to load costs the case record nothing and a case record
 * that fails to load costs the trail nothing.
 *
 * Everything here comes from the `GET /api/v1/cases/{caseId}/audit-logs`
 * contract and nothing else. There is no actor name - Backend deliberately
 * never returns `actorId` - no note text, no diff, no rule evidence and no AI
 * commentary, because the response carries none of them and a console that
 * inferred one would put a claim on screen that no system made.
 *
 * There is also no action. No status change, no reassignment, no resolution and
 * no note: this section implements the read, and the note identifier a
 * `CASE_NOTE_CREATED` entry carries is shown as the text it is rather than as a
 * link, because this console has no note screen for it to lead to.
 *
 * The page and the page size are this section's own state. They are not in the
 * address bar: they are a reading position inside one section of one screen,
 * not a different screen, and a shared `?auditPage=3` would promise a stable
 * view it cannot keep because the trail grows underneath it.
 */

/**
 * Fixed messages. Each says what happened and what the analyst can do about it,
 * and none of them carries a status code, a trace id, a response body, a token
 * or the address that was asked for.
 */
const ERROR_COPY = Object.freeze({
  timeout: {
    title: "The audit history took too long to load",
    body: "The backend did not answer in time. Try loading it again.",
  },
  "network-error": {
    title: "The backend could not be reached",
    body: "Check the connection to the FinGuardOps backend, then try again.",
  },
  "invalid-response": {
    title: "The audit history could not be read",
    body:
      "The backend returned data this console will not display. Nothing is shown rather than " +
      "part of a trail. Try again, and report it if it continues.",
  },
  "authentication-required": {
    title: "Your session ended",
    body: "Sign in again to continue.",
  },
  "generic-error": {
    title: "The audit history could not be loaded",
    body: "The backend could not return this audit history. Try again.",
  },
});

/**
 * Errors where sending the identical request again could plausibly succeed.
 *
 * Only transport, response-contract and generic failures are retryable. A 401,
 * 403 and 404 are statuses of their own and render no retry control.
 */
const RETRYABLE: ReadonlySet<CaseAuditState["status"]> = new Set([
  "timeout",
  "network-error",
  "invalid-response",
  "generic-error",
]);

const NOT_FOUND_COPY = Object.freeze({
  title: "Audit history not found",
  body: "No audit history is available for this case.",
});

const FORBIDDEN_COPY = Object.freeze({
  title: "Access denied",
  body: "You do not have permission to view this audit history.",
});

/** The fixed refusal a settled non-success state shows, or `null` for none. */
function refusalCopy(
  state: CaseAuditState,
): { readonly title: string; readonly body: string } | null {
  if (state.status === "not-found") {
    return NOT_FOUND_COPY;
  }
  if (state.status === "forbidden") {
    return FORBIDDEN_COPY;
  }
  if (
    state.status === "authentication-required" ||
    state.status === "timeout" ||
    state.status === "network-error" ||
    state.status === "invalid-response" ||
    state.status === "generic-error"
  ) {
    return ERROR_COPY[state.status];
  }
  return null;
}

export interface CaseAuditSectionProps {
  /** The canonical lowercase UUID v4 the detail route carries. */
  readonly caseId: string;
}

/**
 * The section as the case detail screen mounts it: the hook, and the panel that
 * renders what the hook says.
 *
 * Thin on purpose. Everything a browser can measure lives in `CaseAuditPanel`
 * below, which takes a settled state and three callbacks and reaches nothing
 * else - no transport, no session, no router - so the same production markup
 * and the same production stylesheet can be rendered with entries in them by a
 * geometry fixture that makes no request at all.
 */
export function CaseAuditSection({ caseId }: CaseAuditSectionProps) {
  const { state, setPage, setSize, retry } = useCaseAuditLog(caseId);
  return (
    <CaseAuditPanel
      state={state}
      onPageChange={setPage}
      onPageSizeChange={setSize}
      onRetry={retry}
    />
  );
}

export interface CaseAuditPanelProps {
  readonly state: CaseAuditState;
  readonly onPageChange: (pageNumber: number) => void;
  readonly onPageSizeChange: (size: number) => void;
  readonly onRetry: () => void;
}

/**
 * The panel itself: one heading, one live region, and the current audit state.
 *
 * A `.panel` like the three the record above uses, so the section reads as one
 * more part of the same sheet rather than as a widget bolted onto it.
 */
export function CaseAuditPanel({
  state,
  onPageChange,
  onPageSizeChange,
  onRetry,
}: CaseAuditPanelProps) {
  const refusal = refusalCopy(state);
  const retryable = RETRYABLE.has(state.status);
  const errorHeadingRef = useRef<HTMLHeadingElement | null>(null);

  useEffect(() => {
    if (retryable) {
      errorHeadingRef.current?.focus();
    }
  }, [retryable, state.status]);

  return (
    <section className="panel audit" aria-labelledby="case-audit-heading">
      <h3 id="case-audit-heading">Audit history</h3>
      <p className="audit__note">
        Every recorded change to this case, newest first. Times are Korea Standard Time
        (UTC+09:00).
      </p>

      {/*
        The section's own live region, named so it is distinguishable from the
        case record's. Two unnamed status regions on one screen announce two
        different things under one name, which is worse than announcing neither.
      */}
      <p className="result-line" role="status" aria-live="polite" aria-label="Audit history status">
        <AuditSummary state={state} />
      </p>

      {refusal !== null && (
        <div className="notice notice--error" role="alert">
          <h4 className="notice__title" tabIndex={-1} ref={errorHeadingRef}>
            {refusal.title}
          </h4>
          <p className="notice__body">{refusal.body}</p>
          {retryable && (
            <button className="button" type="button" onClick={onRetry}>
              Try loading the audit history again
            </button>
          )}
        </div>
      )}

      {state.status === "loading" && (
        <p className="loading-panel">Loading audit history...</p>
      )}

      {(state.status === "success" || state.status === "empty") && (
        <AuditRecord
          view={state.data}
          onPageChange={onPageChange}
          onPageSizeChange={onPageSizeChange}
        />
      )}
    </section>
  );
}

/** The one sentence the live region carries, for each state the section has. */
function AuditSummary({ state }: { readonly state: CaseAuditState }) {
  if (state.status === "idle") {
    return <span>No audit history requested.</span>;
  }
  if (state.status === "loading") {
    return <span>Loading audit history</span>;
  }
  if (state.status === "success") {
    const window = describeAuditWindow(state.data.page, state.data.content.length);
    return <span>{describeAuditRange(window)}</span>;
  }
  if (state.status === "empty") {
    const window = describeAuditWindow(state.data.page, state.data.content.length);
    return <span>{describeAuditRange(window)}</span>;
  }
  const refusal = refusalCopy(state);
  if (refusal !== null) {
    return <span>No audit history shown. {refusal.title}.</span>;
  }
  return null;
}

/**
 * A settled page: its entries, or the reason there are none, and the pager.
 *
 * The two empty states are separate sentences because they are separate facts.
 * A trail with no entries at all is a case nothing has happened to yet; a page
 * with no entries on a trail that has some is a page number past the end, which
 * this section reports rather than silently correcting. Correcting it would
 * mean a second request nobody asked for, and a reader who pressed Next twice
 * would be quietly moved back.
 */
function AuditRecord({
  view,
  onPageChange,
  onPageSizeChange,
}: {
  readonly view: CaseAuditView;
  readonly onPageChange: (pageNumber: number) => void;
  readonly onPageSizeChange: (size: number) => void;
}) {
  const { content, page } = view;

  return (
    <>
      {content.length === 0 ? (
        <p className="notice notice--empty">
          {page.totalElements === 0 ? NO_AUDIT_HISTORY_MESSAGE : NO_AUDIT_ENTRIES_ON_PAGE_MESSAGE}
        </p>
      ) : (
        <ol className="audit__list">
          {content.map((entry, index) => (
            <AuditItem
              // The trail carries no entry identifier, so position within the
              // page is the only key available. It is stable for as long as the
              // page is: a new page replaces the whole list rather than editing
              // it in place, because the hook publishes a fresh array.
              key={`${String(page.number)}-${String(index)}`}
              entry={entry}
              id={`case-audit-entry-${String(page.number)}-${String(index)}`}
            />
          ))}
        </ol>
      )}

      <AuditPager page={view.page} onPageChange={onPageChange} onPageSizeChange={onPageSizeChange} />
    </>
  );
}

/**
 * One recorded change.
 *
 * An `article` because it is a self-contained record that would still make
 * sense lifted out of the list, headed by the Backend `action` code it is. The
 * classification codes are printed exactly as the contract spells them - see
 * the note in `caseAuditPresentation` for why they are not translated here.
 */
function AuditItem({ entry, id }: { readonly entry: CaseAuditEntry; readonly id: string }) {
  const noteId = describeAuditNoteId(entry);
  const changed = formatAuditInstant(entry.changedAt);

  return (
    <li className="audit__item">
      <article className="audit__entry" aria-labelledby={id}>
        <h4
          className="audit__action"
          id={id}
          aria-label={`${entry.action}, changed ${changed ?? "at an unreadable time"}${changed === null ? "" : " KST"}`}
        >
          {entry.action}
        </h4>
        <dl className="facts">
          <dt>Reason code</dt>
          <dd className="audit__code">{entry.reasonCode}</dd>

          <dt>Actor type</dt>
          <dd className="audit__code">{entry.actorType}</dd>

          <dt>Changed</dt>
          <dd>
            <KstInstant utcInstant={entry.changedAt} />
          </dd>

          {/*
            Before and after are named, always both, and never merged into one
            line. A change is two states, and a section that printed only the
            result would be reporting the record rather than the change.
          */}
          <dt>Before</dt>
          <dd>
            <AuditSummaryValue display={describeAuditSummary(entry.beforeSummary)} />
          </dd>

          <dt>After</dt>
          <dd>
            <AuditSummaryValue display={describeAuditSummary(entry.afterSummary)} />
          </dd>

          {noteId !== null && (
            <>
              <dt>Note ID</dt>
              {/*
                Text, not an anchor. There is no investigation note screen in
                this console, so a link here would lead nowhere.
              */}
              <dd className="facts__ref">{noteId}</dd>
            </>
          )}
        </dl>
      </article>
    </li>
  );
}

/** One side of a change: its named values, or the phrase for its absence. */
function AuditSummaryValue({ display }: { readonly display: AuditSummaryDisplay }) {
  if (!display.present) {
    return <span className="facts__absent">{display.label}</span>;
  }
  return (
    <ul className="audit__summary">
      {display.fields.map((field) => (
        <li key={field.name} className="audit__summary-field">
          <span className="audit__summary-name">{field.name}</span>
          <span
            className={
              field.absent ? "facts__absent" : "audit__summary-value"
            }
          >
            {field.text}
          </span>
        </li>
      ))}
    </ul>
  );
}

/**
 * Page and page-size controls for this section alone.
 *
 * `CasePagination` is deliberately not reused. That component belongs to the
 * case list, where a page change is a new committed query that also re-reads
 * the address bar and the filter draft; here a page change is a local reading
 * position inside one panel. Sharing the component would tie the two together,
 * and the first divergence - a different accessible name, a different label,
 * one of them gaining a control - would have to be paid for by a prop that
 * exists to tell the two callers apart.
 *
 * Every number shown comes from the page envelope the API layer already checked
 * for internal consistency, so this states the position rather than recomputing
 * it from the entries on screen.
 */
function AuditPager({
  page,
  onPageChange,
  onPageSizeChange,
}: {
  readonly page: CaseAuditView["page"];
  readonly onPageChange: (pageNumber: number) => void;
  readonly onPageSizeChange: (size: number) => void;
}) {
  return (
    <nav className="pager" aria-label="Audit history pages">
      <button
        className="button"
        type="button"
        disabled={page.first}
        onClick={() => {
          onPageChange(page.number - 1);
        }}
      >
        Previous
      </button>
      <button
        className="button"
        type="button"
        disabled={page.last}
        onClick={() => {
          onPageChange(page.number + 1);
        }}
      >
        Next
      </button>
      <p className="pager__position">{describeAuditPosition(page)}</p>
      <div className="pager__size">
        <label htmlFor="case-audit-pager-size">Entries per page</label>
        <select
          id="case-audit-pager-size"
          value={page.size}
          onChange={(event) => {
            onPageSizeChange(Number(event.target.value));
          }}
        >
          {AUDIT_PAGE_SIZE_OPTIONS.map((size) => (
            <option key={size} value={size}>
              {size}
            </option>
          ))}
        </select>
      </div>
    </nav>
  );
}

/**
 * Seoul wall clock for the reader, the Backend's untouched UTC value for the
 * machine. The conversion is a fixed +09:00 applied to the instant, so the
 * workstation's own time zone takes no part in it.
 */
function KstInstant({ utcInstant }: { readonly utcInstant: string }) {
  const shown = formatAuditInstant(utcInstant);
  if (shown === null) {
    // Unreachable through the validated contract, and still not a place to
    // print the raw value: a time that cannot be read is reported as one.
    return <span className="facts__absent">Not a readable time</span>;
  }
  return <time dateTime={utcInstant}>{shown} KST</time>;
}
