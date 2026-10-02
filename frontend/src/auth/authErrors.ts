export type AuthErrorKind = "configuration" | "sign-in" | "callback" | "sign-out";

const SAFE_AUTH_ERROR_MESSAGES: Record<AuthErrorKind, string> = {
  configuration: "인증 서비스를 사용할 수 없습니다. 관리자에게 문의하세요.",
  "sign-in": "로그인을 시작할 수 없습니다. 다시 시도하세요.",
  callback: "로그인을 완료할 수 없습니다. 다시 로그인하세요.",
  "sign-out": "로그아웃을 완료할 수 없지만 이 브라우저의 세션은 종료되었습니다.",
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
    super("로그인을 완료할 수 없습니다.");
    this.name = "AuthCallbackError";
  }
}

export class AuthSignInError extends Error {
  constructor() {
    super("로그인을 시작할 수 없습니다.");
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
    super("로그아웃을 완료할 수 없습니다.");
    this.name = "AuthSignOutError";
  }
}
