import { describe, expect, it } from "vitest";
import type { AuthSession } from "./authClient";
import { authReducer, initialAuthState, type AuthState } from "./authState";

// The reducer moves sessions around without reading them, so the roles here
// only have to be a real published set. They are named rather than empty
// because `resolveUserRoles()` refuses an empty one, and a fixture no adapter
// could produce would quietly stop being evidence about the reducer.
const SESSION: AuthSession = {
  subject: "11111111-1111-4111-8111-111111111111",
  displayName: "Test Analyst",
  roles: ["FDS_ANALYST"],
};

const OTHER_SESSION: AuthSession = {
  subject: "22222222-2222-4222-8222-222222222222",
  roles: ["FDS_APPROVER"],
};

const UNAUTHENTICATED: AuthState = { status: "unauthenticated" };
const AUTHENTICATING: AuthState = { status: "authenticating" };
const AUTHENTICATED: AuthState = { status: "authenticated", session: SESSION };
const ERROR: AuthState = { status: "error", kind: "callback" };
const SIGNING_OUT: AuthState = { status: "signing-out" };
const SIGN_OUT_ERROR: AuthState = { status: "error", kind: "sign-out" };

describe("initial auth state", () => {
  it("starts as initializing", () => {
    expect(initialAuthState).toEqual({ status: "initializing" });
  });
});

describe("initialization transitions", () => {
  it("moves from initializing to unauthenticated", () => {
    expect(authReducer(initialAuthState, { type: "init-completed" })).toEqual(UNAUTHENTICATED);
  });

  it("restores an in-memory session found during initialization", () => {
    expect(authReducer(initialAuthState, { type: "init-restored", session: SESSION })).toEqual({
      status: "authenticated",
      session: SESSION,
    });
  });

  it("reports a configuration error when initialization fails", () => {
    expect(authReducer(initialAuthState, { type: "init-failed" })).toEqual({
      status: "error",
      kind: "configuration",
    });
  });

  it("ignores a late init-completed once a callback is already running", () => {
    expect(authReducer(AUTHENTICATING, { type: "init-completed" })).toBe(AUTHENTICATING);
  });

  it("ignores a late init-restored once the machine has moved on", () => {
    expect(authReducer(AUTHENTICATED, { type: "init-restored", session: OTHER_SESSION })).toBe(
      AUTHENTICATED,
    );
  });

  it("ignores a late init-failed once the machine has moved on", () => {
    expect(authReducer(AUTHENTICATED, { type: "init-failed" })).toBe(AUTHENTICATED);
  });
});

describe("sign-in transitions", () => {
  it("starts authenticating from unauthenticated", () => {
    expect(authReducer(UNAUTHENTICATED, { type: "sign-in-started" })).toEqual(AUTHENTICATING);
  });

  it("allows a retry from the error state", () => {
    expect(authReducer(ERROR, { type: "sign-in-started" })).toEqual(AUTHENTICATING);
  });

  it("ignores a duplicate sign-in while already authenticating", () => {
    expect(authReducer(AUTHENTICATING, { type: "sign-in-started" })).toBe(AUTHENTICATING);
  });

  it("ignores a sign-in while already authenticated", () => {
    expect(authReducer(AUTHENTICATED, { type: "sign-in-started" })).toBe(AUTHENTICATED);
  });

  it("reports a fixed sign-in error kind when the redirect cannot start", () => {
    expect(authReducer(AUTHENTICATING, { type: "sign-in-failed" })).toEqual({
      status: "error",
      kind: "sign-in",
    });
  });

  it("ignores a sign-in failure that arrives outside authenticating", () => {
    expect(authReducer(AUTHENTICATED, { type: "sign-in-failed" })).toBe(AUTHENTICATED);
  });
});

