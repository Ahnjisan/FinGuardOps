import { RequestNotAllowedError } from "./errors";
import {
  compareUtcInstants,
  isCanonicalUuidV4,
  isJavaBlank,
  isJavaTrimmed,
  isUtcInstantString,
} from "./responseValidation";

export { isCanonicalUuidV4 };

/**
 * The exact set of Backend business endpoints a signed-in USER browser client
 * may call, and the exact query each of them accepts.
 *
 * This registry is the allowlist. Callers name an endpoint by key and supply
 * path parameter values and, for the list endpoints that have them, query
 * values; they cannot supply a URL, a method, a raw query string or a header.
 * Anything not described here has no code path to reach the network, which is
 * what keeps the SERVICE ingestion endpoints, the public health path, the
 * management listener, the AI service and every observability service
 * unreachable from this client rather than merely undocumented.
 *
 * A descriptor owns more than parameter *names*. Each query parameter carries
 * the rule its value must satisfy, transcribed from the Backend validator that
 * would otherwise answer 400 or 422. That is what lets the typed builder, the
 * URL builder and the URL re-verification all execute the same contract instead
 * of three drifting approximations of it - and it is why a hand-crafted URL
 * carrying `page=-1` is refused at the transport and at the credential
 * capability, not only at the typed entry point that a caller could bypass.
 *
 * Mirrors `FinGuardOpsSecurityConfiguration` and
 * `docs/02-architecture/security-architecture.md` section 5. Deliberately
 * absent, and not to be added without a Backend matrix change:
 *
 * - `GET /api/health` - public, credential-free, served by `healthApi.ts`
 * - `POST /api/v1/transactions` - SERVICE `transaction:intake`
 * - `POST /api/v1/behavior-events` - SERVICE `behavior-event:intake`
 * - `/actuator/**`, management listener 8081
 * - endpoints that exist only as documentation candidates
 */
export type BackendEndpointKey =
  | "transaction-list"
  | "transaction-detail"
  | "case-list"
  | "case-detail"
  | "case-note-list"
  | "case-audit-list"
  | "case-status-change"
  | "case-assignee-change"
  | "case-resolution-create"
  | "case-note-create";

export type BackendHttpMethod = "GET" | "PATCH" | "POST";

export type BackendPathParamName = "transactionId" | "caseId";

export type BackendPathParams = Readonly<Record<string, string>>;

/**
 * Every query parameter name any approved endpoint accepts, transcribed from
 * the Backend query validators. A name absent from this union cannot be spelled
 * anywhere in the registry, so an endpoint cannot quietly gain a filter the
 * Backend would answer 400 to.
 */
export type BackendQueryParamName =
  | "page"
  | "size"
  | "sort"
  | "occurredAtFrom"
  | "occurredAtTo"
  | "transactionType"
  | "processingStatus"
  | "externalCustomerRef"
  | "accountRef"
  | "caseStatus"
  | "finalDisposition"
  | "assigneeRef"
  | "createdAtFrom"
  | "createdAtTo"
  | "lastChangedAtFrom"
  | "lastChangedAtTo"
  | "transactionId";

/**
 * Already-stringified query values, keyed by approved name. This is the only
 * query input shape the URL layer accepts: never a raw query string, never a
 * caller-built `URL` or `URLSearchParams`, and never a value the URL layer
 * coerces on the caller's behalf.
 */
export type BackendQueryParams = Readonly<Record<string, string>>;

/**
 * `TransactionType`, transcribed from the Backend enum. Declared here rather
 * than in the API module because the registry needs it to validate the
 * `transactionType` filter, and one definition is what keeps the typed builder
 * and the URL re-verification from diverging.
 */
export const TRANSACTION_TYPES = [
  "ACCOUNT_TRANSFER",
  "OPEN_BANKING_TRANSFER",
  "ATM_WITHDRAWAL",
  "LOAN_DISBURSED",
] as const;

/** `TransactionProcessingStatus`. */
export const TRANSACTION_PROCESSING_STATUSES = [
  "RECEIVED",
  "ANALYZING",
  "ANALYZED",
  "APPROVED",
  "ADDITIONAL_AUTH_REQUIRED",
  "HELD",
  "FAILED",
] as const;

/** `FraudCaseStatus`. */
export const CASE_STATUSES = [
  "OPEN",
  "IN_REVIEW",
  "ADDITIONAL_INFORMATION_REQUIRED",
  "CLOSED",
] as const;

