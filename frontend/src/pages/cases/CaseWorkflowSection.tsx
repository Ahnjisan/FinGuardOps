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
import { Icon } from "../../shared/Icon";
import { CASE_FINAL_DISPOSITION_LABELS, CASE_STATUS_LABELS } from "./casePresentation";

const ASSIGNEE_INPUT_ID = "case-workflow-assignee";
const ASSIGNEE_HELPER_ID = "case-workflow-assignee-helper";
const ASSIGNEE_ERROR_ID = "case-workflow-assignee-error";
const RESOLUTION_LABEL_ID = "case-resolution-disposition-label";
const RESOLUTION_HELPER_ID = "case-resolution-helper";
const RESOLUTION_ERROR_ID = "case-resolution-error";
const RESOLUTION_RADIO_NAME = "case-resolution-disposition";

const SUCCESS_MESSAGES: Readonly<Record<CaseWorkflowActionKind, string>> = Object.freeze({
  "start-review": "최신 사건 정보에서 검토 시작을 확인했습니다.",
  "request-additional-information":
    "최신 사건 정보에서 추가 정보 요청을 확인했습니다.",
  "resume-review": "최신 사건 정보에서 검토 재개를 확인했습니다.",
  "change-assignee": "최신 사건 정보에서 담당자 변경을 확인했습니다.",
  "release-assignee": "최신 사건 정보에서 담당자 배정 해제를 확인했습니다.",
  "resolve-case": "최신 사건 정보에서 사건 종결을 확인했습니다.",
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
        title: "종결 전 사건 정보가 변경되었습니다",
        body: "최신 사건 정보를 확인한 뒤 종결을 다시 제출하세요.",
      };
    case "ambiguous":
      return {
        title: "종결 결과를 확인할 수 없습니다",
        body: "최신 사건 정보를 불러왔습니다. 확인한 뒤 종결을 다시 제출하세요.",
      };
    case "reconciling":
      return state.result === "unconfirmed"
        ? {
            title: "최신 사건 기록에서 종결을 확인할 수 없습니다",
            body:
              "최신 사건 기록에서 제출한 최종 판정을 확인할 수 없습니다. " +
              "다른 작업 전에 사건 정보를 새로고침하세요.",
          }
        : null;
    case "forbidden":
      return {
        title: "사건 종결이 거부되었습니다",
        body: "세션은 유지됩니다. 권한 있는 담당자에게 사건 종결을 요청하세요.",
      };
    case "not-found":
      return {
        title: "사건을 더 이상 사용할 수 없습니다",
        body: "사건 목록으로 돌아가 상태를 확인하세요.",
      };
    case "authentication-required":
      return {
        title: "세션이 종료되었습니다",
        body: "사건을 종결하려면 다시 로그인하세요.",
      };
    case "request-rejected":
      return {
        title: "종결 요청을 보내지 않았습니다",
        body: "현재 사건 상태를 확인하고 가능하면 종결을 다시 제출하세요.",
      };
    case "server-error":
      return {
        title: "사건을 종결할 수 없습니다",
        body: "백엔드가 종결을 완료하지 못했습니다. 선택한 최종 판정은 유지됩니다.",
      };
    case "validation-error":
      return state.field === "action"
        ? {
            title: "이 사건은 종결할 수 없습니다",
            body: "종결 전 현재 사건 상태를 확인하세요.",
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
        title: "작업 전 사건 정보가 변경되었습니다",
        body: "최신 사건 정보를 확인한 뒤 작업을 다시 제출하세요.",
      };
    case "ambiguous":
      return {
        title: "업무 처리 결과를 확인할 수 없습니다",
        body: "최신 사건 정보를 불러왔습니다. 확인한 뒤 다시 제출하세요.",
      };
    case "forbidden":
      return {
        title: "업무 처리가 거부되었습니다",
        body: "세션은 유지됩니다. 권한 있는 분석 담당자에게 처리를 요청하세요.",
      };
    case "not-found":
      return {
        title: "사건을 더 이상 사용할 수 없습니다",
        body: "다른 작업 전에 사건 목록에서 상태를 확인하세요.",
      };
    case "authentication-required":
      return {
        title: "세션이 종료되었습니다",
        body: "다른 작업을 하려면 다시 로그인하세요.",
      };
    case "request-rejected":
      return {
        title: "업무 처리 요청을 보내지 않았습니다",
        body: "현재 사건을 확인하고 가능한 작업을 다시 제출하세요.",
      };
    case "server-error":
      return {
        title: "업무 처리를 완료할 수 없습니다",
        body: "백엔드가 작업을 완료하지 못했습니다. 담당자 입력값은 유지됩니다.",
      };
    case "validation-error":
      return state.field === "action"
        ? {
            title: "이 작업은 더 이상 사용할 수 없습니다",
            body: "현재 사건 상태를 확인하고 가능한 작업을 선택하세요.",
          }
        : null;
    default:
      return null;
  }
}

