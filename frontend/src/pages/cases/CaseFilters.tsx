import { CASE_FINAL_DISPOSITIONS, CASE_STATUSES } from "../../api/caseApi";
import {
  CASE_FINAL_DISPOSITION_LABELS,
  CASE_STATUS_LABELS,
  MAX_ASSIGNEE_REF_LENGTH,
  type CaseFilterDraft,
} from "./casePresentation";
import { Icon } from "../../shared/Icon";

export interface CaseFiltersProps {
  readonly draft: CaseFilterDraft;
  readonly onDraftChange: (draft: CaseFilterDraft) => void;
  readonly onApply: () => void;
  readonly onReset: () => void;
  /** True while committed filters differ from the draft on screen. */
  readonly hasPendingEdits: boolean;
}

export function CaseFilters({
  draft,
  onDraftChange,
  onApply,
  onReset,
  hasPendingEdits,
}: CaseFiltersProps) {
  const hasTimeRange = Boolean(
    draft.createdAtFrom || draft.createdAtTo || draft.lastChangedAtFrom || draft.lastChangedAtTo,
  );
  const update = <TKey extends keyof CaseFilterDraft>(key: TKey, value: string): void => {
    onDraftChange({ ...draft, [key]: value });
  };

  return (
    // A form, so Enter in any field applies the filters the way an analyst
    // working from the keyboard expects. Submission is intercepted: nothing
    // navigates, and no filter value is ever encoded into a URL.
    <form
      className="filters"
      aria-labelledby="case-filters-heading"
      onSubmit={(event) => {
        event.preventDefault();
        onApply();
      }}
    >
      <h3 id="case-filters-heading" className="filters__title">검색 및 필터</h3>
      <details className="filters__advanced">
        <summary>기간 필터 <span>{hasTimeRange ? "기간 설정됨" : "생성·변경 기간 선택"}</span></summary>
        <div className="filters__time-grid">
        {/*
          Two independent ranges, in two fieldsets rather than one. Backend
          validates `[createdAtFrom, createdAtTo)` and
          `[lastChangedAtFrom, lastChangedAtTo)` separately, and an analyst
          filling in one has to be able to see that the other is untouched.
        */}
        <fieldset className="field-group field-group--wide">
          <legend className="field-group__legend">생성 기간</legend>
          <div className="field-group__fields field-group__fields--pair">
            <div className="field">
              <label className="field__label" htmlFor="case-filter-created-from">
                생성 시작(KST)
              </label>
              <input
                className="field__control"
                id="case-filter-created-from"
                type="datetime-local"
                step="60"
                value={draft.createdAtFrom}
                onChange={(event) => {
                  update("createdAtFrom", event.target.value);
                }}
              />
            </div>
            <div className="field">
              <label className="field__label" htmlFor="case-filter-created-to">
                생성 끝(KST)
              </label>
              <input
                className="field__control"
                id="case-filter-created-to"
                type="datetime-local"
                step="60"
                value={draft.createdAtTo}
                onChange={(event) => {
                  update("createdAtTo", event.target.value);
                }}
              />
            </div>
          </div>
          <p className="field__hint">
            한국 표준시(UTC+09:00)로 입력하며 서버에는 UTC로 전달합니다.
          </p>
        </fieldset>

        <fieldset className="field-group field-group--wide">
          <legend className="field-group__legend">변경 기간</legend>
          <div className="field-group__fields field-group__fields--pair">
            <div className="field">
              <label className="field__label" htmlFor="case-filter-changed-from">
                변경 시작(KST)
              </label>
              <input
                className="field__control"
                id="case-filter-changed-from"
                type="datetime-local"
                step="60"
                value={draft.lastChangedAtFrom}
                onChange={(event) => {
                  update("lastChangedAtFrom", event.target.value);
                }}
              />
            </div>
            <div className="field">
              <label className="field__label" htmlFor="case-filter-changed-to">
                변경 끝(KST)
              </label>
              <input
                className="field__control"
                id="case-filter-changed-to"
                type="datetime-local"
                step="60"
                value={draft.lastChangedAtTo}
                onChange={(event) => {
                  update("lastChangedAtTo", event.target.value);
                }}
              />
            </div>
          </div>
          <p className="field__hint">
            생성 기간과 별도로 적용할 수 있습니다.
          </p>
        </fieldset>
        </div>
      </details>
      <div className="filters__grid">
        <fieldset className="field-group">
          <legend className="field-group__legend">조사 상태</legend>
          <div className="field-group__fields">
            <div className="field">
              <label className="field__label" htmlFor="case-filter-status">
                사건 상태
              </label>
              <select
                className="field__control"
                id="case-filter-status"
                value={draft.caseStatus}
                onChange={(event) => {
                  update("caseStatus", event.target.value);
                }}
              >
                <option value="">모든 상태</option>
                {CASE_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {CASE_STATUS_LABELS[status]}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label className="field__label" htmlFor="case-filter-disposition">
                최종 판정
              </label>
              <select
                className="field__control"
                id="case-filter-disposition"
                value={draft.finalDisposition}
                onChange={(event) => {
                  update("finalDisposition", event.target.value);
                }}
              >
                <option value="">모든 판정</option>
                {CASE_FINAL_DISPOSITIONS.map((disposition) => (
                  <option key={disposition} value={disposition}>
                    {CASE_FINAL_DISPOSITION_LABELS[disposition]}
                  </option>
                ))}
              </select>
            </div>
          </div>
        </fieldset>

        <fieldset className="field-group">
          <legend className="field-group__legend">참조값</legend>
          <div className="field-group__fields">
            <div className="field">
              <label className="field__label" htmlFor="case-filter-assignee-ref">
                담당자 참조값
              </label>
              {/*
                No `maxLength`. Backend's 128-character bound is reported as a
                refusal the analyst can read rather than enforced by silently
                swallowing the 129th keystroke, which would leave a pasted
                reference looking complete when it is not.
              */}
              <input
                className="field__control"
                id="case-filter-assignee-ref"
                type="text"
                inputMode="text"
                autoComplete="off"
                spellCheck={false}
                value={draft.assigneeRef}
                onChange={(event) => {
                  update("assigneeRef", event.target.value);
                }}
              />
              <p className="field__hint">
                정확히 일치시킵니다. 최대 {MAX_ASSIGNEE_REF_LENGTH} 자까지 입력하고 앞뒤 공백은 제외하세요.
              </p>
            </div>
            <div className="field">
              <label className="field__label" htmlFor="case-filter-transaction-id">
                연관 거래 ID
              </label>
              <input
                className="field__control"
                id="case-filter-transaction-id"
                type="text"
                inputMode="text"
                autoComplete="off"
                spellCheck={false}
                value={draft.transactionId}
                onChange={(event) => {
                  update("transactionId", event.target.value);
                }}
              />
              <p className="field__hint">
                거래 기록에 저장된 소문자 UUID를 입력하세요.
              </p>
            </div>
          </div>
        </fieldset>
      </div>

      <div className="filters__actions">
        <button className="button button--primary" type="submit">
          <Icon name="search" />필터 적용
        </button>
        <button className="button" type="button" onClick={onReset}>
          <Icon name="reset" />필터 초기화
        </button>
        <p className="filters__note">
          {hasPendingEdits
            ? "변경한 조건은 아직 적용되지 않았습니다."
            : "적용된 필터의 결과를 표시합니다."}
        </p>
      </div>
    </form>
  );
}
