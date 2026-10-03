/** Test-only synthetic layout fixture. No Backend, token or real USER session is involved. */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import type { CaseDetail } from "../src/api/caseApi";
import type { CaseAuditView } from "../src/api/useCaseAuditLog";
import type { CaseInvestigationNotesView } from "../src/api/useCaseInvestigationNotes";
import type { AuthClient, AuthSession } from "../src/auth/authClient";
import { AuthProvider } from "../src/auth/AuthProvider";
import { CaseAtAGlance, CaseRecord } from "../src/pages/CaseDetailPage";
import { CaseAuditPanel } from "../src/pages/cases/CaseAuditSection";
import { CaseInvestigationNotesPanel } from "../src/pages/cases/CaseInvestigationNotesSection";
import { CaseTransactionsPanel } from "../src/pages/cases/CaseTransactionsSection";
import { CaseWorkflowSection } from "../src/pages/cases/CaseWorkflowSection";
import { InvestigationNoteComposer } from "../src/pages/cases/InvestigationNoteComposer";
import "../src/styles/app.css";

const mode = new URLSearchParams(location.search).get("mode") ?? "analyst";
const role = mode === "viewer" ? "FDS_VIEWER" : mode === "approver" ? "FDS_APPROVER" : "FDS_ANALYST";
const session: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: [role],
};
const client: AuthClient = {
  initialize: async () => ({ session }),
  signIn: async () => undefined,
  completeSignIn: async () => ({ session, returnTo: "/" }),
  signOut: async () => undefined,
  onSessionInvalidated: () => () => undefined,
};

const longRef = "synthetic-assignee-reference-".padEnd(128, "x");
const caseId = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const detail: CaseDetail = {
  caseId,
  caseStatus: "IN_REVIEW",
  finalDisposition: null,
  assigneeRef: longRef,
  relatedTransactionCount: 3,
  createdAt: "2026-09-02T00:00:00Z",
  reviewStartedAt: "2026-09-02T00:30:00Z",
  closedAt: null,
  lastChangedAt: "2026-09-02T01:00:00Z",
  concurrencyVersion: 6,
};
const page = { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true };
const notes: CaseInvestigationNotesView = {
  items: [{
    noteId: "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60",
    authorType: "USER",
    authorRef: longRef,
    content: `${"긴 조사 내용 ".repeat(30)}\n${"끊기지않는메모".repeat(140)}`,
    createdAt: "2026-09-02T01:02:03Z",
  }],
  page,
};
const audit: CaseAuditView = {
  content: [{
    action: "CASE_STATUS_CHANGED",
    reasonCode: "CASE_REVIEW_STARTED",
    actorType: "USER",
    changedAt: "2026-09-02T00:30:00Z",
    beforeSummary: { caseStatus: "OPEN", assigneeRef: null },
    afterSummary: { caseStatus: "IN_REVIEW", assigneeRef: longRef },
    metadata: {},
  }],
  page,
};
const emptyPage = { number: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true };
const notesState = mode === "empty"
  ? { status: "empty" as const, data: { items: [], page: emptyPage } }
  : mode === "error"
    ? { status: "network-error" as const }
    : { status: "success" as const, data: notes };
const auditState = mode === "empty"
  ? { status: "empty" as const, data: { content: [], page: emptyPage } }
  : mode === "error"
    ? { status: "network-error" as const }
    : { status: "success" as const, data: audit };

const root = document.getElementById("root");
if (!root) throw new Error("Root element not found");
createRoot(root).render(
  <StrictMode>
    <AuthProvider client={client}>
      <div className="app">
        <div className="rail" />
        <main className="main" id="main-content">
          <section className="detail" aria-labelledby="case-detail-heading">
            <div className="page-head">
              <h2 id="case-detail-heading">사건 <span className="detail__id">{caseId}</span></h2>
              <p>합성 데이터 배치 검증 · 인증된 Backend 화면이 아닙니다.</p>
            </div>
            <CaseAtAGlance detail={detail} />
            <div className="case-detail__workspace">
              <CaseRecord detail={detail} />
              <div className="case-detail__work-area">
                <CaseWorkflowSection detail={detail} reconciliationGeneration={1}
                  detailRefreshState="idle" onReconcile={() => undefined} />
              </div>
            </div>
            <CaseInvestigationNotesPanel state={notesState} onPageChange={() => undefined}
              onPageSizeChange={() => undefined} onRetry={() => undefined}
              composer={mode === "analyst" ? <InvestigationNoteComposer caseId={caseId}
                caseStatus="IN_REVIEW" expectedVersion={6} reconciliationGeneration={1}
                onReconcile={() => undefined} /> : null} />
            <CaseAuditPanel state={auditState} onPageChange={() => undefined}
              onPageSizeChange={() => undefined} onRetry={() => undefined} />
            <CaseTransactionsPanel state={mode === "empty"
              ? { status: "success", ids: [], page: emptyPage }
              : mode === "error" ? { status: "error", kind: "network" }
                : { status: "success", ids: [
                  "91a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5003",
                  "12a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5004",
                ], page: { number: 0, size: 20, totalElements: 22, totalPages: 2,
                  first: true, last: false } }}
              pageNumber={0} onPageChange={() => undefined} onRetry={() => undefined}
              mayViewTransaction={mode !== "viewer"} />
          </section>
        </main>
      </div>
    </AuthProvider>
  </StrictMode>,
);