/** `FraudCaseFinalDisposition`. */
export const CASE_FINAL_DISPOSITIONS = ["NORMAL", "FALSE_POSITIVE", "CONFIRMED_FRAUD"] as const;

/** The only sorts each list validator accepts. Multi-sort and other fields are 400. */
export const TRANSACTION_LIST_SORTS = ["occurredAt,asc", "occurredAt,desc"] as const;
export const CASE_LIST_SORTS = ["lastChangedAt,asc", "lastChangedAt,desc"] as const;
export const NOTE_LIST_SORTS = ["createdAt,asc", "createdAt,desc"] as const;
export const CASE_AUDIT_LIST_SORTS = ["changedAt,asc", "changedAt,desc"] as const;

/**
 * How one query value is validated, in the string form it travels as.
 *
 * Each kind names a Backend validator rule rather than a general-purpose type,
 * so widening one endpoint's filter cannot silently widen another's.
 */
export type BackendQueryValueRule =
  /** 0-based page index within Java `int`. */
  | { readonly kind: "page" }
  /** Page size inside Backend's 1..100 window. */
  | { readonly kind: "size" }
  /** One exact member of a closed set: an enum filter, or `field,direction`. */
  | { readonly kind: "choice"; readonly allowed: readonly string[] }
  /** UTC ISO-8601 with a literal `Z`. */
  | { readonly kind: "instant" }
  /** Canonical lowercase UUID v4. */
  | { readonly kind: "uuid" }
  /**
   * A transaction reference filter, as `TransactionQueryValidator` defines
   * one: any non-blank value, searched exactly. No length bound and no trim
   * comparison, because Backend imposes neither - `" acct "` is a filter for
   * a stored reference that really does carry those spaces.
   */
  | { readonly kind: "transaction-ref" }
  /**
   * A case assignee filter, as `FraudCaseQueryValidator` defines one:
   * non-blank, at most 128 characters, and equal to its own Java `trim()`.
   *
   * Deliberately a separate rule from the transaction references above. The
   * two endpoints validate their references differently, and collapsing them
   * into one shared rule would silently impose this endpoint's bounds on the
   * other - refusing transaction filters Backend would have accepted.
   */
  | { readonly kind: "case-assignee-ref" };

export interface BackendQueryParamContract {
  readonly name: BackendQueryParamName;
  readonly rule: BackendQueryValueRule;
}

/**
 * A half-open instant range the endpoint validates across two of its own
 * parameters.
 *
 * Some of the contract cannot be checked one value at a time.
 * `occurredAtFrom` is a perfectly good instant on its own and only becomes a
 * 422 next to the `occurredAtTo` it is later than, so the range belongs to
 * the endpoint rather than to either parameter - which is what lets all
 * three layers enforce it from the same declaration instead of only the
 * typed entry point a caller can bypass.
 */
export interface BackendQueryRangeContract {
  readonly from: BackendQueryParamName;
  readonly to: BackendQueryParamName;
}

/** Backend's `size` bounds, identical across all four list validators. */
export const MIN_PAGE_SIZE = 1;
export const MAX_PAGE_SIZE = 100;

/** Backend parses `page` and `size` with `Integer.parseInt`. */
const MAX_INT32 = 2147483647;

/** No sign and no leading zero, which is the only form the canonical builder emits. */
const NON_NEGATIVE_INTEGER = /^(0|[1-9][0-9]*)$/;

/**
 * Applies one endpoint's rule to one already-stringified value.
 *
 * This is the single semantic gate. It runs on the way out, when the typed
 * builder has produced a string, and again on the way back in, when a
 * fully-formed URL is re-verified - so `?page=-1` handed straight to the
 * transport or to the credential capability is refused on its own merits rather
 * than because some earlier layer is assumed to have looked.
 */
