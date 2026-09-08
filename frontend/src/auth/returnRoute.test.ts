import { describe, expect, it } from "vitest";
import { DEFAULT_RETURN_ROUTE, resolveReturnRoute } from "./returnRoute";

describe("resolveReturnRoute", () => {
  it("allows the root route", () => {
    expect(resolveReturnRoute("/")).toBe("/");
  });

  it("allows the health route", () => {
    expect(resolveReturnRoute("/health")).toBe("/health");
  });

  it("allows the transactions route", () => {
    expect(resolveReturnRoute("/transactions")).toBe("/transactions");
  });

  it("allows the cases route", () => {
    expect(resolveReturnRoute("/cases")).toBe("/cases");
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
    ["the transactions route with a trailing slash", "/transactions/"],
    ["a transaction detail route that does not exist", "/transactions/1"],
    ["a sibling route sharing the transactions prefix", "/transactionsx"],
    ["a route that merely contains the transactions path", "/x/transactions"],
    ["the transactions route with a query string", "/transactions?page=1"],
    ["the transactions route with a fragment", "/transactions#row"],
    ["the transactions route in different casing", "/Transactions"],
    ["the transactions route with whitespace", " /transactions"],
    ["an encoded transactions route", "%2ftransactions"],
    ["a protocol-relative host named transactions", "//transactions"],
    ["an absolute URL ending in the transactions path", "https://evil.example/transactions"],
    ["the cases route with a trailing slash", "/cases/"],
    ["a case detail route that does not exist", "/cases/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["a numeric case identifier", "/cases/1"],
    ["a sibling route sharing the cases prefix", "/casesx"],
    ["a route that merely contains the cases path", "/x/cases"],
    ["the cases route with a query string", "/cases?status=OPEN"],
    ["the cases route with a fragment", "/cases#content"],
    ["the cases route with a query string and a fragment", "/cases?status=OPEN#content"],
    ["the cases route in different casing", "/Cases"],
    ["the cases route with leading whitespace", " /cases"],
    ["the cases route with trailing whitespace", "/cases "],
    ["an encoded cases route", "%2fcases"],
    ["an encoded slash inside the cases route", "/cases%2f"],
    ["an encoded backslash after the cases route", "/cases%5c"],
    ["a double-encoded cases route", "%252fcases"],
    ["a backslash separator before cases", "\\cases"],
    ["a backslash after the leading slash", "/\\cases"],
    ["a duplicate separator before cases", "//cases"],
    ["a duplicate separator inside the cases path", "/cases//"],
    ["a protocol-relative host named cases", "//cases"],
    ["an absolute https URL ending in the cases path", "https://evil.example/cases"],
    ["an absolute http URL ending in the cases path", "http://localhost:5173/cases"],
    ["userinfo in an absolute cases URL", "https://user:pass@evil.example/cases"],
    ["a different port on the same host for cases", "http://localhost:8080/cases"],
    ["a javascript scheme carrying the cases path", "javascript:/cases"],
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

  it("only ever returns an allowlisted route", () => {
    const inputs: unknown[] = [
      "/",
      "/health",
      "/transactions",
      "/transactions/",
      "/cases",
      "/cases/",
      "/admin",
      "//evil",
      undefined,
      0,
    ];
    for (const input of inputs) {
      expect(["/", "/health", "/transactions", "/cases"]).toContain(resolveReturnRoute(input));
    }
  });

  it("treats the cases route as a literal and never as a prefix", () => {
    // Every one of these shares the prefix and none of them is a route this
    // application has. There is no case detail route at all, so admitting a
    // segment under `/cases/` would be a redirect to a 404 at best.
    for (const suffix of [
      "/",
      "/1",
      "/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
      "/../health",
      "//evil.example",
      "\\evil.example",
      "x",
      "?status=OPEN",
      "#content",
    ]) {
      expect(resolveReturnRoute(`/cases${suffix}`)).toBe("/");
    }
  });

  it("does not readmit a cases address by stripping its query or fragment", () => {
    // The whole location is judged, so an address that would become `/cases`
    // after a repair is refused as written - and the repair really would have
    // produced an admitted route.
    for (const value of ["/cases?status=OPEN", "/cases#content", "/cases?status=OPEN#content"]) {
      expect(resolveReturnRoute(value)).toBe("/");
      expect(resolveReturnRoute(value.split(/[?#]/)[0])).toBe("/cases");
    }
  });

  it("returns the cases literal itself rather than a value built from the input", () => {
    const hostile = "https://evil.example/cases?token=hunter2#x";
    const resolved: string = resolveReturnRoute(hostile);

    expect(resolved).toBe("/");
    expect(resolved).not.toContain("evil.example");
    expect(resolved).not.toContain("hunter2");
  });

  it("treats the transactions route as a literal and never as a prefix", () => {
    // Every one of these shares the prefix and none of them is a route this
    // application has, so a prefix match here would be a redirect to nowhere at
    // best and an open redirect at worst.
    for (const suffix of ["/", "/1", "/../health", "//evil.example", "\\evil.example"]) {
      expect(resolveReturnRoute(`/transactions${suffix}`)).toBe("/");
    }
  });
});

/**
 * The one parameterized destination.
 *
 * `/transactions` stays a literal; what is added here is a *shape* - one path
 * segment that is already a canonical lowercase UUID v4 - and nothing that
 * shares a prefix with it. Each rejected value below is a real attempt at the
 * usual ways an allowlist built on `startsWith`, `includes`, a permissive
 * pattern, a decode or a trim is walked past.
 */
describe("resolveReturnRoute for the transaction detail route", () => {
  const CANONICAL = "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";

  it("allows a canonical transaction detail route", () => {
    expect(resolveReturnRoute(CANONICAL)).toBe(CANONICAL);
  });

  it("allows every canonical variant character the format admits", () => {
    for (const id of [
      "00000000-0000-4000-8000-000000000000",
      "ffffffff-ffff-4fff-bfff-ffffffffffff",
      "3a1b2c3d-4e5f-4a6b-9c7d-9e0f1a2b3c4d",
      "3a1b2c3d-4e5f-4a6b-ac7d-9e0f1a2b3c4d",
    ]) {
      expect(resolveReturnRoute(`/transactions/${id}`)).toBe(`/transactions/${id}`);
    }
  });

  const rejectedDetail: Array<[string, unknown]> = [
    ["an uppercase UUID", "/transactions/2F4C0A4E-8A9D-4C2F-9A1B-7D6E5F430001"],
    ["a mixed-case UUID", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7D6E5F430001"],
    ["a version 1 UUID", "/transactions/2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001"],
    ["a version 3 UUID", "/transactions/2f4c0a4e-8a9d-3c2f-9a1b-7d6e5f430001"],
    ["a version 5 UUID", "/transactions/2f4c0a4e-8a9d-5c2f-9a1b-7d6e5f430001"],
    ["an invalid RFC variant nibble", "/transactions/2f4c0a4e-8a9d-4c2f-1a1b-7d6e5f430001"],
    ["a variant nibble of c", "/transactions/2f4c0a4e-8a9d-4c2f-ca1b-7d6e5f430001"],
    ["a UUID with no hyphens", "/transactions/2f4c0a4e8a9d4c2f9a1b7d6e5f430001"],
    ["a UUID one digit short", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f43000"],
    ["a UUID one digit long", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f4300011"],
    ["a trailing slash", `${CANONICAL}/`],
    ["a deeper path", `${CANONICAL}/evidence`],
    ["a query string", `${CANONICAL}?tab=raw`],
    ["a fragment", `${CANONICAL}#amount`],
    ["a semicolon parameter", `${CANONICAL};jsessionid=1`],
    ["a leading space", ` ${CANONICAL}`],
    ["a trailing space", `${CANONICAL} `],
    ["an inner space", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b 7d6e5f430001"],
    ["a tab character", `${CANONICAL}\t`],
    ["a newline", `${CANONICAL}\n`],
    ["a carriage return", `${CANONICAL}\r`],
    ["a null character", `${CANONICAL}\u0000`],
    ["an encoded slash", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001%2fedit"],
    ["an encoded backslash", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001%5cedit"],
    ["a percent-encoded first digit", "/transactions/%32f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["a double-encoded slash", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001%252f"],
    ["an encoded dot segment", "/transactions/%2e%2e/health"],
    ["a raw dot segment", "/transactions/../admin"],
    ["a duplicate separator", `/transactions//${"2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"}`],
    ["a leading duplicate separator", `/${CANONICAL}`],
    ["a backslash separator", "\\transactions\\2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["an absolute https URL", `https://evil.example${CANONICAL}`],
    ["an absolute http URL", `http://localhost:5173${CANONICAL}`],
    ["a protocol-relative URL", `//evil.example${CANONICAL}`],
    ["userinfo in an absolute URL", `https://user:pass@evil.example${CANONICAL}`],
    ["a different port on the same host", `http://localhost:8080${CANONICAL}`],
    ["a javascript scheme", `javascript:${CANONICAL}`],
    ["a prefix sibling", `/transactionsx/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001`],
    ["a path merely containing the route", `/x${CANONICAL}`],
    ["a different resource with the same shape", "/cases/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["a String object wrapping a canonical route", new String(CANONICAL)],
    ["an array wrapping a canonical route", [CANONICAL]],
    ["an object stringifying to a canonical route", { toString: () => CANONICAL }],
  ];

  it.each(rejectedDetail)("falls back to the default for %s", (_label, value) => {
    expect(resolveReturnRoute(value)).toBe(DEFAULT_RETURN_ROUTE);
  });

  it("never returns a value that is not rebuilt from a validated identifier", () => {
    // The returned string is assembled here from the thirty-six validated
    // characters, so a value that merely contains a canonical route cannot
    // carry anything of its own through.
    const hostile = `https://evil.example${CANONICAL}?token=hunter2#x`;
    const resolved: string = resolveReturnRoute(hostile);

    expect(resolved).toBe("/");
    expect(resolved).not.toContain("evil.example");
    expect(resolved).not.toContain("hunter2");
  });

  it("does not admit a route on the strength of decoding or trimming it", () => {
    // Each of these becomes the canonical route once it is decoded, trimmed or
    // both - which is exactly why neither is done. The value is judged as
    // written, so all three are refused even though the "repaired" form of each
    // one is a route this allowlist does admit.
    const repairable = [
      ` ${CANONICAL} `,
      "/transactions/%32f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001",
      " /transactions/%32f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001 ",
    ];
    for (const value of repairable) {
      expect(resolveReturnRoute(value)).toBe("/");
      // The repair really would have produced an admitted route.
      expect(resolveReturnRoute(decodeURIComponent(value.trim()))).toBe(CANONICAL);
    }
  });
});
