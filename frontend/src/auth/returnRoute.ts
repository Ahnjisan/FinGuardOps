/**
 * Exact allowlist for post-login navigation. Nothing is decoded, trimmed,
 * unescaped or prefix-matched: a value is either one of the three literal
 * routes the application actually has, or it is replaced by the default. That
 * leaves no normalization step for an open-redirect payload to survive.
 *
 * `/transactions` is listed as itself and only as itself. It is not a prefix:
 * `/transactions/`, `/transactions/abc` and every encoded variation fall back
 * to the default, because a route that does not exist is not a place a user
 * should be returned to after signing in.
 */
export const ALLOWED_RETURN_ROUTES = ["/", "/health", "/transactions"] as const;

export type AllowedReturnRoute = (typeof ALLOWED_RETURN_ROUTES)[number];

export const DEFAULT_RETURN_ROUTE: AllowedReturnRoute = "/";

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
  return DEFAULT_RETURN_ROUTE;
}
