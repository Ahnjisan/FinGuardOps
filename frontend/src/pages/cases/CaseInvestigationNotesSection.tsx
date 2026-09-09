import { useEffect, useRef, useState } from "react";
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

const INITIAL_PAGE = 0;
const INITIAL_SIZE = 20;

const ERROR_COPY = Object.freeze({
  timeout: {
    title: "The investigation notes took too long to load",
    body: "The backend did not answer in time. Try loading them again.",
  },
  "network-error": {
    title: "The backend could not be reached",
    body: "Check the connection to the FinGuardOps backend, then try again.",
  },
  "invalid-response": {
    title: "The investigation notes could not be read",
    body:
      "The backend returned data this console will not display. Nothing is shown rather than " +
      "part of the investigation record. Try again, and report it if it continues.",
  },
  "authentication-required": {
    title: "Your session ended",
    body: "Sign in again to continue.",
  },
  "generic-error": {
    title: "The investigation notes could not be loaded",
    body: "The backend could not return these investigation notes. Try again.",
  },
});

const FORBIDDEN_COPY = Object.freeze({
  title: "Access denied",
  body: "You do not have permission to view investigation notes.",
});

const NOT_FOUND_COPY = Object.freeze({
  title: "Investigation notes unavailable",
  body: "Investigation notes are unavailable because this case was not found.",
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
}

interface Cursor {
  readonly caseId: string;
  readonly page: number;
  readonly size: number;
}

/** Owns local pagination while the hook owns request lifecycle. */
export function CaseInvestigationNotesSection({
  caseId,
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

  const { state, retry } = useCaseInvestigationNotes(caseId, current.page, current.size);

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
    />
  );
}

export interface CaseInvestigationNotesPanelProps {
  readonly state: CaseInvestigationNotesState;
  readonly onPageChange: (page: number) => void;
  readonly onPageSizeChange: (size: number) => void;
  readonly onRetry: () => void;
}

/** Production panel, also mounted directly by the test-only geometry fixture. */
export function CaseInvestigationNotesPanel({
  state,
  onPageChange,
  onPageSizeChange,
  onRetry,
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
    <section className="panel investigation-notes" aria-labelledby="case-notes-heading">
      <h3 id="case-notes-heading">Investigation notes</h3>
      <p className="investigation-notes__note">
        Read-only notes recorded for this investigation. Times are Korea Standard Time
        (UTC+09:00).
      </p>

      <p
        className="result-line"
        role="status"
        aria-live="polite"
        aria-label="Investigation notes status"
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
              Try loading the investigation notes again
            </button>
          )}
        </div>
      )}

      {state.status === "loading" && (
        <p className="loading-panel">Loading investigation notes...</p>
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
    return <span>No investigation notes requested.</span>;
  }
  if (state.status === "loading") {
    return <span>Loading investigation notes</span>;
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
  return refusal === null ? null : <span>No investigation notes shown. {refusal.title}.</span>;
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
        <h4 id={id}>Investigation note {ordinal}</h4>
        <dl className="facts">
          <dt>Note ID</dt>
          <dd className="facts__ref">{note.noteId}</dd>

          <dt>Author type</dt>
          <dd className="investigation-notes__opaque">{note.authorType}</dd>

          <dt>Author reference</dt>
          <dd className="facts__ref">{note.authorRef}</dd>

          <dt>Created at</dt>
          <dd>
            <KstInstant utcInstant={note.createdAt} />
          </dd>

          <dt>Content</dt>
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
    <nav className="pager" aria-label="Investigation notes pages">
      <button
        className="button"
        type="button"
        disabled={page.first}
        onClick={() => {
          onPageChange(page.number - 1);
        }}
      >
        Previous
      </button>
      <button
        className="button"
        type="button"
        disabled={page.last}
        onClick={() => {
          onPageChange(page.number + 1);
        }}
      >
        Next
      </button>
      <p className="pager__position">{describeInvestigationNotePosition(page)}</p>
      <div className="pager__size">
        <label htmlFor="case-notes-pager-size">Notes per page</label>
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
    <span className="facts__absent">Not a readable time</span>
  ) : (
    <time dateTime={utcInstant}>{shown} KST</time>
  );
}
