import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import type { CaseDetail } from "../../api/caseApi";
import {
  useCaseWorkflowMutations,
  type CaseWorkflowActionKind,
  type CaseWorkflowMutationState,
  type CaseWorkflowReconciliationScope,
} from "../../api/useCaseWorkflowMutations";
import { useAuth } from "../../auth/useAuth";
import { useCapabilities } from "../../auth/useCapabilities";
import { CASE_STATUS_LABELS } from "./casePresentation";

const ASSIGNEE_INPUT_ID = "case-workflow-assignee";
const ASSIGNEE_HELPER_ID = "case-workflow-assignee-helper";
const ASSIGNEE_ERROR_ID = "case-workflow-assignee-error";

const SUCCESS_MESSAGES: Readonly<Record<CaseWorkflowActionKind, string>> = Object.freeze({
  "start-review": "Review started from authoritative case information.",
  "request-additional-information":
    "Additional information requested from authoritative case information.",
  "resume-review": "Review resumed from authoritative case information.",
  "change-assignee": "Assignee updated from authoritative case information.",
  "release-assignee": "Assignee released from authoritative case information.",
});

export interface CaseWorkflowSectionProps {
  readonly detail: CaseDetail;
  readonly reconciliationGeneration: number;
  readonly detailRefreshState: "idle" | "refreshing" | "failed";
  readonly onReconcile: (
    scope: CaseWorkflowReconciliationScope,
    minimumDetailVersion: number,
  ) => void;
}

function isRequestFailure(state: CaseWorkflowMutationState): boolean {
  return (
    state.status === "conflict" ||
    state.status === "ambiguous" ||
    state.status === "forbidden" ||
    state.status === "not-found" ||
    state.status === "authentication-required" ||
    state.status === "request-rejected" ||
    state.status === "server-error" ||
    (state.status === "validation-error" && state.field === "action")
  );
}

function failureCopy(
  state: CaseWorkflowMutationState,
): { readonly title: string; readonly body: string } | null {
  switch (state.status) {
    case "conflict":
      return {
        title: "The case changed before this action",
        body: "Review the latest case information, then submit the action again.",
      };
    case "ambiguous":
      return {
        title: "The workflow result could not be confirmed",
        body: "The latest case information has been loaded. Review it before submitting again.",
      };
    case "forbidden":
      return {
        title: "The workflow action was denied",
        body: "Your session is unchanged. Ask an authorized analyst to continue this workflow.",
      };
    case "not-found":
      return {
        title: "The case is no longer available",
        body: "Return to the case list before taking another action.",
      };
    case "authentication-required":
      return {
        title: "Your session ended",
        body: "Sign in again before taking another workflow action.",
      };
    case "request-rejected":
      return {
        title: "The workflow action was not sent",
        body: "Review the current case and submit an available action again.",
      };
    case "server-error":
      return {
        title: "The workflow action could not be completed",
        body: "The backend did not complete this action. Your assignee entry has been kept.",
      };
    case "validation-error":
      return state.field === "action"
        ? {
            title: "This workflow action is no longer available",
            body: "Review the current case state and choose an available action.",
          }
        : null;
    default:
      return null;
  }
}

function assigneeHelper(detail: CaseDetail): string {
  if (detail.caseStatus === "OPEN") {
    return "Required to start review. Enter one canonical lowercase UUID v4 exactly as issued.";
  }
  return "Enter a different canonical lowercase UUID v4. Spaces and uppercase letters are not corrected.";
}

