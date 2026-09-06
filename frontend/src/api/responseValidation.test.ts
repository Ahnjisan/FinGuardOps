import { describe, expect, it } from "vitest";
import { InvalidResponseError } from "./errors";
import {
  compareUtcInstants,
  hasExactKeys,
  isArrayOf,
  isBoolean,
  isEnumMember,
  isInt32,
  isIntegerAmountString,
  isJavaBlank,
  isJavaTrimmed,
  isJavaWhitespaceCodePoint,
  isMicrosecondUtcInstantString,
  isJsonObject,
  isNoteContentString,
  isNullableOpaqueRefString,
  isNullableUtcInstantString,
  isNullableUuidV4String,
  isObjectWithExactKeys,
  isOpaqueRefString,
  isSafeIntegerInRange,
  isSafeLong,
  isTraceIdString,
  isUtcInstantString,
  isUuidV4String,
  resolveTraceId,
} from "./responseValidation";

const UUID = "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f";

describe("isJsonObject", () => {
  it("accepts what JSON.parse can produce for an object", () => {
    expect(isJsonObject({})).toBe(true);
    expect(isJsonObject({ a: 1 })).toBe(true);
    expect(isJsonObject(JSON.parse('{"a":1}'))).toBe(true);
    expect(isJsonObject(Object.create(null))).toBe(true);
  });

  it("refuses null, arrays, primitives and prototype-bearing values", () => {
    class Holder {}
    for (const value of [null, undefined, 0, "", "{}", true, [], [1], new Holder(), new Map()]) {
      expect(isJsonObject(value)).toBe(false);
    }
  });
});

describe("hasExactKeys / isObjectWithExactKeys", () => {
  it("requires the key sets to match in both directions", () => {
    expect(hasExactKeys({ a: 1, b: 2 }, ["a", "b"])).toBe(true);
    expect(hasExactKeys({ a: 1 }, ["a", "b"])).toBe(false);
    expect(hasExactKeys({ a: 1, b: 2, c: 3 }, ["a", "b"])).toBe(false);
    expect(hasExactKeys({ a: 1, c: 2 }, ["a", "b"])).toBe(false);
  });

  it("counts a non-enumerable own key and refuses a symbol key", () => {
    const nonEnumerable: Record<string, unknown> = { a: 1 };
    Object.defineProperty(nonEnumerable, "b", { value: 2, enumerable: false });
    expect(hasExactKeys(nonEnumerable, ["a"])).toBe(false);
    expect(hasExactKeys(nonEnumerable, ["a", "b"])).toBe(true);

    const symbolKeyed: Record<string, unknown> = { a: 1 };
    (symbolKeyed as Record<symbol, unknown>)[Symbol("b")] = 2;
    expect(hasExactKeys(symbolKeyed, ["a"])).toBe(false);
  });

  it("does not count an inherited key as present", () => {
    const inherited = Object.create({ a: 1 }) as Record<string, unknown>;
    expect(isObjectWithExactKeys(inherited, ["a"])).toBe(false);
  });

  it("accepts an object with no keys against an empty contract", () => {
    expect(isObjectWithExactKeys({}, [])).toBe(true);
    expect(isObjectWithExactKeys({ a: 1 }, [])).toBe(false);
  });
});

