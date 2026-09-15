import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { HttpError, InvalidResponseError, NetworkError, TimeoutError } from "./errors";
import { fetchHealth } from "./healthApi";
import { BACKEND_ENDPOINT_KEYS, getBackendEndpoint } from "./backendEndpoints";
import {
  jsonResponse,
  mockFetchAbortOnce,
  mockFetchOnce,
  mockFetchRejectOnce,
  textResponse,
} from "../test/mockFetch";

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("fetchHealth", () => {
  it("resolves the exact success contract", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    const result = await fetchHealth();

    expect(result.data).toEqual({ status: "UP", service: "backend" });
  });

  it("requests exactly the /api/health path on the configured base URL", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await fetchHealth();

    const call = vi.mocked(fetch).mock.calls[0];
    expect(call[0]).toBe("http://localhost:8080/api/health");
  });

  it("calls fetch exactly once with no automatic retry", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await fetchHealth();

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("forwards an optional external AbortSignal all the way to the real fetch call", async () => {
    mockFetchAbortOnce();
    const externalController = new AbortController();

    const pending = fetchHealth(externalController.signal);
    const fetchSignal = vi.mocked(fetch).mock.calls[0][1]?.signal;
    expect(fetchSignal?.aborted).toBe(false);

    externalController.abort();

    expect(fetchSignal?.aborted).toBe(true);
    await expect(pending).rejects.toBeInstanceOf(NetworkError);
  });

  it("does not add an Authorization header in any casing or form", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await fetchHealth();

    const call = vi.mocked(fetch).mock.calls[0];
    const init = call[1] as RequestInit;
    const normalizedHeaders = new Headers(init.headers);
    expect(normalizedHeaders.has("Authorization")).toBe(false);
    expect(normalizedHeaders.has("authorization")).toBe(false);
  });

  it("does not add an Authorization header to the request input itself", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await fetchHealth();

    const call = vi.mocked(fetch).mock.calls[0];
    const input = call[0];
    if (input instanceof Request) {
      expect(input.headers.has("Authorization")).toBe(false);
    } else {
      // plain string/URL input carries no headers of its own — nothing to leak
      expect(typeof input === "string" || input instanceof URL).toBe(true);
    }
  });

  it("does not include a token or credential in the request URL", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await fetchHealth();

    const call = vi.mocked(fetch).mock.calls[0];
    const requestedUrl = String(call[0]);
    expect(requestedUrl).not.toMatch(/token|credential|password|bearer/i);
    expect(requestedUrl).not.toContain("?");
    expect(requestedUrl).not.toContain("#");
  });

  it("sends no request body", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await fetchHealth();

    const call = vi.mocked(fetch).mock.calls[0];
    const init = call[1] as RequestInit;
    expect(init.body).toBeUndefined();
  });

  it("rejects with HttpError for a 4xx response", async () => {
    mockFetchOnce(async () => jsonResponse({ error: "nope" }, { status: 404 }));

    await expect(fetchHealth()).rejects.toBeInstanceOf(HttpError);
  });

  it("rejects with HttpError for a 5xx response", async () => {
    mockFetchOnce(async () => jsonResponse({ error: "nope" }, { status: 500 }));

    await expect(fetchHealth()).rejects.toBeInstanceOf(HttpError);
  });

  it("rejects with NetworkError when the network request fails", async () => {
    mockFetchRejectOnce(new TypeError("Failed to fetch"));

    await expect(fetchHealth()).rejects.toBeInstanceOf(NetworkError);
  });

  it("rejects with TimeoutError when the request does not complete in time", async () => {
    vi.useFakeTimers();
    mockFetchAbortOnce();

    const pending = fetchHealth();
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);

    await vi.advanceTimersByTimeAsync(5000);
    await assertion;

    vi.useRealTimers();
  });

  it("rejects with InvalidResponseError for malformed JSON, not HttpError", async () => {
    mockFetchOnce(async () => textResponse("not json{"));

    await expect(fetchHealth()).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("rejects with InvalidResponseError when a required field is missing", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP" }));

    await expect(fetchHealth()).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("rejects with InvalidResponseError when a field has the wrong type", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: 123 }));

    await expect(fetchHealth()).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("rejects with InvalidResponseError when a field has the wrong value", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "DOWN", service: "backend" }));

    await expect(fetchHealth()).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("rejects with InvalidResponseError when the body has an extra field", async () => {
    mockFetchOnce(async () =>
      jsonResponse({ status: "UP", service: "backend", extra: "unexpected" }),
    );

    await expect(fetchHealth()).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("returns HttpError, not InvalidResponseError, when a non-2xx status has a well-formed body", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }, { status: 500 }));

    const error = await fetchHealth().catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(HttpError);
    expect(error).not.toBeInstanceOf(InvalidResponseError);
  });

  describe("X-Trace-Id contract: /^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$/ full match only", () => {
    async function fetchWithTraceId(traceId: string): Promise<string | undefined> {
      vi.stubGlobal(
        "fetch",
        vi.fn().mockResolvedValueOnce({
          ok: true,
          status: 200,
          headers: {
            get: (name: string) => (name === "X-Trace-Id" ? traceId : null),
          },
          json: async () => ({ status: "UP", service: "backend" }),
        }),
      );

      const result = await fetchHealth();
      return result.traceId;
    }

    it("accepts the minimum length of 8", async () => {
      const traceId = "a".repeat(8);
      expect(await fetchWithTraceId(traceId)).toBe(traceId);
    });

    it("accepts the maximum length of 64", async () => {
      const traceId = "a".repeat(64);
      expect(await fetchWithTraceId(traceId)).toBe(traceId);
    });

    it("rejects a length of 7", async () => {
      expect(await fetchWithTraceId("a".repeat(7))).toBeUndefined();
    });

    it("rejects a length of 65", async () => {
      expect(await fetchWithTraceId("a".repeat(65))).toBeUndefined();
    });

    it("rejects leading/trailing whitespace instead of trimming it", async () => {
      expect(await fetchWithTraceId(" abcdefgh")).toBeUndefined();
      expect(await fetchWithTraceId("abcdefgh ")).toBeUndefined();
    });

    it("rejects embedded whitespace", async () => {
      expect(await fetchWithTraceId("abcd efgh")).toBeUndefined();
    });

    it("rejects special characters such as <script>", async () => {
      expect(await fetchWithTraceId("<script>abc")).toBeUndefined();
    });

    it("rejects non-ASCII characters", async () => {
      expect(await fetchWithTraceId("abcdefgé")).toBeUndefined();
    });

    it("rejects control characters", async () => {
      expect(await fetchWithTraceId(`trace${String.fromCharCode(9)}id12`)).toBeUndefined();
    });

    it("rejects a trace id unreasonably longer than the maximum", async () => {
      expect(await fetchWithTraceId("a".repeat(200))).toBeUndefined();
    });

    it("rejects a leading dot", async () => {
      expect(await fetchWithTraceId(".abcdefg")).toBeUndefined();
    });

    it("rejects a leading underscore", async () => {
      expect(await fetchWithTraceId("_abcdefg")).toBeUndefined();
    });

    it("rejects a leading colon", async () => {
      expect(await fetchWithTraceId(":abcdefg")).toBeUndefined();
    });

    it("rejects a leading hyphen", async () => {
      expect(await fetchWithTraceId("-abcdefg")).toBeUndefined();
    });

    it("accepts a valid mix of letters, digits, dot, underscore, colon and hyphen", async () => {
      const traceId = "a1B2.c_d:e-f9Z";
      expect(await fetchWithTraceId(traceId)).toBe(traceId);
    });

    it("does not preserve an unsafe trace id anywhere on the rejected result", async () => {
      const result = await (async () => {
        vi.stubGlobal(
          "fetch",
          vi.fn().mockResolvedValueOnce({
            ok: true,
            status: 200,
            headers: { get: (name: string) => (name === "X-Trace-Id" ? "<script>bad" : null) },
            json: async () => ({ status: "UP", service: "backend" }),
          }),
        );
        return fetchHealth();
      })();

      expect(result.traceId).toBeUndefined();
      expect(JSON.stringify(result)).not.toContain("<script>");
    });
  });

  it("does not leak the raw response body in a rejected error's message", async () => {
    mockFetchOnce(async () => jsonResponse({ secretField: "do-not-leak" }, { status: 500 }));

    const error = await fetchHealth().catch((caught: unknown) => caught);

    expect((error as Error).message).not.toContain("do-not-leak");
  });
});

