import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type {
  TransactionListQuery,
  TransactionListSort,
  TransactionProcessingStatus,
  TransactionType,
} from "../api/transactionApi";
import {
  TRANSACTION_PROCESSING_STATUSES,
  TRANSACTION_TYPES,
} from "../api/transactionApi";
import {
  useTransactionList,
  type TransactionListErrorKind,
  type TransactionListState,
} from "../api/useTransactionList";
import { TransactionFilters } from "./transactions/TransactionFilters";
import { TransactionPagination } from "./transactions/TransactionPagination";
import { TransactionTable } from "./transactions/TransactionTable";
import {
  describeResultWindow,
  EMPTY_FILTER_DRAFT,
  isReversedUtcRange,
  kstInputToUtcInstant,
  type TransactionFilterDraft,
} from "./transactions/transactionPresentation";

/** The query this screen opens with, and the one Reset restores. */
export const DEFAULT_PAGE = 0;
export const DEFAULT_PAGE_SIZE = 20;
export const DEFAULT_SORT: TransactionListSort = "occurredAt,desc";

/**
 * A committed filter set: what the last Apply actually asked for.
 *
 * Deliberately a different shape from the draft. The draft is Seoul wall-clock
 * text an analyst is still typing; this is UTC instants and exact enum members
 * that the query contract will accept. Keeping the two apart is what stops a
 * half-typed date from being sent as a filter.
 */
interface CommittedFilters {
  readonly occurredAtFrom?: string;
  readonly occurredAtTo?: string;
  readonly transactionType?: TransactionType;
  readonly processingStatus?: TransactionProcessingStatus;
  readonly externalCustomerRef?: string;
  readonly accountRef?: string;
}

const NO_FILTERS: CommittedFilters = Object.freeze({});

/**
 * The refusal the last Apply produced, if it produced one.
 *
 * `generation` counts invalid submissions and nothing else. It is what focus
 * management keys on, because the alternative - a signature built from the
 * refusal itself - cannot tell two different single-problem refusals apart:
 * fixing a reversed time range and then submitting a blank reference is two
 * separate refusals that both carry one problem, and an analyst who is taken
 * to the first explanation has to be taken to the second one as well.
 *
 * It carries no problem text and no filter value, so nothing an analyst typed
 * can reach a dependency array, a DOM attribute or a message through it.
 */
interface FilterRefusal {
  readonly problems: readonly string[];
  readonly generation: number;
}

const NO_REFUSAL: FilterRefusal = Object.freeze({ problems: [], generation: 0 });

/**
 * Fixed messages. Each says what happened and what the analyst can do about it,
 * and none of them carries a trace id, a status line, a response body, a token
 * or a filter value.
 */
const ERROR_COPY: Readonly<
  Record<TransactionListErrorKind, { readonly title: string; readonly body: string }>
> = Object.freeze({
  timeout: {
    title: "The search took too long",
    body: "The backend did not answer in time. Try the search again.",
  },
  network: {
    title: "The backend could not be reached",
    body: "Check the connection to the FinGuardOps backend, then try again.",
  },
  "invalid-response": {
    title: "The results could not be read",
    body:
      "The backend returned data this console will not display. Nothing is shown rather than " +
      "a partial list. Try again, and report it if it continues.",
  },
  "access-denied": {
    title: "Access denied",
    body: "You do not have permission to read transactions.",
  },
  "session-lost": {
    title: "Your session ended",
    body: "Sign in again to continue.",
  },
  "request-rejected": {
    title: "The search was not sent",
    body: "These filters are not a search this console will send. Adjust them and try again.",
  },
  unknown: {
    title: "The search failed",
    body: "The backend could not complete the search. Try again.",
  },
});

/** Errors where sending the identical request again could plausibly succeed. */
const RETRYABLE: ReadonlySet<TransactionListErrorKind> = new Set<TransactionListErrorKind>([
  "timeout",
  "network",
  "invalid-response",
  "unknown",
]);

function isTransactionType(value: string): value is TransactionType {
  return (TRANSACTION_TYPES as readonly string[]).includes(value);
}

function isProcessingStatus(value: string): value is TransactionProcessingStatus {
  return (TRANSACTION_PROCESSING_STATUSES as readonly string[]).includes(value);
}

interface CommitOutcome {
  readonly filters: CommittedFilters | null;
  readonly problems: readonly string[];
}

/**
 * Turns a draft into committed filters, or into the reasons it cannot be one.
 *
 * Reference values are passed through byte for byte. No trim, no case folding,
 * no collapsing of inner spaces: `TransactionQueryValidator` matches a
 * reference exactly, so `" acct "` is a search for a stored reference that
 * really does carry those spaces, and normalising it here would quietly search
 * for something else.
 */