describe("isSafeLong / isSafeIntegerInRange / isInt32", () => {
  it("accepts exact integers within the safe range", () => {
    for (const value of [0, 1, -1, Number.MAX_SAFE_INTEGER, -Number.MAX_SAFE_INTEGER]) {
      expect(isSafeLong(value)).toBe(true);
    }
  });

  it("refuses a long that JSON.parse has already rounded", () => {
    for (const value of [
      Number.MAX_SAFE_INTEGER + 1,
      Number.MAX_SAFE_INTEGER + 2,
      2 ** 53,
      2 ** 63,
      Number("9223372036854775807"),
      -(2 ** 53),
    ]) {
      expect(isSafeLong(value)).toBe(false);
    }
  });

  it("refuses fractions, NaN, infinities and non-numbers", () => {
    for (const value of [
      0.5,
      Number.NaN,
      Number.POSITIVE_INFINITY,
      Number.NEGATIVE_INFINITY,
      "1",
      null,
      undefined,
      true,
      [1],
    ]) {
      expect(isSafeLong(value)).toBe(false);
    }
  });

  it("bounds an inclusive range and the Java int window", () => {
    expect(isSafeIntegerInRange(1, 1, 100)).toBe(true);
    expect(isSafeIntegerInRange(100, 1, 100)).toBe(true);
    expect(isSafeIntegerInRange(0, 1, 100)).toBe(false);
    expect(isSafeIntegerInRange(101, 1, 100)).toBe(false);

    expect(isInt32(2147483647)).toBe(true);
    expect(isInt32(-2147483648)).toBe(true);
    expect(isInt32(2147483648)).toBe(false);
    expect(isInt32(-2147483649)).toBe(false);
  });
});

describe("isUuidV4String", () => {
  it("accepts only the canonical lowercase v4 form", () => {
    expect(isUuidV4String(UUID)).toBe(true);
    expect(isNullableUuidV4String(null)).toBe(true);
    expect(isNullableUuidV4String(UUID)).toBe(true);
  });

  it("refuses case, version, variant, separator and length variants", () => {
    for (const value of [
      UUID.toUpperCase(),
      "6F1E0B6C-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
      "6f1e0b6c-3a2b-1c8d-9e0f-1a2b3c4d5e6f",
      "6f1e0b6c-3a2b-5c8d-9e0f-1a2b3c4d5e6f",
      "6f1e0b6c-3a2b-4c8d-ce0f-1a2b3c4d5e6f",
      "6f1e0b6c-3a2b-4c8d-7e0f-1a2b3c4d5e6f",
      "6f1e0b6c3a2b4c8d9e0f1a2b3c4d5e6f",
      "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6",
      "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6ff",
      `{${UUID}}`,
      ` ${UUID}`,
      `${UUID} `,
      "",
      null,
      undefined,
      123,
    ]) {
      expect(isUuidV4String(value)).toBe(false);
    }
    expect(isNullableUuidV4String(undefined)).toBe(false);
  });
});

describe("isUtcInstantString", () => {
  it("accepts second and sub-second UTC Z precision", () => {
    for (const value of [
      "2026-07-23T01:15:30Z",
      "2026-07-23T01:15:30.1Z",
      "2026-07-23T01:15:30.12Z",
      "2026-07-23T01:15:30.123Z",
      "2026-07-23T03:10:00.123456Z",
      "2026-07-23T03:10:00.123456789Z",
      "2024-02-29T00:00:00Z",
      "2000-01-01T00:00:00Z",
    ]) {
      expect(isUtcInstantString(value)).toBe(true);
    }
  });

  it("refuses an offset or a local time", () => {
    for (const value of [
      "2026-07-23T01:15:30+09:00",
      "2026-07-23T01:15:30-05:00",
      "2026-07-23T01:15:30+00:00",
      "2026-07-23T01:15:30",
      "2026-07-23T01:15:30z",
      "2026-07-23 01:15:30Z",
      "2026-07-23T01:15Z",
      "2026-07-23",
      "20260723T011530Z",
    ]) {
      expect(isUtcInstantString(value)).toBe(false);
    }
  });

  it("refuses a date that matches the shape but is not a date", () => {
    for (const value of [
      "2026-02-30T00:00:00Z",
      "2026-13-01T00:00:00Z",
      "2026-00-01T00:00:00Z",
      "2026-07-32T00:00:00Z",
      "2026-07-23T24:00:00Z",
      "2026-07-23T00:60:00Z",
      "2025-02-29T00:00:00Z",
      "2026-02-29T00:00:00Z",
    ]) {
      expect(isUtcInstantString(value)).toBe(false);
    }
  });

  it("refuses padded, empty and non-string values, and never trims", () => {
    for (const value of [
      " 2026-07-23T01:15:30Z",
      "2026-07-23T01:15:30Z ",
      "\t2026-07-23T01:15:30Z",
      "",
      null,
      undefined,
      1753234530000,
      new Date("2026-07-23T01:15:30Z"),
    ]) {
      expect(isUtcInstantString(value)).toBe(false);
    }
    expect(isNullableUtcInstantString(null)).toBe(true);
    expect(isNullableUtcInstantString("2026-07-23T01:15:30Z")).toBe(true);
    expect(isNullableUtcInstantString(undefined)).toBe(false);
  });
});

