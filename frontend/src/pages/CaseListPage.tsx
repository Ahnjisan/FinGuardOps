import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  CASE_FINAL_DISPOSITIONS,
  CASE_STATUSES,
  type CaseFinalDisposition,
  type CaseListQuery,
  type CaseListSort,
  type CaseStatus,
} from "../api/caseApi";
import { useCaseList, type CaseListErrorKind, type CaseListState } from "../api/useCaseList";
import { CaseFilters } from "./cases/CaseFilters";
import { CasePagination } from "./cases/CasePagination";
import { CaseTable } from "./cases/CaseTable";
import {
  assigneeRefProblem,
  describeResultWindow,
  EMPTY_CASE_FILTER_DRAFT,
  isCanonicalTransactionFilter,
  isReversedUtcRange,
  kstInputToUtcInstant,
  MAX_ASSIGNEE_REF_LENGTH,
  type CaseFilterDraft,
} from "./cases/casePresentation";

/** The query this screen opens with, and the one Reset restores. */
export const DEFAULT_PAGE = 0;
export const DEFAULT_PAGE_SIZE = 20;
export const DEFAULT_SORT: CaseListSort = "lastChangedAt,desc";

/**
 * A committed filter set: what the last Apply actually asked for.
 *
 * Deliberately a different shape from the draft. The draft is Seoul wall-clock
 * text and half-typed references an analyst is still working on; this is UTC
 * instants, exact enum members and references the query contract will accept.
 * Keeping the two apart is what stops a half-typed date or a partially pasted
 * UUID from being sent as a filter.
 */
interface CommittedFilters {
  readonly caseStatus?: CaseStatus;
  readonly finalDisposition?: CaseFinalDisposition;
  readonly assigneeRef?: string;
  readonly createdAtFrom?: string;
  readonly createdAtTo?: string;
  readonly lastChangedAtFrom?: string;
  readonly lastChangedAtTo?: string;
  readonly transactionId?: string;
}

const NO_FILTERS: CommittedFilters = Object.freeze({});

/**
 * The refusal the last Apply produced, if it produced one.
 *
 * `generation` counts invalid submissions and nothing else. It is what focus
 * management keys on, because the alternative - a signature built from the
 * refusal itself - cannot tell two different single-problem refusals apart:
 * fixing a reversed opened range and then submitting a malformed transaction
 * identifier is two separate refusals that both carry one problem, and an
 * analyst who is taken to the first explanation has to be taken to the second
 * one as well.
 *
 * It carries no problem text and no filter value, so nothing an analyst typed -
 * no assignee reference, no identifier, no timestamp - can reach a dependency
 * array, a DOM attribute or a message through it.
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
  Record<CaseListErrorKind, { readonly title: string; readonly body: string }>
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
    body: "You do not have permission to read cases.",
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

/**
 * Errors where sending the identical request again could plausibly succeed.
 *
 * `access-denied` is absent on purpose: a 403 is the Backend's decision about
 * this session, and repeating the same request cannot change it. Offering a
 * retry there would invite an analyst to hammer an endpoint that has already
 * answered.
 */
const RETRYABLE: ReadonlySet<CaseListErrorKind> = new Set<CaseListErrorKind>([
  "timeout",
  "network",
  "invalid-response",
  "unknown",
]);

function isCaseStatus(value: string): value is CaseStatus {
  return (CASE_STATUSES as readonly string[]).includes(value);
}

function isFinalDisposition(value: string): value is CaseFinalDisposition {
  return (CASE_FINAL_DISPOSITIONS as readonly string[]).includes(value);
}

interface CommitOutcome {
  readonly filters: CommittedFilters | null;
  readonly problems: readonly string[];
}

/** One half-open instant range, committed or refused on its own. */
interface RangeOutcome {
  readonly from?: string;
  readonly to?: string;
}

/**
 * Commits one two-sided KST range into UTC instants, appending any reason it
 * cannot be committed.
 *
 * Shared by the two ranges rather than written twice, and given its own
 * sentences by the caller: `FraudCaseQueryValidator` validates
 * `[createdAtFrom, createdAtTo)` and `[lastChangedAtFrom, lastChangedAtTo)`
 * independently, so an inversion in one has to be reported as that one. A
 * half-open range and equal bounds are both legitimate and pass through.
 */
