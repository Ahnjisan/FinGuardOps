/**
 * Port between the application and whatever performs the OIDC protocol work.
 *
 * The raw access token, the ID token and the provider `User` object never leave
 * the adapter. It keeps them in its own memory and exposes only the minimum
 * each caller needs, so no React state, context value, error object or DOM node
 * can ever hold a credential.
 *
 * The port is split in two on purpose.
 *
 * `AuthClient` is the public surface: sign in, complete a callback, sign out,
 * observe invalidation. It is what `AuthProvider` publishes to the React tree,
 * and it can obtain no credential at all.
 *
 * `CredentialAuthClient` adds the one capability that can produce an
 * authenticated request. It is never published to the React tree, and it is
 * handed only to the authenticated Backend transport.
 */

export interface AuthSession {
  readonly subject: string;
  readonly displayName?: string;
}

export interface InitializeResult {
  /** In-memory session for this page load. Never restored from any storage. */
  readonly session: AuthSession | null;
}

export interface CompleteSignInResult {
  readonly session: AuthSession;
  /**
   * Untrusted application state echoed back through the sign-in transaction.
   * Callers must pass it through `resolveReturnRoute` before navigating.
   */
  readonly returnTo: unknown;
}

/**
 * The public authentication surface. Everything the UI legitimately needs and
 * nothing that can yield a credential: there is no token accessor, no way to
 * reach the provider `User`, and no way to have an arbitrary request signed.
 */
export interface AuthClient {
  /**
   * Prepares the client for this page load. Must not perform any network
   * request: discovery, JWKS and authorize calls happen only when the user
   * starts sign-in or when a callback is processed.
   */
  initialize(): Promise<InitializeResult>;

  /** Starts an Authorization Code + PKCE redirect. */
  signIn(returnTo: string): Promise<void>;

  /** Completes a redirect callback from the captured URL string. */
  completeSignIn(callbackUrl: string): Promise<CompleteSignInResult>;

  /** Local logout: drops the in-memory session immediately. */
  signOut(): Promise<void>;

  /**
   * Single local invalidation signal. Hard deadline, access token expiry,
   * logout and a Backend 401 all converge here. Returns an unsubscribe
   * function.
   */
  onSessionInvalidated(listener: () => void): () => void;
}

/**
 * An approved request that already carries its credential, paired with the
 * only way to act on that request being rejected.
 *
 * The two travel together because they belong to the same session. A 401 is
 * evidence about the session that signed *this* request, and nothing else, so
 * the capability to act on it is issued per request rather than looked up
 * globally afterwards.
 */
export interface AuthorizedRequest {
  /** A new request carrying exactly one `Authorization: Bearer` header. */
  readonly request: Request;
  /**
   * Invalidates the session that authorized this request — but only while that
   * session is still the current one.
   *
   * A 401 for a session that has since been replaced, signed out or expired
   * says nothing about the session in place now, so this is a complete no-op in
   * that case. Calling it repeatedly is safe: the second call finds the session
   * already gone and does nothing.
   */
  readonly invalidateIfCurrent: () => void;
}

/**
 * The internal surface, held only by the authenticated Backend transport.
 *
 * Deliberately not part of `AuthClient`: a value published to the React tree
 * must not be able to sign anything.
 */
export interface CredentialAuthClient extends AuthClient {
  /**
   * Authorizes a request for the current session, or returns `null` when it
   * cannot be authorized.
   *
   * The destination is validated first and independently: only an approved
   * Backend USER endpoint on the configured Backend origin can be signed. A
   * request for anywhere else — an external origin, the public health path, a
   * SERVICE ingestion endpoint, the management listener, an observability
   * service — is refused before the token store is read at all, so this
   * capability cannot be used to send a credential somewhere it does not
   * belong even if a caller reaches it directly.
   *
   * The caller's request object is never given an Authorization header of its
   * own, and the token is never returned, logged or stored. `null` is an
   * ordinary answer, not an error: the caller turns it into a local failure and
   * sends nothing.
   */
  authorizeRequest(request: Request): Promise<AuthorizedRequest | null>;
}
