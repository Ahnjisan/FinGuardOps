import { describe, expect, it } from "vitest";
import {
  AuthCallbackError,
  AuthSignInError,
  safeAuthErrorMessage,
  type AuthErrorKind,
} from "./authErrors";

const ALL_KINDS: AuthErrorKind[] = ["configuration", "sign-in", "callback"];

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
});
