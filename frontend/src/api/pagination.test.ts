import { describe, expect, it } from "vitest";
import {
  buildBackendRequestUrl,
  findApprovedBackendRequest,
} from "./backendEndpoints";
import { RequestNotAllowedError } from "./errors";
import {
  buildQueryValues,
  isConsistentPageMetadata,
  isPageMetadata,
  MAX_PAGE_SIZE,
  MIN_PAGE_SIZE,
  type PageMetadata,
} from "./pagination";

/**
 * Every case below drives the real endpoint contract from the registry rather
 * than a spec written for the test, so a rule that drifts away from the
 * Backend validator fails here as well as at the URL boundary.
 */
const CASE_LIST = "case-list";
const BASE = "http://localhost:8080";

/** Carries hex letters, so case sensitivity is actually observable. */
const UUID = "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f";

function page(overrides: Partial<PageMetadata> = {}): PageMetadata {
  return {
    number: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    first: true,
    last: true,
    ...overrides,
  };
}

describe("buildQueryValues — page", () => {
  it("accepts a zero-based integer page and stringifies it exactly", () => {
    expect(buildQueryValues(CASE_LIST, { page: 0 })).toEqual({ page: "0" });
    expect(buildQueryValues(CASE_LIST, { page: 7 })).toEqual({ page: "7" });
    expect(buildQueryValues(CASE_LIST, { page: 2147483647 })).toEqual({ page: "2147483647" });
  });

  it("refuses a negative page", () => {
    for (const value of [-1, -0.5, -2147483648]) {
      expect(() => buildQueryValues(CASE_LIST, { page: value })).toThrow(RequestNotAllowedError);
    }
  });

  it("refuses a fractional page rather than rounding it", () => {
    for (const value of [0.5, 1.0000001, 2.9999999999]) {
      expect(() => buildQueryValues(CASE_LIST, { page: value })).toThrow(RequestNotAllowedError);
    }
  });

  it("refuses a page past Number.MAX_SAFE_INTEGER or past Java int", () => {
    for (const value of [
      Number.MAX_SAFE_INTEGER + 1,
      Number.MAX_SAFE_INTEGER + 2,
      2 ** 53,
      2147483648,
      1e21,
      Number.MAX_VALUE,
    ]) {
      expect(() => buildQueryValues(CASE_LIST, { page: value })).toThrow(RequestNotAllowedError);
    }
  });

  it("refuses NaN and both infinities", () => {
    for (const value of [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY]) {
      expect(() => buildQueryValues(CASE_LIST, { page: value })).toThrow(RequestNotAllowedError);
    }
  });

  it("refuses a page that is a string, however numeric it looks", () => {
    for (const value of ["0", " 0", "0 ", "1e2", "0x1", "", true, null, [0], { page: 0 }]) {
      expect(() => buildQueryValues(CASE_LIST, { page: value })).toThrow(RequestNotAllowedError);
    }
  });
});

describe("buildQueryValues — size", () => {
  it("accepts the whole Backend window and nothing outside it", () => {
    expect(buildQueryValues(CASE_LIST, { size: MIN_PAGE_SIZE })).toEqual({ size: "1" });
    expect(buildQueryValues(CASE_LIST, { size: MAX_PAGE_SIZE })).toEqual({ size: "100" });
    for (const value of [0, -1, 101, 1000, 20.5, Number.NaN]) {
      expect(() => buildQueryValues(CASE_LIST, { size: value })).toThrow(RequestNotAllowedError);
    }
  });
});

describe("buildQueryValues — sort and enum choices", () => {
  it("accepts only an exact member", () => {
    expect(buildQueryValues(CASE_LIST, { sort: "lastChangedAt,asc" })).toEqual({
      sort: "lastChangedAt,asc",
    });
    expect(buildQueryValues(CASE_LIST, { caseStatus: "IN_REVIEW" })).toEqual({
      caseStatus: "IN_REVIEW",
    });
  });

  it("refuses case, whitespace and separator variants", () => {
    for (const value of [
      "lastChangedAt,ASC",
      "lastChangedAt,Desc",
      "LASTCHANGEDAT,asc",
      " lastChangedAt,asc",
      "lastChangedAt,asc ",
      "lastChangedAt, asc",
      "lastChangedAt",
      "lastChangedAt,asc,createdAt,desc",
      "createdAt,asc",
      "",
    ]) {
      expect(() => buildQueryValues(CASE_LIST, { sort: value })).toThrow(RequestNotAllowedError);
    }
    for (const value of ["in_review", "IN REVIEW", " IN_REVIEW", "IN_REVIEW ", "UNKNOWN"]) {
      expect(() => buildQueryValues(CASE_LIST, { caseStatus: value })).toThrow(RequestNotAllowedError);
    }
  });
});

