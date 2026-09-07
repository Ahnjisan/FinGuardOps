import type {
  TransactionProcessingStatus,
  TransactionType,
} from "../../api/transactionApi";

/**
 * Everything the transaction screen has to turn into something a person reads,
 * kept out of the components so it can be tested as arithmetic and string work
 * rather than through the DOM.
 *
 * Three rules run through the whole file:
 *
 * - No value is normalized on its way to the screen. References are shown
 *   exactly as the Backend stored them, spaces and casing included, because a
 *   reference is an opaque key and a trimmed one is a different key.
 * - No amount becomes a `number`. The API contract carries amounts as decimal
 *   strings precisely so a client never has to reason about where the IEEE 754
 *   exact-integer limit falls, and a rounded won figure on a fraud console is a
 *   wrong figure.
 * - No instant is read or written through the browser's own time zone. The
 *   console states KST and means it, on a machine set to any zone at all.
 */

/**
 * Korea has been on UTC+09:00 with no daylight saving since 1961, and the
 * Backend stores instants in UTC. So the whole conversion is one fixed offset,
 * applied explicitly - never `toLocaleString`, never the host zone.
 */
export const KST_OFFSET_MINUTES = 9 * 60;

const KST_OFFSET_MS = KST_OFFSET_MINUTES * 60 * 1000;

/** `YYYY-MM-DDTHH:MM(:SS)?(.fraction)?Z`, the shape the API validator admits. */
const UTC_INSTANT = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?Z$/;

/** What `<input type="datetime-local">` produces, seconds optional. */
const LOCAL_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/;

function pad(value: number, width: number): string {
  return String(value).padStart(width, "0");
}

/**
 * Epoch milliseconds for a calendar reading, or `null` when the reading is not
 * a real date.
 *
 * `Date.UTC` happily rolls `2026-02-30` forward into March, so the fields are
 * compared back against what it produced. That is what makes an impossible date
 * a refusal rather than a silently different one.
 */
function utcFieldsToEpochMs(
  year: number,
  month: number,
  day: number,
  hour: number,
  minute: number,
  second: number,
): number | null {
  if (month < 1 || month > 12 || day < 1 || day > 31) {
    return null;
  }
  if (hour > 23 || minute > 59 || second > 59) {
    return null;
  }
  const epochMs = Date.UTC(year, month - 1, day, hour, minute, second);
  if (!Number.isFinite(epochMs)) {
    return null;
  }
  const roundTrip = new Date(epochMs);
  if (
    roundTrip.getUTCFullYear() !== year ||
    roundTrip.getUTCMonth() !== month - 1 ||
    roundTrip.getUTCDate() !== day
  ) {
    return null;
  }
  return epochMs;
}

/** The calendar fields of an instant, as they read in Seoul. */
export interface KstParts {
  readonly year: number;
  readonly month: number;
  readonly day: number;
  readonly hour: number;
  readonly minute: number;
  readonly second: number;
}

/**
 * Reads a UTC instant as Seoul wall-clock fields.
 *
 * The shift is applied to the epoch value and the result is read back with the
 * `getUTC*` accessors, so the host's own zone never takes part: a workstation
 * set to UTC, to America/New_York or to Asia/Seoul all produce the same string.
 */
export function toKstParts(utcInstant: string): KstParts | null {
  const match = UTC_INSTANT.exec(utcInstant);
  if (match === null) {
    return null;
  }
  const epochMs = utcFieldsToEpochMs(
    Number(match[1]),
    Number(match[2]),
    Number(match[3]),
    Number(match[4]),
    Number(match[5]),
    Number(match[6]),
  );
  if (epochMs === null) {
    return null;
  }
  const shifted = new Date(epochMs + KST_OFFSET_MS);
  return {
    year: shifted.getUTCFullYear(),
    month: shifted.getUTCMonth() + 1,
    day: shifted.getUTCDate(),
    hour: shifted.getUTCHours(),
    minute: shifted.getUTCMinutes(),
    second: shifted.getUTCSeconds(),
  };
}

