import { RequestNotAllowedError } from "./errors";

/**
 * The exact set of Backend business endpoints a signed-in USER browser client
 * may call.
 *
 * This registry is the allowlist. Callers name an endpoint by key and supply
 * path parameter values; they cannot supply a URL, a method, a query string or
 * a header. Anything not described here has no code path to reach the network,
 * which is what keeps the SERVICE ingestion endpoints, the public health path,
 * the management listener, the AI service and every observability service
 * unreachable from this client rather than merely undocumented.
 *
 * Mirrors `FinGuardOpsSecurityConfiguration` and
 * `docs/02-architecture/security-architecture.md` section 5. Deliberately
 * absent, and not to be added without a Backend matrix change:
 *
 * - `GET /api/health` - public, credential-free, served by `healthApi.ts`
 * - `POST /api/v1/transactions` - SERVICE `transaction:intake`
 * - `POST /api/v1/behavior-events` - SERVICE `behavior-event:intake`
 * - `/actuator/**`, management listener 8081
 * - endpoints that exist only as documentation candidates
 */
export type BackendEndpointKey =
  | "transaction-list"
  | "transaction-detail"
  | "case-list"
  | "case-detail"
  | "case-note-list"
  | "case-audit-list"
  | "case-status-change"
  | "case-assignee-change"
  | "case-resolution-create"
  | "case-note-create";

export type BackendHttpMethod = "GET" | "PATCH" | "POST";

export type BackendPathParamName = "transactionId" | "caseId";

export type BackendPathParams = Readonly<Record<string, string>>;

type PathSegment =
  | { readonly kind: "literal"; readonly value: string }
  | { readonly kind: "param"; readonly name: BackendPathParamName };

export interface BackendEndpointDescriptor {
  readonly key: BackendEndpointKey;
  readonly method: BackendHttpMethod;
  readonly template: string;
  readonly segments: readonly PathSegment[];
  readonly paramNames: readonly BackendPathParamName[];
  /** GET carries no request body; only PATCH and POST may send JSON. */
  readonly acceptsJsonBody: boolean;
}

/**
 * Canonical lowercase UUID v4 with an RFC 4122 variant nibble, matching the
 * form Backend itself validates for business identifiers.
 *
 * Strictness here is the whole path-traversal defense. The character class
 * admits no percent sign, slash, backslash, semicolon, dot or whitespace, so an
 * encoded slash, a dot segment, a matrix parameter or a trailing slash cannot
 * survive into a URL - they are rejected before a URL is even assembled.
 */
const CANONICAL_UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export function isCanonicalUuidV4(value: string): boolean {
  return CANONICAL_UUID_V4.test(value);
}

const PARAM_NAMES: readonly BackendPathParamName[] = ["transactionId", "caseId"];

function isParamName(value: string): value is BackendPathParamName {
  return (PARAM_NAMES as readonly string[]).includes(value);
}

/**
 * Parses a template into fixed segments once, at module load. Templates are
 * source constants, so a malformed one is a build-time defect and throws here
 * rather than degrading into a permissive runtime path.
 */
function parseTemplate(template: string): readonly PathSegment[] {
  if (!template.startsWith("/") || template.endsWith("/")) {
    throw new Error("Backend endpoint template must start with, and not end with, a slash.");
  }
  return template
    .slice(1)
    .split("/")
    .map((part): PathSegment => {
      if (part.startsWith("{") && part.endsWith("}")) {
        const name = part.slice(1, -1);
        if (!isParamName(name)) {
          throw new Error("Backend endpoint template uses an unknown path parameter.");
        }
        return { kind: "param", name };
      }
      if (!/^[a-z0-9-]+$/.test(part)) {
        throw new Error("Backend endpoint template contains an unsupported literal segment.");
      }
      return { kind: "literal", value: part };
    });
}

function describe(
  key: BackendEndpointKey,
  method: BackendHttpMethod,
  template: string,
): BackendEndpointDescriptor {
  const segments = parseTemplate(template);
  return {
    key,
    method,
    template,
    segments,
    paramNames: segments.flatMap((segment) => (segment.kind === "param" ? [segment.name] : [])),
    acceptsJsonBody: method !== "GET",
  };
}

