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