/** `2026-09-07 14:03:22`, Seoul wall clock. The `KST` suffix is added by the UI. */
export function formatKstDateTime(utcInstant: string): string | null {
  const parts = toKstParts(utcInstant);
  if (parts === null) {
    return null;
  }
  return (
    `${pad(parts.year, 4)}-${pad(parts.month, 2)}-${pad(parts.day, 2)} ` +
    `${pad(parts.hour, 2)}:${pad(parts.minute, 2)}:${pad(parts.second, 2)}`
  );
}

/** `2026-09-07 14:03`, for the denser secondary line. */
export function formatKstDateTimeShort(utcInstant: string): string | null {
  const parts = toKstParts(utcInstant);
  if (parts === null) {
    return null;
  }
  return (
    `${pad(parts.year, 4)}-${pad(parts.month, 2)}-${pad(parts.day, 2)} ` +
    `${pad(parts.hour, 2)}:${pad(parts.minute, 2)}`
  );
}

/**
 * Turns a Seoul wall-clock filter input into the UTC instant the query contract
 * requires, or `null` when the input is not a real local date and time.
 *
 * The seconds are defaulted to `00` rather than to "now", and the result always
 * carries an explicit `Z`, so what leaves the browser is unambiguous no matter
 * what the machine underneath thinks the time is.
 */
export function kstInputToUtcInstant(value: string): string | null {
  const match = LOCAL_DATE_TIME.exec(value);
  if (match === null) {
    return null;
  }
  const epochMs = utcFieldsToEpochMs(
    Number(match[1]),
    Number(match[2]),
    Number(match[3]),
    Number(match[4]),
    Number(match[5]),
    match[6] === undefined ? 0 : Number(match[6]),
  );
  if (epochMs === null) {
    return null;
  }
  const utc = new Date(epochMs - KST_OFFSET_MS);
  return (
    `${pad(utc.getUTCFullYear(), 4)}-${pad(utc.getUTCMonth() + 1, 2)}-` +
    `${pad(utc.getUTCDate(), 2)}T${pad(utc.getUTCHours(), 2)}:` +
    `${pad(utc.getUTCMinutes(), 2)}:${pad(utc.getUTCSeconds(), 2)}Z`
  );
}

/**
 * Whether a two-sided filter range runs backwards.
 *
 * A half-open range is legitimate and equal bounds are an empty range Backend
 * accepts, so only a strictly inverted pair is refused. Comparing the two UTC
 * strings lexicographically is exact here because both have already been
 * produced by `kstInputToUtcInstant` in one fixed-width format.
 */
export function isReversedUtcRange(from: string | null, to: string | null): boolean {
  if (from === null || to === null) {
    return false;
  }
  return from > to;
}

const INTEGER_AMOUNT = /^[1-9][0-9]{0,14}$/;

/**
 * Groups a decimal integer amount string into thousands.
 *
 * `BigInt` carries any number of digits exactly and `Intl.NumberFormat` accepts
 * one directly, so nothing passes through a double on the way to the screen.
 * The contract's fifteen digits happen to fit a double today; this does not
 * depend on that staying true.
 * The manual fallback exists because grouping is not worth an exception: if a
 * runtime ever refuses the pair, the digits still reach the analyst correctly
 * grouped rather than not at all.
 */
export function formatAmountDigits(amount: string): string {
  if (!INTEGER_AMOUNT.test(amount)) {
    return groupFromRight(amount);
  }
  try {
    return new Intl.NumberFormat("ko-KR").format(BigInt(amount));
  } catch {
    return groupFromRight(amount);
  }
}

function groupFromRight(digits: string): string {
  let grouped = "";
  for (let index = digits.length; index > 0; index -= 3) {
    const start = Math.max(0, index - 3);
    grouped = digits.slice(start, index) + (grouped === "" ? "" : ",") + grouped;
  }
  return grouped;
}

/**
 * A reference as the table renders it.
 *
 * The text is the stored value, unchanged: nothing is trimmed, case folded,
 * elided or abbreviated, because an analyst comparing a reference against
 * another system needs the value itself. `wrap` is the layout answer instead -
 * long values break inside their cell rather than widening the sheet - and
 * `absent` is the nullable recipient, which gets a word rather than an empty
 * cell so a blank is never mistaken for a rendering fault.
 */
export interface ReferenceDisplay {
  readonly text: string;
  readonly wrap: boolean;
  readonly absent: boolean;
}

