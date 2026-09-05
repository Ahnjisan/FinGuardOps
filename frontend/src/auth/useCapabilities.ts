import { useMemo } from "react";
import { resolveCapabilities, type CapabilitySet } from "./capabilities";
import { useAuth } from "./useAuth";

/**
 * The single place the React tree turns an authentication state into a
 * capability decision.
 *
 * Navigation, route guards and action controls all read this hook, so a control
 * cannot disagree with the guard protecting what it leads to.
 *
 * Only the `authenticated` state contributes roles. `initializing` and
 * `authenticating` deliberately resolve to nothing here: callers must
 * distinguish "not decided yet" from "decided as none" by looking at the auth
 * state itself, because treating a pending session as denied would flash a
 * refusal at a user who is in fact permitted.
 *
 * Memoized on the roles array, which the adapter freezes once per session, so a
 * new session produces a new decision and a re-render of the same session does
 * not.
 */
export function useCapabilities(): CapabilitySet {
  const { state } = useAuth();
  const roles = state.status === "authenticated" ? state.session.roles : undefined;
  return useMemo(() => resolveCapabilities(roles), [roles]);
}
