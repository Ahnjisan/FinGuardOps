import { describe, expect, it } from "vitest";
import {
  BACKEND_ENDPOINT_KEYS,
  buildBackendRequestUrl,
  getBackendEndpoint,
  isApprovedQueryValue,
  isStructurallyAllowedQueryValue,
  isCanonicalUuidV4,
  findApprovedBackendRequest,
  isExactBackendUrl,
  getQueryRule,
  isApprovedQuerySet,
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
    expect(isExactBackendUrl(EXACT, ORIGIN, PATHNAME, "")).toBe(true);
  });

  it("rejects a host that merely starts with the expected origin", () => {
    for (const candidate of [
      "http://localhost:8080.evil.example/api/v1/cases",
      "http://localhost:80800/api/v1/cases",
      "http://localhost:8080x/api/v1/cases",
    ]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME, "")).toBe(false);
    }
  });

  it("rejects a different scheme or host entirely", () => {
    for (const candidate of [
      "https://localhost:8080/api/v1/cases",
      "http://evil.example/api/v1/cases",
      "http://localhost:9090/api/v1/cases",
    ]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME, "")).toBe(false);
    }
  });

  it("rejects a path that merely starts with the expected pathname", () => {
    for (const candidate of [
      `${ORIGIN}/api/v1/cases/extra`,
      `${ORIGIN}/api/v1/cases-archive`,
      `${ORIGIN}/api/v1/casesx`,
      `${ORIGIN}/api/v1/cases/`,
    ]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME, "")).toBe(false);
    }
  });

  it("rejects a path that is only a prefix of the expected pathname", () => {
    expect(isExactBackendUrl(`${ORIGIN}/api`, ORIGIN, PATHNAME, "")).toBe(false);
    expect(isExactBackendUrl(`${ORIGIN}/`, ORIGIN, PATHNAME, "")).toBe(false);
  });

  it("rejects userinfo, a query string and a fragment", () => {
    for (const candidate of [
      "http://user:pass@localhost:8080/api/v1/cases",
      "http://user@localhost:8080/api/v1/cases",
      `${EXACT}?page=0`,
      `${EXACT}#top`,
    ]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME, "")).toBe(false);
    }
  });

  it("rejects an unparseable candidate", () => {
    for (const candidate of ["", "not a url", "//localhost:8080/api/v1/cases"]) {
      expect(isExactBackendUrl(candidate, ORIGIN, PATHNAME, "")).toBe(false);
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

  it("refuses a fragment, userinfo or a trailing slash", () => {
    for (const url of [
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

/**
 * The registry's query half. Only four endpoints declare a query at all, and
 * every one of those declarations is transcribed from a Backend validator.
 */
describe("endpoint registry — declared query parameters", () => {
  const DECLARED: Readonly<Record<string, readonly string[]>> = {
    "transaction-list": [
      "occurredAtFrom",
      "occurredAtTo",
      "transactionType",
      "processingStatus",
      "externalCustomerRef",
      "accountRef",
      "page",
      "size",
      "sort",
    ],
    "case-list": [
      "caseStatus",
      "finalDisposition",
      "assigneeRef",
      "createdAtFrom",
      "createdAtTo",
      "lastChangedAtFrom",
      "lastChangedAtTo",
      "transactionId",
      "page",
      "size",
      "sort",
    ],
    "case-note-list": ["page", "size", "sort"],
    "case-audit-list": ["page", "size", "sort"],
    "transaction-detail": [],
    "case-detail": [],
    "case-status-change": [],
    "case-assignee-change": [],
    "case-resolution-create": [],
    "case-note-create": [],
  };

  it("declares exactly the Backend-approved names, in emission order", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      expect(getBackendEndpoint(key)?.queryParamNames).toEqual(DECLARED[key]);
    }
  });

  it("gives every detail and write endpoint no query at all", () => {
    for (const key of [
      "transaction-detail",
      "case-detail",
      "case-status-change",
      "case-assignee-change",
      "case-resolution-create",
      "case-note-create",
    ]) {
      expect(getBackendEndpoint(key)?.queryParamNames).toHaveLength(0);
    }
  });

  it("declares no name more than once, and none outside the list endpoints", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      const descriptor = getBackendEndpoint(key);
      const names = descriptor?.queryParamNames ?? [];
      expect(new Set(names).size).toBe(names.length);
      if (names.length > 0) {
        expect(descriptor?.method).toBe("GET");
      }
    }
  });
});

describe("buildBackendRequestUrl — canonical query", () => {
  it("emits declared order, not caller key order", () => {
    const { url } = buildBackendRequestUrl(BASE, "transaction-list", undefined, {
      sort: "occurredAt,desc",
      page: "0",
      size: "20",
      transactionType: "ACCOUNT_TRANSFER",
    });
    expect(url).toBe(
      `${BASE}/api/v1/transactions` +
        "?transactionType=ACCOUNT_TRANSFER&page=0&size=20&sort=occurredAt%2Cdesc",
    );
  });

  it("produces one URL no matter which key order the caller used", () => {
    const first = buildBackendRequestUrl(BASE, "case-list", undefined, {
      page: "3",
      caseStatus: "IN_REVIEW",
    }).url;
    const second = buildBackendRequestUrl(BASE, "case-list", undefined, {
      caseStatus: "IN_REVIEW",
      page: "3",
    }).url;
    expect(first).toBe(second);
    expect(first).toBe(`${BASE}/api/v1/cases?caseStatus=IN_REVIEW&page=3`);
  });

  it("omits the question mark entirely when no query is supplied or none is set", () => {
    expect(buildBackendRequestUrl(BASE, "case-list").url).toBe(`${BASE}/api/v1/cases`);
    expect(buildBackendRequestUrl(BASE, "case-list", undefined, {}).url).toBe(
      `${BASE}/api/v1/cases`,
    );
  });

  /**
   * The injection case that matters: an opaque operator reference is data, and
   * a separator inside it has to stay inside it.
   */
  it("encodes separators inside an opaque reference rather than letting them become structure", () => {
    for (const hostile of [
      "acct&page=99",
      "acct=evil",
      "acct#fragment",
      "acct%2Fnotes",
      "acct?page=1",
      "acct value",
      "acct+plus",
    ]) {
      const { url } = buildBackendRequestUrl(BASE, "transaction-list", undefined, {
        accountRef: hostile,
        page: "0",
      });
      const parsed = new URL(url);
      expect(parsed.hash).toBe("");
      expect(parsed.pathname).toBe("/api/v1/transactions");
      expect([...parsed.searchParams.keys()].sort()).toEqual(["accountRef", "page"]);
      expect(parsed.searchParams.get("accountRef")).toBe(hostile);
      expect(parsed.searchParams.get("page")).toBe("0");
      expect(findApprovedBackendRequest(BASE, "GET", url)?.key).toBe("transaction-list");
    }
  });

  it("refuses a name the endpoint does not declare", () => {
    expect(() =>
      buildBackendRequestUrl(BASE, "case-list", undefined, {
        occurredAtFrom: "2026-07-23T00:00:00Z",
      }),
    ).toThrow(RequestNotAllowedError);
    expect(() =>
      buildBackendRequestUrl(BASE, "case-note-list", { caseId: CASE_ID }, { caseStatus: "OPEN" }),
    ).toThrow(RequestNotAllowedError);
  });

  it("refuses a query on a detail or write endpoint", () => {
    for (const [key, params] of [
      ["transaction-detail", { transactionId: TRANSACTION_ID }],
      ["case-detail", { caseId: CASE_ID }],
      ["case-status-change", { caseId: CASE_ID }],
      ["case-assignee-change", { caseId: CASE_ID }],
      ["case-resolution-create", { caseId: CASE_ID }],
      ["case-note-create", { caseId: CASE_ID }],
    ] as ReadonlyArray<[string, Record<string, string>]>) {
      expect(() => buildBackendRequestUrl(BASE, key, params, { page: "0" })).toThrow(
        RequestNotAllowedError,
      );
      // An empty object is refused too: passing one means the caller believes
      // this endpoint filters something.
      expect(() => buildBackendRequestUrl(BASE, key, params, {})).toThrow(
        RequestNotAllowedError,
      );
      // Passing nothing is still how these endpoints are built.
      expect(buildBackendRequestUrl(BASE, key, params).url).not.toContain("?");
    }
  });

  it("never lets an inherited, non-enumerable or symbol-keyed property become a parameter", () => {
    // A prototype-bearing container is refused outright, so an inherited
    // `page` never reaches the URL and never silently disappears either.
    const inherited = Object.create({ page: "0" }) as Record<string, string>;
    expect(() => buildBackendRequestUrl(BASE, "case-list", undefined, inherited)).toThrow(
      RequestNotAllowedError,
    );

    const nonEnumerable = {};
    Object.defineProperty(nonEnumerable, "unknownField", { value: "1", enumerable: false });
    expect(() =>
      buildBackendRequestUrl(BASE, "case-list", undefined, nonEnumerable as Record<string, string>),
    ).toThrow(RequestNotAllowedError);

    const symbolKeyed: Record<string, string> = {};
    (symbolKeyed as Record<symbol, string>)[Symbol("page")] = "0";
    expect(() => buildBackendRequestUrl(BASE, "case-list", undefined, symbolKeyed)).toThrow(
      RequestNotAllowedError,
    );
  });

  it("refuses a non-plain query container", () => {
    class QueryHolder {
      page = "0";
    }
    for (const query of [
      new QueryHolder() as unknown as Record<string, string>,
      [] as unknown as Record<string, string>,
      new URLSearchParams("page=0") as unknown as Record<string, string>,
    ]) {
      expect(() => buildBackendRequestUrl(BASE, "case-list", undefined, query)).toThrow(
        RequestNotAllowedError,
      );
    }
  });

  it("refuses a value that is empty, over-long, non-string or control-bearing", () => {
    for (const value of [
      "",
      "a".repeat(129),
      "line\nbreak",
      "tab\tvalue",
      "null\u0000byte",
      "\u007fdelete",
      "\u0085next",
    ]) {
      expect(() =>
        buildBackendRequestUrl(BASE, "case-list", undefined, { assigneeRef: value }),
      ).toThrow(RequestNotAllowedError);
    }
    for (const value of [0, null, true, ["a"], {}]) {
      expect(() =>
        buildBackendRequestUrl(BASE, "case-list", undefined, {
          assigneeRef: value as unknown as string,
        }),
      ).toThrow(RequestNotAllowedError);
    }
  });

  it("still refuses a non-canonical path parameter even when the query is valid", () => {
    expect(() =>
      buildBackendRequestUrl(
        BASE,
        "case-note-list",
        { caseId: LETTERED_ID.toUpperCase() },
        { page: "0" },
      ),
    ).toThrow(RequestNotAllowedError);
  });
});

describe("findApprovedBackendRequest — query re-verification", () => {
  it("approves a URL whose query is exactly what the canonical builder emits", () => {
    for (const key of ["transaction-list", "case-list", "case-note-list", "case-audit-list"]) {
      const descriptor = getBackendEndpoint(key);
      if (descriptor === undefined) {
        throw new Error("registry lookup failed");
      }
      const params: Record<string, string> = {};
      for (const name of descriptor.paramNames) {
        params[name] = name === "caseId" ? CASE_ID : TRANSACTION_ID;
      }
      const { url } = buildBackendRequestUrl(BASE, key, params, { page: "2", size: "50" });
      expect(findApprovedBackendRequest(BASE, "GET", url)?.key).toBe(key);
    }
  });

  it("refuses a duplicated query parameter", () => {
    for (const url of [
      `${BASE}/api/v1/cases?page=0&page=1`,
      `${BASE}/api/v1/cases?page=0&page=0`,
      `${BASE}/api/v1/cases?size=20&page=0&size=20`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url)).toBeUndefined();
    }
  });

  it("refuses an unknown query parameter, alone or alongside a valid one", () => {
    for (const url of [
      `${BASE}/api/v1/cases?unknown=1`,
      `${BASE}/api/v1/cases?page=0&unknown=1`,
      `${BASE}/api/v1/cases?occurredAtFrom=2026-07-23T00%3A00%3A00Z`,
      `${BASE}/api/v1/cases/${CASE_ID}/notes?caseStatus=OPEN`,
      `${BASE}/api/v1/cases?__proto__=1`,
      `${BASE}/api/v1/cases?constructor=1`,
      `${BASE}/api/v1/cases?toString=1`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url)).toBeUndefined();
    }
  });

  it("refuses a query on a detail or write endpoint", () => {
    for (const [method, url] of [
      ["GET", `${BASE}/api/v1/transactions/${TRANSACTION_ID}?page=0`],
      ["GET", `${BASE}/api/v1/cases/${CASE_ID}?page=0`],
      ["PATCH", `${BASE}/api/v1/cases/${CASE_ID}/status?page=0`],
      ["PATCH", `${BASE}/api/v1/cases/${CASE_ID}/assignee?page=0`],
      ["POST", `${BASE}/api/v1/cases/${CASE_ID}/resolution?page=0`],
      ["POST", `${BASE}/api/v1/cases/${CASE_ID}/notes?page=0`],
    ] as ReadonlyArray<[string, string]>) {
      expect(findApprovedBackendRequest(BASE, method, url)).toBeUndefined();
    }
  });

  it("refuses a non-canonical encoding that merely decodes to an approved value", () => {
    for (const url of [
      `${BASE}/api/v1/cases?assigneeRef=a%20b`,
      `${BASE}/api/v1/cases?%70age=0`,
      `${BASE}/api/v1/cases?page=%30`,
      `${BASE}/api/v1/cases?sort=lastChangedAt,desc`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url)).toBeUndefined();
    }
  });

  it("refuses an empty query string, an empty value and a valueless name", () => {
    for (const url of [
      `${BASE}/api/v1/cases?`,
      `${BASE}/api/v1/cases?page=`,
      `${BASE}/api/v1/cases?page`,
      `${BASE}/api/v1/cases?=0`,
      `${BASE}/api/v1/cases?&page=0`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url)).toBeUndefined();
    }
  });

  it("refuses an approved query that reaches an approved path with the wrong method", () => {
    expect(findApprovedBackendRequest(BASE, "POST", `${BASE}/api/v1/cases?page=0`)).toBeUndefined();
    expect(
      findApprovedBackendRequest(BASE, "DELETE", `${BASE}/api/v1/cases?page=0`),
    ).toBeUndefined();
  });

  it("keeps refusing a fragment even when the query is approved", () => {
    expect(
      findApprovedBackendRequest(BASE, "GET", `${BASE}/api/v1/cases?page=0#top`),
    ).toBeUndefined();
  });
});

