import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { isCanonicalUuidV4 } from "../api/responseValidation";
import { safeAuthErrorMessage } from "../auth/authErrors";
import { resolveReturnRoute } from "../auth/returnRoute";
import { useAuth } from "../auth/useAuth";
import { useCapabilities } from "../auth/useCapabilities";

/** One path segment under `/cases/`, and nothing else. */
const CASE_DETAIL_PATH = /^\/cases\/([^/]+)$/;

/**
 * Whether a location is one of the two addresses the case section actually
 * occupies: the list itself, or one case's detail.
 *
 * The whole location is judged, not a prefix of its path. React Router's
 * `NavLink` marks itself current for everything *under* its `to`, and `end`
 * narrows that only to the pathname - so `/cases?caseStatus=OPEN`,
 * `/cases#content`, `/cases/`, `/casesx` and every unrouted address beneath
 * `/cases/` would still claim to be the page the analyst is on. None of them is
 * an address this application navigates to.
 *
 * The detail form is admitted by the same rule the route, the API layer and the
 * return-route allowlist apply: one path segment that is already a canonical
 * lowercase UUID v4, read exactly as React Router reports it and never decoded,
 * trimmed or case folded. An uppercase identifier, a UUID v1, an invalid RFC
 * variant and a malformed one are each a different address, and each is a 404
 * rather than the case section - so announcing one as the current page would
 * send a screen-reader user looking for a record that is not there.
 */
function isCaseSectionLocation(pathname: string, search: string, hash: string): boolean {
  if (search !== "" || hash !== "") {
    return false;
  }
  if (pathname === "/cases") {
    return true;
  }
  const match = CASE_DETAIL_PATH.exec(pathname);
  return match !== null && isCanonicalUuidV4(match[1]);
}

export function AppShell() {
  const { state, signIn, signOut } = useAuth();
  const capabilities = useCapabilities();
  const location = useLocation();

  // The whole location the router holds - path, query and fragment - rather
  // than the path alone. `/transactions/{uuid}?tab=raw` and
  // `/transactions/{uuid}#raw` are not routes this application has, and
  // dropping the query or the fragment before the check would readmit them as
  // the canonical detail route and send someone somewhere they never asked to
  // go. The three parts are joined exactly as React Router reports them - each
  // already empty when absent, and nothing trimmed, decoded or normalized on
  // the way in - so a location carrying either one fails the allowlist and
  // falls back to the default rather than being carried along.
  const returnTo = resolveReturnRoute(
    `${location.pathname}${location.search}${location.hash}`,
  );

  /**
   * Whether the browser is inside the case section: the list, or one case's
   * detail.
   *
   * `aria-current` is a statement to a screen reader about where the user *is*,
   * and both of those addresses are places this destination leads to, so both
   * carry it. Everything else does not - see `isCaseSectionLocation`, which
   * owns the rule.
   *
   * `search` and `hash` are read as React Router reports them - already empty
   * when absent, and nothing trimmed, decoded or normalized on the way in - so
   * a location carrying either one is simply not the current page.
   */
  const inCaseSection = isCaseSectionLocation(
    location.pathname,
    location.search,
    location.hash,
  );

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
    <div className="app">
      {/*
        First focusable thing on the page. An analyst working from the keyboard
        reaches the sheet in one keystroke instead of tabbing past the rail on
        every navigation.
      */}
      <a className="skip-link" href="#main-content">
        Skip to main content
      </a>
      <header className="rail">
        <div className="rail__identity">
          <h1 className="rail__wordmark">FinGuardOps</h1>
          <p className="rail__tagline">Fraud operations console</p>
        </div>
        <nav className="rail__nav" aria-label="Primary">
          <ul className="rail__list">
            <li>
              <NavLink className="rail__link" to="/" end>
                Home
              </NavLink>
            </li>
            {/*
              Rendered only for a session that actually holds the capability,
              and removed from the DOM otherwise rather than disabled or hidden
              with CSS: a control that is merely styled away is still in the
              accessibility tree and comes back with one attribute change. A
              `RULE_OPERATOR`, `RECOVERY_OPERATOR` or `PLATFORM_ADMIN` session
              therefore has no trace of this destination at all.

              This is a convenience boundary. The route behind it is guarded
              independently, and Backend re-decides authorization from the
              access token on every request.
            */}
            {capabilities.has("transaction:view") && (
              <li>
                <NavLink className="rail__link" to="/transactions">
                  Transactions
                </NavLink>
              </li>
            )}
            {/*
              The same treatment for the case list, decided by its own
              capability. `case:view` and `transaction:view` are granted
              together by today's three FDS roles, but they are asked for
              separately here: the capability table is what decides, and a
              future role holding one and not the other must see exactly one
              destination rather than both.
            */}
            {capabilities.has("case:view") && (
              <li>
                {/*
                  A plain `Link` carrying the attribute this application decides,
                  rather than a `NavLink` carrying one React Router derives. The
                  two cannot both write `aria-current`, so there is exactly one
                  of it in the markup and it is either `page` or absent - never
                  the string `"false"`, which assistive technology reads as an
                  attribute that is present.
                */}
                <Link
                  className="rail__link"
                  to="/cases"
                  aria-current={inCaseSection ? "page" : undefined}
                >
                  Cases
                </Link>
              </li>
            )}
            <li>
              <NavLink className="rail__link" to="/health">
                Health
              </NavLink>
            </li>
          </ul>
        </nav>
        <div className="rail__session">
          <div className="rail__status" role="status" aria-label="Authentication status">
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
              className="button button--rail"
              type="button"
              onClick={() => {
                signIn(returnTo);
              }}
            >
              Sign in
            </button>
          )}
          {state.status === "authenticated" && (
            <button className="button button--rail" type="button" onClick={signOut}>
              Sign out
            </button>
          )}
        </div>
      </header>
      <main className="main" id="main-content" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  );
}
