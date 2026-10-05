import { useCallback, useEffect, useRef, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import { Icon } from "../shared/Icon";
import { isCanonicalUuidV4 } from "../api/backendEndpoints";
import type { CaseDetail } from "../api/caseApi";
import { CaseAiReportSection } from "./cases/CaseAiReportSection";
import {
  useCaseDetail,
  type CaseDetailErrorKind,
  type CaseDetailState,
} from "../api/useCaseDetail";
import type { CaseWorkflowReconciliationScope } from "../api/useCaseWorkflowMutations";
import { CaseAuditSection } from "./cases/CaseAuditSection";
import { CaseTransactionsSection } from "./cases/CaseTransactionsSection";
import { CaseInvestigationNotesSection } from "./cases/CaseInvestigationNotesSection";
import { CaseWorkflowSection } from "./cases/CaseWorkflowSection";
import {
  CASE_FINAL_DISPOSITION_LABELS,
  CASE_STATUS_LABELS,
  caseStatusTone,
  describeReference,
  formatCaseInstant,
  formatTransactionCount,
  UNASSIGNED_LABEL,
} from "./cases/casePresentation";

/**
 * One fraud case record with capability-gated workflow actions.
 *
 * Everything in the record on this screen comes from the `CaseDetail` contract
 * and nothing else. There is no risk level, no risk score, no detection result,
 * no rule evidence, no related transaction ID, no investigation note and no AI
 * report, because `GET /api/v1/cases/{caseId}` carries none of them and a
 * console that infers one puts a judgement on screen that no system made.
 *
 * Below the record sit the workflow section, investigation notes, audit
 * history and a separately fetched page of linked transaction IDs. Each read
 * owns independent request, error and paging state.
 *
 * The two settled refusals are the exception, and deliberately so. A case that
 * does not exist and a case this session may not read are answers about the
 * case itself, so both subordinate sections are removed rather than left to
 * repeat the refusal. Unmounting discards their state and blocks late publish.
 *
 * Status, reassignment and resolution share one page-level mutation lane and
 * reconcile through authoritative detail/audit reads.
 * 상태·담당자 control은 `case:workflow`, 최종 판정 form은 `case:resolve` capability에만 노출된다.
 * The investigation-note composer retains its separate append lifecycle; a note
 * racing a resolution is decided by Backend optimistic concurrency (409).
 *
 * `concurrencyVersion` is shown for the same reason the rest of the record is -
 * it is part of the response - and for no other. It is record metadata a reader
 * can quote when reporting a case and the exact optimistic-locking token the
 * note composer and workflow lane send as `expectedVersion`.
 */

/**
 * One path segment after `/cases/`, taken from the location the browser gave
 * this application.
 *
 * `[^/]+` is a *shape* check, not the decision. The decision is
 * `isCanonicalUuidV4` below, applied to that segment exactly as it reaches
 * here - never decoded, trimmed or case folded: a surviving encoded slash, an
 * encoded backslash, a duplicate separator, whitespace, a trailing slash, an
 * extra segment, an uppercase digit, a non-v4 version nibble and an invalid
 * variant nibble all fail it, and so does anything carrying a `%` at all.
 */
const DETAIL_PATH = /^\/cases\/([^/]+)$/;

interface CaseRouteLocation {
  readonly pathname: string;
  readonly search: string;
  readonly hash: string;
}

/**
 * The case this address names, or `null` when it names none.
 *
 * The input boundary here is the location the browser's own URL parser handed
 * to this application - `location.pathname`, `location.search` and
 * `location.hash` - and nothing earlier than that. Whatever the address bar, a
 * link or a redirect originally carried has already been parsed and
 * canonicalized by the browser before any of this code runs; a representation
 * the browser resolved away is not recoverable here and is not claimed to be.
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
function readCanonicalCaseId(location: CaseRouteLocation): string | null {
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
 * What a case with no final disposition is shown as on this screen.
 *
 * `null` means the investigation has not concluded - not that the case was
 * found normal, and not that the field failed to render. The wording is fixed
 * here rather than derived, so the screen cannot drift into implying either.
 */
const NOT_DECIDED_LABEL = "미결정";

/** What a case whose review has not begun is shown as. */
const NOT_STARTED_LABEL = "시작 전";

/** What a case that is still open is shown as. */
const NOT_CLOSED_LABEL = "종결 전";

/**
 * Fixed messages. Each says what happened and what the analyst can do about it,
 * and none of them carries a status code, a trace id, a response body, a token
 * or the address that was asked for.
 */
const ERROR_COPY: Readonly<
  Record<CaseDetailErrorKind, { readonly title: string; readonly body: string }>
> = Object.freeze({
  timeout: {
    title: "사건을 불러오는 데 시간이 오래 걸립니다",
    body: "백엔드가 제때 응답하지 않았습니다. 다시 불러오세요.",
  },
  network: {
    title: "백엔드에 연결할 수 없습니다",
    body: "FinGuardOps 백엔드 연결을 확인한 뒤 다시 시도하세요.",
  },
  "invalid-response": {
    title: "사건 정보를 읽을 수 없습니다",
    body:
      "백엔드 응답을 표시할 수 없어 " +
      "일부 기록만 보여주지 않았습니다. 다시 시도하고 문제가 계속되면 알려주세요.",
  },
  "session-lost": {
    title: "세션이 종료되었습니다",
    body: "계속하려면 다시 로그인하세요.",
  },
  "request-rejected": {
    title: "사건을 요청하지 않았습니다",
    body: "이 주소로는 사건을 요청할 수 없습니다.",
  },
  unknown: {
    title: "사건을 불러올 수 없습니다",
    body: "백엔드가 사건 정보를 반환하지 않았습니다. 다시 시도하세요.",
  },
});

/**
 * Errors where sending the identical request again could plausibly succeed.
 *
 * Every kind in `CaseDetailErrorKind` except the two that say something about
 * the session rather than about the request. A 404 and a 403 are not in this
 * set at all: they are statuses of their own, and no retry control is rendered
 * for either.
 */
const RETRYABLE: ReadonlySet<CaseDetailErrorKind> = new Set<CaseDetailErrorKind>([
  "timeout",
  "network",
  "invalid-response",
  "unknown",
]);

const NOT_FOUND_COPY = Object.freeze({
  title: "사건을 찾을 수 없습니다",
  body: "이 ID에 해당하는 사건이 없습니다. 사건 목록으로 돌아가세요.",
});

const FORBIDDEN_COPY = Object.freeze({
  title: "접근할 수 없습니다",
  body: "이 사건을 볼 권한이 없습니다.",
});

const INVALID_ROUTE_COPY = Object.freeze({
  title: "올바른 사건 주소가 아닙니다",
  body: "이 주소로는 사건을 열 수 없습니다. 사건 목록에서 다시 선택하세요.",
});

/** The fixed refusal a settled non-success state shows, or `null` for none. */
function refusalCopy(
  state: CaseDetailState,
): { readonly title: string; readonly body: string } | null {
  if (state.status === "not-found") {
    return NOT_FOUND_COPY;
  }
  if (state.status === "forbidden") {
    return FORBIDDEN_COPY;
  }
  if (state.status === "error") {
    return ERROR_COPY[state.error];
  }
  return null;
}

export function CaseDetailPage() {
  const location = useLocation();
  const caseId = readCanonicalCaseId(location);
  const {
    state,
    retry,
    refresh,
    refreshState,
    reconciliationGeneration,
  } = useCaseDetail(caseId);
  const [notesRefreshSignal, setNotesRefreshSignal] = useState(0);
  const [auditRefreshSignal, setAuditRefreshSignal] = useState(0);
  const [aiReportOpen, setAiReportOpen] = useState(false);
  const reconcileNoteMutation = useCallback((minimumDetailVersion?: number) => {
    refresh(minimumDetailVersion);
    setNotesRefreshSignal((current) => current + 1);
    setAuditRefreshSignal((current) => current + 1);
  }, [refresh]);
  const reconcileWorkflowMutation = useCallback((
    scope: CaseWorkflowReconciliationScope,
    minimumDetailVersion: number,
  ) => {
    refresh(minimumDetailVersion);
    setAuditRefreshSignal((current) => current + 1);
    if (scope === "detail-notes-audit") {
      setNotesRefreshSignal((current) => current + 1);
    }
  }, [refresh]);

  const errorRef = useRef<HTMLDivElement | null>(null);

  // Focus moves to the summary once per refusal, never on every render. A
  // malformed address is one fixed refusal; a Backend answer is identified by
  // what it was, so a retry that fails the same way again does not steal focus
  // a second time from someone who is already reading it.
  const errorSignature =
    caseId === null
      ? "route"
      : state.status === "error"
        ? `request:${state.error}`
        : state.status === "not-found" || state.status === "forbidden"
          ? `request:${state.status}`
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

  const refusal = caseId === null ? INVALID_ROUTE_COPY : refusalCopy(state);
  const retryable = caseId !== null && state.status === "error" && RETRYABLE.has(state.error);

  return (
    <section className="detail" aria-labelledby="case-detail-heading">
      <div className="page-head">
        <p className="detail__back">
          <Link to="/cases"><Icon name="back" />사건 목록으로</Link>
        </p>
        <h2 id="case-detail-heading">
          사건
          {caseId !== null && (
            <>
              {" "}
              <span className="detail__id">{caseId}</span>
            </>
          )}
        </h2>
        <p>
          사건 기록을 조회합니다. 시간은 한국 표준시(UTC+09:00)입니다.
        </p>
      </div>

      {caseId !== null && state.status === "success" && (
        <CaseAtAGlance detail={state.data} />
      )}

      {/*
        Named, because both subordinate sections have live regions of their own.
      */}
      <div
        className="result-line"
        role="status"
        aria-live="polite"
        aria-label="사건 기록 상태"
      >
        <DetailSummary invalidRoute={caseId === null} state={state} />
      </div>

      {refusal !== null && (
        <div className="notice notice--error" role="alert" tabIndex={-1} ref={errorRef}>
          <p className="notice__title">{refusal.title}</p>
          <p className="notice__body">{refusal.body}</p>
          {retryable && (
            <button className="button" type="button" onClick={retry}>
              <Icon name="refresh" />다시 시도
            </button>
          )}
        </div>
      )}

      {caseId !== null && state.status === "loading" && (
        <p className="loading-panel">사건을 불러오고 있습니다…</p>
      )}

      {caseId !== null && state.status === "success" && (
        <div className="case-detail__workspace">
          <CaseRecord detail={state.data} />
          <div className="case-detail__work-area">
            <CaseWorkflowSection
              detail={state.data}
              reconciliationGeneration={reconciliationGeneration}
              detailRefreshState={refreshState}
              onReconcile={reconcileWorkflowMutation}
            />
            {refreshState === "failed" && (
              <div className="notice notice--error case-detail__refresh" role="alert">
                <h3 className="notice__title">최신 사건 정보를 불러올 수 없습니다</h3>
                <p className="notice__body">
                  직전 변경 결과는 확인되지 않았습니다. 다른 작업 전에 사건 정보를 새로고침하세요.
                </p>
                <button className="button" type="button" onClick={() => refresh()}>
                  <Icon name="refresh" />사건 정보 새로고침
                </button>
              </div>
            )}
          </div>
        </div>
      )}

      {/*
        Both sections mount from the first render of a canonical address. Their
        effects therefore start beside the detail request in the same commit.
        A detail 403/404 unmounts both and their lifecycle gates refuse any late
        notes or audit settlement.
      */}
      {caseId !== null && showsSubordinateSections(state) && (
        <>
          <CaseInvestigationNotesSection
            caseId={caseId}
            caseStatus={state.status === "success" ? state.data.caseStatus : null}
            expectedVersion={state.status === "success" ? state.data.concurrencyVersion : null}
            reconciliationGeneration={reconciliationGeneration}
            refreshSignal={notesRefreshSignal}
            onReconcile={reconcileNoteMutation}
          />
          <CaseAuditSection caseId={caseId} refreshSignal={auditRefreshSignal} />
        </>
      )}

      {caseId !== null && state.status === "success" &&
        <CaseTransactionsSection caseId={caseId} />}
      {caseId !== null && state.status === "success" &&
        <details className="case-ai-report-disclosure" onToggle={(event) =>
          setAiReportOpen(event.currentTarget.open)}>
          <summary>AI 조사 보조 리포트 보기</summary>
          {aiReportOpen && <CaseAiReportSection caseId={caseId} caseStatus={state.data.caseStatus} />}
        </details>}
    </section>
  );
}

/**
 * Whether the audit history section belongs on screen for this detail state.
 *
 * Present while the record is loading, present when it arrives, and present
 * when the record failed in a way that says nothing about the case - a timeout,
 * a network failure, an unreadable response. Absent for the two settled
 * refusals, which are answers about the case itself.
 */
function showsSubordinateSections(state: CaseDetailState): boolean {
  return state.status !== "not-found" && state.status !== "forbidden";
}

function DetailSummary({
  invalidRoute,
  state,
}: {
  readonly invalidRoute: boolean;
  readonly state: CaseDetailState;
}) {
  if (invalidRoute) {
    return <span>{INVALID_ROUTE_COPY.title}.</span>;
  }
  if (state.status === "loading") {
    return <span>사건을 불러오는 중</span>;
  }
  if (state.status === "success") {
    return <span>사건 기록 전체를 표시합니다.</span>;
  }
  const refusal = refusalCopy(state);
  if (refusal !== null) {
    return <span>표시할 기록이 없습니다. {refusal.title}.</span>;
  }
  return null;
}

/** The three contracted facts that orient a reader before the full record. */
export function CaseAtAGlance({ detail }: { readonly detail: CaseDetail }) {
  const assignee = describeReference(detail.assigneeRef);

  return (
    <dl className="case-detail__glance" aria-label="사건 요약">
      <div className="case-detail__glance-item">
        <dt>사건 상태</dt>
        <dd>
          <span className={`badge badge--${caseStatusTone(detail.caseStatus)}`}>
            <span className="badge__mark" aria-hidden="true" />
            {CASE_STATUS_LABELS[detail.caseStatus]}
          </span>
        </dd>
      </div>
      <div className="case-detail__glance-item">
        <dt>최종 판정</dt>
        <dd>{detail.finalDisposition === null
          ? <span className="facts__absent">{NOT_DECIDED_LABEL}</span>
          : CASE_FINAL_DISPOSITION_LABELS[detail.finalDisposition]}</dd>
      </div>
      <div className="case-detail__glance-item">
        <dt>담당자</dt>
        <dd className="facts__ref">{assignee.absent
          ? <span className="facts__absent">{UNASSIGNED_LABEL}</span>
          : assignee.text}</dd>
      </div>
    </dl>
  );
}

/**
 * The record itself, as three sections of definitions.
 *
 * Ten names for the ten fields the contract carries, in three groups: what the
 * case currently is, when it moved, and the metadata of the row itself. A
 * definition list rather than a grid of cards - every line here is a name and
 * one value, and wrapping each pair in its own bordered surface would add
 * decoration without adding a single fact.
 */
export function CaseRecord({ detail }: { readonly detail: CaseDetail }) {
  const tone = caseStatusTone(detail.caseStatus);
  const assignee = describeReference(detail.assigneeRef);

  return (
    <div className="detail__record">
      <section className="panel" aria-labelledby="case-summary-heading">
        <h3 id="case-summary-heading">사건</h3>
        <dl className="facts">
          <dt>사건 ID</dt>
          <dd className="facts__ref">{detail.caseId}</dd>

          <dt>사건 상태</dt>
          <dd>
            {/*
              Shape, word and colour together, exactly as the sheet renders it.
              This is where the case has reached in the investigation workflow -
              not a risk level, and not a verdict about the case.
            */}
            <span className={`badge badge--${tone}`}>
              <span className="badge__mark" aria-hidden="true" />
              {CASE_STATUS_LABELS[detail.caseStatus]}
            </span>
          </dd>

          <dt>최종 판정</dt>
          <dd>
            {detail.finalDisposition === null ? (
              // A word rather than an empty cell, and a word that means "not
              // concluded yet" rather than one that could be read as a verdict.
              <span className="facts__absent">{NOT_DECIDED_LABEL}</span>
            ) : (
              CASE_FINAL_DISPOSITION_LABELS[detail.finalDisposition]
            )}
          </dd>

          <dt>담당자</dt>
          <dd className="facts__ref">
            {assignee.absent ? (
              <span className="facts__absent">{UNASSIGNED_LABEL}</span>
            ) : (
              assignee.text
            )}
          </dd>

          <dt>연관 거래</dt>
          {/*
            The number Backend counted, printed exactly as the validator
            admitted it. The transactions themselves are a separate endpoint
            this console does not call, so the count is a count and not a link.
          */}
          <dd>{formatTransactionCount(detail.relatedTransactionCount)}</dd>
        </dl>
      </section>

      <section className="panel" aria-labelledby="case-timeline-heading">
        <h3 id="case-timeline-heading">사건 시각</h3>
        <dl className="facts">
          <dt>생성</dt>
          <dd>
            <KstInstant utcInstant={detail.createdAt} />
          </dd>

          <dt>검토 시작</dt>
          <dd>
            <NullableInstant utcInstant={detail.reviewStartedAt} absentLabel={NOT_STARTED_LABEL} />
          </dd>

          <dt>종결</dt>
          <dd>
            <NullableInstant utcInstant={detail.closedAt} absentLabel={NOT_CLOSED_LABEL} />
          </dd>

          {/*
            The contract's own field name is `lastChangedAt`, and this is what
            it is called on screen. There is no `updatedAt` in this response and
            none is invented here.
          */}
          <dt>최종 변경</dt>
          <dd>
            <KstInstant utcInstant={detail.lastChangedAt} />
          </dd>
        </dl>
      </section>

      <section className="panel" aria-labelledby="case-metadata-heading">
        <h3 id="case-metadata-heading">기록 정보</h3>
        <dl className="facts">
          <dt>버전</dt>
          <dd>{formatTransactionCount(detail.concurrencyVersion)}</dd>
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
  const shown = formatCaseInstant(utcInstant);
  if (shown === null) {
    // Unreachable through the validated contract, and still not a place to
    // print the raw value: a time that cannot be read is reported as one.
    return <span className="facts__absent">시간을 표시할 수 없음</span>;
  }
  return <time dateTime={utcInstant}>{shown} KST</time>;
}

/**
 * An instant the contract allows to be `null`, and the fixed phrase that stands
 * for its absence.
 *
 * The phrase says which milestone has not happened yet. It is not a time, so it
 * is not wrapped in a `<time>`: an element with no `datetime` would be a
 * machine-readable claim about a moment that does not exist.
 */
function NullableInstant({
  utcInstant,
  absentLabel,
}: {
  readonly utcInstant: string | null;
  readonly absentLabel: string;
}) {
  if (utcInstant === null) {
    return <span className="facts__absent">{absentLabel}</span>;
  }
  return <KstInstant utcInstant={utcInstant} />;
}
