import { Link } from "react-router-dom";
import type { CaseListItem, CaseListSort } from "../../api/caseApi";
import {
  CASE_FINAL_DISPOSITION_LABELS,
  CASE_STATUS_LABELS,
  caseStatusTone,
  describeReference,
  formatCaseInstant,
  formatTransactionCount,
  UNASSIGNED_LABEL,
  UNRESOLVED_DISPOSITION_LABEL,
} from "./casePresentation";

export interface CaseTableProps {
  readonly items: readonly CaseListItem[];
  readonly sort: CaseListSort;
  readonly onSortChange: (sort: CaseListSort) => void;
}

/**
 * The case sheet.
 *
 * A row is a record with exactly one way out of it: the case identifier is an
 * anchor to that case's detail address, and nothing else in the row is
 * actionable. The row itself carries no click handler, no `role` and no
 * `tabindex` - a whole row made clickable is an affordance a keyboard cannot
 * reach and a screen reader cannot name, and it would put the only navigation
 * on the sheet somewhere a reader has no way to find it.
 *
 * Nothing here is derived. The seven columns are the seven fields
 * `CaseListItem` carries: no risk score, no priority, no SLA countdown, no
 * detection result and no evidence, because the list response holds none of
 * those and a console that invents one is putting a judgement on screen that no
 * system made.
 *
 * Every identifier and reference is printed in full. Truncating one would make
 * two different cases look identical, so long values wrap inside their cell
 * instead - and apart from the `aria-label` that names the case identifier's
 * link, they are not repeated into a `title`, a `data-` attribute or any other
 * place a value could be read out of the DOM twice.
 */
export function CaseTable({ items, sort, onSortChange }: CaseTableProps) {
  const descending = sort === "lastChangedAt,desc";

  return (
    <div className="sheet sheet--cases">
      {/*
        A scroll container that the keyboard can reach. Below the console's
        design width the sheet scrolls sideways rather than dropping columns:
        an analyst is not shown a partial record without being told.
      */}
      <div
        className="sheet__scroll"
        role="region"
        aria-label="Case results, scrollable"
        tabIndex={0}
      >
        <table>
          <caption className="visually-hidden">
            Fraud cases matching the applied filters, sorted by when each case last
            changed.
          </caption>
          <thead>
            <tr>
              <th scope="col" aria-sort={descending ? "descending" : "ascending"}>
                <button
                  className="sheet__sort"
                  type="button"
                  onClick={() => {
                    onSortChange(descending ? "lastChangedAt,asc" : "lastChangedAt,desc");
                  }}
                >
                  Last changed (KST)
                  <span className="sheet__sort-mark" aria-hidden="true">
                    {descending ? "▼" : "▲"}
                  </span>
                  <span className="visually-hidden">
                    {descending
                      ? ", most recent first. Activate to show least recent first."
                      : ", least recent first. Activate to show most recent first."}
                  </span>
                </button>
              </th>
              <th scope="col">
                <span className="sheet__heading">Case status</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">Final disposition</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">Assignee</span>
              </th>
              <th scope="col" className="is-numeric">
                <span className="sheet__heading">Related transactions</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">Opened (KST)</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">Case ID</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <CaseRow key={item.caseId} item={item} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function CaseRow({ item }: { readonly item: CaseListItem }) {
  const lastChanged = formatCaseInstant(item.lastChangedAt);
  const opened = formatCaseInstant(item.createdAt);
  const tone = caseStatusTone(item.caseStatus);
  const assignee = describeReference(item.assigneeRef);

  return (
    <tr>
      <td className="cell-time">
        {/*
          The machine-readable value is the untouched UTC instant the Backend
          sent; the human-readable one is Seoul wall clock, computed from a fixed
          +09:00 offset rather than from whatever zone this workstation is set to.
        */}
        <time dateTime={item.lastChangedAt}>{lastChanged} KST</time>
      </td>
      <td>
        <span className={`badge badge--${tone}`}>
          <span className="badge__mark" aria-hidden="true" />
          {CASE_STATUS_LABELS[item.caseStatus]}
        </span>
      </td>
      <td>
        {item.finalDisposition === null ? (
          // A word rather than an empty cell, and a word that means "not
          // decided yet" rather than one that could be read as a verdict.
          <span className="cell-ref__absent">{UNRESOLVED_DISPOSITION_LABEL}</span>
        ) : (
          CASE_FINAL_DISPOSITION_LABELS[item.finalDisposition]
        )}
      </td>
      {assignee.absent ? (
        <td className="cell-ref">
          <span className="cell-ref__absent">{UNASSIGNED_LABEL}</span>
        </td>
      ) : (
        <td className={assignee.wrap ? "cell-ref cell-ref--long" : "cell-ref"}>
          {assignee.text}
        </td>
      )}
      <td className="cell-count">{formatTransactionCount(item.relatedTransactionCount)}</td>
      <td className="cell-time">
        {/*
          Printed once, in full. No hidden second copy and no `title`: a
          duplicated instant is a financial value the DOM would carry twice.
        */}
        <time dateTime={item.createdAt}>{opened} KST</time>
      </td>
      <td className="cell-ref cell-ref--id">
        {/*
          An ordinary anchor produced by `Link`: no `state`, no `onClick` of our
          own, and no `preventDefault`, so Ctrl-click, middle click,
          Shift-click and "Open in new tab" all behave exactly as the browser
          intends. The target is the canonical detail route built from the
          identifier the response validator already admitted as a canonical
          lowercase UUID v4 - no query, no fragment, no trailing slash, and
          never a transaction identifier.

          The identifier is the whole of the link's visible text; what the link
          is for is supplied by its `aria-label`, so a reader hears "View case
          details for <identifier>" rather than a bare UUID with no stated
          purpose. That name lives on the attribute rather than in a visually
          hidden prefix inside the anchor: `.visually-hidden` is absolutely
          positioned, and neither `.sheet` nor `.sheet__scroll` is a positioned
          element, so such a prefix would not be clipped by the scroll container
          it appears to sit in - at the console's narrowest width this sheet
          really does scroll sideways, the last column's static position is past
          the viewport, and the escaped box would widen the document instead of
          the sheet. The `aria-label` is the only place besides the text and the
          `href` that carries the identifier: no `title`, no `data-` attribute
          and no hidden mirror adds a fourth.
        */}
        <Link
          className="cell-ref__link"
          to={`/cases/${item.caseId}`}
          aria-label={`View case details for ${item.caseId}`}
        >
          {item.caseId}
        </Link>
      </td>
    </tr>
  );
}
