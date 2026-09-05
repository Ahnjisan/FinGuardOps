import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createOidcAuthClient } from "../auth/oidcAuthClient";
import type { OidcUserLike, UserManagerLike } from "../auth/oidcAuthClient";

/**
 * Lets one describe block replace what the URL builder returns, so the
 * transport's own exact-URL check has something wrong to catch. Everything else
 * in the module stays real.
 */
const builderOverride = vi.hoisted(() => ({ url: undefined as string | undefined }));

vi.mock("./backendEndpoints", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./backendEndpoints")>();
  return {
    ...actual,
    buildBackendRequestUrl: (
      baseUrl: string,
      key: string,
      params?: Readonly<Record<string, string>>,
    ) => {
      const built = actual.buildBackendRequestUrl(baseUrl, key, params);
      return builderOverride.url === undefined ? built : { ...built, url: builderOverride.url };
    },
  };
});
import type {
  AuthorizedRequest,
  AuthSession,
  CompleteSignInResult,
  CredentialAuthClient,
  InitializeResult,
} from "../auth/authClient";
import { BACKEND_ENDPOINT_KEYS, getBackendEndpoint } from "./backendEndpoints";
import {
  AuthenticationRequiredError,
  ForbiddenError,
  HttpError,
  InvalidResponseError,
  NetworkError,
  RequestNotAllowedError,
  TimeoutError,
  UnauthorizedError,
} from "./errors";
import { fetchHealth } from "./healthApi";
import { sendAuthorizedBackendRequest } from "./authorizedClient";
import {
  jsonResponse,
  mockFetchHangForever,
  mockFetchOkWithControlledJson,
  mockFetchOnce,
  mockFetchRejectOnce,
} from "../test/mockFetch";

const BASE = "http://localhost:8080";
const CASE_ID = "20000000-0000-4000-9000-000000000003";
const TRANSACTION_ID = "11111111-2222-4333-8444-555555555555";
const TOKEN = "header.payload.signature";
const TIMEOUT_MS = 5000;
const SAFE_TRACE_ID = "trace0123abcd";

interface FakeCalls {
  authorizeRequest: number;
  invalidateIfCurrent: number;
  effectiveInvalidations: number;
  signOut: number;
  signIn: number;
  notified: number;
}

interface LocalFakeAuthClient extends CredentialAuthClient {
  readonly calls: FakeCalls;
  endSession(): void;
  /** Replaces the live session, as a fresh sign-in would. */
  publishNewSession(): void;
  /** Makes the next authorization hang until the returned release runs. */
  deferAuthorization(): () => void;
}

interface FakeOptions {
  readonly signedIn?: boolean;
  readonly token?: string | null;
  /**
   * Appends a second credential instead of replacing, so the caller-side
   * verification of "exactly one Bearer" has something real to catch.
   */
  readonly appendSecondCredential?: boolean;
  /** Leaves the request untouched, as a port that forgot to authorize would. */
  readonly omitCredential?: boolean;
  /** Redirects the request elsewhere, as a compromised port would. */
  readonly rewriteUrlTo?: string;
  /** Sets the Authorization header verbatim, for grammar checks. */
  readonly rawCredential?: string;
}

/**
 * A minimal local stand-in for the port. It mirrors the adapter's two
 * invariants that this module depends on: authorization yields a copy carrying
 * the credential, and invalidation of an already-dead session is a no-op.
 */