/**
 * Guards the separation the authenticated transport introduced. `fetchHealth`
 * must keep reaching the Backend on its own, with no endpoint registry, no
 * AuthClient and no credential of any kind.
 */
describe("fetchHealth — separation from the authenticated transport", () => {
  it("has no endpoint key in the authenticated registry", () => {
    for (const key of BACKEND_ENDPOINT_KEYS) {
      expect(getBackendEndpoint(key)?.template).not.toBe("/api/health");
    }
    for (const guess of ["health", "api-health", "/api/health"]) {
      expect(getBackendEndpoint(guess)).toBeUndefined();
    }
  });

  it("sends a plain URL and init, never an authorized Request object", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await fetchHealth();

    const [input, init] = vi.mocked(fetch).mock.calls[0];
    expect(typeof input).toBe("string");
    expect(input).toBe("http://localhost:8080/api/health");
    expect((init as RequestInit).method).toBe("GET");
  });

  it("carries no Authorization header in any header representation", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await fetchHealth();

    const init = vi.mocked(fetch).mock.calls[0][1] as RequestInit;
    const headers = new Headers(init.headers);
    expect(headers.has("Authorization")).toBe(false);
    expect(headers.has("authorization")).toBe(false);
    expect([...headers]).toEqual([]);
    expect(JSON.stringify(init)).not.toContain("Bearer");
  });

  it("keeps the shared trace id contract, accepting only a full match", async () => {
    mockFetchOnce(async () =>
      jsonResponse({ status: "UP", service: "backend" }, { headers: { "X-Trace-Id": "short" } }),
    );
    await expect(fetchHealth()).resolves.toEqual({
      data: { status: "UP", service: "backend" },
      traceId: undefined,
    });
    vi.unstubAllGlobals();

    mockFetchOnce(async () =>
      jsonResponse(
        { status: "UP", service: "backend" },
        { headers: { "X-Trace-Id": "trace0123abcd" } },
      ),
    );
    await expect(fetchHealth()).resolves.toEqual({
      data: { status: "UP", service: "backend" },
      traceId: "trace0123abcd",
    });
  });
});

