import { InMemoryWebStorage, UserManager, WebStorageStateStore } from "oidc-client-ts";
import type { UserManagerSettings } from "oidc-client-ts";
import { findApprovedBackendRequest } from "../api/backendEndpoints";
import { getAuthEnv, getEnv, type AuthEnv } from "../config/env";
import type {
  AuthorizedRequest,
  AuthSession,
  CompleteSignInResult,
  CredentialAuthClient,
  InitializeResult,
} from "./authClient";
import { AuthCallbackError, AuthSignInError } from "./authErrors";
import { CALLBACK_PATH } from "./callbackUrl";
import {
  acquireTransactionStorage,
  clearAuthTransactionState,
  OIDC_TRANSACTION_STORE_PREFIX,
  OIDC_USER_STORE_PREFIX,
} from "./transactionStorage";

/**
 * Hard upper bound on a browser session, independent of what the Authorization
 * Server puts in expires_at. There is no silent renew and no refresh token, so
 * this is the whole session rather than a refresh interval.
 */
export const SESSION_HARD_DEADLINE_MS = 15 * 60 * 1000;

/** Minimal structural view of the library, so tests can drive the adapter. */
export interface OidcUserLike {
  readonly profile: { readonly sub: string; readonly name?: string };
  readonly access_token?: string;
  readonly expires_at?: number;
  readonly state?: unknown;
}

export interface UserManagerLike {
  signinRedirect(args: { state: unknown }): Promise<void>;
  signinRedirectCallback(url: string): Promise<OidcUserLike>;
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
    stateStore: new WebStorageStateStore({
      store: transactionStorage,
      prefix: OIDC_TRANSACTION_STORE_PREFIX,
    }),
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

function toAuthSession(user: OidcUserLike): AuthSession {
  const displayName = typeof user.profile.name === "string" ? user.profile.name : undefined;
  return { subject: user.profile.sub, displayName };
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
): CredentialAuthClient {
  const isCallbackRoute = options.isCallbackRoute ?? defaultIsCallbackRoute;
  const listeners = new Set<() => void>();
  let runtime: AuthRuntime | undefined;
  let inFlightInitialize: Promise<InitializeResult> | undefined;
  let inFlightTeardown: Promise<void> | undefined;
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
    if (!isCallbackRoute()) {
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
        await current.userManager.signinRedirect({ state: { returnTo } });
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

      // Protocol validation passed, but the session is published only once the
      // one-time transaction record is actually gone. If it cannot be removed
      // the sign-in is abandoned rather than completed on replayable state.
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

      const session = toAuthSession(user);
      startSession(session, user.expires_at);
      return { session, returnTo: extractReturnTo(user.state) };
    },

    signOut(): Promise<void> {
      invalidateLocally();
      // Returned directly rather than awaited inside an async wrapper, so a
      // caller racing an in-flight expiry teardown observably shares that work.
      return discardRemoteState();
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

let sharedAuthClient: CredentialAuthClient | undefined;

/**
 * Lazily built singleton, so the deadline lives for the whole page load.
 *
 * Building the client touches no Web Storage: the runtime factory is only
 * stored here, and runs on the first real authentication operation.
 */
export function getOidcAuthClient(): CredentialAuthClient {
  if (sharedAuthClient === undefined) {
    sharedAuthClient = createOidcAuthClient(createDefaultAuthRuntime);
  }
  return sharedAuthClient;
}
