import { InMemoryWebStorage, UserManager, WebStorageStateStore } from "oidc-client-ts";
import type { StateStore, UserManagerSettings } from "oidc-client-ts";
import { findApprovedBackendRequest } from "../api/backendEndpoints";
import { EnvConfigError, getAuthEnv, getEnv, type AuthEnv } from "../config/env";
import type {
  AuthorizedRequest,
  AuthSession,
  CompleteSignInResult,
  CredentialAuthClient,
  InitializeResult,
  SignOutCallbackClient,
} from "./authClient";
import { AuthCallbackError, AuthSignInError, AuthSignOutError } from "./authErrors";
import { CALLBACK_PATH } from "./callbackUrl";
import { LOGOUT_CALLBACK_PATH } from "./logoutCallbackUrl";
import { resolveUserRoles, type NonEmptyUserRoles } from "./userRoles";
import {
  acquireTransactionStorage,
  clearAuthTransactionState,
  isApprovedTransactionRecord,
  LOGOUT_TRANSACTION_DATA,
  OIDC_TRANSACTION_STORE_PREFIX,
  OIDC_USER_STORE_PREFIX,
} from "./transactionStorage";

/**
 * Hard upper bound on a browser session, independent of what the Authorization
 * Server puts in expires_at. There is no silent renew and no refresh token, so
 * this is the whole session rather than a refresh interval.
 */
export const SESSION_HARD_DEADLINE_MS = 15 * 60 * 1000;
const LOGIN_NONCE_BYTES = 32;
const TRANSACTION_STATE_REJECTED = "OIDC transaction state rejected.";

/**
 * The end-session path this deployment's Authorization Server publishes, stated
 * here rather than taken from discovery.
 *
 * The destination of a logout redirect is the one thing a tampered or swapped
 * discovery document could otherwise move, and the browser would follow it
 * carrying an `id_token_hint`. Seeding the endpoint from the configured issuer
 * makes the discovery document unable to decide where the user is sent.
 */
const END_SESSION_PATH = "/protocol/openid-connect/logout";

/**
 * RFC 7519 compact serialization, anchored over a whole ID token.
 *
 * This is a shape check and nothing more. The token's signature, issuer,
 * audience and nonce were verified by the OIDC client during the sign-in
 * callback, and ADR-011 makes that validated provenance the single source of
 * truth; re-fetching a JWK set to check it a second time here would add a
 * network round trip and a second, divergent verifier without adding a decision.
 */
const COMPACT_JWT = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;

/** Minimal structural view of the library, so tests can drive the adapter. */
export interface OidcUserLike {
  readonly profile: {
    readonly sub: string;
    readonly name?: string;
    /**
     * Both typed `unknown` for the same reason as `refresh_token` below: the
     * only questions this adapter may ask are "is this exactly the string
     * `USER`?" and "is this an array of known USER role names?". Declaring them
     * as `string` and `string[]` would leave a provider that answers `null`, a
     * number, an object or a bare string with no declared case to refuse, and
     * the fail-closed path would be unreachable from the type.
     */
    readonly principal_type?: unknown;
    readonly roles?: unknown;
  };
  readonly access_token?: string;
  /**
   * Typed `unknown` rather than the library's `string`, because the only
   * question this adapter is allowed to ask is whether anything is there at
   * all. A provider that returns `null`, a number or an object must reach the
   * same fail-closed answer as one that returns a token, and a `string` here
   * would leave exactly those runtime shapes with no declared case to refuse.
   */
  readonly refresh_token?: unknown;
  /**
   * Typed `unknown` for the same reason as `refresh_token`: the only questions
   * this adapter may ask are whether anything is there at all and whether it
   * has the compact JWT shape. A `string` here would leave a provider that
   * answers `null`, a number or an object with no declared case to refuse.
   *
   * The value is never read out of this property into a name, a copy, a log or
   * a logout argument. The library that verified it during the sign-in callback
   * is the only thing that ever puts it on the wire, as `id_token_hint`.
   */
  readonly id_token?: unknown;
  readonly expires_at?: number;
  readonly state?: unknown;
}

export interface UserManagerLike {
  signinRedirect(args: { state: unknown; nonce: string }): Promise<void>;
  signinRedirectCallback(url: string): Promise<OidcUserLike>;
  /**
   * Starts the end-session redirect.
   *
   * There is deliberately no `id_token_hint` in the argument type. The library
   * reads the ID token it validated straight out of the memory user store,
   * which is what keeps the raw value out of this application entirely — no
   * expression here binds it, and nothing can pass a different one.
   *
   * `redirectMethod` is required rather than defaulted, so a regression to the
   * library's `assign` fails to compile as well as failing its test.
   */
  signoutRedirect(args: { state: unknown; redirectMethod: "replace" }): Promise<void>;
  /** Consumes the end-session response, and the logout transaction it names. */
  signoutRedirectCallback(url: string): Promise<unknown>;
  removeUser(): Promise<void>;
  /**
   * Reads the in-memory user store. This is the single point in the whole
   * application where an access token is observed, and the value never leaves
   * `authorizeRequest`.
   */
  getUser(): Promise<OidcUserLike | null>;
  readonly events: {
    addAccessTokenExpired(callback: () => void): () => void;
  };
}