/**
 * The descriptor owns the rule each value must satisfy, not only the name. The
 * cases below go straight at `findApprovedBackendRequest` with hand-made URLs,
 * which is the shape the transport and the credential capability actually see:
 * neither of them has an endpoint key or a typed builder in hand.
 */
describe("endpoint registry — per-value query rules", () => {
  it("declares a rule for every name it declares", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      const descriptor = getBackendEndpoint(key);
      if (descriptor === undefined) {
        throw new Error("registry lookup failed");
      }
      expect(descriptor.queryContract.map((entry) => entry.name)).toEqual(
        descriptor.queryParamNames,
      );
      for (const { name, rule } of descriptor.queryContract) {
        expect(getQueryRule(descriptor, name)).toBe(rule);
      }
      expect(getQueryRule(descriptor, "definitelyNotADeclaredName")).toBeUndefined();
    }
  });

  it("bounds page and size the way the Backend validators do", () => {
    const page = { kind: "page" } as const;
    const size = { kind: "size" } as const;

    for (const value of ["0", "1", "2147483647"]) {
      expect(isApprovedQueryValue(page, value)).toBe(true);
    }
    for (const value of ["-1", "-0", "2147483648", "9999999999", "00", "01", "0.5", "1e2", "", " 0", "0 ", "+1", "one"]) {
      expect(isApprovedQueryValue(page, value)).toBe(false);
    }

    for (const value of ["1", "20", "100"]) {
      expect(isApprovedQueryValue(size, value)).toBe(true);
    }
    for (const value of ["0", "101", "1000", "-1", "020", "1.5", ""]) {
      expect(isApprovedQueryValue(size, value)).toBe(false);
    }
  });

  it("applies the instant, uuid, reference and choice rules exactly", () => {
    expect(isApprovedQueryValue({ kind: "instant" }, "2026-07-23T00:00:00Z")).toBe(true);
    for (const value of ["2026-07-23T00:00:00+09:00", "2026-02-30T00:00:00Z", "2026-07-23"]) {
      expect(isApprovedQueryValue({ kind: "instant" }, value)).toBe(false);
    }

    expect(isApprovedQueryValue({ kind: "uuid" }, LETTERED_ID)).toBe(true);
    for (const value of [LETTERED_ID.toUpperCase(), "not-a-uuid", `${LETTERED_ID} `]) {
      expect(isApprovedQueryValue({ kind: "uuid" }, value)).toBe(false);
    }

    expect(isApprovedQueryValue({ kind: "transaction-ref" }, "acct_ref_demo_s91c")).toBe(true);
    expect(isApprovedQueryValue({ kind: "case-assignee-ref" }, "acct_ref_demo_s91c")).toBe(
      true,
    );

    // A transaction reference is bounded only by Java's `isBlank`.
    for (const value of ["", " ", "   ", "　"]) {
      expect(isApprovedQueryValue({ kind: "transaction-ref" }, value)).toBe(false);
    }
    for (const value of [" acct ", "a".repeat(129), " "]) {
      expect(isApprovedQueryValue({ kind: "transaction-ref" }, value)).toBe(true);
    }

    // The case assignee adds a length bound and a trim comparison, and the two
    // rules must disagree on exactly those.
    for (const value of [" acct ", "acct ", "a".repeat(129)]) {
      expect(isApprovedQueryValue({ kind: "case-assignee-ref" }, value)).toBe(false);
      expect(isApprovedQueryValue({ kind: "transaction-ref" }, value)).toBe(true);
    }

    const rule = { kind: "choice", allowed: ["OPEN", "CLOSED"] } as const;
    expect(isApprovedQueryValue(rule, "OPEN")).toBe(true);
    for (const value of ["open", " OPEN", "OPEN ", "UNKNOWN", ""]) {
      expect(isApprovedQueryValue(rule, value)).toBe(false);
    }
  });
});

