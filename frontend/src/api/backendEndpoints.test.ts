import { describe, expect, it } from "vitest";
import {
  BACKEND_ENDPOINT_KEYS,
  buildBackendRequestUrl,
  getBackendEndpoint,
  isCanonicalUuidV4,
  findApprovedBackendRequest,
  isExactBackendUrl,
  type BackendEndpointDescriptor,
} from "./backendEndpoints";
import { RequestNotAllowedError } from "./errors";

const BASE = "http://localhost:8080";
const CASE_ID = "20000000-0000-4000-9000-000000000003";
const TRANSACTION_ID = "11111111-2222-4333-8444-555555555555";
/** Contains hex letters, so case sensitivity is actually observable. */
const LETTERED_ID = "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f";

/**
 * The Backend production matrix for a signed-in USER, transcribed from
 * `FinGuardOpsSecurityConfiguration` and security-architecture.md section 5.
 * The SERVICE ingestion endpoints and the public health path are absent by
 * design and are asserted absent below.
 */
const EXPECTED: ReadonlyArray<{ key: string; method: string; template: string }> = [
  { key: "transaction-list", method: "GET", template: "/api/v1/transactions" },
  { key: "transaction-detail", method: "GET", template: "/api/v1/transactions/{transactionId}" },
  { key: "case-list", method: "GET", template: "/api/v1/cases" },
  { key: "case-detail", method: "GET", template: "/api/v1/cases/{caseId}" },
  { key: "case-note-list", method: "GET", template: "/api/v1/cases/{caseId}/notes" },
  { key: "case-audit-list", method: "GET", template: "/api/v1/cases/{caseId}/audit-logs" },
  { key: "case-status-change", method: "PATCH", template: "/api/v1/cases/{caseId}/status" },
  { key: "case-assignee-change", method: "PATCH", template: "/api/v1/cases/{caseId}/assignee" },
  { key: "case-resolution-create", method: "POST", template: "/api/v1/cases/{caseId}/resolution" },
  { key: "case-note-create", method: "POST", template: "/api/v1/cases/{caseId}/notes" },
];

function paramsFor(descriptor: BackendEndpointDescriptor): Record<string, string> {
  const params: Record<string, string> = {};
  for (const name of descriptor.paramNames) {
    params[name] = name === "caseId" ? CASE_ID : TRANSACTION_ID;
  }
  return params;
}

describe("endpoint registry — exact method and path matrix", () => {
  it("contains exactly the ten approved USER endpoints", () => {
    expect(BACKEND_ENDPOINT_KEYS).toHaveLength(10);
    expect([...BACKEND_ENDPOINT_KEYS].sort()).toEqual(EXPECTED.map((e) => e.key).sort());
  });

  it("maps each key to exactly one approved method and template", () => {
    for (const expected of EXPECTED) {
      const descriptor = getBackendEndpoint(expected.key);
      expect(descriptor?.method).toBe(expected.method);
      expect(descriptor?.template).toBe(expected.template);
    }
  });

  it("has no duplicate key, and no duplicate method+template pair", () => {
    const keys = BACKEND_ENDPOINT_KEYS.map(String);
    expect(new Set(keys).size).toBe(keys.length);

    const pairs = BACKEND_ENDPOINT_KEYS.map((key) => {
      const descriptor = getBackendEndpoint(key);
      return `${descriptor?.method} ${descriptor?.template}`;
    });
    expect(new Set(pairs).size).toBe(pairs.length);
  });

  it("uses only GET, PATCH and POST", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      expect(["GET", "PATCH", "POST"]).toContain(getBackendEndpoint(key)?.method);
    }
  });

  it("allows a JSON body for PATCH and POST only", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      const descriptor = getBackendEndpoint(key);
      expect(descriptor?.acceptsJsonBody).toBe(descriptor?.method !== "GET");
    }
  });

  it("excludes the public health path", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      expect(getBackendEndpoint(key)?.template).not.toBe("/api/health");
    }
  });

  it("excludes the SERVICE ingestion endpoints", () => {
    const serviceOnly = [
      "POST /api/v1/transactions",
      "POST /api/v1/behavior-events",
      "GET /api/v1/behavior-events",
    ];
    const registered = BACKEND_ENDPOINT_KEYS.map((key) => {
      const descriptor = getBackendEndpoint(key);
      return `${descriptor?.method} ${descriptor?.template}`;
    });
    for (const entry of serviceOnly) {
      expect(registered).not.toContain(entry);
    }
  });

  it("excludes actuator, management and documentation-only candidate paths", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      const template = getBackendEndpoint(key)?.template ?? "";
      expect(template.startsWith("/actuator")).toBe(false);
      expect(template).not.toContain("ai-report");
      expect(template).not.toContain("detection-results");
      expect(template).not.toBe("/api/v1/cases/{caseId}/transactions");
    }
  });
});

