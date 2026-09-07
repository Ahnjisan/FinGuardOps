export type AuthErrorKind = "configuration" | "sign-in" | "callback" | "sign-out";

const SAFE_AUTH_ERROR_MESSAGES: Record<AuthErrorKind, string> = {
  configuration: "Authentication is unavailable right now. Please contact an administrator.",
  "sign-in": "Unable to start sign-in right now. Please try again.",
  callback: "Sign-in could not be completed. Please try signing in again.",
  "sign-out": "Sign-out could not be completed. You are signed out of this browser.",
};

export function safeAuthErrorMessage(kind: AuthErrorKind): string {
  return SAFE_AUTH_ERROR_MESSAGES[kind];
}

/**
 * The single error the auth boundary is allowed to propagate. It deliberately
 * carries no provider payload: no authorization code, state, nonce, verifier,
 * provider message, inner error or stack can travel with it.
 */
export class AuthCallbackError extends Error {
  constructor() {
    super("Sign-in could not be completed.");
    this.name = "AuthCallbackError";
  }
}

export class AuthSignInError extends Error {
  constructor() {
    super("Sign-in could not be started.");
    this.name = "AuthSignInError";
  }
}

/**
 * The single failure remote logout is allowed to propagate.
 *
 * Like the other two it carries no provider payload: no ID token, no state, no
 * end-session URL, no provider error code or description, no inner error and no
 * stack. It says only that the sign-out did not complete, which is all the UI
 * is allowed to show and all a caller is allowed to act on.
 *
 * It never means "you are still signed in". The local session, its ownership
 * and its deadline are dropped before any remote work starts, and this error
 * does not put them back.
 */
export class AuthSignOutError extends Error {
  constructor() {
    super("Sign-out could not be completed.");
    this.name = "AuthSignOutError";
  }
}