describe("isIntegerAmountString", () => {
  it("accepts a positive decimal integer string up to the contract length", () => {
    for (const value of ["1", "1250000", "9".repeat(15)]) {
      expect(isIntegerAmountString(value)).toBe(true);
    }
  });

  it("refuses a number, so precision is never lost at this boundary", () => {
    for (const value of [1250000, 1250000.0, Number.MAX_SAFE_INTEGER]) {
      expect(isIntegerAmountString(value)).toBe(false);
    }
  });

  it("refuses zero, signs, leading zeros, fractions and exponents", () => {
    for (const value of [
      "0",
      "00",
      "01250000",
      "-1250000",
      "+1250000",
      "1250000.00",
      "1250.5",
      "1.25e6",
      "1_250_000",
      "1 250 000",
      " 1250000",
      "1250000 ",
      "",
      "abc",
      "9".repeat(16),
    ]) {
      expect(isIntegerAmountString(value)).toBe(false);
    }
  });
});

describe("isOpaqueRefString", () => {
  it("accepts an exact, case-sensitive reference within 1..128 characters", () => {
    for (const value of ["a", "acct_ref_demo_s91c", "Analyst_Ref_07", UUID, "x".repeat(128)]) {
      expect(isOpaqueRefString(value)).toBe(true);
    }
    expect(isNullableOpaqueRefString(null)).toBe(true);
  });

  it("refuses blank, padded, over-long and control-bearing references", () => {
    for (const value of [
      "",
      " ",
      "   ",
      " ref",
      "ref ",
      "\tref",
      "x".repeat(129),
      "ref\nvalue",
      "ref\u0000value",
      "ref\u007fvalue",
      "ref\u0085value",
      null,
      undefined,
      1,
    ]) {
      expect(isOpaqueRefString(value)).toBe(false);
    }
    expect(isNullableOpaqueRefString(undefined)).toBe(false);
  });
});

describe("isNoteContentString", () => {
  it("accepts plain text up to 4,000 code points, newlines included", () => {
    expect(isNoteContentString("조사 메모 원문")).toBe(true);
    expect(isNoteContentString("line one\r\nline two")).toBe(true);
    expect(isNoteContentString("a".repeat(4000))).toBe(true);
    // Astral characters count once, matching Backend's codePointCount.
    expect(isNoteContentString("🙂".repeat(4000))).toBe(true);
  });

  it("preserves rather than repairs markup, quotes and leading whitespace", () => {
    for (const value of [
      "  leading and trailing  ",
      "<script>alert(1)</script>",
      "<img src=x onerror=alert(1)>",
      "'; DROP TABLE fraud_case; --",
      "**markdown**",
    ]) {
      expect(isNoteContentString(value)).toBe(true);
    }
  });

  it("refuses empty, whitespace-only, over-long and control-bearing content", () => {
    for (const value of [
      "",
      " ",
      "\t\n ",
      "a".repeat(4001),
      "🙂".repeat(4001),
      "null\u0000byte",
      "bell\u0007",
      "delete\u007f",
      "next\u0085line",
      null,
      undefined,
      123,
    ]) {
      expect(isNoteContentString(value)).toBe(false);
    }
  });
});