export function isApprovedQueryValue(rule: BackendQueryValueRule, value: string): boolean {
  switch (rule.kind) {
    case "page":
      // Rejects a sign, a leading zero, a fraction and anything past Java `int`.
      // The length guard keeps the `Number` conversion exact.
      return NON_NEGATIVE_INTEGER.test(value) && value.length <= 10 && Number(value) <= MAX_INT32;
    case "size":
      return (
        NON_NEGATIVE_INTEGER.test(value) &&
        value.length <= 3 &&
        Number(value) >= MIN_PAGE_SIZE &&
        Number(value) <= MAX_PAGE_SIZE
      );
    case "choice":
      // Exact membership: no case folding, no trimming, no multi-sort, no
      // direction spelled `DESC`.
      return rule.allowed.includes(value);
    case "instant":
      return isUtcInstantString(value);
    case "uuid":
      return isCanonicalUuidV4(value);
    case "transaction-ref":
      // `validateReference` refuses blank and nothing else - no length bound,
      // no trim comparison - so a 257-character non-blank reference is a
      // legitimate exact-match filter and is sent verbatim.
      return !isJavaBlank(value);
    case "case-assignee-ref":
      return !isJavaBlank(value) && value.length <= 128 && isJavaTrimmed(value);
  }
}

/**
 * The cross-value half: every declared range must be ordered.
 *
 * A range with only one bound is a legitimate half-open filter and is left
 * alone. Equal bounds are an empty range, which Backend also allows.
 * Ordering is nanosecond-resolution, because both bounds become `Instant` on
 * the other side and `Date` would collapse a sub-millisecond inversion into
 * equality.
 *
 * Values are read but never reported: a refusal carries no query text, so
 * nothing reaches an error message or a log.
 */
export function isApprovedQuerySet(
  descriptor: BackendEndpointDescriptor,
  query: Readonly<Record<string, string>>,
): boolean {
  for (const { from, to } of descriptor.queryRanges) {
    if (
      !Object.prototype.hasOwnProperty.call(query, from) ||
      !Object.prototype.hasOwnProperty.call(query, to)
    ) {
      continue;
    }
    const start = query[from];
    const end = query[to];
    if (!isUtcInstantString(start) || !isUtcInstantString(end)) {
      return false;
    }
    if (compareUtcInstants(start, end) > 0) {
      return false;
    }
  }
  return true;
}

type PathSegment =
  | { readonly kind: "literal"; readonly value: string }
  | { readonly kind: "param"; readonly name: BackendPathParamName };

export interface BackendEndpointDescriptor {
  readonly key: BackendEndpointKey;
  readonly method: BackendHttpMethod;
  readonly template: string;
  readonly segments: readonly PathSegment[];
  readonly paramNames: readonly BackendPathParamName[];
  /**
   * The query parameters this endpoint accepts, each with the rule its value
   * must satisfy, in the one order the canonical builder emits them.
   *
   * An empty list means the endpoint takes no query at all. Every detail
   * endpoint and every write endpoint is in that group, and for them a query
   * argument is refused for existing - an empty object included, because an
   * empty object is still a caller believing this endpoint filters something.
   */
  readonly queryContract: readonly BackendQueryParamContract[];
  /** The declared names, derived from the contract so the two cannot drift. */
  readonly queryParamNames: readonly BackendQueryParamName[];
  /**
   * Instant ranges this endpoint validates across two of its parameters.
   * Empty for every endpoint that has no time filters.
   */
  readonly queryRanges: readonly BackendQueryRangeContract[];
  /** GET carries no request body; only PATCH and POST may send JSON. */
  readonly acceptsJsonBody: boolean;
}

const PARAM_NAMES: readonly BackendPathParamName[] = ["transactionId", "caseId"];

function isParamName(value: string): value is BackendPathParamName {
  return (PARAM_NAMES as readonly string[]).includes(value);
}

/**
 * Parses a template into fixed segments once, at module load. Templates are
 * source constants, so a malformed one is a build-time defect and throws here
 * rather than degrading into a permissive runtime path.
 */
function parseTemplate(template: string): readonly PathSegment[] {
  if (!template.startsWith("/") || template.endsWith("/")) {
    throw new Error("Backend endpoint template must start with, and not end with, a slash.");
  }
  return template
    .slice(1)
    .split("/")
    .map((part): PathSegment => {
      if (part.startsWith("{") && part.endsWith("}")) {
        const name = part.slice(1, -1);
        if (!isParamName(name)) {
          throw new Error("Backend endpoint template uses an unknown path parameter.");
        }
        return { kind: "param", name };
      }
      if (!/^[a-z0-9-]+$/.test(part)) {
        throw new Error("Backend endpoint template contains an unsupported literal segment.");
      }
      return { kind: "literal", value: part };
    });
}

