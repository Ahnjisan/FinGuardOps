import type { CredentialAuthClient } from "../auth/authClient";
import { sendAuthorizedBackendRequest } from "./authorizedClient";
import { CASE_AUDIT_LIST_SORTS } from "./backendEndpoints";
import {
  buildQueryValues,
  isConsistentPageMetadata,
  isPageMetadata,
  type PageMetadata,
} from "./pagination";
import {
  isArrayOf,
  isEnumMember,
  isNullableUuidV4String,
  isMicrosecondUtcInstantString,
  isObjectWithExactKeys,
  isTraceIdString,
  isUuidV4String,
  resolveTraceId,
  type ApiResult,
} from "./responseValidation";
import { CASE_FINAL_DISPOSITIONS, CASE_STATUSES } from "./caseApi";
import type { CaseFinalDisposition, CaseStatus } from "./caseApi";

/**
 * `GET /api/v1/cases/{caseId}/audit-logs`, the read-only case audit trail.
 *
 * An audit entry is a discriminated union, not a bag of optional fields, and
 * the discrimination goes deeper than `action`. Backend's `AuditMetadataPolicy`
 * decides an entry from `action` *and* `reasonCode` together, and it asserts
 * relationships between the before and after values: a case is created `OPEN`,
 * a transaction link is `true`, a review that resumed came from
 * `ADDITIONAL_INFORMATION_REQUIRED` and kept its assignee, a released assignee
 * ends up `null`, a resolution went `IN_REVIEW → CLOSED` without the case
 * changing hands. Backend answers 500 for the whole page rather than emit a row
 * it cannot project, so an entry that disagrees here is not a variant to
 * tolerate - it is evidence the response did not come from that mapper.
 *
 * The validator below is written the same way, per reason rather than per
 * action, precisely so that one broad "workflow summary" shape cannot be reused
 * to wave through a combination Backend would never store. Refusing the page
 * rather than the row is deliberate: an audit trail that silently drops the
 * entries it could not parse is the one kind of incorrect audit trail that
 * looks completely normal.
 */

/** The six `AuditAction` values a fraud case audit page can contain. */
export const CASE_AUDIT_ACTIONS = [
  "CASE_CREATED",
  "CASE_TRANSACTION_LINKED",
  "CASE_STATUS_CHANGED",
  "CASE_ASSIGNEE_CHANGED",
  "CASE_RESOLVED",
  "CASE_NOTE_CREATED",
] as const;
export type CaseAuditAction = (typeof CASE_AUDIT_ACTIONS)[number];

/** `AuditActorType`. `actorId` is deliberately never returned. */
export const AUDIT_ACTOR_TYPES = ["SYSTEM", "USER"] as const;
export type AuditActorType = (typeof AUDIT_ACTOR_TYPES)[number];

export const CASE_STATUS_CHANGED_REASONS = [
  "CASE_REVIEW_STARTED",
  "CASE_ADDITIONAL_INFORMATION_REQUESTED",
  "CASE_REVIEW_RESUMED",
] as const;
export type CaseStatusChangedReason = (typeof CASE_STATUS_CHANGED_REASONS)[number];

export const CASE_ASSIGNEE_CHANGED_REASONS = [
  "CASE_ASSIGNEE_ASSIGNED",
  "CASE_ASSIGNEE_CHANGED",
  "CASE_ASSIGNEE_RELEASED",
] as const;
export type CaseAssigneeChangedReason = (typeof CASE_ASSIGNEE_CHANGED_REASONS)[number];

/**
 * The `AuditReasonCode` values each action may carry, transcribed from
 * `FraudCaseAuditLogMapper.validateActionReason`. A reason outside its action's
 * list is refused rather than displayed next to the wrong change.
 */
const REASONS_BY_ACTION: Readonly<Record<CaseAuditAction, readonly string[]>> = Object.freeze({
  CASE_CREATED: ["CASE_REQUIRED_BY_RISK_POLICY"],
  CASE_TRANSACTION_LINKED: ["CASE_REQUIRED_BY_RISK_POLICY"],
  CASE_STATUS_CHANGED: CASE_STATUS_CHANGED_REASONS,
  CASE_ASSIGNEE_CHANGED: CASE_ASSIGNEE_CHANGED_REASONS,
  CASE_RESOLVED: ["CASE_RESOLUTION_COMPLETED"],
  CASE_NOTE_CREATED: ["CASE_INVESTIGATION_NOTE_ADDED"],
});