describe("callback transitions", () => {
  it("starts authenticating directly from initializing", () => {
    expect(authReducer(initialAuthState, { type: "callback-started" })).toEqual(AUTHENTICATING);
  });

  it("starts authenticating from unauthenticated", () => {
    expect(authReducer(UNAUTHENTICATED, { type: "callback-started" })).toEqual(AUTHENTICATING);
  });

  it("authenticates on a successful callback", () => {
    expect(authReducer(AUTHENTICATING, { type: "callback-succeeded", session: SESSION })).toEqual(
      AUTHENTICATED,
    );
  });

  it("ignores a duplicate callback success", () => {
    const first = authReducer(AUTHENTICATING, { type: "callback-succeeded", session: SESSION });
    const second = authReducer(first, { type: "callback-succeeded", session: OTHER_SESSION });

    expect(second).toBe(first);
  });

  it("reports a fixed callback error kind on failure", () => {
    expect(authReducer(AUTHENTICATING, { type: "callback-failed" })).toEqual({
      status: "error",
      kind: "callback",
    });
  });

  it("ignores a duplicate callback failure", () => {
    const first = authReducer(AUTHENTICATING, { type: "callback-failed" });
    const second = authReducer(first, { type: "callback-failed" });

    expect(second).toBe(first);
  });

  it("does not let a late callback failure demote an established session", () => {
    expect(authReducer(AUTHENTICATED, { type: "callback-failed" })).toBe(AUTHENTICATED);
  });

  it("does not let a late callback success resurrect a failed sign-in", () => {
    expect(authReducer(ERROR, { type: "callback-succeeded", session: SESSION })).toBe(ERROR);
  });
});

describe("sign-out transitions", () => {
  it("leaves the authenticated state immediately, carrying no session", () => {
    const state = authReducer(AUTHENTICATED, { type: "sign-out-started" });

    expect(state).toEqual(SIGNING_OUT);
    expect(Object.keys(state)).toEqual(["status"]);
  });

  it("starts only from an authenticated session", () => {
    for (const state of [initialAuthState, UNAUTHENTICATED, AUTHENTICATING, ERROR]) {
      expect(authReducer(state, { type: "sign-out-started" })).toBe(state);
    }
  });

  it("ignores a duplicate sign-out while the redirect is in flight", () => {
    expect(authReducer(SIGNING_OUT, { type: "sign-out-started" })).toBe(SIGNING_OUT);
  });

  it("refuses to start a sign-in while signing out", () => {
    expect(authReducer(SIGNING_OUT, { type: "sign-in-started" })).toBe(SIGNING_OUT);
  });

  it("refuses to start a sign-in callback while signing out", () => {
    expect(authReducer(SIGNING_OUT, { type: "callback-started" })).toBe(SIGNING_OUT);
  });

  it("reports a fixed sign-out error kind when the redirect cannot start", () => {
    expect(authReducer(SIGNING_OUT, { type: "sign-out-failed" })).toEqual(SIGN_OUT_ERROR);
  });

  it("never restores a session on a sign-out failure", () => {
    const state = authReducer(SIGNING_OUT, { type: "sign-out-failed" });

    expect(state.status).not.toBe("authenticated");
    expect(Object.keys(state).sort()).toEqual(["kind", "status"]);
  });

  it("ignores a sign-out failure that arrives outside signing-out", () => {
    for (const state of [initialAuthState, UNAUTHENTICATED, AUTHENTICATING, AUTHENTICATED, ERROR]) {
      expect(authReducer(state, { type: "sign-out-failed" })).toBe(state);
    }
  });

  it("stays signed out when the redirect is cancelled or the page is restored", () => {
    expect(authReducer(SIGNING_OUT, { type: "sign-out-cancelled" })).toEqual(UNAUTHENTICATED);
  });

  it("cannot resurrect a session through a cancellation", () => {
    expect(authReducer(AUTHENTICATED, { type: "sign-out-cancelled" })).toBe(AUTHENTICATED);
    expect(authReducer(UNAUTHENTICATED, { type: "sign-out-cancelled" })).toBe(UNAUTHENTICATED);
  });

  it("leaves a signing-out state alone on invalidation", () => {
    expect(authReducer(SIGNING_OUT, { type: "session-invalidated" })).toBe(SIGNING_OUT);
  });
});