function describe(
  key: BackendEndpointKey,
  method: BackendHttpMethod,
  template: string,
  queryContract: readonly BackendQueryParamContract[] = [],
  queryRanges: readonly BackendQueryRangeContract[] = [],
): BackendEndpointDescriptor {
  const segments = parseTemplate(template);
  const queryParamNames = queryContract.map((entry) => entry.name);
  if (new Set(queryParamNames).size !== queryParamNames.length) {
    throw new Error("Backend endpoint declares a query parameter more than once.");
  }
  if (queryParamNames.length > 0 && method !== "GET") {
    throw new Error("Only a GET endpoint may declare query parameters.");
  }
  for (const range of queryRanges) {
    if (!queryParamNames.includes(range.from) || !queryParamNames.includes(range.to)) {
      throw new Error("Backend endpoint declares a range over a parameter it does not have.");
    }
  }
  return {
    key,
    method,
    template,
    segments,
    paramNames: segments.flatMap((segment) => (segment.kind === "param" ? [segment.name] : [])),
    queryContract: Object.freeze([...queryContract]),
    queryParamNames: Object.freeze(queryParamNames),
    queryRanges: Object.freeze([...queryRanges]),
    acceptsJsonBody: method !== "GET",
  };
}

const PAGE: BackendQueryValueRule = { kind: "page" };
const SIZE: BackendQueryValueRule = { kind: "size" };
const INSTANT: BackendQueryValueRule = { kind: "instant" };
const UUID: BackendQueryValueRule = { kind: "uuid" };
const TRANSACTION_REF: BackendQueryValueRule = { kind: "transaction-ref" };
const CASE_ASSIGNEE_REF: BackendQueryValueRule = { kind: "case-assignee-ref" };

function choice(allowed: readonly string[]): BackendQueryValueRule {
  return { kind: "choice", allowed };
}

/** Transcribed from `TransactionQueryValidator`: nine single-valued names. */
const TRANSACTION_LIST_QUERY: readonly BackendQueryParamContract[] = [
  { name: "occurredAtFrom", rule: INSTANT },
  { name: "occurredAtTo", rule: INSTANT },
  { name: "transactionType", rule: choice(TRANSACTION_TYPES) },
  { name: "processingStatus", rule: choice(TRANSACTION_PROCESSING_STATUSES) },
  { name: "externalCustomerRef", rule: TRANSACTION_REF },
  { name: "accountRef", rule: TRANSACTION_REF },
  { name: "page", rule: PAGE },
  { name: "size", rule: SIZE },
  { name: "sort", rule: choice(TRANSACTION_LIST_SORTS) },
];

/** Transcribed from `FraudCaseQueryValidator`: eleven single-valued names. */
const CASE_LIST_QUERY: readonly BackendQueryParamContract[] = [
  { name: "caseStatus", rule: choice(CASE_STATUSES) },
  { name: "finalDisposition", rule: choice(CASE_FINAL_DISPOSITIONS) },
  { name: "assigneeRef", rule: CASE_ASSIGNEE_REF },
  { name: "createdAtFrom", rule: INSTANT },
  { name: "createdAtTo", rule: INSTANT },
  { name: "lastChangedAtFrom", rule: INSTANT },
  { name: "lastChangedAtTo", rule: INSTANT },
  { name: "transactionId", rule: UUID },
  { name: "page", rule: PAGE },
  { name: "size", rule: SIZE },
  { name: "sort", rule: choice(CASE_LIST_SORTS) },
];

/** `TransactionQueryValidator` validates `[occurredAtFrom, occurredAtTo)`. */
const TRANSACTION_LIST_RANGES: readonly BackendQueryRangeContract[] = [
  { from: "occurredAtFrom", to: "occurredAtTo" },
];

/** `FraudCaseQueryValidator` validates the two ranges independently. */
const CASE_LIST_RANGES: readonly BackendQueryRangeContract[] = [
  { from: "createdAtFrom", to: "createdAtTo" },
  { from: "lastChangedAtFrom", to: "lastChangedAtTo" },
];

/** Transcribed from `InvestigationNoteValidator`, which 400s any other name. */
const NOTE_LIST_QUERY: readonly BackendQueryParamContract[] = [
  { name: "page", rule: PAGE },
  { name: "size", rule: SIZE },
  { name: "sort", rule: choice(NOTE_LIST_SORTS) },
];