/** `CaseStatusSummary`: the after-state of case creation, always `OPEN`. */
export interface CaseStatusSummary {
  readonly caseStatus: "OPEN";
}

/** `LinkedSummary`: the after-state of a transaction link, always `true`. */
export interface LinkedSummary {
  readonly linked: true;
}

/** `WorkflowSummary`: status plus assignee, with an unassigned case as explicit null. */
export interface WorkflowSummary {
  readonly caseStatus: CaseStatus;
  readonly assigneeRef: string | null;
}

/** `ResolutionSummary`: the after-state of a closure, whose assignee is required. */
export interface ResolutionSummary {
  readonly caseStatus: "CLOSED";
  readonly assigneeRef: string;
  readonly finalDisposition: CaseFinalDisposition;
}

export type CaseAuditSummary =
  | CaseStatusSummary
  | LinkedSummary
  | WorkflowSummary
  | ResolutionSummary;

/** `NoteMetadata`, carried only by `CASE_NOTE_CREATED`. */
export interface NoteMetadata {
  readonly noteId: string;
}

/** `EmptyMetadata`, carried by every other action. */
export type EmptyMetadata = Record<string, never>;

interface CaseAuditEntryBase {
  readonly actorType: AuditActorType;
  readonly changedAt: string;
}

export type CaseAuditEntry = CaseAuditEntryBase &
  (
    | {
        readonly action: "CASE_CREATED";
        readonly reasonCode: "CASE_REQUIRED_BY_RISK_POLICY";
        readonly beforeSummary: null;
        readonly afterSummary: CaseStatusSummary;
        readonly metadata: EmptyMetadata;
      }
    | {
        readonly action: "CASE_TRANSACTION_LINKED";
        readonly reasonCode: "CASE_REQUIRED_BY_RISK_POLICY";
        readonly beforeSummary: null;
        readonly afterSummary: LinkedSummary;
        readonly metadata: EmptyMetadata;
      }
    | {
        readonly action: "CASE_STATUS_CHANGED";
        readonly reasonCode: CaseStatusChangedReason;
        readonly beforeSummary: WorkflowSummary;
        readonly afterSummary: WorkflowSummary;
        readonly metadata: EmptyMetadata;
      }
    | {
        readonly action: "CASE_ASSIGNEE_CHANGED";
        readonly reasonCode: CaseAssigneeChangedReason;
        readonly beforeSummary: WorkflowSummary;
        readonly afterSummary: WorkflowSummary;
        readonly metadata: EmptyMetadata;
      }
    | {
        readonly action: "CASE_RESOLVED";
        readonly reasonCode: "CASE_RESOLUTION_COMPLETED";
        readonly beforeSummary: WorkflowSummary;
        readonly afterSummary: ResolutionSummary;
        readonly metadata: EmptyMetadata;
      }
    | {
        readonly action: "CASE_NOTE_CREATED";
        readonly reasonCode: "CASE_INVESTIGATION_NOTE_ADDED";
        readonly beforeSummary: null;
        readonly afterSummary: null;
        readonly metadata: NoteMetadata;
      }
  );

export interface CaseAuditPage {
  readonly caseId: string;
  readonly content: readonly CaseAuditEntry[];
  readonly page: PageMetadata;
  readonly traceId: string;
}

/** The only two sorts `FraudCaseAuditLogQueryValidator` accepts. */
export { CASE_AUDIT_LIST_SORTS };
export type CaseAuditListSort = (typeof CASE_AUDIT_LIST_SORTS)[number];

export interface CaseAuditListQuery {
  readonly page?: number;
  readonly size?: number;
  readonly sort?: CaseAuditListSort;
}

function isCaseStatusSummary(value: unknown): value is CaseStatusSummary {
  // `validateCaseCreated` requires OPEN specifically: a case cannot be created
  // already closed, in review, or awaiting information.
  return isObjectWithExactKeys(value, ["caseStatus"]) && value.caseStatus === "OPEN";
}

