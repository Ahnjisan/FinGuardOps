export class TimeoutError extends Error {
  constructor() {
    super("Request timed out.");
    this.name = "TimeoutError";
  }
}

export class NetworkError extends Error {
  constructor() {
    super("Network request failed.");
    this.name = "NetworkError";
  }
}

export class HttpError extends Error {
  readonly status: number;

  constructor(status: number) {
    super(`Request failed with status ${status}.`);
    this.name = "HttpError";
    this.status = status;
  }
}

export class InvalidResponseError extends Error {
  constructor() {
    super("Received an unexpected response shape.");
    this.name = "InvalidResponseError";
  }
}

/**
 * No usable authenticated session exists locally, so the request was never
 * sent. Distinct from `UnauthorizedError`, which is a Backend verdict: this one
 * is decided in the browser and costs zero fetch calls.
 */
export class AuthenticationRequiredError extends Error {
  constructor() {
    super("Sign-in is required for this request.");
    this.name = "AuthenticationRequiredError";
  }
}

/**
 * The request was refused before any network call: unknown endpoint key,
 * rejected path parameter, a URL that did not survive exact re-verification,
 * or a method/body combination the endpoint does not accept.
 */
export class RequestNotAllowedError extends Error {
  constructor() {
    super("The request was not permitted by the client.");
    this.name = "RequestNotAllowedError";
  }
}

/**
 * HTTP 401 only.
 *
 * Carries nothing from the response but a trace id that already passed the
 * exact `X-Trace-Id` contract: no body, no `WWW-Authenticate` challenge, no
 * token, no claim.
 */
export class UnauthorizedError extends Error {
  readonly traceId?: string;

  constructor(traceId?: string) {
    super("The session is no longer valid.");
    this.name = "UnauthorizedError";
    this.traceId = traceId;
  }
}

/** HTTP 403 only. Same disclosure boundary as `UnauthorizedError`. */
export class ForbiddenError extends Error {
  readonly traceId?: string;

  constructor(traceId?: string) {
    super("The request was denied.");
    this.name = "ForbiddenError";
    this.traceId = traceId;
  }
}

export type ApiError =
  | TimeoutError
  | NetworkError
  | HttpError
  | InvalidResponseError
  | AuthenticationRequiredError
  | RequestNotAllowedError
  | UnauthorizedError
  | ForbiddenError;
