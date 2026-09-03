import { vi } from "vitest";

export function jsonResponse(
  body: unknown,
  init: { status?: number; headers?: Record<string, string> } = {},
): Response {
  const { status = 200, headers = {} } = init;
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

export function textResponse(
  body: string,
  init: { status?: number; headers?: Record<string, string> } = {},
): Response {
  const { status = 200, headers = {} } = init;
  return new Response(body, {
    status,
    headers: { "Content-Type": "text/plain", ...headers },
  });
}

export function mockFetchOnce(implementation: () => Promise<Response>): void {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementationOnce(implementation),
  );
}

export function mockFetchRejectOnce(error: unknown): void {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValueOnce(error));
}

export function mockFetchAbortOnce(): void {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementationOnce((_input: RequestInfo | URL, init?: RequestInit) => {
      return new Promise((_resolve, reject) => {
        const signal = init?.signal;
        if (signal) {
          signal.addEventListener("abort", () => {
            const err = new DOMException("The operation was aborted.", "AbortError");
            reject(err);
          });
        }
      });
    }),
  );
}

/**
 * A hostile fetch mock: the fetch() call itself never settles and ignores
 * the abort signal entirely. Used to prove the request is still bounded by
 * its own deadline, independent of whether the underlying fetch cooperates
 * with cancellation.
 */
export function mockFetchHangForever(): void {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementationOnce(() => new Promise<Response>(() => {})),
  );
}

export interface ControlledJsonResponse {
  resolveJson: (value: unknown) => void;
  rejectJson: (error: unknown) => void;
}

/**
 * fetch() resolves immediately with an ok response, but response.json()
 * returns a promise the test controls directly and that ignores the abort
 * signal. Used to prove a hung body/JSON read is still bounded by the
 * request's overall deadline.
 */
export function mockFetchOkWithControlledJson(
  init: { status?: number; headers?: Record<string, string> } = {},
): ControlledJsonResponse {
  const { status = 200, headers = {} } = init;
  let resolveJson!: (value: unknown) => void;
  let rejectJson!: (error: unknown) => void;
  const jsonPromise = new Promise<unknown>((resolve, reject) => {
    resolveJson = resolve;
    rejectJson = reject;
  });

  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementationOnce(() =>
      Promise.resolve({
        ok: true,
        status,
        headers: new Headers(headers),
        json: () => jsonPromise,
      } as unknown as Response),
    ),
  );

  return { resolveJson, rejectJson };
}