function commitDraft(draft: TransactionFilterDraft): CommitOutcome {
  const problems: string[] = [];
  const filters: {
    occurredAtFrom?: string;
    occurredAtTo?: string;
    transactionType?: TransactionType;
    processingStatus?: TransactionProcessingStatus;
    externalCustomerRef?: string;
    accountRef?: string;
  } = {};

  if (draft.occurredAtFrom !== "") {
    const from = kstInputToUtcInstant(draft.occurredAtFrom);
    if (from === null) {
      problems.push("Enter the start of the time range as a real date and time.");
    } else {
      filters.occurredAtFrom = from;
    }
  }
  if (draft.occurredAtTo !== "") {
    const to = kstInputToUtcInstant(draft.occurredAtTo);
    if (to === null) {
      problems.push("Enter the end of the time range as a real date and time.");
    } else {
      filters.occurredAtTo = to;
    }
  }
  if (isReversedUtcRange(filters.occurredAtFrom ?? null, filters.occurredAtTo ?? null)) {
    problems.push("The start of the time range must not be later than the end.");
  }

  if (draft.transactionType !== "") {
    if (isTransactionType(draft.transactionType)) {
      filters.transactionType = draft.transactionType;
    } else {
      problems.push("Choose a transaction type from the list.");
    }
  }
  if (draft.processingStatus !== "") {
    if (isProcessingStatus(draft.processingStatus)) {
      filters.processingStatus = draft.processingStatus;
    } else {
      problems.push("Choose a processing status from the list.");
    }
  }

  // Backend refuses a blank reference, so a field holding only spaces is a
  // filter that would be answered with a 422. It is reported here instead.
  if (draft.externalCustomerRef !== "") {
    if (draft.externalCustomerRef.trim() === "") {
      problems.push("Enter a customer reference, or leave the field empty.");
    } else {
      filters.externalCustomerRef = draft.externalCustomerRef;
    }
  }
  if (draft.accountRef !== "") {
    if (draft.accountRef.trim() === "") {
      problems.push("Enter an account reference, or leave the field empty.");
    } else {
      filters.accountRef = draft.accountRef;
    }
  }

  return problems.length > 0 ? { filters: null, problems } : { filters, problems: [] };
}

/**
 * Clears the problems while keeping the counter where it is.
 *
 * The counter never goes back, so a successful Apply or a Reset in between two
 * refusals cannot hand the second one a number the first one already used.
 */
function clearProblems(previous: FilterRefusal): FilterRefusal {
  return previous.problems.length === 0
    ? previous
    : { problems: [], generation: previous.generation };
}

function draftMatches(draft: TransactionFilterDraft, applied: TransactionFilterDraft): boolean {
  return (
    draft.occurredAtFrom === applied.occurredAtFrom &&
    draft.occurredAtTo === applied.occurredAtTo &&
    draft.transactionType === applied.transactionType &&
    draft.processingStatus === applied.processingStatus &&
    draft.externalCustomerRef === applied.externalCustomerRef &&
    draft.accountRef === applied.accountRef
  );
}

