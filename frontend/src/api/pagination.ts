import {
  getBackendEndpoint,
  isApprovedQuerySet,
  isApprovedQueryValue,
  isStructurallyAllowedQueryValue,
  MAX_PAGE_SIZE,
  MIN_PAGE_SIZE,
  type BackendQueryParams,
  type BackendQueryValueRule,
} from "./backendEndpoints";
import { RequestNotAllowedError } from "./errors";
import {
  hasExactKeys,
  isBoolean,
  isInt32,
  isJsonObject,
  isSafeIntegerInRange,
  isSafeLong,
} from "./responseValidation";

export { MAX_PAGE_SIZE, MIN_PAGE_SIZE };

/**
 * The typed entry point to the query contract, and the page envelope validator.
 *
 * Two halves that never meet in the middle:
 *
 * - Outbound, a typed plain object is turned into approved query *values*. The
 *   caller never writes a query string, never hands over a `URL` or a
 *   `URLSearchParams`, and never gets a value coerced on its behalf. The rules
 *   applied are not this module's own: they are the ones the endpoint registry
 *   declares, so the typed builder and the URL re-verification cannot drift
 *   apart. A caller that skipped this builder entirely and handed a hand-made
 *   URL to the transport meets the same rules there.
 * - Inbound, `page` metadata is validated as a shape *and* as arithmetic.
 *   Backend builds it from a Spring `Page`, so `totalPages`, `first`, `last`
 *   and the item count are all derivable from `number`, `size` and
 *   `totalElements`. A response where they disagree is not a page this client
 *   can paginate, so it is refused rather than displayed.
 */

/** Backend parses `page` with `Integer.parseInt`. */
const MAX_INT32 = 2147483647;

/**
 * Turns one typed value into the string form the endpoint rule is written
 * against, without coercing anything.
 *
 * A `page` of `"0"` is refused rather than accepted as `0`, and a `sort` of
 * `Symbol()` or `3` is refused rather than stringified: the caller has to have
 * meant the type the contract names. Range and membership are not decided here
 * - the registry rule decides them, on this exact string.
 */
function toQueryString(rule: BackendQueryValueRule, value: unknown): string {
  switch (rule.kind) {
    case "page":
    case "size":
      // A fraction, `NaN`, an infinity and anything past
      // `Number.MAX_SAFE_INTEGER` never become a string here; the registry rule
      // then bounds what is left.
      if (!isSafeLong(value) || value < 0) {
        throw new RequestNotAllowedError();
      }
      return String(value);
    case "choice":
    case "instant":
    case "uuid":
    case "transaction-ref":
    case "case-assignee-ref":
      if (typeof value !== "string") {
        throw new RequestNotAllowedError();
      }
      return value;
  }
}

/**
 * Turns an endpoint's typed query object into approved string values, using
 * that endpoint's own contract from the registry.
 *
 * The input must be a plain own-property record. A prototype-bearing object, a
 * symbol key, a non-enumerable own key and any name outside the contract are
 * all refused rather than dropped, so a caller cannot believe it filtered
 * something it did not. An own key whose value is `undefined` is treated as
 * absent, which is what TypeScript's own optional-property semantics produce;
 * `null` is not, because a `null` is a caller asserting a value.
 *
 * An endpoint that declares no query refuses the argument itself, `{}`
 * included. Passing one means the caller believes a detail or write endpoint
 * filters something, and that belief is worth surfacing rather than dropping.
 *
 * Returns `undefined` when nothing is set, so the URL carries no `?` at all
 * rather than an empty query string.
 */
