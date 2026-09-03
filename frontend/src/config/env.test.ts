import { afterEach, describe, expect, it, vi } from "vitest";
import { EnvConfigError, parseApiBaseUrl } from "./env";

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
});
