import { describe, expect, it } from "vitest";
import { DEFAULT_RETURN_ROUTE, resolveReturnRoute } from "./returnRoute";

describe("resolveReturnRoute", () => {
  it("allows the root route", () => {
    expect(resolveReturnRoute("/")).toBe("/");
  });

  it("allows the health route", () => {
    expect(resolveReturnRoute("/health")).toBe("/health");
  });

  const rejected: Array<[string, unknown]> = [
    ["trailing whitespace", "/health "],
    ["leading whitespace", " /health"],
    ["trailing slash variant", "/health/"],
    ["different casing", "/HEALTH"],
    ["query string appended", "/health?x=1"],
    ["fragment appended", "/health#a"],
    ["protocol-relative URL", "//evil.example"],
    ["backslash after slash", "/\\evil.example"],
    ["double backslash", "\\\\evil.example"],
    ["single backslash", "\\evil.example"],
    ["encoded slash", "%2fhealth"],
    ["encoded double slash", "/%2f%2fevil.example"],
    ["double encoded slash", "%252fhealth"],
    ["encoded backslash", "%5cevil.example"],
    ["encoded dot segments", "/%2e%2e/"],
    ["absolute https URL", "https://evil.example"],
    ["absolute http URL", "http://evil.example/health"],
    ["javascript scheme", "javascript:alert(1)"],
    ["data scheme", "data:text/html,<script>"],
    ["the callback route itself", "/auth/callback"],
    ["an unknown internal route", "/admin"],
    ["a nested internal route", "/health/details"],
    ["empty string", ""],
    ["undefined", undefined],
    ["null", null],
    ["a number", 42],
    ["an object", {}],
    ["an array wrapping an allowed route", ["/health"]],
    ["a String object wrapping an allowed route", new String("/health")],
  ];

  it.each(rejected)("falls back to the default for %s", (_label, value) => {
    expect(resolveReturnRoute(value)).toBe(DEFAULT_RETURN_ROUTE);
    expect(resolveReturnRoute(value)).toBe("/");
  });

  it("never returns a value derived from the raw input", () => {
    const hostile = "https://evil.example/steal?token=hunter2";
    const resolved: string = resolveReturnRoute(hostile);

    expect(resolved).not.toContain("evil.example");
    expect(resolved).not.toContain("hunter2");
  });

  it("only ever returns one of the two allowlisted routes", () => {
    const inputs: unknown[] = ["/", "/health", "/admin", "//evil", undefined, 0];
    for (const input of inputs) {
      expect(["/", "/health"]).toContain(resolveReturnRoute(input));
    }
  });
});