/**
 * Issue #301: Health 성공 응답 projection 경계.
 *
 * 이 describe의 fetch double은 JSON 재직렬화 없이 테스트가 보관한 raw 객체 참조를 `response.json()`으로
 * 그대로 돌려준다. validator는 바꾸지 않고 승인된 두 필드만 새 객체로 게시되는지 확인한다.
 */
describe("fetchHealth — success response projection", () => {
  function stubHeldHealthBody(raw: object, headers: Record<string, string> = {}): void {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        const response = new Response(null, { status: 200, headers });
        Object.defineProperty(response, "json", { value: async () => raw });
        return response;
      }),
    );
  }

  it("returns a fresh result root and data carrying only status and service", async () => {
    const raw = { status: "UP", service: "backend" };
    stubHeldHealthBody(raw, { "X-Trace-Id": "trace0123abcd" });

    const result = await fetchHealth();

    expect(fetch).toHaveBeenCalledTimes(1);
    expect(result.data).not.toBe(raw);
    expect(Object.getPrototypeOf(result)).toBe(Object.prototype);
    expect(Object.getPrototypeOf(result.data)).toBe(Object.prototype);
    expect(new Set(Object.getOwnPropertyNames(result))).toEqual(new Set(["data", "traceId"]));
    expect(new Set(Object.getOwnPropertyNames(result.data))).toEqual(
      new Set(["status", "service"]),
    );
    expect(result).toStrictEqual({
      data: { status: "UP", service: "backend" },
      traceId: "trace0123abcd",
    });
  });

  it("does not let a later mutation of the held raw body reach the result and keeps an absent trace id key", async () => {
    const raw: Record<string, unknown> = { status: "UP", service: "backend" };
    stubHeldHealthBody(raw);

    const result = await fetchHealth();
    raw.status = "DOWN";
    raw.service = "ai-service";
    raw.extra = "unexpected";

    expect(result.data).toStrictEqual({ status: "UP", service: "backend" });
    expect(result.data).not.toHaveProperty("extra");
    // header가 없어도 기존 계약대로 traceId key는 undefined 값으로 남는다.
    expect(Object.prototype.hasOwnProperty.call(result, "traceId")).toBe(true);
    expect(result.traceId).toBeUndefined();
  });

  it("returns an independent result for every delivery of the same held raw body", async () => {
    const raw = { status: "UP", service: "backend" };
    stubHeldHealthBody(raw);

    const first = await fetchHealth();
    const second = await fetchHealth();

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(second).not.toBe(first);
    expect(first.data).not.toBe(raw);
    expect(second.data).not.toBe(raw);
    expect(second.data).not.toBe(first.data);
  });

  it("keeps a mutation of an earlier result out of the next delivery of the same raw body", async () => {
    const raw = { status: "UP", service: "backend" };
    stubHeldHealthBody(raw);

    const first = await fetchHealth();
    // 호출자가 첫 결과를 바꿔도 raw body와 다음 delivery는 검증된 원래 값을 유지해야 한다.
    Object.assign(first.data, { status: "DOWN" });
    const second = await fetchHealth();

    expect(raw).toStrictEqual({ status: "UP", service: "backend" });
    expect(second.data).toStrictEqual({ status: "UP", service: "backend" });
  });

  it("publishes an Object.prototype plain object for a held raw body with a null or foreign prototype", async () => {
    // JSON.parse는 만들 수 없지만 현재 Health validator가 허용하는 raw다.
    for (const prototype of [null, { inheritedSecret: "inherited-secret" }]) {
      const raw = { status: "UP", service: "backend" };
      Object.setPrototypeOf(raw, prototype);
      stubHeldHealthBody(raw);

      const result = await fetchHealth();

      expect(Object.getPrototypeOf(result.data)).toBe(Object.prototype);
      expect("inheritedSecret" in result.data).toBe(false);
      expect(result.data).toStrictEqual({ status: "UP", service: "backend" });
      vi.unstubAllGlobals();
    }
  });

  it("does not carry a non-enumerable or symbol-keyed own field of the held raw body", async () => {
    const hidden = Symbol("hidden");
    const raw = { status: "UP", service: "backend" };
    Object.defineProperty(raw, "hiddenSecret", { value: "hidden-secret", enumerable: false });
    Object.defineProperty(raw, hidden, { value: "symbol-secret", enumerable: true });
    stubHeldHealthBody(raw);

    const result = await fetchHealth();

    expect(new Set(Object.getOwnPropertyNames(result.data))).toEqual(
      new Set(["status", "service"]),
    );
    expect(Object.getOwnPropertySymbols(result.data)).toEqual([]);
    expect(result.data).not.toHaveProperty("hiddenSecret");
  });
});
