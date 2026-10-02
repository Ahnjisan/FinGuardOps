import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import type { CaseStatus } from "../../api/caseApi";
import { Icon } from "../../shared/Icon";
import {
  useCaseInvestigationNotes,
  type CaseInvestigationNoteItem,
  type CaseInvestigationNotesState,
  type CaseInvestigationNotesView,
} from "../../api/useCaseInvestigationNotes";
import {
  describeInvestigationNotePosition,
  describeInvestigationNoteRange,
  describeInvestigationNoteWindow,
  formatInvestigationNoteInstant,
  NOTE_PAGE_SIZE_OPTIONS,
  NO_INVESTIGATION_NOTES_MESSAGE,
  NO_INVESTIGATION_NOTES_ON_PAGE_MESSAGE,
} from "./investigationNotePresentation";
import { InvestigationNoteComposer } from "./InvestigationNoteComposer";

const INITIAL_PAGE = 0;
const INITIAL_SIZE = 20;

const ERROR_COPY = Object.freeze({
  timeout: {
    title: "조사 메모를 불러오는 데 시간이 오래 걸립니다",
    body: "백엔드가 제때 응답하지 않았습니다. 다시 불러오세요.",
  },
  "network-error": {
    title: "백엔드에 연결할 수 없습니다",
    body: "FinGuardOps 백엔드 연결을 확인한 뒤 다시 시도하세요.",
  },
  "invalid-response": {
    title: "조사 메모를 읽을 수 없습니다",
    body:
      "백엔드 응답을 표시할 수 없어 " +
      "일부 메모만 보여주지 않았습니다. 다시 시도하고 문제가 계속되면 알려주세요.",
  },
  "authentication-required": {
    title: "세션이 종료되었습니다",
    body: "계속하려면 다시 로그인하세요.",
  },
  "generic-error": {
    title: "조사 메모를 불러올 수 없습니다",
    body: "백엔드가 조사 메모를 반환하지 않았습니다. 다시 시도하세요.",
  },
});

const FORBIDDEN_COPY = Object.freeze({
  title: "접근할 수 없습니다",
  body: "조사 메모를 볼 권한이 없습니다.",
});

const NOT_FOUND_COPY = Object.freeze({
  title: "조사 메모를 볼 수 없습니다",
  body: "사건을 찾을 수 없어 조사 메모를 볼 수 없습니다.",
});

const RETRYABLE: ReadonlySet<CaseInvestigationNotesState["status"]> = new Set([
  "timeout",
  "network-error",
  "invalid-response",
  "generic-error",
]);

function refusalCopy(
  state: CaseInvestigationNotesState,
): { readonly title: string; readonly body: string } | null {
  if (state.status === "forbidden") {
    return FORBIDDEN_COPY;
  }
  if (state.status === "not-found") {
    return NOT_FOUND_COPY;
  }
  if (
    state.status === "authentication-required" ||
    state.status === "timeout" ||
    state.status === "network-error" ||
    state.status === "invalid-response" ||
    state.status === "generic-error"
  ) {
    return ERROR_COPY[state.status];
  }
  return null;
}

export interface CaseInvestigationNotesSectionProps {
  readonly caseId: string;
  readonly caseStatus?: CaseStatus | null;
  readonly expectedVersion?: number | null;
  readonly reconciliationGeneration?: number;
  readonly refreshSignal?: number;
  readonly onReconcile?: (minimumDetailVersion?: number) => void;
}

interface Cursor {
  readonly caseId: string;
  readonly page: number;
  readonly size: number;
}

