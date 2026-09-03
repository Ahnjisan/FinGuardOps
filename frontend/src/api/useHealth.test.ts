import { createElement, StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { subscribeToHealthRequest, useHealth } from "./useHealth";
import { jsonResponse } from "../test/mockFetch";

interface Deferred<T> {
  resolve: (value: T) => void;
  reject: (reason?: unknown) => void;
  promise: Promise<T>;
}

function createDeferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { resolve, reject, promise };
}

/** Each fetch() call gets its own deferred response, settled independently by the test. */
function stubQueuedFetch(): Array<Deferred<unknown>> {
  const deferredList: Array<Deferred<unknown>> = [];
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(() => {
      const deferred = createDeferred<unknown>();
      deferredList.push(deferred);
      return deferred.promise;
    }),
  );
  return deferredList;
}

interface ControlledJsonCall {
  readonly signal: AbortSignal;
  readonly resolveJson: (value: unknown) => void;
  readonly rejectJson: (error: unknown) => void;
}

/**
 * Each fetch() call resolves immediately with an ok response, but
 * response.json() returns a promise the test controls directly, and the
 * real signal passed to fetch() (the one httpClient/useHealth's registry
 * ultimately owns) is captured for direct AbortSignal assertions.
 */
function stubControlledJsonFetch(): ControlledJsonCall[] {
  const calls: ControlledJsonCall[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((_url: string, init?: RequestInit) => {
      let resolveJson!: (value: unknown) => void;
      let rejectJson!: (error: unknown) => void;
      const jsonPromise = new Promise<unknown>((res, rej) => {
        resolveJson = res;
        rejectJson = rej;
      });
      const signal = init?.signal as AbortSignal;
      calls.push({ signal, resolveJson, rejectJson });
      return Promise.resolve({
        ok: true,
        status: 200,
        headers: new Headers(),
        json: () => jsonPromise,
      } as unknown as Response);
    }),
  );
  return calls;
}

/** Rejects with AbortError once its captured signal aborts; never settles otherwise. */
function stubAbortCooperativeFetchOnce(): { getSignal: () => AbortSignal | undefined } {
  let capturedSignal: AbortSignal | undefined;
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementationOnce((_url: string, init?: RequestInit) => {
      const signal = init?.signal as AbortSignal;
      capturedSignal = signal;
      return new Promise<Response>((_resolve, reject) => {
        if (signal.aborted) {
          reject(new DOMException("The operation was aborted.", "AbortError"));
          return;
        }
        signal.addEventListener("abort", () => {
          reject(new DOMException("The operation was aborted.", "AbortError"));
        });
      });
    }),
  );
  return { getSignal: () => capturedSignal };
}

/**
 * First fetch() call is abort-cooperative (rejects once its signal
 * aborts); the second call resolves ok but its response.json() stays
 * pending until the test explicitly resolves it. Both calls share one
 * continuous mock so call counts accumulate correctly across both.
 */
function stubAbortThenControlledFetch(): {
  getSignalA: () => AbortSignal | undefined;
  resolveB: (value: unknown) => void;
} {
  let capturedSignalA: AbortSignal | undefined;
  let resolveB!: (value: unknown) => void;
  const fn = vi.fn();
  fn.mockImplementationOnce((_url: string, init?: RequestInit) => {
    const signal = init?.signal as AbortSignal;
    capturedSignalA = signal;
    return new Promise<Response>((_resolve, reject) => {
      signal.addEventListener("abort", () => {
        reject(new DOMException("The operation was aborted.", "AbortError"));
      });
    });
  });
  fn.mockImplementationOnce(() => {
    const jsonPromise = new Promise<unknown>((res) => {
      resolveB = res;
    });
    return Promise.resolve({
      ok: true,
      status: 200,
      headers: new Headers(),
      json: () => jsonPromise,
    } as unknown as Response);
  });
  vi.stubGlobal("fetch", fn);
  return {
    getSignalA: () => capturedSignalA,
    resolveB: (value: unknown) => resolveB(value),
  };
}