function assigneeHelper(detail: CaseDetail): string {
  if (detail.caseStatus === "OPEN") {
    return "검토를 시작하려면 발급된 소문자 UUID v4를 정확히 입력하세요.";
  }
  return "다른 소문자 UUID v4를 입력하세요. 공백과 대문자는 자동 수정되지 않습니다.";
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
        사건 처리
      </h3>
      <p className="case-workflow__summary">
        현재 상태: <strong>{CASE_STATUS_LABELS[detail.caseStatus]}</strong>
      </p>

      {currentAssignee !== null && (
        <p className="case-workflow__assignee">
          현재 담당자: <code>{currentAssignee}</code>
        </p>
      )}

      {detail.caseStatus === "CLOSED" ? (
        <p className="notice notice--empty case-workflow__unavailable">
          종결된 사건은 상태를 변경할 수 없습니다.
        </p>
      ) : (
        <>
          {mayChangeWorkflow && (
            <div className="case-workflow__controls">
              {detail.caseStatus === "IN_REVIEW" && (
                <fieldset className="case-workflow__group">
                  <legend>검토 상태</legend>
                  <p className="case-workflow__helper">
                    추가 정보 필요 상태로 변경합니다. 감사 사유는 작업에 따라 정해집니다.
                  </p>
                  <button
                    className="button"
                    type="button"
                    data-workflow-focus-target
                    disabled={busy}
                    onClick={() => submit({ kind: "request-additional-information" })}
                  >
                    추가 정보 요청
                  </button>
                </fieldset>
              )}

              {detail.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" && (
                <fieldset className="case-workflow__group">
                  <legend>검토 상태</legend>
                  {currentAssignee === null ? (
                    <p className="case-workflow__helper">
                      검토를 재개하려면 담당자를 배정하세요.
                    </p>
                  ) : (
                    <>
                      <p className="case-workflow__helper">
                        현재 담당자로 검토를 재개합니다. 감사 사유는 작업에 따라 정해집니다.
                      </p>
                      <button
                        className="button"
                        type="button"
                        data-workflow-focus-target
                        disabled={busy}
                        onClick={() => submit({ kind: "resume-review" })}
                      >
                        검토 재개
                      </button>
                    </>
                  )}
                </fieldset>
              )}

              <form className="case-workflow__form" onSubmit={submitAssignee} noValidate>
                <fieldset className="case-workflow__group" disabled={busy}>
                  <legend>{detail.caseStatus === "OPEN" ? "검토 시작" : "담당자"}</legend>
                  <label htmlFor={ASSIGNEE_INPUT_ID}>담당자 UUID</label>
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
                      앞뒤 공백 없이 다른 소문자 UUID v4를 입력하세요.
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
                        ? "검토 시작"
                        : currentAssignee === null
                          ? "담당자 배정"
                          : "담당자 변경"}
                    </button>
                    {detail.caseStatus === "ADDITIONAL_INFORMATION_REQUIRED" &&
                      currentAssignee !== null && (
                        <button
                          className="button"
                          type="button"
                          disabled={busy}
                          onClick={() => submit({ kind: "release-assignee" })}
                        >
                          담당자 배정 해제
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
                  <legend>사건 종결</legend>
                  <p id={RESOLUTION_HELPER_ID} className="case-workflow__helper">
                    최종 판정을 선택하세요. 사건을 종결하면 되돌릴 수 없습니다. 감사 사유는 CASE_RESOLUTION_COMPLETED로 기록됩니다.
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
                      최종 판정
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
                      사건을 종결하려면 최종 판정을 선택하세요.
                    </p>
                  )}
                  <div className="case-workflow__actions">
                    <button className="button button--primary" type="submit" disabled={busy}>
                      사건 종결
                    </button>
                  </div>
                </fieldset>
              </form>
            ) : (
              <p className="notice notice--empty case-workflow__unavailable">
                현재 사건 상태에서는 종결할 수 없습니다.
              </p>
            ))}
        </>
      )}

      {state.status === "reconciling" && !unconfirmed && (
        <div className="notice notice--empty case-workflow__reconciling">
          <p className="notice__title">최신 사건 정보를 확인하고 있습니다</p>
          <p className="notice__body">
            최신 사건 기록이 확인될 때까지 작업할 수 없습니다.
          </p>
          {detailRefreshState === "failed" && (
            <button className="button" type="button" onClick={retryReconciliation}>
              <Icon name="refresh" />사건 처리 정보 새로고침
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
              <Icon name="refresh" />사건 처리 정보 새로고침
            </button>
          )}
        </div>
      )}

      <p
        className="case-workflow__live"
        role="status"
        aria-live="polite"
        aria-atomic="true"
        aria-label="사건 처리 결과"
      >
        {state.status === "success" ? SUCCESS_MESSAGES[state.notice.action] : ""}
      </p>

    </section>
  );
}
