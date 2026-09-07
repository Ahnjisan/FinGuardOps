import { isCanonicalUuidV4 } from "../api/responseValidation";

/**
 * Exact allowlist for post-login navigation. Nothing is decoded, trimmed,
 * unescaped or prefix-matched: a value is either one of the routes this
 * application actually has, or it is replaced by the default. That leaves no
 * normalization step for an open-redirect payload to survive.
 *
 * There are two kinds of allowed value, and only two.
 *
 * The three literal routes below are compared with `===` against a string
 * literal, so `/transactions` is listed as itself and only as itself.
 *
 * The transaction detail route is the one parameterized destination, and it is
 * not admitted by a prefix test. `startsWith("/transactions/")`,
 * `includes("/transactions")` and a permissive pattern are all absent on
 * purpose: each of them accepts `/transactions/../admin`,
 * `/transactions/%2f%2fevil.example` and `/transactions/x` as readily as a real
 * transaction. What is admitted instead is a single path segment that is
 * already a canonical lowercase UUID v4 - the same rule the API layer applies
 * to a path parameter - and the returned value is *rebuilt* from that validated
 * segment rather than being the caller's string. So no byte of the input
 * reaches a navigation, an error, the DOM or the console: what comes back is
 * either a literal written in this file or a route assembled here from
 * thirty-six characters of `[0-9a-f-]`.
 */
export const ALLOWED_RETURN_ROUTES = ["/", "/health", "/transactions"] as const;

export type LiteralReturnRoute = (typeof ALLOWED_RETURN_ROUTES)[number];

/** `/transactions/{canonical lowercase UUID v4}`, built only by this module. */
export type TransactionDetailReturnRoute = `/transactions/${string}`;

export type AllowedReturnRoute = LiteralReturnRoute | TransactionDetailReturnRoute;

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
  // `typeof` rather than `instanceof String`: a `String` object wrapping an
  // allowed route is not an allowed route, and neither is anything else that
  // merely stringifies into one.
  if (typeof value === "string") {
    const match = TRANSACTION_DETAIL_SEGMENT.exec(value);
    if (match !== null && isCanonicalUuidV4(match[1])) {
      return `/transactions/${match[1]}`;
    }
  }
  return DEFAULT_RETURN_ROUTE;
}
