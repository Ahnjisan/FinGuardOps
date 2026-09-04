/**
 * Exact allowlist for post-login navigation. Nothing is decoded, trimmed,
 * unescaped or prefix-matched: a value is either one of the two literal routes
 * the application actually has, or it is replaced by the default. That leaves
 * no normalization step for an open-redirect payload to survive.
 */
export const ALLOWED_RETURN_ROUTES = ["/", "/health"] as const;

export type AllowedReturnRoute = (typeof ALLOWED_RETURN_ROUTES)[number];

export const DEFAULT_RETURN_ROUTE: AllowedReturnRoute = "/";

export function resolveReturnRoute(value: unknown): AllowedReturnRoute {
  if (value === "/") {
    return "/";
  }
  if (value === "/health") {
    return "/health";
  }
  return DEFAULT_RETURN_ROUTE;
}
