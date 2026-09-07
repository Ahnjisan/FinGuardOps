import { describe, expect, it } from "vitest";
import {
  AuthCallbackError,
  AuthSignInError,
  AuthSignOutError,
  safeAuthErrorMessage,
  type AuthErrorKind,
} from "./authErrors";

const ALL_KINDS: AuthErrorKind[] = ["configuration", "sign-in", "callback", "sign-out"];

describe("safeAuthErrorMessage", () => {
  it("returns a fixed message for every kind", () => {
    for (const kind of ALL_KINDS) {
      expect(safeAuthErrorMessage(kind)).toMatch(/\S/);
    }
  });

  it("returns the same message for the same kind every time", () => {
    expect(safeAuthErrorMessage("callback")).toBe(safeAuthErrorMessage("callback"));
  });

  it("never mentions protocol material", () => {
    for (const kind of ALL_KINDS) {
      const message = safeAuthErrorMessage(kind);
      expect(message).not.toMatch(/token|code_verifier|nonce|state=|bearer|jwt/i);
    }
  });
});

describe("auth error classes", () => {
  it("carries no provider payload on a callback failure", () => {
    const error = new AuthCallbackError();

    expect(error.name).toBe("AuthCallbackError");
    expect(error.message).not.toMatch(/code|state|nonce|verifier|token/i);
    expect(Object.keys(error)).not.toContain("innerError");
  });

  it("carries no provider payload on a sign-in failure", () => {
    const error = new AuthSignInError();

    expect(error.name).toBe("AuthSignInError");
    expect(error.message).not.toMatch(/code|state|nonce|verifier|token/i);
  });

  it("carries no provider payload on a sign-out failure", () => {
    const error = new AuthSignOutError();

    expect(error.name).toBe("AuthSignOutError");
    expect(error.message).not.toMatch(/code|state|nonce|verifier|token|hint|logout endpoint/i);
    expect(Object.keys(error)).toEqual(["name"]);
    expect((error as { cause?: unknown }).cause).toBeUndefined();
  });

  it("keeps the sign-out message distinct from the sign-in ones", () => {
    const signOut = safeAuthErrorMessage("sign-out");

    expect(signOut).not.toBe(safeAuthErrorMessage("sign-in"));
    expect(signOut).not.toBe(safeAuthErrorMessage("callback"));
    expect(signOut).not.toBe(safeAuthErrorMessage("configuration"));
  });

  it("never suggests the sign-out did not happen locally", () => {
    expect(safeAuthErrorMessage("sign-out")).toContain("signed out of this browser");
  });
});
