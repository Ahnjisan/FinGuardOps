import { describe, expect, it } from "vitest";
import type { PageMetadata } from "../../api/pagination";
import {
  describeInvestigationNotePosition,
  describeInvestigationNoteRange,
  describeInvestigationNoteWindow,
  formatInvestigationNoteInstant,
  NOTE_PAGE_SIZE_OPTIONS,
} from "./investigationNotePresentation";

function page(overrides: Partial<PageMetadata> = {}): PageMetadata {
  return {
    number: 0,
    size: 20,
    totalElements: 1,
    totalPages: 1,
    first: true,
    last: true,
    ...overrides,
  };
}

describe("investigation note presentation", () => {
  it("uses the shared KST conversion and preserves the fixed size choices", () => {
    expect(formatInvestigationNoteInstant("2026-09-02T00:00:00.123456Z")).toBe(
      "2026-09-02 09:00:00",
    );
    expect(NOTE_PAGE_SIZE_OPTIONS).toEqual([20, 50, 100]);
  });

  it("distinguishes a true empty result from an out-of-range page", () => {
    const trulyEmpty = describeInvestigationNoteWindow(
      page({ totalElements: 0, totalPages: 0 }),
      0,
    );
    const outOfRange = describeInvestigationNoteWindow(
      page({ number: 3, totalElements: 41, totalPages: 3, first: false }),
      0,
    );

    expect(describeInvestigationNoteRange(trulyEmpty)).toBe("조사 메모가 없습니다.");
    expect(describeInvestigationNoteRange(outOfRange)).toBe("전체 41건 중 이 페이지에 표시할 메모가 없습니다.");
  });

  it("states one-based item and page positions without correcting metadata", () => {
    const metadata = page({
      number: 1,
      size: 20,
      totalElements: 45,
      totalPages: 3,
      first: false,
      last: false,
    });
    expect(describeInvestigationNoteRange(describeInvestigationNoteWindow(metadata, 20))).toBe(
      "전체 45건 중 21~40건 표시",
    );
    expect(describeInvestigationNotePosition(metadata)).toBe("전체 3페이지 중 2페이지");
  });
});