/** Beyond this many characters a reference is wrapped inside its cell. */
export const WRAPPING_REFERENCE_LENGTH = 20;

export function describeReference(value: string | null): ReferenceDisplay {
  if (value === null) {
    return { text: "", wrap: false, absent: true };
  }
  return {
    text: value,
    wrap: value.length > WRAPPING_REFERENCE_LENGTH,
    absent: false,
  };
}

/** Plain-language names for the Backend transaction types. */
export const TRANSACTION_TYPE_LABELS: Readonly<Record<TransactionType, string>> =
  Object.freeze({
    ACCOUNT_TRANSFER: "Account transfer",
    OPEN_BANKING_TRANSFER: "Open banking transfer",
    ATM_WITHDRAWAL: "ATM withdrawal",
    LOAN_DISBURSED: "Loan disbursed",
  });

/**
 * Plain-language names for the Backend processing statuses.
 *
 * This column is where a transaction has reached in the processing pipeline. It
 * is not a risk score and not a risk level: the list response carries neither,
 * and dressing a processing state up as one would put a number on screen that
 * no system ever computed.
 */
export const PROCESSING_STATUS_LABELS: Readonly<
  Record<TransactionProcessingStatus, string>
> = Object.freeze({
  RECEIVED: "Received",
  ANALYZING: "Analyzing",
  ANALYZED: "Analyzed",
  APPROVED: "Approved",
  ADDITIONAL_AUTH_REQUIRED: "Auth required",
  HELD: "Held",
  FAILED: "Failed",
});

/**
 * The badge treatment for a processing status.
 *
 * A tone drives colour *and* a differently shaped mark, and the label is always
 * rendered beside it, so the status survives a monochrome display, a printout
 * and every form of colour blindness. The tones describe pipeline progress -
 * "attention" is work waiting on someone, "danger" is processing that failed -
 * and carry no claim about how risky the transaction is.
 */
export type StatusTone = "neutral" | "info" | "success" | "attention" | "danger";

const STATUS_TONES: Readonly<Record<TransactionProcessingStatus, StatusTone>> =
  Object.freeze({
    RECEIVED: "neutral",
    ANALYZING: "info",
    ANALYZED: "info",
    APPROVED: "success",
    ADDITIONAL_AUTH_REQUIRED: "attention",
    HELD: "attention",
    FAILED: "danger",
  });

export function processingStatusTone(status: TransactionProcessingStatus): StatusTone {
  return STATUS_TONES[status];
}

/**
 * The one-based item window a page covers, for the result line.
 *
 * Derived from metadata the API layer has already checked for internal
 * consistency, so this only has to state the window rather than defend against
 * a page whose arithmetic disagrees with itself.
 */
export interface ResultWindow {
  readonly first: number;
  readonly last: number;
  readonly total: number;
}

export function describeResultWindow(
  pageNumber: number,
  pageSize: number,
  itemCount: number,
  totalElements: number,
): ResultWindow {
  if (itemCount === 0) {
    return { first: 0, last: 0, total: totalElements };
  }
  const first = pageNumber * pageSize + 1;
  return { first, last: first + itemCount - 1, total: totalElements };
}

/**
 * The draft filter values, exactly as they are typed.
 *
 * Everything here is a string because everything here is an `<input>` value.
 * The draft lives in the page's memory and nowhere else: it never reaches the
 * address bar, the history entry, `localStorage`, `sessionStorage` or any error
 * string, so a customer or account reference an analyst types cannot be
 * recovered from the browser afterwards.
 */
export interface TransactionFilterDraft {
  readonly occurredAtFrom: string;
  readonly occurredAtTo: string;
  readonly transactionType: string;
  readonly processingStatus: string;
  readonly externalCustomerRef: string;
  readonly accountRef: string;
}

export const EMPTY_FILTER_DRAFT: TransactionFilterDraft = Object.freeze({
  occurredAtFrom: "",
  occurredAtTo: "",
  transactionType: "",
  processingStatus: "",
  externalCustomerRef: "",
  accountRef: "",
});

/** The page sizes the console offers, all inside Backend's 1..100 window. */
export const PAGE_SIZE_OPTIONS: readonly number[] = Object.freeze([20, 50, 100]);