describe("logout callback transitions", () => {
  it("classifies the root response before anything else has run", () => {
    expect(authReducer(initialAuthState, { type: "logout-callback-started" })).toEqual(SIGNING_OUT);
  });

  it("never starts from a page load that already has a session", () => {
    for (const state of [UNAUTHENTICATED, AUTHENTICATING, AUTHENTICATED, ERROR]) {
      expect(authReducer(state, { type: "logout-callback-started" })).toBe(state);
    }
  });

  it("ends unauthenticated on success", () => {
    expect(authReducer(SIGNING_OUT, { type: "logout-callback-succeeded" })).toEqual(
      UNAUTHENTICATED,
    );
  });

  it("ends in the fixed sign-out error, with no credential, on failure", () => {
    const state = authReducer(SIGNING_OUT, { type: "logout-callback-failed" });

    expect(state).toEqual(SIGN_OUT_ERROR);
    expect(Object.keys(state).sort()).toEqual(["kind", "status"]);
  });

  it("cannot demote a session a later page load already published", () => {
    expect(authReducer(AUTHENTICATED, { type: "logout-callback-succeeded" })).toBe(AUTHENTICATED);
    expect(authReducer(AUTHENTICATED, { type: "logout-callback-failed" })).toBe(AUTHENTICATED);
  });

  it("ignores a duplicate outcome from a shared StrictMode promise", () => {
    const first = authReducer(SIGNING_OUT, { type: "logout-callback-succeeded" });
    expect(authReducer(first, { type: "logout-callback-succeeded" })).toBe(first);

    const failed = authReducer(SIGNING_OUT, { type: "logout-callback-failed" });
    expect(authReducer(failed, { type: "logout-callback-failed" })).toBe(failed);
  });

  it("lets a completed logout callback survive a late initialization result", () => {
    const afterCallback = authReducer(SIGNING_OUT, { type: "logout-callback-succeeded" });

    expect(authReducer(afterCallback, { type: "init-completed" })).toBe(afterCallback);
    expect(authReducer(afterCallback, { type: "init-failed" })).toBe(afterCallback);
    expect(authReducer(afterCallback, { type: "init-restored", session: SESSION })).toBe(
      afterCallback,
    );
  });

  it("lets a failed logout callback survive a late initialization result", () => {
    const afterCallback = authReducer(SIGNING_OUT, { type: "logout-callback-failed" });

    expect(authReducer(afterCallback, { type: "init-completed" })).toBe(afterCallback);
    expect(authReducer(afterCallback, { type: "init-failed" })).toBe(afterCallback);
  });

  it("offers an explicit sign-in again after either outcome", () => {
    for (const state of [
      authReducer(SIGNING_OUT, { type: "logout-callback-succeeded" }),
      authReducer(SIGNING_OUT, { type: "logout-callback-failed" }),
    ]) {
      expect(authReducer(state, { type: "sign-in-started" })).toEqual(AUTHENTICATING);
    }
  });
});

describe("teardown transitions", () => {
  it("invalidates an authenticated session", () => {
    expect(authReducer(AUTHENTICATED, { type: "session-invalidated" })).toEqual(UNAUTHENTICATED);
  });

  it("ignores an invalidation when there is no session", () => {
    expect(authReducer(UNAUTHENTICATED, { type: "session-invalidated" })).toBe(UNAUTHENTICATED);
    expect(authReducer(ERROR, { type: "session-invalidated" })).toBe(ERROR);
    expect(authReducer(AUTHENTICATING, { type: "session-invalidated" })).toBe(AUTHENTICATING);
  });
});

describe("state shape", () => {
  it("never carries provider payload on the error state", () => {
    const state = authReducer(AUTHENTICATING, { type: "callback-failed" });

    expect(Object.keys(state).sort()).toEqual(["kind", "status"]);
  });

  it("exposes only the subject, display name and roles of a session", () => {
    const state = authReducer(AUTHENTICATING, { type: "callback-succeeded", session: SESSION });

    expect(state.status).toBe("authenticated");
    if (state.status === "authenticated") {
      expect(Object.keys(state.session).sort()).toEqual(["displayName", "roles", "subject"]);
    }
  });
});
