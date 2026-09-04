import { describe, expect, it } from "vitest";
import type { AuthSession } from "./authClient";
import { authReducer, initialAuthState, type AuthState } from "./authState";

const SESSION: AuthSession = {
  subject: "11111111-1111-4111-8111-111111111111",
  displayName: "Test Analyst",
};

const OTHER_SESSION: AuthSession = {
  subject: "22222222-2222-4222-8222-222222222222",
};

const UNAUTHENTICATED: AuthState = { status: "unauthenticated" };
const AUTHENTICATING: AuthState = { status: "authenticating" };
const AUTHENTICATED: AuthState = { status: "authenticated", session: SESSION };
const ERROR: AuthState = { status: "error", kind: "callback" };

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

describe("teardown transitions", () => {
  it("signs out from any state", () => {
    const states: AuthState[] = [
      initialAuthState,
      UNAUTHENTICATED,
      AUTHENTICATING,
      AUTHENTICATED,
      ERROR,
    ];
    for (const state of states) {
      expect(authReducer(state, { type: "signed-out" })).toEqual(UNAUTHENTICATED);
    }
  });

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

  it("exposes only the subject and display name of a session", () => {
    const state = authReducer(AUTHENTICATING, { type: "callback-succeeded", session: SESSION });

    expect(state.status).toBe("authenticated");
    if (state.status === "authenticated") {
      expect(Object.keys(state.session).sort()).toEqual(["displayName", "subject"]);
    }
  });
});
