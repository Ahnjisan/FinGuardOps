/**
 * TEST-ONLY BROWSER GEOMETRY FIXTURE. NOT PART OF THE APPLICATION.
 *
 * The real E2E runtime has no populated case seed. This fixture therefore
 * mounts the production panel directly with a settled public projection and
 * the production stylesheet. It is layout evidence only: no hook, auth,
 * router, API mock, interception, request or Backend is involved.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import type { CaseInvestigationNotesView } from "../src/api/useCaseInvestigationNotes";
import { CaseInvestigationNotesPanel } from "../src/pages/cases/CaseInvestigationNotesSection";
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

const rootElement = document.getElementById("root");
if (!rootElement) {
  throw new Error("Root element not found.");
}

createRoot(rootElement).render(
  <StrictMode>
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
          />
        </section>
      </main>
    </div>
  </StrictMode>,
);