/** Transcribed from `FraudCaseAuditLogQueryValidator`. */
const CASE_AUDIT_LIST_QUERY: readonly BackendQueryParamContract[] = [
  { name: "page", rule: PAGE },
  { name: "size", rule: SIZE },
  { name: "sort", rule: choice(CASE_AUDIT_LIST_SORTS) },
];

const REGISTRY: Readonly<Record<BackendEndpointKey, BackendEndpointDescriptor>> = Object.freeze({
  "transaction-list": describe(
    "transaction-list",
    "GET",
    "/api/v1/transactions",
    TRANSACTION_LIST_QUERY,
    TRANSACTION_LIST_RANGES,
  ),
  "transaction-detail": describe(
    "transaction-detail",
    "GET",
    "/api/v1/transactions/{transactionId}",
  ),
  "case-list": describe(
    "case-list",
    "GET",
    "/api/v1/cases",
    CASE_LIST_QUERY,
    CASE_LIST_RANGES,
  ),
  "case-detail": describe("case-detail", "GET", "/api/v1/cases/{caseId}"),
  "case-note-list": describe(
    "case-note-list",
    "GET",
    "/api/v1/cases/{caseId}/notes",
    NOTE_LIST_QUERY,
  ),
  "case-audit-list": describe(
    "case-audit-list",
    "GET",
    "/api/v1/cases/{caseId}/audit-logs",
    CASE_AUDIT_LIST_QUERY,
  ),
  "case-status-change": describe("case-status-change", "PATCH", "/api/v1/cases/{caseId}/status"),
  "case-assignee-change": describe(
    "case-assignee-change",
    "PATCH",
    "/api/v1/cases/{caseId}/assignee",
  ),
  "case-resolution-create": describe(
    "case-resolution-create",
    "POST",
    "/api/v1/cases/{caseId}/resolution",
  ),
  "case-note-create": describe("case-note-create", "POST", "/api/v1/cases/{caseId}/notes"),
});

export const BACKEND_ENDPOINT_KEYS: readonly BackendEndpointKey[] = Object.freeze(
  Object.keys(REGISTRY) as BackendEndpointKey[],
);

/**
 * Own-property lookup only. A plain `REGISTRY[key]` would happily resolve
 * `"constructor"` or `"toString"` to something truthy from the prototype chain.
 */
export function getBackendEndpoint(key: string): BackendEndpointDescriptor | undefined {
  if (!Object.prototype.hasOwnProperty.call(REGISTRY, key)) {
    return undefined;
  }
  return REGISTRY[key as BackendEndpointKey];
}

/** The rule a given endpoint applies to a given query name, if it accepts one. */
export function getQueryRule(
  descriptor: BackendEndpointDescriptor,
  name: string,
): BackendQueryValueRule | undefined {
  return descriptor.queryContract.find((entry) => entry.name === name)?.rule;
}

/** Characters that must never appear in an assembled path, in any position. */
function hasUnsafePathCharacters(pathname: string): boolean {
  return (
    pathname.includes("%") ||
    pathname.includes("\\") ||
    pathname.includes(";") ||
    pathname.includes("..") ||
    pathname.includes("//") ||
    pathname.includes("?") ||
    pathname.includes("#") ||
    pathname.includes("@") ||
    /\s/.test(pathname)
  );
}

/**
 * Refuses anything that is not a plain own-property record whose every key is
 * approved for this endpoint.
 *
 * `Object.keys` alone would be too forgiving in three ways that matter here: an
 * inherited `page`, a non-enumerable own `size` and a symbol key would each be
 * silently dropped rather than refused, leaving the caller believing it sent a
 * filter it did not. A prototype-bearing object is refused outright, so a value
 * shaped by `Object.create` or by class instantiation never reaches the URL.
 */
function assertApprovedRecord(record: unknown, approved: readonly string[]): void {
  if (typeof record !== "object" || record === null) {
    throw new RequestNotAllowedError();
  }
  const prototype: unknown = Object.getPrototypeOf(record);
  if (prototype !== Object.prototype && prototype !== null) {
    throw new RequestNotAllowedError();
  }
  if (Object.getOwnPropertySymbols(record).length !== 0) {
    throw new RequestNotAllowedError();
  }
  for (const name of Object.getOwnPropertyNames(record)) {
    if (!approved.includes(name)) {
      throw new RequestNotAllowedError();
    }
  }
}

