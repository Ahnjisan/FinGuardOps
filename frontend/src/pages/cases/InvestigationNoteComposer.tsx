import { useEffect, useRef, useState, type FormEvent, type KeyboardEvent } from "react";
import type { CaseStatus } from "../../api/caseApi";
import {
  countInvestigationNoteCodePoints,
  INVESTIGATION_NOTE_CONFLICT_MESSAGE,
  INVESTIGATION_NOTE_FAILURE_MESSAGE,
  INVESTIGATION_NOTE_SUCCESS_MESSAGE,
  INVESTIGATION_NOTE_VALIDATION_MESSAGE,
  isWritableCaseStatus,
  useCreateInvestigationNote,
  type CreateInvestigationNoteState,
} from "../../api/useCreateInvestigationNote";
import { useCapabilities } from "../../auth/useCapabilities";

const HELPER_ID = "investigation-note-helper";
const COUNTER_ID = "investigation-note-counter";
const ERROR_ID = "investigation-note-error";

export interface InvestigationNoteComposerProps {
  readonly caseId: string;
  readonly caseStatus: CaseStatus;
  readonly expectedVersion: number;
  readonly reconciliationGeneration: number;
  readonly onReconcile: (minimumDetailVersion?: number) => void;
}

function isFailure(state: CreateInvestigationNoteState): boolean {
  return (
    state.status === "conflict" ||
    state.status === "forbidden" ||
    state.status === "not-found" ||
    state.status === "authentication-required" ||
    state.status === "timeout" ||
    state.status === "network-error" ||
    state.status === "server-error"
  );
}

function failureMessage(state: CreateInvestigationNoteState): string | null {
  if (state.status === "conflict") {
    return INVESTIGATION_NOTE_CONFLICT_MESSAGE;
  }
  return isFailure(state) ? INVESTIGATION_NOTE_FAILURE_MESSAGE : null;
}

export function InvestigationNoteComposer({
  caseId,
  caseStatus,
  expectedVersion,
  reconciliationGeneration,
  onReconcile,
}: InvestigationNoteComposerProps) {
  const capabilities = useCapabilities();
  const [draft, setDraft] = useState("");
  const textareaRef = useRef<HTMLTextAreaElement | null>(null);
  const errorHeadingRef = useRef<HTMLHeadingElement | null>(null);
  const handledSuccessRef = useRef(0);
  const composingRef = useRef(false);
  const { state, submit, reset, waitingForReconciliation } = useCreateInvestigationNote({
    caseId,
    caseStatus,
    expectedVersion,
    reconciliationGeneration,
    onReconcile,
  });

  useEffect(() => {
    if (state.status === "validation-error") {
      textareaRef.current?.focus();
      return;
    }
    if (isFailure(state)) {
      errorHeadingRef.current?.focus();
      return;
    }
    if (state.status === "success" && handledSuccessRef.current !== state.submission) {
      handledSuccessRef.current = state.submission;
      setDraft("");
      textareaRef.current?.focus();
    }
  }, [state]);

  if (!capabilities.has("case:note-write")) {
    return null;
  }

  if (!isWritableCaseStatus(caseStatus)) {
    return (
      <p className="notice notice--empty investigation-note-composer__locked">
        Investigation notes cannot be added while this case is {caseStatus === "OPEN" ? "open" : "closed"}.
      </p>
    );
  }

  const pending = state.status === "submitting";
  const invalid = state.status === "validation-error";
  const failure = failureMessage(state);
  const count = countInvestigationNoteCodePoints(draft);

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    submit(draft);
  };

  const handleShortcut = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (
      event.key === "Enter" &&
      (event.ctrlKey || event.metaKey) &&
      !event.nativeEvent.isComposing &&
      !composingRef.current
    ) {
      event.preventDefault();
      event.currentTarget.form?.requestSubmit();
    }
  };

  return (
    <div className="investigation-note-composer">
      <form onSubmit={handleSubmit} aria-busy={pending || undefined} noValidate>
        <label htmlFor="investigation-note-content">Investigation note</label>
        <p id={HELPER_ID} className="investigation-note-composer__helper">
          Plain text, 1–4,000 Unicode characters. Ctrl+Enter or Cmd+Enter submits.
        </p>
        <textarea
          id="investigation-note-content"
          ref={textareaRef}
          value={draft}
          autoComplete="off"
          aria-describedby={`${HELPER_ID} ${COUNTER_ID}${invalid ? ` ${ERROR_ID}` : ""}`}
          aria-invalid={invalid || undefined}
          disabled={pending}
          onChange={(event) => {
            setDraft(event.target.value);
          }}
          onKeyDown={handleShortcut}
          onCompositionStart={() => {
            composingRef.current = true;
          }}
          onCompositionEnd={() => {
            composingRef.current = false;
          }}
        />
        <p id={COUNTER_ID} className="investigation-note-composer__counter" aria-live="off">
          {count.toLocaleString("en-US")} / 4,000
        </p>

        {invalid && (
          <p id={ERROR_ID} className="form-error">
            {INVESTIGATION_NOTE_VALIDATION_MESSAGE}
          </p>
        )}

        {failure !== null && (
          <div className="notice notice--error investigation-note-composer__feedback" role="alert">
            <h4 ref={errorHeadingRef} tabIndex={-1} className="notice__title">
              Note not added
            </h4>
            <p className="notice__body">{failure}</p>
          </div>
        )}

        <p
          className="investigation-note-composer__live"
          role="status"
          aria-live="polite"
          aria-atomic="true"
        >
          {state.status === "success" ? INVESTIGATION_NOTE_SUCCESS_MESSAGE : ""}
        </p>

        <div className="investigation-note-composer__actions">
          <button
            className="button"
            type="button"
            disabled={pending}
            onClick={() => {
              if (state.status === "success") {
                return;
              }
              setDraft("");
              reset();
              textareaRef.current?.focus();
            }}
          >
            Cancel
          </button>
          <button
            className="button button--primary"
            type="submit"
            disabled={pending || waitingForReconciliation}
          >
            {pending ? "Adding note…" : "Add note"}
          </button>
        </div>
      </form>
    </div>
  );
}
