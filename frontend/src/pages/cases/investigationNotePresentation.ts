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

export const NO_INVESTIGATION_NOTES_MESSAGE = "No investigation notes.";
export const NO_INVESTIGATION_NOTES_ON_PAGE_MESSAGE =
  "No investigation notes on this page.";

export function describeInvestigationNoteWindow(
  page: PageMetadata,
  itemCount: number,
): ResultWindow {
  return describeResultWindow(page.number, page.size, itemCount, page.totalElements);
}

export function describeInvestigationNoteRange(window: ResultWindow): string {
  if (window.total === 0) {
    return "No investigation notes.";
  }
  if (window.first === 0) {
    return `No notes on this page of ${String(window.total)}.`;
  }
  return `Showing ${String(window.first)}-${String(window.last)} of ${String(window.total)}.`;
}

export function describeInvestigationNotePosition(page: PageMetadata): string {
  return `Page ${String(page.number + 1)} of ${String(Math.max(page.totalPages, 1))}`;
}