function isLinkedSummary(value: unknown): value is LinkedSummary {
  // `linked` is asserted true, not merely boolean: there is no "unlinked" audit
  // entry, so `false` is a fabricated row rather than a negative fact.
  return isObjectWithExactKeys(value, ["linked"]) && value.linked === true;
}

function isWorkflowSummary(value: unknown): value is WorkflowSummary {
  return (
    isObjectWithExactKeys(value, ["caseStatus", "assigneeRef"]) &&
    isEnumMember(value.caseStatus, CASE_STATUSES) &&
    isNullableUuidV4String(value.assigneeRef)
  );
}

function isResolutionSummary(value: unknown): value is ResolutionSummary {
  return (
    isObjectWithExactKeys(value, ["caseStatus", "assigneeRef", "finalDisposition"]) &&
    // Always CLOSED, and the assignee is required: a case can only be resolved
    // from `IN_REVIEW`, which cannot be reached without one.
    value.caseStatus === "CLOSED" &&
    isUuidV4String(value.assigneeRef) &&
    isEnumMember(value.finalDisposition, CASE_FINAL_DISPOSITIONS)
  );
}

function isEmptyMetadata(value: unknown): value is EmptyMetadata {
  return isObjectWithExactKeys(value, []);
}

function isNoteMetadata(value: unknown): value is NoteMetadata {
  return isObjectWithExactKeys(value, ["noteId"]) && isUuidV4String(value.noteId);
}

/**
 * The before/after relationships `AuditMetadataPolicy.validateCaseStatusChanged`
 * asserts, one per reason code.
 *
 * These are the transitions themselves, not merely well-formed snapshots. A row
 * claiming `CASE_REVIEW_RESUMED` out of `OPEN`, or one that quietly changed the
 * assignee while only the status was supposed to move, is refused.
 */
function isConsistentStatusChange(
  reasonCode: CaseStatusChangedReason,
  before: WorkflowSummary,
  after: WorkflowSummary,
): boolean {
  switch (reasonCode) {
    case "CASE_REVIEW_STARTED":
      return (
        before.caseStatus === "OPEN" &&
        before.assigneeRef === null &&
        after.caseStatus === "IN_REVIEW" &&
        after.assigneeRef !== null
      );
    case "CASE_ADDITIONAL_INFORMATION_REQUESTED":
      return (
        before.caseStatus === "IN_REVIEW" &&
        after.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" &&
        before.assigneeRef !== null &&
        before.assigneeRef === after.assigneeRef
      );
    case "CASE_REVIEW_RESUMED":
      return (
        before.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" &&
        after.caseStatus === "IN_REVIEW" &&
        before.assigneeRef !== null &&
        before.assigneeRef === after.assigneeRef
      );
  }
}

/**
 * The same, for `validateCaseAssigneeChanged`.
 *
 * An assignee change never moves the status, and it is only possible in the two
 * editable states, so the policy requires the status to be equal on both sides
 * and to be one of those two before it even looks at the reason.
 */
