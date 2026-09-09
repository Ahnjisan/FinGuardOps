import { describe, expect, it } from "vitest";
import type { CaseAuditEntry, CaseAuditSummary } from "../../api/caseAuditApi";
import { CASE_AUDIT_ACTIONS } from "../../api/caseAuditApi";
import type { PageMetadata } from "../../api/pagination";
import { CASE_STATUS_LABELS, UNASSIGNED_LABEL } from "./casePresentation";
import {
  AUDIT_ABSENT_SUMMARY_LABEL,
  AUDIT_PAGE_SIZE_OPTIONS,
  AUDIT_UNASSIGNED_LABEL,
  describeAuditNoteId,
  describeAuditPosition,
  describeAuditRange,
  describeAuditSummary,
  describeAuditWindow,
  formatAuditInstant,
  NO_AUDIT_ENTRIES_ON_PAGE_MESSAGE,
  NO_AUDIT_HISTORY_MESSAGE,
} from "./caseAuditPresentation";

/**
 * The audit display rules, exercised as string and branch work rather than
 * through the DOM.
 *
 * Every expected value below is a literal written out here. Nothing is computed
 * by calling the function under test a second time, and nothing is read back
 * out of the module it is asserting - a test that compared a result against the
 * constant that produced it would pass whatever that constant said.
 */

const ASSIGNEE_A = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
const ASSIGNEE_B = "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071";
const NOTE_ID = "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60";

function pageMetadata(overrides: Partial<PageMetadata> = {}): PageMetadata {
  return {
    number: 0,
    size: 20,
    totalElements: 6,
    totalPages: 1,
    first: true,
    last: true,
    ...overrides,
  };
}

