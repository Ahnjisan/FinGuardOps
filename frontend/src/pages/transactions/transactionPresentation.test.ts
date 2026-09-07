import { describe, expect, it } from "vitest";
import {
  describeReference,
  describeResultWindow,
  EMPTY_FILTER_DRAFT,
  formatAmountDigits,
  formatKstDateTime,
  formatKstDateTimeShort,
  isReversedUtcRange,
  KST_OFFSET_MINUTES,
  kstInputToUtcInstant,
  PAGE_SIZE_OPTIONS,
  PROCESSING_STATUS_LABELS,
  processingStatusTone,
  toKstParts,
  TRANSACTION_TYPE_LABELS,
  WRAPPING_REFERENCE_LENGTH,
} from "./transactionPresentation";
import {
  TRANSACTION_PROCESSING_STATUSES,
  TRANSACTION_TYPES,
} from "../../api/transactionApi";

describe("KST offset", () => {
  it("is a fixed nine hours with no daylight saving", () => {
    expect(KST_OFFSET_MINUTES).toBe(540);
  });
});

describe("formatKstDateTime", () => {
  it("shifts a UTC instant into Seoul wall clock", () => {
    expect(formatKstDateTime("2026-07-23T01:15:30Z")).toBe("2026-07-23 10:15:30");
  });

  it("rolls the date forward when the shift crosses midnight", () => {
    expect(formatKstDateTime("2026-07-22T16:00:00Z")).toBe("2026-07-23 01:00:00");
  });

  it("rolls a year boundary forward", () => {
    expect(formatKstDateTime("2025-12-31T15:30:00Z")).toBe("2026-01-01 00:30:00");
  });

  it("keeps a leap day a leap day", () => {
    expect(formatKstDateTime("2028-02-28T15:00:00Z")).toBe("2028-02-29 00:00:00");
  });

  it("accepts the fractional second the contract allows and drops it from the display", () => {
    expect(formatKstDateTime("2026-07-23T01:15:30.123456Z")).toBe("2026-07-23 10:15:30");
  });

  it("shortens to minute precision for the secondary line", () => {
    expect(formatKstDateTimeShort("2026-07-23T01:15:30Z")).toBe("2026-07-23 10:15");
  });

  const refused: Array<[string, string]> = [
    ["a local time with no zone", "2026-07-23T01:15:30"],
    ["an explicit offset instead of Z", "2026-07-23T01:15:30+09:00"],
    ["a space separator", "2026-07-23 01:15:30Z"],
    ["a lowercase z", "2026-07-23T01:15:30z"],
    ["surrounding whitespace", " 2026-07-23T01:15:30Z "],
    ["a day that does not exist", "2026-02-30T00:00:00Z"],
    ["a thirteenth month", "2026-13-01T00:00:00Z"],
    ["a twenty-fifth hour", "2026-07-23T25:00:00Z"],
    ["a non-leap 29 February", "2027-02-29T00:00:00Z"],
    ["an empty string", ""],
  ];

  it.each(refused)("refuses %s", (_label, value) => {
    expect(formatKstDateTime(value)).toBeNull();
    expect(toKstParts(value)).toBeNull();
  });

  it("does not depend on the host time zone", () => {
    // The value is computed from `Date.UTC` plus a constant and read back with
    // the `getUTC*` accessors, so no host zone takes part. If the shift ever
    // went through a local-time accessor, a machine outside Seoul would produce
    // something other than this.
    const parts = toKstParts("2026-07-23T01:15:30Z");
    expect(parts).toEqual({
      year: 2026,
      month: 7,
      day: 23,
      hour: 10,
      minute: 15,
      second: 30,
    });
  });
});

