import { afterEach, describe, expect, it, vi } from "vitest";
import {
  CALLBACK_PATH,
  classifyCallbackUrl,
  clearCallbackUrl,
  hasCallbackParameters,
} from "./callbackUrl";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("hasCallbackParameters", () => {
  it("detects an authorization code response", () => {
    expect(hasCallbackParameters("http://localhost/auth/callback?code=abc&state=xyz")).toBe(true);
  });

  it("detects an error response", () => {
    expect(hasCallbackParameters("http://localhost/auth/callback?error=access_denied")).toBe(true);
  });

  it("reports false for a bare callback entry", () => {
    expect(hasCallbackParameters("http://localhost/auth/callback")).toBe(false);
  });

  it("does not treat a path segment containing 'code' as a parameter", () => {
    expect(hasCallbackParameters("http://localhost/auth/callback/code")).toBe(false);
    expect(hasCallbackParameters("http://localhost/decoded/error")).toBe(false);
  });

  it("does not treat another parameter's value as a parameter name", () => {
    expect(hasCallbackParameters("http://localhost/auth/callback?next=code")).toBe(false);
    expect(hasCallbackParameters("http://localhost/auth/callback?scope=error")).toBe(false);
  });

  it("does not treat a fragment as a query parameter", () => {
    expect(hasCallbackParameters("http://localhost/auth/callback#code=abc")).toBe(false);
    expect(hasCallbackParameters("http://localhost/auth/callback#error=denied")).toBe(false);
  });

  it("does not match a parameter whose name merely contains 'code'", () => {
    expect(hasCallbackParameters("http://localhost/auth/callback?zipcode=12345")).toBe(false);
    expect(hasCallbackParameters("http://localhost/auth/callback?error_code=1")).toBe(false);
  });

  it("reports false for an unparseable URL", () => {
    expect(hasCallbackParameters("not-a-url?code=abc")).toBe(false);
    expect(hasCallbackParameters("")).toBe(false);
  });

  it("accepts an abnormal response carrying both code and error", () => {
    expect(hasCallbackParameters("http://localhost/auth/callback?code=a&error=b")).toBe(true);
  });
});

describe("classifyCallbackUrl", () => {
  it("classifies a code response as an authorization response", () => {
    expect(classifyCallbackUrl("http://localhost/auth/callback?code=abc&state=xyz")).toBe(
      "authorization-response",
    );
  });

  it("classifies an error response as an authorization response", () => {
    expect(classifyCallbackUrl("http://localhost/auth/callback?error=access_denied")).toBe(
      "authorization-response",
    );
  });

  it("classifies a response carrying both code and error as conflicting", () => {
    expect(
      classifyCallbackUrl("http://localhost/auth/callback?code=abc&error=access_denied&state=xyz"),
    ).toBe("conflicting");
  });

  it("classifies a conflicting response regardless of parameter order", () => {
    expect(classifyCallbackUrl("http://localhost/auth/callback?error=denied&code=abc")).toBe(
      "conflicting",
    );
  });

  it("classifies a repeated code alongside an error as conflicting", () => {
    expect(classifyCallbackUrl("http://localhost/auth/callback?code=a&code=b&error=denied")).toBe(
      "conflicting",
    );
  });

  it("classifies a bare entry and an unparseable URL as none", () => {
    expect(classifyCallbackUrl("http://localhost/auth/callback")).toBe("none");
    expect(classifyCallbackUrl("not-a-url?code=abc")).toBe("none");
  });

  it("does not treat a fragment carrying both names as conflicting", () => {
    expect(classifyCallbackUrl("http://localhost/auth/callback#code=a&error=b")).toBe("none");
  });
});

describe("clearCallbackUrl", () => {
  it("replaces the history entry with the bare callback path", () => {
    const replaceState = vi.spyOn(window.history, "replaceState").mockImplementation(() => undefined);

    clearCallbackUrl();

    expect(replaceState).toHaveBeenCalledTimes(1);
    expect(replaceState).toHaveBeenCalledWith(null, "", CALLBACK_PATH);
  });

  it("removes the whole query string and fragment by construction", () => {
    const replaceState = vi.spyOn(window.history, "replaceState").mockImplementation(() => undefined);

    clearCallbackUrl();

    const target = replaceState.mock.calls[0]?.[2];
    expect(target).toBe("/auth/callback");
    expect(String(target)).not.toContain("?");
    expect(String(target)).not.toContain("#");
  });

  it("propagates a history failure so callers can fail closed", () => {
    vi.spyOn(window.history, "replaceState").mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });

    expect(() => {
      clearCallbackUrl();
    }).toThrow();
  });
});
