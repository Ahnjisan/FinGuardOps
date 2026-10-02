# FDS console visual baseline (Issue #320)

The console is a light financial work surface. Navigation, filters, results, and record links should read in that order. This pass covers the shell and case queue; later work can rearrange case detail and transaction screens.

- **Color:** near-white canvas `#f7f8fc`, white surfaces, navy text `#172340`, and restrained violet `#5548d9` for actions and links. The Home introduction alone uses a deep navy/violet surface. Status uses restrained tinted backgrounds, a distinct mark, and a written label. Color alone never conveys a decision.
- **Type:** the operating system's UI sans for prose and controls, with no downloaded font or runtime CDN request; system mono only for identifiers and numeric records. Page headings are 38–48px, body 16px, table 14px. Keep line height and contrast comfortable for long review sessions.
- **Spacing and surfaces:** a 4px scale; 28–44px between major page blocks, 16–28px within panels. Controls have a 42px minimum height, cards use 18px corners, and shadows stay quiet around business data.
- **Table density:** readable rows with 14px vertical padding. The case link and written state lead each row, followed by recency. Preserve every column and its header. Long references wrap in cells; narrow viewports scroll the table within a keyboard-reachable, labelled region.
- **Filters:** status and references remain in view. Optional time ranges use a native disclosure so the case queue follows the essential search controls without hiding the time filters from keyboard users.
- **States:** show loading, empty, error, current navigation, sort order, and applied filter state in text. Keep visible focus indicators, skip link, semantic table headers, and alert focus behavior.
- **Language and icons:** use concise Korean for navigation, controls, states, and accessible names. Preserve IDs, user content, Backend codes, and audit identifiers as recorded. A small local SVG set supports navigation and primary actions; visible text remains beside every icon.
- **Metrics:** the current list APIs return `totalElements` for the active query and Health returns service status. Neither is a period series or an unfiltered dashboard measure. Do not present a page of rows as an overall count or draw a trend without a contracted aggregate endpoint.
- **Responsive:** desktop keeps a persistent side navigation. Below 1024px it becomes a wrapping top navigation. Filters stack progressively; the document itself stays within the viewport at 1440, 1280, 1024, and 390px. On phones, case ID and state are visible before horizontal table scrolling; all remaining columns stay accessible inside the labelled table region.

## Case detail (Issue #320, second PR)

- The page heading names the case ID. A compact summary immediately below it repeats only the actual case status, final disposition and assignee. Nullable values say `미결정` or `미배정`; status retains its written label and mark.
- At 1440px and 1280px, case record and authorized case actions occupy two columns. At 1024px and 390px they stack. The document and keyboard order remain record, actions, investigation notes, audit history. A viewer with no case actions gets the full record width.
- The record keeps every contracted field. Its four date fields are headed `사건 시각`; they do not represent a behavior timeline. The related transaction count stays a count, not a link to an unimplemented list.
- Notes and audit history each use a full-width panel with their own loading, empty, error, retry and paging states. Long IDs, note text and audit summaries wrap in place without truncation or whole-document horizontal scrolling.
- Existing role capabilities, write eligibility, mutation reconciliation, focus behavior and accessible control names remain authoritative. No score, AI report, chart or invented transaction detail is displayed.

## Transaction list (Issue #320, next PR)

- Keep the existing eight transaction columns and values. Place transaction ID and written processing status first so both are visible before scrolling at 390px. The remaining columns stay available in a labelled, keyboard-reachable horizontal scroll region.
- Put the visible search heading and query-specific result count around the filters, table and pager in reading order. The count is `totalElements` for the active query, never an unfiltered metric.
- Use a native period disclosure. Its summary shows the applied KST start/end values even when closed; draft changes do not claim to be applied. Enter and Space operate the disclosure, while Apply still converts KST to UTC and resets the page.
- Preserve exact identifiers, references, amount and time. Processing status describes the pipeline, not risk. The list has no case ID, score, evidence, trend or period aggregate.
