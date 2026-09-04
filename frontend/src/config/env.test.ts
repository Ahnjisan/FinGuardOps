import { afterEach, describe, expect, it, vi } from "vitest";
import { EnvConfigError, parseApiBaseUrl, parseOidcAuthority, parseOidcClientId } from "./env";

describe("parseApiBaseUrl", () => {
  it("accepts a normal http URL", () => {
    expect(parseApiBaseUrl("http://localhost:8080")).toBe("http://localhost:8080");
  });

  it("accepts a normal https URL", () => {
    expect(parseApiBaseUrl("https://api.example.com")).toBe("https://api.example.com");
  });

  it("strips a trailing slash", () => {
    expect(parseApiBaseUrl("http://localhost:8080/")).toBe("http://localhost:8080");
  });

  it("rejects an undefined value", () => {
    expect(() => parseApiBaseUrl(undefined)).toThrow(EnvConfigError);
  });

  it("rejects an empty string", () => {
    expect(() => parseApiBaseUrl("")).toThrow(EnvConfigError);
  });

  it("rejects a value that is not a URL", () => {
    expect(() => parseApiBaseUrl("not-a-url")).toThrow(EnvConfigError);
  });

  it("rejects a non-http(s) scheme", () => {
    expect(() => parseApiBaseUrl("ftp://localhost:8080")).toThrow(EnvConfigError);
  });

  it("rejects a URL with a username", () => {
    expect(() => parseApiBaseUrl("http://user@localhost:8080")).toThrow(EnvConfigError);
  });

  it("rejects a URL with a username and password", () => {
    expect(() => parseApiBaseUrl("http://user:pass@localhost:8080")).toThrow(EnvConfigError);
  });

  it("rejects a URL with a query string", () => {
    expect(() => parseApiBaseUrl("http://localhost:8080?token=abc")).toThrow(EnvConfigError);
  });

  it("rejects a URL with a fragment", () => {
    expect(() => parseApiBaseUrl("http://localhost:8080#section")).toThrow(EnvConfigError);
  });

  it("does not leak the raw invalid value in the error message", () => {
    const secretLikeValue = "ftp://user:hunter2@internal.example.com/leak-me";
    try {
      parseApiBaseUrl(secretLikeValue);
      throw new Error("expected parseApiBaseUrl to throw");
    } catch (error) {
      expect(error).toBeInstanceOf(EnvConfigError);
      const message = (error as Error).message;
      expect(message).not.toContain("hunter2");
      expect(message).not.toContain("internal.example.com");
      expect(message).not.toContain("leak-me");
    }
  });
});

