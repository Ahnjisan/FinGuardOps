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
 * A row is a record, and in this screen it is *only* a record. There is no
 * detail route yet, so there is no anchor, no click handler, no `role`, no
 * `tabindex` and no drawer: the case identifier is printed as text. That is the
 * deliberate shape of a list-only screen. A row wired to open something that
 * does not exist, or a whole row made clickable so that a future screen has
 * somewhere to hang, would be an affordance this Issue cannot honour.
 *
 * Nothing here is derived. The seven columns are the seven fields
 * `CaseListItem` carries: no risk score, no priority, no SLA countdown, no
 * detection result and no evidence, because the list response holds none of
 * those and a console that invents one is putting a judgement on screen that no
 * system made.
 *
 * Every identifier and reference is printed in full. Truncating one would make
 * two different cases look identical, so long values wrap inside their cell
 * instead - and they are not repeated into a `title`, a `data-` attribute or
 * any other place a value could be read out of the DOM twice.
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
      <td className="cell-ref cell-ref--id">{item.caseId}</td>
    </tr>
  );
}
