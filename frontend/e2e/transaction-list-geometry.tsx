/** Test-only full list layout with production controls and non-sensitive records. */
import { StrictMode, useState } from "react";
import { createRoot } from "react-dom/client";
import { MemoryRouter } from "react-router-dom";
import type { TransactionListItem, TransactionListSort } from "../src/api/transactionApi";
import { TransactionFilters } from "../src/pages/transactions/TransactionFilters";
import { TransactionPagination } from "../src/pages/transactions/TransactionPagination";
import { TransactionTable } from "../src/pages/transactions/TransactionTable";
import { EMPTY_FILTER_DRAFT, type TransactionFilterDraft } from "../src/pages/transactions/transactionPresentation";
import "../src/styles/app.css";

const LONG_REF = "geometry-reference-".repeat(6).slice(0, 128);
const TRANSACTIONS: readonly TransactionListItem[] = [
  {
    transactionId: "0a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d",
    transactionType: "ACCOUNT_TRANSFER",
    amount: "999999999999999",
    currencyCode: "KRW",
    occurredAt: "2026-01-02T03:04:05Z",
    externalCustomerRef: LONG_REF,
    senderAccountRef: LONG_REF,
    recipientAccountRef: LONG_REF,
    processingStatus: "ADDITIONAL_AUTH_REQUIRED",
    createdAt: "2026-01-02T03:04:06Z",
  },
  {
    transactionId: "1b2c3d4e-5f6a-4b7c-9d0e-1f2a3b4c5d6e",
    transactionType: "ATM_WITHDRAWAL",
    amount: "1250000",
    currencyCode: "KRW",
    occurredAt: "2026-01-03T04:05:06Z",
    externalCustomerRef: "geometry-customer-02",
    senderAccountRef: "geometry-account-02",
    recipientAccountRef: null,
    processingStatus: "FAILED",
    createdAt: "2026-01-03T04:05:07Z",
  },
];

export function Fixture() {
  const [draft, setDraft] = useState<TransactionFilterDraft>(EMPTY_FILTER_DRAFT);
  const [appliedDraft, setAppliedDraft] = useState<TransactionFilterDraft>(EMPTY_FILTER_DRAFT);
  const [sort, setSort] = useState<TransactionListSort>("occurredAt,desc");
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  return (
    <div className="app">
      <header className="rail" aria-label="합성 탐색 영역" />
      <main className="main" id="main-content">
        <section className="transactions" aria-labelledby="transactions-heading">
          <div className="page-head">
            <h2 id="transactions-heading">거래</h2>
            <p>거래 기록과 처리 상태를 조회합니다. 시간은 한국 표준시(UTC+09:00)입니다.</p>
          </div>
          <TransactionFilters
            draft={draft}
            appliedDraft={appliedDraft}
            onDraftChange={setDraft}
            onApply={() => { setAppliedDraft(draft); setPage(0); }}
            onReset={() => { setDraft(EMPTY_FILTER_DRAFT); setAppliedDraft(EMPTY_FILTER_DRAFT); setPage(0); setSize(20); setSort("occurredAt,desc"); }}
            hasPendingEdits={JSON.stringify(draft) !== JSON.stringify(appliedDraft)}
          />
          <div className="transaction-results-head">
            <h3>거래 조회 결과</h3>
            <div className="result-line" role="status" aria-live="polite">전체 2건 중 1~2건 표시</div>
          </div>
          <TransactionTable items={TRANSACTIONS} sort={sort} onSortChange={(value) => { setSort(value); setPage(0); }} />
          <TransactionPagination
            page={{ number: page, size, totalElements: 2, totalPages: 1, first: true, last: true }}
            onPageChange={setPage}
            onPageSizeChange={(value) => { setSize(value); setPage(0); }}
          />
        </section>
      </main>
    </div>
  );
}

const root = document.getElementById("root");
if (!root) throw new Error("Root element not found.");
createRoot(root).render(<StrictMode><MemoryRouter><Fixture /></MemoryRouter></StrictMode>);