function commitRange(
  fromInput: string,
  toInput: string,
  problems: string[],
  copy: {
    readonly badFrom: string;
    readonly badTo: string;
    readonly reversed: string;
  },
): RangeOutcome {
  const range: { from?: string; to?: string } = {};
  if (fromInput !== "") {
    const from = kstInputToUtcInstant(fromInput);
    if (from === null) {
      problems.push(copy.badFrom);
    } else {
      range.from = from;
    }
  }
  if (toInput !== "") {
    const to = kstInputToUtcInstant(toInput);
    if (to === null) {
      problems.push(copy.badTo);
    } else {
      range.to = to;
    }
  }
  if (isReversedUtcRange(range.from ?? null, range.to ?? null)) {
    problems.push(copy.reversed);
  }
  return range;
}

/**
 * Turns a draft into committed filters, or into the reasons it cannot be one.
 *
 * Reference values are passed through byte for byte. No trim, no case folding,
 * no collapsing of inner spaces: `FraudCaseQueryValidator` matches an assignee
 * reference exactly and refuses one that differs from its own Java `trim()`, so
 * repairing a value here would quietly search for something the analyst did not
 * ask for - and would turn a refusal they can read into a result set they
 * cannot explain.
 */
function commitDraft(draft: CaseFilterDraft): CommitOutcome {
  const problems: string[] = [];
  const filters: {
    caseStatus?: CaseStatus;
    finalDisposition?: CaseFinalDisposition;
    assigneeRef?: string;
    createdAtFrom?: string;
    createdAtTo?: string;
    lastChangedAtFrom?: string;
    lastChangedAtTo?: string;
    transactionId?: string;
  } = {};

  const opened = commitRange(draft.createdAtFrom, draft.createdAtTo, problems, {
    badFrom: "Enter the start of the opened time range as a real date and time.",
    badTo: "Enter the end of the opened time range as a real date and time.",
    reversed: "The start of the opened time range must not be later than the end.",
  });
  if (opened.from !== undefined) {
    filters.createdAtFrom = opened.from;
  }
  if (opened.to !== undefined) {
    filters.createdAtTo = opened.to;
  }

  const changed = commitRange(draft.lastChangedAtFrom, draft.lastChangedAtTo, problems, {
    badFrom: "Enter the start of the last-changed time range as a real date and time.",
    badTo: "Enter the end of the last-changed time range as a real date and time.",
    reversed: "The start of the last-changed time range must not be later than the end.",
  });
  if (changed.from !== undefined) {
    filters.lastChangedAtFrom = changed.from;
  }
  if (changed.to !== undefined) {
    filters.lastChangedAtTo = changed.to;
  }

  if (draft.caseStatus !== "") {
    if (isCaseStatus(draft.caseStatus)) {
      filters.caseStatus = draft.caseStatus;
    } else {
      problems.push("Choose a case status from the list.");
    }
  }
  if (draft.finalDisposition !== "") {
    if (isFinalDisposition(draft.finalDisposition)) {
      filters.finalDisposition = draft.finalDisposition;
    } else {
      problems.push("Choose a final disposition from the list.");
    }
  }

  if (draft.assigneeRef !== "") {
    const problem = assigneeRefProblem(draft.assigneeRef);
    if (problem === "blank") {
      problems.push("Enter an assignee reference, or leave the field empty.");
    } else if (problem === "too-long") {
      problems.push(
        `The assignee reference must be ${String(MAX_ASSIGNEE_REF_LENGTH)} characters or fewer.`,
      );
    } else if (problem === "untrimmed") {
      problems.push("The assignee reference must not begin or end with a space.");
    } else {
      filters.assigneeRef = draft.assigneeRef;
    }
  }

  if (draft.transactionId !== "") {
    if (isCanonicalTransactionFilter(draft.transactionId)) {
      filters.transactionId = draft.transactionId;
    } else {
      // Says what shape is required and repeats nothing that was typed, so a
      // mistyped identifier cannot be read back out of the refusal.
      problems.push(
        "Enter the related transaction ID as a canonical lowercase UUID, or leave the field empty.",
      );
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

function draftMatches(draft: CaseFilterDraft, applied: CaseFilterDraft): boolean {
  return (
    draft.caseStatus === applied.caseStatus &&
    draft.finalDisposition === applied.finalDisposition &&
    draft.assigneeRef === applied.assigneeRef &&
    draft.createdAtFrom === applied.createdAtFrom &&
    draft.createdAtTo === applied.createdAtTo &&
    draft.lastChangedAtFrom === applied.lastChangedAtFrom &&
    draft.lastChangedAtTo === applied.lastChangedAtTo &&
    draft.transactionId === applied.transactionId
  );
}

export function CaseListPage() {
  const [draft, setDraft] = useState<CaseFilterDraft>(EMPTY_CASE_FILTER_DRAFT);
  const [appliedDraft, setAppliedDraft] = useState<CaseFilterDraft>(EMPTY_CASE_FILTER_DRAFT);
  const [committed, setCommitted] = useState<CommittedFilters>(NO_FILTERS);
  const [pageNumber, setPageNumber] = useState(DEFAULT_PAGE);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [sort, setSort] = useState<CaseListSort>(DEFAULT_SORT);
  const [refusal, setRefusal] = useState<FilterRefusal>(NO_REFUSAL);
  const problems = refusal.problems;

  const errorRef = useRef<HTMLDivElement | null>(null);

  /**
   * The one request the hook sees. Memoized on the committed values alone, so
   * typing in a field builds nothing and sends nothing: only Apply, Reset, a
   * page, a page size or a sort produces a new object here.
   */
  const query = useMemo<CaseListQuery>(
    () => ({ ...committed, page: pageNumber, size: pageSize, sort }),
    [committed, pageNumber, pageSize, sort],
  );

  const { state, retry } = useCaseList(query);

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
    setDraft(EMPTY_CASE_FILTER_DRAFT);
    setAppliedDraft(EMPTY_CASE_FILTER_DRAFT);
    setCommitted(NO_FILTERS);
    setPageNumber(DEFAULT_PAGE);
    setPageSize(DEFAULT_PAGE_SIZE);
    setSort(DEFAULT_SORT);
  }, []);

  const changeSort = useCallback((next: CaseListSort) => {
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
  // kind of failure it was. Neither half of the signature carries a financial
  // reference, so the value an analyst typed never reaches a dependency array.
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
    <section className="cases" aria-labelledby="cases-heading">
      <div className="page-head">
        <h2 id="cases-heading">Cases</h2>
        <p>
          Search fraud cases and review where each one has reached in investigation. Times
          are Korea Standard Time (UTC+09:00).
        </p>
      </div>

      <CaseFilters
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
          {state.phase === "initial" ? "Loading cases..." : "Applying filters..."}
        </p>
      )}

      {state.status === "success" && state.data.content.length === 0 && (
        <div className="notice notice--empty">
          <p className="notice__title">No cases match these filters</p>
          <p className="notice__body">
            {hasCommittedFilters
              ? "Widen the time range or clear the filters to see more."
              : "There are no cases to show yet."}
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
          <CaseTable items={state.data.content} sort={sort} onSortChange={changeSort} />
          <CasePagination
            page={state.data.page}
            onPageChange={setPageNumber}
            onPageSizeChange={changePageSize}
          />
        </>
      )}
    </section>
  );
}

function ResultSummary({ state }: { readonly state: CaseListState }) {
  if (state.status === "loading") {
    return <span>{state.phase === "initial" ? "Loading cases" : "Applying filters"}</span>;
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
    return <span>No cases found.</span>;
  }
  return (
    <span>
      Showing{" "}
      <span className="result-line__count">
        {window.first}-{window.last}
      </span>{" "}
      of <span className="result-line__count">{window.total}</span> cases.
    </span>
  );
}