describe("endpoint registry — unknown keys", () => {
  it("returns undefined for a key that is not registered", () => {
    expect(getBackendEndpoint("transaction-delete")).toBeUndefined();
    expect(getBackendEndpoint("")).toBeUndefined();
    expect(getBackendEndpoint("/api/v1/transactions")).toBeUndefined();
  });

  it("does not resolve inherited Object properties as endpoints", () => {
    for (const key of ["constructor", "toString", "hasOwnProperty", "__proto__", "valueOf"]) {
      expect(getBackendEndpoint(key)).toBeUndefined();
    }
  });

  it("refuses to build a URL for an unknown key", () => {
    expect(() => buildBackendRequestUrl(BASE, "case-delete", { caseId: CASE_ID })).toThrow(
      RequestNotAllowedError,
    );
    expect(() => buildBackendRequestUrl(BASE, "constructor")).toThrow(RequestNotAllowedError);
  });
});

describe("isCanonicalUuidV4", () => {
  it("accepts a canonical lowercase v4 identifier", () => {
    expect(isCanonicalUuidV4(CASE_ID)).toBe(true);
    expect(isCanonicalUuidV4(TRANSACTION_ID)).toBe(true);
  });

  it("accepts every RFC 4122 variant nibble", () => {
    for (const variant of ["8", "9", "a", "b"]) {
      expect(isCanonicalUuidV4(`11111111-2222-4333-${variant}444-555555555555`)).toBe(true);
    }
  });

  it("rejects uppercase, other versions and other variants", () => {
    expect(isCanonicalUuidV4(LETTERED_ID)).toBe(true);
    expect(isCanonicalUuidV4(LETTERED_ID.toUpperCase())).toBe(false);
    expect(isCanonicalUuidV4("6F1E0B6C-3a2b-4c8d-9e0f-1a2b3c4d5e6f")).toBe(false);
    expect(isCanonicalUuidV4("11111111-2222-1333-8444-555555555555")).toBe(false);
    expect(isCanonicalUuidV4("11111111-2222-4333-c444-555555555555")).toBe(false);
  });

  it("rejects blanks, whitespace, prefixes and suffixes", () => {
    expect(isCanonicalUuidV4("")).toBe(false);
    expect(isCanonicalUuidV4("   ")).toBe(false);
    expect(isCanonicalUuidV4(` ${CASE_ID}`)).toBe(false);
    expect(isCanonicalUuidV4(`${CASE_ID} `)).toBe(false);
    expect(isCanonicalUuidV4(`x${CASE_ID}`)).toBe(false);
    expect(isCanonicalUuidV4(`${CASE_ID}x`)).toBe(false);
    expect(isCanonicalUuidV4(`${CASE_ID}\n`)).toBe(false);
  });
});