describe("isEnumMember / isBoolean / isArrayOf", () => {
  it("matches an enum member exactly", () => {
    const members = ["OPEN", "CLOSED"] as const;
    expect(isEnumMember("OPEN", members)).toBe(true);
    for (const value of ["open", "Open", " OPEN", "OPEN ", "UNKNOWN", "", null, 0]) {
      expect(isEnumMember(value, members)).toBe(false);
    }
  });

  it("does not treat a prototype name as a member", () => {
    const members = ["OPEN"] as const;
    for (const value of ["toString", "constructor", "__proto__", "hasOwnProperty"]) {
      expect(isEnumMember(value, members)).toBe(false);
    }
  });

  it("accepts booleans only", () => {
    expect(isBoolean(true)).toBe(true);
    expect(isBoolean(false)).toBe(true);
    for (const value of ["true", 1, 0, null, undefined]) {
      expect(isBoolean(value)).toBe(false);
    }
  });

  it("rejects the whole array when one item is malformed", () => {
    expect(isArrayOf([], isUuidV4String)).toBe(true);
    expect(isArrayOf([UUID, UUID], isUuidV4String)).toBe(true);
    expect(isArrayOf([UUID, "not-a-uuid"], isUuidV4String)).toBe(false);
    expect(isArrayOf(["not-a-uuid", UUID], isUuidV4String)).toBe(false);
    expect(isArrayOf([UUID, null], isUuidV4String)).toBe(false);
  });

  it("refuses a non-array", () => {
    for (const value of [null, undefined, {}, "", 0, { length: 0 }]) {
      expect(isArrayOf(value, isUuidV4String)).toBe(false);
    }
  });
});

describe("isTraceIdString", () => {
  it("accepts the official trace id contract", () => {
    for (const value of ["trace0123", "trace_demo_case_list_01", "a".repeat(64)]) {
      expect(isTraceIdString(value)).toBe(true);
    }
  });

  it("refuses values outside it", () => {
    for (const value of ["short", "a".repeat(65), "_leading", "has space", "", null, 1]) {
      expect(isTraceIdString(value)).toBe(false);
    }
  });
});

describe("resolveTraceId", () => {
  it("returns the body trace id when the header is absent", () => {
    expect(resolveTraceId(undefined, "trace_demo_01")).toBe("trace_demo_01");
  });

  it("returns the trace id when header and body agree", () => {
    expect(resolveTraceId("trace_demo_01", "trace_demo_01")).toBe("trace_demo_01");
  });

  it("refuses the response when header and body name different requests", () => {
    for (const headerTraceId of ["trace_demo_02", "TRACE_DEMO_01", "trace_demo_011"]) {
      expect(() => resolveTraceId(headerTraceId, "trace_demo_01")).toThrow(InvalidResponseError);
    }
  });
});

describe("compareUtcInstants", () => {
  it("orders whole seconds", () => {
    expect(compareUtcInstants("2026-07-23T00:00:00Z", "2026-07-23T00:00:01Z")).toBeLessThan(0);
    expect(compareUtcInstants("2026-07-23T00:00:01Z", "2026-07-23T00:00:00Z")).toBeGreaterThan(0);
    expect(compareUtcInstants("2026-07-23T00:00:00Z", "2026-07-23T00:00:00Z")).toBe(0);
  });

  it("orders below millisecond resolution, where Date cannot", () => {
    const a = "2026-07-23T00:00:00.000000001Z";
    const b = "2026-07-23T00:00:00.000000002Z";
    expect(new Date(a).getTime()).toBe(new Date(b).getTime());
    expect(compareUtcInstants(a, b)).toBeLessThan(0);
    expect(compareUtcInstants(b, a)).toBeGreaterThan(0);
    expect(compareUtcInstants(a, a)).toBe(0);
  });

  it("pads fractional digits by position rather than reading them as a number", () => {
    // .1 is 100,000,000ns and .09 is 90,000,000ns.
    expect(
      compareUtcInstants("2026-07-23T00:00:00.09Z", "2026-07-23T00:00:00.1Z"),
    ).toBeLessThan(0);
    // .1 and .100000000 are the same instant.
    expect(compareUtcInstants("2026-07-23T00:00:00.1Z", "2026-07-23T00:00:00.100000000Z")).toBe(0);
    // no fraction is zero nanoseconds, not "unknown".
    expect(
      compareUtcInstants("2026-07-23T00:00:00Z", "2026-07-23T00:00:00.000000001Z"),
    ).toBeLessThan(0);
    expect(compareUtcInstants("2026-07-23T00:00:00Z", "2026-07-23T00:00:00.000000000Z")).toBe(0);
  });

  it("orders across a second boundary at nanosecond resolution", () => {
    expect(
      compareUtcInstants("2026-07-23T00:00:00.999999999Z", "2026-07-23T00:00:01Z"),
    ).toBeLessThan(0);
  });

  it("orders instants before the epoch", () => {
    expect(compareUtcInstants("1969-12-31T23:59:59Z", "1970-01-01T00:00:00Z")).toBeLessThan(0);
    expect(
      compareUtcInstants("1969-12-31T23:59:59.000000002Z", "1969-12-31T23:59:59.000000001Z"),
    ).toBeGreaterThan(0);
  });
});