describe("buildQueryValues — instant, uuid and opaque reference", () => {
  it("accepts UTC Z instants with one to nine fractional digits", () => {
    for (const value of [
      "2026-07-23T00:00:00Z",
      "2026-07-23T00:00:00.1Z",
      "2026-07-23T00:00:00.123456Z",
      "2026-07-23T00:00:00.123456789Z",
    ]) {
      expect(buildQueryValues(CASE_LIST, { createdAtFrom: value })).toEqual({ createdAtFrom: value });
    }
  });

  it("refuses an offset, a missing Z, a local time and an impossible date", () => {
    for (const value of [
      "2026-07-23T00:00:00+09:00",
      "2026-07-23T00:00:00-00:00",
      "2026-07-23T00:00:00",
      "2026-07-23 00:00:00Z",
      "2026-07-23",
      "2026-02-30T00:00:00Z",
      "2026-13-01T00:00:00Z",
      "2026-07-23T24:00:00Z",
      " 2026-07-23T00:00:00Z",
      "2026-07-23T00:00:00Z ",
      "2026-07-23T00:00:00.1234567890Z",
      "2026-07-23T00:00:00z",
    ]) {
      expect(() => buildQueryValues(CASE_LIST, { createdAtFrom: value })).toThrow(
        RequestNotAllowedError,
      );
    }
  });

  it("accepts a canonical lowercase UUID v4 and refuses every near miss", () => {
    expect(buildQueryValues(CASE_LIST, { transactionId: UUID })).toEqual({ transactionId: UUID });
    for (const value of [
      UUID.toUpperCase(),
      `${UUID} `,
      ` ${UUID}`,
      "6f1e0b6c-3a2b-1c8d-9e0f-1a2b3c4d5e6f",
      "6f1e0b6c-3a2b-4c8d-ce0f-1a2b3c4d5e6f",
      "6f1e0b6c3a2b4c8d9e0f1a2b3c4d5e6f",
      "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6",
      "not-a-uuid",
    ]) {
      expect(() => buildQueryValues(CASE_LIST, { transactionId: value })).toThrow(
        RequestNotAllowedError,
      );
    }
  });

  it("keeps an opaque reference exact and refuses blank, padded and over-long values", () => {
    expect(buildQueryValues(CASE_LIST, { assigneeRef: "Analyst_Ref_07" })).toEqual({
      assigneeRef: "Analyst_Ref_07",
    });
    for (const value of ["", "   ", " ref", "ref ", "a".repeat(129), "ref\nvalue"]) {
      expect(() => buildQueryValues(CASE_LIST, { assigneeRef: value })).toThrow(RequestNotAllowedError);
    }
  });
});

describe("buildQueryValues — container discipline", () => {
  it("returns undefined when nothing is set, so no question mark is produced", () => {
    expect(buildQueryValues(CASE_LIST, undefined)).toBeUndefined();
    expect(buildQueryValues(CASE_LIST, {})).toBeUndefined();
    expect(buildQueryValues(CASE_LIST, { page: undefined, size: undefined })).toBeUndefined();
  });

  it("refuses a name outside the spec", () => {
    for (const input of [{ unknown: "1" }, { page: 0, unknown: "1" }, { Page: 0 }]) {
      expect(() => buildQueryValues(CASE_LIST, input)).toThrow(RequestNotAllowedError);
    }
  });

  it("refuses an inherited, non-enumerable or symbol-keyed property", () => {
    expect(() => buildQueryValues(CASE_LIST, Object.create({ page: 0 }))).toThrow(
      RequestNotAllowedError,
    );

    const nonEnumerable = {};
    Object.defineProperty(nonEnumerable, "unknown", { value: "1", enumerable: false });
    expect(() => buildQueryValues(CASE_LIST, nonEnumerable)).toThrow(RequestNotAllowedError);

    const symbolKeyed: Record<symbol, string> = {};
    symbolKeyed[Symbol("page")] = "0";
    expect(() => buildQueryValues(CASE_LIST, symbolKeyed)).toThrow(RequestNotAllowedError);
  });

  it("refuses a non-plain container", () => {
    class Holder {
      page = 0;
    }
    for (const input of [new Holder(), [], new URLSearchParams("page=0"), new Map(), "page=0", 0]) {
      expect(() => buildQueryValues(CASE_LIST, input)).toThrow(RequestNotAllowedError);
    }
  });

  it("treats an explicit null as a value, not as an omission", () => {
    expect(() => buildQueryValues(CASE_LIST, { page: null })).toThrow(RequestNotAllowedError);
    expect(() => buildQueryValues(CASE_LIST, { assigneeRef: null })).toThrow(RequestNotAllowedError);
  });

  it("emits in spec order regardless of the input key order", () => {
    const built = buildQueryValues(CASE_LIST, { sort: "lastChangedAt,asc", page: 1, caseStatus: "OPEN" });
    expect(Object.keys(built ?? {})).toEqual(["caseStatus", "page", "sort"]);
  });
});