describe("URL assembly — approved requests", () => {
  it("builds the exact URL for every approved endpoint", () => {
    const built = BACKEND_ENDPOINT_KEYS.map((key) => {
      const descriptor = getBackendEndpoint(key);
      if (descriptor === undefined) {
        throw new Error("registry lookup failed");
      }
      return buildBackendRequestUrl(BASE, key, paramsFor(descriptor)).url;
    });

    expect(built).toEqual([
      "http://localhost:8080/api/v1/transactions",
      `http://localhost:8080/api/v1/transactions/${TRANSACTION_ID}`,
      "http://localhost:8080/api/v1/cases",
      `http://localhost:8080/api/v1/cases/${CASE_ID}`,
      `http://localhost:8080/api/v1/cases/${CASE_ID}/notes`,
      `http://localhost:8080/api/v1/cases/${CASE_ID}/audit-logs`,
      `http://localhost:8080/api/v1/cases/${CASE_ID}/status`,
      `http://localhost:8080/api/v1/cases/${CASE_ID}/assignee`,
      `http://localhost:8080/api/v1/cases/${CASE_ID}/resolution`,
      `http://localhost:8080/api/v1/cases/${CASE_ID}/notes`,
    ]);
  });

  it("never produces a query string, fragment or trailing slash", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      const descriptor = getBackendEndpoint(key);
      if (descriptor === undefined) {
        throw new Error("registry lookup failed");
      }
      const { url } = buildBackendRequestUrl(BASE, key, paramsFor(descriptor));
      expect(url).not.toContain("?");
      expect(url).not.toContain("#");
      expect(url.endsWith("/")).toBe(false);
      expect(new URL(url).search).toBe("");
      expect(new URL(url).hash).toBe("");
    }
  });

  it("preserves an operator-configured base path prefix exactly", () => {
    const { url } = buildBackendRequestUrl("https://gateway.example/finguard", "case-detail", {
      caseId: CASE_ID,
    });

    expect(url).toBe(`https://gateway.example/finguard/api/v1/cases/${CASE_ID}`);
  });

  it("does not double a slash when the base path ends with one", () => {
    const { url } = buildBackendRequestUrl("https://gateway.example/finguard/", "case-list");

    expect(url).toBe("https://gateway.example/finguard/api/v1/cases");
  });

  it("keeps a non-default port in the assembled URL", () => {
    const { url } = buildBackendRequestUrl("https://backend.internal:9443", "case-list");

    expect(url).toBe("https://backend.internal:9443/api/v1/cases");
  });
});

describe("URL assembly — path parameter bypass attempts", () => {
  const HOSTILE = [
    "..",
    "../..",
    "../../actuator/prometheus",
    "%2e%2e",
    "%2e%2e%2f",
    "%2F",
    `${CASE_ID}%2Fnotes`,
    `${CASE_ID}/../../actuator`,
    `${CASE_ID}\\..\\actuator`,
    `${CASE_ID}/`,
    `/${CASE_ID}`,
    `${CASE_ID};jsessionid=abc`,
    `${CASE_ID}?page=1`,
    `${CASE_ID}#fragment`,
    `${CASE_ID}%25`,
    "//evil.example",
    "http://evil.example",
    "https://user:pass@evil.example",
    "\\\\evil.example\\share",
    `${CASE_ID}\nX-Injected: 1`,
    "",
    " ",
    LETTERED_ID.toUpperCase(),
  ];

  it("refuses every hostile path parameter value", () => {
    for (const caseId of HOSTILE) {
      expect(() => buildBackendRequestUrl(BASE, "case-detail", { caseId })).toThrow(
        RequestNotAllowedError,
      );
    }
  });

  it("refuses a missing required path parameter", () => {
    expect(() => buildBackendRequestUrl(BASE, "case-detail")).toThrow(RequestNotAllowedError);
    expect(() => buildBackendRequestUrl(BASE, "case-detail", {})).toThrow(RequestNotAllowedError);
  });

  it("refuses a parameter this endpoint does not have", () => {
    expect(() =>
      buildBackendRequestUrl(BASE, "case-detail", { caseId: CASE_ID, transactionId: TRANSACTION_ID }),
    ).toThrow(RequestNotAllowedError);
    expect(() => buildBackendRequestUrl(BASE, "case-list", { caseId: CASE_ID })).toThrow(
      RequestNotAllowedError,
    );
  });

  it("refuses a non-string parameter value arriving from untyped data", () => {
    const params = { caseId: 42 } as unknown as Record<string, string>;

    expect(() => buildBackendRequestUrl(BASE, "case-detail", params)).toThrow(
      RequestNotAllowedError,
    );
  });

  it("does not resolve an inherited property as a path parameter value", () => {
    const params = Object.create({ caseId: CASE_ID }) as Record<string, string>;

    expect(() => buildBackendRequestUrl(BASE, "case-detail", params)).toThrow(
      RequestNotAllowedError,
    );
  });
});

