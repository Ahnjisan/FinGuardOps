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

export type ApiError = TimeoutError | NetworkError | HttpError | InvalidResponseError;
