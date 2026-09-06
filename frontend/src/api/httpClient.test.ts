import { afterEach, describe, expect, it, vi } from "vitest";
import { HttpError, InvalidResponseError, NetworkError, TimeoutError } from "./errors";
import { httpGet, httpRequest } from "./httpClient";
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

describe("httpRequest — preparation inside the same deadline", () => {
  const okDispatch = () => Promise.resolve(jsonResponse({ ok: true }));

  it("reaches the network in the caller's own turn when preparation is synchronous", async () => {
    const dispatch = vi.fn(okDispatch);

    const pending = httpRequest({ timeoutMs: TIMEOUT_MS, prepare: () => dispatch });

    expect(dispatch).toHaveBeenCalledTimes(1);
    await pending;
  });

  it("propagates a preparation failure unchanged instead of calling it a network error", async () => {
    class NoSessionError extends Error {}
    const dispatch = vi.fn(okDispatch);

    const failing = httpRequest({
      timeoutMs: TIMEOUT_MS,
      prepare: () => Promise.reject(new NoSessionError()),
    });

    await expect(failing).rejects.toBeInstanceOf(NoSessionError);
    expect(dispatch).not.toHaveBeenCalled();
  });

  it("times out while preparation is pending and never dispatches", async () => {
    vi.useFakeTimers();
    const dispatch = vi.fn(okDispatch);

    const pending = httpRequest({
      timeoutMs: TIMEOUT_MS,
      prepare: () => new Promise<typeof dispatch>(() => {}),
    });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    expect(dispatch).not.toHaveBeenCalled();
  });

  it("does not dispatch when preparation finishes after the deadline", async () => {
    vi.useFakeTimers();
    const dispatch = vi.fn(okDispatch);
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });

    const pending = httpRequest({
      timeoutMs: TIMEOUT_MS,
      prepare: async () => {
        await gate;
        return dispatch;
      },
    });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);
    await vi.advanceTimersByTimeAsync(TIMEOUT_MS);
    await assertion;

    release();
    await vi.advanceTimersByTimeAsync(0);
    await Promise.resolve();

    expect(dispatch).not.toHaveBeenCalled();
  });

  it("bounds preparation plus body to one budget rather than one each", async () => {
    vi.useFakeTimers();
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });
    const controlled = mockFetchOkWithControlledJson();

    const pending = httpRequest({
      timeoutMs: TIMEOUT_MS,
      prepare: async (signal: AbortSignal) => {
        await gate;
        return () => fetch("http://localhost:8080/api/v1/cases", { signal });
      },
    });
    const assertion = expect(pending).rejects.toBeInstanceOf(TimeoutError);

    await vi.advanceTimersByTimeAsync(TIMEOUT_MS - 200);
    release();
    await vi.advanceTimersByTimeAsync(201);
    await assertion;

    controlled.resolveJson({ ok: true });
  });

  it("asks for nothing and dispatches nothing when the signal is already aborted", async () => {
    const controller = new AbortController();
    controller.abort();
    const prepare = vi.fn(() => okDispatch);

    await expect(
      httpRequest({ timeoutMs: TIMEOUT_MS, signal: controller.signal, prepare }),
    ).rejects.toBeInstanceOf(NetworkError);
    expect(prepare).not.toHaveBeenCalled();
  });

  it("does not dispatch a request cancelled while preparation was pending", async () => {
    const controller = new AbortController();
    const dispatch = vi.fn(okDispatch);
    let release!: () => void;
    const gate = new Promise<void>((resolve) => {
      release = resolve;
    });

    const pending = httpRequest({
      timeoutMs: TIMEOUT_MS,
      signal: controller.signal,
      prepare: async () => {
        await gate;
        return dispatch;
      },
    });
    await Promise.resolve();
    controller.abort();
    release();

    await expect(pending).rejects.toBeInstanceOf(NetworkError);
    expect(dispatch).not.toHaveBeenCalled();
  });
});