describe("parseOidcAuthority", () => {
  const dev = { isProduction: false };
  const prod = { isProduction: true };

  it("returns an https issuer verbatim", () => {
    expect(parseOidcAuthority("https://as.example", prod)).toBe("https://as.example");
  });

  it("preserves an issuer path verbatim", () => {
    const authority = "https://as.example/realms/finguardops";
    expect(parseOidcAuthority(authority, prod)).toBe(authority);
  });

  it("preserves a trailing slash rather than normalizing it away", () => {
    const withSlash = "https://as.example/realms/finguardops/";
    const withoutSlash = "https://as.example/realms/finguardops";

    expect(parseOidcAuthority(withSlash, prod)).toBe(withSlash);
    expect(parseOidcAuthority(withoutSlash, prod)).toBe(withoutSlash);
    expect(parseOidcAuthority(withSlash, prod)).not.toBe(parseOidcAuthority(withoutSlash, prod));
  });

  it("allows an at sign inside the issuer path", () => {
    const authority = "https://as.example/realms/@tenant";
    expect(parseOidcAuthority(authority, prod)).toBe(authority);
  });

  it("preserves the port and casing of the value verbatim", () => {
    const authority = "https://AS.Example:8443/Realms/Fin";
    expect(parseOidcAuthority(authority, prod)).toBe(authority);
  });

  it.each([
    ["undefined", undefined],
    ["an empty string", ""],
    ["only whitespace", "   "],
  ])("rejects %s", (_label, value) => {
    expect(() => parseOidcAuthority(value, dev)).toThrow(EnvConfigError);
  });

  it.each([
    ["leading whitespace", " https://as.example"],
    ["trailing whitespace", "https://as.example "],
    ["a trailing newline", "https://as.example\n"],
    ["a leading tab", "\thttps://as.example"],
  ])("rejects surrounding whitespace: %s", (_label, value) => {
    expect(() => parseOidcAuthority(value, prod)).toThrow(EnvConfigError);
  });

  it.each([
    ["a NUL character", "https://as.exa\u0000mple"],
    ["a unit separator", "https://as.example\u001F"],
    ["a DEL character", "https://as.example\u007F"],
    ["an embedded carriage return", "https://as.\rexample"],
    ["an embedded tab", "https://as.\texample"],
  ])("rejects a control character: %s", (_label, value) => {
    expect(() => parseOidcAuthority(value, prod)).toThrow(EnvConfigError);
  });

  it.each([
    ["empty userinfo", "https://@as.example"],
    ["username-only userinfo", "https://user@as.example"],
    ["username and password userinfo", "https://user:password@as.example"],
    ["empty userinfo with a path", "https://@as.example/realms/fin"],
  ])("rejects userinfo in the authority component: %s", (_label, value) => {
    expect(() => parseOidcAuthority(value, prod)).toThrow(EnvConfigError);
  });

  it.each([
    ["an empty query delimiter", "https://as.example/?"],
    ["an empty fragment delimiter", "https://as.example/#"],
    ["a bare empty query on the origin", "https://as.example?"],
    ["a query string", "https://as.example/?client_secret=abc"],
    ["a fragment", "https://as.example/#section"],
  ])("rejects a query or fragment: %s", (_label, value) => {
    expect(() => parseOidcAuthority(value, prod)).toThrow(EnvConfigError);
  });

  it.each([
    ["a bare host", "as.example"],
    ["a relative path", "/realms/fin"],
    ["an ftp scheme", "ftp://as.example"],
    ["a javascript scheme", "javascript:alert(1)"],
    ["a data scheme", "data:text/html,x"],
    ["a mailto scheme", "mailto:ops.example"],
  ])("rejects a value that is not an http(s) URL: %s", (_label, value) => {
    expect(() => parseOidcAuthority(value, prod)).toThrow(EnvConfigError);
  });

  it("rejects plain http in production", () => {
    expect(() => parseOidcAuthority("http://localhost:8002", prod)).toThrow(EnvConfigError);
    expect(() => parseOidcAuthority("http://as.example", prod)).toThrow(EnvConfigError);
  });

  it.each([
    ["localhost", "http://localhost:8002"],
    ["the IPv4 loopback", "http://127.0.0.1:8002"],
    ["the IPv6 loopback", "http://[::1]:8002"],
    ["localhost without a port", "http://localhost"],
  ])("allows http outside production for %s", (_label, value) => {
    expect(parseOidcAuthority(value, dev)).toBe(value);
  });

  it.each([
    ["a public host", "http://as.example"],
    ["a loopback-like subdomain", "http://localhost.evil.example"],
    ["a neighbouring loopback address", "http://127.0.0.2:8002"],
    ["a private address", "http://10.0.0.1:8002"],
  ])("rejects http outside production for %s", (_label, value) => {
    expect(() => parseOidcAuthority(value, dev)).toThrow(EnvConfigError);
  });

  it("allows https outside production", () => {
    expect(parseOidcAuthority("https://as.example", dev)).toBe("https://as.example");
  });

  it("does not leak the raw value, host or credential in the error", () => {
    const hostile = "https://operator:hunter2@secret-host.internal/leak-me";
    try {
      parseOidcAuthority(hostile, prod);
      throw new Error("expected parseOidcAuthority to throw");
    } catch (error) {
      expect(error).toBeInstanceOf(EnvConfigError);
      const message = (error as Error).message;
      expect(message).not.toContain("hunter2");
      expect(message).not.toContain("secret-host.internal");
      expect(message).not.toContain("operator");
      expect(message).not.toContain("leak-me");
    }
  });
});

describe("parseOidcClientId", () => {
  it("returns the client ID verbatim", () => {
    expect(parseOidcClientId("finguardops-frontend")).toBe("finguardops-frontend");
  });

  it("preserves internal spacing and casing verbatim", () => {
    expect(parseOidcClientId("FinGuard Ops SPA")).toBe("FinGuard Ops SPA");
  });

  it.each([
    ["undefined", undefined],
    ["an empty string", ""],
    ["only whitespace", "  "],
    ["only a tab", "\t"],
  ])("rejects %s", (_label, value) => {
    expect(() => parseOidcClientId(value)).toThrow(EnvConfigError);
  });

  it.each([
    ["leading whitespace", " finguardops-frontend"],
    ["trailing whitespace", "finguardops-frontend "],
    ["a trailing newline", "finguardops-frontend\n"],
  ])("rejects surrounding whitespace: %s", (_label, value) => {
    expect(() => parseOidcClientId(value)).toThrow(EnvConfigError);
  });

  it.each([
    ["a NUL character", "fin\u0000guard"],
    ["a DEL character", "finguard\u007F"],
    ["an embedded newline", "fin\nguard"],
    ["an embedded tab", "fin\tguard"],
  ])("rejects a control character: %s", (_label, value) => {
    expect(() => parseOidcClientId(value)).toThrow(EnvConfigError);
  });

  it("does not leak the raw client ID in the error", () => {
    try {
      parseOidcClientId(" secret-client-id-xyz");
      throw new Error("expected parseOidcClientId to throw");
    } catch (error) {
      expect((error as Error).message).not.toContain("secret-client-id-xyz");
    }
  });
});

