import type { PageMetadata } from "../../api/pagination";
import type { CaseAuditEntry, CaseAuditSummary } from "../../api/caseAuditApi";
import {
  describeResultWindow,
  formatCaseInstant,
  PAGE_SIZE_OPTIONS,
  UNASSIGNED_LABEL,
  type ResultWindow,
} from "./casePresentation";

/**
 * Everything the audit history section has to turn into something a person
 * reads, kept out of the component so it can be tested as string and branch
 * work rather than through the DOM.
 *
 * Two rules run through it, and the first is narrower than the one the other
 * case screens follow.
 *
 * - Nothing here is renamed. An audit entry is the record of a change, and the
 *   three values that classify it - `action`, `reasonCode` and `actorType` -
 *   are shown as the Backend enum codes they are. The same holds for the enum
 *   values inside a before/after summary: a section that printed
 *   `CASE_STATUS_CHANGED` beside "In review" would be quoting the record in one
 *   line and paraphrasing it in the next, and a reader comparing an audit entry
 *   against a Backend log could no longer tell which words came from where. The
 *   plain-language label maps in `casePresentation` are deliberately not
 *   imported.
 * - Nothing is invented. An audit page carries no actor identity - Backend
 *   never returns `actorId` - no note text, no diff and no explanation, so
 *   there is no function here that could produce one. The only strings this
 *   module adds are the fixed phrases that stand for an absence, and each says
 *   which absence it means.
 *
 * The time, page-window and page-size rules are the case screens', imported
 * rather than restated: a `changedAt` and a `lastChangedAt` are the same kind
 * of value, and two copies of a fixed-offset KST conversion would eventually
 * disagree about a leap day.
 */

export { PAGE_SIZE_OPTIONS as AUDIT_PAGE_SIZE_OPTIONS, type ResultWindow };

/** `2026-09-07 14:03:22`, Seoul wall clock. The `KST` suffix is added by the UI. */
export const formatAuditInstant = formatCaseInstant;

/**
 * What a `null` before- or after-summary is shown as.
 *
 * `null` here is a structural fact about the action rather than a value that
 * went missing: a case creation has no before-state, and a note has neither
 * side. The phrase says the summary does not apply to this kind of change, so
 * the section cannot be read as "the Backend failed to send it" or as "the
 * state was empty".
 */
export const AUDIT_ABSENT_SUMMARY_LABEL = "Not applicable";

/**
 * What an audit summary carrying no assignee is shown as.
 *
 * The same word the case list and the case detail screen use for the same
 * absence, imported rather than re-typed: three screens describing one state in
 * two different words would read as two different states of the record.
 */
export const AUDIT_UNASSIGNED_LABEL = UNASSIGNED_LABEL;

/** The empty state of the whole trail: the case has no audit entry at all. */
export const NO_AUDIT_HISTORY_MESSAGE = "No audit history recorded.";

/**
 * The empty state of one page: the trail is not empty, but this page number is
 * past its end.
 *
 * A distinct sentence from the one above on purpose. They are different facts,
 * and collapsing them would tell an analyst who paged too far that the case has
 * no history at all.
 */
export const NO_AUDIT_ENTRIES_ON_PAGE_MESSAGE = "No audit entries on this page.";

/** One name-and-value line inside a before- or after-summary. */
export interface AuditSummaryField {
  readonly name: string;
  /** The value as it is shown: a Backend enum code, a reference, or a phrase. */
  readonly text: string;
  /** True when `text` is a fixed phrase standing in for an absent value. */
  readonly absent: boolean;
}

/**
 * One side of a change, as the section renders it.
 *
 * `present: false` is the whole summary being `null`; an absent *field* inside
 * a summary that does exist is marked on that field instead. The two are
 * different facts and the section shows them differently.
 */
export type AuditSummaryDisplay =
  | { readonly present: false; readonly label: string }
  | { readonly present: true; readonly fields: readonly AuditSummaryField[] };

