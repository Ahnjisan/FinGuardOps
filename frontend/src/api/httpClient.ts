import { HttpError, InvalidResponseError, NetworkError, TimeoutError } from "./errors";

export interface HttpDeadlineOptions {
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

export type HttpGetOptions = HttpDeadlineOptions;

export interface HttpResult<TBody = unknown> {
  readonly status: number;
  readonly headers: Headers;
  readonly body: TBody;
}

export type HttpGetResult = HttpResult<unknown>;

/** Performs exactly the network call. Its rejections are network failures. */
export type HttpDispatch = () => Promise<Response>;

export interface HttpRequestOptions<TBody = unknown> extends HttpDeadlineOptions {
  /**
   * Everything that must happen before the network call and that owns its own
   * failure vocabulary: session lookup and request authorization. It runs
   * inside the same deadline as the network call, and its rejections
   * propagate unchanged rather than being flattened into NetworkError — "there
   * is no session" is not a network problem.
   *
   * Returning the dispatch directly, rather than a promise for it, keeps the
   * network call synchronous with the caller. The credential-free path has
   * nothing to prepare and relies on that.
   */
  prepare: (signal: AbortSignal) => HttpDispatch | Promise<HttpDispatch>;
  /**
   * Builds the error for a non-2xx response. Called with the Response so a
   * caller can read response *headers*; the body is never read here, so no
   * error can carry response content.
   */
  classifyErrorStatus?: (response: Response) => Error;
  /**
   * Validates the parsed 2xx body inside the deadline. Returning false yields
   * InvalidResponseError, so `unknown` is never handed back unchecked, and the
   * narrowed type is what the caller receives.
   */
  validate?: (body: unknown) => body is TBody;
}

async function performRequest<TBody>(
  options: HttpRequestOptions<TBody>,
  signal: AbortSignal,
  isDeadlineExceeded: () => boolean,
): Promise<HttpResult<TBody>> {
  // Checkpoint: nothing has happened yet, but the caller may already be late.
  if (isDeadlineExceeded()) {
    throw new TimeoutError();
  }
  // Cancelled before anything started: do not ask the auth port for a
  // credential and do not open a connection. Only this request's own deadline
  // produces TimeoutError, so an external abort is a NetworkError here.
  if (signal.aborted) {
    throw new NetworkError();
  }
  // Checkpoint: before preparation.
  if (isDeadlineExceeded()) {
    throw new TimeoutError();
  }

  const prepared = options.prepare(signal);
  // Awaited only when preparation is actually asynchronous, so a request with
  // nothing to prepare still reaches fetch in the caller's own turn.
  const dispatch = typeof prepared === "function" ? prepared : await prepared;

  // Checkpoint: preparation may have overrun, whether it awaited or blocked.
  // Sending now would mean a request that outlives its own timeout, so the
  // network call is simply never made.
  if (isDeadlineExceeded()) {
    throw new TimeoutError();
  }
  // Cancelled while preparation was pending: the credential exists but must not
  // travel, so nothing is sent.
  if (signal.aborted) {
    throw new NetworkError();
  }

  let response: Response;
  try {
    response = await dispatch();
  } catch {
    if (isDeadlineExceeded()) {
      throw new TimeoutError();
    }
    throw new NetworkError();
  }

  // Checkpoint: after the network call.
  if (isDeadlineExceeded()) {
    throw new TimeoutError();
  }

  if (!response.ok) {
    throw options.classifyErrorStatus
      ? options.classifyErrorStatus(response)
      : new HttpError(response.status);
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

  // Checkpoint: after the body read and JSON parse.
  if (isDeadlineExceeded()) {
    throw new TimeoutError();
  }

  // A synchronous validator cannot be interrupted by a timer: while it runs,
  // the timer callback simply cannot execute. So its verdict is checked against
  // the clock on both paths, and a verdict that arrived after the deadline is
  // reported as a timeout rather than as whatever it concluded.
  if (options.validate !== undefined) {
    if (!options.validate(body)) {
      // Checkpoint: the validator rejected the body, but if it also overran,
      // "this response was malformed" is the wrong story to tell.
      if (isDeadlineExceeded()) {
        throw new TimeoutError();
      }
      throw new InvalidResponseError();
    }
    // Checkpoint: immediately before returning a success. A success produced
    // after the deadline is not a success.
    if (isDeadlineExceeded()) {
      throw new TimeoutError();
    }
    return { status: response.status, headers: response.headers, body };
  }

  // Checkpoint: immediately before returning a success.
  if (isDeadlineExceeded()) {
    throw new TimeoutError();
  }
  return {
    status: response.status,
    headers: response.headers,
    body: body as TBody,
  };
}

/**
 * Bounds an entire request lifecycle — preparation (session lookup, request
 * authorization), the network call, headers, status check, body read, JSON
 * parse and response validation — to a single deadline. Not one deadline per
 * stage: a request cannot take `timeoutMs` to authorize and then another
 * `timeoutMs` to read its body.
 *
 * The deadline is one absolute instant on a monotonic clock, computed once and
 * shared by two mechanisms. A timer ends the wait when the work is genuinely
 * pending. Explicit elapsed-time checks between stages cover what a timer
 * cannot: JavaScript is single-threaded, so a synchronous validator that runs
 * long simply prevents the timer callback from executing at all. Nothing here
 * interrupts synchronous work — that is not possible — but a result that comes
 * back after the deadline is refused rather than accepted as a success.
 *
 * `Date.now()` is deliberately not used: it can jump backwards or forwards with
 * a clock adjustment, which would either cut a request short or extend it.
 *
 * Exactly one network call per invocation. There is no retry anywhere in this
 * module, so no caller can obtain replay of a failed request by accident.
 */
export async function httpRequest<TBody = unknown>(
  options: HttpRequestOptions<TBody>,
): Promise<HttpResult<TBody>> {
  const controller = new AbortController();
  // One absolute instant, on a monotonic clock, shared by the timer below and
  // by every explicit checkpoint inside performRequest.
  const deadlineAt = performance.now() + options.timeoutMs;
  let deadlineExceeded = false;
  let rejectOnDeadline!: (error: TimeoutError) => void;

  // At the deadline, not merely past it: exactly `timeoutMs` elapsed is already
  // out of budget, which is the same instant the timer fires.
  const isDeadlineExceeded = () => deadlineExceeded || performance.now() >= deadlineAt;

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

  const workPromise = performRequest(options, controller.signal, isDeadlineExceeded);
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

/**
 * Credential-free GET. Adds no headers of any kind, so the public Health path
 * cannot acquire an Authorization header by way of a shared default.
 */
export async function httpGet(url: string, options: HttpGetOptions): Promise<HttpGetResult> {
  return httpRequest({
    timeoutMs: options.timeoutMs,
    signal: options.signal,
    prepare: (signal: AbortSignal) => () => fetch(url, { method: "GET", signal }),
  });
}