const REGISTRY: Readonly<Record<BackendEndpointKey, BackendEndpointDescriptor>> = Object.freeze({
  "transaction-list": describe("transaction-list", "GET", "/api/v1/transactions"),
  "transaction-detail": describe(
    "transaction-detail",
    "GET",
    "/api/v1/transactions/{transactionId}",
  ),
  "case-list": describe("case-list", "GET", "/api/v1/cases"),
  "case-detail": describe("case-detail", "GET", "/api/v1/cases/{caseId}"),
  "case-note-list": describe("case-note-list", "GET", "/api/v1/cases/{caseId}/notes"),
  "case-audit-list": describe("case-audit-list", "GET", "/api/v1/cases/{caseId}/audit-logs"),
  "case-status-change": describe("case-status-change", "PATCH", "/api/v1/cases/{caseId}/status"),
  "case-assignee-change": describe(
    "case-assignee-change",
    "PATCH",
    "/api/v1/cases/{caseId}/assignee",
  ),
  "case-resolution-create": describe(
    "case-resolution-create",
    "POST",
    "/api/v1/cases/{caseId}/resolution",
  ),
  "case-note-create": describe("case-note-create", "POST", "/api/v1/cases/{caseId}/notes"),
});

export const BACKEND_ENDPOINT_KEYS: readonly BackendEndpointKey[] = Object.freeze(
  Object.keys(REGISTRY) as BackendEndpointKey[],
);

/**
 * Own-property lookup only. A plain `REGISTRY[key]` would happily resolve
 * `"constructor"` or `"toString"` to something truthy from the prototype chain.
 */
export function getBackendEndpoint(key: string): BackendEndpointDescriptor | undefined {
  if (!Object.prototype.hasOwnProperty.call(REGISTRY, key)) {
    return undefined;
  }
  return REGISTRY[key as BackendEndpointKey];
}

/** Characters that must never appear in an assembled path, in any position. */
function hasUnsafePathCharacters(pathname: string): boolean {
  return (
    pathname.includes("%") ||
    pathname.includes("\\") ||
    pathname.includes(";") ||
    pathname.includes("..") ||
    pathname.includes("//") ||
    pathname.includes("?") ||
    pathname.includes("#") ||
    pathname.includes("@") ||
    /\s/.test(pathname)
  );
}

/**
 * The base URL is already validated by `parseApiBaseUrl`, but it is operator
 * input and may legitimately carry a path prefix
 * (`https://gateway.example/finguard`). That prefix becomes part of every
 * business URL, so it is re-validated here and compared exactly rather than
 * assumed to be empty.
 */
function resolveBasePath(baseUrl: string): { readonly origin: string; readonly basePath: string } {
  let base: URL;
  try {
    base = new URL(baseUrl);
  } catch {
    throw new RequestNotAllowedError();
  }
  if (base.protocol !== "http:" && base.protocol !== "https:") {
    throw new RequestNotAllowedError();
  }
  if (base.username !== "" || base.password !== "" || base.search !== "" || base.hash !== "") {
    throw new RequestNotAllowedError();
  }
  const basePath = base.pathname === "/" ? "" : base.pathname.replace(/\/+$/, "");
  if (basePath !== "" && (!basePath.startsWith("/") || hasUnsafePathCharacters(basePath))) {
    throw new RequestNotAllowedError();
  }
  return { origin: `${base.protocol}//${base.host}`, basePath };
}

function resolveParamValue(
  params: BackendPathParams | undefined,
  name: BackendPathParamName,
): string {
  if (params === undefined || !Object.prototype.hasOwnProperty.call(params, name)) {
    throw new RequestNotAllowedError();
  }
  const value: unknown = params[name];
  if (typeof value !== "string" || !isCanonicalUuidV4(value)) {
    throw new RequestNotAllowedError();
  }
  return value;
}

export interface BackendRequestTarget {
  readonly descriptor: BackendEndpointDescriptor;
  readonly url: string;
}

/**
 * Re-parses an assembled URL and compares every component against what was
 * intended.
 *
 * Comparison is exact on origin, userinfo, pathname, search and hash - never
 * `startsWith`, never substring containment. That is the difference between
 * accepting only `http://localhost:8080/api/v1/cases` and also accepting
 * `http://localhost:8080.evil.example/api/v1/cases` or
 * `http://localhost:8080/api/v1/cases/../../actuator`, both of which share a
 * prefix with the intended value.
 *
 * Exported because it is the boundary's last check and is verified directly
 * against those near-miss URLs, rather than only through inputs that happen to
 * be well-formed by the time they get here.
 */
export function isExactBackendUrl(
  candidate: string,
  expectedOrigin: string,
  expectedPathname: string,
): boolean {
  let assembled: URL;
  try {
    assembled = new URL(candidate);
  } catch {
    return false;
  }
  return (
    assembled.origin === expectedOrigin &&
    `${assembled.protocol}//${assembled.host}` === expectedOrigin &&
    assembled.username === "" &&
    assembled.password === "" &&
    assembled.pathname === expectedPathname &&
    assembled.search === "" &&
    assembled.hash === "" &&
    assembled.href === candidate
  );
}

