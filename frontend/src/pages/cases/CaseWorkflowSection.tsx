import {
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import {
  CASE_FINAL_DISPOSITIONS,
  isResolvableCaseDetail,
  type CaseDetail,
  type CaseFinalDisposition,
} from "../../api/caseApi";
import {
  useCaseWorkflowMutations,
  type CaseWorkflowActionKind,
  type CaseWorkflowMutationState,
  type CaseWorkflowReconciliationScope,
} from "../../api/useCaseWorkflowMutations";
import { useAuth } from "../../auth/useAuth";
import { useCapabilities } from "../../auth/useCapabilities";
import { CASE_FINAL_DISPOSITION_LABELS, CASE_STATUS_LABELS } from "./casePresentation";

const ASSIGNEE_INPUT_ID = "case-workflow-assignee";
const ASSIGNEE_HELPER_ID = "case-workflow-assignee-helper";
const ASSIGNEE_ERROR_ID = "case-workflow-assignee-error";
const RESOLUTION_LABEL_ID = "case-resolution-disposition-label";
const RESOLUTION_HELPER_ID = "case-resolution-helper";
const RESOLUTION_ERROR_ID = "case-resolution-error";
const RESOLUTION_RADIO_NAME = "case-resolution-disposition";

const SUCCESS_MESSAGES: Readonly<Record<CaseWorkflowActionKind, string>> = Object.freeze({
  "start-review": "Review started from authoritative case information.",
  "request-additional-information":
    "Additional information requested from authoritative case information.",
  "resume-review": "Review resumed from authoritative case information.",
  "change-assignee": "Assignee updated from authoritative case information.",
  "release-assignee": "Assignee released from authoritative case information.",
  "resolve-case": "Case resolved from authoritative case information.",
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

interface FailureCopy {
  readonly title: string;
  readonly body: string;
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
    (state.status === "validation-error" && state.field === "action") ||
    (state.status === "reconciling" && state.result === "unconfirmed")
  );
}

/**
 * 최종 판정 전용 고정 오류 문구. 선택한 판정, Backend code·message, trace를 담지 않는다.
 */
function resolutionFailureCopy(state: CaseWorkflowMutationState): FailureCopy | null {
  switch (state.status) {
    case "conflict":
      return {
        title: "The case changed before the resolution",
        body: "Review the latest case information, then submit the resolution again.",
      };
    case "ambiguous":
      return {
        title: "The resolution result could not be confirmed",
        body: "The latest case information has been loaded. Review it before submitting a resolution again.",
      };
    case "reconciling":
      return state.result === "unconfirmed"
        ? {
            title: "The resolution is not confirmed by the latest case record",
            body:
              "The latest case record does not show the submitted final disposition. Refresh the " +
              "case information before taking another action.",
          }
        : null;
    case "forbidden":
      return {
        title: "The resolution was denied",
        body: "Your session is unchanged. Ask an authorized approver to resolve this case.",
      };
    case "not-found":
      return {
        title: "The case is no longer available",
        body: "Return to the case list before resolving a case.",
      };
    case "authentication-required":
      return {
        title: "Your session ended",
        body: "Sign in again before resolving the case.",
      };
    case "request-rejected":
      return {
        title: "The resolution was not sent",
        body: "Review the current case and submit the resolution again if it is still available.",
      };
    case "server-error":
      return {
        title: "The resolution could not be completed",
        body: "The backend did not complete this resolution. Your selected disposition has been kept.",
      };
    case "validation-error":
      return state.field === "action"
        ? {
            title: "This resolution is no longer available",
            body: "Review the current case state before resolving it.",
          }
        : null;
    default:
      return null;
  }
}

function failureCopy(state: CaseWorkflowMutationState): FailureCopy | null {
  if (state.status !== "idle" && state.notice.action === "resolve-case") {
    return resolutionFailureCopy(state);
  }
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
  const [dispositionDraft, setDispositionDraft] = useState<CaseFinalDisposition | null>(null);
  const sectionRef = useRef<HTMLElement | null>(null);
  const headingRef = useRef<HTMLHeadingElement | null>(null);
  const assigneeInputRef = useRef<HTMLInputElement | null>(null);
  const firstDispositionRef = useRef<HTMLInputElement | null>(null);
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
  const mayChangeWorkflow = capabilities.has("case:workflow");
  const mayResolve = capabilities.has("case:resolve");
  // 판정 form은 capability와 Backend 사전조건을 옮긴 detail 판정이 모두 참일 때만 존재한다.
  const resolvable = mayResolve && isResolvableCaseDetail(detail);
  const [draftResolvable, setDraftResolvable] = useState(resolvable);
  // case:resolve 상실이나 종결 가능 조건 상실(CLOSED 수신 포함)은 판정 draft를 버린다. 종결 가능
  // 조건을 유지하는 version·담당자 refresh와 요청 실패는 draft를 보존하며, 자동 제출은 없다.
  // effect가 아니라 렌더 중 직전 판정과 비교해 "가능 → 불가" 전이에서만 state를 조정한다.
  if (draftResolvable !== resolvable) {
    setDraftResolvable(resolvable);
    if (!resolvable) {
      setDispositionDraft(null);
    }
  }
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
      setDispositionDraft(null);
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
    if (state.status === "validation-error" && state.field === "disposition") {
      // 판정을 고르지 않은 제출은 첫 radio로 돌아가 keyboard 사용자가 바로 고를 수 있게 한다.
      firstDispositionRef.current?.focus();
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
        if (state.notice.action === "resolve-case") {
          setDispositionDraft(null);
        }
        const target = sectionRef.current?.querySelector<HTMLElement>(
          "[data-workflow-focus-target]:not(:disabled)",
        );
        // 성공 뒤 사용할 control이 남지 않으면(종결로 form이 사라진 경우) section heading으로 돌아간다.
        (target ?? headingRef.current)?.focus();
      });
      return () => {
        active = false;
      };
    }
  }, [state]);

  if (!mayChangeWorkflow && !mayResolve) {
    return null;
  }

  const invalidAssignee = state.status === "validation-error" && state.field === "assignee";
  const invalidDisposition = state.status === "validation-error" && state.field === "disposition";
  const unconfirmed = state.status === "reconciling" && state.result === "unconfirmed";
  const failure = failureCopy(state);
  const describedBy = `${ASSIGNEE_HELPER_ID}${invalidAssignee ? ` ${ASSIGNEE_ERROR_ID}` : ""}`;
  const resolutionDescribedBy = `${RESOLUTION_HELPER_ID}${
    invalidDisposition ? ` ${RESOLUTION_ERROR_ID}` : ""
  }`;
  const currentAssignee = detail.assigneeRef;

  const changeDraft = (value: string) => {
    setAssigneeDraft(value);
    reset();
  };

  const changeDisposition = (value: CaseFinalDisposition) => {
    setDispositionDraft(value);
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

  // 별도 browser confirm 없이 form 안의 되돌릴 수 없음 안내와 명시적 제출로 판정한다.
  const submitResolution = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    submit({ kind: "resolve-case", finalDisposition: dispositionDraft });
  };

  return (
    <section
      ref={sectionRef}
      className="panel case-workflow"
      aria-labelledby="case-workflow-heading"
      aria-busy={busy || undefined}
    >
      <h3 id="case-workflow-heading" ref={headingRef} tabIndex={-1}>
        Case workflow
      </h3>
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
        <>
          {mayChangeWorkflow && (
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

          {mayResolve &&
            (resolvable ? (
              <form className="case-workflow__form case-resolution" onSubmit={submitResolution} noValidate>
                <fieldset className="case-workflow__group" disabled={busy}>
                  <legend>Case resolution</legend>
                  <p id={RESOLUTION_HELPER_ID} className="case-workflow__helper">
                    Choose the final disposition. Resolving closes the case and cannot be undone. The
                    audit reason is fixed as CASE_RESOLUTION_COMPLETED.
                  </p>
                  <div
                    className="case-resolution__options"
                    role="radiogroup"
                    aria-labelledby={RESOLUTION_LABEL_ID}
                    aria-describedby={resolutionDescribedBy}
                    aria-required="true"
                    aria-invalid={invalidDisposition || undefined}
                  >
                    <p id={RESOLUTION_LABEL_ID} className="case-resolution__label">
                      Final disposition
                    </p>
                    {CASE_FINAL_DISPOSITIONS.map((disposition, index) => (
                      <label key={disposition} className="case-resolution__option">
                        <input
                          ref={index === 0 ? firstDispositionRef : undefined}
                          type="radio"
                          name={RESOLUTION_RADIO_NAME}
                          value={disposition}
                          checked={dispositionDraft === disposition}
                          onChange={() => changeDisposition(disposition)}
                        />
                        <span>{CASE_FINAL_DISPOSITION_LABELS[disposition]}</span>
                      </label>
                    ))}
                  </div>
                  {invalidDisposition && (
                    <p id={RESOLUTION_ERROR_ID} className="form-error">
                      Choose a final disposition before resolving the case.
                    </p>
                  )}
                  <div className="case-workflow__actions">
                    <button className="button button--primary" type="submit" disabled={busy}>
                      Resolve case
                    </button>
                  </div>
                </fieldset>
              </form>
            ) : (
              <p className="notice notice--empty case-workflow__unavailable">
                Case resolution is not available for the current case state.
              </p>
            ))}
        </>
      )}

      {state.status === "reconciling" && !unconfirmed && (
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
          {unconfirmed && (
            <button
              className="button"
              type="button"
              disabled={detailRefreshState === "refreshing"}
              onClick={retryReconciliation}
            >
              Refresh workflow information
            </button>
          )}
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
