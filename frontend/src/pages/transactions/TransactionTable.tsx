import { Link } from "react-router-dom";
import type {
  TransactionListItem,
  TransactionListSort,
} from "../../api/transactionApi";
import {
  ABSENT_REFERENCE_LABEL,
  describeReference,
  formatAmountDigits,
  formatKstDateTime,
  formatKstDateTimeShort,
  PROCESSING_STATUS_LABELS,
  processingStatusTone,
  TRANSACTION_TYPE_LABELS,
} from "./transactionPresentation";

export interface TransactionTableProps {
  readonly items: readonly TransactionListItem[];
  readonly sort: TransactionListSort;
  readonly onSortChange: (sort: TransactionListSort) => void;
}

/**
 * The transaction sheet.
 *
 * A row is a record, not a control. It has no click handler, no `role`, no
 * `tabindex` and no hidden identifier: the one way into the detail screen is
 * the anchor in the Transaction ID cell. That is deliberate. A clickable row
 * swallows text selection, cannot be reached from the keyboard without being
 * given a button role it should not have, and has no href for a middle click or
 * a Ctrl-click to open - so the sheet offers a real link instead and lets the
 * browser do what it already does well.
 *
 * The link carries the transaction id and nothing else. No amount, no customer,
 * no account and no device reference reaches the destination through router
 * state, a query string or a `data-` attribute, so the address bar and the
 * history entry hold an opaque identifier and no financial value.
 *
 * Every reference is printed in full. Truncating a financial reference to fit a
 * column would make two different accounts look identical, so long values wrap
 * inside their cell instead - and they are not repeated into a `title`, a
 * `data-` attribute or any other place a value could be read out of the DOM
 * twice.
 */
export function TransactionTable({ items, sort, onSortChange }: TransactionTableProps) {
  const descending = sort === "occurredAt,desc";

  return (
    <div className="sheet sheet--transactions">
      {/*
        A scroll container that the keyboard can reach. Below the console's
        design width the sheet scrolls sideways rather than dropping columns:
        an analyst is not shown a partial record without being told.
      */}
      <div
        className="sheet__scroll"
        role="region"
        aria-label="거래 결과, 가로로 스크롤 가능"
        tabIndex={0}
      >
        <table>
          <caption className="visually-hidden">
            적용된 필터에 맞는 거래를 발생 시각순으로 표시합니다.
          </caption>
          <thead>
            <tr>
              <th scope="col"><span className="sheet__heading">거래 ID</span></th>
              <th scope="col"><span className="sheet__heading">처리 상태</span></th>
              <th scope="col" aria-sort={descending ? "descending" : "ascending"}>
                <button
                  className="sheet__sort"
                  type="button"
                  onClick={() => {
                    onSortChange(descending ? "occurredAt,asc" : "occurredAt,desc");
                  }}
                >
                  발생(KST)
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
                <span className="sheet__heading">유형</span>
              </th>
              <th scope="col" className="is-numeric">
                <span className="sheet__heading">금액</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">고객</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">출금 계좌</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">입금 계좌</span>
              </th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <TransactionRow key={item.transactionId} item={item} />
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function TransactionRow({ item }: { readonly item: TransactionListItem }) {
  const occurred = formatKstDateTime(item.occurredAt);
  const recorded = formatKstDateTimeShort(item.createdAt);
  const tone = processingStatusTone(item.processingStatus);

  return (
    <tr>
      <td className="cell-ref cell-ref--id">
        <Link className="cell-ref__link" to={`/transactions/${item.transactionId}`}>
          <span className="visually-hidden">거래 상세 보기</span>{" "}
          {item.transactionId}
        </Link>
      </td>
      <td>
        <span className={`badge badge--${tone}`}>
          <span className="badge__mark" aria-hidden="true" />
          {PROCESSING_STATUS_LABELS[item.processingStatus]}
        </span>
      </td>
      <td className="cell-time">
        {/*
          The machine-readable value is the untouched UTC instant the Backend
          sent; the human-readable one is Seoul wall clock, computed from a fixed
          +09:00 offset rather than from whatever zone this workstation is set to.
        */}
        <time dateTime={item.occurredAt}>{occurred} KST</time>
        <small>
          기록 <time dateTime={item.createdAt}>{recorded}</time>
        </small>
      </td>
      <td>{TRANSACTION_TYPE_LABELS[item.transactionType]}</td>
      <td className="cell-amount">
        <b>{formatAmountDigits(item.amount)}</b>
        <span>{item.currencyCode}</span>
      </td>
      <ReferenceCell value={item.externalCustomerRef} />
      <ReferenceCell value={item.senderAccountRef} />
      <ReferenceCell value={item.recipientAccountRef} />
    </tr>
  );
}

function ReferenceCell({ value }: { readonly value: string | null }) {
  const reference = describeReference(value);
  if (reference.absent) {
    // A word rather than an empty cell: a blank would read as a rendering fault
    // instead of as a transaction that genuinely has no counterparty account.
    return (
      <td className="cell-ref">
        <span className="cell-ref__absent">{ABSENT_REFERENCE_LABEL}</span>
      </td>
    );
  }
  // A short reference stays on one line; a long one is allowed to break inside
  // the cell rather than widen the sheet. Nothing is shortened either way.
  return (
    <td className={reference.wrap ? "cell-ref cell-ref--long" : "cell-ref"}>
      {reference.text}
    </td>
  );
}
