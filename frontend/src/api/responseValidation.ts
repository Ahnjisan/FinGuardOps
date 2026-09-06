import { InvalidResponseError } from "./errors";
import { isSafeTraceId } from "./traceId";

/**
 * The primitives every typed Backend API module validates its values with, on
 * both the request and the response side.
 *
 * A Backend response is untrusted input. It arrives as `unknown`, and nothing
 * here narrows it on the strength of a field merely being present: an object is
 * accepted only when its own keys are *exactly* the contract's keys and every
 * value satisfies the contract's type. A missing key, an extra key, a key
 * inherited from a prototype, a `null` where a string belongs, an unknown enum
 * member, a non-canonical UUID, a local-time instant or an amount that has
 * become a `number` all fail the same way: the whole response is refused.
 *
 * Refusing the whole response, rather than dropping the offending item, is the
 * point. A partially rendered case queue that silently omits the rows it could
 * not parse is worse than an error, because nothing on screen says a row is
 * missing.
 *
 * This module is the dependency root of the API layer: it imports only the
 * error types and the trace-id contract, so the endpoint registry can enforce
 * its own query contract with these predicates without an import cycle.
 */

/**
 * Canonical lowercase UUID v4 with an RFC 4122 variant nibble, matching the
 * form Backend itself validates for business identifiers.
 *
 * Strictness here is also the URL layer's path-traversal defense. The character
 * class admits no percent sign, slash, backslash, semicolon, dot or whitespace,
 * so an encoded slash, a dot segment, a matrix parameter or a trailing slash
 * cannot survive into a URL.
 */
const CANONICAL_UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export function isCanonicalUuidV4(value: string): boolean {
  return CANONICAL_UUID_V4.test(value);
}

/**
 * A JSON object and nothing else: no array, no `null`, and nothing carrying a
 * prototype a `JSON.parse` result could not have.
 */
export function isJsonObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const prototype: unknown = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

/**
 * Exact own-key equality against the contract.
 *
 * Both directions matter. A missing key means the response is not what the
 * contract promised; an extra key means the Backend is returning something this
 * client has never reviewed, which for a fraud case is as likely to be a
 * disclosure defect as a harmless addition. Symbol keys cannot occur in parsed
 * JSON, so their presence is itself disqualifying.
 */
export function hasExactKeys(value: Record<string, unknown>, keys: readonly string[]): boolean {
  const own = Object.getOwnPropertyNames(value);
  if (own.length !== keys.length || Object.getOwnPropertySymbols(value).length !== 0) {
    return false;
  }
  for (const key of keys) {
    if (!Object.prototype.hasOwnProperty.call(value, key)) {
      return false;
    }
  }
  return true;
}

/** A JSON object whose own keys are exactly `keys`. */
export function isObjectWithExactKeys(
  value: unknown,
  keys: readonly string[],
): value is Record<string, unknown> {
  return isJsonObject(value) && hasExactKeys(value, keys);
}

/**
 * A Java `long` that survived JSON as an exact JavaScript integer.
 *
 * Backend's `concurrencyVersion`, `relatedTransactionCount` and
 * `totalElements` are all 64-bit. Beyond 2^53-1 a JSON number has already lost
 * precision by the time `JSON.parse` returns it, so the value on hand is not
 * the value Backend sent. There is no repair for that, and quietly rendering an
 * off-by-a-few count or, worse, sending a corrupted `expectedVersion` back as
 * an optimistic-locking token is not acceptable - so it fails closed.
 */
export function isSafeLong(value: unknown): value is number {
  return typeof value === "number" && Number.isSafeInteger(value);
}

/** A safe integer within an inclusive range. */
export function isSafeIntegerInRange(value: unknown, min: number, max: number): value is number {
  return isSafeLong(value) && value >= min && value <= max;
}

/** A Java `int`, which is what page number, size and total pages are. */
export function isInt32(value: unknown): value is number {
  return isSafeIntegerInRange(value, -2147483648, 2147483647);
}

export function isBoolean(value: unknown): value is boolean {
  return typeof value === "boolean";
}

/** A canonical lowercase UUID v4 string, exactly as the Backend validators define it. */
export function isUuidV4String(value: unknown): value is string {
  return typeof value === "string" && isCanonicalUuidV4(value);
}

export function isNullableUuidV4String(value: unknown): value is string | null {
  return value === null || isUuidV4String(value);
}

