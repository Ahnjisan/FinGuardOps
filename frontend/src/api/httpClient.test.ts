import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpError, InvalidResponseError, NetworkError, TimeoutError } from "./errors";
import { httpGet } from "./httpClient";
import {
  jsonResponse,
  mockFetchAbortOnce,
  mockFetchHangForever,
  mockFetchOkWithControlledJson,
  mockFetchOnce,
  mockFetchRejectOnce,
} from "../test/mockFetch";

const TIMEOUT_MS = 5000;

/**
 * Rejects with AbortError as soon as the signal fetch() receives is
 * aborted — whether it was already aborted before the call or aborts
 * later. Unlike mockFetchAbortOnce, this also checks `signal.aborted`
 * up front, since a real AbortSignal never (re-)fires its "abort" event
 * for a listener attached after the signal already aborted.
 */
function mockFetchAbortCooperative(): void {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementationOnce((_input: RequestInfo | URL, init?: RequestInit) => {
      const signal = init?.signal;
      return new Promise<Response>((_resolve, reject) => {
        const abort = () => {
          reject(new DOMException("The operation was aborted.", "AbortError"));
        };
        if (signal?.aborted) {
          abort();
          return;
        }
        signal?.addEventListener("abort", abort);
      });
    }),
  );
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

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("httpGet — success and classification", () => {
  it("resolves with status, headers and parsed body on a 2xx response", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    const result = await httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });

    expect(result.status).toBe(200);
    expect(result.body).toEqual({ status: "UP", service: "backend" });
  });

  it("calls fetch exactly once per request", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("issues a GET request with no body", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });

    const call = vi.mocked(fetch).mock.calls[0];
    const init = call[1] as RequestInit;
    expect(init.method).toBe("GET");
    expect(init.body).toBeUndefined();
  });

  it("does not add an Authorization header in any casing or form", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });

    const call = vi.mocked(fetch).mock.calls[0];
    const init = call[1] as RequestInit;
    const normalizedHeaders = new Headers(init.headers);
    expect(normalizedHeaders.has("Authorization")).toBe(false);
    expect(normalizedHeaders.has("authorization")).toBe(false);
  });

  it("does not call any URL other than the one requested", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });

    const call = vi.mocked(fetch).mock.calls[0];
    expect(call[0]).toBe("http://localhost:8080/api/health");
  });

  it("throws HttpError for a 4xx response", async () => {
    mockFetchOnce(async () => jsonResponse({ error: "not found" }, { status: 404 }));

    await expect(httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS })).rejects.toBeInstanceOf(
      HttpError,
    );
  });

  it("throws HttpError for a 5xx response", async () => {
    mockFetchOnce(async () => jsonResponse({ error: "boom" }, { status: 503 }));

    await expect(httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS })).rejects.toBeInstanceOf(
      HttpError,
    );
  });

  it("does not read or expose the response body for a non-2xx status", async () => {
    const jsonSpy = vi.fn(() => {
      throw new Error("body must not be read for non-2xx responses");
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: false,
        status: 500,
        headers: new Headers(),
        json: jsonSpy,
      }),
    );

    await expect(httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS })).rejects.toBeInstanceOf(
      HttpError,
    );
    expect(jsonSpy).not.toHaveBeenCalled();
  });

  it("throws NetworkError when fetch rejects", async () => {
    mockFetchRejectOnce(new TypeError("Failed to fetch"));

    await expect(httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS })).rejects.toBeInstanceOf(
      NetworkError,
    );
  });

  it("throws InvalidResponseError for malformed JSON, not HttpError", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: () => Promise.reject(new SyntaxError("Unexpected token")),
      }),
    );

    await expect(httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS })).rejects.toBeInstanceOf(
      InvalidResponseError,
    );
  });

  it("does not expose the raw response body in a non-2xx error message", async () => {
    mockFetchOnce(async () => jsonResponse({ secret: "do-not-leak" }, { status: 500 }));

    const error = await httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS }).catch(
      (caught: unknown) => caught,
    );

    expect((error as Error).message).not.toContain("do-not-leak");
  });
});

