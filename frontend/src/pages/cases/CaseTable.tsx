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
        aria-label="사건 결과, 가로로 스크롤 가능"
        tabIndex={0}
      >
        <table>
          <caption className="visually-hidden">
            적용된 필터에 맞는 사건을 최종 변경 시각순으로 표시합니다.
          </caption>
          <thead>
            <tr>
              <th scope="col">
                <span className="sheet__heading">사건 ID</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">사건 상태</span>
              </th>
              <th scope="col" aria-sort={descending ? "descending" : "ascending"}>
                <button
                  className="sheet__sort"
                  type="button"
                  onClick={() => {
                    onSortChange(descending ? "lastChangedAt,asc" : "lastChangedAt,desc");
                  }}
                >
                  최종 변경(KST)
                  <span className="sheet__sort-mark" aria-hidden="true">
                    {descending ? "▼" : "▲"}
                  </span>
                  <span className="visually-hidden">
                    {descending
                      ? ", 최신순. 오래된순으로 바꾸려면 누르세요."
                      : ", 오래된순. 최신순으로 바꾸려면 누르세요."}
                  </span>
                </button>
              </th>
              <th scope="col">
                <span className="sheet__heading">최종 판정</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">담당자</span>
              </th>
              <th scope="col" className="is-numeric">
                <span className="sheet__heading">연관 거래</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">생성(KST)</span>
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
      <td className="cell-ref cell-ref--id">
        <Link
          className="cell-ref__link"
          to={`/cases/${item.caseId}`}
          aria-label={`사건 ${item.caseId} 상세 보기`}
        >
          {item.caseId}
        </Link>
      </td>
      <td>
        <span className={`badge badge--${tone}`}>
          <span className="badge__mark" aria-hidden="true" />
          {CASE_STATUS_LABELS[item.caseStatus]}
        </span>
      </td>
      <td className="cell-time">
        {/*
          The machine-readable value is the untouched UTC instant the Backend
          sent; the human-readable one is Seoul wall clock, computed from a fixed
          +09:00 offset rather than from whatever zone this workstation is set to.
        */}
        <time dateTime={item.lastChangedAt}>{lastChanged} KST</time>
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
    </tr>
  );
}