describe("isIntegerAmountString — contract digit bound", () => {
  it("accepts the whole integral range numeric(19,4) can hold", () => {
    expect(isIntegerAmountString("1")).toBe(true);
    expect(isIntegerAmountString("999999999999999")).toBe(true);
    expect("999999999999999".length).toBe(15);
  });

  it("refuses sixteen digits and beyond", () => {
    expect(isIntegerAmountString("1000000000000000")).toBe(false);
    expect(isIntegerAmountString("9999999999999999")).toBe(false);
    expect(isIntegerAmountString("9".repeat(20))).toBe(false);
    expect(isIntegerAmountString("9".repeat(30))).toBe(false);
  });
});

describe("isJavaWhitespaceCodePoint", () => {
  /**
   * The set Java produces for
   * `Character.isWhitespace(cp) || Character.isSpaceChar(cp)`: the ASCII
   * control whitespace, the file/group/record/unit separators, and the Zs, Zl
   * and Zp general categories - non-breaking spaces included, because
   * `isSpaceChar` counts them even though `isWhitespace` does not.
   */
  const JAVA_WHITESPACE: readonly number[] = [
    0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0xa0, 0x1680, 0x2000, 0x2001,
    0x2002, 0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029,
    0x202f, 0x205f, 0x3000,
  ];

  it("matches Java's set exactly for every code point it contains", () => {
    for (const codePoint of JAVA_WHITESPACE) {
      expect(isJavaWhitespaceCodePoint(codePoint)).toBe(true);
    }
  });

  it("counts the non-breaking spaces that isWhitespace alone would miss", () => {
    for (const codePoint of [0xa0, 0x2007, 0x202f]) {
      expect(isJavaWhitespaceCodePoint(codePoint)).toBe(true);
    }
  });

  it("does not count U+FEFF, which Java classifies as a format character", () => {
    expect(isJavaWhitespaceCodePoint(0xfeff)).toBe(false);
    // JavaScript's own `\s` disagrees, which is exactly why it is not used.
    expect(/\s/u.test("\ufeff")).toBe(true);
  });

  it("does not count characters outside the set", () => {
    for (const codePoint of [
      0x00, 0x08, 0x0e, 0x1b, 0x21, 0x41, 0x7f, 0x85, 0x180e, 0x200b, 0x200c, 0x2060, 0xfeff,
      0x1f600,
    ]) {
      expect(isJavaWhitespaceCodePoint(codePoint)).toBe(false);
    }
  });

  it("agrees with the note-content validator on whitespace-only input", () => {
    // Java treats each of these as whitespace, so a note made only of them is 422.
    for (const codePoint of [0x20, 0xa0, 0x2007, 0x202f, 0x3000, 0x2028]) {
      expect(isNoteContentString(String.fromCodePoint(codePoint))).toBe(false);
    }
    // U+FEFF is not whitespace to Java, so a note made only of it is accepted.
    expect(isNoteContentString("\ufeff")).toBe(true);
    expect(isNoteContentString("\ufeff\ufeff")).toBe(true);
    // and a real character next to Java whitespace is still content.
    expect(isNoteContentString(" a ")).toBe(true);
  });
});