describe("isPageMetadata", () => {
  it("accepts the exact six-key envelope", () => {
    expect(isPageMetadata(page())).toBe(true);
    expect(isPageMetadata(page({ number: 1, totalElements: 45, totalPages: 3, first: false })))
      .toBe(true);
  });

  it("refuses a missing or extra key", () => {
    const complete = page() as unknown as Record<string, unknown>;
    for (const key of Object.keys(complete)) {
      const missing = { ...complete };
      delete missing[key];
      expect(isPageMetadata(missing)).toBe(false);
    }
    expect(isPageMetadata({ ...complete, empty: true })).toBe(false);
    expect(isPageMetadata({ ...complete, numberOfElements: 0 })).toBe(false);
  });

  it("refuses a value of the wrong type", () => {
    expect(isPageMetadata(page({ number: "0" as unknown as number }))).toBe(false);
    expect(isPageMetadata(page({ first: "true" as unknown as boolean }))).toBe(false);
    expect(isPageMetadata(page({ last: 1 as unknown as boolean }))).toBe(false);
    expect(isPageMetadata(page({ totalElements: null as unknown as number }))).toBe(false);
  });

  it("refuses a negative number, an out-of-window size and a fractional count", () => {
    expect(isPageMetadata(page({ number: -1 }))).toBe(false);
    expect(isPageMetadata(page({ size: 0 }))).toBe(false);
    expect(isPageMetadata(page({ size: 101 }))).toBe(false);
    expect(isPageMetadata(page({ totalElements: -1 }))).toBe(false);
    expect(isPageMetadata(page({ totalElements: 1.5 }))).toBe(false);
    expect(isPageMetadata(page({ totalPages: -1 }))).toBe(false);
  });

  it("refuses a long that has already lost precision in JSON.parse", () => {
    expect(isPageMetadata(page({ totalElements: Number.MAX_SAFE_INTEGER + 1 }))).toBe(false);
    expect(isPageMetadata(page({ totalElements: 2 ** 53 }))).toBe(false);
    expect(isPageMetadata(page({ totalElements: Number("9223372036854775807") }))).toBe(false);
  });

  it("refuses a non-object and an array", () => {
    for (const value of [null, undefined, 0, "page", [], [page()]]) {
      expect(isPageMetadata(value)).toBe(false);
    }
  });
});