export function CaseWorkflowSection({
  detail,
  reconciliationGeneration,
  detailRefreshState,
  onReconcile,
}: CaseWorkflowSectionProps) {
  const { state: authState } = useAuth();
  const capabilities = useCapabilities();
  const [assigneeDraft, setAssigneeDraft] = useState("");
  const sectionRef = useRef<HTMLElement | null>(null);
  const assigneeInputRef = useRef<HTMLInputElement | null>(null);
  const errorHeadingRef = useRef<HTMLHeadingElement | null>(null);
  const composingRef = useRef(false);
  const handledSuccessRef = useRef(0);
  const identityRef = useRef({
    session: authState.status === "authenticated" ? authState.session : null,
    caseId: detail.caseId,
  });
  const recordIdentityRef = useRef({
    caseStatus: detail.caseStatus,
    assigneeRef: detail.assigneeRef,
    version: detail.concurrencyVersion,
  });
  const { state, submit, reset, retryReconciliation, busy } = useCaseWorkflowMutations({
    caseId: detail.caseId,
    detail,
    reconciliationGeneration,
    detailRefreshState,
    onReconcile,
  });

  useEffect(() => {
    const session = authState.status === "authenticated" ? authState.session : null;
    const previous = identityRef.current;
    if (previous.session !== session || previous.caseId !== detail.caseId) {
      identityRef.current = { session, caseId: detail.caseId };
      setAssigneeDraft("");
      reset();
    }
  }, [authState, detail.caseId, reset]);

  // A new authoritative status/version invalidates the old action selection.
  // The UUID draft remains visible but is never submitted automatically.
  useEffect(() => {
    const previous = recordIdentityRef.current;
    if (
      previous.caseStatus !== detail.caseStatus ||
      previous.assigneeRef !== detail.assigneeRef ||
      previous.version !== detail.concurrencyVersion
    ) {
      recordIdentityRef.current = {
        caseStatus: detail.caseStatus,
        assigneeRef: detail.assigneeRef,
        version: detail.concurrencyVersion,
      };
      reset();
    }
  }, [detail.caseStatus, detail.assigneeRef, detail.concurrencyVersion, reset]);

  useEffect(() => {
    if (state.status === "validation-error" && state.field === "assignee") {
      assigneeInputRef.current?.focus();
      return;
    }
    if (isRequestFailure(state)) {
      errorHeadingRef.current?.focus();
      return;
    }
    if (state.status === "success" && handledSuccessRef.current !== state.submission) {
      handledSuccessRef.current = state.submission;
      let active = true;
      queueMicrotask(() => {
        if (!active || handledSuccessRef.current !== state.submission) {
          return;
        }
        if (
          state.notice.action === "start-review" ||
          state.notice.action === "change-assignee" ||
          state.notice.action === "release-assignee"
        ) {
          setAssigneeDraft("");
        }
        const target = sectionRef.current?.querySelector<HTMLElement>(
          "[data-workflow-focus-target]:not(:disabled)",
        );
        target?.focus();
      });
      return () => {
        active = false;
      };
    }
  }, [state]);

  if (!capabilities.has("case:workflow")) {
    return null;
  }

  const invalidAssignee = state.status === "validation-error" && state.field === "assignee";
  const failure = failureCopy(state);
  const describedBy = `${ASSIGNEE_HELPER_ID}${invalidAssignee ? ` ${ASSIGNEE_ERROR_ID}` : ""}`;
  const currentAssignee = detail.assigneeRef;

  const changeDraft = (value: string) => {
    setAssigneeDraft(value);
    reset();
  };

  const protectComposition = (event: KeyboardEvent<HTMLInputElement>) => {
    if (
      event.key === "Enter" &&
      (event.repeat || event.nativeEvent.isComposing || composingRef.current)
    ) {
      event.preventDefault();
    }
  };

  const submitAssignee = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    submit(
      detail.caseStatus === "OPEN"
        ? { kind: "start-review", assigneeRef: assigneeDraft }
        : { kind: "change-assignee", assigneeRef: assigneeDraft },
    );
  };

  return (
    <section
      ref={sectionRef}
      className="panel case-workflow"
      aria-labelledby="case-workflow-heading"
      aria-busy={busy || undefined}
    >
      <h3 id="case-workflow-heading">Case workflow</h3>
      <p className="case-workflow__summary">
        Current status: <strong>{CASE_STATUS_LABELS[detail.caseStatus]}</strong>
      </p>

      {currentAssignee !== null && (
        <p className="case-workflow__assignee">
          Current assignee: <code>{currentAssignee}</code>
        </p>
      )}

      {detail.caseStatus === "CLOSED" ? (
        <p className="notice notice--empty case-workflow__unavailable">
          Workflow changes are unavailable for a closed case.
        </p>
      ) : (
        <div className="case-workflow__controls">
          {detail.caseStatus === "IN_REVIEW" && (
            <fieldset className="case-workflow__group">
              <legend>Review status</legend>
              <p className="case-workflow__helper">
                Move the case to additional information required. The audit reason is fixed by this action.
              </p>
              <button
                className="button"
                type="button"
                data-workflow-focus-target
                disabled={busy}
                onClick={() => submit({ kind: "request-additional-information" })}
              >
                Request additional information
              </button>
            </fieldset>
          )}

          {detail.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" && (
            <fieldset className="case-workflow__group">
              <legend>Review status</legend>
              {currentAssignee === null ? (
                <p className="case-workflow__helper">
                  Assign an analyst before resuming review.
                </p>
              ) : (
                <>
                  <p className="case-workflow__helper">
                    Resume review with the current assignee. The audit reason is fixed by this action.
                  </p>
                  <button
                    className="button"
                    type="button"
                    data-workflow-focus-target
                    disabled={busy}
                    onClick={() => submit({ kind: "resume-review" })}
                  >
                    Resume review
                  </button>
                </>
              )}
            </fieldset>
          )}

          <form className="case-workflow__form" onSubmit={submitAssignee} noValidate>
            <fieldset className="case-workflow__group" disabled={busy}>
              <legend>{detail.caseStatus === "OPEN" ? "Start review" : "Assignee"}</legend>
              <label htmlFor={ASSIGNEE_INPUT_ID}>Assignee UUID</label>
              <p id={ASSIGNEE_HELPER_ID} className="case-workflow__helper">
                {assigneeHelper(detail)}
              </p>
              <input
                id={ASSIGNEE_INPUT_ID}
                ref={assigneeInputRef}
                className="case-workflow__input"
                type="text"
                inputMode="text"
                autoComplete="off"
                spellCheck={false}
                required={detail.caseStatus === "OPEN"}
                value={assigneeDraft}
                aria-describedby={describedBy}
                aria-invalid={invalidAssignee || undefined}
                data-workflow-focus-target={detail.caseStatus === "OPEN" ? true : undefined}
                onChange={(event) => changeDraft(event.target.value)}
                onKeyDown={protectComposition}
                onCompositionStart={() => {
                  composingRef.current = true;
                }}
                onCompositionEnd={() => {
                  composingRef.current = false;
                }}
              />
              {invalidAssignee && (
                <p id={ASSIGNEE_ERROR_ID} className="form-error">
                  Enter a different canonical lowercase UUID v4 without leading or trailing whitespace.
                </p>
              )}
              <div className="case-workflow__actions">
                <button
                  className="button button--primary"
                  type="submit"
                  data-workflow-focus-target={detail.caseStatus !== "OPEN" ? true : undefined}
                  disabled={busy}
                >
                  {detail.caseStatus === "OPEN"
                    ? "Start review"
                    : currentAssignee === null
                      ? "Assign analyst"
                      : "Change assignee"}
                </button>
                {detail.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" &&
                  currentAssignee !== null && (
                    <button
                      className="button"
                      type="button"
                      disabled={busy}
                      onClick={() => submit({ kind: "release-assignee" })}
                    >
                      Release assignee
                    </button>
                  )}
              </div>
            </fieldset>
          </form>
        </div>
      )}

      {state.status === "reconciling" && (
        <div className="notice notice--empty case-workflow__reconciling">
          <p className="notice__title">Refreshing authoritative case information</p>
          <p className="notice__body">
            Workflow actions remain unavailable until the latest case record is confirmed.
          </p>
          {detailRefreshState === "failed" && (
            <button className="button" type="button" onClick={retryReconciliation}>
              Refresh workflow information
            </button>
          )}
        </div>
      )}

      {failure !== null && (
        <div className="notice notice--error case-workflow__feedback" role="alert">
          <h4 ref={errorHeadingRef} tabIndex={-1} className="notice__title">
            {failure.title}
          </h4>
          <p className="notice__body">{failure.body}</p>
        </div>
      )}

      <p
        className="case-workflow__live"
        role="status"
        aria-live="polite"
        aria-atomic="true"
        aria-label="Case workflow result"
      >
        {state.status === "success" ? SUCCESS_MESSAGES[state.notice.action] : ""}
      </p>

    </section>
  );
}