/**
 * UTC ISO-8601 with a literal `Z` and no offset, matching what Backend
 * serializes an `Instant` to.
 *
 * The pattern is deliberately narrow: no `+09:00`, no missing `Z`, no space
 * separator, no year outside four digits. A fractional second is optional and
 * may carry one to nine digits, which covers both the second-precision values
 * in the list responses and the microsecond-precision `closedAt`/`createdAt`
 * the write endpoints return. Nothing is trimmed or coerced first - a value
 * with surrounding whitespace is simply not this format.
 */
const UTC_INSTANT = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/;

/**
 * Verifies the value is not merely shaped like an instant but is a real one.
 *
 * `2026-02-30T00:00:00Z` matches the pattern and is not a date, so the calendar
 * fields are re-derived from the parsed time and compared back. `Date` truncates
 * at milliseconds, so only the fields it can round-trip are compared; the
 * sub-millisecond digits are already constrained by the pattern.
 */
export function isUtcInstantString(value: unknown): value is string {
  if (typeof value !== "string" || !UTC_INSTANT.test(value)) {
    return false;
  }
  const parsed = new Date(value);
  const time = parsed.getTime();
  if (!Number.isFinite(time)) {
    return false;
  }
  const [date, rest] = value.split("T");
  const clock = rest.slice(0, 8);
  return (
    parsed.toISOString().slice(0, 10) === date && parsed.toISOString().slice(11, 19) === clock
  );
}

export function isNullableUtcInstantString(value: unknown): value is string | null {
  return value === null || isUtcInstantString(value);
}

/**
 * An instant decomposed the way `java.time.Instant` holds one: whole epoch
 * seconds plus a nanosecond-of-second.
 */
interface InstantParts {
  readonly epochSecond: number;
  readonly nanoOfSecond: number;
}

/**
 * Splits a validated UTC instant without going through a `Date`'s millisecond
 * resolution.
 *
 * `Date` is used only for the whole-second part, which it represents exactly;
 * the fractional digits are read from the string and right-padded to nine, so
 * `.1Z` is 100,000,000ns rather than 1ns and no digit is lost to rounding.
 */
function toInstantParts(value: string): InstantParts {
  const fractionStart = value.indexOf(".");
  const whole = fractionStart === -1 ? value.slice(0, -1) : value.slice(0, fractionStart);
  const fraction = fractionStart === -1 ? "" : value.slice(fractionStart + 1, -1);
  return {
    epochSecond: Date.parse(`${whole}Z`) / 1000,
    nanoOfSecond: Number(`${fraction}000000000`.slice(0, 9)),
  };
}

/**
 * Orders two validated UTC instants to the nanosecond.
 *
 * Comparing through `Date.getTime()` would truncate at milliseconds, so
 * `...00.000000002Z` and `...00.000000001Z` would compare equal and an inverted
 * range built from them would be sent as though it were valid. Backend holds
 * these as `Instant`, which is nanosecond-resolution, so the client orders them
 * at the same resolution.
 *
 * Returns a negative number, zero, or a positive number, like any comparator.
 * Both arguments must already have passed `isUtcInstantString`.
 */
export function compareUtcInstants(left: string, right: string): number {
  const a = toInstantParts(left);
  const b = toInstantParts(right);
  if (a.epochSecond !== b.epochSecond) {
    return a.epochSecond < b.epochSecond ? -1 : 1;
  }
  if (a.nanoOfSecond === b.nanoOfSecond) {
    return 0;
  }
  return a.nanoOfSecond < b.nanoOfSecond ? -1 : 1;
}

/**
 * A UTC instant whose nanosecond-of-second is a whole number of microseconds.
 *
 * `FraudCaseAuditLogMapper` refuses a stored `changedAt` whose `getNano() %
 * 1_000` is non-zero and fails the whole page rather than project it, because
 * the audit column is microsecond-resolution and a finer value did not come
 * from it. So `...000001Z` is a microsecond and is fine, while
 * `...000000001Z` is a nanosecond and is not.
 *
 * Deliberately narrower than `isUtcInstantString`, and applied only where
 * Backend applies it: the other DTOs keep the common validator.
 */
export function isMicrosecondUtcInstantString(value: unknown): value is string {
  if (!isUtcInstantString(value)) {
    return false;
  }
  return toInstantParts(value).nanoOfSecond % 1000 === 0;
}

/**
 * A monetary amount, kept as the decimal integer string the API contract
 * defines.
 *
 * It is never converted to a `number`. `api-conventions.md` chose a string
 * exactly because IEEE 754 cannot hold every financial amount, and a client
 * that parses it back into a double throws that away at the boundary the
 * contract was written to protect. Leading zeros, a sign, a decimal point and
 * exponent notation are all outside the contract and are refused rather than
 * normalized.
 *
 * Length is capped at fifteen digits. `financial_transaction.amount` is
 * `numeric(19,4)`, so fifteen integer digits is the entire integral range that
 * column can hold; a sixteen-digit value did not come from it and is refused
 * rather than displayed as an amount.
 */
