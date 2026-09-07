import {
  TRANSACTION_PROCESSING_STATUSES,
  TRANSACTION_TYPES,
} from "../../api/transactionApi";
import {
  PROCESSING_STATUS_LABELS,
  TRANSACTION_TYPE_LABELS,
  type TransactionFilterDraft,
} from "./transactionPresentation";

export interface TransactionFiltersProps {
  readonly draft: TransactionFilterDraft;
  readonly onDraftChange: (draft: TransactionFilterDraft) => void;
  readonly onApply: () => void;
  readonly onReset: () => void;
  /** True while committed filters differ from the draft on screen. */
  readonly hasPendingEdits: boolean;
}

export function TransactionFilters({
  draft,
  onDraftChange,
  onApply,
  onReset,
  hasPendingEdits,
}: TransactionFiltersProps) {
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
      <h3 id="filters-heading" className="visually-hidden">
        Transaction filters
      </h3>
      <div className="filters__grid">
        <fieldset className="field-group field-group--wide">
          <legend className="field-group__legend">Occurred between</legend>
          <div className="field-group__fields field-group__fields--pair">
            <div className="field">
              <label className="field__label" htmlFor="filter-occurred-from">
                From (KST)
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
                To (KST)
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
            Entered and read as Korea Standard Time, UTC+09:00. Sent to the backend as UTC.
          </p>
        </fieldset>

        <fieldset className="field-group">
          <legend className="field-group__legend">Classification</legend>
          <div className="field-group__fields">
            <div className="field">
              <label className="field__label" htmlFor="filter-transaction-type">
                Transaction type
              </label>
              <select
                className="field__control"
                id="filter-transaction-type"
                value={draft.transactionType}
                onChange={(event) => {
                  update("transactionType", event.target.value);
                }}
              >
                <option value="">Any type</option>
                {TRANSACTION_TYPES.map((type) => (
                  <option key={type} value={type}>
                    {TRANSACTION_TYPE_LABELS[type]}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label className="field__label" htmlFor="filter-processing-status">
                Processing status
              </label>
              <select
                className="field__control"
                id="filter-processing-status"
                value={draft.processingStatus}
                onChange={(event) => {
                  update("processingStatus", event.target.value);
                }}
              >
                <option value="">Any status</option>
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
          <legend className="field-group__legend">References</legend>
          <div className="field-group__fields">
            <div className="field">
              <label className="field__label" htmlFor="filter-customer-ref">
                Customer reference
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
                Account reference
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
              Matched exactly, including spaces and capitalisation.
            </p>
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