describe("findApprovedBackendRequest — hand-crafted values, no builder involved", () => {
  /**
   * Each of these is a well-formed URL naming an approved endpoint with an
   * approved parameter *name*. Only the value is wrong, so nothing but the
   * endpoint's own rule can catch them.
   */
  const REFUSED_VALUES: readonly string[] = [
    `${BASE}/api/v1/cases?page=-1`,
    `${BASE}/api/v1/cases?page=-0`,
    `${BASE}/api/v1/cases?page=2147483648`,
    `${BASE}/api/v1/cases?page=00`,
    `${BASE}/api/v1/cases?page=1.5`,
    `${BASE}/api/v1/cases?size=0`,
    `${BASE}/api/v1/cases?size=101`,
    `${BASE}/api/v1/cases?size=1000`,
    `${BASE}/api/v1/cases?sort=createdAt%2Casc`,
    `${BASE}/api/v1/cases?sort=lastChangedAt%2CDESC`,
    `${BASE}/api/v1/cases?caseStatus=in_review`,
    `${BASE}/api/v1/cases?caseStatus=UNKNOWN`,
    `${BASE}/api/v1/cases?finalDisposition=normal`,
    `${BASE}/api/v1/cases?transactionId=not-a-uuid`,
    `${BASE}/api/v1/cases?transactionId=${LETTERED_ID.toUpperCase()}`,
    `${BASE}/api/v1/cases?createdAtFrom=2026-07-23`,
    `${BASE}/api/v1/cases?createdAtFrom=2026-02-30T00%3A00%3A00Z`,
    `${BASE}/api/v1/cases?assigneeRef=+padded`,
    `${BASE}/api/v1/transactions?transactionType=account_transfer`,
    `${BASE}/api/v1/transactions?processingStatus=held`,
    `${BASE}/api/v1/transactions?occurredAtFrom=2026-07-23T00%3A00%3A00%2B09%3A00`,
    `${BASE}/api/v1/transactions?sort=lastChangedAt%2Cdesc`,
    `${BASE}/api/v1/cases/${CASE_ID}/notes?sort=changedAt%2Casc`,
    `${BASE}/api/v1/cases/${CASE_ID}/notes?page=-1`,
    `${BASE}/api/v1/cases/${CASE_ID}/audit-logs?sort=createdAt%2Cdesc`,
    `${BASE}/api/v1/cases/${CASE_ID}/audit-logs?size=101`,
  ];

  it("refuses every hand-crafted URL whose value breaks its endpoint rule", () => {
    for (const url of REFUSED_VALUES) {
      expect(findApprovedBackendRequest(BASE, "GET", url), `expected refusal for ${url}`)
        .toBeUndefined();
    }
  });

  it("approves the corrected form of each of those, so the refusal is about the value", () => {
    for (const url of [
      `${BASE}/api/v1/cases?page=0`,
      `${BASE}/api/v1/cases?size=100`,
      `${BASE}/api/v1/cases?sort=lastChangedAt%2Cdesc`,
      `${BASE}/api/v1/cases?caseStatus=IN_REVIEW`,
      `${BASE}/api/v1/cases?finalDisposition=NORMAL`,
      `${BASE}/api/v1/cases?transactionId=${LETTERED_ID}`,
      `${BASE}/api/v1/cases?createdAtFrom=2026-07-23T00%3A00%3A00Z`,
      `${BASE}/api/v1/transactions?transactionType=ACCOUNT_TRANSFER`,
      `${BASE}/api/v1/transactions?processingStatus=HELD`,
      `${BASE}/api/v1/transactions?sort=occurredAt%2Cdesc`,
      `${BASE}/api/v1/cases/${CASE_ID}/notes?sort=createdAt%2Casc`,
      `${BASE}/api/v1/cases/${CASE_ID}/audit-logs?sort=changedAt%2Cdesc`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url), `expected approval for ${url}`)
        .toBeDefined();
    }
  });

  /**
   * The cross-value half of the contract, reached the way the transport and
   * the credential capability reach it: a finished URL, no endpoint key and no
   * typed builder in sight. Both bounds are individually valid instants, so
   * only a query-set check can refuse these.
   */
  it("refuses a hand-crafted inverted instant range", () => {
    for (const url of [
      `${BASE}/api/v1/transactions?occurredAtFrom=2026-07-24T00%3A00%3A00Z&occurredAtTo=2026-07-23T00%3A00%3A00Z`,
      `${BASE}/api/v1/cases?createdAtFrom=2026-07-24T00%3A00%3A00Z&createdAtTo=2026-07-23T00%3A00%3A00Z`,
      `${BASE}/api/v1/cases?lastChangedAtFrom=2026-07-24T00%3A00%3A00Z&lastChangedAtTo=2026-07-23T00%3A00%3A00Z`,
      // inverted only below millisecond resolution
      `${BASE}/api/v1/transactions?occurredAtFrom=2026-07-23T00%3A00%3A00.000000002Z&occurredAtTo=2026-07-23T00%3A00%3A00.000000001Z`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url), url).toBeUndefined();
    }
  });

  it("approves the same ranges the right way round, and either bound alone", () => {
    for (const url of [
      `${BASE}/api/v1/transactions?occurredAtFrom=2026-07-23T00%3A00%3A00Z&occurredAtTo=2026-07-24T00%3A00%3A00Z`,
      `${BASE}/api/v1/transactions?occurredAtFrom=2026-07-24T00%3A00%3A00Z`,
      `${BASE}/api/v1/transactions?occurredAtTo=2026-07-23T00%3A00%3A00Z`,
      `${BASE}/api/v1/cases?createdAtFrom=2026-07-23T00%3A00%3A00Z&createdAtTo=2026-07-23T00%3A00%3A00Z`,
      // the two case ranges are independent
      `${BASE}/api/v1/cases?createdAtFrom=2026-07-25T00%3A00%3A00Z&lastChangedAtTo=2026-07-23T00%3A00%3A00Z`,
    ]) {
      expect(findApprovedBackendRequest(BASE, "GET", url), url).toBeDefined();
    }
  });

  it("refuses a hand-crafted transaction reference only when Backend would", () => {
    // padded but non-blank: a legitimate exact-match filter, encoded verbatim
    expect(
      findApprovedBackendRequest(BASE, "GET", `${BASE}/api/v1/transactions?accountRef=+acct+`)
        ?.key,
    ).toBe("transaction-list");
    // blank: refused
    for (const encoded of ["+", "+++", "%e3%80%80"]) {
      expect(
        findApprovedBackendRequest(BASE, "GET", `${BASE}/api/v1/transactions?accountRef=${encoded}`),
      ).toBeUndefined();
    }
    // the case assignee keeps its own stricter bounds on the same shapes
    expect(
      findApprovedBackendRequest(BASE, "GET", `${BASE}/api/v1/cases?assigneeRef=+acct+`),
    ).toBeUndefined();
  });

  it("refuses a value carrying a control character even under an approved name", () => {
    for (const encoded of ["%00", "%0a", "%7f", "%c2%85"]) {
      expect(
        findApprovedBackendRequest(BASE, "GET", `${BASE}/api/v1/cases?assigneeRef=a${encoded}b`),
      ).toBeUndefined();
    }
  });
});