/**
 * `String.isBlank()` is defined on `Character.isWhitespace` alone, which is a
 * narrower set than the one note content uses. The no-break spaces are the
 * whole difference, and they decide whether a reference filter is legal.
 */
describe("isJavaBlank", () => {
  it("treats the empty string and whitespace-only values as blank", () => {
    for (const value of [
      "",
      " ",
      "   ",
      "\u0009",
      "\u000a",
      "\u001c",
      "\u1680",
      "\u2000",
      "\u2028",
      "\u3000",
      "\u0020\u3000\u0009",
    ]) {
      expect(isJavaBlank(value)).toBe(true);
    }
  });

  it("does not treat a no-break space as blank, because isWhitespace does not", () => {
    for (const codePoint of [0xa0, 0x2007, 0x202f]) {
      const value = String.fromCodePoint(codePoint);
      expect(isJavaBlank(value)).toBe(false);
      // The wider union used for note content disagrees, which is the point.
      expect(isJavaWhitespaceCodePoint(codePoint)).toBe(true);
    }
  });

  it("is false as soon as one code point is content", () => {
    for (const value of ["a", " a ", "\u3000a", "메모", "\ufeff"]) {
      expect(isJavaBlank(value)).toBe(false);
    }
  });
});

describe("isJavaTrimmed", () => {
  it("compares against Java trim, which strips only up to U+0020", () => {
    for (const value of ["", "a", "a b", "\u00a0", "a\u00a0"]) {
      expect(isJavaTrimmed(value)).toBe(true);
    }
    for (const value of [" a", "a ", " a ", "\u0009a", "a\u000a"]) {
      expect(isJavaTrimmed(value)).toBe(false);
    }
  });

  it("does not strip what JavaScript trim would", () => {
    // JavaScript strips U+00A0 and U+FEFF; Java does not.
    const nbsp = "\u00a0a\u00a0";
    expect(nbsp.trim()).not.toBe(nbsp);
    expect(isJavaTrimmed(nbsp)).toBe(true);

    const bom = "\ufeffa\ufeff";
    expect(bom.trim()).not.toBe(bom);
    expect(isJavaTrimmed(bom)).toBe(true);
  });
});

describe("isMicrosecondUtcInstantString", () => {
  it("accepts up to microsecond precision", () => {
    for (const value of [
      "2026-07-24T02:05:10Z",
      "2026-07-24T02:05:10.1Z",
      "2026-07-24T02:05:10.12Z",
      "2026-07-24T02:05:10.123Z",
      "2026-07-24T02:05:10.1234Z",
      "2026-07-24T02:05:10.12345Z",
      "2026-07-24T02:05:10.123456Z",
      "2026-07-24T02:05:10.000001Z",
    ]) {
      expect(isMicrosecondUtcInstantString(value), value).toBe(true);
    }
  });

  it("refuses anything finer, which the audit column cannot hold", () => {
    for (const value of [
      "2026-07-24T02:05:10.0000001Z",
      "2026-07-24T02:05:10.00000001Z",
      "2026-07-24T02:05:10.000000001Z",
      "2026-07-24T02:05:10.123456789Z",
    ]) {
      expect(isMicrosecondUtcInstantString(value), value).toBe(false);
      // still a perfectly good instant for every DTO Backend does not narrow
      expect(isUtcInstantString(value)).toBe(true);
    }
  });

  it("accepts a trailing zero at nanosecond width, which is still whole microseconds", () => {
    expect(isMicrosecondUtcInstantString("2026-07-24T02:05:10.123456000Z")).toBe(true);
    expect(isMicrosecondUtcInstantString("2026-07-24T02:05:10.000000100Z")).toBe(false);
  });

  it("still refuses what the shared validator refuses", () => {
    for (const value of [
      "2026-07-24T02:05:10+09:00",
      "2026-02-30T00:00:00Z",
      "2026-07-24",
      null,
      undefined,
    ]) {
      expect(isMicrosecondUtcInstantString(value)).toBe(false);
    }
  });
});