export function TransactionListPage() {
  const [draft, setDraft] = useState<TransactionFilterDraft>(EMPTY_FILTER_DRAFT);
  const [appliedDraft, setAppliedDraft] = useState<TransactionFilterDraft>(EMPTY_FILTER_DRAFT);
  const [committed, setCommitted] = useState<CommittedFilters>(NO_FILTERS);
  const [pageNumber, setPageNumber] = useState(DEFAULT_PAGE);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [sort, setSort] = useState<TransactionListSort>(DEFAULT_SORT);
  const [refusal, setRefusal] = useState<FilterRefusal>(NO_REFUSAL);
  const problems = refusal.problems;

  const errorRef = useRef<HTMLDivElement | null>(null);

  /**
   * The one request the hook sees. Memoized on the committed values alone, so
   * typing in a field builds nothing and sends nothing: only Apply, Reset, a
   * page, a page size or a sort produces a new object here.
   */
  const query = useMemo<TransactionListQuery>(
    () => ({ ...committed, page: pageNumber, size: pageSize, sort }),
    [committed, pageNumber, pageSize, sort],
  );

  const { state, retry } = useTransactionList(query);

  const apply = useCallback(() => {
    const outcome = commitDraft(draft);
    if (outcome.filters === null) {
      // A new refusal, even when it repeats the last one word for word: the
      // analyst asked again, so the explanation is announced again.
      setRefusal((previous) => ({
        problems: outcome.problems,
        generation: previous.generation + 1,
      }));
      return;
    }
    setRefusal(clearProblems);
    setAppliedDraft(draft);
    setCommitted(outcome.filters);
    // A new filter set is a new result set, so the page index cannot survive it:
    // page 4 of the old search is not page 4 of the new one.
    setPageNumber(DEFAULT_PAGE);
  }, [draft]);

  const reset = useCallback(() => {
    setRefusal(clearProblems);
    setDraft(EMPTY_FILTER_DRAFT);
    setAppliedDraft(EMPTY_FILTER_DRAFT);
    setCommitted(NO_FILTERS);
    setPageNumber(DEFAULT_PAGE);
    setPageSize(DEFAULT_PAGE_SIZE);
    setSort(DEFAULT_SORT);
  }, []);

  const changeSort = useCallback((next: TransactionListSort) => {
    setSort(next);
    setPageNumber(DEFAULT_PAGE);
  }, []);

  const changePageSize = useCallback((next: number) => {
    setPageSize(next);
    setPageNumber(DEFAULT_PAGE);
  }, []);

  // Focus moves to the summary when a refusal appears, so a keyboard or screen
  // reader user is taken to the explanation instead of having to hunt for it.
  // It moves once per refusal, never on every render: a filter refusal is
  // identified by the submission that produced it, and a Backend refusal by the
  // kind of failure it was.
  const errorSignature =
    problems.length > 0
      ? `filters:${String(refusal.generation)}`
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

  const hasPendingEdits = !draftMatches(draft, appliedDraft);
  const hasCommittedFilters = Object.keys(committed).length > 0;

  return (
    <section className="transactions" aria-labelledby="transactions-heading">
      <div className="page-head">
        <h2 id="transactions-heading">Transactions</h2>
        <p>
          Search the transaction ledger and review where each transaction has reached in
          processing. Times are Korea Standard Time (UTC+09:00).
        </p>
      </div>

      <TransactionFilters
        draft={draft}
        onDraftChange={setDraft}
        onApply={apply}
        onReset={reset}
        hasPendingEdits={hasPendingEdits}
      />

      <div className="result-line" role="status" aria-live="polite">
        <ResultSummary state={state} />
      </div>

      {problems.length > 0 && (
        <div className="notice notice--error" role="alert" tabIndex={-1} ref={errorRef}>
          <p className="notice__title">These filters cannot be searched</p>
          <ul className="notice__body">
            {problems.map((problem) => (
              <li key={problem}>{problem}</li>
            ))}
          </ul>
        </div>
      )}

      {problems.length === 0 && state.status === "error" && (
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

      {state.status === "loading" && (
        <p className="loading-panel">
          {state.phase === "initial"
            ? "Loading transactions..."
            : "Applying filters..."}
        </p>
      )}

      {state.status === "success" && state.data.content.length === 0 && (
        <div className="notice notice--empty">
          <p className="notice__title">No transactions match these filters</p>
          <p className="notice__body">
            {hasCommittedFilters
              ? "Widen the time range or clear the filters to see more."
              : "There are no transactions to show yet."}
          </p>
          {hasCommittedFilters && (
            <button className="button" type="button" onClick={reset}>
              Reset filters
            </button>
          )}
        </div>
      )}

      {state.status === "success" && state.data.content.length > 0 && (
        <>
          <TransactionTable
            items={state.data.content}
            sort={sort}
            onSortChange={changeSort}
          />
          <TransactionPagination
            page={state.data.page}
            onPageChange={setPageNumber}
            onPageSizeChange={changePageSize}
          />
        </>
      )}
    </section>
  );
}

function ResultSummary({ state }: { readonly state: TransactionListState }) {
  if (state.status === "loading") {
    return <span>{state.phase === "initial" ? "Loading transactions" : "Applying filters"}</span>;
  }
  if (state.status === "error") {
    return <span>No results. {ERROR_COPY[state.error].title}.</span>;
  }
  if (state.status !== "success") {
    return null;
  }
  const window = describeResultWindow(
    state.data.page.number,
    state.data.page.size,
    state.data.content.length,
    state.data.page.totalElements,
  );
  if (window.total === 0) {
    return <span>No transactions found.</span>;
  }
  return (
    <span>
      Showing{" "}
      <span className="result-line__count">
        {window.first}-{window.last}
      </span>{" "}
      of <span className="result-line__count">{window.total}</span> transactions.
    </span>
  );
}
