import { describe, expect, it } from "vitest";
import { CASE_FINAL_DISPOSITIONS, CASE_STATUSES } from "../../api/caseApi";
import {
  assigneeRefProblem,
  CASE_FINAL_DISPOSITION_LABELS,
  CASE_STATUS_LABELS,
  caseStatusTone,
  describeReference,
  EMPTY_CASE_FILTER_DRAFT,
  formatCaseInstant,
  formatTransactionCount,
  isCanonicalTransactionFilter,
  isReversedUtcRange,
  kstInputToUtcInstant,
  MAX_ASSIGNEE_REF_LENGTH,
  UNASSIGNED_LABEL,
  UNRESOLVED_DISPOSITION_LABEL,
} from "./casePresentation";

/**
 * The display and query rules, exercised as string work rather than through the
 * DOM.
 *
 * Every expected value below is a literal written out here. Nothing is computed
 * by calling the function under test a second time, and nothing is derived from
 * the label tables themselves - a test that read `CASE_STATUS_LABELS.OPEN` and
 * asserted the result equalled it would pass whatever that entry said.
 */

describe("case status labels", () => {
  it("names every Backend status exactly", () => {
    expect(CASE_STATUS_LABELS.OPEN).toBe("Open");
    expect(CASE_STATUS_LABELS.IN_REVIEW).toBe("In review");
    expect(CASE_STATUS_LABELS.ADDITIONAL_INFORMATION_REQUIRED).toBe("Information required");
    expect(CASE_STATUS_LABELS.CLOSED).toBe("Closed");
  });

  it("covers the enum exactly, with no extra entry and no gap", () => {
    // Both directions. A status added to the Backend enum without a label here
    // fails the first check; a label invented for a value the enum does not
    // have fails the second.
    expect([...CASE_STATUSES].sort()).toEqual(Object.keys(CASE_STATUS_LABELS).sort());
    expect(Object.keys(CASE_STATUS_LABELS)).toHaveLength(4);
  });

  it("gives every status a tone, and never colour alone", () => {
    expect(caseStatusTone("OPEN")).toBe("neutral");
    expect(caseStatusTone("IN_REVIEW")).toBe("info");
    expect(caseStatusTone("ADDITIONAL_INFORMATION_REQUIRED")).toBe("attention");
    expect(caseStatusTone("CLOSED")).toBe("success");
  });

  it("assigns no danger tone, because a status is not a risk judgement", () => {
    // The list response carries no risk score and no priority. A "danger" tone
    // on a workflow state would be this console inventing one.
    for (const status of CASE_STATUSES) {
      expect(caseStatusTone(status)).not.toBe("danger");
    }
  });

  it("has no fallback for a value outside the enum", () => {
    // The response validator refuses such a value first, and nothing here
    // invents a name for it: the lookup is undefined rather than a plausible
    // string a reader would take for a real Backend state.
    const table = CASE_STATUS_LABELS as Readonly<Record<string, string | undefined>>;
    expect(table.ESCALATED).toBeUndefined();
    expect(table.REOPENED).toBeUndefined();
  });
});

describe("case final disposition labels", () => {
  it("names every Backend disposition exactly", () => {
    expect(CASE_FINAL_DISPOSITION_LABELS.NORMAL).toBe("Normal");
    expect(CASE_FINAL_DISPOSITION_LABELS.FALSE_POSITIVE).toBe("False positive");
    expect(CASE_FINAL_DISPOSITION_LABELS.CONFIRMED_FRAUD).toBe("Confirmed fraud");
  });

  it("covers the enum exactly", () => {
    expect([...CASE_FINAL_DISPOSITIONS].sort()).toEqual(
      Object.keys(CASE_FINAL_DISPOSITION_LABELS).sort(),
    );
    expect(Object.keys(CASE_FINAL_DISPOSITION_LABELS)).toHaveLength(3);
  });

  it("says exactly Not resolved for a case with no disposition", () => {
    expect(UNRESOLVED_DISPOSITION_LABEL).toBe("Not resolved");
    // Not one of the three verdicts. An unresolved case must not read as a
    // decided one.
    expect(Object.values(CASE_FINAL_DISPOSITION_LABELS)).not.toContain(
      UNRESOLVED_DISPOSITION_LABEL,
    );
  });

  it("says exactly Unassigned for a case with no assignee", () => {
    expect(UNASSIGNED_LABEL).toBe("Unassigned");
  });
});

