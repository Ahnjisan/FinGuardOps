/**
 * The exact post-logout landing address. It is the application root and
 * nothing else: the Keycloak client allowlists `http://localhost:5173/`
 * verbatim, so a callback that arrives anywhere else is not a response this
 * application asked for.
 */
export const LOGOUT_CALLBACK_PATH = "/";

/**
 * The only query parameters an end-session response may carry.
 *
 * `state` is ours; the three error fields are the ones OpenID Connect RP
 * Initiated Logout allows an Authorization Server to add. Anything else — a
 * `code`, a `session_state`, an `iss`, an application parameter — means the
 * address bar is not the response this application is prepared to consume.
 */
const ALLOWED_PARAMETERS = new Set(["state", "error", "error_description", "error_uri"]);

/**
 * The shape a state value is allowed to have.
 *
 * oidc-client-ts splits a response `state` on `;` and treats the tail as
 * round-tripped application data. This application never sends one, so a state
 * carrying that delimiter — or whitespace, or anything outside an unreserved
 * URL character — is refused here rather than silently truncated into
 * something that happens to match a stored transaction id.
 */
const STATE_VALUE = /^[A-Za-z0-9._~-]{1,256}$/;

/**
 * What the address bar holds on the root route.
 *
 * `none` is an ordinary page load: nothing about it is a logout response, so
 * the application initializes normally. `invalid` is the fail-closed answer for
 * everything that claims to be a logout response but is not exactly one — it is
 * deliberately not merged into `none`, because a malformed response must end in
 * the fixed sign-out error rather than in a silent normal start-up.
 */
export type LogoutCallbackKind = "logout-response" | "logout-error-response" | "invalid" | "none";

/**
 * Classifies the captured address bar as an end-session response, by exact
 * parsed URL parts rather than by substring search.
 *
 * Everything is judged fail-closed once the URL claims to be a response at all:
 * the origin must be exactly this document's, the path exactly `/`, there must
 * be no fragment and no userinfo, every parameter must be one of the four
 * allowed names, none of them may repeat, and `state` must be present exactly
 * once with a nonblank value. An error response must name its `error`; a
 * success response must carry `state` and nothing else, so an `error_description`
 * with no `error` cannot pass as a successful logout.
 */
export function classifyLogoutCallbackUrl(capturedUrl: string, origin: string): LogoutCallbackKind {
  let url: URL;
  try {
    url = new URL(capturedUrl);
  } catch {
    return "none";
  }
  if (url.origin !== origin || url.pathname !== LOGOUT_CALLBACK_PATH) {
    return "none";
  }

  // Every key as it actually appears, so a repeated parameter is visible.
  const keys = [...url.searchParams.keys()];
  if (!keys.includes("state") && !keys.includes("error")) {
    return "none";
  }

  if (url.hash !== "" || url.username !== "" || url.password !== "") {
    return "invalid";
  }
  if (new Set(keys).size !== keys.length) {
    return "invalid";
  }
  if (keys.some((key) => !ALLOWED_PARAMETERS.has(key))) {
    return "invalid";
  }
  if (!STATE_VALUE.test(url.searchParams.get("state") ?? "")) {
    return "invalid";
  }

  if (keys.includes("error")) {
    return (url.searchParams.get("error") ?? "").trim() === "" ? "invalid" : "logout-error-response";
  }
  // A success response is exactly one parameter: the state we sent.
  return keys.length === 1 ? "logout-response" : "invalid";
}

/**
 * Replaces the current history entry with the bare root path.
 *
 * Rewriting to a fixed path removes the whole query string and fragment by
 * construction, so no state, provider error code or error description can
 * survive in the address bar or in `document.referrer`.
 *
 * Throws if the history API refuses the change; callers must fail closed.
 */
export function clearLogoutCallbackUrl(): void {
  window.history.replaceState(null, "", LOGOUT_CALLBACK_PATH);
}