export function buildQueryValues(
  endpointKey: string,
  input: unknown,
): BackendQueryParams | undefined {
  const descriptor = getBackendEndpoint(endpointKey);
  if (descriptor === undefined) {
    throw new RequestNotAllowedError();
  }
  if (input === undefined) {
    return undefined;
  }
  if (descriptor.queryContract.length === 0) {
    throw new RequestNotAllowedError();
  }
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new RequestNotAllowedError();
  }
  const prototype: unknown = Object.getPrototypeOf(input);
  if (prototype !== Object.prototype && prototype !== null) {
    throw new RequestNotAllowedError();
  }
  if (Object.getOwnPropertySymbols(input).length !== 0) {
    throw new RequestNotAllowedError();
  }

  const allowedNames: readonly string[] = descriptor.queryParamNames;
  for (const name of Object.getOwnPropertyNames(input)) {
    if (!allowedNames.includes(name)) {
      throw new RequestNotAllowedError();
    }
  }

  const source = input as Record<string, unknown>;
  const values: Record<string, string> = {};
  let present = false;
  for (const { name, rule } of descriptor.queryContract) {
    if (!Object.prototype.hasOwnProperty.call(source, name)) {
      continue;
    }
    const value = source[name];
    if (value === undefined) {
      continue;
    }
    const text = toQueryString(rule, value);
    // The URL layer's structural floor and then the endpoint's own rule -
    // the same two checks the canonical builder applies, so the typed path
    // cannot be the lenient one.
    if (!isStructurallyAllowedQueryValue(text) || !isApprovedQueryValue(rule, text)) {
      throw new RequestNotAllowedError();
    }
    values[name] = text;
    present = true;
  }
  if (!present) {
    return undefined;
  }

  // The cross-value half of the same contract: `occurredAtFrom` is a fine
  // instant on its own and only becomes a 422 next to the `occurredAtTo` it is
  // later than. Running the endpoint's own declaration here, rather than a
  // per-module check, is what keeps this in step with the two URL layers.
  if (!isApprovedQuerySet(descriptor, values)) {
    throw new RequestNotAllowedError();
  }
  return values;
}

/**
 * Backend's page envelope, identical in `PageMetadataResponse` and
 * `FraudCasePageMetadataResponse`.
 */
export interface PageMetadata {
  readonly number: number;
  readonly size: number;
  readonly totalElements: number;
  readonly totalPages: number;
  readonly first: boolean;
  readonly last: boolean;
}

const PAGE_METADATA_KEYS: readonly string[] = [
  "number",
  "size",
  "totalElements",
  "totalPages",
  "first",
  "last",
];

/**
 * The page envelope as a shape: exactly six keys, each within the range its
 * Java type and the Backend validators allow.
 *
 * `totalElements` is a Java `long`. One past `Number.MAX_SAFE_INTEGER` has
 * already lost precision inside `JSON.parse`, so it is refused rather than
 * displayed as an approximate count.
 */
export function isPageMetadata(value: unknown): value is PageMetadata {
  if (!isJsonObject(value) || !hasExactKeys(value, PAGE_METADATA_KEYS)) {
    return false;
  }
  return (
    isSafeIntegerInRange(value.number, 0, MAX_INT32) &&
    isSafeIntegerInRange(value.size, MIN_PAGE_SIZE, MAX_PAGE_SIZE) &&
    isSafeLong(value.totalElements) &&
    value.totalElements >= 0 &&
    isInt32(value.totalPages) &&
    value.totalPages >= 0 &&
    isBoolean(value.first) &&
    isBoolean(value.last)
  );
}

/**
 * The page envelope as arithmetic.
 *
 * Backend derives every one of these from a Spring `Page`, so they are not
 * independent facts and a response where they disagree did not come from the
 * pagination this client is written against:
 *
 * - `totalPages` is the ceiling of `totalElements / size`, and zero when empty.
 * - `first` is `number === 0`; `last` is `number + 1 >= totalPages`.
 * - a page past the end carries no items; a page before the last is full; the
 *   last page carries exactly the remainder.
 *
 * Checking this matters because the alternative is a queue that silently
 * renders one page and hides the rest behind a `last: true` that was never
 * true.
 */
export function isConsistentPageMetadata(page: PageMetadata, contentLength: number): boolean {
  const expectedTotalPages =
    page.totalElements === 0 ? 0 : Math.ceil(page.totalElements / page.size);
  if (page.totalPages !== expectedTotalPages) {
    return false;
  }
  if (page.first !== (page.number === 0) || page.last !== (page.number + 1 >= page.totalPages)) {
    return false;
  }
  if (contentLength > page.size) {
    return false;
  }
  if (page.number >= page.totalPages) {
    return contentLength === 0;
  }
  if (page.number < page.totalPages - 1) {
    return contentLength === page.size;
  }
  return contentLength === page.totalElements - page.number * page.size;
}