/**
 * The structural floor every query value has to clear before its endpoint rule
 * is consulted: a non-empty string free of C0/C1 control characters.
 *
 * Deliberately carries **no length bound**. Length is not a structural fact,
 * it is part of each endpoint's contract, and the endpoints disagree:
 * `FraudCaseQueryValidator` bounds `assigneeRef` at 128 characters while
 * `TransactionQueryValidator.validateReference` bounds its references not at
 * all. A shared cap here would quietly impose one endpoint's limit on the
 * other and refuse transaction filters Backend would have accepted, so every
 * rule states its own bound instead - `page` and `size` by digit count,
 * `instant` and `uuid` by grammar, `case-assignee-ref` by Backend's 128, and
 * `transaction-ref` by nothing.
 *
 * Everything except control characters - `&`, `=`, `#`, `%`, `?`, spaces,
 * quotes - is allowed as a *value* precisely because it is percent-encoded on
 * the way out and therefore cannot become query structure. An opaque operator
 * reference that happens to contain a separator is data, not a second
 * parameter.
 */
export function isStructurallyAllowedQueryValue(value: unknown): value is string {
  if (typeof value !== "string" || value.length === 0) {
    return false;
  }
  for (const character of value) {
    const codePoint = character.codePointAt(0) ?? 0;
    if (codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)) {
      return false;
    }
  }
  return true;
}

/**
 * The one canonical serialization of an endpoint's query, with every value
 * checked against that endpoint's own rule.
 *
 * Values are placed with `URLSearchParams.set()`, never appended and never
 * concatenated, so one name holds exactly one value and no value can introduce
 * a separator. Emission follows the descriptor's declared order rather than the
 * caller's key order, which is what makes the result a function of the values
 * alone and therefore comparable byte-for-byte.
 */
function buildCanonicalSearch(
  descriptor: BackendEndpointDescriptor,
  query: BackendQueryParams | undefined,
): string {
  if (query === undefined) {
    return "";
  }
  // An endpoint that declares no query does not accept the *argument*. `{}` is
  // refused with the rest: a caller passing it believes this endpoint filters
  // something, and that belief is wrong in a way worth surfacing.
  if (descriptor.queryContract.length === 0) {
    throw new RequestNotAllowedError();
  }
  assertApprovedRecord(query, descriptor.queryParamNames);

  const search = new URLSearchParams();
  for (const { name, rule } of descriptor.queryContract) {
    if (!Object.prototype.hasOwnProperty.call(query, name)) {
      continue;
    }
    const value: unknown = query[name];
    if (!isStructurallyAllowedQueryValue(value) || !isApprovedQueryValue(rule, value)) {
      throw new RequestNotAllowedError();
    }
    search.set(name, value);
  }

  // The cross-value half, from the same declaration the URL re-verification
  // uses. An inverted range never reaches a URL, so it never reaches the
  // credential lookup either.
  if (!isApprovedQuerySet(descriptor, query)) {
    throw new RequestNotAllowedError();
  }

  const serialized = search.toString();
  return serialized === "" ? "" : `?${serialized}`;
}

/**
 * The base URL is already validated by `parseApiBaseUrl`, but it is operator
 * input and may legitimately carry a path prefix
 * (`https://gateway.example/finguard`). That prefix becomes part of every
 * business URL, so it is re-validated here and compared exactly rather than
 * assumed to be empty.
 */
function resolveBasePath(baseUrl: string): { readonly origin: string; readonly basePath: string } {
  let base: URL;
  try {
    base = new URL(baseUrl);
  } catch {
    throw new RequestNotAllowedError();
  }
  if (base.protocol !== "http:" && base.protocol !== "https:") {
    throw new RequestNotAllowedError();
  }
  if (base.username !== "" || base.password !== "" || base.search !== "" || base.hash !== "") {
    throw new RequestNotAllowedError();
  }
  const basePath = base.pathname === "/" ? "" : base.pathname.replace(/\/+$/, "");
  if (basePath !== "" && (!basePath.startsWith("/") || hasUnsafePathCharacters(basePath))) {
    throw new RequestNotAllowedError();
  }
  return { origin: `${base.protocol}//${base.host}`, basePath };
}