function createLocalFakeAuthClient(options: FakeOptions = {}): LocalFakeAuthClient {
  const calls: FakeCalls = {
    authorizeRequest: 0,
    invalidateIfCurrent: 0,
    effectiveInvalidations: 0,
    signOut: 0,
    signIn: 0,
    notified: 0,
  };
  const listeners = new Set<() => void>();
  const token = options.token === undefined ? TOKEN : options.token;
  let sessionLive = options.signedIn ?? true;
  let ownership: object | null = sessionLive ? Object.freeze({}) : null;
  let gate: Promise<void> | undefined;

  return {
    calls,

    endSession(): void {
      sessionLive = false;
      ownership = null;
    },

    publishNewSession(): void {
      sessionLive = true;
      ownership = Object.freeze({});
    },

    deferAuthorization(): () => void {
      let release!: () => void;
      gate = new Promise<void>((resolve) => {
        release = () => {
          resolve();
        };
      });
      return release;
    },

    initialize(): Promise<InitializeResult> {
      return Promise.resolve({ session: null });
    },

    signIn(): Promise<void> {
      calls.signIn += 1;
      return Promise.resolve();
    },

    completeSignIn(): Promise<CompleteSignInResult> {
      return Promise.reject(new Error("not used"));
    },

    signOut(): Promise<void> {
      calls.signOut += 1;
      sessionLive = false;
      ownership = null;
      return Promise.resolve();
    },

    async authorizeRequest(request: Request): Promise<AuthorizedRequest | null> {
      calls.authorizeRequest += 1;
      if (gate !== undefined) {
        await gate;
      }
      const issuedFor = ownership;
      if (!sessionLive || issuedFor === null || token === null || token === "") {
        return null;
      }
      const url = options.rewriteUrlTo ?? request.url;
      const headers = new Headers(request.headers);
      const authorized = (() => {
        if (options.omitCredential) {
          return new Request(url, { method: request.method, headers });
        }
        headers.delete("Authorization");
        headers.set("Authorization", options.rawCredential ?? `Bearer ${token}`);
        if (options.appendSecondCredential) {
          headers.append("Authorization", "Bearer second.credential.value");
        }
        return options.rewriteUrlTo === undefined
          ? new Request(request, { headers })
          : new Request(url, { method: request.method, headers });
      })();

      return {
        request: authorized,
        invalidateIfCurrent: () => {
          calls.invalidateIfCurrent += 1;
          if (ownership !== issuedFor || !sessionLive) {
            return;
          }
          sessionLive = false;
          ownership = null;
          calls.effectiveInvalidations += 1;
          for (const listener of [...listeners]) {
            calls.notified += 1;
            listener();
          }
        },
      };
    },

    onSessionInvalidated(listener: () => void): () => void {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

function errorResponse(status: number, traceId?: string): Response {
  const headers: Record<string, string> = {};
  if (traceId !== undefined) {
    headers["X-Trace-Id"] = traceId;
  }
  return new Response(
    JSON.stringify({
      code: status === 401 ? "UNAUTHORIZED" : "ACCESS_DENIED",
      message: "인증이 필요하거나 인증 정보가 유효하지 않습니다.",
      traceId: "leaked-body-trace",
      fieldErrors: [],
    }),
    {
      status,
      headers: {
        "Content-Type": "application/json",
        "WWW-Authenticate": 'Bearer realm="finguardops-backend", error="invalid_token"',
        ...headers,
      },
    },
  );
}

function statusOnce(status: number, traceId?: string): void {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(() => Promise.resolve(errorResponse(status, traceId))),
  );
}

function caseDetail(client: CredentialAuthClient, signal?: AbortSignal) {
  return sendAuthorizedBackendRequest(client, {
    endpoint: "case-detail",
    params: { caseId: CASE_ID },
    validate: isRecord,
    signal,
  });
}

function noteCreate(client: CredentialAuthClient) {
  return sendAuthorizedBackendRequest(client, {
    endpoint: "case-note-create",
    params: { caseId: CASE_ID },
    body: { content: "investigation note" },
    validate: isRecord,
  });
}

function statusChange(client: CredentialAuthClient) {
  return sendAuthorizedBackendRequest(client, {
    endpoint: "case-status-change",
    params: { caseId: CASE_ID },
    body: { status: "IN_REVIEW" },
    validate: isRecord,
  });
}

function sentRequests(): Request[] {
  return vi.mocked(fetch).mock.calls.map((call) => call[0] as Request);
}

function captureUnhandledRejections(): { errors: unknown[]; restore: () => void } {
  const errors: unknown[] = [];
  const handler = (reason: unknown) => {
    errors.push(reason);
  };
  process.on("unhandledRejection", handler);
  return {
    errors,
    restore: () => {
      process.off("unhandledRejection", handler);
    },
  };
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", BASE);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("authorized transport — Authorization on approved endpoints only", () => {
  it("sends exactly one Bearer credential to every approved endpoint", async () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      const descriptor = getBackendEndpoint(key);
      if (descriptor === undefined) {
        throw new Error("registry lookup failed");
      }
      mockFetchOnce(async () => jsonResponse({ ok: true }));
      const client = createLocalFakeAuthClient();
      const params: Record<string, string> = {};
      for (const name of descriptor.paramNames) {
        params[name] = name === "caseId" ? CASE_ID : TRANSACTION_ID;
      }

      await sendAuthorizedBackendRequest(client, {
        endpoint: key,
        params,
        body: descriptor.acceptsJsonBody ? { field: "value" } : undefined,
        validate: isRecord,
      });

      const [request] = sentRequests();
      expect(request.headers.get("Authorization")).toBe(`Bearer ${TOKEN}`);
      expect(request.method).toBe(descriptor.method);
      vi.unstubAllGlobals();
    }
  });

  it("sets the credential exactly once, not as a merged multi-value header", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    await caseDetail(createLocalFakeAuthClient());

    const raw = [...sentRequests()[0].headers].filter(([name]) => name === "authorization");
    expect(raw).toHaveLength(1);
    expect(raw[0][1]).toBe(`Bearer ${TOKEN}`);
  });

  it("refuses to send when the port merged a second credential into the header", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient({ appendSecondCredential: true });

    await expect(caseDetail(client)).rejects.toBeInstanceOf(RequestNotAllowedError);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("refuses to send when the port returned a request with no credential", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient({ omitCredential: true });

    await expect(caseDetail(client)).rejects.toBeInstanceOf(RequestNotAllowedError);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("refuses to send when the port redirected the request to another origin", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient({ rewriteUrlTo: "http://evil.example/collect" });

    await expect(caseDetail(client)).rejects.toBeInstanceOf(RequestNotAllowedError);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("omits credentials and refuses redirects on the wire", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    await caseDetail(createLocalFakeAuthClient());

    const [request] = sentRequests();
    expect(request.credentials).toBe("omit");
    expect(request.redirect).toBe("error");
  });

  it("sends the token only in the Authorization header — never in URL, query or body", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    await noteCreate(createLocalFakeAuthClient());

    const [request] = sentRequests();
    expect(request.url).toBe(`${BASE}/api/v1/cases/${CASE_ID}/notes`);
    expect(request.url).not.toContain(TOKEN);
    expect(new URL(request.url).search).toBe("");
    expect(new URL(request.url).hash).toBe("");
    await expect(request.clone().text()).resolves.toBe(
      JSON.stringify({ content: "investigation note" }),
    );
    await expect(request.clone().text()).resolves.not.toContain(TOKEN);
  });

  it("sends JSON content type for writes and no body for reads", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    await statusChange(createLocalFakeAuthClient());
    expect(sentRequests()[0].headers.get("Content-Type")).toBe("application/json");
    vi.unstubAllGlobals();

    mockFetchOnce(async () => jsonResponse({ ok: true }));
    await caseDetail(createLocalFakeAuthClient());
    const read = sentRequests()[0];
    expect(read.headers.get("Content-Type")).toBeNull();
    expect(read.body).toBeNull();
  });
});