describe("httpRequest — status classification and validation hooks", () => {
  it("uses the caller's classifier for a non-2xx status without reading the body", async () => {
    class Denied extends Error {
      constructor(readonly traceId: string | null) {
        super("denied");
      }
    }
    const jsonSpy = vi.fn(() => {
      throw new Error("body must not be read");
    });

    const failing = httpRequest({
      timeoutMs: TIMEOUT_MS,
      prepare: () => () =>
        Promise.resolve({
          ok: false,
          status: 403,
          headers: new Headers({ "X-Trace-Id": "trace0123abcd" }),
          json: jsonSpy,
        } as unknown as Response),
      classifyErrorStatus: (response: Response) => new Denied(response.headers.get("X-Trace-Id")),
    });

    const error = (await failing.catch((caught: unknown) => caught)) as Denied;
    expect(error).toBeInstanceOf(Denied);
    expect(error.traceId).toBe("trace0123abcd");
    expect(jsonSpy).not.toHaveBeenCalled();
  });

  it("falls back to HttpError when no classifier is supplied", async () => {
    mockFetchOnce(async () => jsonResponse({ error: "boom" }, { status: 500 }));

    await expect(
      httpRequest({
        timeoutMs: TIMEOUT_MS,
        prepare: (signal: AbortSignal) => () => fetch("http://localhost:8080/x", { signal }),
      }),
    ).rejects.toBeInstanceOf(HttpError);
  });

  it("turns a validator rejection into InvalidResponseError", async () => {
    mockFetchOnce(async () => jsonResponse({ unexpected: true }));

    await expect(
      httpRequest({
        timeoutMs: TIMEOUT_MS,
        prepare: (signal: AbortSignal) => () => fetch("http://localhost:8080/x", { signal }),
        validate: (body: unknown): body is { ok: true } =>
          typeof body === "object" && body !== null && "ok" in body,
      }),
    ).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("returns the validated body when the validator accepts it", async () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    const result = await httpRequest({
      timeoutMs: TIMEOUT_MS,
      prepare: (signal: AbortSignal) => () => fetch("http://localhost:8080/x", { signal }),
      validate: (body: unknown): body is { ok: true } =>
        typeof body === "object" && body !== null && "ok" in body,
    });

    expect(result.body.ok).toBe(true);
  });
});

/**
 * Replaces the monotonic clock with one the test drives directly.
 *
 * These cases are about work that blocks the event loop, so the timer callback
 * is exactly what cannot run. Fake timers would prove nothing here: the point
 * is that the deadline still holds when no timer ever fires.
 */
function stubMonotonicClock(): { advanceTo: (ms: number) => void } {
  let current = 0;
  vi.spyOn(performance, "now").mockImplementation(() => current);
  return {
    advanceTo: (ms: number) => {
      current = ms;
    },
  };
}

describe("httpRequest — deadline holds without a timer callback", () => {
  const okFetch = () => {
    mockFetchOnce(async () => jsonResponse({ ok: true }));
  };

  const isOk = (body: unknown): body is { ok: true } =>
    typeof body === "object" && body !== null && "ok" in body;

  function requestWithValidator(validate: (body: unknown) => body is { ok: true }) {
    return httpRequest({
      timeoutMs: TIMEOUT_MS,
      prepare: (signal: AbortSignal) => () =>
        fetch("http://localhost:8080/api/v1/cases", { signal }),
      validate,
    });
  }

  it("accepts a synchronous validator that finishes at 4,999ms", async () => {
    const clock = stubMonotonicClock();
    okFetch();

    const result = await requestWithValidator((body): body is { ok: true } => {
      clock.advanceTo(4999);
      return isOk(body);
    });

    expect(result.body).toEqual({ ok: true });
  });

  it("rejects a synchronous validator that finishes at exactly 5,000ms", async () => {
    const clock = stubMonotonicClock();
    okFetch();

    await expect(
      requestWithValidator((body): body is { ok: true } => {
        clock.advanceTo(5000);
        return isOk(body);
      }),
    ).rejects.toBeInstanceOf(TimeoutError);
  });

  it("rejects a synchronous validator that finishes at 5,001ms", async () => {
    const clock = stubMonotonicClock();
    okFetch();

    await expect(
      requestWithValidator((body): body is { ok: true } => {
        clock.advanceTo(5001);
        return isOk(body);
      }),
    ).rejects.toBeInstanceOf(TimeoutError);
  });

  it("does not adopt a success returned by a validator that ran 6,000ms", async () => {
    const clock = stubMonotonicClock();
    okFetch();

    await expect(
      requestWithValidator((body): body is { ok: true } => {
        clock.advanceTo(6000);
        return isOk(body);
      }),
    ).rejects.toBeInstanceOf(TimeoutError);
  });

  /**
   * A validator that both overran and rejected the body is a timeout, not a
   * contract violation: reporting "malformed response" would be a claim the
   * client is in no position to make.
   */
  it("reports a timeout, not an invalid response, when a late validator also rejects", async () => {
    const clock = stubMonotonicClock();
    okFetch();

    const error = await requestWithValidator((body): body is { ok: true } => {
      clock.advanceTo(6000);
      void body;
      return false;
    }).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(TimeoutError);
    expect(error).not.toBeInstanceOf(InvalidResponseError);
  });

  it("still reports an invalid response when the validator rejects in time", async () => {
    const clock = stubMonotonicClock();
    okFetch();

    const error = await requestWithValidator((body): body is { ok: true } => {
      clock.advanceTo(10);
      void body;
      return false;
    }).catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(InvalidResponseError);
  });

  it("times out when preparation overran, without ever dispatching", async () => {
    const clock = stubMonotonicClock();
    const dispatch = vi.fn(async () => jsonResponse({ ok: true }));

    const pending = httpRequest({
      timeoutMs: TIMEOUT_MS,
      prepare: () => {
        clock.advanceTo(5000);
        return dispatch;
      },
    });

    await expect(pending).rejects.toBeInstanceOf(TimeoutError);
    expect(dispatch).not.toHaveBeenCalled();
  });

  it("times out when the caller was already past the deadline on entry", async () => {
    const clock = stubMonotonicClock();
    const prepare = vi.fn(() => async () => jsonResponse({ ok: true }));
    clock.advanceTo(0);

    // timeoutMs of zero means the deadline is the very instant of entry.
    await expect(httpRequest({ timeoutMs: 0, prepare })).rejects.toBeInstanceOf(TimeoutError);
    expect(prepare).not.toHaveBeenCalled();
  });

  it("leaves no timer behind when the deadline is detected by an explicit check", async () => {
    vi.useFakeTimers();
    const clock = stubMonotonicClock();
    mockFetchOnce(async () => jsonResponse({ ok: true }));

    await requestWithValidator((body): body is { ok: true } => {
      clock.advanceTo(6000);
      return isOk(body);
    }).catch(() => undefined);

    expect(vi.getTimerCount()).toBe(0);
  });
});

/**
 * `response.ok` is a range, and a contract is not. An endpoint that promises
 * 200 answering 202, or one that promises 201 answering 200, is not the
 * response the caller asked for, so it is refused before the body is read.
 */
describe("httpRequest — exact success status", () => {
  function requestWithExpectedStatus(expectedStatus: number, responseStatus: number) {
    return httpRequest({
      timeoutMs: 1000,
      expectedStatus,
      prepare: () => () =>
        Promise.resolve(
          responseStatus === 204
            ? new Response(null, { status: 204 })
            : new Response(JSON.stringify({ ok: true }), {
                status: responseStatus,
                headers: { "Content-Type": "application/json" },
              }),
        ),
    });
  }

  it("accepts the exact expected status", async () => {
    await expect(requestWithExpectedStatus(200, 200)).resolves.toMatchObject({ status: 200 });
    await expect(requestWithExpectedStatus(201, 201)).resolves.toMatchObject({ status: 201 });
  });

  it("refuses any other 2xx", async () => {
    for (const [expected, actual] of [
      [200, 201],
      [200, 202],
      [200, 204],
      [200, 206],
      [201, 200],
      [201, 202],
    ] as ReadonlyArray<[number, number]>) {
      await expect(requestWithExpectedStatus(expected, actual)).rejects.toBeInstanceOf(
        InvalidResponseError,
      );
    }
  });

  it("does not read the body of an unexpected 2xx", async () => {
    let jsonReads = 0;
    await expect(
      httpRequest({
        timeoutMs: 1000,
        expectedStatus: 200,
        prepare: () => () =>
          Promise.resolve({
            ok: true,
            status: 202,
            headers: new Headers(),
            json: () => {
              jsonReads += 1;
              return Promise.resolve({ ok: true });
            },
          } as unknown as Response),
      }),
    ).rejects.toBeInstanceOf(InvalidResponseError);
    expect(jsonReads).toBe(0);
  });

  it("still classifies a non-2xx by status rather than as an unexpected success", async () => {
    await expect(
      httpRequest({
        timeoutMs: 1000,
        expectedStatus: 200,
        prepare: () => () => Promise.resolve(new Response("{}", { status: 404 })),
      }),
    ).rejects.toBeInstanceOf(HttpError);
  });

  it("performs exactly one fetch for an unexpected 2xx", async () => {
    let dispatches = 0;
    await expect(
      httpRequest({
        timeoutMs: 1000,
        expectedStatus: 200,
        prepare: () => () => {
          dispatches += 1;
          return Promise.resolve(new Response("{}", { status: 202 }));
        },
      }),
    ).rejects.toBeInstanceOf(InvalidResponseError);
    expect(dispatches).toBe(1);
  });

  it("leaves every status alone when no expectation is declared", async () => {
    for (const status of [200, 201, 202, 206]) {
      await expect(
        httpRequest({
          timeoutMs: 1000,
          prepare: () => () =>
            Promise.resolve(
              new Response(JSON.stringify({ ok: true }), {
                status,
                headers: { "Content-Type": "application/json" },
              }),
            ),
        }),
      ).resolves.toMatchObject({ status });
    }
  });
});