/** Owns local pagination while the hook owns request lifecycle. */
export function CaseInvestigationNotesSection({
  caseId,
  caseStatus = null,
  expectedVersion = null,
  reconciliationGeneration = 0,
  refreshSignal = 0,
  onReconcile = () => undefined,
}: CaseInvestigationNotesSectionProps) {
  const [cursor, setCursor] = useState<Cursor>(() => ({
    caseId,
    page: INITIAL_PAGE,
    size: INITIAL_SIZE,
  }));

  let current = cursor;
  if (cursor.caseId !== caseId) {
    current = { caseId, page: INITIAL_PAGE, size: INITIAL_SIZE };
    setCursor(current);
  }

  const setAuthoritativePage = useCallback((page: number) => {
    setCursor((latest) => ({ ...latest, page }));
  }, []);
  const { state, retry, refresh, refreshState } = useCaseInvestigationNotes(
    caseId,
    current.page,
    current.size,
    setAuthoritativePage,
  );
  const observedRefreshSignalRef = useRef(refreshSignal);

  useEffect(() => {
    if (observedRefreshSignalRef.current === refreshSignal) {
      return;
    }
    observedRefreshSignalRef.current = refreshSignal;
    refresh();
  }, [refresh, refreshSignal]);

  return (
    <CaseInvestigationNotesPanel
      state={state}
      onPageChange={(page) => {
        setCursor({ caseId, page, size: current.size });
      }}
      onPageSizeChange={(size) => {
        setCursor({ caseId, page: INITIAL_PAGE, size });
      }}
      onRetry={retry}
      refreshState={refreshState}
      onRefresh={refresh}
      composer={caseStatus !== null && expectedVersion !== null ? (
        <InvestigationNoteComposer
          caseId={caseId}
          caseStatus={caseStatus}
          expectedVersion={expectedVersion}
          reconciliationGeneration={reconciliationGeneration}
          onReconcile={onReconcile}
        />
      ) : null}
    />
  );
}

export interface CaseInvestigationNotesPanelProps {
  readonly state: CaseInvestigationNotesState;
  readonly onPageChange: (page: number) => void;
  readonly onPageSizeChange: (size: number) => void;
  readonly onRetry: () => void;
  readonly refreshState?: "idle" | "refreshing" | "failed";
  readonly onRefresh?: () => void;
  readonly composer?: ReactNode;
}

/** Production panel, also mounted directly by the test-only geometry fixture. */
export function CaseInvestigationNotesPanel({
  state,
  onPageChange,
  onPageSizeChange,
  onRetry,
  refreshState = "idle",
  onRefresh = () => undefined,
  composer = null,
}: CaseInvestigationNotesPanelProps) {
  const refusal = refusalCopy(state);
  const retryable = RETRYABLE.has(state.status);
  const errorHeadingRef = useRef<HTMLHeadingElement | null>(null);

  useEffect(() => {
    if (retryable) {
      errorHeadingRef.current?.focus();
    }
  }, [retryable, state.status]);

  return (
    <section
      className="panel investigation-notes"
      aria-labelledby="case-notes-heading"
      aria-busy={refreshState === "refreshing" || undefined}
    >
      <h3 id="case-notes-heading">조사 메모</h3>
      <p className="investigation-notes__note">
        이 사건의 조사 메모를 조회합니다. 시간은 한국 표준시(UTC+09:00)입니다.
      </p>

      {composer}

      {refreshState === "failed" && (
        <div className="notice notice--error investigation-notes__refresh" role="alert">
          <h4 className="notice__title">최신 조사 메모를 불러올 수 없습니다</h4>
          <p className="notice__body">
            메모 등록 결과는 확인되지 않았습니다. 조사 메모를 새로고침하세요.
          </p>
          <button className="button" type="button" onClick={onRefresh}>
            <Icon name="refresh" />조사 메모 새로고침
          </button>
        </div>
      )}

      <p
        className="result-line"
        role="status"
        aria-live="polite"
        aria-label="조사 메모 상태"
      >
        <NotesSummary state={state} />
      </p>

      {refusal !== null && (
        <div className="notice notice--error" role="alert">
          <h4 className="notice__title" tabIndex={-1} ref={errorHeadingRef}>
            {refusal.title}
          </h4>
          <p className="notice__body">{refusal.body}</p>
          {retryable && (
            <button className="button" type="button" onClick={onRetry}>
              <Icon name="refresh" />조사 메모 다시 불러오기
            </button>
          )}
        </div>
      )}

      {state.status === "loading" && (
        <p className="loading-panel">조사 메모를 불러오고 있습니다…</p>
      )}

      {(state.status === "success" || state.status === "empty") && (
        <NotesRecord
          view={state.data}
          onPageChange={onPageChange}
          onPageSizeChange={onPageSizeChange}
        />
      )}
    </section>
  );
}