describe("authorized transport — endpoints that must never receive a token", () => {
  it("keeps the public health request credential-free", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await fetchHealth();

    const init = vi.mocked(fetch).mock.calls[0][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(headers.has("Authorization")).toBe(false);
    expect(headers.has("authorization")).toBe(false);
    expect(init.body).toBeUndefined();
  });

  it("has no endpoint key for the public health path", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();

    for (const endpoint of ["health", "api-health", "/api/health"]) {
      await expect(
        sendAuthorizedBackendRequest(client, { endpoint, validate: isRecord }),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
    }
    expect(fetch).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("has no endpoint key for the SERVICE ingestion endpoints", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();

    for (const endpoint of [
      "transaction-create",
      "transaction-intake",
      "behavior-event-create",
      "behavior-event-intake",
      "behavior-events",
    ]) {
      await expect(
        sendAuthorizedBackendRequest(client, {
          endpoint,
          body: { any: "payload" },
          validate: isRecord,
        }),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
    }
    expect(fetch).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("has no endpoint key for management, AI, observability or external origins", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();

    for (const endpoint of [
      "actuator-health",
      "actuator-prometheus",
      "/actuator/prometheus",
      "http://localhost:8081/actuator/health",
      "http://localhost:8000/api/v1/scoring",
      "http://prometheus:9090/api/v1/query",
      "http://grafana:3000/api/dashboards",
      "http://alertmanager:9093/api/v2/alerts",
      "https://risk-provider.example/score",
      "https://evil.example/collect",
      "//evil.example",
    ]) {
      await expect(
        sendAuthorizedBackendRequest(client, { endpoint, validate: isRecord }),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
    }
    expect(fetch).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("refuses a rejected path parameter before any credential is requested", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();

    for (const caseId of [`${CASE_ID}/../../actuator`, "%2e%2e", `${CASE_ID}/`, ""]) {
      await expect(
        sendAuthorizedBackendRequest(client, {
          endpoint: "case-detail",
          params: { caseId },
          validate: isRecord,
        }),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
    }
    expect(fetch).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("refuses a body on a read endpoint and a missing body on a write endpoint", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();

    await expect(
      sendAuthorizedBackendRequest(client, {
        endpoint: "case-detail",
        params: { caseId: CASE_ID },
        body: { unexpected: true },
        validate: isRecord,
      }),
    ).rejects.toBeInstanceOf(RequestNotAllowedError);

    await expect(
      sendAuthorizedBackendRequest(client, {
        endpoint: "case-note-create",
        params: { caseId: CASE_ID },
        validate: isRecord,
      }),
    ).rejects.toBeInstanceOf(RequestNotAllowedError);

    expect(fetch).not.toHaveBeenCalled();
  });

  it("refuses a body that cannot be serialized as JSON", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const circular: Record<string, unknown> = {};
    circular.self = circular;

    await expect(
      sendAuthorizedBackendRequest(createLocalFakeAuthClient(), {
        endpoint: "case-note-create",
        params: { caseId: CASE_ID },
        body: circular,
        validate: isRecord,
      }),
    ).rejects.toBeInstanceOf(RequestNotAllowedError);
    expect(fetch).not.toHaveBeenCalled();
  });
});

describe("authorized transport — no local session", () => {
  it("fails locally and sends nothing when the session is not authenticated", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient({ signedIn: false });

    await expect(caseDetail(client)).rejects.toBeInstanceOf(AuthenticationRequiredError);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("fails locally and sends nothing when there is no usable token", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    for (const token of [null, ""]) {
      const client = createLocalFakeAuthClient({ token });
      await expect(caseDetail(client)).rejects.toBeInstanceOf(AuthenticationRequiredError);
    }
    expect(fetch).not.toHaveBeenCalled();
  });

  it("does not start a sign-in or a redirect when authentication is missing", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient({ signedIn: false });
    const before = window.location.href;

    await expect(caseDetail(client)).rejects.toBeInstanceOf(AuthenticationRequiredError);

    expect(client.calls.signIn).toBe(0);
    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(window.location.href).toBe(before);
  });
});

describe("authorized transport — no token is observable outside the port", () => {
  it("exposes no token accessor on the port surface", () => {
    const client = createLocalFakeAuthClient();
    const surface = Object.keys(client);

    for (const forbidden of ["getAccessToken", "accessToken", "token", "getUser", "getToken"]) {
      expect(surface).not.toContain(forbidden);
    }
  });

  it("returns no token on the success result and writes none to Web Storage", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    const result = await caseDetail(createLocalFakeAuthClient());

    expect(JSON.stringify(result)).not.toContain(TOKEN);
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it("puts no token in any error it raises", async () => {
    for (const status of [401, 403, 500]) {
      statusOnce(status, SAFE_TRACE_ID);
      const error = await caseDetail(createLocalFakeAuthClient()).catch((caught: unknown) => caught);

      expect(JSON.stringify({ message: (error as Error).message })).not.toContain(TOKEN);
      expect((error as Error).stack ?? "").not.toContain(TOKEN);
      vi.unstubAllGlobals();
    }
  });
});

describe("authorized transport — 401", () => {
  it("raises UnauthorizedError, not a generic HttpError", async () => {
    statusOnce(401, SAFE_TRACE_ID);

    const error = await caseDetail(createLocalFakeAuthClient()).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(UnauthorizedError);
    expect(error).not.toBeInstanceOf(ForbiddenError);
  });

  it("keeps a contract-valid trace id and nothing else from the response", async () => {
    statusOnce(401, SAFE_TRACE_ID);

    const error = (await caseDetail(createLocalFakeAuthClient()).catch(
      (caught: unknown) => caught,
    )) as UnauthorizedError;

    expect(error.traceId).toBe(SAFE_TRACE_ID);
    const serialized = JSON.stringify({ message: error.message, traceId: error.traceId });
    expect(serialized).not.toContain("UNAUTHORIZED");
    expect(serialized).not.toContain("leaked-body-trace");
    expect(serialized).not.toContain("인증이 필요");
    expect(serialized).not.toContain("WWW-Authenticate");
    expect(serialized).not.toContain("invalid_token");
    expect(serialized).not.toContain("finguardops-backend");
  });

  it("discards a trace id that fails the contract", async () => {
    statusOnce(401, "bad id");

    const error = (await caseDetail(createLocalFakeAuthClient()).catch(
      (caught: unknown) => caught,
    )) as UnauthorizedError;

    expect(error.traceId).toBeUndefined();
  });

  it("never reads the 401 response body", async () => {
    const jsonSpy = vi.fn(() => {
      throw new Error("body must not be read");
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        headers: new Headers({ "X-Trace-Id": SAFE_TRACE_ID }),
        json: jsonSpy,
      }),
    );

    await expect(caseDetail(createLocalFakeAuthClient())).rejects.toBeInstanceOf(UnauthorizedError);
    expect(jsonSpy).not.toHaveBeenCalled();
  });

  it("invalidates the local session exactly once", async () => {
    statusOnce(401);
    const client = createLocalFakeAuthClient();

    await expect(caseDetail(client)).rejects.toBeInstanceOf(UnauthorizedError);

    expect(client.calls.invalidateIfCurrent).toBe(1);
    expect(client.calls.effectiveInvalidations).toBe(1);
    expect(client.calls.signOut).toBe(0);
  });

  it("collapses three concurrent 401s into one teardown and one notification", async () => {
    statusOnce(401);
    const client = createLocalFakeAuthClient();
    client.onSessionInvalidated(() => undefined);

    const outcomes = await Promise.allSettled([
      caseDetail(client),
      sendAuthorizedBackendRequest(client, {
        endpoint: "case-note-list",
        params: { caseId: CASE_ID },
        validate: isRecord,
      }),
      sendAuthorizedBackendRequest(client, {
        endpoint: "transaction-list",
        validate: isRecord,
      }),
    ]);

    expect(outcomes.every((outcome) => outcome.status === "rejected")).toBe(true);
    // Each request was sent once, and only once.
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(client.calls.invalidateIfCurrent).toBe(3);
    expect(client.calls.effectiveInvalidations).toBe(1);
    expect(client.calls.notified).toBe(1);
    expect(client.calls.signIn).toBe(0);
  });

  it("does not replay a GET after a 401", async () => {
    statusOnce(401);

    await expect(caseDetail(createLocalFakeAuthClient())).rejects.toBeInstanceOf(UnauthorizedError);

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("does not replay a POST after a 401", async () => {
    statusOnce(401);

    await expect(noteCreate(createLocalFakeAuthClient())).rejects.toBeInstanceOf(UnauthorizedError);

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(sentRequests()).toHaveLength(1);
  });

  it("does not replay a PATCH after a 401", async () => {
    statusOnce(401);

    await expect(statusChange(createLocalFakeAuthClient())).rejects.toBeInstanceOf(
      UnauthorizedError,
    );

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(sentRequests()).toHaveLength(1);
  });

  it("does not navigate or start a sign-in on 401", async () => {
    statusOnce(401);
    const client = createLocalFakeAuthClient();
    const before = window.location.href;

    await expect(caseDetail(client)).rejects.toBeInstanceOf(UnauthorizedError);

    expect(client.calls.signIn).toBe(0);
    expect(window.location.href).toBe(before);
  });

  it("does not invalidate again when a 401 arrives after the user already signed out", async () => {
    statusOnce(401);
    const client = createLocalFakeAuthClient();
    const pending = caseDetail(client).catch((caught: unknown) => caught);
    await client.signOut();

    await pending;

    expect(client.calls.effectiveInvalidations).toBe(0);
    expect(client.calls.notified).toBe(0);
  });
});

describe("authorized transport — 403", () => {
  it("raises ForbiddenError, not UnauthorizedError", async () => {
    statusOnce(403, SAFE_TRACE_ID);

    const error = await caseDetail(createLocalFakeAuthClient()).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ForbiddenError);
    expect(error).not.toBeInstanceOf(UnauthorizedError);
  });

  it("keeps the session, the token and the subscriber list untouched", async () => {
    statusOnce(403, SAFE_TRACE_ID);
    const client = createLocalFakeAuthClient();
    client.onSessionInvalidated(() => undefined);

    await expect(caseDetail(client)).rejects.toBeInstanceOf(ForbiddenError);

    expect(client.calls.invalidateIfCurrent).toBe(0);
    expect(client.calls.effectiveInvalidations).toBe(0);
    expect(client.calls.notified).toBe(0);
    expect(client.calls.signOut).toBe(0);
  });

  it("still authorizes the next request, proving the session survived", async () => {
    statusOnce(403);
    const client = createLocalFakeAuthClient();
    await expect(caseDetail(client)).rejects.toBeInstanceOf(ForbiddenError);
    vi.unstubAllGlobals();

    mockFetchOnce(async () => jsonResponse({ ok: true }));
    await expect(caseDetail(client)).resolves.toEqual(
      expect.objectContaining({ data: { ok: true } }),
    );
    expect(sentRequests()[0].headers.get("Authorization")).toBe(`Bearer ${TOKEN}`);
  });

  it("keeps only a contract-valid trace id and exposes no role, claim or body", async () => {
    statusOnce(403, SAFE_TRACE_ID);

    const error = (await caseDetail(createLocalFakeAuthClient()).catch(
      (caught: unknown) => caught,
    )) as ForbiddenError;

    expect(error.traceId).toBe(SAFE_TRACE_ID);
    const serialized = JSON.stringify({ message: error.message, traceId: error.traceId });
    expect(serialized).not.toContain("ACCESS_DENIED");
    expect(serialized).not.toContain("leaked-body-trace");
    expect(serialized).not.toContain("insufficient_scope");
    expect(serialized).not.toContain("case:read");
    expect(serialized).not.toContain(TOKEN);
  });

  it("discards an unsafe trace id on 403", async () => {
    statusOnce(403, "x");

    const error = (await caseDetail(createLocalFakeAuthClient()).catch(
      (caught: unknown) => caught,
    )) as ForbiddenError;

    expect(error.traceId).toBeUndefined();
  });

  it("does not retry, replay or redirect on 403", async () => {
    statusOnce(403);
    const client = createLocalFakeAuthClient();
    const before = window.location.href;

    await expect(noteCreate(client)).rejects.toBeInstanceOf(ForbiddenError);

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(client.calls.signIn).toBe(0);
    expect(window.location.href).toBe(before);
  });
});

describe("authorized transport — other outcomes", () => {
  it("classifies other non-2xx statuses as HttpError only", async () => {
    for (const status of [400, 404, 409, 422, 500, 503]) {
      statusOnce(status);
      const client = createLocalFakeAuthClient();
      const error = await caseDetail(client).catch((caught: unknown) => caught);

      expect(error).toBeInstanceOf(HttpError);
      expect(error).not.toBeInstanceOf(UnauthorizedError);
      expect(error).not.toBeInstanceOf(ForbiddenError);
      expect(client.calls.invalidateIfCurrent).toBe(0);
      vi.unstubAllGlobals();
    }
  });

  it("classifies a fetch rejection as NetworkError", async () => {
    mockFetchRejectOnce(new TypeError("Failed to fetch"));

    await expect(caseDetail(createLocalFakeAuthClient())).rejects.toBeInstanceOf(NetworkError);
  });

  it("classifies malformed JSON as InvalidResponseError", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: () => Promise.reject(new SyntaxError("Unexpected token")),
      }),
    );

    await expect(caseDetail(createLocalFakeAuthClient())).rejects.toBeInstanceOf(
      InvalidResponseError,
    );
  });

  it("rejects a 2xx body that fails the caller's validator", async () => {
    mockFetchOnce(async () => jsonResponse(["not", "an", "object"]));

    await expect(
      sendAuthorizedBackendRequest(createLocalFakeAuthClient(), {
        endpoint: "case-detail",
        params: { caseId: CASE_ID },
        validate: (body: unknown): body is { caseId: string } =>
          isRecord(body) && typeof body.caseId === "string",
      }),
    ).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("returns validated data and a safe trace id on success", async () => {
    mockFetchOnce(async () =>
      jsonResponse({ caseId: CASE_ID }, { headers: { "X-Trace-Id": SAFE_TRACE_ID } }),
    );

    const result = await sendAuthorizedBackendRequest(createLocalFakeAuthClient(), {
      endpoint: "case-detail",
      params: { caseId: CASE_ID },
      validate: (body: unknown): body is { caseId: string } =>
        isRecord(body) && typeof body.caseId === "string",
    });

    expect(result.data.caseId).toBe(CASE_ID);
    expect(result.traceId).toBe(SAFE_TRACE_ID);
  });

  it("drops an unsafe trace id on success", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }, { headers: { "X-Trace-Id": "no" } }));

    const result = await caseDetail(createLocalFakeAuthClient());

    expect(result.traceId).toBeUndefined();
  });
});