function assertApprovedTransaction(key: string, value: string | null): void {
  if (value !== null && !isApprovedTransactionRecord(key, value)) {
    throw new Error(TRANSACTION_STATE_REJECTED);
  }
}

/**
 * Enforces the transaction-record schema at every oidc-client-ts state-store
 * boundary.
 *
 * Sign-in and logout are validated as two separate schemas rather than as one
 * relaxed rule that both happen to satisfy. A sign-in record keeps the nonce
 * and PKCE contract it has always had; a logout record has no nonce and no
 * verifier and is refused if one is injected, and neither can pass as the
 * other. A record for a popup, silent or unknown request type is refused
 * outright, because this client runs none of those flows.
 *
 * Missing records stay missing, so the library can still report an unknown or
 * replayed state; every record that does exist must match its schema and its
 * own key before it can be written, read, or returned.
 *
 * Removal reads and validates before it consumes. A one-time removal is
 * destructive, so a record that fails the schema must not be destroyed on the
 * way to being refused: removing first would let a single malformed or injected
 * record delete the transaction a legitimate flow still needs, and would leave
 * nothing to inspect afterwards. The record is therefore previewed with `get`,
 * validated whole, and only then removed — and the removed value has to be
 * byte-identical to the previewed one, or something rewrote the record between
 * the two reads and the consume fails closed.
 *
 * The rejection carries the same fixed message in every case, so no stored
 * value reaches a caller through an error.
 */
export function validatingStateStore(store: StateStore): StateStore {
  return {
    async set(key: string, value: string): Promise<void> {
      assertApprovedTransaction(key, value);
      await store.set(key, value);
    },
    async get(key: string): Promise<string | null> {
      const value = await store.get(key);
      assertApprovedTransaction(key, value);
      return value;
    },
    async remove(key: string): Promise<string | null> {
      const previewed = await store.get(key);
      if (previewed === null) {
        // Nothing to consume, and nothing to destroy. The library reports this
        // as an unknown or already-replayed state.
        return null;
      }
      assertApprovedTransaction(key, previewed);
      const removed = await store.remove(key);
      if (removed !== previewed) {
        throw new Error(TRANSACTION_STATE_REJECTED);
      }
      return removed;
    },
    getAllKeys(): Promise<string[]> {
      return store.getAllKeys();
    },
  };
}

/**
 * Pins the end-session destination to the configured issuer.
 *
 * The operator's issuer string is used exactly as written — no normalization
 * beyond dropping a single trailing slash before the fixed path is appended, so
 * a default port, a case difference or a trailing-slash difference stays the
 * operator's decision rather than something this function rewrites. The result
 * is then parsed back and refused unless it is still an http(s) URL with no
 * query, no fragment and no userinfo, and with the exact end-session path.
 */
export function resolveEndSessionEndpoint(authority: string): string {
  const base = authority.endsWith("/") ? authority.slice(0, -1) : authority;
  const endpoint = `${base}${END_SESSION_PATH}`;
  let parsed: URL;
  try {
    parsed = new URL(endpoint);
  } catch {
    throw new EnvConfigError();
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
    throw new EnvConfigError();
  }
  if (
    parsed.search !== "" ||
    parsed.hash !== "" ||
    parsed.username !== "" ||
    parsed.password !== "" ||
    !parsed.pathname.endsWith(END_SESSION_PATH)
  ) {
    throw new EnvConfigError();
  }
  return endpoint;
}

/**
 * The exact address the Authorization Server may return the browser to.
 *
 * It is this document's origin followed by `/` and nothing else. The Keycloak
 * client allowlists that string verbatim, so anything the origin could smuggle
 * in — a query, a fragment, userinfo, a path — is refused here rather than sent
 * to the end-session endpoint.
 */
export function resolvePostLogoutRedirectUri(origin: string): string {
  const candidate = `${origin}${LOGOUT_CALLBACK_PATH}`;
  let parsed: URL;
  try {
    parsed = new URL(candidate);
  } catch {
    throw new EnvConfigError();
  }
  if (
    parsed.href !== candidate ||
    parsed.origin !== origin ||
    parsed.pathname !== LOGOUT_CALLBACK_PATH ||
    parsed.search !== "" ||
    parsed.hash !== "" ||
    parsed.username !== "" ||
    parsed.password !== ""
  ) {
    throw new EnvConfigError();
  }
  return candidate;
}

