import { useEffect, useRef } from "react";
import { Link, useLocation } from "react-router-dom";
import { isCanonicalUuidV4 } from "../api/backendEndpoints";
import type { TransactionDetail } from "../api/transactionApi";
import {
  useTransactionDetail,
  type TransactionDetailErrorKind,
  type TransactionDetailState,
} from "../api/useTransactionDetail";
import {
  ABSENT_REFERENCE_LABEL,
  describeReference,
  formatAmountDigits,
  formatKstDateTime,
  PROCESSING_STATUS_LABELS,
  processingStatusTone,
  TRANSACTION_CHANNEL_LABELS,
  TRANSACTION_TYPE_LABELS,
} from "./transactions/transactionPresentation";

/**
 * One transaction, read only.
 *
 * Everything on this screen comes from the `TransactionDetail` contract and
 * nothing else. There is no risk score, no risk level, no fraud probability, no
 * detection result, no evidence and no case link, because the endpoint carries
 * none of them and a console that infers one puts a number on screen that no
 * system ever computed. `processingStatus` is where the transaction has reached
 * in the pipeline; it is not a verdict about the transaction.
 *
 * There is also no action. No edit, no reprocess, no case creation, no
 * clipboard copy: the analyst reads the record and navigates away.
 */

/**
 * One path segment after `/transactions/`, taken from the location the browser
 * gave this application.
 *
 * `[^/]+` is a *shape* check, not the decision. The decision is
 * `isCanonicalUuidV4` below, applied to that segment exactly as it reaches
 * here - never decoded, trimmed or case folded: a surviving encoded slash, an
 * encoded backslash, a duplicate separator, whitespace, a trailing slash, an
 * extra segment, an uppercase digit, a non-v4 version nibble and an invalid
 * variant nibble all fail it, and so does anything carrying a `%` at all.
 */
const DETAIL_PATH = /^\/transactions\/([^/]+)$/;

interface TransactionRouteLocation {
  readonly pathname: string;
  readonly search: string;
  readonly hash: string;
}

/**
 * The transaction this address names, or `null` when it names none.
 *
 * The input boundary here is the location the browser's own URL parser handed
 * to this application - `location.pathname`, `location.search` and
 * `location.hash` - and nothing earlier than that. Whatever the address bar,
 * a link or a redirect originally carried has already been parsed and
 * canonicalized by the browser before any of this code runs; a representation
 * the browser resolved away is not recoverable here and is not claimed to be.
 * What this function decides is whether the location that actually arrived is
 * the canonical detail route.
 *
 * It reads `location.pathname` rather than `useParams()` because React Router
 * hands a route parameter over already percent-decoded: a `%32` that the
 * browser preserved in the path produces a parameter that *is* a canonical
 * UUID while the location is not. Validating the decoded parameter would admit
 * that location; validating the path segment as it arrived refuses it, because
 * that segment still carries its `%32`.
 *
 * A query string or a fragment is refused for the same reason: this route has
 * neither, so a location carrying one is not this route. The application's own
 * fragment - the skip link's `#main-content` - is a same-document navigation
 * the router never observes, so it cannot reach here.
 */
function readCanonicalTransactionId(
  location: TransactionRouteLocation,
): string | null {
  if (location.search !== "" || location.hash !== "") {
    return null;
  }
  const match = DETAIL_PATH.exec(location.pathname);
  if (match === null) {
    return null;
  }
  const candidate = match[1];
  return isCanonicalUuidV4(candidate) ? candidate : null;
}

/**
 * Fixed messages. Each says what happened and what the analyst can do about it,
 * and none of them carries a status code, a trace id, a response body, a token
 * or the address that was asked for.
 */
const ERROR_COPY: Readonly<
  Record<TransactionDetailErrorKind, { readonly title: string; readonly body: string }>
