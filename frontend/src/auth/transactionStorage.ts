/** Prefix for the in-memory user store. Never backed by Web Storage. */
export const OIDC_USER_STORE_PREFIX = "finguardops.oidc.user.";

/**
 * Prefix for the transient protocol transaction records the library needs to
 * survive the redirect. Kept deliberately distinct from the user store prefix
 * so a transaction sweep can never reach anything else.
 */
export const OIDC_TRANSACTION_STORE_PREFIX = "finguardops.oidc.transaction.";

/**
 * The single failure the storage boundary is allowed to raise.
 *
 * It carries no DOM payload. A hostile `window.sessionStorage` getter throws a
 * `SecurityError` whose message and stack describe the embedding context, and
 * that DOMException stops here: it is never re-thrown, logged, rendered or
 * stored in a context value.
 */
export class AuthStorageUnavailableError extends Error {
  constructor() {
    super("Session storage is unavailable.");
    this.name = "AuthStorageUnavailableError";
  }
}

/**
 * Reads the `window.sessionStorage` property inside a try/catch.
 *
 * The property is a getter, not a field: in a partitioned, sandboxed or
 * cookie-blocked context it throws rather than returning null, so a bare read
 * anywhere in the boundary would take down whatever is executing at the time —
 * a module import, a factory call or the first React render. Every read in the
 * auth boundary goes through this function, which is why storage acquisition
 * can be deferred to the point where a fixed authentication error is the
 * correct outcome.
 */
export function acquireTransactionStorage(): Storage {
  let storage: Storage | null | undefined;
  try {
    storage = window.sessionStorage;
  } catch {
    throw new AuthStorageUnavailableError();
  }
  if (storage === null || storage === undefined) {
    throw new AuthStorageUnavailableError();
  }
  return storage;
}

/**
 * Removes every transaction record this application owns, and nothing else.
 *
 * Keys are collected first (walking backwards) and removed afterwards, so the
 * index shifting that `removeItem` causes during a forward walk cannot make the
 * sweep skip an entry. Storage failures are propagated: callers decide whether
 * a failure is fail-closed (before a redirect) or best-effort (after teardown).
 */
export function clearAuthTransactionState(storage: Storage): void {
  const keysToRemove: string[] = [];
  for (let index = storage.length - 1; index >= 0; index -= 1) {
    const key = storage.key(index);
    if (key !== null && key.startsWith(OIDC_TRANSACTION_STORE_PREFIX)) {
      keysToRemove.push(key);
    }
  }
  for (const key of keysToRemove) {
    storage.removeItem(key);
  }
}

/**
 * The two oidc-client-ts request types this application is allowed to have a
 * transaction record for. The library stamps every record it writes with one of
 * these; `si:s` (silent) and `si:p`/`so:p` (popup) belong to flows this client
 * does not run, so a record claiming one of them is refused rather than swept.
 */
export const OIDC_LOGIN_REQUEST_TYPE = "si:r";
export const OIDC_LOGOUT_REQUEST_TYPE = "so:r";

/**
 * The entire application payload a logout transaction may carry.
 *
 * oidc-client-ts only persists a signout state when the caller supplies one, so
 * something has to be sent; this is deliberately a fixed marker rather than
 * anything session-specific. No subject, no return route, no token and no
 * provider value travels through the address bar and back.
 */
export const LOGOUT_TRANSACTION_DATA: { readonly kind: "sign-out" } = Object.freeze({
  kind: "sign-out",
});

/**
 * The exact field set `SigninState.toStorageString()` produces for the redirect
 * sign-in this client performs, pinned to oidc-client-ts 3.5.0.
 *
 * Every one of these is required, and nothing outside the list may be present.
 * `extraTokenParams` is on the list rather than tolerated as optional because
 * the library always writes it: `OidcClientSettingsStore` defaults it to `{}`
 * and `SigninState.toStorageString()` serializes it, so a record without it is
 * not a record this client wrote. `oidcAuthClient.test.ts` pins that by driving
 * the real `UserManager` and validating the record it actually stores.
 */
const LOGIN_FIELDS: readonly string[] = [
  "id",
  "data",
  "created",
  "request_type",
  "code_verifier",
  "authority",
  "client_id",
  "redirect_uri",
  "scope",
  "extraTokenParams",
  "nonce",
];

/**
 * The exact field set `State.toStorageString()` produces for a signout request.
 *
 * A logout transaction has no nonce, no PKCE verifier and no authority: those
 * belong to a code exchange, and this record never takes part in one. Listing
 * the permitted fields rather than the forbidden ones is what makes an injected
 * `nonce` or `code_verifier` a refusal instead of a field nobody looked at.
 */