/**
 * Builds the one URL an endpoint key plus its parameters is allowed to produce,
 * then verifies the result component by component.
 *
 * The re-check is deliberate defense in depth: assembly already uses only
 * source-constant literals and UUID-validated parameters, so a mismatch here
 * means an assumption broke.
 */
export function buildBackendRequestUrl(
  baseUrl: string,
  key: string,
  params?: BackendPathParams,
): BackendRequestTarget {
  const descriptor = getBackendEndpoint(key);
  if (descriptor === undefined) {
    throw new RequestNotAllowedError();
  }

  // Every supplied parameter must be one this endpoint actually has: an extra
  // key means the caller believes it is calling something else.
  if (params !== undefined) {
    for (const name of Object.keys(params)) {
      if (!(descriptor.paramNames as readonly string[]).includes(name)) {
        throw new RequestNotAllowedError();
      }
    }
  }

  const { origin, basePath } = resolveBasePath(baseUrl);

  const endpointPath = `/${descriptor.segments
    .map((segment) =>
      segment.kind === "literal" ? segment.value : resolveParamValue(params, segment.name),
    )
    .join("/")}`;

  const expectedPathname = `${basePath}${endpointPath}`;
  if (hasUnsafePathCharacters(expectedPathname)) {
    throw new RequestNotAllowedError();
  }

  const candidate = `${origin}${expectedPathname}`;
  if (!isExactBackendUrl(candidate, origin, expectedPathname)) {
    throw new RequestNotAllowedError();
  }

  return { descriptor, url: candidate };
}

/**
 * Decides whether a fully formed URL and method are one of the approved
 * Backend USER requests, without being told which endpoint was intended.
 *
 * This is the check for code that holds a URL rather than an endpoint key —
 * most importantly the credential capability, which must be able to refuse a
 * destination on its own rather than trusting that its caller already did. It
 * matches the path against the registry segment by segment, validates every
 * path parameter, and then rebuilds the URL through the same exact builder and
 * requires an identical result. A near miss never matches: not a different
 * origin, not a trailing slash, not an encoded separator, not a query string,
 * not the right path with the wrong method.
 */
export function findApprovedBackendRequest(
  baseUrl: string,
  method: string,
  url: string,
): BackendEndpointDescriptor | undefined {
  let candidate: URL;
  try {
    candidate = new URL(url);
  } catch {
    return undefined;
  }
  if (
    candidate.username !== "" ||
    candidate.password !== "" ||
    candidate.search !== "" ||
    candidate.hash !== ""
  ) {
    return undefined;
  }
  if (hasUnsafePathCharacters(candidate.pathname)) {
    return undefined;
  }

  let base: { readonly origin: string; readonly basePath: string };
  try {
    base = resolveBasePath(baseUrl);
  } catch {
    return undefined;
  }
  if (
    candidate.origin !== base.origin ||
    `${candidate.protocol}//${candidate.host}` !== base.origin
  ) {
    return undefined;
  }

  // The base prefix must end on a segment boundary, so a base of `/finguard`
  // does not match a path under `/finguardx`.
  if (base.basePath !== "" && !candidate.pathname.startsWith(`${base.basePath}/`)) {
    return undefined;
  }
  const endpointPath = candidate.pathname.slice(base.basePath.length);
  if (!endpointPath.startsWith("/") || endpointPath.endsWith("/")) {
    return undefined;
  }
  const parts = endpointPath.slice(1).split("/");

  for (const key of BACKEND_ENDPOINT_KEYS) {
    const descriptor = REGISTRY[key];
    if (descriptor.method !== method || descriptor.segments.length !== parts.length) {
      continue;
    }

    const params: Record<string, string> = {};
    let matched = true;
    for (let index = 0; index < parts.length; index += 1) {
      const segment = descriptor.segments[index];
      const part = parts[index];
      if (segment.kind === "literal") {
        if (part !== segment.value) {
          matched = false;
          break;
        }
      } else if (isCanonicalUuidV4(part)) {
        params[segment.name] = part;
      } else {
        matched = false;
        break;
      }
    }
    if (!matched) {
      continue;
    }

    // Same builder, same exact comparison: whatever this URL is, it has to be
    // byte-for-byte what an approved request would have produced.
    try {
      return buildBackendRequestUrl(baseUrl, descriptor.key, params).url === candidate.href
        ? descriptor
        : undefined;
    } catch {
      return undefined;
    }
  }

  return undefined;
}
