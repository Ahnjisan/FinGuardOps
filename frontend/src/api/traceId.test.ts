import { describe, expect, it } from "vitest";
import { extractSafeTraceId, isSafeTraceId } from "./traceId";

const VALID = "trace0123abcd";

describe("isSafeTraceId — official contract, full match only", () => {
  it("accepts the shortest allowed value (8 characters)", () => {
    expect(isSafeTraceId("a1b2c3d4")).toBe(true);
  });

  it("accepts the longest allowed value (64 characters)", () => {
    expect(isSafeTraceId("a".repeat(64))).toBe(true);
  });

  it("accepts a canonical UUID, which Backend generates by default", () => {
    expect(isSafeTraceId("6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f")).toBe(true);
  });

  it("accepts every permitted punctuation character after the first position", () => {
    expect(isSafeTraceId("a._:-b1234")).toBe(true);
  });

  it("rejects a value shorter than 8 characters", () => {
    expect(isSafeTraceId("a1b2c3d")).toBe(false);
  });

  it("rejects a value longer than 64 characters", () => {
    expect(isSafeTraceId("a".repeat(65))).toBe(false);
  });

  it("rejects an empty value", () => {
    expect(isSafeTraceId("")).toBe(false);
  });

  it("rejects a leading punctuation character", () => {
    expect(isSafeTraceId("-abcdefgh")).toBe(false);
  });

  it("rejects characters outside the contract", () => {
    for (const value of [
      "trace id 01",
      "trace/0123",
      "trace\\0123",
      "trace<0123",
      "trace%200123",
      "trace,0123",
      "trace;0123",
      "traceé0123",
    ]) {
      expect(isSafeTraceId(value)).toBe(false);
    }
  });

  /**
   * A partial-match implementation would accept all of these, because each
   * contains a conforming substring. Full match is what makes the header
   * unusable as an injection point.
   */
  it("rejects a conforming substring embedded in a non-conforming value", () => {
    expect(isSafeTraceId(`<script>${VALID}</script>`)).toBe(false);
    expect(isSafeTraceId(`${VALID}\nX-Injected: 1`)).toBe(false);
    expect(isSafeTraceId(`prefix ${VALID}`)).toBe(false);
    expect(isSafeTraceId(`${VALID} suffix`)).toBe(false);
  });

  it("does not normalize surrounding whitespace into a valid value", () => {
    expect(isSafeTraceId(` ${VALID}`)).toBe(false);
    expect(isSafeTraceId(`${VALID} `)).toBe(false);
    expect(isSafeTraceId(`\t${VALID}\n`)).toBe(false);
  });
});

describe("extractSafeTraceId", () => {
  it("returns the header value when it satisfies the contract", () => {
    const headers = new Headers({ "X-Trace-Id": VALID });

    expect(extractSafeTraceId(headers)).toBe(VALID);
  });

  it("matches the header name case-insensitively, as HTTP requires", () => {
    const headers = new Headers({ "x-trace-id": VALID });

    expect(extractSafeTraceId(headers)).toBe(VALID);
  });

  it("returns undefined when the header is absent", () => {
    expect(extractSafeTraceId(new Headers())).toBeUndefined();
  });

  /**
   * `Headers` itself strips leading and trailing HTTP whitespace from a value,
   * so the interesting cases are the ones it preserves: anything the contract
   * forbids in the middle of the value.
   */
  it("discards a non-conforming value instead of repairing it", () => {
    for (const value of [`prefix ${VALID}`, `${VALID} suffix`, "trace 0123456", "tr", "-abcdefgh"]) {
      expect(extractSafeTraceId(new Headers({ "X-Trace-Id": value }))).toBeUndefined();
    }
  });

  it("discards an empty header value", () => {
    const headers = new Headers({ "X-Trace-Id": "" });

    expect(extractSafeTraceId(headers)).toBeUndefined();
  });
});