describe("related transaction count", () => {
  it("prints the integer the validator admitted, unchanged", () => {
    expect(formatTransactionCount(0)).toBe("0");
    expect(formatTransactionCount(1)).toBe("1");
    expect(formatTransactionCount(42)).toBe("42");
  });

  it("does not group, abbreviate or round a large count", () => {
    expect(formatTransactionCount(1234567)).toBe("1234567");
    expect(formatTransactionCount(Number.MAX_SAFE_INTEGER)).toBe("9007199254740991");
    expect(formatTransactionCount(1234567)).not.toContain(",");
    expect(formatTransactionCount(1234567)).not.toContain("K");
  });
});

describe("case instants in Asia/Seoul", () => {
  it("reads a UTC instant as Seoul wall clock, nine hours ahead", () => {
    expect(formatCaseInstant("2026-07-23T01:15:30Z")).toBe("2026-07-23 10:15:30");
    expect(formatCaseInstant("2026-07-24T02:20:40Z")).toBe("2026-07-24 11:20:40");
  });

  it("crosses the date at the Seoul boundary, not the UTC one", () => {
    // 15:00Z is midnight in Seoul the next day.
    expect(formatCaseInstant("2026-07-23T15:00:00Z")).toBe("2026-07-24 00:00:00");
    expect(formatCaseInstant("2026-12-31T15:00:00Z")).toBe("2027-01-01 00:00:00");
  });

  it("refuses an instant that is not a real date", () => {
    expect(formatCaseInstant("2026-02-30T00:00:00Z")).toBeNull();
    expect(formatCaseInstant("not-an-instant")).toBeNull();
  });

  it("turns a Seoul filter input back into the UTC instant Backend expects", () => {
    expect(kstInputToUtcInstant("2026-07-23T10:15")).toBe("2026-07-23T01:15:00Z");
    expect(kstInputToUtcInstant("2026-01-01T00:00")).toBe("2025-12-31T15:00:00Z");
    expect(kstInputToUtcInstant("2026-02-30T00:00")).toBeNull();
  });
});

describe("case time ranges", () => {
  it("refuses only a strictly inverted range", () => {
    expect(isReversedUtcRange("2026-07-31T00:00:00Z", "2026-07-01T00:00:00Z")).toBe(true);
    expect(isReversedUtcRange("2026-07-01T00:00:00Z", "2026-07-31T00:00:00Z")).toBe(false);
    // Equal bounds are an empty range Backend accepts, and a half-open range is
    // a legitimate filter.
    expect(isReversedUtcRange("2026-07-01T00:00:00Z", "2026-07-01T00:00:00Z")).toBe(false);
    expect(isReversedUtcRange("2026-07-01T00:00:00Z", null)).toBe(false);
    expect(isReversedUtcRange(null, "2026-07-01T00:00:00Z")).toBe(false);
  });
});

