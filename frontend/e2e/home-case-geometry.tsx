/** Test-only layout fixture. No authentication, transport, Backend, or seeded database. */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MemoryRouter } from "react-router-dom";
import type { CaseListItem } from "../src/api/caseApi";
import { CaseWorkOverviewView } from "../src/pages/home/CaseWorkOverview";
import "../src/styles/app.css";

const rows: CaseListItem[] = Array.from({ length: 5 }, (_, index) => ({
  caseId: `5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e${String(index).padStart(2, "0")}`,
  caseStatus: "OPEN",
  finalDisposition: null,
  assigneeRef: index === 0 ? "x".repeat(128) : null,
  relatedTransactionCount: index,
  createdAt: "2026-07-23T01:15:30Z",
  lastChangedAt: "2026-07-24T02:20:40Z",
}));

const noop = () => undefined;
const root = document.getElementById("root");
if (!root) throw new Error("Root element not found.");

createRoot(root).render(
  <StrictMode>
    <MemoryRouter>
      <div className="app">
        <div className="rail" />
        <main className="main" id="main-content">
          <div className="home">
            <CaseWorkOverviewView
              open={{ state: { status: "success", data: {
                content: rows,
                page: { number: 0, size: 5, totalElements: 8, totalPages: 2, first: true, last: false },
              } }, retry: noop }}
              information={{ state: { status: "success", data: {
                content: [],
                page: { number: 0, size: 1, totalElements: 0, totalPages: 0, first: true, last: true },
              } }, retry: noop }}
            />
          </div>
        </main>
      </div>
    </MemoryRouter>
  </StrictMode>,
);
