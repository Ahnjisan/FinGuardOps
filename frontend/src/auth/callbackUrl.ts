export const CALLBACK_PATH = "/auth/callback";

/**
 * What the captured address bar actually contains.
 *
 * `conflicting` is separated from `authorization-response` on purpose. A
 * response carrying both `code` and `error` is not a shape any Authorization
 * Server is allowed to produce, so it is treated as an attack surface rather
 * than resolved by precedence: the library happens to look at `error` first
 * today, but that is an internal detail, not a contract to depend on.
 */
export type CallbackUrlKind = "authorization-response" | "conflicting" | "none";

/**
 * Classifies the captured URL by exact query keys of a parsed URL, never by a
 * substring search: a path segment or another parameter's value containing
 * "code" must not be mistaken for a callback parameter. An unparseable URL
 * classifies as `none` and is handled as a direct entry, which ends in the
 * same safe error.
 */
export function classifyCallbackUrl(callbackUrl: string): CallbackUrlKind {
  let url: URL;
  try {
    url = new URL(callbackUrl);
  } catch {
    return "none";
  }
  const hasCode = url.searchParams.has("code");
  const hasError = url.searchParams.has("error");
  if (hasCode && hasError) {
    return "conflicting";
  }
  return hasCode || hasError ? "authorization-response" : "none";
}

/** Reports whether the captured URL carries an authorization response at all. */
export function hasCallbackParameters(callbackUrl: string): boolean {
  return classifyCallbackUrl(callbackUrl) !== "none";
}

/**
 * Replaces the current history entry with the bare callback path.
 *
 * Rewriting to a fixed path (rather than deleting known parameter names)
 * removes the whole query string and fragment by construction, so no
 * authorization code, state, issuer, session state or provider error
 * description can survive in the address bar or in `document.referrer`.
 *
 * Throws if the history API refuses the change; callers must fail closed.
 */
export function clearCallbackUrl(): void {
  window.history.replaceState(null, "", CALLBACK_PATH);
}