function resolveParamValue(
  params: BackendPathParams | undefined,
  name: BackendPathParamName,
): string {
  if (params === undefined || !Object.prototype.hasOwnProperty.call(params, name)) {
    throw new RequestNotAllowedError();
  }
  const value: unknown = params[name];
  if (typeof value !== "string" || !isCanonicalUuidV4(value)) {
    throw new RequestNotAllowedError();
  }
  return value;
}

export interface BackendRequestTarget {
  readonly descriptor: BackendEndpointDescriptor;
  readonly url: string;
}

/**
 * Re-parses an assembled URL and compares every component against what was
 * intended.
 *
 * Comparison is exact on origin, userinfo, pathname, search and hash - never
 * `startsWith`, never substring containment. That is the difference between
 * accepting only `http://localhost:8080/api/v1/cases` and also accepting
 * `http://localhost:8080.evil.example/api/v1/cases` or
 * `http://localhost:8080/api/v1/cases/../../actuator`, both of which share a
 * prefix with the intended value. The search string is held to the same
 * severity, so a duplicated name, an unknown name and a re-encoded value that
 * merely decodes to the intended one are all mismatches.
 *
 * Exported because it is the boundary's last check and is verified directly
 * against those near-miss URLs, rather than only through inputs that happen to
 * be well-formed by the time they get here.
 */
export function isExactBackendUrl(
  candidate: string,
  expectedOrigin: string,
  expectedPathname: string,
  expectedSearch: string,
): boolean {
  let assembled: URL;
  try {
    assembled = new URL(candidate);
  } catch {
    return false;
  }
  return (
    assembled.origin === expectedOrigin &&
    `${assembled.protocol}//${assembled.host}` === expectedOrigin &&
    assembled.username === "" &&
    assembled.password === "" &&
    assembled.pathname === expectedPathname &&
    assembled.search === expectedSearch &&
    assembled.hash === "" &&
    assembled.href === candidate
  );
}

/**
 * Builds the one URL an endpoint key, its path parameters and its query are
 * allowed to produce, then verifies the result component by component.
 *
 * The re-check is deliberate defense in depth: assembly already uses only
 * source-constant literals, UUID-validated parameters and rule-validated,
 * `URLSearchParams`-encoded query values, so a mismatch here means an
 * assumption broke.
 */
export function buildBackendRequestUrl(
  baseUrl: string,
  key: string,
  params?: BackendPathParams,
  query?: BackendQueryParams,
): BackendRequestTarget {
  const descriptor = getBackendEndpoint(key);
  if (descriptor === undefined) {
    throw new RequestNotAllowedError();
  }

  // Every supplied parameter must be one this endpoint actually has: an extra
  // key means the caller believes it is calling something else.
  if (params !== undefined) {
    assertApprovedRecord(params, descriptor.paramNames);
  }

  const expectedSearch = buildCanonicalSearch(descriptor, query);

  const { origin, basePath } = resolveBasePath(baseUrl);

  const endpointPath = `/${descriptor.segments
    .map((segment) =>
      segment.kind === "literal" ? segment.value : resolveParamValue(params, segment.name),
    )
    .join("/")}`;

  const expectedPathname = `${basePath}${endpointPath}`;
  if (hasUnsafePathCharacters(expectedPathname)) {
    throw new RequestNotAllowedError();
  }

  const candidate = `${origin}${expectedPathname}${expectedSearch}`;
  if (!isExactBackendUrl(candidate, origin, expectedPathname, expectedSearch)) {
    throw new RequestNotAllowedError();
  }

  return { descriptor, url: candidate };
}

/**
 * Re-parses a URL's query into at most one value per name.
 *
 * Returns `undefined` - "this is not an approved query at all" - for a repeated
 * name and for an empty name. Everything that survives is validated against the
 * endpoint's rules and handed back to the canonical builder, whose output must
 * then match the original URL exactly, so a value that merely *decodes* to
 * something approved is not enough.
 *
 * The accumulator has a null prototype so that a parameter literally named
 * `__proto__` or `constructor` is an ordinary key rather than a write through
 * the prototype chain.
 */
function parseQuery(search: string): Record<string, string> | undefined {
  const parsed = new URLSearchParams(search);
  const query = Object.create(null) as Record<string, string>;
  for (const [name, value] of parsed.entries()) {
    if (name === "" || Object.prototype.hasOwnProperty.call(query, name)) {
      return undefined;
    }
    query[name] = value;
  }
  return query;
}