const ABSENT_SUMMARY: AuditSummaryDisplay = Object.freeze({
  present: false,
  label: AUDIT_ABSENT_SUMMARY_LABEL,
});

function statusField(caseStatus: string): AuditSummaryField {
  // The Backend enum code, not a label. See the module note above.
  return { name: "Case status", text: caseStatus, absent: false };
}

function assigneeField(assigneeRef: string | null): AuditSummaryField {
  if (assigneeRef === null) {
    return { name: "Assignee", text: AUDIT_UNASSIGNED_LABEL, absent: true };
  }
  // An opaque key, printed exactly as Backend stored it. Nothing here trims,
  // case-folds or shortens it: a trimmed reference is a different reference.
  return { name: "Assignee", text: assigneeRef, absent: false };
}

/**
 * One before- or after-summary, as name-and-value lines.
 *
 * The four summary shapes are told apart by the keys they carry, which is how
 * `isCaseAuditPage` admitted them in the first place: `linked` belongs to a
 * transaction link alone, `finalDisposition` to a resolution alone, and an
 * `assigneeRef` beside a status is the workflow shape. There is no fallback
 * branch, because a fifth shape cannot reach here - the response validator
 * refuses the whole page before one entry of it is displayed.
 */
export function describeAuditSummary(summary: CaseAuditSummary | null): AuditSummaryDisplay {
  if (summary === null) {
    return ABSENT_SUMMARY;
  }
  if ("linked" in summary) {
    // `true` is the only value the contract admits, and it is printed as the
    // literal it is rather than as a word this module chose for it.
    return {
      present: true,
      fields: [{ name: "Linked", text: String(summary.linked), absent: false }],
    };
  }
  if ("finalDisposition" in summary) {
    return {
      present: true,
      fields: [
        statusField(summary.caseStatus),
        assigneeField(summary.assigneeRef),
        { name: "Final disposition", text: summary.finalDisposition, absent: false },
      ],
    };
  }
  if ("assigneeRef" in summary) {
    return {
      present: true,
      fields: [statusField(summary.caseStatus), assigneeField(summary.assigneeRef)],
    };
  }
  return { present: true, fields: [statusField(summary.caseStatus)] };
}

/**
 * The note identifier a `CASE_NOTE_CREATED` entry carries, or `null` for every
 * other action.
 *
 * It is returned as a string and shown as one. This console has no note screen
 * and no note route, so an anchor here would lead nowhere - and a link that
 * cannot be followed is a promise the section cannot keep.
 */
export function describeAuditNoteId(entry: CaseAuditEntry): string | null {
  return entry.action === "CASE_NOTE_CREATED" ? entry.metadata.noteId : null;
}

/**
 * The one-based item window a page of audit entries covers.
 *
 * Derived from metadata the API layer has already checked for internal
 * consistency, so this states the window rather than recomputing it from the
 * entries on screen.
 */
export function describeAuditWindow(page: PageMetadata, itemCount: number): ResultWindow {
  return describeResultWindow(page.number, page.size, itemCount, page.totalElements);
}

/**
 * The result line above the list.
 *
 * Three sentences for three different facts: a trail with nothing in it, a page
 * past the end of a trail that does have entries, and a page with entries on
 * it. The numbers are the page envelope's own, printed without grouping
 * separators and without abbreviation.
 */
export function describeAuditRange(window: ResultWindow): string {
  if (window.total === 0) {
    return "No audit entries.";
  }
  if (window.first === 0) {
    return `No entries on this page of ${String(window.total)}.`;
  }
  return `Showing ${String(window.first)}-${String(window.last)} of ${String(window.total)}.`;
}

/**
 * `Page 3 of 7`, for the pager.
 *
 * `totalPages` is 0 for an empty result, which still reads as one page to a
 * person looking at the screen.
 */
export function describeAuditPosition(page: PageMetadata): string {
  return `Page ${String(page.number + 1)} of ${String(Math.max(page.totalPages, 1))}`;
}
