import { createContext } from "react";
import type { AuthClient, AuthSession } from "./authClient";
import type { AuthState } from "./authState";

export interface AuthContextValue {
  readonly state: AuthState;
  /** The port itself, so the callback route can drive protocol completion. */
  readonly client: AuthClient;
  readonly signIn: (returnTo: string) => void;
  readonly signOut: () => void;
  readonly notifyCallbackStarted: () => void;
  readonly notifyCallbackSucceeded: (session: AuthSession) => void;
  readonly notifyCallbackFailed: () => void;
}

export const AuthContext = createContext<AuthContextValue | null>(null);
