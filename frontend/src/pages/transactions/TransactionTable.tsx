import type {
  TransactionListItem,
  TransactionListSort,
} from "../../api/transactionApi";
import {
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
 * Rows are records, not links. There is no detail route in this scope, so a row
 * is not clickable, carries no hidden identifier and offers no business action:
 * everything the analyst can read is visible text in a cell.
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
    <div className="sheet">
      {/*
        A scroll container that the keyboard can reach. Below the console's
        design width the sheet scrolls sideways rather than dropping columns:
        an analyst is not shown a partial record without being told.
      */}
      <div
        className="sheet__scroll"
        role="region"
        aria-label="Transaction results, scrollable"
        tabIndex={0}
      >
        <table>
          <caption className="visually-hidden">
            Transactions matching the applied filters, sorted by occurrence time.
          </caption>
          <thead>
            <tr>
              <th scope="col" aria-sort={descending ? "descending" : "ascending"}>
                <button
                  className="sheet__sort"
                  type="button"
                  onClick={() => {
                    onSortChange(descending ? "occurredAt,asc" : "occurredAt,desc");
                  }}
                >
                  Occurred (KST)
                  <span className="sheet__sort-mark" aria-hidden="true">
                    {descending ? "▼" : "▲"}
                  </span>
                  <span className="visually-hidden">
                    {descending
                      ? ", newest first. Activate to show oldest first."
                      : ", oldest first. Activate to show newest first."}
                  </span>
                </button>
              </th>
              <th scope="col">
                <span className="sheet__heading">Type</span>
              </th>
              <th scope="col" className="is-numeric">
                <span className="sheet__heading">Amount</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">Processing status</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">Transaction ID</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">Customer</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">From account</span>
              </th>
              <th scope="col">
                <span className="sheet__heading">To account</span>
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
      <td className="cell-time">
        {/*
          The machine-readable value is the untouched UTC instant the Backend
          sent; the human-readable one is Seoul wall clock, computed from a fixed
          +09:00 offset rather than from whatever zone this workstation is set to.
        */}
        <time dateTime={item.occurredAt}>{occurred} KST</time>
        <small>
          Recorded <time dateTime={item.createdAt}>{recorded}</time>
        </small>
      </td>
      <td>{TRANSACTION_TYPE_LABELS[item.transactionType]}</td>
      <td className="cell-amount">
        <b>{formatAmountDigits(item.amount)}</b>
        <span>{item.currencyCode}</span>
      </td>
      <td>
        <span className={`badge badge--${tone}`}>
          <span className="badge__mark" aria-hidden="true" />
          {PROCESSING_STATUS_LABELS[item.processingStatus]}
        </span>
      </td>
      <td className="cell-ref cell-ref--id">{item.transactionId}</td>
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
        <span className="cell-ref__absent">None recorded</span>
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
