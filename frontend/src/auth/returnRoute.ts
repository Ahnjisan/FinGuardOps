import { isCanonicalUuidV4 } from "../api/responseValidation";

/**
 * Exact allowlist for post-login navigation. Nothing is decoded, trimmed,
 * unescaped or prefix-matched: a value is either one of the routes this
 * application actually has, or it is replaced by the default. That leaves no
 * normalization step for an open-redirect payload to survive.
 *
 * There are two kinds of allowed value, and only two.
 *
 * The four literal routes below are compared with `===` against a string
 * literal, so `/transactions` is listed as itself and only as itself, and
 * `/cases` likewise. A literal admits itself and nothing beneath it:
 * `/cases/`, `/casesx` and `/cases?status=OPEN` are none of them the literal,
 * and none of them is repaired into it.
 *
 * The two detail routes are the parameterized destinations, and neither is
 * admitted by a prefix test. `startsWith("/transactions/")`,
 * `includes("/cases")` and a permissive pattern are all absent on purpose:
 * each of them accepts `/transactions/../admin`,
 * `/cases/%2f%2fevil.example` and `/cases/x` as readily as a real record. What
 * is admitted instead is a single path segment that is already a canonical
 * lowercase UUID v4 - the same rule the API layer applies to a path
 * parameter - and the returned value is *rebuilt* from that validated segment
 * rather than being the caller's string. So no byte of the input reaches a
 * navigation, an error, the DOM or the console: what comes back is either a
 * literal written in this file or a route assembled here from thirty-six
 * characters of `[0-9a-f-]`.
 */
export const ALLOWED_RETURN_ROUTES = ["/", "/health", "/transactions", "/cases"] as const;

export type LiteralReturnRoute = (typeof ALLOWED_RETURN_ROUTES)[number];

/** `/transactions/{canonical lowercase UUID v4}`, built only by this module. */
export type TransactionDetailReturnRoute = `/transactions/${string}`;

/** `/cases/{canonical lowercase UUID v4}`, built only by this module. */
export type CaseDetailReturnRoute = `/cases/${string}`;

export type AllowedReturnRoute =
  | LiteralReturnRoute
  | TransactionDetailReturnRoute
  | CaseDetailReturnRoute;

export const DEFAULT_RETURN_ROUTE: LiteralReturnRoute = "/";

/**
 * One path segment under `/transactions/`, and nothing else.
 *
 * `[^/]+` only says "one segment"; it decides nothing. `isCanonicalUuidV4`
 * below is the decision, and it is applied to the segment exactly as written -
 * never decoded, never trimmed, never case folded. A `%2f`, a `%5c`, a `%20`, a
 * space, a dot segment, an uppercase digit, a UUID v1/v3/v5 version nibble and
 * an invalid RFC variant nibble therefore all fail, as do a trailing slash, a
 * deeper path, a duplicate separator, a query and a fragment - none of which
 * can be part of a single `[^/]+` segment that consists only of `[0-9a-f-]`.
 */
const TRANSACTION_DETAIL_SEGMENT = /^\/transactions\/([^/]+)$/;

/**
 * One path segment under `/cases/`, and nothing else.
 *
 * The same rule as its ledger twin, written out separately rather than shared
 * through a factory: these are two allowlist entries for two different routes,
 * and a single generated pattern would make "the case route is admitted"
 * depend on a parameter rather than on a line in this file. A `%2f`, an encoded
 * backslash, whitespace, a dot segment, an uppercase digit, a non-v4 version
 * nibble and an invalid RFC variant nibble all fail `isCanonicalUuidV4` below,
 * as do a trailing slash, a deeper path, a duplicate separator, a query and a
 * fragment - none of which can be part of a single `[^/]+` segment that
 * consists only of `[0-9a-f-]`.
 */
const CASE_DETAIL_SEGMENT = /^\/cases\/([^/]+)$/;

export function resolveReturnRoute(value: unknown): AllowedReturnRoute {
  if (value === "/") {
    return "/";
  }
  if (value === "/health") {
    return "/health";
  }
  if (value === "/transactions") {
    return "/transactions";
  }
  // The list itself, exactly. Its parameterized sibling is decided below and
  // separately: no query or fragment is stripped here in the hope of
  // readmitting an address as this literal.
  if (value === "/cases") {
    return "/cases";
  }
  // `typeof` rather than `instanceof String`: a `String` object wrapping an
  // allowed route is not an allowed route, and neither is anything else that
  // merely stringifies into one.
  if (typeof value === "string") {
    const transaction = TRANSACTION_DETAIL_SEGMENT.exec(value);
    if (transaction !== null && isCanonicalUuidV4(transaction[1])) {
      return `/transactions/${transaction[1]}`;
    }
    // Rebuilt from the validated segment, never returned as the caller wrote
    // it. `/cases/` plus thirty-six characters of `[0-9a-f-]` is the whole of
    // what can come back from here, so an address that merely contained a
    // canonical case route carries nothing of its own through.
    const detail = CASE_DETAIL_SEGMENT.exec(value);
    if (detail !== null && isCanonicalUuidV4(detail[1])) {
      return `/cases/${detail[1]}`;
    }
  }
  return DEFAULT_RETURN_ROUTE;
}
