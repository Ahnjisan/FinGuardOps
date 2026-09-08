import type { CaseFinalDisposition, CaseStatus } from "../../api/caseApi";
import {
  isCanonicalUuidV4,
  isJavaBlank,
  isJavaTrimmed,
} from "../../api/responseValidation";
import {
  describeReference,
  describeResultWindow,
  formatKstDateTime,
  isReversedUtcRange,
  kstInputToUtcInstant,
  PAGE_SIZE_OPTIONS,
  type ReferenceDisplay,
  type ResultWindow,
  type StatusTone,
} from "../transactions/transactionPresentation";

/**
 * Everything the case screen has to turn into something a person reads.
 *
 * The time, reference and result-window rules are the ledger's, imported rather
 * than restated: a case timestamp and a transaction timestamp are the same kind
 * of value, and two copies of a fixed-offset KST conversion would eventually
 * disagree about a leap day. What this module adds is the part that is
 * genuinely about cases - the two Backend enums, the two nullable fields, and
 * the query contract `FraudCaseQueryValidator` enforces.
 *
 * Two rules run through it:
 *
 * - No value is normalized on its way to the screen or on its way to a query.
 *   An assignee reference is an opaque key; a trimmed one is a different key.
 * - Nothing is invented. A case carries no risk score, no priority and no SLA,
 *   so there is no function here that could produce one.
 */

export {
  describeReference,
  describeResultWindow,
  isReversedUtcRange,
  kstInputToUtcInstant,
  PAGE_SIZE_OPTIONS,
  type ReferenceDisplay,
  type ResultWindow,
  type StatusTone,
};

/** `2026-09-07 14:03:22`, Seoul wall clock. The `KST` suffix is added by the UI. */
export const formatCaseInstant = formatKstDateTime;

/**
 * Plain-language names for `FraudCaseStatus`.
 *
 * Exhaustive over the Backend enum by type, and with no fallback branch. A
 * status outside the enum never reaches here: `isCaseListPage` refuses the
 * whole response first. Inventing a name for an unknown value would put a case
 * on screen in a state no system defined, so the refusal is left where it is.
 */
export const CASE_STATUS_LABELS: Readonly<Record<CaseStatus, string>> = Object.freeze({
  OPEN: "Open",
  IN_REVIEW: "In review",
  ADDITIONAL_INFORMATION_REQUIRED: "Information required",
  CLOSED: "Closed",
});

/**
 * The badge treatment for a case status.
 *
 * A tone drives colour *and* a differently shaped mark, and the label is always
 * rendered beside it, so the status survives a monochrome display, a printout
 * and every form of colour blindness.
 *
 * The tones describe where the case has reached in the investigation workflow
 * and carry no claim about how risky it is. "attention" is work waiting on
 * someone else, not a severity; there is deliberately no "danger" tone, because
 * the only thing that could justify one is a risk judgement this response does
 * not carry.
 */
const CASE_STATUS_TONES: Readonly<Record<CaseStatus, StatusTone>> = Object.freeze({
  OPEN: "neutral",
  IN_REVIEW: "info",
  ADDITIONAL_INFORMATION_REQUIRED: "attention",
  CLOSED: "success",
});

export function caseStatusTone(status: CaseStatus): StatusTone {
  return CASE_STATUS_TONES[status];
}

/**
 * Plain-language names for `FraudCaseFinalDisposition`.
 *
 * `CONFIRMED_FRAUD` is an investigator's concluded verdict recorded by Backend,
 * not a score this console derived, and it is shown as exactly that.
 */
export const CASE_FINAL_DISPOSITION_LABELS: Readonly<Record<CaseFinalDisposition, string>> =
  Object.freeze({
    NORMAL: "Normal",
    FALSE_POSITIVE: "False positive",
    CONFIRMED_FRAUD: "Confirmed fraud",
  });

/**
 * What a case with no final disposition is shown as.
 *
 * `null` here means the investigation has not concluded - not that the case is
 * normal, and not that the field failed to render. The exact wording is fixed
 * so the list cannot drift into implying either.
 */
export const UNRESOLVED_DISPOSITION_LABEL = "Not resolved";

/** What a case with no assignee is shown as. */
export const UNASSIGNED_LABEL = "Unassigned";

/**
 * The related-transaction count, as text.
 *
 * Printed exactly as the response validator admitted it. `isNonNegativeLong`
 * has already refused a fraction, a negative and anything past
 * `Number.MAX_SAFE_INTEGER`, so what is left is an exact integer - and it is
 * shown without grouping separators or abbreviation, because the only thing
 * this column is allowed to say is the number Backend counted.
 */
export function formatTransactionCount(count: number): string {
  return String(count);
}

/**
 * The draft filter values, exactly as they are typed.
 *
 * Everything here is a string because everything here is an `<input>` value.
 * The draft lives in the page's memory and nowhere else: it never reaches the
 * address bar, the history entry, `localStorage`, `sessionStorage` or any error
 * string, so an assignee reference or a transaction identifier an analyst types
 * cannot be recovered from the browser afterwards.
 */
export interface CaseFilterDraft {
  readonly caseStatus: string;
  readonly finalDisposition: string;
  readonly assigneeRef: string;
  readonly createdAtFrom: string;
  readonly createdAtTo: string;
  readonly lastChangedAtFrom: string;
  readonly lastChangedAtTo: string;
  readonly transactionId: string;
}

export const EMPTY_CASE_FILTER_DRAFT: CaseFilterDraft = Object.freeze({
  caseStatus: "",
  finalDisposition: "",
  assigneeRef: "",
  createdAtFrom: "",
  createdAtTo: "",
  lastChangedAtFrom: "",
  lastChangedAtTo: "",
  transactionId: "",
});

/** Backend's own bound on `assigneeRef`. */
export const MAX_ASSIGNEE_REF_LENGTH = 128;

/**
 * Why `FraudCaseQueryValidator` would refuse an assignee reference, or `null`
 * when it would accept it.
 *
 * A separate rule from the transaction reference filters on purpose. The case
 * endpoint bounds its reference at 128 characters and compares it against its
 * own Java `trim()`; the transaction endpoints do neither. Collapsing the two
 * would impose one endpoint's bounds on the other.
 *
 * The value is read and never reported: the caller turns each of these into a
 * fixed sentence that names the rule rather than repeating what was typed.
 */
export type AssigneeRefProblem =
  | "blank"
  /** Longer than Backend's 128 characters. */
  | "too-long"
  /** Not equal to its own Java `trim()`: leading or trailing whitespace. */
  | "untrimmed";

export function assigneeRefProblem(value: string): AssigneeRefProblem | null {
  if (isJavaBlank(value)) {
    return "blank";
  }
  if (value.length > MAX_ASSIGNEE_REF_LENGTH) {
    return "too-long";
  }
  if (!isJavaTrimmed(value)) {
    return "untrimmed";
  }
  return null;
}

/**
 * Whether a related-transaction filter is the canonical lowercase UUID v4 the
 * query contract requires.
 *
 * Applied to the value exactly as typed - never decoded, trimmed or case
 * folded - because an uppercase UUID is a different string to the validator on
 * the other side, and repairing it here would send a filter the analyst did not
 * write.
 */
export function isCanonicalTransactionFilter(value: string): boolean {
  return isCanonicalUuidV4(value);
}