describe("getEnv", () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it("returns the validated base URL once configured", async () => {
    vi.stubEnv("VITE_API_BASE_URL", "http://localhost:9000");
    const { getEnv } = await import("./env");

    expect(getEnv().apiBaseUrl).toBe("http://localhost:9000");
  });

  it("throws EnvConfigError when the variable is missing", async () => {
    vi.stubEnv("VITE_API_BASE_URL", "");
    const { getEnv, EnvConfigError: LocalEnvConfigError } = await import("./env");

    expect(() => getEnv()).toThrow(LocalEnvConfigError);
  });

  it("memoizes the validated value across calls", async () => {
    vi.stubEnv("VITE_API_BASE_URL", "http://localhost:9000");
    const { getEnv } = await import("./env");

    expect(getEnv()).toBe(getEnv());
  });

  it("does not depend on the OIDC configuration", async () => {
    vi.stubEnv("VITE_API_BASE_URL", "http://localhost:9000");
    vi.stubEnv("VITE_OIDC_AUTHORITY", "");
    vi.stubEnv("VITE_OIDC_CLIENT_ID", "");
    const { getEnv } = await import("./env");

    expect(getEnv().apiBaseUrl).toBe("http://localhost:9000");
  });
});

describe("getAuthEnv", () => {
  function stubValidAuthEnv(): void {
    vi.stubEnv("VITE_OIDC_AUTHORITY", "https://as.example/realms/fin");
    vi.stubEnv("VITE_OIDC_CLIENT_ID", "finguardops-frontend");
  }

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it("returns the validated OIDC configuration", async () => {
    stubValidAuthEnv();
    const { getAuthEnv } = await import("./env");

    expect(getAuthEnv().oidcAuthority).toBe("https://as.example/realms/fin");
    expect(getAuthEnv().oidcClientId).toBe("finguardops-frontend");
  });

  it("throws EnvConfigError when the authority is missing", async () => {
    stubValidAuthEnv();
    vi.stubEnv("VITE_OIDC_AUTHORITY", "");
    const { getAuthEnv, EnvConfigError: LocalEnvConfigError } = await import("./env");

    expect(() => getAuthEnv()).toThrow(LocalEnvConfigError);
  });

  it("throws EnvConfigError when the client ID is missing", async () => {
    stubValidAuthEnv();
    vi.stubEnv("VITE_OIDC_CLIENT_ID", "");
    const { getAuthEnv, EnvConfigError: LocalEnvConfigError } = await import("./env");

    expect(() => getAuthEnv()).toThrow(LocalEnvConfigError);
  });

  it("throws EnvConfigError when the authority carries userinfo", async () => {
    stubValidAuthEnv();
    vi.stubEnv("VITE_OIDC_AUTHORITY", "https://user@as.example");
    const { getAuthEnv, EnvConfigError: LocalEnvConfigError } = await import("./env");

    expect(() => getAuthEnv()).toThrow(LocalEnvConfigError);
  });

  it("does not depend on the backend base URL", async () => {
    stubValidAuthEnv();
    vi.stubEnv("VITE_API_BASE_URL", "");
    const { getAuthEnv } = await import("./env");

    expect(getAuthEnv().oidcClientId).toBe("finguardops-frontend");
  });

  it("memoizes the validated value across calls", async () => {
    stubValidAuthEnv();
    const { getAuthEnv } = await import("./env");

    expect(getAuthEnv()).toBe(getAuthEnv());
  });

  it("keeps a trailing slash on the authority verbatim", async () => {
    stubValidAuthEnv();
    vi.stubEnv("VITE_OIDC_AUTHORITY", "https://as.example/realms/fin/");
    const { getAuthEnv } = await import("./env");

    expect(getAuthEnv().oidcAuthority).toBe("https://as.example/realms/fin/");
  });
});