function isConsistentAssigneeChange(
  reasonCode: CaseAssigneeChangedReason,
  before: WorkflowSummary,
  after: WorkflowSummary,
): boolean {
  const editableState =
    before.caseStatus === after.caseStatus &&
    (before.caseStatus === "IN_REVIEW" ||
      before.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED");
  if (!editableState) {
    return false;
  }
  switch (reasonCode) {
    case "CASE_ASSIGNEE_ASSIGNED":
      return (
        before.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" &&
        before.assigneeRef === null &&
        after.assigneeRef !== null
      );
    case "CASE_ASSIGNEE_CHANGED":
      return (
        before.assigneeRef !== null &&
        after.assigneeRef !== null &&
        before.assigneeRef !== after.assigneeRef
      );
    case "CASE_ASSIGNEE_RELEASED":
      return (
        before.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" &&
        before.assigneeRef !== null &&
        after.assigneeRef === null
      );
  }
}

const ENTRY_KEYS: readonly string[] = [
  "action",
  "reasonCode",
  "actorType",
  "changedAt",
  "beforeSummary",
  "afterSummary",
  "metadata",
];

/**
 * Validates one entry as a whole. The action and reason are read first and then
 * decide every remaining check, the relationship between the before and after
 * values included, so no summary shape is ever accepted on a combination that
 * cannot produce it.
 */
function isCaseAuditEntry(value: unknown): value is CaseAuditEntry {
  if (!isObjectWithExactKeys(value, ENTRY_KEYS)) {
    return false;
  }
  const { action, reasonCode, actorType, changedAt, beforeSummary, afterSummary, metadata } = value;

  if (!isEnumMember(action, CASE_AUDIT_ACTIONS)) {
    return false;
  }
  if (typeof reasonCode !== "string" || !REASONS_BY_ACTION[action].includes(reasonCode)) {
    return false;
  }
  // `changedAt` is microsecond-resolution on the audit column, and
  // `FraudCaseAuditLogMapper` fails the whole page on a finer value rather than
  // project it, so this is narrower than the shared instant validator.
  if (!isEnumMember(actorType, AUDIT_ACTOR_TYPES) || !isMicrosecondUtcInstantString(changedAt)) {
    return false;
  }

  const metadataMatches =
    action === "CASE_NOTE_CREATED" ? isNoteMetadata(metadata) : isEmptyMetadata(metadata);
  if (!metadataMatches) {
    return false;
  }

  switch (action) {
    case "CASE_CREATED":
      return beforeSummary === null && isCaseStatusSummary(afterSummary);
    case "CASE_TRANSACTION_LINKED":
      return beforeSummary === null && isLinkedSummary(afterSummary);
    case "CASE_STATUS_CHANGED":
      return (
        isWorkflowSummary(beforeSummary) &&
        isWorkflowSummary(afterSummary) &&
        isEnumMember(reasonCode, CASE_STATUS_CHANGED_REASONS) &&
        isConsistentStatusChange(reasonCode, beforeSummary, afterSummary)
      );
    case "CASE_ASSIGNEE_CHANGED":
      return (
        isWorkflowSummary(beforeSummary) &&
        isWorkflowSummary(afterSummary) &&
        isEnumMember(reasonCode, CASE_ASSIGNEE_CHANGED_REASONS) &&
        isConsistentAssigneeChange(reasonCode, beforeSummary, afterSummary)
      );
    case "CASE_RESOLVED":
      return (
        isWorkflowSummary(beforeSummary) &&
        isResolutionSummary(afterSummary) &&
        // `IN_REVIEW → CLOSED`, with the case never changing hands.
        beforeSummary.caseStatus === "IN_REVIEW" &&
        beforeSummary.assigneeRef !== null &&
        beforeSummary.assigneeRef === afterSummary.assigneeRef
      );
    case "CASE_NOTE_CREATED":
      return beforeSummary === null && afterSummary === null;
  }
}

export function isCaseAuditPage(value: unknown): value is CaseAuditPage {
  if (!isObjectWithExactKeys(value, ["caseId", "content", "page", "traceId"])) {
    return false;
  }
  if (
    !isUuidV4String(value.caseId) ||
    !isArrayOf(value.content, isCaseAuditEntry) ||
    !isPageMetadata(value.page) ||
    !isTraceIdString(value.traceId)
  ) {
    return false;
  }
  return isConsistentPageMetadata(value.page, value.content.length);
}

/**
 * `GET /api/v1/cases/{caseId}/audit-logs`.
 *
 * The response echoes the `caseId` that was asked for, and it is checked
 * against the requested one: a page of another case's audit trail is a
 * disclosure, not a display quirk.
 */
export async function fetchCaseAuditList(
  authClient: CredentialAuthClient,
  caseId: string,
  query?: CaseAuditListQuery,
  signal?: AbortSignal,
): Promise<ApiResult<CaseAuditPage>> {
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "case-audit-list",
    params: { caseId },
    query: buildQueryValues("case-audit-list", query),
    expectedStatus: 200,
    validate: (body: unknown): body is CaseAuditPage =>
      isCaseAuditPage(body) && body.caseId === caseId,
    signal,
  });
  return { data: result.data, traceId: resolveTraceId(result.traceId, result.data.traceId) };
}
