import { createContext } from "react";
import type { AuthClient, AuthSession } from "./authClient";
import type { AuthState } from "./authState";

export interface AuthContextValue {
  readonly state: AuthState;
  /**
   * The public authentication port, so the callback route can drive protocol
   * completion.
   *
   * `AuthClient` carries no credential capability, and what `AuthProvider`
   * publishes here is an explicitly built facade rather than the adapter
   * itself. Narrowing the type alone would not be enough: the object reaching
   * the React tree must not *have* an `authorizeRequest` property at run time,
   * because anything in the tree can read one off a value it was handed.
   */
  readonly client: AuthClient;
  readonly signIn: (returnTo: string) => void;
  readonly signOut: () => void;
  readonly notifyCallbackStarted: () => void;
  readonly notifyCallbackSucceeded: (session: AuthSession) => void;
  readonly notifyCallbackFailed: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
