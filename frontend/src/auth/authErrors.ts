export type AuthErrorKind = "configuration" | "sign-in" | "callback";

const SAFE_AUTH_ERROR_MESSAGES: Record<AuthErrorKind, string> = {
  configuration: "Authentication is unavailable right now. Please contact an administrator.",
  "sign-in": "Unable to start sign-in right now. Please try again.",
  callback: "Sign-in could not be completed. Please try signing in again.",
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
