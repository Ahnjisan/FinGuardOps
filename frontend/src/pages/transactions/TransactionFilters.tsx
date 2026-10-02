import {
  TRANSACTION_PROCESSING_STATUSES,
  TRANSACTION_TYPES,
} from "../../api/transactionApi";
import {
  PROCESSING_STATUS_LABELS,
  TRANSACTION_TYPE_LABELS,
  type TransactionFilterDraft,
} from "./transactionPresentation";
import { Icon } from "../../shared/Icon";

export interface TransactionFiltersProps {
  readonly draft: TransactionFilterDraft;
  readonly appliedDraft: TransactionFilterDraft;
  readonly onDraftChange: (draft: TransactionFilterDraft) => void;
  readonly onApply: () => void;
  readonly onReset: () => void;
  /** True while committed filters differ from the draft on screen. */
  readonly hasPendingEdits: boolean;
}

export function TransactionFilters({
  draft,
  appliedDraft,
  onDraftChange,
  onApply,
  onReset,
  hasPendingEdits,
}: TransactionFiltersProps) {
  const hasAppliedTimeRange = Boolean(appliedDraft.occurredAtFrom || appliedDraft.occurredAtTo);
  const update = <TKey extends keyof TransactionFilterDraft>(
    key: TKey,
    value: string,
  ): void => {
    onDraftChange({ ...draft, [key]: value });
  };

  return (
    // A form, so Enter in any field applies the filters the way an analyst
    // working from the keyboard expects. Submission is intercepted: nothing
    // navigates, and no filter value is ever encoded into a URL.
    <form
      className="filters"
      aria-labelledby="filters-heading"
      onSubmit={(event) => {
        event.preventDefault();
        onApply();
      }}
    >
      <h3 id="filters-heading" className="filters__title">검색 및 필터</h3>
      <details className="filters__advanced">
        <summary>
          기간 필터 <span>{hasAppliedTimeRange
            ? `적용됨: ${appliedDraft.occurredAtFrom ? `${appliedDraft.occurredAtFrom.replace("T", " ")}부터` : "시작 제한 없음"} · ${appliedDraft.occurredAtTo ? `${appliedDraft.occurredAtTo.replace("T", " ")} 전까지` : "끝 제한 없음"} (KST)`
            : "발생 기간 선택"}</span>
        </summary>
        <div className="filters__time-grid">
        <fieldset className="field-group field-group--wide">
          <legend className="field-group__legend">발생 기간</legend>
          <div className="field-group__fields field-group__fields--pair">
            <div className="field">
              <label className="field__label" htmlFor="filter-occurred-from">
                시작(KST)
              </label>
              <input
                className="field__control"
                id="filter-occurred-from"
                type="datetime-local"
                step="60"
                value={draft.occurredAtFrom}
                onChange={(event) => {
                  update("occurredAtFrom", event.target.value);
                }}
              />
            </div>
            <div className="field">
              <label className="field__label" htmlFor="filter-occurred-to">
                끝(KST)
              </label>
              <input
                className="field__control"
                id="filter-occurred-to"
                type="datetime-local"
                step="60"
                value={draft.occurredAtTo}
                onChange={(event) => {
                  update("occurredAtTo", event.target.value);
                }}
              />
            </div>
          </div>
          <p className="field__hint">
            한국 표준시(UTC+09:00)로 입력하며 서버에는 UTC로 전달합니다.
          </p>
        </fieldset>
        </div>
      </details>
      <div className="filters__grid">

        <fieldset className="field-group">
          <legend className="field-group__legend">분류</legend>
          <div className="field-group__fields">
            <div className="field">
              <label className="field__label" htmlFor="filter-transaction-type">
                거래 유형
              </label>
              <select
                className="field__control"
                id="filter-transaction-type"
                value={draft.transactionType}
                onChange={(event) => {
                  update("transactionType", event.target.value);
                }}
              >
                <option value="">모든 유형</option>
                {TRANSACTION_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {TRANSACTION_TYPE_LABELS[type]}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label className="field__label" htmlFor="filter-processing-status">
                처리 상태
              </label>
              <select
                className="field__control"
                id="filter-processing-status"
                value={draft.processingStatus}
                onChange={(event) => {
                  update("processingStatus", event.target.value);
                }}
              >
                <option value="">모든 상태</option>
                {TRANSACTION_PROCESSING_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {PROCESSING_STATUS_LABELS[status]}
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
              <label className="field__label" htmlFor="filter-customer-ref">
                고객 참조값
              </label>
              <input
                className="field__control"
                id="filter-customer-ref"
                type="text"
                inputMode="text"
                autoComplete="off"
                spellCheck={false}
                value={draft.externalCustomerRef}
                onChange={(event) => {
                  update("externalCustomerRef", event.target.value);
                }}
              />
            </div>
            <div className="field">
              <label className="field__label" htmlFor="filter-account-ref">
                계좌 참조값
              </label>
              <input
                className="field__control"
                id="filter-account-ref"
                type="text"
                inputMode="text"
                autoComplete="off"
                spellCheck={false}
                value={draft.accountRef}
                onChange={(event) => {
                  update("accountRef", event.target.value);
                }}
              />
            </div>
            <p className="field__hint">
              공백과 대소문자를 포함해 정확히 일치시킵니다.
            </p>
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
