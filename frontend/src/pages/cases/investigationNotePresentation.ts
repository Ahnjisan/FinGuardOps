import type { PageMetadata } from "../../api/pagination";
import {
  describeResultWindow,
  formatCaseInstant,
  PAGE_SIZE_OPTIONS,
  type ResultWindow,
} from "./casePresentation";

export { PAGE_SIZE_OPTIONS as NOTE_PAGE_SIZE_OPTIONS, type ResultWindow };

/** Seoul wall clock for a validated Backend UTC instant. */
export const formatInvestigationNoteInstant = formatCaseInstant;

export const NO_INVESTIGATION_NOTES_MESSAGE = "조사 메모가 없습니다.";
export const NO_INVESTIGATION_NOTES_ON_PAGE_MESSAGE =
  "이 페이지에 조사 메모가 없습니다.";

export function describeInvestigationNoteWindow(
  page: PageMetadata,
  itemCount: number,
): ResultWindow {
  return describeResultWindow(page.number, page.size, itemCount, page.totalElements);
}

export function describeInvestigationNoteRange(window: ResultWindow): string {
  if (window.total === 0) {
    return "조사 메모가 없습니다.";
  }
  if (window.first === 0) {
    return `전체 ${String(window.total)}건 중 이 페이지에 표시할 메모가 없습니다.`;
  }
  return `전체 ${String(window.total)}건 중 ${String(window.first)}~${String(window.last)}건 표시`;
}

export function describeInvestigationNotePosition(page: PageMetadata): string {
  return `전체 ${String(Math.max(page.totalPages, 1))}페이지 중 ${String(page.number + 1)}페이지`;
}