const LOGOUT_FIELDS: readonly string[] = ["id", "data", "created", "request_type"];

function isNonBlankString(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/**
 * Exact field membership: every required field present, and no field outside
 * the required and optional lists. `JSON.parse` makes `__proto__` an ordinary
 * own key, so a record trying to smuggle one is refused here as an unknown
 * field rather than reaching a prototype.
 */
function hasExactFields(
  record: Record<string, unknown>,
  required: readonly string[],
  optional: readonly string[],
): boolean {
  for (const field of required) {
    if (!Object.prototype.hasOwnProperty.call(record, field)) {
      return false;
    }
  }
  for (const key of Object.keys(record)) {
    if (!required.includes(key) && !optional.includes(key)) {
      return false;
    }
  }
  return true;
}

/**
 * Whether a value is exactly `{}` as this application sends it.
 *
 * `extraTokenParams` is spread straight into the token request body, so any key
 * surviving here is a parameter this client did not choose: a `code_verifier`
 * that would replace the PKCE proof, a `redirect_uri` that would change the
 * bound address, a `client_secret`, a `scope`. Requiring emptiness refuses all
 * of them by construction rather than by naming the dangerous ones.
 *
 * `JSON.parse` makes a `"__proto__"` key an ordinary own property rather than a
 * prototype mutation, so it is caught by the same key count; the prototype and
 * symbol checks state the remaining shapes explicitly instead of relying on
 * `JSON.parse` never producing them.
 */
function isEmptyPlainObject(value: unknown): boolean {
  if (!isPlainObject(value)) {
    return false;
  }
  if (Object.getPrototypeOf(value) !== Object.prototype) {
    return false;
  }
  return (
    Object.getOwnPropertyNames(value).length === 0 &&
    Object.getOwnPropertySymbols(value).length === 0
  );
}

function isApprovedLoginRecord(record: Record<string, unknown>): boolean {
  if (!hasExactFields(record, LOGIN_FIELDS, [])) {
    return false;
  }
  if (!isEmptyPlainObject(record.extraTokenParams)) {
    return false;
  }
  if (
    !isNonBlankString(record.nonce) ||
    !isNonBlankString(record.code_verifier) ||
    !isNonBlankString(record.authority) ||
    !isNonBlankString(record.client_id) ||
    !isNonBlankString(record.redirect_uri) ||
    !isNonBlankString(record.scope)
  ) {
    return false;
  }
  const data = record.data;
  if (!isPlainObject(data)) {
    return false;
  }
  const dataKeys = Object.keys(data);
  return dataKeys.length === 1 && dataKeys[0] === "returnTo" && typeof data.returnTo === "string";
}

function isApprovedLogoutRecord(record: Record<string, unknown>): boolean {
  if (!hasExactFields(record, LOGOUT_FIELDS, [])) {
    return false;
  }
  const data = record.data;
  if (!isPlainObject(data)) {
    return false;
  }
  const dataKeys = Object.keys(data);
  return (
    dataKeys.length === 1 && dataKeys[0] === "kind" && data.kind === LOGOUT_TRANSACTION_DATA.kind
  );
}

/**
 * Whether a stored transaction record is one this application actually wrote,
 * for the key it is stored under.
 *
 * The two flows are validated apart rather than through one relaxed rule. A
 * sign-in record keeps the whole nonce and PKCE contract; a logout record has a
 * schema of its own and is refused outright if it carries a nonce, a verifier
 * or any other sign-in field. Neither can pass as the other, an unparseable
 * record is refused, and so is a record whose `id` disagrees with its key —
 * which is the same comparison the library later makes against the `state` in
 * the address bar, made here before the record can be handed back.
 */
export function isApprovedTransactionRecord(key: string, value: string): boolean {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value);
  } catch {
    return false;
  }
  if (!isPlainObject(parsed)) {
    return false;
  }
  if (!isNonBlankString(parsed.id) || parsed.id !== key) {
    return false;
  }
  if (typeof parsed.created !== "number" || !Number.isFinite(parsed.created) || parsed.created <= 0) {
    return false;
  }
  if (parsed.request_type === OIDC_LOGIN_REQUEST_TYPE) {
    return isApprovedLoginRecord(parsed);
  }
  if (parsed.request_type === OIDC_LOGOUT_REQUEST_TYPE) {
    return isApprovedLogoutRecord(parsed);
  }
  // A popup, silent or unknown request type belongs to a flow this client does
  // not run at all.
  return false;
}