const INTEGER_AMOUNT = /^[1-9][0-9]{0,14}$/;

export function isIntegerAmountString(value: unknown): value is string {
  return typeof value === "string" && INTEGER_AMOUNT.test(value);
}

/**
 * `Character.isWhitespace(cp)` on its own.
 *
 * This is a *different* set from the one below, and the difference decides a
 * real question: `String.isBlank()` is defined in terms of this predicate, so
 * the no-break spaces U+00A0, U+2007 and U+202F are deliberately **not**
 * whitespace here. A reference consisting only of a no-break space is
 * therefore not blank to Backend, and this client must not decide otherwise.
 */
function isJavaIsWhitespaceCodePoint(codePoint: number): boolean {
  if (codePoint >= 0x09 && codePoint <= 0x0d) {
    return true;
  }
  if (codePoint >= 0x1c && codePoint <= 0x1f) {
    return true;
  }
  // Zs from EN QUAD to HAIR SPACE, minus FIGURE SPACE, which is a no-break space.
  if (codePoint >= 0x2000 && codePoint <= 0x200a) {
    return codePoint !== 0x2007;
  }
  return (
    codePoint === 0x20 || // SPACE (Zs)
    codePoint === 0x1680 || // OGHAM SPACE MARK (Zs)
    codePoint === 0x2028 || // LINE SEPARATOR (Zl)
    codePoint === 0x2029 || // PARAGRAPH SEPARATOR (Zp)
    codePoint === 0x205f || // MEDIUM MATHEMATICAL SPACE (Zs)
    codePoint === 0x3000 // IDEOGRAPHIC SPACE (Zs)
    // U+00A0, U+2007 and U+202F are Zs but not `isWhitespace`.
  );
}

/**
 * Java's `String.isBlank()`: empty, or every code point `isWhitespace`.
 *
 * `TransactionQueryValidator.validateReference` refuses exactly this and
 * nothing else - no length bound, no trim comparison - so a padded but
 * non-blank reference like `" acct "` is a legitimate exact-match filter.
 */
export function isJavaBlank(value: string): boolean {
  for (const character of value) {
    if (!isJavaIsWhitespaceCodePoint(character.codePointAt(0) ?? 0)) {
      return false;
    }
  }
  return true;
}

/**
 * Java's `value.equals(value.trim())`.
 *
 * `String.trim()` strips characters at or below U+0020 by code *unit*, so the
 * question is only about the first and last unit. JavaScript's own `trim()`
 * strips a wider set - Unicode whitespace plus U+FEFF - and using it would
 * refuse references Backend accepts.
 */
export function isJavaTrimmed(value: string): boolean {
  if (value.length === 0) {
    return true;
  }
  return value.charCodeAt(0) > 0x20 && value.charCodeAt(value.length - 1) > 0x20;
}

/**
 * The code points Java treats as whitespace through
 * `Character.isWhitespace(cp) || Character.isSpaceChar(cp)`, which is the exact
 * test `InvestigationNoteValidator` applies to note content.
 *
 * JavaScript's `\s` is not that set, and it errs in both directions. It matches
 * U+FEFF, which Java classifies as a format character and therefore *not*
 * whitespace - so a note consisting only of a byte-order mark is accepted by
 * Backend and must not be refused here. It also misses U+001C-U+001F, which
 * Java does count. The union is spelled out rather than approximated:
 *
 * - `isWhitespace` contributes U+0009-U+000D and U+001C-U+001F,
 * - `isSpaceChar` is exactly the Zs, Zl and Zp general categories,
 * - and the non-breaking spaces U+00A0, U+2007 and U+202F, which `isWhitespace`
 *   deliberately excludes, are still Zs and so remain in the union.
 */
export function isJavaWhitespaceCodePoint(codePoint: number): boolean {
  if (codePoint >= 0x09 && codePoint <= 0x0d) {
    return true;
  }
  if (codePoint >= 0x1c && codePoint <= 0x1f) {
    return true;
  }
  // Zs, the contiguous block from EN QUAD to HAIR SPACE, U+2007 included.
  if (codePoint >= 0x2000 && codePoint <= 0x200a) {
    return true;
  }
  return (
    codePoint === 0x20 || // SPACE (Zs)
    codePoint === 0xa0 || // NO-BREAK SPACE (Zs)
    codePoint === 0x1680 || // OGHAM SPACE MARK (Zs)
    codePoint === 0x2028 || // LINE SEPARATOR (Zl)
    codePoint === 0x2029 || // PARAGRAPH SEPARATOR (Zp)
    codePoint === 0x202f || // NARROW NO-BREAK SPACE (Zs)
    codePoint === 0x205f || // MEDIUM MATHEMATICAL SPACE (Zs)
    codePoint === 0x3000 // IDEOGRAPHIC SPACE (Zs)
  );
}

