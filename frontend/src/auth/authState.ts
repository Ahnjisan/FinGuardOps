import type { AuthSession } from "./authClient";
import type { AuthErrorKind } from "./authErrors";

/**
 * Authentication is a single discriminated union rather than a set of
 * booleans, so "authenticating and authenticated" or "error and authenticated"
 * are not representable.
 */
export type AuthState =
  | { readonly status: "initializing" }
  | { readonly status: "unauthenticated" }
  | { readonly status: "authenticating" }
  | { readonly status: "authenticated"; readonly session: AuthSession }
  | { readonly status: "error"; readonly kind: AuthErrorKind };

export type AuthAction =
  | { readonly type: "init-completed" }
  | { readonly type: "init-restored"; readonly session: AuthSession }
  | { readonly type: "init-failed" }
  | { readonly type: "sign-in-started" }
  | { readonly type: "sign-in-failed" }
  | { readonly type: "callback-started" }
  | { readonly type: "callback-succeeded"; readonly session: AuthSession }
  | { readonly type: "callback-failed" }
  | { readonly type: "signed-out" }
  | { readonly type: "session-invalidated" };

export const initialAuthState: AuthState = { status: "initializing" };

/**
 * Every transition is guarded by the state it is legal from. That is what makes
 * duplicate work harmless rather than merely unlikely: a second
 * `callback-succeeded` (StrictMode replay, a late shared promise) finds the
 * machine already out of `authenticating` and changes nothing, and a late
 * `init-completed` cannot demote a session that a callback already established.
 */
export function authReducer(state: AuthState, action: AuthAction): AuthState {
  switch (action.type) {
    case "init-completed":
      return state.status === "initializing" ? { status: "unauthenticated" } : state;

    case "init-restored":
      return state.status === "initializing"
        ? { status: "authenticated", session: action.session }
        : state;

    case "init-failed":
      return state.status === "initializing" ? { status: "error", kind: "configuration" } : state;

    case "sign-in-started":
      return state.status === "unauthenticated" || state.status === "error"
        ? { status: "authenticating" }
        : state;

    case "sign-in-failed":
      return state.status === "authenticating" ? { status: "error", kind: "sign-in" } : state;

    case "callback-started":
      return state.status === "initializing" ||
        state.status === "unauthenticated" ||
        state.status === "error"
        ? { status: "authenticating" }
        : state;

    case "callback-succeeded":
      return state.status === "authenticating"
        ? { status: "authenticated", session: action.session }
        : state;

    case "callback-failed":
      return state.status === "authenticating" ? { status: "error", kind: "callback" } : state;

    case "signed-out":
      return { status: "unauthenticated" };

    case "session-invalidated":
      return state.status === "authenticated" ? { status: "unauthenticated" } : state;

    default: {
      const exhaustiveCheck: never = action;
      void exhaustiveCheck;
      return state;
    }
  }
}