/** Generates a fresh 256-bit nonce without a fallback PRNG. */
export function generateLoginNonce(): string {
  const bytes = new Uint8Array(LOGIN_NONCE_BYTES);
  globalThis.crypto.getRandomValues(bytes);
  let binary = "";
  for (const byte of bytes) {
    binary += String.fromCharCode(byte);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/u, "");
}

/**
 * Everything an actual protocol operation needs, acquired as one unit.
 *
 * Both halves come from the same act of construction because both fail for the
 * same reason: the UserManager cannot be built without a state store, and the
 * state store cannot be built without reading window.sessionStorage.
 */
export interface AuthRuntime {
  readonly userManager: UserManagerLike;
  readonly storage: Storage;
}

export type AuthRuntimeFactory = () => AuthRuntime;

export interface OidcAuthClientOptions {
  /**
   * Whether this page load is the redirect callback. Injected in tests; in the
   * browser it is the real pathname, which `clearCallbackUrl` preserves.
   */
  readonly isCallbackRoute?: () => boolean;
}

/**
 * The session ends at whichever comes first: the token expiry or a fixed cap
 * measured from sign-in completion. A missing, non-finite or non-numeric
 * expires_at falls back to the cap rather than to "no deadline", and a value
 * already in the past invalidates immediately.
 */
export function resolveSessionDeadline(nowMs: number, expiresAtSeconds?: number): number {
  const hardCap = nowMs + SESSION_HARD_DEADLINE_MS;
  if (typeof expiresAtSeconds !== "number" || !Number.isFinite(expiresAtSeconds)) {
    return hardCap;
  }
  const tokenDeadlineMs = expiresAtSeconds * 1000;
  if (tokenDeadlineMs <= nowMs) {
    return nowMs;
  }
  return Math.min(tokenDeadlineMs, hardCap);
}

/**
 * Every setting the security posture depends on is stated explicitly rather
 * than inherited from a library default, so a future default change cannot
 * silently enable session monitoring, silent renew or a userinfo round trip.
 *
 * The transaction storage is a parameter rather than a window.sessionStorage
 * read: this function must stay callable from a context where that property
 * getter throws, with the failure owned by whoever acquired the storage.
 */
export function createOidcSettings(
  env: AuthEnv,
  transactionStorage: Storage,
  origin: string = window.location.origin,
): UserManagerSettings {
  return {
    authority: env.oidcAuthority,
    client_id: env.oidcClientId,
    redirect_uri: `${origin}${CALLBACK_PATH}`,
    post_logout_redirect_uri: resolvePostLogoutRedirectUri(origin),
    // Merged over the discovery document rather than under it, so no
    // Authorization Server response can move the logout destination.
    metadataSeed: { end_session_endpoint: resolveEndSessionEndpoint(env.oidcAuthority) },
    response_type: "code",
    scope: "openid profile",
    automaticSilentRenew: false,
    monitorSession: false,
    loadUserInfo: false,
    // Tokens live only here, and this store is plain memory: a reload starts
    // from nothing, which is the point.
    userStore: new WebStorageStateStore({
      store: new InMemoryWebStorage(),
      prefix: OIDC_USER_STORE_PREFIX,
    }),
    // Only the transient protocol transaction record survives the redirect.
    stateStore: validatingStateStore(
      new WebStorageStateStore({
        store: transactionStorage,
        prefix: OIDC_TRANSACTION_STORE_PREFIX,
      }),
    ),
  };
}

/**
 * RFC 6750 `b64token`, anchored over a whole raw access token.
 *
 * b64token = 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"="
 *
 * The transport re-checks the finished `Authorization` header, but a header
 * value is not the raw token: `Headers.set` strips leading and trailing
 * whitespace on the way in, so a stored token of `"opaque.token "` would reach
 * that check already normalized and pass as a well-formed credential. Checking
 * the value as it came out of the user store is what makes the grammar a
 * statement about the token the Authorization Server actually issued.
 *
 * The empty string has no body to match, so it is refused by this rule rather
 * than by a separate emptiness test.
 */
const RAW_ACCESS_TOKEN = /^[A-Za-z0-9\-._~+/]+=*$/;

/**
 * Whether a value read from the user store is usable exactly as issued.
 *
 * Nothing is trimmed, rewritten or normalized: leading, trailing or internal
 * whitespace, a tab, a CR, an LF, or a `=` anywhere but the padding is refused
 * as it stands. The value never leaves this function.
 */
function isUsableAccessToken(accessToken: unknown): accessToken is string {
  return typeof accessToken === "string" && RAW_ACCESS_TOKEN.test(accessToken);
}