> = Object.freeze({
  timeout: {
    title: "The transaction took too long to load",
    body: "The backend did not answer in time. Try loading it again.",
  },
  network: {
    title: "The backend could not be reached",
    body: "Check the connection to the FinGuardOps backend, then try again.",
  },
  "invalid-response": {
    title: "The transaction could not be read",
    body:
      "The backend returned data this console will not display. Nothing is shown rather than " +
      "part of a record. Try again, and report it if it continues.",
  },
  "not-found": {
    title: "Transaction not found",
    body: "No transaction with this identifier is available. Return to the transaction list.",
  },
  "access-denied": {
    title: "Access denied",
    body: "You do not have permission to view this transaction.",
  },
  "session-lost": {
    title: "Your session ended",
    body: "Sign in again to continue.",
  },
  "request-rejected": {
    title: "The transaction was not requested",
    body: "This address is not a transaction request this console will send.",
  },
  unknown: {
    title: "The transaction could not be loaded",
    body: "The backend could not return this transaction. Try again.",
  },
});

/** Errors where sending the identical request again could plausibly succeed. */
const RETRYABLE: ReadonlySet<TransactionDetailErrorKind> = new Set<TransactionDetailErrorKind>([
  "timeout",
  "network",
  "invalid-response",
  "unknown",
]);

const INVALID_ROUTE_COPY = Object.freeze({
  title: "This is not a transaction address",
  body:
    "The address does not name a transaction this console can open. Open a transaction from " +
    "the transaction list instead.",
});

export function TransactionDetailPage() {
  const location = useLocation();
  const transactionId = readCanonicalTransactionId(location);
  const { state, retry } = useTransactionDetail(transactionId);

  const errorRef = useRef<HTMLDivElement | null>(null);

  // Focus moves to the summary once per error, never on every render. A
  // malformed address is one fixed refusal; a Backend failure is identified by
  // the kind of failure it was, so a retry that fails the same way again does
  // not steal focus a second time from someone who is already reading it.
  const errorSignature =
    transactionId === null
      ? "route"
      : state.status === "error"
        ? `request:${state.error}`
        : "";
  const lastFocusedSignature = useRef("");
  useEffect(() => {
    if (errorSignature === "" || errorSignature === lastFocusedSignature.current) {
      lastFocusedSignature.current = errorSignature;
      return;
    }
    lastFocusedSignature.current = errorSignature;
    errorRef.current?.focus();
  }, [errorSignature]);

  return (
    <section className="detail" aria-labelledby="transaction-detail-heading">
      <div className="page-head">
        <p className="detail__back">
          <Link to="/transactions">Back to transactions</Link>
        </p>
        <h2 id="transaction-detail-heading">
          Transaction
          {transactionId !== null && (
            <>
              {" "}
              <span className="detail__id">{transactionId}</span>
            </>
          )}
        </h2>
        <p>
          A read-only record from the transaction ledger. Times are Korea Standard Time
          (UTC+09:00).
        </p>
      </div>

      <div className="result-line" role="status" aria-live="polite">
        <DetailSummary invalidRoute={transactionId === null} state={state} />
      </div>

      {transactionId === null && (
        <div className="notice notice--error" role="alert" tabIndex={-1} ref={errorRef}>
          <p className="notice__title">{INVALID_ROUTE_COPY.title}</p>
          <p className="notice__body">{INVALID_ROUTE_COPY.body}</p>
        </div>
      )}

      {transactionId !== null && state.status === "error" && (
        <div className="notice notice--error" role="alert" tabIndex={-1} ref={errorRef}>
          <p className="notice__title">{ERROR_COPY[state.error].title}</p>
          <p className="notice__body">{ERROR_COPY[state.error].body}</p>
          {RETRYABLE.has(state.error) && (
            <button className="button" type="button" onClick={retry}>
              Try again
            </button>
          )}
        </div>
      )}

      {transactionId !== null && state.status === "loading" && (
        <p className="loading-panel">Loading transaction...</p>
      )}

      {transactionId !== null && state.status === "success" && (
        <TransactionRecord transaction={state.data} />
      )}
    </section>
  );
}

function DetailSummary({
  invalidRoute,
  state,
}: {
  readonly invalidRoute: boolean;
  readonly state: TransactionDetailState;
}) {
  if (invalidRoute) {
    return <span>{INVALID_ROUTE_COPY.title}.</span>;
  }
  if (state.status === "loading") {
    return <span>Loading transaction</span>;
  }
  if (state.status === "error") {
    return <span>No record shown. {ERROR_COPY[state.error].title}.</span>;
  }
  if (state.status === "success") {
    return <span>Showing the full transaction record.</span>;
  }
  return null;
}