describe("httpGet — full-lifecycle deadline", () => {
  it("throws TimeoutError when fetch() itself never settles (cooperative abort)", async () => {
    vi.useFakeTimers();
    mockFetchAbortOnce();

    const pending = httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);

    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;
  });

  it("throws TimeoutError when fetch() never settles and ignores the abort signal entirely", async () => {
    vi.useFakeTimers();
    mockFetchHangForever();

    const pending = httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);

    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("throws TimeoutError when fetch resolves but response.json() hangs and ignores abort", async () => {
    vi.useFakeTimers();
    mockFetchOkWithControlledJson();

    const pending = httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);

    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;
  });

  it("does not use a separate fetch-timeout plus body-timeout (bounded to a single 5s deadline, not 10s)", async () => {
    vi.useFakeTimers();
    const { resolveJson } = mockFetchOkWithControlledJson();

    const pending = httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);

    // Advance to just past the single overall deadline; if the implementation
    // wrongly applied fetch-timeout + body-timeout separately, this would
    // still be pending at this point instead of already rejected.
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS + 1);
    await assertion;

    resolveJson({ status: "UP", service: "backend" });
  });

  it("resolves normally when body completes just before the deadline", async () => {
    vi.useFakeTimers();
    const { resolveJson } = mockFetchOkWithControlledJson();

    const pending = httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });

    await vi.advanceTimersByTimeAsync(TIMEOUT_MS - 100);
    resolveJson({ status: "UP", service: "backend" });

    await expect(pending).resolves.toEqual(
      expect.objectContaining({ body: { status: "UP", service: "backend" } }),
    );
  });

  it("does not produce an unhandled rejection when the body resolves after the timeout has already settled", async () => {
    vi.useFakeTimers();
    const capture = captureUnhandledRejections();
    const { resolveJson } = mockFetchOkWithControlledJson();

    const pending = httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    resolveJson({ status: "UP", service: "backend" });
    await vi.advanceTimersByTimeAsync(0);
    await Promise.resolve();
    await Promise.resolve();

    expect(capture.errors).toEqual([]);
    capture.restore();
  });

  it("does not produce an unhandled rejection when the body rejects after the timeout has already settled", async () => {
    vi.useFakeTimers();
    const capture = captureUnhandledRejections();
    const { rejectJson } = mockFetchOkWithControlledJson();

    const pending = httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });
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

  it("clears its timer after a successful request completes (no lingering timer)", async () => {
    vi.useFakeTimers();
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });

    expect(vi.getTimerCount()).toBe(0);
  });

  it("clears its timer after a non-2xx failure completes (no lingering timer)", async () => {
    vi.useFakeTimers();
    mockFetchOnce(async () => jsonResponse({ error: "boom" }, { status: 500 }));

    await httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS }).catch(() => undefined);

    expect(vi.getTimerCount()).toBe(0);
  });

  it("calls fetch exactly once even when the request times out (no automatic retry)", async () => {
    vi.useFakeTimers();
    mockFetchHangForever();

    const pending = httpGet("http://localhost:8080/api/health", { timeoutMs: TIMEOUT_MS });
    const settled = pending.catch(() => undefined);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await settled;

    expect(fetch).toHaveBeenCalledTimes(1);
  });
});

describe("httpGet — external cancellation (signal option)", () => {
  it("forwards an external signal abort to the signal fetch() actually receives", async () => {
    mockFetchAbortCooperative();
    const externalController = new AbortController();

    const pending = httpGet("http://localhost:8080/api/health", {
      timeoutMs: TIMEOUT_MS,
      signal: externalController.signal,
    });

    const fetchSignal = vi.mocked(fetch).mock.calls[0][1]?.signal;
    expect(fetchSignal?.aborted).toBe(false);

    externalController.abort();

    expect(fetchSignal?.aborted).toBe(true);
    await expect(pending).rejects.toBeInstanceOf(NetworkError);
  });

  it("classifies an external abort as NetworkError, never TimeoutError", async () => {
    mockFetchAbortCooperative();
    const externalController = new AbortController();

    const pending = httpGet("http://localhost:8080/api/health", {
      timeoutMs: TIMEOUT_MS,
      signal: externalController.signal,
    });
    externalController.abort();

    const error = await pending.catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(NetworkError);
    expect(error).not.toBeInstanceOf(TimeoutError);
  });

  it("aborts immediately if the external signal is already aborted before the call", async () => {
    mockFetchAbortCooperative();
    const externalController = new AbortController();
    externalController.abort();

    const pending = httpGet("http://localhost:8080/api/health", {
      timeoutMs: TIMEOUT_MS,
      signal: externalController.signal,
    });

    await expect(pending).rejects.toBeInstanceOf(NetworkError);
  });

  it("still applies its own deadline as TimeoutError when only the internal timer fires (external signal present but never aborted)", async () => {
    vi.useFakeTimers();
    mockFetchHangForever();
    const externalController = new AbortController();

    const pending = httpGet("http://localhost:8080/api/health", {
      timeoutMs: TIMEOUT_MS,
      signal: externalController.signal,
    });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);

    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;
  });

  it("removes the external abort listener after a successful settle", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));
    const externalController = new AbortController();
    const removeSpy = vi.spyOn(externalController.signal, "removeEventListener");

    await httpGet("http://localhost:8080/api/health", {
      timeoutMs: TIMEOUT_MS,
      signal: externalController.signal,
    });

    expect(removeSpy).toHaveBeenCalledWith("abort", expect.any(Function));
  });

  it("removes the external abort listener after a non-2xx failure", async () => {
    mockFetchOnce(async () => jsonResponse({ error: "boom" }, { status: 500 }));
    const externalController = new AbortController();
    const removeSpy = vi.spyOn(externalController.signal, "removeEventListener");

    await httpGet("http://localhost:8080/api/health", {
      timeoutMs: TIMEOUT_MS,
      signal: externalController.signal,
    }).catch(() => undefined);

    expect(removeSpy).toHaveBeenCalledWith("abort", expect.any(Function));
  });

  it("removes the external abort listener after its own deadline times out", async () => {
    vi.useFakeTimers();
    mockFetchHangForever();
    const externalController = new AbortController();
    const removeSpy = vi.spyOn(externalController.signal, "removeEventListener");

    const pending = httpGet("http://localhost:8080/api/health", {
      timeoutMs: TIMEOUT_MS,
      signal: externalController.signal,
    });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    expect(removeSpy).toHaveBeenCalledWith("abort", expect.any(Function));
  });

  it("calls fetch exactly once even when an external signal option is provided", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));
    const externalController = new AbortController();

    await httpGet("http://localhost:8080/api/health", {
      timeoutMs: TIMEOUT_MS,
      signal: externalController.signal,
    });

    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
