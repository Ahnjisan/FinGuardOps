/**
 * The official `X-Trace-Id` contract, defined exactly once for every Backend
 * client.
 *
 * Backend's `TraceIdFilter` sets this header on every response, including 401
 * and 403, and the CORS configuration exposes it. That is what lets an error
 * carry a support reference without the client ever reading a non-2xx response
 * body.
 *
 * Full match only: length 8-64, first character alphanumeric, remaining
 * characters alphanumeric plus `.` `_` `:` `-`. A value that does not match is
 * discarded rather than trimmed or normalized, so a hostile or malformed header
 * cannot be repaired into something the UI will display.
 */
const TRACE_ID_HEADER = "X-Trace-Id";

const TRACE_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,63}$/;

export function isSafeTraceId(value: string): boolean {
  return TRACE_ID_PATTERN.test(value);
}

/** Returns the response's trace id only when it satisfies the exact contract. */
export function extractSafeTraceId(headers: Headers): string | undefined {
  const rawTraceId = headers.get(TRACE_ID_HEADER);
  if (rawTraceId !== null && isSafeTraceId(rawTraceId)) {
    return rawTraceId;
  }
  return undefined;
}
