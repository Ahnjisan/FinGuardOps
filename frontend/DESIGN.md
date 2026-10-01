# FDS console visual baseline (Issue #320, first pass)

The console is a light financial work surface. Navigation, filters, results, and record links should read in that order. This pass covers the shell and case queue; later work can rearrange case detail and transaction screens.

- **Color:** off-white canvas `#f6f8fb`, white surfaces, deep slate text `#17243b`, blue `#2457b5` for actions and links. Status uses restrained tinted backgrounds, a distinct mark, and a written label. Color alone never conveys a decision.
- **Type:** system sans for prose and controls; system mono only for identifiers and numeric records. Page heading 28–30px, section heading 16–18px, body 14–15px, table 13–14px. Keep line height and contrast comfortable for long review sessions.
- **Spacing:** a 4px scale; 24–32px between major page blocks, 16–24px within panels, 8–12px between labels and controls. One clear surface per filter or result region.
- **Table density:** compact but readable rows with 12–14px vertical padding. The case link leads each row, followed by recency and investigation state. Preserve every column and its header. Long references wrap in cells; narrow viewports scroll the table within a keyboard-reachable, labelled region.
- **States:** show loading, empty, error, current navigation, sort order, and applied filter state in text. Keep visible focus indicators, skip link, semantic table headers, and alert focus behavior.
- **Responsive:** desktop keeps a persistent side navigation. Below 1024px it becomes a wrapping top navigation. Filters stack progressively; the document itself stays within the viewport at 1440, 1280, 1024, and 390px. Table overflow is explicit and contained.