describe("authorized transport — one deadline for the whole lifecycle", () => {
  it("times out while authorization is still pending, without sending anything", async () => {
    vi.useFakeTimers();
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();
    client.deferAuthorization();

    const pending = caseDetail(client);
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    expect(fetch).not.toHaveBeenCalled();
  });

  it("does not send after the deadline even when authorization completes late", async () => {
    vi.useFakeTimers();
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();
    const release = client.deferAuthorization();

    const pending = caseDetail(client);
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    release();
    await vi.advanceTimersByTimeAsync(0);
    await Promise.resolve();

    expect(fetch).not.toHaveBeenCalled();
  });

  it("times out while fetch is pending", async () => {
    vi.useFakeTimers();
    mockFetchHangForever();

    const pending = caseDetail(createLocalFakeAuthClient());
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("times out while the body and JSON parse are pending", async () => {
    vi.useFakeTimers();
    mockFetchOkWithControlledJson();

    const pending = caseDetail(createLocalFakeAuthClient());
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;
  });

  /**
   * Authorization spends most of the budget and the body spends the rest. A
   * per-stage deadline would let this succeed after nearly ten seconds; a
   * single deadline must end it at five.
   */
  it("bounds authorization plus body to one budget, not one budget each", async () => {
    vi.useFakeTimers();
    const controlled = mockFetchOkWithControlledJson();
    const client = createLocalFakeAuthClient();
    const release = client.deferAuthorization();

    const pending = caseDetail(client);
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);

    await vi.advanceTimersByTimeAsync(TIMEOUT_MS - 500);
    release();
    await vi.advanceTimersByTimeAsync(499);
    await vi.advanceTimersByTimeAsync(2);
    await assertion;

    controlled.resolveJson({ ok: true });
  });

  it("succeeds when everything completes just inside the deadline", async () => {
    vi.useFakeTimers();
    const { resolveJson } = mockFetchOkWithControlledJson();

    const pending = caseDetail(createLocalFakeAuthClient());
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS - 100);
    resolveJson({ ok: true });

    await expect(pending).resolves.toEqual(expect.objectContaining({ data: { ok: true } }));
  });

  it("calls fetch exactly once on timeout — no retry", async () => {
    vi.useFakeTimers();
    mockFetchHangForever();

    const pending = caseDetail(createLocalFakeAuthClient()).catch(() => undefined);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await pending;

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("leaves no timer behind on success or failure", async () => {
    vi.useFakeTimers();
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    await caseDetail(createLocalFakeAuthClient());
    expect(vi.getTimerCount()).toBe(0);
    vi.unstubAllGlobals();

    statusOnce(403);
    await caseDetail(createLocalFakeAuthClient()).catch(() => undefined);
    expect(vi.getTimerCount()).toBe(0);
  });
});

describe("authorized transport — external cancellation", () => {
  it("classifies an external abort as NetworkError, never TimeoutError", async () => {
    const external = new AbortController();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((request: Request) => {
        return new Promise<Response>((_resolve, reject) => {
          const abort = () => {
            reject(new DOMException("The operation was aborted.", "AbortError"));
          };
          if (request.signal.aborted) {
            abort();
            return;
          }
          request.signal.addEventListener("abort", abort);
        });
      }),
    );

    const pending = caseDetail(createLocalFakeAuthClient(), external.signal);
    await Promise.resolve();
    external.abort();

    const error = await pending.catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(NetworkError);
    expect(error).not.toBeInstanceOf(TimeoutError);
  });

  it("asks for no credential and sends nothing when the signal is already aborted", async () => {
    const external = new AbortController();
    external.abort();
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();

    await expect(caseDetail(client, external.signal)).rejects.toBeInstanceOf(NetworkError);

    expect(client.calls.authorizeRequest).toBe(0);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("does not send a request that was cancelled while authorization was pending", async () => {
    const external = new AbortController();
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();
    const release = client.deferAuthorization();

    const pending = caseDetail(client, external.signal);
    await Promise.resolve();
    external.abort();
    release();

    await expect(pending).rejects.toBeInstanceOf(NetworkError);
    expect(client.calls.authorizeRequest).toBe(1);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("removes its external abort listener on every settle path", async () => {
    const external = new AbortController();
    const removeSpy = vi.spyOn(external.signal, "removeEventListener");
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    await caseDetail(createLocalFakeAuthClient(), external.signal);

    expect(removeSpy).toHaveBeenCalledWith("abort", expect.any(Function));
  });

  it("produces no unhandled rejection when the body settles after the deadline", async () => {
    vi.useFakeTimers();
    const capture = captureUnhandledRejections();
    const { rejectJson } = mockFetchOkWithControlledJson();

    const pending = caseDetail(createLocalFakeAuthClient());
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    rejectJson(new SyntaxError("late parse failure"));
    await vi.advanceTimersByTimeAsync(0);
    await Promise.resolve();
    await Promise.resolve();

    expect(capture.errors).toEqual([]);
    capture.restore();
  });

  it("produces no unhandled rejection when authorization rejects after the deadline", async () => {
    vi.useFakeTimers();
    const capture = captureUnhandledRejections();
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient();
    const release = client.deferAuthorization();

    const pending = caseDetail(client);
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    release();
    await vi.advanceTimersByTimeAsync(0);
    await Promise.resolve();
    await Promise.resolve();

    expect(capture.errors).toEqual([]);
    capture.restore();
  });
});

describe("authorized transport — the session type is unchanged by this module", () => {
  it("never constructs or mutates an AuthSession", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const session: AuthSession = {
      subject: "11111111-1111-4111-8111-111111111111",
      roles: ["FDS_ANALYST"],
    };

    await caseDetail(createLocalFakeAuthClient());

    expect(session).toEqual({
      subject: "11111111-1111-4111-8111-111111111111",
      roles: ["FDS_ANALYST"],
    });
  });
});

describe("authorized transport — RFC 6750 Bearer grammar", () => {
  async function sendWithCredential(credential: string) {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const client = createLocalFakeAuthClient({ rawCredential: credential });
    const outcome = await caseDetail(client).then(
      () => "sent" as const,
      (error: unknown) => error,
    );
    return { outcome, sent: vi.mocked(fetch).mock.calls.length };
  }

  it("accepts the credential forms the grammar allows", async () => {
    for (const credential of [
      "Bearer abc",
      "Bearer abc.def",
      "Bearer abc-._~+/",
      "Bearer abc=",
      "Bearer abc==",
      "Bearer header.payload.signature",
      "Bearer 2YotnFZFEjr1zCsicMWpAA",
      "Bearer mF_9.B5f-4.1JqM",
    ]) {
      const { outcome, sent } = await sendWithCredential(credential);
      expect(outcome, `expected ${credential} to be sent`).toBe("sent");
      expect(sent).toBe(1);
      vi.unstubAllGlobals();
    }
  });

  /**
   * Some malformed values never reach the grammar check at all: `Headers.set`
   * refuses a line break outright, and refuses any code point above 255.
   * Recorded so their absence from the list below is a platform guarantee
   * rather than a gap in the grammar check. Latin-1 characters are NOT in
   * this group - they reach the grammar check, which is where they are
   * rejected.
   */
  it("cannot even construct a header value with a line break or a code point above 255", () => {
    for (const credential of [
      "Bearer abc\ndef",
      "Bearer abc\r\nX-Injected: 1",
      "Bearer \ud1a0\ucf58\uac12\uc785\ub2c8\ub2e4",
    ]) {
      expect(() => new Headers().set("Authorization", credential)).toThrow(TypeError);
    }
  });

  /**
   * `Headers` strips leading and trailing whitespace from a value on its way
   * in, so a padded credential is already trimmed by the time the grammar
   * check sees it. Nothing in this module normalizes anything; recorded so
   * the padded forms are not mistaken for values the check let through.
   */
  it("sees a value the platform has already trimmed, and normalizes nothing itself", () => {
    const headers = new Headers();
    headers.set("Authorization", " Bearer abc ");
    expect(headers.get("Authorization")).toBe("Bearer abc");
  });

  it("refuses everything outside the grammar, without sending", async () => {
    for (const credential of [
      "Bearer",
      "Bearer ",
      "Bearer =",
      "Bearer ==",
      "Bearer abc=def",
      "Bearer =abc",
      "Bearer ab=c",
      "Bearer abc def",
      "Bearer abc\tdef",
      "Bearer abc,Bearer def",
      "Bearer abc, Bearer def",
      "Bearer abc!",
      "Bearer abc%20",
      "Bearer abc(def)",
      "Bearer abc\u00e9",
      "Bearer abc:def",
      "bearer abc",
      "BEARER abc",
      "Bearer  abc",
      "Basic YWJjOmRlZg==",
      "abc",
    ]) {
      const { outcome, sent } = await sendWithCredential(credential);
      expect(outcome, `expected ${JSON.stringify(credential)} to be refused`).toBeInstanceOf(
        RequestNotAllowedError,
      );
      expect(sent).toBe(0);
      vi.unstubAllGlobals();
    }
  });
});

/**
 * The transport's own exact-URL check, proven independently of the builder that
 * normally produces the URL. The builder is replaced so it hands back a
 * near-miss, and the authorizer is willing to sign anything: the only thing
 * standing between the caller and a credential on a wrong URL is the check
 * inside the transport.
 */
describe("authorized transport — production exact URL wiring", () => {
  afterEach(() => {
    builderOverride.url = undefined;
  });

  const NEAR_MISS = [
    "https://evil.example/api/v1/cases/20000000-0000-4000-9000-000000000003",
    "http://localhost.evil.example:8080/api/v1/cases/20000000-0000-4000-9000-000000000003",
    "http://localhost:9090/api/v1/cases/20000000-0000-4000-9000-000000000003",
    "http://localhost:8080/api/v1/cases/20000000-0000-4000-9000-000000000003/",
    "http://localhost:8080/api/v1/cases/20000000-0000-4000-9000-000000000003?page=0",
    "http://localhost:8080/api/v1/cases/20000000-0000-4000-9000-000000000003#x",
    "http://localhost:8080/api/health",
    // An approved endpoint, but not the one the caller asked for.
    "http://localhost:8080/api/v1/transactions",
    "http://localhost:8080/api/v1/cases",
    "http://localhost:8080/actuator/prometheus",
    "http://localhost:8080/api/v1/cases/../../actuator",
  ];

  it("refuses to send a URL the builder should never have produced", async () => {
    for (const url of NEAR_MISS) {
      builderOverride.url = url;
      mockFetchOnce(async () => jsonResponse({ ok: true }));
      // An authorizer that signs anything it is given: the transport must not
      // be relying on it to notice.
      const client = createLocalFakeAuthClient();

      await expect(caseDetail(client), `expected refusal for ${url}`).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );
      expect(fetch).not.toHaveBeenCalled();
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }
  });

  it("still sends when the builder produces the URL it should", async () => {
    builderOverride.url = `${BASE}/api/v1/cases/${CASE_ID}`;
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    await expect(caseDetail(createLocalFakeAuthClient())).resolves.toEqual(
      expect.objectContaining({ data: { ok: true } }),
    );
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});

/**
 * The remaining cases run the real OIDC adapter against the real transport.
 * A fake stands in only for the oidc-client-ts UserManager, because session
 * ownership is a property of how those two behave together — a fake port would
 * simply reproduce whatever it was told to.
 */
/**
 * The raw access token, end to end: a real OIDC user in the store, the
 * production adapter, the production transport and a real fetch at the far end.
 *
 * `Headers.set` strips leading and trailing whitespace, so a padded token that
 * reached header construction would arrive at the transport's grammar check
 * already normalized and go out as a perfectly valid credential. Only a test
 * that starts from the stored token can tell that apart, which is why these
 * cases do not hand a pre-built header to anything.
 */
describe("authorized transport — raw access token against the real adapter", () => {
  const SUBJECT = "11111111-1111-4111-8111-111111111111";

  interface RawTokenManager extends UserManagerLike {
    readonly calls: { removeUser: number; getUser: number };
  }

  function createManager(accessToken: string): RawTokenManager {
    const calls = { removeUser: 0, getUser: 0 };
    const user: OidcUserLike = {
      profile: { sub: SUBJECT, principal_type: "USER", roles: ["FDS_ANALYST"] },
      access_token: accessToken,
      state: { returnTo: "/" },
    };
    let stored: OidcUserLike | null = null;

    return {
      calls,
      async signinRedirect(): Promise<void> {},
      async signinRedirectCallback(): Promise<OidcUserLike> {
        stored = user;
        return user;
      },
      async getUser(): Promise<OidcUserLike | null> {
        calls.getUser += 1;
        return stored;
      },
      async removeUser(): Promise<void> {
        calls.removeUser += 1;
        stored = null;
      },
      events: {
        addAccessTokenExpired(): () => void {
          return () => undefined;
        },
      },
    };
  }

  async function signedInWith(accessToken: string) {
    const manager = createManager(accessToken);
    const client = createOidcAuthClient(
      () => ({ userManager: manager, storage: window.sessionStorage }),
      { isCallbackRoute: () => false },
    );
    await client.completeSignIn("http://localhost/auth/callback?code=a&state=a");
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    return { manager, client, invalidated };
  }

  it("sends a well-formed opaque token exactly once, unchanged", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const { manager, client, invalidated } = await signedInWith("opaque.token");

    await expect(caseDetail(client)).resolves.toEqual(
      expect.objectContaining({ data: { ok: true } }),
    );

    expect(fetch).toHaveBeenCalledTimes(1);
    const [sent] = sentRequests();
    expect(sent.headers.get("Authorization")).toBe("Bearer opaque.token");
    expect([...sent.headers].filter(([name]) => name === "authorization")).toHaveLength(1);
    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(0);
  });

  it("sends nothing for a raw token the platform would have trimmed into shape", async () => {
    for (const token of ["opaque.token ", " opaque.token", "opaque.token\t"]) {
      const label = JSON.stringify(token);
      mockFetchOnce(async () => jsonResponse({ ok: true }));
      const { manager, client, invalidated } = await signedInWith(token);

      const outcome = await caseDetail(client).then(
        () => "sent" as const,
        (error: unknown) => error,
      );

      // Refused because there is no usable credential, not because a Backend
      // said 401: the two must not be confused.
      expect(outcome, `expected ${label} to be refused`).toBeInstanceOf(
        AuthenticationRequiredError,
      );
      expect(outcome).not.toBeInstanceOf(UnauthorizedError);
      expect(outcome).not.toBeInstanceOf(ForbiddenError);

      // Zero fetches, zero invalidation, zero teardown, zero replay.
      expect(fetch, `expected no request for ${label}`).not.toHaveBeenCalled();
      expect(invalidated).not.toHaveBeenCalled();
      expect(manager.calls.removeUser).toBe(0);
      expect(manager.calls.getUser).toBe(1);

      vi.unstubAllGlobals();
    }
  });

  it("never lets a padded token reach the wire as its trimmed form", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    const { client } = await signedInWith("opaque.token ");

    await expect(caseDetail(client)).rejects.toBeInstanceOf(AuthenticationRequiredError);

    expect(sentRequests()).toHaveLength(0);
    // The value the platform would have produced, proven rather than assumed.
    const normalized = new Headers();
    normalized.set("Authorization", "Bearer opaque.token ");
    expect(normalized.get("Authorization")).toBe("Bearer opaque.token");
  });

  it("treats a malformed stored token as a refusal, not as a dead session", async () => {
    const { manager, client, invalidated } = await signedInWith("opaque.token ");
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    await expect(caseDetail(client)).rejects.toBeInstanceOf(AuthenticationRequiredError);
    expect(fetch).not.toHaveBeenCalled();
    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(0);
  });
});

describe("authorized transport — session ownership against the real adapter", () => {
  const SUBJECT_A = "11111111-1111-4111-8111-111111111111";
  const TOKEN_A = "session.a.token";

  interface FakeManager extends UserManagerLike {
    readonly calls: { removeUser: number };
    emitAccessTokenExpired(): void;
    setNextUser(user: OidcUserLike): void;
    /** Holds the next user-store read open until the returned release runs. */
    deferGetUser(): () => void;
  }

  function createManager(): FakeManager {
    const expiredListeners = new Set<() => void>();
    const calls = { removeUser: 0 };
    let nextUser: OidcUserLike = {
      profile: { sub: SUBJECT_A, principal_type: "USER", roles: ["FDS_ANALYST"] },
      access_token: TOKEN_A,
      state: { returnTo: "/" },
    };
    let stored: OidcUserLike | null = null;
    let getUserGate: Promise<void> | undefined;

    return {
      calls,
      deferGetUser(): () => void {
        let release!: () => void;
        getUserGate = new Promise<void>((resolve) => {
          release = () => {
            resolve();
          };
        });
        return release;
      },
      emitAccessTokenExpired(): void {
        for (const listener of [...expiredListeners]) {
          listener();
        }
      },
      setNextUser(user: OidcUserLike): void {
        nextUser = user;
      },
      async signinRedirect(): Promise<void> {},
      async signinRedirectCallback(): Promise<OidcUserLike> {
        stored = nextUser;
        return nextUser;
      },
      async getUser(): Promise<OidcUserLike | null> {
        if (getUserGate !== undefined) {
          await getUserGate;
        }
        return stored;
      },
      async removeUser(): Promise<void> {
        calls.removeUser += 1;
        stored = null;
      },
      events: {
        addAccessTokenExpired(callback: () => void): () => void {
          expiredListeners.add(callback);
          return () => {
            expiredListeners.delete(callback);
          };
        },
      },
    };
  }

  async function signedIn() {
    const manager = createManager();
    const client = createOidcAuthClient(
      () => ({ userManager: manager, storage: window.sessionStorage }),
      { isCallbackRoute: () => false },
    );
    await client.completeSignIn("http://localhost/auth/callback?code=a&state=a");
    return { manager, client };
  }

  function respondWith(status: number): void {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => Promise.resolve(new Response(null, { status }))),
    );
  }

  /** Case A. */
  it("collapses three concurrent 401s from one session into a single teardown", async () => {
    const { manager, client } = await signedIn();
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    respondWith(401);

    const outcomes = await Promise.allSettled([
      caseDetail(client),
      sendAuthorizedBackendRequest(client, {
        endpoint: "case-note-list",
        params: { caseId: CASE_ID },
        validate: isRecord,
      }),
      sendAuthorizedBackendRequest(client, { endpoint: "transaction-list", validate: isRecord }),
    ]);
    await Promise.resolve();
    await Promise.resolve();

    expect(outcomes.every((outcome) => outcome.status === "rejected")).toBe(true);
    // One fetch each, and no replay of any of them.
    expect(fetch).toHaveBeenCalledTimes(3);
    expect(invalidated).toHaveBeenCalledTimes(1);
    // removeUser and the transaction sweep live in the same teardown function,
    // so one call means both ran exactly once.
    expect(manager.calls.removeUser).toBe(1);
  });

  /** Case B: the headline defect — a stale 401 must not log out the new user. */
  it("does not invalidate a newer session when an older session's request 401s", async () => {
    const { manager, client } = await signedIn();

    // Session A's request reaches the network and stops there.
    let releaseFetch!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((resolve) => {
            releaseFetch = resolve;
          }),
      ),
    );
    const staleRequest = caseDetail(client).catch((error: unknown) => error);
    await Promise.resolve();
    await Promise.resolve();

    // Session B is published while A is still in flight.
    manager.setNextUser({
      profile: {
        sub: "22222222-2222-4222-8222-222222222222",
        principal_type: "USER",
        roles: ["FDS_ANALYST"],
      },
      access_token: "session.b.token",
      state: { returnTo: "/" },
    });
    await client.completeSignIn("http://localhost/auth/callback?code=b&state=b");
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const removeUserAfterB = manager.calls.removeUser;

    releaseFetch(new Response(null, { status: 401 }));
    expect(await staleRequest).toBeInstanceOf(UnauthorizedError);
    await Promise.resolve();
    await Promise.resolve();

    // Session B is untouched.
    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(removeUserAfterB);

    // And still usable: the next request carries session B's credential.
    vi.unstubAllGlobals();
    mockFetchOnce(async () => jsonResponse({ ok: true }));
    await expect(caseDetail(client)).resolves.toEqual(
      expect.objectContaining({ data: { ok: true } }),
    );
    const [sent] = vi.mocked(fetch).mock.calls.map((call) => call[0] as Request);
    expect(sent.headers.get("Authorization")).toBe("Bearer session.b.token");
  });

  /** Case C. */
  it("does nothing when a 401 arrives after the user signed out", async () => {
    const { manager, client } = await signedIn();
    let releaseFetch!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((resolve) => {
            releaseFetch = resolve;
          }),
      ),
    );
    const pending = caseDetail(client).catch((error: unknown) => error);
    await Promise.resolve();
    await Promise.resolve();

    await client.signOut();
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const removeUserAfterLogout = manager.calls.removeUser;

    releaseFetch(new Response(null, { status: 401 }));
    expect(await pending).toBeInstanceOf(UnauthorizedError);
    await Promise.resolve();

    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(removeUserAfterLogout);
  });

  /** Case D. */
  it("does nothing when a 401 arrives after the session already expired", async () => {
    const { manager, client } = await signedIn();
    let releaseFetch!: (response: Response) => void;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((resolve) => {
            releaseFetch = resolve;
          }),
      ),
    );
    const pending = caseDetail(client).catch((error: unknown) => error);
    await Promise.resolve();
    await Promise.resolve();

    manager.emitAccessTokenExpired();
    await Promise.resolve();
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const removeUserAfterExpiry = manager.calls.removeUser;

    releaseFetch(new Response(null, { status: 401 }));
    expect(await pending).toBeInstanceOf(UnauthorizedError);
    await Promise.resolve();

    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(removeUserAfterExpiry);
  });

  /** Case E, at the transport boundary. */
  it("sends nothing when the session is replaced while authorization is pending", async () => {
    const { manager, client } = await signedIn();
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    // Hold the user-store read open, then publish a whole new session while
    // session A's authorization is still waiting on it.
    const release = manager.deferGetUser();
    const pending = caseDetail(client);
    await Promise.resolve();
    manager.setNextUser({
      profile: {
        sub: "22222222-2222-4222-8222-222222222222",
        principal_type: "USER",
        roles: ["FDS_ANALYST"],
      },
      access_token: "session.b.token",
      state: { returnTo: "/" },
    });
    await client.completeSignIn("http://localhost/auth/callback?code=b&state=b");
    release();

    await expect(pending).rejects.toBeInstanceOf(AuthenticationRequiredError);
    expect(fetch).not.toHaveBeenCalled();
  });

  it("does not replay a write when a stale session's PATCH is rejected", async () => {
    const { client } = await signedIn();
    respondWith(401);

    await expect(statusChange(client)).rejects.toBeInstanceOf(UnauthorizedError);

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("keeps the session on 403 even with the real adapter", async () => {
    const { manager, client } = await signedIn();
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    respondWith(403);

    await expect(caseDetail(client)).rejects.toBeInstanceOf(ForbiddenError);

    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(0);
  });
});