describe("isConsistentPageMetadata", () => {
  it("accepts an empty result", () => {
    expect(isConsistentPageMetadata(page(), 0)).toBe(true);
  });

  it("accepts a single full page and a single partial page", () => {
    expect(
      isConsistentPageMetadata(page({ totalElements: 20, totalPages: 1, last: true }), 20),
    ).toBe(true);
    expect(
      isConsistentPageMetadata(page({ totalElements: 3, totalPages: 1, last: true }), 3),
    ).toBe(true);
  });

  it("accepts a middle page, a last page and a page past the end", () => {
    expect(
      isConsistentPageMetadata(
        page({ number: 1, totalElements: 45, totalPages: 3, first: false, last: false }),
        20,
      ),
    ).toBe(true);
    expect(
      isConsistentPageMetadata(
        page({ number: 2, totalElements: 45, totalPages: 3, first: false, last: true }),
        5,
      ),
    ).toBe(true);
    expect(
      isConsistentPageMetadata(
        page({ number: 9, totalElements: 45, totalPages: 3, first: false, last: true }),
        0,
      ),
    ).toBe(true);
  });

  it("refuses totalPages that is not the ceiling of totalElements over size", () => {
    for (const totalPages of [0, 1, 2, 4]) {
      expect(
        isConsistentPageMetadata(page({ totalElements: 45, totalPages, last: false }), 20),
      ).toBe(false);
    }
    expect(isConsistentPageMetadata(page({ totalElements: 0, totalPages: 1 }), 0)).toBe(false);
  });

  it("refuses first or last that contradicts the page number", () => {
    expect(
      isConsistentPageMetadata(
        page({ number: 0, totalElements: 45, totalPages: 3, first: false, last: false }),
        20,
      ),
    ).toBe(false);
    expect(
      isConsistentPageMetadata(
        page({ number: 1, totalElements: 45, totalPages: 3, first: true, last: false }),
        20,
      ),
    ).toBe(false);
    expect(
      isConsistentPageMetadata(
        page({ number: 0, totalElements: 45, totalPages: 3, first: true, last: true }),
        20,
      ),
    ).toBe(false);
    expect(
      isConsistentPageMetadata(
        page({ number: 2, totalElements: 45, totalPages: 3, first: false, last: false }),
        5,
      ),
    ).toBe(false);
  });

  it("refuses an item count that contradicts the page", () => {
    // more items than the page size
    expect(
      isConsistentPageMetadata(page({ totalElements: 45, totalPages: 3, last: false }), 21),
    ).toBe(false);
    // a non-final page that is not full
    expect(
      isConsistentPageMetadata(page({ totalElements: 45, totalPages: 3, last: false }), 19),
    ).toBe(false);
    // a final page whose remainder does not match
    expect(
      isConsistentPageMetadata(
        page({ number: 2, totalElements: 45, totalPages: 3, first: false, last: true }),
        4,
      ),
    ).toBe(false);
    // an empty result that still carries items
    expect(isConsistentPageMetadata(page(), 1)).toBe(false);
    // a page past the end that still carries items
    expect(
      isConsistentPageMetadata(
        page({ number: 9, totalElements: 45, totalPages: 3, first: false, last: true }),
        1,
      ),
    ).toBe(false);
  });
});

describe("buildQueryValues — the endpoint owns the contract", () => {
  it("applies each endpoint's own filter vocabulary, not a shared one", () => {
    // `occurredAtFrom` belongs to the transaction list and to nothing else.
    expect(
      buildQueryValues("transaction-list", { occurredAtFrom: "2026-07-23T00:00:00Z" }),
    ).toEqual({ occurredAtFrom: "2026-07-23T00:00:00Z" });
    expect(() =>
      buildQueryValues(CASE_LIST, { occurredAtFrom: "2026-07-23T00:00:00Z" }),
    ).toThrow(RequestNotAllowedError);

    // and `caseStatus` belongs to the case list and to nothing else.
    expect(buildQueryValues(CASE_LIST, { caseStatus: "OPEN" })).toEqual({ caseStatus: "OPEN" });
    expect(() => buildQueryValues("transaction-list", { caseStatus: "OPEN" })).toThrow(
      RequestNotAllowedError,
    );
  });

  it("applies each endpoint's own sort field", () => {
    const cases: ReadonlyArray<readonly [string, string, string]> = [
      ["transaction-list", "occurredAt,desc", "lastChangedAt,desc"],
      [CASE_LIST, "lastChangedAt,desc", "occurredAt,desc"],
      ["case-note-list", "createdAt,asc", "changedAt,asc"],
      ["case-audit-list", "changedAt,desc", "createdAt,desc"],
    ];
    for (const [endpoint, accepted, refused] of cases) {
      expect(buildQueryValues(endpoint, { sort: accepted })).toEqual({ sort: accepted });
      expect(() => buildQueryValues(endpoint, { sort: refused })).toThrow(RequestNotAllowedError);
    }
  });

  it("refuses a query argument on an endpoint that declares none, empty object included", () => {
    for (const endpoint of [
      "transaction-detail",
      "case-detail",
      "case-status-change",
      "case-assignee-change",
      "case-resolution-create",
      "case-note-create",
    ]) {
      expect(() => buildQueryValues(endpoint, {})).toThrow(RequestNotAllowedError);
      expect(() => buildQueryValues(endpoint, { page: 0 })).toThrow(RequestNotAllowedError);
      // Passing nothing is still how those endpoints are called.
      expect(buildQueryValues(endpoint, undefined)).toBeUndefined();
    }
  });

  it("refuses an endpoint key that is not in the registry", () => {
    for (const endpoint of ["unknown-endpoint", "", "toString", "__proto__", "constructor"]) {
      expect(() => buildQueryValues(endpoint, { page: 0 })).toThrow(RequestNotAllowedError);
      expect(() => buildQueryValues(endpoint, undefined)).toThrow(RequestNotAllowedError);
    }
  });
});

