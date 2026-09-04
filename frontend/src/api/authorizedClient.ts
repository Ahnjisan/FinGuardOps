import type { CredentialAuthClient } from "../auth/authClient";
import { getEnv } from "../config/env";
import {
  buildBackendRequestUrl,
  findApprovedBackendRequest,
  type BackendPathParams,
} from "./backendEndpoints";
import {
  AuthenticationRequiredError,
  ForbiddenError,
  HttpError,
  RequestNotAllowedError,
  UnauthorizedError,
} from "./errors";
import { httpRequest } from "./httpClient";
import { extractSafeTraceId } from "./traceId";

/**
 * One deadline for the whole authenticated request: memory user lookup, request
 * authorization, the network call, response headers, status handling, body
 * read, JSON parse and the caller's validator. Not five seconds per stage.
 */
const AUTHENTICATED_REQUEST_TIMEOUT_MS = 5000;

/**
 * RFC 6750 credentials: `Bearer` followed by one `b64token`.
 *
 * b64token = 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"="
 *
 * So the body needs at least one character and any `=` may only pad the end:
 * `Bearer abc=` and `Bearer abc==` are credentials, `Bearer =`, `Bearer ==` and
 * `Bearer abc=def` are not. Whitespace, control characters and non-ASCII are
 * outside the grammar, and because a merged multi-value header would contain a
 * comma and a space, two credentials cannot pass as one. The shape is not
 * narrowed to a three-part JWT: an opaque access token is equally valid.
 */
const SINGLE_BEARER_CREDENTIAL = /^Bearer [A-Za-z0-9\-._~+/]+=*$/;

export interface AuthorizedRequestOptions<TData> {
  /**
   * An endpoint key from the registry. Typed as `string` on purpose: an
   * unknown key must be rejected at run time, not only refused by the compiler,
   * because a value that reaches here from untyped data would otherwise pass.
   */
  readonly endpoint: string;
  readonly params?: BackendPathParams;
  /** Required for PATCH and POST, forbidden for GET. Serialized as JSON. */
  readonly body?: unknown;
  /** Runs inside the deadline. A 2xx response that fails it is not a success. */
  readonly validate: (body: unknown) => body is TData;
  readonly signal?: AbortSignal;
}

export interface AuthorizedResult<TData> {
  readonly data: TData;
  /** Present only when the response carried a contract-valid `X-Trace-Id`. */
  readonly traceId?: string;
}

function serializeJsonBody(body: unknown): string {
  let serialized: string | undefined;
  try {
    serialized = JSON.stringify(body);
  } catch {
    throw new RequestNotAllowedError();
  }
  if (typeof serialized !== "string") {
    throw new RequestNotAllowedError();
  }
  return serialized;
}

/**
 * Re-checks what the auth port handed back before anything is sent.
 *
 * The port is trusted to add the credential, not to leave the request otherwise
 * intact, so the URL, the method and the credential's grammar are all verified
 * against what this module built. A mismatch fails closed with zero fetch calls
 * rather than sending a token somewhere unintended.
 */
function assertAuthorizedRequest(
  authorized: Request,
  expectedUrl: string,
  expectedMethod: string,
): void {
  if (authorized.url !== expectedUrl || authorized.method !== expectedMethod) {
    throw new RequestNotAllowedError();
  }
  const credential = authorized.headers.get("Authorization");
  if (credential === null || !SINGLE_BEARER_CREDENTIAL.test(credential)) {
    throw new RequestNotAllowedError();
  }
}

/**
 * Sends one request to one approved Backend business endpoint on behalf of the
 * signed-in USER.
 *
 * The caller names an endpoint key and its path parameters. It cannot supply a
 * URL, a method, a query string, a header or a credential, so there is no input
 * that makes this reach the public health path, a SERVICE ingestion endpoint,
 * the management listener, the AI service, an observability service or any
 * other origin.
 *
 * Exactly one fetch per call and no retry of any kind. A failed request is
 * never re-sent — least of all a PATCH or POST, whose replay would duplicate a
 * case decision or an investigation note.
 */