/**
 * The cross-value half of the contract, pinned at each place that runs it.
 * `buildBackendRequestUrl` is exercised with an already-stringified query, so it
 * is the canonical builder deciding here and not the typed builder above it.
 */
describe("endpoint registry — query-set range contract", () => {
  it("declares exactly the ranges the Backend validators check", () => {
    expect(getBackendEndpoint("transaction-list")?.queryRanges).toEqual([
      { from: "occurredAtFrom", to: "occurredAtTo" },
    ]);
    expect(getBackendEndpoint("case-list")?.queryRanges).toEqual([
      { from: "createdAtFrom", to: "createdAtTo" },
      { from: "lastChangedAtFrom", to: "lastChangedAtTo" },
    ]);
    for (const key of BACKEND_ENDPOINT_KEYS) {
      if (key !== "transaction-list" && key !== "case-list") {
        expect(getBackendEndpoint(key)?.queryRanges).toEqual([]);
      }
    }
  });

  it("orders each declared range, and leaves a half-open one alone", () => {
    const transactions = getBackendEndpoint("transaction-list");
    const cases = getBackendEndpoint("case-list");
    if (transactions === undefined || cases === undefined) {
      throw new Error("registry lookup failed");
    }
    const early = "2026-07-23T00:00:00Z";
    const late = "2026-07-24T00:00:00Z";

    expect(
      isApprovedQuerySet(transactions, { occurredAtFrom: early, occurredAtTo: late }),
    ).toBe(true);
    expect(
      isApprovedQuerySet(transactions, { occurredAtFrom: early, occurredAtTo: early }),
    ).toBe(true);
    expect(isApprovedQuerySet(transactions, { occurredAtFrom: late })).toBe(true);
    expect(isApprovedQuerySet(transactions, { occurredAtTo: early })).toBe(true);
    expect(isApprovedQuerySet(transactions, {})).toBe(true);

    expect(
      isApprovedQuerySet(transactions, { occurredAtFrom: late, occurredAtTo: early }),
    ).toBe(false);
    expect(isApprovedQuerySet(cases, { createdAtFrom: late, createdAtTo: early })).toBe(false);
    expect(
      isApprovedQuerySet(cases, { lastChangedAtFrom: late, lastChangedAtTo: early }),
    ).toBe(false);

    // the two case ranges do not borrow each other's bounds
    expect(isApprovedQuerySet(cases, { createdAtFrom: late, lastChangedAtTo: early })).toBe(
      true,
    );
  });

  it("orders below millisecond resolution", () => {
    const transactions = getBackendEndpoint("transaction-list");
    if (transactions === undefined) {
      throw new Error("registry lookup failed");
    }
    const from = "2026-07-23T00:00:00.000000002Z";
    const to = "2026-07-23T00:00:00.000000001Z";
    expect(new Date(from).getTime()).toBe(new Date(to).getTime());

    expect(isApprovedQuerySet(transactions, { occurredAtFrom: from, occurredAtTo: to })).toBe(
      false,
    );
    expect(isApprovedQuerySet(transactions, { occurredAtFrom: to, occurredAtTo: from })).toBe(
      true,
    );
  });

  it("refuses an inverted range in the canonical builder itself", () => {
    // Already-stringified values, so nothing above the URL layer is involved.
    expect(() =>
      buildBackendRequestUrl(BASE, "transaction-list", undefined, {
        occurredAtFrom: "2026-07-24T00:00:00Z",
        occurredAtTo: "2026-07-23T00:00:00Z",
      }),
    ).toThrow(RequestNotAllowedError);
    expect(() =>
      buildBackendRequestUrl(BASE, "case-list", undefined, {
        createdAtFrom: "2026-07-24T00:00:00Z",
        createdAtTo: "2026-07-23T00:00:00Z",
      }),
    ).toThrow(RequestNotAllowedError);
    expect(() =>
      buildBackendRequestUrl(BASE, "case-list", undefined, {
        lastChangedAtFrom: "2026-07-23T00:00:00.000000002Z",
        lastChangedAtTo: "2026-07-23T00:00:00.000000001Z",
      }),
    ).toThrow(RequestNotAllowedError);

    // the same pairs the right way round still build
    expect(
      buildBackendRequestUrl(BASE, "transaction-list", undefined, {
        occurredAtFrom: "2026-07-23T00:00:00Z",
        occurredAtTo: "2026-07-24T00:00:00Z",
      }).url,
    ).toContain("occurredAtFrom=");
  });
});

