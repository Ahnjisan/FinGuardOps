/**
 * TEST-ONLY BROWSER GEOMETRY FIXTURE. NOT PART OF THE APPLICATION.
 *
 * The real E2E runtime has no populated case seed. This fixture therefore
 * mounts the production panel and capability-gated composer with a settled
 * public projection, the production stylesheet, and a synthetic FDS_ANALYST
 * AuthClient/session. It uses no credential, token or Keycloak login and is
 * layout evidence only, not authentication or authorization security evidence.
 * No API request, interception or Backend is involved.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import type { CaseInvestigationNotesView } from "../src/api/useCaseInvestigationNotes";
import type { AuthClient, AuthSession } from "../src/auth/authClient";
import { AuthProvider } from "../src/auth/AuthProvider";
import { CaseInvestigationNotesPanel } from "../src/pages/cases/CaseInvestigationNotesSection";
import { InvestigationNoteComposer } from "../src/pages/cases/InvestigationNoteComposer";
import "../src/styles/app.css";

const PLAIN_PREFIX =
  "  한글 Unicode Ω🙂\r\nsecond  line <script>alert('plain')</script> " +
  "https://example.invalid/path?q=plain  ";
const MAX_CONTENT = `${PLAIN_PREFIX}${"界".repeat(
  4000 - Array.from(PLAIN_PREFIX).length,
)}`;

/** Width probes for the public text projection; they are not API fixtures. */
const LONG_NOTE_ID = "note-id-unbroken-".padEnd(128, "n");
const LONG_AUTHOR_REF = "author-reference-unbroken-".padEnd(128, "a");

const GEOMETRY_VIEW: CaseInvestigationNotesView = {
  items: [
    {
      noteId: "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60",
      authorType: "SYSTEM",
      authorRef: "finguardops-backend",
      content: "System Unicode note: 시스템 확인 완료.\r\nSpacing  is  preserved.",
      createdAt: "2026-09-02T00:00:00.123456Z",
    },
    {
      noteId: LONG_NOTE_ID,
      authorType: "USER",
      authorRef: LONG_AUTHOR_REF,
      content: MAX_CONTENT,
      createdAt: "2026-09-02T01:02:03.000004Z",
    },
    {
      noteId: "9e3f4a50-6b7c-4d8e-9f01-2c3d4e5f6071",
      authorType: "USER",
      authorRef: "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
      content: "<b>HTML-like text stays text</b> https://not-linked.invalid",
      createdAt: "2026-09-02T02:03:04Z",
    },
  ],
  page: {
    number: 0,
    size: 20,
    totalElements: 3,
    totalPages: 1,
    first: true,
    last: true,
  },
};

const GEOMETRY_SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: ["FDS_ANALYST"],
};

/** Synthetic capability context used only to render the production composer for measurement. */
const GEOMETRY_AUTH_CLIENT: AuthClient = {
  initialize: async () => ({ session: GEOMETRY_SESSION }),
  signIn: async () => undefined,
  completeSignIn: async () => ({ session: GEOMETRY_SESSION, returnTo: "/" }),
  signOut: async () => undefined,
  onSessionInvalidated: () => () => undefined,
};

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Root element not found.");
}

createRoot(rootElement).render(
  <StrictMode>
    <AuthProvider client={GEOMETRY_AUTH_CLIENT}>
      <div className="app">
        <div className="rail" />
        <main className="main" id="main-content">
          <section className="detail" aria-labelledby="case-notes-fixture-heading">
            <div className="page-head">
              <h2 id="case-notes-fixture-heading">
                Case investigation notes geometry fixture (test only)
              </h2>
              <p>
                Browser geometry measurement of the production notes panel. No request is made.
              </p>
            </div>
            <CaseInvestigationNotesPanel
              state={{ status: "success", data: GEOMETRY_VIEW }}
              onPageChange={() => undefined}
              onPageSizeChange={() => undefined}
              onRetry={() => undefined}
              composer={
                <InvestigationNoteComposer
                  caseId="5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60"
                  caseStatus="IN_REVIEW"
                  expectedVersion={6}
                  reconciliationGeneration={1}
                  onReconcile={() => undefined}
                />
              }
            />
          </section>
        </main>
      </div>
    </AuthProvider>
  </StrictMode>,
);
