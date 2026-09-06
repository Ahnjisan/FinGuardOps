import type { CredentialAuthClient } from "../auth/authClient";
import { readExactRequestFields, sendAuthorizedBackendRequest } from "./authorizedClient";
import { RequestNotAllowedError } from "./errors";
import { NOTE_LIST_SORTS } from "./backendEndpoints";
import {
  buildQueryValues,
  isConsistentPageMetadata,
  isPageMetadata,
  type PageMetadata,
} from "./pagination";
import {
  isArrayOf,
  isEnumMember,
  isNoteContentString,
  isObjectWithExactKeys,
  isSafeLong,
  isTraceIdString,
  isUtcInstantString,
  isUuidV4String,
  resolveTraceId,
  type ApiResult,
} from "./responseValidation";

/**
 * The two approved USER investigation-note endpoints.
 *
 * `content` is untrusted plain text that Backend stores verbatim and does not
 * interpret. This module preserves it exactly and never repairs it. Anything
 * rendering it must escape it: `innerHTML` and `dangerouslySetInnerHTML` are
 * not acceptable outputs for a note, and the Backend's refusal to execute the
 * text says nothing about what a browser would do with it.
 */

/** `InvestigationNoteAuthorType`. */
export const NOTE_AUTHOR_TYPES = ["SYSTEM", "USER"] as const;
export type NoteAuthorType = (typeof NOTE_AUTHOR_TYPES)[number];

/** `InvestigationNote.SYSTEM_AUTHOR_REF`, the only reference a SYSTEM note carries. */
export const SYSTEM_NOTE_AUTHOR_REF = "finguardops-backend";

/** The only two sorts `InvestigationNoteValidator` accepts; it defaults to ascending. */
export { NOTE_LIST_SORTS };
export type NoteListSort = (typeof NOTE_LIST_SORTS)[number];

export interface InvestigationNote {
  readonly noteId: string;
  readonly caseId: string;
  readonly authorType: NoteAuthorType;
  readonly authorRef: string;
  /** Untrusted plain text, preserved exactly. Escape before display. */
  readonly content: string;
  readonly createdAt: string;
}

export interface InvestigationNotePage {
  /** Named `items`, not `content`: this envelope differs from the other three. */
  readonly items: readonly InvestigationNote[];
  readonly page: PageMetadata;
  readonly traceId: string;
}

export interface InvestigationNoteCreated {
  readonly noteId: string;
  readonly caseId: string;
  readonly authorType: NoteAuthorType;
  readonly authorRef: string;
  readonly content: string;
  readonly createdAt: string;
  readonly concurrencyVersion: number;
  readonly traceId: string;
}

export interface InvestigationNoteListQuery {
  readonly page?: number;
  readonly size?: number;
  readonly sort?: NoteListSort;
}

export interface InvestigationNoteCreateRequest {
  /** 1 to 4,000 Unicode code points of plain text. */
  readonly content: string;
  readonly expectedVersion: number;
}

/**
 * The author pair is validated together, not field by field.
 *
 * A SYSTEM note carries exactly `finguardops-backend`; a USER note carries the
 * canonical lowercase UUID v4 taken from the verified JWT `sub`. A USER note
 * bearing the system reference, or a SYSTEM note bearing a UUID, is a crossed
 * pair that the DB CHECK constraint forbids - so it is a malformed response
 * rather than something to display as an author.
 */
function isValidAuthorPair(authorType: unknown, authorRef: unknown): boolean {
  if (authorType === "SYSTEM") {
    return authorRef === SYSTEM_NOTE_AUTHOR_REF;
  }
  if (authorType === "USER") {
    return isUuidV4String(authorRef);
  }
  return false;
}

const NOTE_KEYS: readonly string[] = [
  "noteId",
  "caseId",
  "authorType",
  "authorRef",
  "content",
  "createdAt",
];

function isInvestigationNote(value: unknown): value is InvestigationNote {
  if (!isObjectWithExactKeys(value, NOTE_KEYS)) {
    return false;
  }
  return (
    isUuidV4String(value.noteId) &&
    isUuidV4String(value.caseId) &&
    isEnumMember(value.authorType, NOTE_AUTHOR_TYPES) &&
    isValidAuthorPair(value.authorType, value.authorRef) &&
    isNoteContentString(value.content) &&
    isUtcInstantString(value.createdAt)
  );
}

const CREATED_KEYS: readonly string[] = [...NOTE_KEYS, "concurrencyVersion", "traceId"];

export function isInvestigationNoteCreated(value: unknown): value is InvestigationNoteCreated {
  if (!isObjectWithExactKeys(value, CREATED_KEYS)) {
    return false;
  }
  return (
    isUuidV4String(value.noteId) &&
    isUuidV4String(value.caseId) &&
    isEnumMember(value.authorType, NOTE_AUTHOR_TYPES) &&
    isValidAuthorPair(value.authorType, value.authorRef) &&
    isNoteContentString(value.content) &&
    isUtcInstantString(value.createdAt) &&
    isSafeLong(value.concurrencyVersion) &&
    value.concurrencyVersion >= 0 &&
    isTraceIdString(value.traceId)
  );
}

export function isInvestigationNotePage(value: unknown): value is InvestigationNotePage {
  if (!isObjectWithExactKeys(value, ["items", "page", "traceId"])) {
    return false;
  }
  if (
    !isArrayOf(value.items, isInvestigationNote) ||
    !isPageMetadata(value.page) ||
    !isTraceIdString(value.traceId)
  ) {
    return false;
  }
  return isConsistentPageMetadata(value.page, value.items.length);
}

/** `GET /api/v1/cases/{caseId}/notes`. */
export async function fetchInvestigationNoteList(
  authClient: CredentialAuthClient,
  caseId: string,
  query?: InvestigationNoteListQuery,
  signal?: AbortSignal,
): Promise<ApiResult<InvestigationNotePage>> {
  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "case-note-list",
    params: { caseId },
    query: buildQueryValues("case-note-list", query),
    expectedStatus: 200,
    validate: isInvestigationNotePage,
    signal,
  });
  return { data: result.data, traceId: resolveTraceId(result.traceId, result.data.traceId) };
}

/**
 * `POST /api/v1/cases/{caseId}/notes`.
 *
 * The only endpoint in this client that answers 201; a 200 here would mean
 * something other than the documented creation, so it is refused. `authorRef`,
 * `actorType` and `actorId` are decided by Backend from the verified JWT and
 * are not fields this request can carry - the exact-key check refuses them
 * rather than letting a caller propose an author.
 *
 * Sent exactly once and never retried. A replayed create would append a second
 * note to the investigation record, and there is no idempotency key to collapse
 * it.
 */
export async function createInvestigationNote(
  authClient: CredentialAuthClient,
  caseId: string,
  request: InvestigationNoteCreateRequest,
  signal?: AbortSignal,
): Promise<ApiResult<InvestigationNoteCreated>> {
  const fields = readExactRequestFields(request, ["content", "expectedVersion"]);

  if (!isNoteContentString(fields.content)) {
    throw new RequestNotAllowedError();
  }
  if (!isSafeLong(fields.expectedVersion) || fields.expectedVersion < 0) {
    throw new RequestNotAllowedError();
  }

  const result = await sendAuthorizedBackendRequest(authClient, {
    endpoint: "case-note-create",
    params: { caseId },
    body: { content: fields.content, expectedVersion: fields.expectedVersion },
    expectedStatus: 201,
    validate: isInvestigationNoteCreated,
    signal,
  });
  return { data: result.data, traceId: resolveTraceId(result.traceId, result.data.traceId) };
}