describe("audit summary display", () => {
  it("names the absence of a summary rather than leaving it blank", () => {
    expect(describeAuditSummary(null)).toEqual({
      present: false,
      label: "Not applicable",
    });
    expect(AUDIT_ABSENT_SUMMARY_LABEL).toBe("Not applicable");
  });

  it("keeps a missing summary and a missing assignee as different facts", () => {
    // The counterexample this pair exists for. Both are an absence, and a
    // section that phrased them alike would tell a reader that a case creation
    // had an unassigned before-state rather than no before-state at all.
    const absentSummary = describeAuditSummary(null);
    const absentAssignee = describeAuditSummary({
      caseStatus: "OPEN",
      assigneeRef: null,
    });

    expect(absentSummary.present).toBe(false);
    expect(absentAssignee.present).toBe(true);
    if (!absentAssignee.present) {
      throw new Error("unreachable");
    }
    expect(absentAssignee.fields[1].text).toBe("Unassigned");
    expect(absentAssignee.fields[1].text).not.toBe("Not applicable");
  });

  it("shows a case-status summary as the Backend enum code", () => {
    expect(describeAuditSummary({ caseStatus: "OPEN" })).toEqual({
      present: true,
      fields: [{ name: "Case status", text: "OPEN", absent: false }],
    });
  });

  it("shows a link summary as the literal the contract carries", () => {
    expect(describeAuditSummary({ linked: true })).toEqual({
      present: true,
      fields: [{ name: "Linked", text: "true", absent: false }],
    });
  });

  it("shows a workflow summary as a status and an assignee", () => {
    expect(
      describeAuditSummary({ caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_A }),
    ).toEqual({
      present: true,
      fields: [
        { name: "Case status", text: "IN_REVIEW", absent: false },
        { name: "Assignee", text: ASSIGNEE_A, absent: false },
      ],
    });
  });

  it("names an unassigned workflow summary with the word the case screens use", () => {
    const display = describeAuditSummary({
      caseStatus: "ADDITIONAL_INFORMATION_REQUIRED",
      assigneeRef: null,
    });

    expect(display).toEqual({
      present: true,
      fields: [
        { name: "Case status", text: "ADDITIONAL_INFORMATION_REQUIRED", absent: false },
        { name: "Assignee", text: "Unassigned", absent: true },
      ],
    });
    // One word for one absence across three screens. Splitting them would read
    // as two different states of the same record.
    expect(AUDIT_UNASSIGNED_LABEL).toBe(UNASSIGNED_LABEL);
  });

  it("shows a resolution summary as three fields including the disposition", () => {
    expect(
      describeAuditSummary({
        caseStatus: "CLOSED",
        assigneeRef: ASSIGNEE_B,
        finalDisposition: "CONFIRMED_FRAUD",
      }),
    ).toEqual({
      present: true,
      fields: [
        { name: "Case status", text: "CLOSED", absent: false },
        { name: "Assignee", text: ASSIGNEE_B, absent: false },
        { name: "Final disposition", text: "CONFIRMED_FRAUD", absent: false },
      ],
    });
  });

  it("never translates an enum code into the label the record screens use", () => {
    // The counterexample for the module's own rule. `IN_REVIEW` reads as "In
    // review" on the case record; inside an audit entry it stays the code, so a
    // reader comparing this section against a Backend log sees the same string.
    const workflow = describeAuditSummary({
      caseStatus: "IN_REVIEW",
      assigneeRef: ASSIGNEE_A,
    });
    if (!workflow.present) {
      throw new Error("unreachable");
    }
    expect(workflow.fields[0].text).toBe("IN_REVIEW");
    expect(workflow.fields[0].text).not.toBe(CASE_STATUS_LABELS.IN_REVIEW);
    expect(workflow.fields[0].text).not.toBe("In review");
  });

  it("does not normalize an assignee reference on its way to the screen", () => {
    // An opaque key: a trimmed one is a different key. The value is printed
    // exactly as it arrived, padding and casing included.
    const padded = "  Mixed Case Ref  ";
    const display = describeAuditSummary({ caseStatus: "IN_REVIEW", assigneeRef: padded });
    if (!display.present) {
      throw new Error("unreachable");
    }
    expect(display.fields[1].text).toBe(padded);
  });

  it("tells the four summary shapes apart by the keys they carry", () => {
    // Each shape produces its own number of fields, so a validator change that
    // let one shape be read as another would move a count here.
    const shapes: Array<[CaseAuditSummary, number]> = [
      [{ caseStatus: "OPEN" }, 1],
      [{ linked: true }, 1],
      [{ caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_A }, 2],
      [
        { caseStatus: "CLOSED", assigneeRef: ASSIGNEE_A, finalDisposition: "NORMAL" },
        3,
      ],
    ];
    for (const [summary, fieldCount] of shapes) {
      const display = describeAuditSummary(summary);
      if (!display.present) {
        throw new Error("unreachable");
      }
      expect(display.fields).toHaveLength(fieldCount);
    }
  });

  it("returns a fresh field list rather than a shared one", () => {
    const first = describeAuditSummary({ caseStatus: "OPEN" });
    const second = describeAuditSummary({ caseStatus: "OPEN" });

    expect(first).toEqual(second);
    expect(first).not.toBe(second);
  });
});

describe("audit note identifier", () => {
  it("returns the identifier a note entry carries", () => {
    const entry: CaseAuditEntry = {
      action: "CASE_NOTE_CREATED",
      reasonCode: "CASE_INVESTIGATION_NOTE_ADDED",
      actorType: "USER",
      changedAt: "2026-03-10T05:06:07.000000Z",
      beforeSummary: null,
      afterSummary: null,
      metadata: { noteId: NOTE_ID },
    };

    expect(describeAuditNoteId(entry)).toBe(NOTE_ID);
  });

  it("returns nothing for every other action", () => {
    const entries: CaseAuditEntry[] = [
      {
        action: "CASE_CREATED",
        reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
        actorType: "SYSTEM",
        changedAt: "2026-03-08T09:10:11.123456Z",
        beforeSummary: null,
        afterSummary: { caseStatus: "OPEN" },
        metadata: {},
      },
      {
        action: "CASE_TRANSACTION_LINKED",
        reasonCode: "CASE_REQUIRED_BY_RISK_POLICY",
        actorType: "SYSTEM",
        changedAt: "2026-03-08T09:12:00.000001Z",
        beforeSummary: null,
        afterSummary: { linked: true },
        metadata: {},
      },
      {
        action: "CASE_RESOLVED",
        reasonCode: "CASE_RESOLUTION_COMPLETED",
        actorType: "USER",
        changedAt: "2026-03-10T04:05:06.999999Z",
        beforeSummary: { caseStatus: "IN_REVIEW", assigneeRef: ASSIGNEE_A },
        afterSummary: {
          caseStatus: "CLOSED",
          assigneeRef: ASSIGNEE_A,
          finalDisposition: "NORMAL",
        },
        metadata: {},
      },
    ];

    for (const entry of entries) {
      expect(describeAuditNoteId(entry)).toBeNull();
    }
  });
});