function NotesSummary({ state }: { readonly state: CaseInvestigationNotesState }) {
  if (state.status === "idle") {
    return <span>요청한 조사 메모가 없습니다.</span>;
  }
  if (state.status === "loading") {
    return <span>조사 메모를 불러오는 중</span>;
  }
  if (state.status === "success" || state.status === "empty") {
    return (
      <span>
        {describeInvestigationNoteRange(
          describeInvestigationNoteWindow(state.data.page, state.data.items.length),
        )}
      </span>
    );
  }
  const refusal = refusalCopy(state);
  return refusal === null ? null : <span>표시할 조사 메모가 없습니다. {refusal.title}.</span>;
}

function NotesRecord({
  view,
  onPageChange,
  onPageSizeChange,
}: {
  readonly view: CaseInvestigationNotesView;
  readonly onPageChange: (page: number) => void;
  readonly onPageSizeChange: (size: number) => void;
}) {
  return (
    <>
      {view.items.length === 0 ? (
        <p className="notice notice--empty">
          {view.page.totalElements === 0
            ? NO_INVESTIGATION_NOTES_MESSAGE
            : NO_INVESTIGATION_NOTES_ON_PAGE_MESSAGE}
        </p>
      ) : (
        <ol className="investigation-notes__list">
          {view.items.map((note, index) => (
            <NoteItem
              key={note.noteId}
              note={note}
              id={`case-note-${String(view.page.number)}-${String(index)}`}
              ordinal={view.page.number * view.page.size + index + 1}
            />
          ))}
        </ol>
      )}

      <NotesPager
        page={view.page}
        onPageChange={onPageChange}
        onPageSizeChange={onPageSizeChange}
      />
    </>
  );
}

function NoteItem({
  note,
  id,
  ordinal,
}: {
  readonly note: CaseInvestigationNoteItem;
  readonly id: string;
  readonly ordinal: number;
}) {
  return (
    <li className="investigation-notes__item">
      <article className="investigation-notes__entry" aria-labelledby={id}>
        <h4 id={id}>조사 메모 {ordinal}</h4>
        <dl className="facts">
          <dt>메모 ID</dt>
          <dd className="facts__ref">{note.noteId}</dd>

          <dt>작성자 유형</dt>
          <dd className="investigation-notes__opaque">{note.authorType}</dd>

          <dt>작성자 참조값</dt>
          <dd className="facts__ref">{note.authorRef}</dd>

          <dt>작성 시각</dt>
          <dd>
            <KstInstant utcInstant={note.createdAt} />
          </dd>

          <dt>내용</dt>
          {/* React text content only: no HTML, Markdown, linkification or truncation. */}
          <dd className="investigation-notes__content">{note.content}</dd>
        </dl>
      </article>
    </li>
  );
}

function NotesPager({
  page,
  onPageChange,
  onPageSizeChange,
}: {
  readonly page: CaseInvestigationNotesView["page"];
  readonly onPageChange: (page: number) => void;
  readonly onPageSizeChange: (size: number) => void;
}) {
  return (
    <nav className="pager" aria-label="조사 메모 페이지">
      <button
        className="button"
        type="button"
        disabled={page.first}
        onClick={() => {
          onPageChange(page.number - 1);
        }}
      >
        이전
      </button>
      <button
        className="button"
        type="button"
        disabled={page.last}
        onClick={() => {
          onPageChange(page.number + 1);
        }}
      >
        다음
      </button>
      <p className="pager__position">{describeInvestigationNotePosition(page)}</p>
      <div className="pager__size">
        <label htmlFor="case-notes-pager-size">페이지당 메모 수</label>
        <select
          id="case-notes-pager-size"
          value={page.size}
          onChange={(event) => {
            onPageSizeChange(Number(event.target.value));
          }}
        >
          {NOTE_PAGE_SIZE_OPTIONS.map((size) => (
            <option key={size} value={size}>
              {size}
            </option>
          ))}
        </select>
      </div>
    </nav>
  );
}

function KstInstant({ utcInstant }: { readonly utcInstant: string }) {
  const shown = formatInvestigationNoteInstant(utcInstant);
  return shown === null ? (
    <span className="facts__absent">시간을 표시할 수 없음</span>
  ) : (
    <time dateTime={utcInstant}>{shown} KST</time>
  );
}