/**
 * Whether the user the protocol step produced carries a refresh token.
 *
 * This is a public browser client with nowhere to keep a long-lived
 * credential: `scope` omits `offline_access` and `automaticSilentRenew` is off,
 * so a conforming Authorization Server never issues one. One arriving anyway
 * means the response is not the grant this frontend was built against, and the
 * sign-in is abandoned rather than quietly completed on it.
 *
 * Absence is the only acceptable answer, and absence is spelled `undefined`.
 * The empty string, a whitespace-only string, `null` and every runtime
 * non-string are all "present". The value is never bound to a name, trimmed,
 * normalized, matched against a pattern, interpolated or copied: the single
 * `!== undefined` is the whole test, so no expression in this module ever
 * holds the credential. A property whose getter throws is treated as present
 * too, because "cannot tell" is not "absent".
 */
function carriesRefreshToken(user: OidcUserLike): boolean {
  try {
    return user.refresh_token !== undefined;
  } catch {
    return true;
  }
}

/**
 * Whether the memory user store still holds an ID token the library can use as
 * an `id_token_hint`.
 *
 * Presence and compact JWT shape are the whole test, and both are asked of the
 * property in place: the value is never bound to a name, sliced, decoded,
 * trimmed, interpolated, logged or returned, so no expression in this module
 * ever holds it. A property whose getter throws is "cannot tell", which is not
 * "present", so it fails closed like an absent one.
 *
 * A `false` answer means no redirect is attempted at all. An end-session
 * request without a hint would either be refused or turn into an interactive
 * confirmation page, and neither is a logout this application can claim to have
 * performed.
 */
function carriesUsableIdToken(user: OidcUserLike): boolean {
  try {
    return typeof user.id_token === "string" && COMPACT_JWT.test(user.id_token);
  } catch {
    return false;
  }
}

/**
 * Drops a user that protocol validation accepted but this adapter refuses to
 * publish.
 *
 * Both steps run exactly once and each owns its failure: a rejecting
 * `removeUser()` must not skip the transaction sweep, and neither failure may
 * become the caller's error or turn the refusal back into a sign-in. Nothing
 * was published, so there is no session, deadline or subscriber to unwind -
 * only the library's own local state to discard.
 */
async function discardRejectedUser(runtime: AuthRuntime): Promise<void> {
  try {
    await runtime.userManager.removeUser();
  } catch {
    // Nothing was published, so there is nothing further to surface.
  }
  try {
    clearAuthTransactionState(runtime.storage);
  } catch {
    // Nothing was published, so there is nothing further to surface.
  }
}

/**
 * Turns a user the OIDC client has already validated into the session the rest
 * of the application is allowed to see, or refuses it.
 *
 * This is the single place where `principal_type` and `roles` are read, and the
 * only values that survive it are the subject, an optional display name and a
 * frozen list of known USER role names. The raw claim values are not copied,
 * not re-exposed and not carried in the refusal, and no token is decoded here:
 * `profile` is the ID token the library verified, which ADR-011 section 2.9
 * makes the one permitted source for role display.
 *
 * `null` means "do not publish a session". Every claim-level defect converges
 * on it: a `principal_type` that is not exactly `USER` (a SERVICE token, a
 * missing claim, different casing), a `roles` claim that is not an array or is
 * an empty one, an unknown or repeated role name, and a `profile` whose getters
 * throw. The caller discards the library's user and fails the sign-in, so a
 * rejected user never reaches a subscriber, React state or the DOM.
 */
function toAuthSession(user: OidcUserLike): AuthSession | null {
  let subject: string;
  let displayName: string | undefined;
  let roles: NonEmptyUserRoles | null;
  try {
    const profile = user.profile;
    if (typeof profile.sub !== "string" || profile.sub === "") {
      return null;
    }
    subject = profile.sub;
    displayName = typeof profile.name === "string" ? profile.name : undefined;
    roles = resolveUserRoles(profile.principal_type, profile.roles);
  } catch {
    // A claim that cannot be read is not a claim that is absent.
    return null;
  }
  if (roles === null) {
    return null;
  }
  return { subject, displayName, roles };
}

function extractReturnTo(state: unknown): unknown {
  if (typeof state === "object" && state !== null && "returnTo" in state) {
    return (state as { returnTo: unknown }).returnTo;
  }
  return undefined;
}

function defaultIsCallbackRoute(): boolean {
  return window.location.pathname === CALLBACK_PATH;
}

/**
 * Builds the protocol runtime, reading window.sessionStorage for the first and
 * only time. Nothing on the import, factory or first-render path calls this: it
 * runs inside an actual authentication operation, where a failure has a fixed
 * authentication error to converge on.
 */
export function createDefaultAuthRuntime(): AuthRuntime {
  const storage = acquireTransactionStorage();
  const userManager = new UserManager(createOidcSettings(getAuthEnv(), storage));
  return { userManager, storage };
}