export async function sendAuthorizedBackendRequest<TData>(
  authClient: CredentialAuthClient,
  options: AuthorizedRequestOptions<TData>,
): Promise<AuthorizedResult<TData>> {
  const apiBaseUrl = getEnv().apiBaseUrl;

  // Endpoint key, path parameters and the exact URL, before anything else and
  // before any credential exists.
  const { descriptor, url } = buildBackendRequestUrl(apiBaseUrl, options.endpoint, options.params);

  // The transport's own check on the URL it is about to send, independent of
  // how that URL was produced. The builder validates its own output, but this
  // module is what decides to put a credential on the wire, so it does not
  // delegate that decision: the URL it holds must independently resolve to an
  // approved Backend USER request, for this exact method, and to the very
  // endpoint the caller asked for. Landing on some *other* approved endpoint is
  // still not what was requested.
  if (findApprovedBackendRequest(apiBaseUrl, descriptor.method, url)?.key !== descriptor.key) {
    throw new RequestNotAllowedError();
  }

  // Method and body contract.
  if (descriptor.acceptsJsonBody) {
    if (options.body === undefined) {
      throw new RequestNotAllowedError();
    }
  } else if (options.body !== undefined) {
    throw new RequestNotAllowedError();
  }

  const headers = new Headers({ Accept: "application/json" });
  let serializedBody: string | undefined;
  if (descriptor.acceptsJsonBody) {
    serializedBody = serializeJsonBody(options.body);
    headers.set("Content-Type", "application/json");
  }

  /**
   * The conditional invalidation issued with this request's credential, held
   * for this call only. It belongs to the session that signed this request, so
   * a 401 here can never act on a session that authorized something else.
   */
  let invalidateIfCurrent: (() => void) | undefined;

  try {
    const result = await httpRequest<TData>({
      timeoutMs: AUTHENTICATED_REQUEST_TIMEOUT_MS,
      signal: options.signal,
      prepare: async (signal: AbortSignal) => {
        const unauthenticatedRequest = new Request(url, {
          method: descriptor.method,
          headers,
          body: serializedBody,
          // No cookies, ever: the Backend runs CORS with allowCredentials=false
          // and authenticates from the Bearer header alone.
          credentials: "omit",
          // No approved endpoint redirects. Following one would send an
          // authenticated request somewhere this module never approved, so an
          // unexpected 3xx is a failure instead.
          redirect: "error",
          signal,
        });

        const authorized = await authClient.authorizeRequest(unauthenticatedRequest);
        if (authorized === null) {
          throw new AuthenticationRequiredError();
        }
        assertAuthorizedRequest(authorized.request, unauthenticatedRequest.url, descriptor.method);
        invalidateIfCurrent = authorized.invalidateIfCurrent;

        return () => fetch(authorized.request);
      },
      classifyErrorStatus: (response: Response) => {
        // The body is never read here. A safe trace id from the response header
        // is the only thing an error is allowed to carry, so no response
        // content, challenge, role or claim can reach the UI.
        if (response.status === 401) {
          return new UnauthorizedError(extractSafeTraceId(response.headers));
        }
        if (response.status === 403) {
          return new ForbiddenError(extractSafeTraceId(response.headers));
        }
        return new HttpError(response.status);
      },
      validate: options.validate,
    });

    return { data: result.body, traceId: extractSafeTraceId(result.headers) };
  } catch (error: unknown) {
    if (error instanceof UnauthorizedError) {
      // The Backend rejected the credential this request carried, so the
      // session that signed it is over — and only that session. If it has since
      // been replaced, signed out or expired, this is a no-op rather than a
      // logout of whoever is signed in now. Concurrent 401s from one session
      // still collapse into a single teardown at the port. The failed request
      // is not replayed and nothing navigates.
      invalidateIfCurrent?.();
    }
    // A 403 deliberately falls through untouched: the session and the memory
    // token stay exactly as they were.
    throw error;
  }
}
