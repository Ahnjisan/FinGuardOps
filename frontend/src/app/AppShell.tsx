import { Link, Outlet, useLocation } from "react-router-dom";
import { safeAuthErrorMessage } from "../auth/authErrors";
import { resolveReturnRoute } from "../auth/returnRoute";
import { useAuth } from "../auth/useAuth";

export function AppShell() {
  const { state, signIn, signOut } = useAuth();
  const location = useLocation();

  // Only the two public routes are valid return targets, so an unexpected
  // pathname simply falls back to the default rather than being carried along.
  const returnTo = resolveReturnRoute(location.pathname);

  let statusMessage: string | null = null;
  if (state.status === "initializing") {
    statusMessage = "Preparing sign-in...";
  } else if (state.status === "authenticating") {
    statusMessage = "Signing in...";
  } else if (state.status === "signing-out") {
    statusMessage = "Signing out...";
  } else if (state.status === "error") {
    statusMessage = safeAuthErrorMessage(state.kind);
  } else if (state.status === "authenticated") {
    statusMessage = state.session.displayName
      ? `Signed in as ${state.session.displayName}.`
      : "Signed in.";
  }

  return (
    <div>
      <header>
        <h1>FinGuardOps</h1>
        <nav aria-label="Primary">
          <ul>
            <li>
              <Link to="/">Home</Link>
            </li>
            <li>
              <Link to="/health">Health</Link>
            </li>
          </ul>
        </nav>
        <div>
          <div role="status" aria-label="Authentication status">
            {statusMessage}
          </div>
          {/*
            Neither control is offered while a redirect is in flight. In
            `authenticating` and `signing-out` there is nothing to sign out of
            and nothing to start, so the affordance is withdrawn rather than
            disabled: a second click cannot be swallowed if there is no button.
          */}
          {(state.status === "unauthenticated" || state.status === "error") && (
            <button
              type="button"
              onClick={() => {
                signIn(returnTo);
              }}
            >
              Sign in
            </button>
          )}
          {state.status === "authenticated" && (
            <button type="button" onClick={signOut}>
              Sign out
            </button>
          )}
        </div>
      </header>
      <main>
        <Outlet />
      </main>
    </div>
  );
}
