import { HttpError, InvalidResponseError, NetworkError, TimeoutError } from "./errors";

export interface HttpGetOptions {
  timeoutMs: number;
  /**
   * Optional external cancellation (e.g. "no subscriber is listening
   * anymore"). Forwarded into the same AbortController used for the
   * deadline, so fetch observes a single combined signal. Aborting via this
   * signal is classified as NetworkError, never TimeoutError — only this
   * request's own deadline produces TimeoutError.
   */
  signal?: AbortSignal;
}

export interface HttpGetResult {
  readonly status: number;
  readonly headers: Headers;
  readonly body: unknown;
}

async function performRequest(
  url: string,
  signal: AbortSignal,
  isDeadlineExceeded: () => boolean,
): Promise<HttpGetResult> {
  let response: Response;
  try {
    response = await fetch(url, {
      method: "GET",
      signal,
    });
  } catch {
    if (isDeadlineExceeded()) {
      throw new TimeoutError();
    }
    throw new NetworkError();
  }

  if (!response.ok) {
    throw new HttpError(response.status);
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch {
    if (isDeadlineExceeded()) {
      throw new TimeoutError();
    }
    throw new InvalidResponseError();
  }

  return {
    status: response.status,
    headers: response.headers,
    body,
  };
}

/**
 * Bounds the entire request lifecycle (fetch start, headers, status check, body
 * read, JSON parse) to a single deadline, not just the fetch() promise.
 */
export async function httpGet(url: string, options: HttpGetOptions): Promise<HttpGetResult> {
  const controller = new AbortController();
  let deadlineExceeded = false;
  let rejectOnDeadline!: (error: TimeoutError) => void;

  const deadlinePromise = new Promise<never>((_resolve, reject) => {
    rejectOnDeadline = reject;
  });

  const timeoutId = setTimeout(() => {
    deadlineExceeded = true;
    controller.abort();
    rejectOnDeadline(new TimeoutError());
  }, options.timeoutMs);

  // External cancellation (e.g. the last subscriber unmounted) forwards
  // into the same controller passed to fetch, so callers observe a single
  // combined signal. This does not set deadlineExceeded, so it is
  // classified as NetworkError, never TimeoutError.
  const externalSignal = options.signal;
  const onExternalAbort = () => {
    controller.abort();
  };
  if (externalSignal) {
    if (externalSignal.aborted) {
      controller.abort();
    } else {
      externalSignal.addEventListener("abort", onExternalAbort);
    }
  }

  // Attach a handler to the deadline promise immediately and independently
  // of Promise.race, so a late-firing timer rejection is never observed as
  // unhandled regardless of exactly when the racing promise's own internal
  // subscription is processed.
  const silencedDeadlinePromise = deadlinePromise.catch(() => undefined);

  const workPromise = performRequest(url, controller.signal, () => deadlineExceeded);
  // If the deadline wins the race, workPromise's eventual settlement (which
  // may be a late rejection, e.g. an aborted body read) must still have its
  // own handler so it never surfaces as an unhandled promise rejection.
  const silencedWorkPromise = workPromise.catch(() => undefined);

  try {
    return await Promise.race([workPromise, deadlinePromise]);
  } finally {
    clearTimeout(timeoutId);
    if (externalSignal) {
      externalSignal.removeEventListener("abort", onExternalAbort);
    }
    void silencedWorkPromise;
    void silencedDeadlinePromise;
  }
}
