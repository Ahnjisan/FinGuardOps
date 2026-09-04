/**
 * Port between the application and whatever performs the OIDC protocol work.
 *
 * Tokens never cross this boundary. The adapter keeps the access token, ID
 * token and the raw provider `User` object in its own memory and exposes only
 * the minimum the UI needs, so no React state, context value or DOM node can
 * ever hold a credential.
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
   * Single local invalidation signal. Hard deadline, access token expiry and
   * provider expiry events all converge here. Returns an unsubscribe function.
   */
  onSessionInvalidated(listener: () => void): () => void;
}