/**
 * Investigation note text.
 *
 * Untrusted plain text by contract: 1 to 4,000 Unicode code points, at least
 * one of them not whitespace by Java's definition, with CR and LF the only
 * control characters allowed. It is preserved exactly as written - never
 * trimmed, normalized, interpreted or repaired - because Backend stores the
 * original and the whole point of the field is that an investigator's words
 * survive verbatim.
 *
 * Length is counted in code points, matching Backend's `String.codePointCount`,
 * so an emoji or any other astral character counts once rather than twice.
 *
 * Any component rendering this must escape it, and must never hand it to
 * `innerHTML` or `dangerouslySetInnerHTML`.
 */
export function isNoteContentString(value: unknown): value is string {
  if (typeof value !== "string" || value.length === 0) {
    return false;
  }
  let codePoints = 0;
  let hasVisible = false;
  for (const character of value) {
    codePoints += 1;
    if (codePoints > 4000) {
      return false;
    }
    const codePoint = character.codePointAt(0) ?? 0;
    const isAllowedNewline = codePoint === 0x0a || codePoint === 0x0d;
    if (!isAllowedNewline && (codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f))) {
      return false;
    }
    if (!isJavaWhitespaceCodePoint(codePoint)) {
      hasVisible = true;
    }
  }
  return hasVisible;
}

/**
 * An opaque business reference: customer, account, device, assignee or note
 * author.
 *
 * Backend treats these as exact, case-sensitive, untrimmed values of 1 to 128
 * characters, so a value with surrounding whitespace is rejected rather than
 * trimmed into something that would no longer round-trip as a filter. Control
 * characters have no place in a reference and would only ever be a display or
 * log-injection hazard.
 */
export function isOpaqueRefString(value: unknown): value is string {
  if (typeof value !== "string" || value.length === 0 || value.length > 128) {
    return false;
  }
  if (value !== value.trim()) {
    return false;
  }
  for (const character of value) {
    const codePoint = character.codePointAt(0) ?? 0;
    if (codePoint <= 0x1f || (codePoint >= 0x7f && codePoint <= 0x9f)) {
      return false;
    }
  }
  return true;
}

export function isNullableOpaqueRefString(value: unknown): value is string | null {
  return value === null || isOpaqueRefString(value);
}

/** Membership in a closed enum, by exact string match. */
export function isEnumMember<T extends string>(
  value: unknown,
  members: readonly T[],
): value is T {
  return typeof value === "string" && (members as readonly string[]).includes(value);
}

/**
 * A JSON array whose every item satisfies the item contract.
 *
 * One malformed item rejects the array, and with it the whole response. A
 * silently shortened case queue or audit trail is a correctness failure that
 * looks like an empty result.
 */
export function isArrayOf<T>(
  value: unknown,
  isItem: (item: unknown) => item is T,
): value is readonly T[] {
  return Array.isArray(value) && value.every((item) => isItem(item));
}

/** The Backend `traceId` body field, held to the same contract as the header. */
export function isTraceIdString(value: unknown): value is string {
  return typeof value === "string" && isSafeTraceId(value);
}

/**
 * What every typed Backend API call returns: the validated body, and the trace
 * id the body and header agreed on.
 *
 * The trace id is always present on success - the body carries it by contract -
 * so callers never have to decide what to show when it is missing.
 */
export interface ApiResult<TData> {
  readonly data: TData;
  readonly traceId: string;
}

/**
 * Reconciles the response header's trace id with the body's.
 *
 * The header itself is optional: a proxy that strips `X-Trace-Id` is not a
 * malformed response, and the transport passes `undefined` in that case. What
 * the transport does *not* do is hand over a header that failed the trace
 * contract - a success response carrying a malformed `X-Trace-Id` did not come
 * from `TraceIdFilter`, and the transport has already refused it before this is
 * reached.
 *
 * So a header value that arrives here is well-formed, and it must name the same
 * request the body names. Two different ids mean the header and the body did not
 * come from the same Backend request-scoped trace, and a support reference that
 * points at the wrong request is worse than none at all.
 */
export function resolveTraceId(headerTraceId: string | undefined, bodyTraceId: string): string {
  if (headerTraceId !== undefined && headerTraceId !== bodyTraceId) {
    throw new InvalidResponseError();
  }
  return bodyTraceId;
}