describe("URL assembly — base URL bypass attempts", () => {
  it("refuses a base URL that is not http or https", () => {
    for (const base of ["ftp://backend.example", "javascript:alert(1)", "data:text/plain,x"]) {
      expect(() => buildBackendRequestUrl(base, "case-list")).toThrow(RequestNotAllowedError);
    }
  });

  it("refuses a base URL carrying userinfo, a query or a fragment", () => {
    for (const base of [
      "http://user:pass@localhost:8080",
      "http://user@localhost:8080",
      "http://localhost:8080?next=x",
      "http://localhost:8080#x",
    ]) {
      expect(() => buildBackendRequestUrl(base, "case-list")).toThrow(RequestNotAllowedError);
    }
  });

  it("refuses a base path that still carries percent-encoding after parsing", () => {
    for (const base of ["http://localhost:8080/a%2Fb", "http://localhost:8080/a%2e%2e"]) {
      expect(() => buildBackendRequestUrl(base, "case-list")).toThrow(RequestNotAllowedError);
    }
  });

  /**
   * Dot segments the URL parser can resolve are resolved before this module
   * sees them, so the assembled URL is exact rather than traversable. This
   * records that behaviour rather than asserting a rejection that would not
   * happen.
   */
  it("assembles from the parser-normalized base path when dot segments resolve", () => {
    expect(buildBackendRequestUrl("http://localhost:8080/base/..", "case-list").url).toBe(
      "http://localhost:8080/api/v1/cases",
    );
    expect(buildBackendRequestUrl("http://localhost:8080/a/%2e%2e/b", "case-list").url).toBe(
      "http://localhost:8080/b/api/v1/cases",
    );
  });

  it("refuses an unparseable base URL", () => {
    for (const base of ["", "not a url", "//localhost:8080"]) {
      expect(() => buildBackendRequestUrl(base, "case-list")).toThrow(RequestNotAllowedError);
    }
  });

  /**
   * A `startsWith` origin check would accept the first three of these, and a
   * substring check would accept all of them. Exact comparison is what makes
   * them unreachable.
   */
  it("never assembles a URL on a host that merely resembles the configured one", () => {
    const { url } = buildBackendRequestUrl(BASE, "case-list");
    expect(url).toBe("http://localhost:8080/api/v1/cases");

    for (const impostor of [
      "http://localhost:8080.evil.example",
      "http://localhost:8080@evil.example",
      "http://localhost:80800",
      "http://evil.example/http://localhost:8080",
    ]) {
      const built = (() => {
        try {
          return buildBackendRequestUrl(impostor, "case-list").url;
        } catch {
          return undefined;
        }
      })();
      expect(built).not.toBe(url);
      if (built !== undefined) {
        expect(new URL(built).origin).not.toBe("http://localhost:8080");
      }
    }
  });
});

/**
 * The boundary's last check, exercised directly against near-miss URLs. Every
 * case below shares a prefix with the intended value, so a `startsWith` or
 * substring comparison would accept it.
 */