/**
 * The range contract belongs to the endpoint, not to the API module, so it is
 * exercised through the same typed builder every caller uses. The URL layers
 * run the identical declaration; their coverage lives with the registry, the
 * transport and the credential capability respectively.
 */
describe("buildQueryValues — instant range contract", () => {
  const RANGES: ReadonlyArray<readonly [string, string, string]> = [
    ["transaction-list", "occurredAtFrom", "occurredAtTo"],
    [CASE_LIST, "createdAtFrom", "createdAtTo"],
    [CASE_LIST, "lastChangedAtFrom", "lastChangedAtTo"],
  ];

  it("accepts an ordered range, an empty one, and either bound alone", () => {
    for (const [endpoint, from, to] of RANGES) {
      const early = "2026-07-23T00:00:00Z";
      const late = "2026-07-24T00:00:00Z";

      expect(buildQueryValues(endpoint, { [from]: early, [to]: late })).toEqual({
        [from]: early,
        [to]: late,
      });
      expect(buildQueryValues(endpoint, { [from]: early, [to]: early })).toEqual({
        [from]: early,
        [to]: early,
      });
      expect(buildQueryValues(endpoint, { [from]: late })).toEqual({ [from]: late });
      expect(buildQueryValues(endpoint, { [to]: early })).toEqual({ [to]: early });
    }
  });

  it("refuses an inverted range on every declared range", () => {
    for (const [endpoint, from, to] of RANGES) {
      expect(() =>
        buildQueryValues(endpoint, {
          [from]: "2026-07-24T00:00:00Z",
          [to]: "2026-07-23T00:00:00Z",
        }),
      ).toThrow(RequestNotAllowedError);
    }
  });

  /**
   * The reason this cannot go through `Date`: it truncates at milliseconds, so
   * both bounds collapse to the same instant and the inversion disappears.
   */
  it("refuses an inversion that only exists below millisecond resolution", () => {
    const from = "2026-07-23T00:00:00.000000002Z";
    const to = "2026-07-23T00:00:00.000000001Z";
    expect(new Date(from).getTime()).toBe(new Date(to).getTime());

    for (const [endpoint, fromName, toName] of RANGES) {
      expect(() =>
        buildQueryValues(endpoint, { [fromName]: from, [toName]: to }),
      ).toThrow(RequestNotAllowedError);
      expect(
        buildQueryValues(endpoint, { [fromName]: to, [toName]: from }),
      ).toEqual({ [fromName]: to, [toName]: from });
    }
  });

  it("compares fractional digits by position, not as a number", () => {
    // .1 is 100ms and .09 is 90ms, so this range is ordered even though "09"
    // reads as the larger number.
    expect(
      buildQueryValues("transaction-list", {
        occurredAtFrom: "2026-07-23T00:00:00.09Z",
        occurredAtTo: "2026-07-23T00:00:00.1Z",
      }),
    ).toBeDefined();
    expect(() =>
      buildQueryValues("transaction-list", {
        occurredAtFrom: "2026-07-23T00:00:00.1Z",
        occurredAtTo: "2026-07-23T00:00:00.09Z",
      }),
    ).toThrow(RequestNotAllowedError);
  });

  it("refuses a microsecond inversion inside one second, and orders across seconds", () => {
    expect(() =>
      buildQueryValues("transaction-list", {
        occurredAtFrom: "2026-07-23T00:00:00.123457Z",
        occurredAtTo: "2026-07-23T00:00:00.123456Z",
      }),
    ).toThrow(RequestNotAllowedError);
    expect(() =>
      buildQueryValues("transaction-list", {
        occurredAtFrom: "2026-07-23T00:00:01.000000000Z",
        occurredAtTo: "2026-07-23T00:00:00.999999999Z",
      }),
    ).toThrow(RequestNotAllowedError);
    expect(
      buildQueryValues("transaction-list", {
        occurredAtFrom: "2026-07-23T00:00:00.999999999Z",
        occurredAtTo: "2026-07-23T00:00:01.000000000Z",
      }),
    ).toBeDefined();
  });

  it("keeps the two case ranges independent of each other", () => {
    // An ordered createdAt range does not excuse an inverted lastChangedAt one.
    expect(() =>
      buildQueryValues(CASE_LIST, {
        createdAtFrom: "2026-07-23T00:00:00Z",
        createdAtTo: "2026-07-24T00:00:00Z",
        lastChangedAtFrom: "2026-07-25T00:00:00Z",
        lastChangedAtTo: "2026-07-24T00:00:00Z",
      }),
    ).toThrow(RequestNotAllowedError);

    // and a `from` from one range is not compared against the other's `to`.
    expect(
      buildQueryValues(CASE_LIST, {
        createdAtFrom: "2026-07-25T00:00:00Z",
        lastChangedAtTo: "2026-07-23T00:00:00Z",
      }),
    ).toBeDefined();
  });

  it("still refuses a malformed bound before the range is even considered", () => {
    expect(() =>
      buildQueryValues("transaction-list", {
        occurredAtFrom: "2026-07-23",
        occurredAtTo: "2026-07-24T00:00:00Z",
      }),
    ).toThrow(RequestNotAllowedError);
    expect(() =>
      buildQueryValues("transaction-list", {
        occurredAtFrom: "2026-07-23T00:00:00+09:00",
        occurredAtTo: "2026-07-24T00:00:00Z",
      }),
    ).toThrow(RequestNotAllowedError);
  });
});