/**
 * The record itself, as three sections of definitions.
 *
 * A definition list rather than a grid of cards: every line here is a name and
 * one value, and wrapping each pair in its own bordered surface would add
 * decoration without adding a single fact.
 */
function TransactionRecord({ transaction }: { readonly transaction: TransactionDetail }) {
  const tone = processingStatusTone(transaction.processingStatus);

  return (
    <div className="detail__record">
      <section className="panel" aria-labelledby="transaction-summary-heading">
        <h3 id="transaction-summary-heading">Transaction</h3>
        <dl className="facts">
          <dt>Transaction ID</dt>
          <dd className="facts__ref">{transaction.transactionId}</dd>

          <dt>Type</dt>
          <dd>{TRANSACTION_TYPE_LABELS[transaction.transactionType]}</dd>

          <dt>Channel</dt>
          <dd>{TRANSACTION_CHANNEL_LABELS[transaction.channel]}</dd>

          <dt>Processing status</dt>
          <dd>
            {/*
              Shape, word and colour together, exactly as the sheet renders it.
              This is where the transaction has reached in processing - not a
              risk level, and not a decision about the transaction.
            */}
            <span className={`badge badge--${tone}`}>
              <span className="badge__mark" aria-hidden="true" />
              {PROCESSING_STATUS_LABELS[transaction.processingStatus]}
            </span>
          </dd>

          <dt>Amount</dt>
          <dd className="facts__amount">
            {/*
              Grouped from the decimal string with BigInt. The value never
              becomes a `number`, so all fifteen contract digits survive.
            */}
            <b>{formatAmountDigits(transaction.amount)}</b>
            <span>{transaction.currencyCode}</span>
          </dd>

          <dt>Occurred</dt>
          <dd>
            <KstInstant utcInstant={transaction.occurredAt} />
          </dd>
        </dl>
      </section>

      <section className="panel" aria-labelledby="transaction-parties-heading">
        <h3 id="transaction-parties-heading">Customer, accounts and device</h3>
        <dl className="facts">
          <dt>Customer reference</dt>
          <dd className="facts__ref">{transaction.externalCustomerRef}</dd>

          <dt>From account</dt>
          <dd className="facts__ref">{transaction.senderAccountRef}</dd>

          <dt>To account</dt>
          <dd className="facts__ref">
            <Reference value={transaction.recipientAccountRef} />
          </dd>

          <dt>Device</dt>
          <dd className="facts__ref">
            <Reference value={transaction.deviceRef} />
          </dd>
        </dl>
      </section>

      <section className="panel" aria-labelledby="transaction-record-heading">
        <h3 id="transaction-record-heading">Ledger record</h3>
        <dl className="facts">
          <dt>Recorded</dt>
          <dd>
            <KstInstant utcInstant={transaction.createdAt} />
          </dd>

          <dt>Last updated</dt>
          <dd>
            <KstInstant utcInstant={transaction.updatedAt} />
          </dd>
        </dl>
      </section>
    </div>
  );
}

/**
 * Seoul wall clock for the reader, the Backend's untouched UTC value for the
 * machine. The conversion is a fixed +09:00 applied to the instant, so the
 * workstation's own time zone takes no part in it.
 */
function KstInstant({ utcInstant }: { readonly utcInstant: string }) {
  const shown = formatKstDateTime(utcInstant);
  if (shown === null) {
    // Unreachable through the validated contract, and still not a place to
    // print the raw value: a time that cannot be read is reported as one.
    return <span className="facts__absent">Not a readable time</span>;
  }
  return <time dateTime={utcInstant}>{shown} KST</time>;
}

/**
 * A stored reference, printed in full and exactly once.
 *
 * Nothing is trimmed, case folded or shortened, and the value is not repeated
 * into a `title`, an `aria-label`, a hidden element or a `data-` attribute: an
 * analyst comparing it against another system needs the value itself, and the
 * DOM should carry it once rather than three times.
 */
function Reference({ value }: { readonly value: string | null }) {
  const reference = describeReference(value);
  if (reference.absent) {
    return <span className="facts__absent">{ABSENT_REFERENCE_LABEL}</span>;
  }
  return <>{reference.text}</>;
}