describe("assignee reference filter", () => {
  it("accepts a reference Backend would accept", () => {
    expect(assigneeRefProblem("analyst_ref_demo_a7f2")).toBeNull();
    expect(assigneeRefProblem("a")).toBeNull();
    // Inner spaces are part of the key, not padding.
    expect(assigneeRefProblem("analyst ref demo")).toBeNull();
  });

  it("refuses a blank reference", () => {
    expect(assigneeRefProblem("")).toBe("blank");
    expect(assigneeRefProblem("   ")).toBe("blank");
    expect(assigneeRefProblem("\t\n")).toBe("blank");
  });

  it("refuses one past Backend's 128 characters, at exactly the boundary", () => {
    expect(MAX_ASSIGNEE_REF_LENGTH).toBe(128);
    expect(assigneeRefProblem("a".repeat(128))).toBeNull();
    expect(assigneeRefProblem("a".repeat(129))).toBe("too-long");
  });

  it("refuses a reference that is not equal to its own Java trim", () => {
    expect(assigneeRefProblem(" analyst_ref")).toBe("untrimmed");
    expect(assigneeRefProblem("analyst_ref ")).toBe("untrimmed");
    expect(assigneeRefProblem("\tanalyst_ref")).toBe("untrimmed");
  });

  it("does not apply the case bound to a transaction-shaped reference", () => {
    // `" acct "` is a legitimate transaction filter and an illegitimate case
    // one. The two rules are separate on purpose, and this is the value that
    // proves they have not been collapsed into one.
    expect(assigneeRefProblem(" acct ")).toBe("untrimmed");
  });
});

describe("related transaction filter", () => {
  it("accepts a canonical lowercase UUID v4", () => {
    expect(isCanonicalTransactionFilter("2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001")).toBe(true);
    expect(isCanonicalTransactionFilter("00000000-0000-4000-8000-000000000000")).toBe(true);
    expect(isCanonicalTransactionFilter("ffffffff-ffff-4fff-bfff-ffffffffffff")).toBe(true);
  });

  it.each([
    ["an uppercase UUID", "2F4C0A4E-8A9D-4C2F-9A1B-7D6E5F430001"],
    ["a mixed-case UUID", "2f4c0a4e-8a9d-4c2f-9a1b-7D6E5F430001"],
    ["a version 1 UUID", "2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001"],
    ["a version 5 UUID", "2f4c0a4e-8a9d-5c2f-9a1b-7d6e5f430001"],
    ["an invalid RFC variant nibble", "2f4c0a4e-8a9d-4c2f-1a1b-7d6e5f430001"],
    ["a UUID with no hyphens", "2f4c0a4e8a9d4c2f9a1b7d6e5f430001"],
    ["one digit short", "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f43000"],
    ["one digit long", "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f4300011"],
    ["a leading space", " 2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["a trailing space", "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001 "],
    ["a percent-encoded digit", "%32f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["an empty string", ""],
  ])("refuses %s rather than repairing it", (_label, value) => {
    expect(isCanonicalTransactionFilter(value)).toBe(false);
  });
});

describe("reference display", () => {
  it("shows a stored reference exactly as stored", () => {
    const display = describeReference(" Mixed Case Ref ");
    expect(display.text).toBe(" Mixed Case Ref ");
    expect(display.absent).toBe(false);
  });

  it("marks a null reference absent rather than printing an empty string", () => {
    const display = describeReference(null);
    expect(display.absent).toBe(true);
    expect(display.text).toBe("");
  });

  it("wraps a long reference rather than truncating it", () => {
    const long = "assignee_ref_2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001_desk_0091";
    const display = describeReference(long);
    expect(display.wrap).toBe(true);
    // Nothing is elided: the whole key is still there to compare against
    // another system.
    expect(display.text).toBe(long);
    expect(display.text).not.toContain("...");
  });
});

describe("the empty filter draft", () => {
  it("holds every case filter field, each empty", () => {
    expect(EMPTY_CASE_FILTER_DRAFT).toEqual({
      caseStatus: "",
      finalDisposition: "",
      assigneeRef: "",
      createdAtFrom: "",
      createdAtTo: "",
      lastChangedAtFrom: "",
      lastChangedAtTo: "",
      transactionId: "",
    });
  });

  it("is frozen, so a screen cannot mutate the shared default", () => {
    expect(Object.isFrozen(EMPTY_CASE_FILTER_DRAFT)).toBe(true);
  });
});