/**
 * Decides whether a fully formed URL and method are one of the approved
 * Backend USER requests, without being told which endpoint was intended.
 *
 * This is the check for code that holds a URL rather than an endpoint key —
 * most importantly the credential capability, which must be able to refuse a
 * destination on its own rather than trusting that its caller already did. It
 * matches the path against the registry segment by segment, validates every
 * path parameter, re-parses the query into at most one value per approved name,
 * runs each value through that endpoint's own rule, and then rebuilds the URL
 * through the same exact builder and requires an identical result.
 *
 * A near miss never matches: not a different origin, not a trailing slash, not
 * an encoded separator, not a duplicated or unknown query parameter, not a
 * query on an endpoint that accepts none, not a non-canonical encoding of an
 * otherwise approved value, not a hand-crafted `page=-1`, `size=101`,
 * `sort=createdAt,asc` or malformed `transactionId`, and not the right path
 * with the wrong method.
 */
export function findApprovedBackendRequest(
  baseUrl: string,
  method: string,
  url: string,
): BackendEndpointDescriptor | undefined {
  let candidate: URL;
  try {
    candidate = new URL(url);
  } catch {
    return undefined;
  }
  if (candidate.username !== "" || candidate.password !== "" || candidate.hash !== "") {
    return undefined;
  }
  if (hasUnsafePathCharacters(candidate.pathname)) {
    return undefined;
  }

  const query = parseQuery(candidate.search);
  if (query === undefined) {
    return undefined;
  }
  const queryNames = Object.keys(query);

  let base: { readonly origin: string; readonly basePath: string };
  try {
    base = resolveBasePath(baseUrl);
  } catch {
    return undefined;
  }
  if (
    candidate.origin !== base.origin ||
    `${candidate.protocol}//${candidate.host}` !== base.origin
  ) {
    return undefined;
  }

  // The base prefix must end on a segment boundary, so a base of `/finguard`
  // does not match a path under `/finguardx`.
  if (base.basePath !== "" && !candidate.pathname.startsWith(`${base.basePath}/`)) {
    return undefined;
  }
  const endpointPath = candidate.pathname.slice(base.basePath.length);
  if (!endpointPath.startsWith("/") || endpointPath.endsWith("/")) {
    return undefined;
  }
  const parts = endpointPath.slice(1).split("/");

  for (const key of BACKEND_ENDPOINT_KEYS) {
    const descriptor = REGISTRY[key];
    if (descriptor.method !== method || descriptor.segments.length !== parts.length) {
      continue;
    }

    const params: Record<string, string> = {};
    let matched = true;
    for (let index = 0; index < parts.length; index += 1) {
      const segment = descriptor.segments[index];
      const part = parts[index];
      if (segment.kind === "literal") {
        if (part !== segment.value) {
          matched = false;
          break;
        }
      } else if (isCanonicalUuidV4(part)) {
        params[segment.name] = part;
      } else {
        matched = false;
        break;
      }
    }
    if (!matched) {
      continue;
    }

    // The endpoint's own contract, applied to the URL as it actually arrived: a
    // name it does not declare - including any name at all on a detail or write
    // endpoint - and a value its rule refuses are both rejected here, before
    // the byte-for-byte comparison would have had a chance to be persuaded by a
    // value that merely round-trips.
    for (const name of queryNames) {
      const rule = getQueryRule(descriptor, name);
      if (rule === undefined) {
        return undefined;
      }
      const value = query[name];
      if (!isStructurallyAllowedQueryValue(value) || !isApprovedQueryValue(rule, value)) {
        return undefined;
      }
    }

    // and the cross-value half, so a URL carrying an inverted range is refused
    // here as well - including one handed straight to the transport or to the
    // credential capability, which never see the typed builder.
    if (!isApprovedQuerySet(descriptor, query)) {
      return undefined;
    }

    // Same builder, same exact comparison: whatever this URL is, it has to be
    // byte-for-byte what an approved request would have produced.
    try {
      const rebuilt = buildBackendRequestUrl(
        baseUrl,
        descriptor.key,
        params,
        queryNames.length === 0 ? undefined : { ...query },
      );
      return rebuilt.url === candidate.href ? descriptor : undefined;
    } catch {
      return undefined;
    }
  }

  return undefined;
}
