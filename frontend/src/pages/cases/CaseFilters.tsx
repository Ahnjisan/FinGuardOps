import { CASE_FINAL_DISPOSITIONS, CASE_STATUSES } from "../../api/caseApi";
import {
  CASE_FINAL_DISPOSITION_LABELS,
  CASE_STATUS_LABELS,
  MAX_ASSIGNEE_REF_LENGTH,
  type CaseFilterDraft,
} from "./casePresentation";

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
      <h3 id="case-filters-heading" className="visually-hidden">
        Case filters
      </h3>
      <div className="filters__grid">
        {/*
          Two independent ranges, in two fieldsets rather than one. Backend
          validates `[createdAtFrom, createdAtTo)` and
          `[lastChangedAtFrom, lastChangedAtTo)` separately, and an analyst
          filling in one has to be able to see that the other is untouched.
        */}
        <fieldset className="field-group field-group--wide">
          <legend className="field-group__legend">Opened between</legend>
          <div className="field-group__fields field-group__fields--pair">
            <div className="field">
              <label className="field__label" htmlFor="case-filter-created-from">
                Opened from (KST)
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
                Opened to (KST)
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
            Entered and read as Korea Standard Time, UTC+09:00. Sent to the backend as UTC.
          </p>
        </fieldset>

        <fieldset className="field-group field-group--wide">
          <legend className="field-group__legend">Last changed between</legend>
          <div className="field-group__fields field-group__fields--pair">
            <div className="field">
              <label className="field__label" htmlFor="case-filter-changed-from">
                Changed from (KST)
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
                Changed to (KST)
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
            Independent of the opened range. Either may be used on its own.
          </p>
        </fieldset>

        <fieldset className="field-group">
          <legend className="field-group__legend">Investigation state</legend>
          <div className="field-group__fields">
            <div className="field">
              <label className="field__label" htmlFor="case-filter-status">
                Case status
              </label>
              <select
                className="field__control"
                id="case-filter-status"
                value={draft.caseStatus}
                onChange={(event) => {
                  update("caseStatus", event.target.value);
                }}
              >
                <option value="">Any status</option>
                {CASE_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {CASE_STATUS_LABELS[status]}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label className="field__label" htmlFor="case-filter-disposition">
                Final disposition
              </label>
              <select
                className="field__control"
                id="case-filter-disposition"
                value={draft.finalDisposition}
                onChange={(event) => {
                  update("finalDisposition", event.target.value);
                }}
              >
                <option value="">Any disposition</option>
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
          <legend className="field-group__legend">References</legend>
          <div className="field-group__fields">
            <div className="field">
              <label className="field__label" htmlFor="case-filter-assignee-ref">
                Assignee reference
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
                Matched exactly. At most {MAX_ASSIGNEE_REF_LENGTH} characters, with no
                leading or trailing spaces.
              </p>
            </div>
            <div className="field">
              <label className="field__label" htmlFor="case-filter-transaction-id">
                Related transaction ID
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
                A canonical lowercase UUID, exactly as the ledger records it.
              </p>
            </div>
          </div>
        </fieldset>
      </div>

      <div className="filters__actions">
        <button className="button button--primary" type="submit">
          Apply filters
        </button>
        <button className="button" type="button" onClick={onReset}>
          Reset filters
        </button>
        <p className="filters__note">
          {hasPendingEdits
            ? "Edits are not applied yet. Apply them to search."
            : "Showing results for the applied filters."}
        </p>
      </div>
    </form>
  );
}