function queueNextFetchSuccess(): void {
  vi.mocked(fetch).mockImplementationOnce(
    async () => jsonResponse({ status: "UP", service: "backend" }),
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

/**
 * Drains pending microtasks (registry -> httpGet's Promise.race -> fetchHealth
 * -> .finally() -> subscriber callback is several hops deep) by yielding to a
 * real macrotask boundary, rather than guessing a fixed number of
 * `Promise.resolve()` hops.
 */
async function flushMicrotasks(): Promise<void> {
  await new Promise<void>((resolve) => {
    setImmediate(resolve);
  });
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("subscribeToHealthRequest — active/inactive delivery", () => {
  it("delivers a success result to an active subscriber", async () => {
    const deferred = stubQueuedFetch();
    const onSuccess = vi.fn();
    const onError = vi.fn();

    subscribeToHealthRequest(onSuccess, onError);
    deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
    await flushMicrotasks();

    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(onError).not.toHaveBeenCalled();
  });

  it("delivers an error to an active subscriber", async () => {
    const deferred = stubQueuedFetch();
    const onSuccess = vi.fn();
    const onError = vi.fn();
    const boom = new TypeError("Failed to fetch");

    subscribeToHealthRequest(onSuccess, onError);
    deferred[0].reject(boom);
    await flushMicrotasks();

    expect(onError).toHaveBeenCalledTimes(1);
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it("calls onSuccess zero times after unsubscribe, even though the request later resolves", async () => {
    const deferred = stubQueuedFetch();
    const onSuccess = vi.fn();
    const onError = vi.fn();

    const unsubscribe = subscribeToHealthRequest(onSuccess, onError);
    unsubscribe();
    deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
    await flushMicrotasks();

    expect(onSuccess).toHaveBeenCalledTimes(0);
    expect(onError).toHaveBeenCalledTimes(0);
  });

  it("calls onError zero times after unsubscribe, even though the request later rejects", async () => {
    const deferred = stubQueuedFetch();
    const onSuccess = vi.fn();
    const onError = vi.fn();

    const unsubscribe = subscribeToHealthRequest(onSuccess, onError);
    unsubscribe();
    deferred[0].reject(new TypeError("Failed to fetch"));
    await flushMicrotasks();

    expect(onSuccess).toHaveBeenCalledTimes(0);
    expect(onError).toHaveBeenCalledTimes(0);
  });

  it("treats unsubscribe as idempotent", async () => {
    const deferred = stubQueuedFetch();
    const onSuccess = vi.fn();

    const unsubscribe = subscribeToHealthRequest(onSuccess, vi.fn());
    expect(() => {
      unsubscribe();
      unsubscribe();
      unsubscribe();
    }).not.toThrow();

    deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
    await flushMicrotasks();

    expect(onSuccess).toHaveBeenCalledTimes(0);
  });

  it("is safe to unsubscribe after the request has already settled", async () => {
    const deferred = stubQueuedFetch();
    const onSuccess = vi.fn();

    const unsubscribe = subscribeToHealthRequest(onSuccess, vi.fn());
    deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
    await flushMicrotasks();

    expect(() => unsubscribe()).not.toThrow();
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it("delivers a shared request's result only to the subscriber that is still active, sharing a single fetch", async () => {
    const deferred = stubQueuedFetch();
    const staleOnSuccess = vi.fn();
    const activeOnSuccess = vi.fn();

    const staleUnsubscribe = subscribeToHealthRequest(staleOnSuccess, vi.fn());
    subscribeToHealthRequest(activeOnSuccess, vi.fn());
    expect(fetch).toHaveBeenCalledTimes(1);

    staleUnsubscribe();
    deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
    await flushMicrotasks();

    expect(staleOnSuccess).toHaveBeenCalledTimes(0);
    expect(activeOnSuccess).toHaveBeenCalledTimes(1);
  });

  it("never calls a callback more than once for a single settlement", async () => {
    const deferred = stubQueuedFetch();
    const onSuccess = vi.fn();

    subscribeToHealthRequest(onSuccess, vi.fn());
    deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
    await flushMicrotasks();
    await flushMicrotasks();

    expect(onSuccess).toHaveBeenCalledTimes(1);
  });
});

describe("subscribeToHealthRequest — registry release and deferred abort", () => {
  it("does not abort while at least one subscriber remains active", async () => {
    const calls = stubControlledJsonFetch();
    const unsubscribeA = subscribeToHealthRequest(vi.fn(), vi.fn());
    subscribeToHealthRequest(vi.fn(), vi.fn());
    expect(calls).toHaveLength(1);

    unsubscribeA();
    await flushMicrotasks();
    await flushMicrotasks();

    expect(calls[0].signal.aborted).toBe(false);

    calls[0].resolveJson({ status: "UP", service: "backend" });
    await flushMicrotasks();
  });

  it("aborts the underlying signal only after a deferred microtask once the last subscriber unsubscribes", async () => {
    const calls = stubControlledJsonFetch();
    const unsubscribe = subscribeToHealthRequest(vi.fn(), vi.fn());

    unsubscribe();
    // Not yet: cancellation is deferred to a microtask, not applied inline.
    expect(calls[0].signal.aborted).toBe(false);

    await flushMicrotasks();

    expect(calls[0].signal.aborted).toBe(true);
  });

  it("does not abort if a new subscriber re-subscribes within the same synchronous turn (StrictMode replay)", async () => {
    const calls = stubControlledJsonFetch();
    const onSuccess = vi.fn();

    const unsubscribeA = subscribeToHealthRequest(vi.fn(), vi.fn());
    unsubscribeA(); // count -> 0, schedules deferred abort
    subscribeToHealthRequest(onSuccess, vi.fn()); // synchronously re-subscribes before the microtask runs

    await flushMicrotasks();

    expect(calls).toHaveLength(1); // still only one real fetch call
    expect(calls[0].signal.aborted).toBe(false);

    calls[0].resolveJson({ status: "UP", service: "backend" });
    await flushMicrotasks();

    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it("does not let subscriber count go negative when cleanup runs more than once", async () => {
    const calls = stubControlledJsonFetch();
    const unsubscribe = subscribeToHealthRequest(vi.fn(), vi.fn());

    unsubscribe();
    unsubscribe();
    unsubscribe();
    await flushMicrotasks();

    expect(calls[0].signal.aborted).toBe(true);

    // A fresh subscriber after the abort must start a genuinely new entry,
    // proving the registry was not left in a corrupted (e.g. negative
    // count) state by the repeated cleanup calls.
    calls[0].resolveJson({ status: "UP", service: "backend" });
    await flushMicrotasks();

    const onSuccess = vi.fn();
    subscribeToHealthRequest(onSuccess, vi.fn());
    expect(calls).toHaveLength(2);
    calls[1].resolveJson({ status: "UP", service: "backend" });
    await flushMicrotasks();
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it("keeps a newer, still-pending entry (B) intact after an older aborted entry (A) settles late — identity comparison", async () => {
    const { getSignalA, resolveB } = stubAbortThenControlledFetch();

    const unsubscribeA = subscribeToHealthRequest(vi.fn(), vi.fn());
    unsubscribeA();

    // Exactly one microtask tick: lets the deferred release's queued
    // microtask run (calling controller.abort() and detaching A from the
    // registry) WITHOUT waiting for that abort to fully propagate through
    // fetch -> httpGet -> fetchHealth -> entry.promise's .finally(). This
    // is what lets B be created *before* A's .finally() has fired, so the
    // identity check actually has something to protect.
    await Promise.resolve();
    expect(getSignalA()?.aborted).toBe(true);

    // Entry B: a fresh subscriber created right after A was detached but
    // before A's own request has actually settled. B is left deliberately
    // pending (not resolved yet).
    const bOnSuccess = vi.fn();
    subscribeToHealthRequest(bOnSuccess, vi.fn());
    expect(fetch).toHaveBeenCalledTimes(2);
    expect(bOnSuccess).not.toHaveBeenCalled();

    // Now let A's underlying fetch actually finish rejecting (from the
    // abort above) and its `.finally()` fire, well after B took over the
    // registry slot, while B is *still pending*. This must not clobber
    // B's registration.
    await flushMicrotasks();
    await flushMicrotasks();

    // A third subscriber must share B's still-pending entry rather than
    // starting a new fetch — proving A's late finally did not clear the
    // registry out from under B.
    const cOnSuccess = vi.fn();
    subscribeToHealthRequest(cOnSuccess, vi.fn());
    expect(fetch).toHaveBeenCalledTimes(2);

    resolveB({ status: "UP", service: "backend" });
    await flushMicrotasks();

    expect(bOnSuccess).toHaveBeenCalledTimes(1);
    expect(cOnSuccess).toHaveBeenCalledTimes(1);
  });
});

describe("useHealth — full AbortSignal wiring on unmount (success and error paths)", () => {
  it("success path: unmount aborts the signal via deferred cleanup, and a late success is ignored with no unhandled rejection", async () => {
    const capture = captureUnhandledRejections();
    const calls = stubControlledJsonFetch();

    const { unmount } = renderHook(() => useHealth());
    expect(calls).toHaveLength(1);
    expect(calls[0].signal.aborted).toBe(false);

    unmount();
    await flushMicrotasks();

    expect(calls[0].signal.aborted).toBe(true);

    calls[0].resolveJson({ status: "UP", service: "backend" });
    await flushMicrotasks();
    await flushMicrotasks();

    expect(capture.errors).toEqual([]);
    capture.restore();
  });

  it("error path: unmount aborts the signal via deferred cleanup, and a late rejection is ignored with no unhandled rejection", async () => {
    const capture = captureUnhandledRejections();
    const calls = stubControlledJsonFetch();

    const { unmount } = renderHook(() => useHealth());
    expect(calls).toHaveLength(1);
    expect(calls[0].signal.aborted).toBe(false);

    unmount();
    await flushMicrotasks();

    expect(calls[0].signal.aborted).toBe(true);

    calls[0].rejectJson(new TypeError("late failure"));
    await flushMicrotasks();
    await flushMicrotasks();

    expect(capture.errors).toEqual([]);
    capture.restore();
  });
});

describe("useHealth — StrictMode, multi-subscriber and remount", () => {
  it("fetches exactly once on the first logical mount and the signal is not aborted while mounted", async () => {
    const calls = stubControlledJsonFetch();

    const { result } = renderHook(() => useHealth(), {
      wrapper: ({ children }) => createElement(StrictMode, null, children),
    });

    expect(calls).toHaveLength(1);
    expect(calls[0].signal.aborted).toBe(false);

    await act(async () => {
      calls[0].resolveJson({ status: "UP", service: "backend" });
      await flushMicrotasks();
    });

    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    expect(calls[0].signal.aborted).toBe(false);
  });

  it("shares one pending request across two concurrently mounted subscribers", async () => {
    const calls = stubControlledJsonFetch();

    const first = renderHook(() => useHealth());
    const second = renderHook(() => useHealth());
    expect(calls).toHaveLength(1);

    await act(async () => {
      calls[0].resolveJson({ status: "UP", service: "backend" });
      await flushMicrotasks();
    });

    await waitFor(() => {
      expect(first.result.current.state.status).toBe("success");
      expect(second.result.current.state.status).toBe("success");
    });
  });

  it("does not abort when only the first of two subscribers unmounts", async () => {
    const calls = stubControlledJsonFetch();

    const first = renderHook(() => useHealth());
    renderHook(() => useHealth());
    expect(calls).toHaveLength(1);

    first.unmount();
    await flushMicrotasks();
    await flushMicrotasks();

    expect(calls[0].signal.aborted).toBe(false);

    await act(async () => {
      calls[0].resolveJson({ status: "UP", service: "backend" });
      await flushMicrotasks();
    });
  });

  it("aborts only after the last of two subscribers unmounts, following the deferred cleanup", async () => {
    const calls = stubControlledJsonFetch();

    const first = renderHook(() => useHealth());
    const second = renderHook(() => useHealth());
    expect(calls).toHaveLength(1);

    first.unmount();
    await flushMicrotasks();
    expect(calls[0].signal.aborted).toBe(false);

    second.unmount();
    await flushMicrotasks();
    await flushMicrotasks();

    expect(calls[0].signal.aborted).toBe(true);
  });

  it("starts a new fetch on a genuine mount after the last subscriber's abort completed", async () => {
    const abortCooperative = stubAbortCooperativeFetchOnce();

    const first = renderHook(() => useHealth());
    first.unmount();
    await flushMicrotasks();
    expect(abortCooperative.getSignal()?.aborted).toBe(true);

    queueNextFetchSuccess();
    const second = renderHook(() => useHealth());
    expect(fetch).toHaveBeenCalledTimes(2);

    await waitFor(() => {
      expect(second.result.current.state.status).toBe("success");
    });
  });

  it("starts a new fetch on a genuine mount after the previous request already settled normally", async () => {
    const calls = stubControlledJsonFetch();

    const first = renderHook(() => useHealth());
    await act(async () => {
      calls[0].resolveJson({ status: "UP", service: "backend" });
      await flushMicrotasks();
    });
    await waitFor(() => {
      expect(first.result.current.state.status).toBe("success");
    });
    first.unmount();

    queueNextFetchSuccess();
    const second = renderHook(() => useHealth());
    expect(fetch).toHaveBeenCalledTimes(2);

    await waitFor(() => {
      expect(second.result.current.state.status).toBe("success");
    });
  });

  it("removes a rejected (non-aborted) request from the registry once it settles", async () => {
    const deferred = stubQueuedFetch();

    const first = renderHook(() => useHealth());
    await act(async () => {
      deferred[0].reject(new TypeError("Failed to fetch"));
      await flushMicrotasks();
    });
    await waitFor(() => {
      expect(first.result.current.state.status).toBe("error");
    });
    first.unmount();

    stubQueuedFetch();
    renderHook(() => useHealth());
    expect(fetch).toHaveBeenCalledTimes(1); // relative to the freshly-stubbed mock
  });

  it("does not leave a pending request behind (settle before the test ends)", async () => {
    const calls = stubControlledJsonFetch();
    const { result, unmount } = renderHook(() => useHealth());

    await act(async () => {
      calls[0].resolveJson({ status: "UP", service: "backend" });
      await flushMicrotasks();
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    unmount();
  });
});

describe("useHealth — retry guards (unchanged contract)", () => {
  it("ignores retry() while the initial request is still pending", async () => {
    const deferred = stubQueuedFetch();

    const { result } = renderHook(() => useHealth());
    expect(fetch).toHaveBeenCalledTimes(1);

    act(() => {
      result.current.retry();
    });
    expect(fetch).toHaveBeenCalledTimes(1);

    await act(async () => {
      deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
      await flushMicrotasks();
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
  });

  it("starts exactly one additional fetch when retry() is called after an error", async () => {
    const deferred = stubQueuedFetch();

    const { result } = renderHook(() => useHealth());
    await act(async () => {
      deferred[0].reject(new TypeError("Failed to fetch"));
      await flushMicrotasks();
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("error");
    });
    expect(fetch).toHaveBeenCalledTimes(1);

    act(() => {
      result.current.retry();
    });
    expect(fetch).toHaveBeenCalledTimes(2);

    await act(async () => {
      deferred[1].resolve(jsonResponse({ status: "UP", service: "backend" }));
      await flushMicrotasks();
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
  });

  it("ignores retry() once the request has already succeeded", async () => {
    const deferred = stubQueuedFetch();

    const { result } = renderHook(() => useHealth());
    await act(async () => {
      deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
      await flushMicrotasks();
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("success");
    });
    expect(fetch).toHaveBeenCalledTimes(1);

    act(() => {
      result.current.retry();
    });
    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("does not retry automatically after a failure", async () => {
    const deferred = stubQueuedFetch();

    const { result } = renderHook(() => useHealth());
    await act(async () => {
      deferred[0].reject(new TypeError("Failed to fetch"));
      await flushMicrotasks();
    });
    await waitFor(() => {
      expect(result.current.state.status).toBe("error");
    });

    await flushMicrotasks();
    expect(fetch).toHaveBeenCalledTimes(1);
  });
});