export function createOidcAuthClient(
  createRuntime: AuthRuntimeFactory,
  options: OidcAuthClientOptions = {},
): CredentialAuthClient & SignOutCallbackClient {
  const isCallbackRoute = options.isCallbackRoute ?? defaultIsCallbackRoute;
  const listeners = new Set<() => void>();
  let runtime: AuthRuntime | undefined;
  let inFlightInitialize: Promise<InitializeResult> | undefined;
  let inFlightTeardown: Promise<void> | undefined;
  /**
   * The logout attempt currently in flight, and the session generation it
   * belongs to.
   *
   * Sharing is scoped to both facts, not just to "a logout happened". Two
   * clicks on the same session join one flight, so there is one redirect, one
   * teardown and one notification for that session. But the entry is released
   * the moment the attempt settles, and a flight belonging to an earlier
   * generation is never handed to a later one: after a sign-out fails and the
   * user signs in again, the new session's logout is a new local invalidation
   * and a new `signoutRedirect()`, not the previous session's answer replayed.
   */
  interface SignOutFlight {
    readonly generation: number;
    readonly promise: Promise<void>;
  }
  let signOutFlight: SignOutFlight | undefined;
  /**
   * Monotonic count of sessions published on this client.
   *
   * Deliberately separate from `sessionOwnership`, which invalidation clears:
   * this has to stay readable *after* a session ends, because that is exactly
   * when a logout flight needs to say which session it belonged to.
   */
  let sessionGeneration = 0;
  /**
   * Set the moment a root end-session response is claimed, and cleared by the
   * first initialization that sees it.
   *
   * On the logout callback the record in storage is the one-time transaction
   * the response in the address bar is about to be validated against, exactly
   * as on the sign-in callback route. An initialization that swept it first
   * would destroy the value the callback needs, so it does not sweep — even if
   * a caller were to start it while the callback is still in flight.
   */
  let signOutCallbackClaimed = false;
  let activeSession: AuthSession | null = null;
  /**
   * Opaque identity of the currently published session.
   *
   * A fresh frozen object per session, deliberately carrying nothing: not a
   * counter, not the subject, and above all not the token. Comparison is by
   * reference, so "is this still the same session?" cannot be satisfied by a
   * value that merely looks equal, and holding one reveals nothing. It never
   * reaches React state, context, the DOM or a log.
   */
  let sessionOwnership: object | null = null;
  let deadlineTimer: ReturnType<typeof setTimeout> | undefined;

  function clearDeadlineTimer(): void {
    if (deadlineTimer !== undefined) {
      clearTimeout(deadlineTimer);
      deadlineTimer = undefined;
    }
  }

  /**
   * The synchronous half of invalidation. The UI must never wait on storage or
   * on the library to stop showing someone as signed in, so the local session
   * reference, the timer and the notification are all dropped in one turn.
   * Returns false when there was nothing left to invalidate, which is what
   * makes a second expiry event or a racing deadline a no-op.
   */
  function invalidateLocally(): boolean {
    clearDeadlineTimer();
    if (activeSession === null) {
      return false;
    }
    activeSession = null;
    sessionOwnership = null;
    for (const listener of [...listeners]) {
      listener();
    }
    return true;
  }

  /**
   * Acquires the runtime once, lazily, and caches it only after every step has
   * succeeded. A storage getter that throws, or a UserManager constructor that
   * does, leaves the cache empty rather than half-populated, so the next
   * operation retries from scratch instead of inheriting a broken object.
   */
  function getRuntime(): AuthRuntime {
    if (runtime !== undefined) {
      return runtime;
    }
    const created = createRuntime();
    // Registered with the runtime rather than with the client, so token expiry
    // converges on the same local invalidation boundary as the deadline and as
    // logout, and so exactly one listener exists per runtime.
    created.userManager.events.addAccessTokenExpired(() => {
      handleInvalidation();
    });
    runtime = created;
    return created;
  }

  /** Best-effort teardown. Never rejects, so no caller can leak a rejection. */
  async function runTeardown(): Promise<void> {
    let current: AuthRuntime;
    try {
      current = getRuntime();
    } catch {
      // Storage was never usable, so nothing was ever written to tear down.
      return;
    }
    try {
      await current.userManager.removeUser();
    } catch {
      // Already unauthenticated locally; nothing further to surface.
    }
    try {
      clearAuthTransactionState(current.storage);
    } catch {
      // Already unauthenticated locally; nothing further to surface.
    }
  }

  /**
   * The single in-flight teardown boundary shared by the hard deadline, token
   * expiry and local logout. Without it, a logout issued while an expiry
   * teardown is still pending would run removeUser() and the transaction sweep
   * a second time. Callers get the same promise, and the entry is released on
   * settle so a genuinely later teardown still runs.
   */
  function discardRemoteState(): Promise<void> {
    if (inFlightTeardown !== undefined) {
      return inFlightTeardown;
    }
    const teardown = runTeardown().finally(() => {
      if (inFlightTeardown === teardown) {
        inFlightTeardown = undefined;
      }
    });
    inFlightTeardown = teardown;
    return teardown;
  }

  function handleInvalidation(): void {
    if (invalidateLocally()) {
      void discardRemoteState();
    }
  }

  /**
   * Waits until no teardown belonging to an earlier session is still running.
   *
   * A teardown ends with a sweep of the whole transaction prefix. Anything that
   * is about to *write* to that prefix — a new sign-in transaction, or the user
   * record a callback installs — must therefore run after it, or the old
   * session's cleanup deletes the new session's state. Waiting here is what
   * orders the two; there is no generation stamp to keep in sync.
   *
   * The promise is re-read after each await because a teardown that settles
   * releases its own entry, and an invalidation racing this call can install a
   * new one. Each iteration awaits a different promise and a teardown only
   * starts from an invalidation of a live session, so the loop is bounded
   * rather than a spin. `runTeardown` never rejects, so nothing propagates out.
   */
  async function awaitPendingTeardown(): Promise<void> {
    let awaited: Promise<void> | undefined;
    while (inFlightTeardown !== undefined && inFlightTeardown !== awaited) {
      awaited = inFlightTeardown;
      await awaited;
    }
  }

  function startSession(session: AuthSession, expiresAtSeconds?: number): void {
    // A replacement session must not leave the previous deadline armed.
    clearDeadlineTimer();
    sessionGeneration += 1;
    activeSession = session;
    // A new identity for every published session, so a callback issued against
    // the previous one can never be mistaken for a current one.
    sessionOwnership = Object.freeze({});
    const now = Date.now();
    const deadline = resolveSessionDeadline(now, expiresAtSeconds);
    deadlineTimer = setTimeout(() => {
      deadlineTimer = undefined;
      handleInvalidation();
    }, Math.max(0, deadline - now));
  }

  async function runInitialize(): Promise<InitializeResult> {
    // On the callback route the record in storage is the state, nonce and PKCE
    // verifier that the response now in the address bar is about to be
    // validated against. Nothing is swept, and storage is not even read: the
    // adapter takes over cleanup once the protocol step has run.
    const claimedSignOutCallback = signOutCallbackClaimed;
    signOutCallbackClaimed = false;
    if (!isCallbackRoute() && !claimedSignOutCallback) {
      // Anywhere else, a record left behind by an abandoned redirect is removed
      // here. The sweep is synchronous and owns its own failure rather than
      // delegating to a library helper that does not await its internal
      // removals, so a failure becomes this rejection, observed by the provider
      // as an initialization error, instead of escaping as an unhandled one.
      const { storage } = getRuntime();
      clearAuthTransactionState(storage);
    }
    return { session: activeSession };
  }

  /**
   * The remote half of one logout attempt.
   *
   * Everything local is already gone by the time this starts. What remains is
   * to hand the browser to the Authorization Server's end-session endpoint, and
   * the only way to do that honestly is with the ID token the library validated
   * during the sign-in callback. So the store is consulted first, and the whole
   * thing fails closed if there is no usable one: an ID token that is missing,
   * of the wrong runtime type, not in compact JWT form, or unreadable because
   * its getter throws, all end here rather than in a redirect that would leave
   * the Authorization Server session standing.
   *
   * Every failure ends the same way: a best-effort removal of the library's
   * user record and of the transaction records this application owns, then the
   * fixed `AuthSignOutError`. No provider message, no state, no URL and no
   * token travels with it, and nothing puts the local session back.
   */
  async function runSignOut(): Promise<void> {
    let current: AuthRuntime;
    try {
      current = getRuntime();
    } catch {
      // Storage was never usable, so nothing was ever written to tear down.
      throw new AuthSignOutError();
    }

    let user: OidcUserLike | null;
    try {
      user = await current.userManager.getUser();
    } catch {
      await discardRemoteState();
      throw new AuthSignOutError();
    }
    if (user === null || user === undefined || !carriesUsableIdToken(user)) {
      await discardRemoteState();
      throw new AuthSignOutError();
    }

    try {
      // No `id_token_hint` is passed. The library takes the token it verified
      // from the memory user store itself, removes the user, writes the
      // one-time logout transaction and navigates to the seeded end-session
      // endpoint with the exact allowlisted post-logout redirect URI.
      //
      // `replace` rather than the library's default `assign`: the end-session
      // URL carries the ID token hint and the logout state, and an `assign`
      // would leave that address in session history where Back could re-issue
      // it. Replacing drops the signed-in page from history in the same step.
      await current.userManager.signoutRedirect({
        state: LOGOUT_TRANSACTION_DATA,
        redirectMethod: "replace",
      });
    } catch {
      await discardRemoteState();
      throw new AuthSignOutError();
    }
  }

  return {
    initialize(): Promise<InitializeResult> {
      // One shared in-flight promise instead of an "already ran" flag: a
      // StrictMode replay joins the same work, and the entry is released on
      // settle so a genuine later initialization still runs.
      if (inFlightInitialize === undefined) {
        inFlightInitialize = runInitialize().finally(() => {
          inFlightInitialize = undefined;
        });
      }
      return inFlightInitialize;
    },

    async signIn(returnTo: string): Promise<void> {
      // Fail closed: if the runtime cannot be acquired, or prior transaction
      // records cannot be removed, we do not start a redirect that would leave
      // ambiguous state behind.
      let current: AuthRuntime;
      try {
        current = getRuntime();
      } catch {
        throw new AuthSignInError();
      }

      // Before creating anything: let a previous session's teardown finish its
      // removeUser() and its prefix sweep. Redirecting first would have the old
      // sweep land on the transaction this sign-in is about to write, and the
      // state, nonce and PKCE verifier would be gone by the time the callback
      // needs them. A failed teardown still settles, so this never blocks the
      // user out of signing in.
      await awaitPendingTeardown();

      try {
        clearAuthTransactionState(current.storage);
      } catch {
        throw new AuthSignInError();
      }
      try {
        const nonce = generateLoginNonce();
        await current.userManager.signinRedirect({ state: { returnTo }, nonce });
      } catch {
        throw new AuthSignInError();
      }
    },

    async completeSignIn(callbackUrl: string): Promise<CompleteSignInResult> {
      let current: AuthRuntime;
      try {
        current = getRuntime();
      } catch {
        throw new AuthCallbackError();
      }

      // Same ordering as sign-in, for the same reason: a previous session's
      // teardown would otherwise remove the user record this callback installs
      // and sweep the transaction it is validating against. The token exchange
      // has not started yet at this point, and the callback URL was captured
      // and cleared by the page long before, so waiting costs nothing.
      await awaitPendingTeardown();

      let user: OidcUserLike;
      try {
        user = await current.userManager.signinRedirectCallback(callbackUrl);
      } catch {
        // This adapter owns transaction cleanup for every library outcome.
        try {
          clearAuthTransactionState(current.storage);
        } catch {
          // Reported as the same fixed callback failure either way.
        }
        throw new AuthCallbackError();
      }

      // Protocol validation passed; the credential shape has not been judged
      // yet. This runs on the user as returned, before `toAuthSession`, before
      // `startSession` and therefore before any subscriber, React state or
      // Backend request can observe a session - a user-loaded event raised
      // inside the call above changes nothing, because publication happens
      // only at the end of this method.
      if (carriesRefreshToken(user)) {
        await discardRejectedUser(current);
        throw new AuthCallbackError();
      }

      // The claim contract, judged in the same place and the same way as the
      // credential shape: before the transaction record is cleared, before
      // `startSession`, and therefore before any subscriber, React state or
      // Backend request can observe a session. A user whose `principal_type` is
      // not exactly `USER`, or whose `roles` claim is malformed or empty, is
      // discarded here rather than published with a salvaged or empty role set.
      const session = toAuthSession(user);
      if (session === null) {
        await discardRejectedUser(current);
        throw new AuthCallbackError();
      }

      // The session is published only once the one-time transaction record is
      // actually gone. If it cannot be removed the sign-in is abandoned rather
      // than completed on replayable state.
      try {
        clearAuthTransactionState(current.storage);
      } catch {
        try {
          await current.userManager.removeUser();
        } catch {
          // Nothing was published, so there is nothing further to roll back.
        }
        throw new AuthCallbackError();
      }

      startSession(session, user.expires_at);
      return { session, returnTo: extractReturnTo(user.state) };
    },

    signOut(): Promise<void> {
      // Only a flight that is still running, and only one belonging to the
      // session in place now, answers for this call.
      if (signOutFlight !== undefined && signOutFlight.generation === sessionGeneration) {
        return signOutFlight.promise;
      }
      // Synchronous and first, on every call that starts an attempt: the
      // session reference, its ownership, its deadline timer and the one
      // subscriber notification are all gone before any await, so nothing can
      // observe an authenticated session while the redirect is being prepared.
      // The library's copy of the user stays put until it has read the ID token
      // it needs for the hint.
      invalidateLocally();
      const generation = sessionGeneration;
      // Released on settle, identity-checked so a late continuation of an
      // earlier attempt cannot clear the entry a newer one installed.
      const promise = runSignOut().finally(() => {
        if (signOutFlight?.promise === promise) {
          signOutFlight = undefined;
        }
      });
      signOutFlight = { generation, promise };
      return promise;
    },

    async completeSignOut(callbackUrl: string): Promise<void> {
      // Synchronous, before the first await: an initialization racing this call
      // must not sweep the transaction the response is about to consume.
      signOutCallbackClaimed = true;
      let current: AuthRuntime;
      try {
        current = getRuntime();
      } catch {
        throw new AuthSignOutError();
      }
      try {
        // The library removes the one transaction record the response names and
        // validates the response against it, which is what makes the state a
        // one-time value. Nothing else happens here: no user is removed, no
        // session is invalidated and no subscriber is told, so a response
        // belonging to an earlier page load cannot disturb the session in place
        // now. A replayed or unknown state finds no record and lands below.
        await current.userManager.signoutRedirectCallback(callbackUrl);
      } catch {
        throw new AuthSignOutError();
      }
    },

    /**
     * The only place an access token is ever read.
     *
     * The destination is checked first, and on its own. This capability is what
     * actually holds the credential, so it must be able to refuse a request
     * that is not an approved Backend USER endpoint without relying on its
     * caller having checked already. A request for an external origin, the
     * public health path, a SERVICE ingestion endpoint, the management
     * listener or an observability service is rejected here, before the
     * runtime is touched and before the user store is read: no token lookup,
     * no Authorization header, no returned request.
     *
     * After that, every remaining failure reason collapses into `null`: no
     * published session, an unusable runtime, a missing token, a raw token
     * outside the RFC 6750 grammar, a token past its expiry, or a memory user
     * whose subject disagrees with the session the UI is showing. The caller
     * learns "this request cannot be authorized" and nothing else - no token,
     * no claim, no provider error, no URL.
     *
     * Session identity is captured before the store read and re-checked both
     * after it and immediately before returning, because a hard deadline, a
     * token-expiry event, a logout or a whole new sign-in can land while that
     * await is pending. Without those checks a request could be signed by a
     * session that ended mid-flight.
     */
    async authorizeRequest(request: Request): Promise<AuthorizedRequest | null> {
      let apiBaseUrl: string;
      try {
        apiBaseUrl = getEnv().apiBaseUrl;
      } catch {
        return null;
      }
      if (findApprovedBackendRequest(apiBaseUrl, request.method, request.url) === undefined) {
        return null;
      }

      const ownership = sessionOwnership;
      if (ownership === null || activeSession === null) {
        return null;
      }
      const startingSession = activeSession;

      let current: AuthRuntime;
      try {
        current = getRuntime();
      } catch {
        return null;
      }

      let user: OidcUserLike | null;
      try {
        user = await current.userManager.getUser();
      } catch {
        return null;
      }
      if (user === null || user === undefined) {
        return null;
      }

      if (sessionOwnership !== ownership) {
        return null;
      }
      if (user.profile.sub !== startingSession.subject) {
        return null;
      }

      // A missing or non-finite expires_at is not treated as "expired": the
      // session's own hard deadline already bounds that case, and refusing here
      // would lock out a provider that simply omits the field.
      if (
        typeof user.expires_at === "number" &&
        Number.isFinite(user.expires_at) &&
        user.expires_at * 1000 <= Date.now()
      ) {
        return null;
      }

      // The raw token, checked before it can become a header value, so a token
      // the platform would have quietly trimmed into shape never gets the
      // chance. Anything outside the grammar collapses into the same `null` as
      // a missing one: no header is built, no request is returned and nothing
      // is sent, so this is not a 401 and no session is invalidated.
      const accessToken = user.access_token;
      if (!isUsableAccessToken(accessToken)) {
        return null;
      }

      // Last look before handing out a credential.
      if (sessionOwnership !== ownership) {
        return null;
      }

      // A copy: the caller's request keeps whatever headers it had, and
      // anything it may have called "Authorization" is dropped rather than
      // merged, so exactly one Authorization header exists and this port set it.
      const headers = new Headers(request.headers);
      headers.delete("Authorization");
      headers.set("Authorization", `Bearer ${accessToken}`);

      return {
        request: new Request(request, { headers }),
        invalidateIfCurrent: () => {
          // Scoped to the session that signed the request. A 401 for a session
          // that has since been replaced, signed out or expired is evidence
          // about that session only, so it must not disturb the current one.
          if (sessionOwnership === ownership) {
            handleInvalidation();
          }
        },
      };
    },

    onSessionInvalidated(listener: () => void): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

let sharedAuthClient: (CredentialAuthClient & SignOutCallbackClient) | undefined;

/**
 * Lazily built singleton, so the deadline lives for the whole page load.
 *
 * Building the client touches no Web Storage: the runtime factory is only
 * stored here, and runs on the first real authentication operation.
 */
export function getOidcAuthClient(): CredentialAuthClient & SignOutCallbackClient {
  if (sharedAuthClient === undefined) {
    sharedAuthClient = createOidcAuthClient(createDefaultAuthRuntime);
  }
  return sharedAuthClient;
}