/**
 * Length is a per-endpoint contract, not a shared structural bound. The two
 * reference rules disagree about it, and merging them back into one shared
 * length limit fails every assertion below.
 */
describe("endpoint registry — reference length is per endpoint", () => {
  const LONG = "a".repeat(257);
  const VERY_LONG = "b".repeat(4096);
  const PADDED = " acct ";

  it("accepts a non-blank transaction reference of any length", () => {
    for (const value of [LONG, VERY_LONG, "a".repeat(129), "a".repeat(256)]) {
      expect(isApprovedQueryValue({ kind: "transaction-ref" }, value)).toBe(true);
    }
  });

  it("keeps the structural floor free of any length bound", () => {
    expect(isStructurallyAllowedQueryValue(LONG)).toBe(true);
    expect(isStructurallyAllowedQueryValue(VERY_LONG)).toBe(true);
    expect(isStructurallyAllowedQueryValue("")).toBe(false);
    expect(isStructurallyAllowedQueryValue("a\u0000b")).toBe(false);
  });

  it("builds and re-verifies a 257-character transaction reference", () => {
    for (const name of ["accountRef", "externalCustomerRef"]) {
      const { url } = buildBackendRequestUrl(BASE, "transaction-list", undefined, {
        [name]: LONG,
      });
      expect(new URL(url).searchParams.get(name)).toBe(LONG);
      expect(findApprovedBackendRequest(BASE, "GET", url)?.key).toBe("transaction-list");
    }
  });

  it("restores a padded reference exactly, without trimming it", () => {
    for (const name of ["accountRef", "externalCustomerRef"]) {
      const { url } = buildBackendRequestUrl(BASE, "transaction-list", undefined, {
        [name]: PADDED,
      });
      expect(new URL(url).searchParams.get(name)).toBe(PADDED);
      expect(new URL(url).search).toBe(`?${name}=+acct+`);
    }
  });

  it("refuses a blank transaction reference by Java's definition", () => {
    for (const value of ["", " ", "   ", "\u3000", "\u3000\u3000", "\u2028"]) {
      expect(isApprovedQueryValue({ kind: "transaction-ref" }, value)).toBe(false);
      expect(() =>
        buildBackendRequestUrl(BASE, "transaction-list", undefined, { accountRef: value }),
      ).toThrow(RequestNotAllowedError);
    }
  });

  it("keeps the case assignee at Backend's 128 characters", () => {
    expect(isApprovedQueryValue({ kind: "case-assignee-ref" }, "a".repeat(128))).toBe(true);
    expect(isApprovedQueryValue({ kind: "case-assignee-ref" }, "a".repeat(129))).toBe(false);
    expect(isApprovedQueryValue({ kind: "case-assignee-ref" }, LONG)).toBe(false);

    expect(
      buildBackendRequestUrl(BASE, "case-list", undefined, {
        assigneeRef: "a".repeat(128),
      }).url,
    ).toContain("assigneeRef=");
    expect(() =>
      buildBackendRequestUrl(BASE, "case-list", undefined, { assigneeRef: "a".repeat(129) }),
    ).toThrow(RequestNotAllowedError);
  });

  it("makes the two rules disagree at exactly 129 characters", () => {
    // Merging them back into one shared length bound breaks this in one
    // direction or the other, whichever bound survives.
    const boundary = "a".repeat(129);
    expect(isApprovedQueryValue({ kind: "transaction-ref" }, boundary)).toBe(true);
    expect(isApprovedQueryValue({ kind: "case-assignee-ref" }, boundary)).toBe(false);

    expect(
      findApprovedBackendRequest(BASE, "GET", `${BASE}/api/v1/transactions?accountRef=${boundary}`)
        ?.key,
    ).toBe("transaction-list");
    expect(
      findApprovedBackendRequest(BASE, "GET", `${BASE}/api/v1/cases?assigneeRef=${boundary}`),
    ).toBeUndefined();
  });

  it("does not put a refused reference into the error it throws", () => {
    const secret = "s".repeat(300);
    const thrown = (() => {
      try {
        buildBackendRequestUrl(BASE, "case-list", undefined, { assigneeRef: secret });
        return undefined;
      } catch (error: unknown) {
        return error;
      }
    })();

    expect(thrown).toBeInstanceOf(RequestNotAllowedError);
    expect((thrown as Error).message).not.toContain(secret);
    expect((thrown as Error).stack ?? "").not.toContain(secret);
    expect(JSON.stringify(thrown)).not.toContain(secret);
  });
});