describe("kstInputToUtcInstant", () => {
  it("turns a Seoul wall-clock input into an explicit UTC instant", () => {
    expect(kstInputToUtcInstant("2026-07-23T10:15")).toBe("2026-07-23T01:15:00Z");
  });

  it("rolls the date back when the shift crosses midnight", () => {
    expect(kstInputToUtcInstant("2026-07-23T01:00")).toBe("2026-07-22T16:00:00Z");
  });

  it("accepts seconds when the control supplies them", () => {
    expect(kstInputToUtcInstant("2026-07-23T10:15:45")).toBe("2026-07-23T01:15:45Z");
  });

  it("round-trips back to the same wall clock", () => {
    const utc = kstInputToUtcInstant("2026-01-01T00:30");
    expect(utc).toBe("2025-12-31T15:30:00Z");
    expect(formatKstDateTimeShort(utc as string)).toBe("2026-01-01 00:30");
  });

  const refused: Array<[string, string]> = [
    ["an empty value", ""],
    ["a date with no time", "2026-07-23"],
    ["a day that does not exist", "2026-02-30T00:00"],
    ["a thirteenth month", "2026-13-01T00:00"],
    ["a twenty-fifth hour", "2026-07-23T25:00"],
    ["a sixtieth minute", "2026-07-23T10:60"],
    ["a trailing Z", "2026-07-23T10:15Z"],
    ["surrounding whitespace", " 2026-07-23T10:15"],
    ["free text", "yesterday"],
  ];

  it.each(refused)("refuses %s", (_label, value) => {
    expect(kstInputToUtcInstant(value)).toBeNull();
  });
});

describe("isReversedUtcRange", () => {
  it("accepts an ordered range", () => {
    expect(isReversedUtcRange("2026-07-01T00:00:00Z", "2026-07-31T00:00:00Z")).toBe(false);
  });

  it("accepts equal bounds, which are an empty range the backend allows", () => {
    expect(isReversedUtcRange("2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z")).toBe(false);
  });

  it("accepts a half-open range in either direction", () => {
    expect(isReversedUtcRange("2026-07-01T00:00:00Z", null)).toBe(false);
    expect(isReversedUtcRange(null, "2026-07-01T00:00:00Z")).toBe(false);
    expect(isReversedUtcRange(null, null)).toBe(false);
  });

  it("refuses a range that runs backwards", () => {
    expect(isReversedUtcRange("2026-07-31T00:00:00Z", "2026-07-01T00:00:00Z")).toBe(true);
  });

  it("refuses an inversion of one second", () => {
    expect(isReversedUtcRange("2026-07-01T00:00:01Z", "2026-07-01T00:00:00Z")).toBe(true);
  });
});

describe("formatAmountDigits", () => {
  it("groups an ordinary amount", () => {
    expect(formatAmountDigits("1250000")).toBe("1,250,000");
  });

  it("leaves a value below one thousand ungrouped", () => {
    expect(formatAmountDigits("999")).toBe("999");
  });

  it("keeps every digit of the largest amount the contract allows", () => {
    const formatted = formatAmountDigits("999999999999999");
    expect(formatted).toBe("999,999,999,999,999");
    expect(formatted.replace(/,/g, "")).toBe("999999999999999");
  });

  it("keeps every digit of an arbitrary fifteen-digit amount", () => {
    const exact = "123456789012345";
    expect(formatAmountDigits(exact)).toBe("123,456,789,012,345");
    expect(formatAmountDigits(exact).replace(/,/g, "")).toBe(exact);
  });

  it("stays exact past the point where a double stops being exact", () => {
    // 2^53 + 1. `Number("9007199254740993")` is 9007199254740992, so this is
    // the case that separates a BigInt path from a double path. Fifteen digits
    // still fits a double today, and this is what keeps the guarantee from
    // depending on that remaining true.
    expect(Number("9007199254740993")).toBe(9007199254740992);
    expect(formatAmountDigits("9007199254740993").replace(/,/g, "")).toBe("9007199254740993");
  });

  it("groups a value the amount contract would not admit rather than throwing", () => {
    expect(formatAmountDigits("0")).toBe("0");
    expect(() => formatAmountDigits("")).not.toThrow();
  });
});