describe("isExactBackendUrl", () => {
  const ORIGIN = "http://localhost:8080";
  const PATHNAME = "/api/v1/cases";
  const EXACT = `${ORIGIN}${PATHNAME}`;

  it("accepts only the intended URL", () => {
    expect(isExactBackendUrl(EXACT, ORIGIN, PATHNAME)).toBe(true);
  });

  it("rejects a host that merely starts with the expected origin", () => {
    for (const candidate of [
      "http://localhost:8080.evil.example/api/v1/cases",
      "http://localhost:80800/api/v1/cases",
      "http://localhost:8080x/api/v1/cases",
    ]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME)).toBe(false);
    }
  });

  it("rejects a different scheme or host entirely", () => {
    for (const candidate of [
      "https://localhost:8080/api/v1/cases",
      "http://evil.example/api/v1/cases",
      "http://localhost:9090/api/v1/cases",
    ]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME)).toBe(false);
    }
  });

  it("rejects a path that merely starts with the expected pathname", () => {
    for (const candidate of [
      `${ORIGIN}/api/v1/cases/extra`,
      `${ORIGIN}/api/v1/cases-archive`,
      `${ORIGIN}/api/v1/casesx`,
      `${ORIGIN}/api/v1/cases/`,
    ]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME)).toBe(false);
    }
  });

  it("rejects a path that is only a prefix of the expected pathname", () => {
    expect(isExactBackendUrl(`${ORIGIN}/api`, ORIGIN, PATHNAME)).toBe(false);
    expect(isExactBackendUrl(`${ORIGIN}/`, ORIGIN, PATHNAME)).toBe(false);
  });

  it("rejects userinfo, a query string and a fragment", () => {
    for (const candidate of [
      "http://user:pass@localhost:8080/api/v1/cases",
      "http://user@localhost:8080/api/v1/cases",
      `${EXACT}?page=0`,
      `${EXACT}#top`,
    ]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME)).toBe(false);
    }
  });

  it("rejects an unparseable candidate", () => {
    for (const candidate of ["", "not a url", "//localhost:8080/api/v1/cases"]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME)).toBe(false);
    }
  });
});

/**
 * The check used by code that holds a URL rather than an endpoint key — the
 * credential capability above all. It must reach the same verdict as the
 * builder without being told what was intended.
 */