/**
 * The transaction references and the case assignee are validated by two
 * different Backend validators, and this is where the two rules are pinned
 * apart. Collapsing them back into one shared rule fails here.
 */
describe("buildQueryValues — reference rules are not shared", () => {
  const PADDED = " acct ";
  const LONG = "a".repeat(129);

  it("accepts a padded but non-blank transaction reference, verbatim", () => {
    for (const name of ["externalCustomerRef", "accountRef"]) {
      expect(buildQueryValues("transaction-list", { [name]: PADDED })).toEqual({
        [name]: PADDED,
      });
    }
  });

  it("accepts a transaction reference longer than 128 characters", () => {
    // `TransactionQueryValidator` imposes no length bound, so neither does this.
    expect(buildQueryValues("transaction-list", { accountRef: LONG })).toEqual({
      accountRef: LONG,
    });
  });

  it("refuses the same two values as a case assignee, which has its own bounds", () => {
    expect(() => buildQueryValues(CASE_LIST, { assigneeRef: PADDED })).toThrow(
      RequestNotAllowedError,
    );
    expect(() => buildQueryValues(CASE_LIST, { assigneeRef: LONG })).toThrow(
      RequestNotAllowedError,
    );
  });

  it("refuses a blank value on both, by Java's definition of blank", () => {
    for (const value of ["", " ", "   ", "\u3000", "\u2028"]) {
      expect(() => buildQueryValues("transaction-list", { accountRef: value })).toThrow(
        RequestNotAllowedError,
      );
      expect(() => buildQueryValues(CASE_LIST, { assigneeRef: value })).toThrow(
        RequestNotAllowedError,
      );
    }
  });

  it("treats a no-break space as content, because String.isBlank does", () => {
    // U+00A0 is Zs but not `Character.isWhitespace`, so a reference made of one
    // is not blank to Backend and must not be refused here.
    expect(buildQueryValues("transaction-list", { accountRef: "\u00a0" })).toEqual({
      accountRef: "\u00a0",
    });
    // The case assignee is bounded differently: U+00A0 is above U+0020, so it
    // survives Java's trim comparison too.
    expect(buildQueryValues(CASE_LIST, { assigneeRef: "\u00a0" })).toEqual({
      assigneeRef: "\u00a0",
    });
  });

  it("percent-encodes a padded reference rather than trimming it", () => {
    const { url } = buildBackendRequestUrl(BASE, "transaction-list", undefined, {
      accountRef: PADDED,
    });
    expect(url).toBe(`${BASE}/api/v1/transactions?accountRef=+acct+`);
    expect(new URL(url).searchParams.get("accountRef")).toBe(PADDED);
    expect(findApprovedBackendRequest(BASE, "GET", url)?.key).toBe("transaction-list");
  });
});