describe("describeReference", () => {
  it("shows a short reference exactly as stored, on one line", () => {
    expect(describeReference("acct_ref_demo_s91c")).toEqual({
      text: "acct_ref_demo_s91c",
      wrap: false,
      absent: false,
    });
  });

  it("wraps a long reference instead of shortening it", () => {
    const uuid = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
    const display = describeReference(uuid);

    expect(display.text).toBe(uuid);
    expect(display.text).toHaveLength(uuid.length);
    expect(display.wrap).toBe(true);
    expect(display.absent).toBe(false);
  });

  it("keeps a 128-character reference whole", () => {
    const longest = "r".repeat(128);
    const display = describeReference(longest);

    expect(display.text).toBe(longest);
    expect(display.wrap).toBe(true);
    expect(display.text).not.toContain("...");
    expect(display.text).not.toContain("…");
  });

  it("wraps only past the threshold", () => {
    expect(describeReference("r".repeat(WRAPPING_REFERENCE_LENGTH)).wrap).toBe(false);
    expect(describeReference("r".repeat(WRAPPING_REFERENCE_LENGTH + 1)).wrap).toBe(true);
  });

  it("does not trim, case fold or otherwise normalise a reference", () => {
    const awkward = "  Mixed Case Ref  ";
    expect(describeReference(awkward).text).toBe(awkward);
  });

  it("reports an absent recipient rather than inventing one", () => {
    expect(describeReference(null)).toEqual({ text: "", wrap: false, absent: true });
  });
});

describe("status and type labels", () => {
  it("names every transaction type", () => {
    for (const type of TRANSACTION_TYPES) {
      expect(TRANSACTION_TYPE_LABELS[type]).toBeTruthy();
    }
    expect(Object.keys(TRANSACTION_TYPE_LABELS)).toHaveLength(TRANSACTION_TYPES.length);
  });

  it("names every processing status", () => {
    for (const status of TRANSACTION_PROCESSING_STATUSES) {
      expect(PROCESSING_STATUS_LABELS[status]).toBeTruthy();
    }
    expect(Object.keys(PROCESSING_STATUS_LABELS)).toHaveLength(
      TRANSACTION_PROCESSING_STATUSES.length,
    );
  });

  it("never labels a processing status as a risk score or level", () => {
    for (const status of TRANSACTION_PROCESSING_STATUSES) {
      expect(PROCESSING_STATUS_LABELS[status].toLowerCase()).not.toContain("risk");
      expect(PROCESSING_STATUS_LABELS[status].toLowerCase()).not.toContain("score");
    }
  });

  it("gives every processing status a tone", () => {
    for (const status of TRANSACTION_PROCESSING_STATUSES) {
      expect(["neutral", "info", "success", "attention", "danger"]).toContain(
        processingStatusTone(status),
      );
    }
  });

  it("uses more than one tone, and never tone alone to tell statuses apart", () => {
    const tones = new Set(
      TRANSACTION_PROCESSING_STATUSES.map((status) => processingStatusTone(status)),
    );
    expect(tones.size).toBeGreaterThan(1);
    // Two statuses may share a tone, so the written label is what distinguishes
    // them; every label is therefore required to be distinct.
    const labels = new Set(
      TRANSACTION_PROCESSING_STATUSES.map((status) => PROCESSING_STATUS_LABELS[status]),
    );
    expect(labels.size).toBe(TRANSACTION_PROCESSING_STATUSES.length);
  });
});

describe("describeResultWindow", () => {
  it("describes the first page", () => {
    expect(describeResultWindow(0, 20, 20, 137)).toEqual({ first: 1, last: 20, total: 137 });
  });

  it("describes a later, partial page", () => {
    expect(describeResultWindow(6, 20, 17, 137)).toEqual({ first: 121, last: 137, total: 137 });
  });

  it("describes an empty result", () => {
    expect(describeResultWindow(0, 20, 0, 0)).toEqual({ first: 0, last: 0, total: 0 });
  });
});

describe("filter defaults", () => {
  it("starts every field empty and frozen", () => {
    expect(EMPTY_FILTER_DRAFT).toEqual({
      occurredAtFrom: "",
      occurredAtTo: "",
      transactionType: "",
      processingStatus: "",
      externalCustomerRef: "",
      accountRef: "",
    });
    expect(Object.isFrozen(EMPTY_FILTER_DRAFT)).toBe(true);
  });

  it("offers only page sizes inside the backend window", () => {
    for (const size of PAGE_SIZE_OPTIONS) {
      expect(size).toBeGreaterThanOrEqual(1);
      expect(size).toBeLessThanOrEqual(100);
    }
    expect(PAGE_SIZE_OPTIONS).toContain(20);
  });
});