describe("findApprovedBackendRequest", () => {
  it("approves every endpoint the builder can produce, with its own method", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      const descriptor = getBackendEndpoint(key);
      if (descriptor === undefined) {
        throw new Error("registry lookup failed");
      }
      const { url } = buildBackendRequestUrl(BASE, key, paramsFor(descriptor));

      expect(findApprovedBackendRequest(BASE, descriptor.method, url)?.key).toBe(key);
    }
  });

  it("approves an endpoint under a configured base path prefix", () => {
    const base = "https://gateway.example/finguard";
    const { url } = buildBackendRequestUrl(base, "case-detail", { caseId: CASE_ID });

    expect(findApprovedBackendRequest(base, "GET", url)?.key).toBe("case-detail");
  });

  it("refuses a base path prefix that only shares a text prefix", () => {
    const url = "https://gateway.example/finguardx/api/v1/cases";

    expect(findApprovedBackendRequest("https://gateway.example/finguard", "GET", url)).toBeUndefined();
  });

  it("refuses the right path with the wrong method", () => {
    const detail = `${BASE}/api/v1/cases/${CASE_ID}`;

    expect(findApprovedBackendRequest(BASE, "GET", detail)?.key).toBe("case-detail");
    for (const method of ["POST", "PATCH", "PUT", "DELETE", "HEAD", "OPTIONS", "get"]) {
      expect(findApprovedBackendRequest(BASE, method, detail)).toBeUndefined();
    }
  });

  it("refuses the public health path", () => {
    expect(findApprovedBackendRequest(BASE, "GET", `${BASE}/api/health`)).toBeUndefined();
  });

  it("refuses the SERVICE ingestion endpoints", () => {
    expect(
      findApprovedBackendRequest(BASE, "POST", `${BASE}/api/v1/transactions`),
    ).toBeUndefined();
    expect(
      findApprovedBackendRequest(BASE, "POST", `${BASE}/api/v1/behavior-events`),
    ).toBeUndefined();
    expect(findApprovedBackendRequest(BASE, "GET", `${BASE}/api/v1/behavior-events`)).toBeUndefined();
  });

  it("refuses actuator, management, AI and observability destinations", () => {
    for (const [method, url] of [
      ["GET", `${BASE}/actuator/health`],
      ["GET", `${BASE}/actuator/prometheus`],
      ["GET", "http://localhost:8081/actuator/health"],
      ["POST", "http://localhost:8000/api/v1/scoring"],
      ["GET", "http://prometheus:9090/api/v1/query"],
      ["GET", "http://grafana:3000/api/dashboards"],
      ["GET", "http://alertmanager:9093/api/v2/alerts"],
      ["POST", "https://risk-provider.example/score"],
    ] as ReadonlyArray<[string, string]>) {
      expect(findApprovedBackendRequest(BASE, method, url)).toBeUndefined();
    }
  });

  it("refuses external and look-alike origins", () => {
    for (const url of [
      "https://evil.example/api/v1/cases",
      "https://evil.example/collect",
      "http://localhost.evil.example:8080/api/v1/cases",
      "http://evil.localhost:8080/api/v1/cases",
      "http://localhost:9090/api/v1/cases",
      "https://localhost:8080/api/v1/cases",
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url)).toBeUndefined();
    }
  });

  it("refuses a query string, a fragment, userinfo or a trailing slash", () => {
    for (const url of [
      `${BASE}/api/v1/cases?page=0`,
      `${BASE}/api/v1/cases#top`,
      `${BASE}/api/v1/cases/`,
      `${BASE}/api/v1/cases/${CASE_ID}/`,
      `http://user:pass@localhost:8080/api/v1/cases`,
      `http://user@localhost:8080/api/v1/cases`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url)).toBeUndefined();
    }
  });

  it("refuses encoded separators, traversal and matrix parameters", () => {
    for (const url of [
      `${BASE}/api/v1/cases/${CASE_ID}%2Fnotes`,
      `${BASE}/api/v1/cases/%2e%2e/actuator`,
      `${BASE}/api/v1/cases/${CASE_ID}%25`,
      `${BASE}/api/v1/cases/${CASE_ID};jsessionid=abc`,
      `${BASE}/api/v1//cases`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url)).toBeUndefined();
    }
  });

  it("refuses a path parameter that is not a canonical lowercase UUID v4", () => {
    for (const value of [
      LETTERED_ID.toUpperCase(),
      "11111111-2222-1333-8444-555555555555",
      "11111111-2222-4333-c444-555555555555",
      "not-a-uuid",
      "",
    ]) {
      expect(
        findApprovedBackendRequest(BASE, "GET", `${BASE}/api/v1/cases/${value}`),
      ).toBeUndefined();
    }
  });

  it("refuses an unparseable URL or an unusable base URL", () => {
    expect(findApprovedBackendRequest(BASE, "GET", "not a url")).toBeUndefined();
    expect(findApprovedBackendRequest(BASE, "GET", "")).toBeUndefined();
    expect(findApprovedBackendRequest("not a url", "GET", `${BASE}/api/v1/cases`)).toBeUndefined();
    expect(findApprovedBackendRequest("", "GET", `${BASE}/api/v1/cases`)).toBeUndefined();
  });

  it("refuses a path with the wrong number of segments", () => {
    for (const url of [
      `${BASE}/api/v1`,
      `${BASE}/api/v1/cases/${CASE_ID}/notes/extra`,
      `${BASE}/api/v1/cases/${CASE_ID}/unknown`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url)).toBeUndefined();
    }
  });
});
