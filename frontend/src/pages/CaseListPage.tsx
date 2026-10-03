import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router-dom";
import {
  CASE_FINAL_DISPOSITIONS,
  CASE_STATUSES,
  type CaseFinalDisposition,
  type CaseListQuery,
  type CaseListSort,
  type CaseStatus,
} from "../api/caseApi";
import { useCaseList, type CaseListErrorKind, type CaseListState } from "../api/useCaseList";
import { INFORMATION_CASE_LIST_ROUTE, OPEN_CASE_LIST_ROUTE } from "../auth/returnRoute";
import { Icon } from "../shared/Icon";
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
    title: "조회에 시간이 오래 걸립니다",
    body: "백엔드가 제때 응답하지 않았습니다. 다시 조회하세요.",
  },
  network: {
    title: "백엔드에 연결할 수 없습니다",
    body: "FinGuardOps 백엔드 연결을 확인한 뒤 다시 시도하세요.",
  },
  "invalid-response": {
    title: "조회 결과를 읽을 수 없습니다",
    body:
      "백엔드 응답을 표시할 수 없어 " +
      "일부 결과만 보여주지 않았습니다. 다시 시도하고 문제가 계속되면 알려주세요.",
  },
  "access-denied": {
    title: "접근할 수 없습니다",
    body: "사건을 조회할 권한이 없습니다.",
  },
  "session-lost": {
    title: "세션이 종료되었습니다",
    body: "계속하려면 다시 로그인하세요.",
  },
  "request-rejected": {
    title: "조회 요청을 보내지 않았습니다",
    body: "필터 조건을 확인하고 다시 조회하세요.",
  },
  unknown: {
    title: "조회에 실패했습니다",
    body: "백엔드가 조회를 완료하지 못했습니다. 다시 시도하세요.",
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
    badFrom: "생성 시작 시각을 올바르게 입력하세요.",
    badTo: "생성 끝 시각을 올바르게 입력하세요.",
    reversed: "생성 시작 시각은 끝 시각보다 늦을 수 없습니다.",
  });
  if (opened.from !== undefined) {
    filters.createdAtFrom = opened.from;
  }
  if (opened.to !== undefined) {
    filters.createdAtTo = opened.to;
  }

  const changed = commitRange(draft.lastChangedAtFrom, draft.lastChangedAtTo, problems, {
    badFrom: "변경 시작 시각을 올바르게 입력하세요.",
    badTo: "변경 끝 시각을 올바르게 입력하세요.",
    reversed: "변경 시작 시각은 끝 시각보다 늦을 수 없습니다.",
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
      problems.push("목록에서 사건 상태를 선택하세요.");
    }
  }
  if (draft.finalDisposition !== "") {
    if (isFinalDisposition(draft.finalDisposition)) {
      filters.finalDisposition = draft.finalDisposition;
    } else {
      problems.push("목록에서 최종 판정을 선택하세요.");
    }
  }

  if (draft.assigneeRef !== "") {
    const problem = assigneeRefProblem(draft.assigneeRef);
    if (problem === "blank") {
      problems.push("담당자 참조값을 입력하거나 비워 두세요.");
    } else if (problem === "too-long") {
      problems.push(
        `담당자 참조값은 ${String(MAX_ASSIGNEE_REF_LENGTH)}자 이하여야 합니다.`,
      );
    } else if (problem === "untrimmed") {
      problems.push("담당자 참조값의 앞뒤에 공백을 넣을 수 없습니다.");
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
        "연관 거래 ID에 소문자 UUID를 입력하거나 비워 두세요.",
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

function routeStatus(search: string, hash: string): CaseStatus | null | "invalid" {
  if (hash !== "") return "invalid";
  if (search === "") return null;
  if (`/cases${search}` === OPEN_CASE_LIST_ROUTE) return "OPEN";
  if (`/cases${search}` === INFORMATION_CASE_LIST_ROUTE) {
    return "ADDITIONAL_INFORMATION_REQUIRED";
  }
  return "invalid";
}

function routeDraft(status: CaseStatus | null): CaseFilterDraft {
  return status === null ? EMPTY_CASE_FILTER_DRAFT : { ...EMPTY_CASE_FILTER_DRAFT, caseStatus: status };
}

function routeFilters(status: CaseStatus | null): CommittedFilters {
  return status === null ? NO_FILTERS : { caseStatus: status };
}

export function CaseListPage() {
  const location = useLocation();
  const status = routeStatus(location.search, location.hash);
  if (status === "invalid") {
    return (
      <section className="cases" aria-labelledby="cases-heading">
        <h2 id="cases-heading">사건</h2>
        <div className="notice notice--error" role="alert">
          <p className="notice__title">이 사건 목록 주소를 열 수 없습니다</p>
          <p className="notice__body">사건 목록에서 다시 조회하세요.</p>
          <Link to="/cases">사건 목록으로</Link>
        </div>
      </section>
    );
  }
  return <CaseListContent routeSearch={location.search} initialStatus={status} />;
}

function CaseListContent({ routeSearch, initialStatus }: {
  readonly routeSearch: string;
  readonly initialStatus: CaseStatus | null;
}) {
  const navigate = useNavigate();
  const [previousSearch, setPreviousSearch] = useState(routeSearch);
  const [skipLocalNavigation, setSkipLocalNavigation] = useState(false);
  const [draft, setDraft] = useState<CaseFilterDraft>(() => routeDraft(initialStatus));
  const [appliedDraft, setAppliedDraft] = useState<CaseFilterDraft>(() => routeDraft(initialStatus));
  const [committed, setCommitted] = useState<CommittedFilters>(() => routeFilters(initialStatus));
  const [pageNumber, setPageNumber] = useState(DEFAULT_PAGE);
  const [pageSize, setPageSize] = useState(DEFAULT_PAGE_SIZE);
  const [sort, setSort] = useState<CaseListSort>(DEFAULT_SORT);
  const [refusal, setRefusal] = useState<FilterRefusal>(NO_REFUSAL);
  const problems = refusal.problems;

  // Browser history can reuse this route component. Clear the previous query's
  // rows during render, before an effect or a network response can paint them.
  if (previousSearch !== routeSearch) {
    setPreviousSearch(routeSearch);
    if (skipLocalNavigation) {
      setSkipLocalNavigation(false);
    } else {
      setDraft(routeDraft(initialStatus));
      setAppliedDraft(routeDraft(initialStatus));
      setCommitted(routeFilters(initialStatus));
      setPageNumber(DEFAULT_PAGE);
      setPageSize(DEFAULT_PAGE_SIZE);
      setSort(DEFAULT_SORT);
      setRefusal(NO_REFUSAL);
    }
  }

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
    if (routeSearch !== "" && outcome.filters.caseStatus !== initialStatus) {
      setSkipLocalNavigation(true);
      navigate("/cases", { replace: true });
    }
  }, [draft, initialStatus, navigate, routeSearch]);

  const reset = useCallback(() => {
    setRefusal(clearProblems);
    setDraft(EMPTY_CASE_FILTER_DRAFT);
    setAppliedDraft(EMPTY_CASE_FILTER_DRAFT);
    setCommitted(NO_FILTERS);
    setPageNumber(DEFAULT_PAGE);
    setPageSize(DEFAULT_PAGE_SIZE);
    setSort(DEFAULT_SORT);
    if (routeSearch !== "") {
      setSkipLocalNavigation(true);
      navigate("/cases", { replace: true });
    }
  }, [navigate, routeSearch]);

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
        <p className="page-head__eyebrow">사건 조사</p>
        <h2 id="cases-heading">사건</h2>
        <p>
          사건을 찾고 조사 상태를 확인합니다. 시간은 한국 표준시(UTC+09:00)입니다.
        </p>
      </div>

      <CaseFilters
        draft={draft}
        onDraftChange={setDraft}
        onApply={apply}
        onReset={reset}
        hasPendingEdits={hasPendingEdits}
      />

      <div className="case-results-head">
        <h3>사건 조회 결과</h3>
        <div className="result-line" role="status" aria-live="polite">
          <ResultSummary state={state} />
        </div>
      </div>

      {problems.length > 0 && (
        <div className="notice notice--error" role="alert" tabIndex={-1} ref={errorRef}>
          <p className="notice__title">필터 조건을 적용할 수 없습니다</p>
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
              <Icon name="refresh" />다시 시도
            </button>
          )}
        </div>
      )}

      {state.status === "loading" && (
        <p className="loading-panel">
          {state.phase === "initial" ? "사건을 불러오고 있습니다…" : "필터를 적용하고 있습니다…"}
        </p>
      )}

      {state.status === "success" && state.data.content.length === 0 && (
        <div className="notice notice--empty">
          <p className="notice__title">조건에 맞는 사건이 없습니다</p>
          <p className="notice__body">
            {hasCommittedFilters
              ? "기간을 넓히거나 필터를 초기화하세요."
              : "표시할 사건이 없습니다."}
          </p>
          {hasCommittedFilters && (
            <button className="button" type="button" onClick={reset}>
              <Icon name="reset" />필터 초기화
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
    return <span>{state.phase === "initial" ? "사건을 불러오는 중" : "필터 적용 중"}</span>;
  }
  if (state.status === "error") {
    return <span>결과가 없습니다. {ERROR_COPY[state.error].title}.</span>;
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
    return <span>사건이 없습니다.</span>;
  }
  return (
    <span>
      전체 <span className="result-line__count">{window.total}</span>건 중{" "}
      <span className="result-line__count">
        {window.first}~{window.last}
      </span>건 표시
    </span>
  );
}