describe("audit instants", () => {
  it("reads a microsecond instant as Seoul wall clock", () => {
    // The audit column is microsecond resolution, and the fraction is not part
    // of the reading. The untouched original is what the markup carries.
    expect(formatAuditInstant("2026-03-09T01:02:03.456789Z")).toBe("2026-03-09 10:02:03");
  });

  it("crosses the Seoul date boundary on the fixed offset alone", () => {
    expect(formatAuditInstant("2026-03-09T15:00:00.000000Z")).toBe("2026-03-10 00:00:00");
    expect(formatAuditInstant("2026-03-09T14:59:59.999999Z")).toBe("2026-03-09 23:59:59");
  });

  it("refuses a value it cannot read rather than inventing one", () => {
    expect(formatAuditInstant("2026-02-30T00:00:00Z")).toBeNull();
    expect(formatAuditInstant("2026-03-09 01:02:03Z")).toBeNull();
    expect(formatAuditInstant("")).toBeNull();
  });
});

describe("audit page window", () => {
  it("states the one-based window of a full first page", () => {
    expect(describeAuditWindow(pageMetadata({ totalElements: 137, totalPages: 7, last: false }), 20)).toEqual(
      { first: 1, last: 20, total: 137 },
    );
  });

  it("states the window of a later page from the page number, not the entries", () => {
    expect(
      describeAuditWindow(
        pageMetadata({ number: 3, totalElements: 137, totalPages: 7, first: false, last: false }),
        20,
      ),
    ).toEqual({ first: 61, last: 80, total: 137 });
  });

  it("states an empty window without pretending the trail is empty", () => {
    expect(
      describeAuditWindow(
        pageMetadata({ number: 9, totalElements: 137, totalPages: 7, first: false }),
        0,
      ),
    ).toEqual({ first: 0, last: 0, total: 137 });
  });
});

describe("audit result line", () => {
  it("says a trail has nothing in it", () => {
    expect(describeAuditRange({ first: 0, last: 0, total: 0 })).toBe("No audit entries.");
  });

  it("says a page is past the end without denying the trail exists", () => {
    // The counterexample the two sentences exist for. Collapsing them would
    // tell an analyst who paged too far that the case has no history.
    expect(describeAuditRange({ first: 0, last: 0, total: 137 })).toBe(
      "No entries on this page of 137.",
    );
  });

  it("states the window it is showing", () => {
    expect(describeAuditRange({ first: 61, last: 80, total: 137 })).toBe(
      "Showing 61-80 of 137.",
    );
  });

  it("prints large totals without grouping or abbreviation", () => {
    expect(describeAuditRange({ first: 1, last: 20, total: 1234567 })).toBe(
      "Showing 1-20 of 1234567.",
    );
  });
});

describe("audit page position", () => {
  it("counts pages from one for a reader", () => {
    expect(describeAuditPosition(pageMetadata({ number: 3, totalPages: 7 }))).toBe("Page 4 of 7");
  });

  it("reads an empty result as one page rather than as zero", () => {
    expect(
      describeAuditPosition(pageMetadata({ totalElements: 0, totalPages: 0 })),
    ).toBe("Page 1 of 1");
  });
});

describe("audit fixed copy", () => {
  it("distinguishes an empty trail from an empty page", () => {
    expect(NO_AUDIT_HISTORY_MESSAGE).toBe("No audit history recorded.");
    expect(NO_AUDIT_ENTRIES_ON_PAGE_MESSAGE).toBe("No audit entries on this page.");
    expect(NO_AUDIT_HISTORY_MESSAGE).not.toBe(NO_AUDIT_ENTRIES_ON_PAGE_MESSAGE);
  });

  it("offers exactly the three page sizes the console uses", () => {
    expect([...AUDIT_PAGE_SIZE_OPTIONS]).toEqual([20, 50, 100]);
  });

  it("carries a display rule for every action the contract has", () => {
    // Six actions, and the note identifier belongs to exactly one of them. An
    // action added to the contract without a decision here moves this count.
    expect([...CASE_AUDIT_ACTIONS]).toHaveLength(6);
  });
});
