import type { PageMetadata } from "../../api/pagination";
import { PAGE_SIZE_OPTIONS } from "./casePresentation";

export interface CasePaginationProps {
  readonly page: PageMetadata;
  readonly onPageChange: (pageNumber: number) => void;
  readonly onPageSizeChange: (size: number) => void;
}

/**
 * Page and page-size controls.
 *
 * Both are the analyst asking for a different query, so both go through the
 * same committed-query path a filter does. Nothing here loads more results into
 * the current view, and nothing paginates on its own.
 *
 * Every number shown comes from the page envelope the API layer already checked
 * for internal consistency, so this states the position rather than recomputing
 * it from the rows on screen.
 */
export function CasePagination({
  page,
  onPageChange,
  onPageSizeChange,
}: CasePaginationProps) {
  // `totalPages` is 0 for an empty result, which still reads as one page to a
  // person looking at the screen.
  const totalPages = Math.max(page.totalPages, 1);

  return (
    <nav className="pager" aria-label="사건 페이지">
      <button
        className="button"
        type="button"
        disabled={page.first}
        onClick={() => {
          onPageChange(page.number - 1);
        }}
      >
        이전 페이지
      </button>
      <button
        className="button"
        type="button"
        disabled={page.last}
        onClick={() => {
          onPageChange(page.number + 1);
        }}
      >
        다음 페이지
      </button>
      <p className="pager__position">
        페이지 {page.number + 1} / 전체 {totalPages}
      </p>
      <div className="pager__size">
        <label htmlFor="case-pager-size">페이지당 행 수</label>
        <select
          id="case-pager-size"
          value={page.size}
          onChange={(event) => {
            onPageSizeChange(Number(event.target.value));
          }}
        >
          {PAGE_SIZE_OPTIONS.map((size) => (
            <option key={size} value={size}>
              {size}
            </option>
          ))}
        </select>
      </div>
    </nav>
  );
}
