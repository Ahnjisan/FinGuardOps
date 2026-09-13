/**
 * TEST-ONLY BROWSER GEOMETRY FIXTURE. NOT PART OF THE APPLICATION.
 *
 * 실제 E2E runtime에는 종결 가능한 사건 seed가 없다. 이 fixture는 synthetic
 * FDS_ANALYST+FDS_APPROVER AuthClient/session과 종결 가능한 IN_REVIEW detail로 production
 * `CaseWorkflowSection`을 production stylesheet와 함께 렌더해 workflow control과 사건 최종 판정
 * fieldset을 한 section에서 측정한다. credential·token·Keycloak 로그인이 없으므로 인증·인가 보안
 * 증거가 아닌 layout 증거다. API 요청, route interception, Backend는 관여하지 않으며 geometry
 * 측정은 radio를 선택하거나 제출하지 않는다.
 */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import type { CaseDetail } from "../src/api/caseApi";
import type { AuthClient, AuthSession } from "../src/auth/authClient";
import { AuthProvider } from "../src/auth/AuthProvider";
import { CaseWorkflowSection } from "../src/pages/cases/CaseWorkflowSection";
import "../src/styles/app.css";

/** 종결 가능한 IN_REVIEW detail: 담당자와 최초 조사 시각이 있고 판정·종결 시각은 없다. */
const GEOMETRY_RESOLVABLE_DETAIL: CaseDetail = {
  caseId: "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60",
  caseStatus: "IN_REVIEW",
  finalDisposition: null,
  assigneeRef: "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f",
  relatedTransactionCount: 3,
  createdAt: "2026-09-02T00:00:00Z",
  reviewStartedAt: "2026-09-02T00:30:00Z",
  closedAt: null,
  lastChangedAt: "2026-09-02T01:00:00Z",
  concurrencyVersion: 6,
};

const GEOMETRY_SESSION: AuthSession = {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f",
  roles: ["FDS_ANALYST", "FDS_APPROVER"],
};

/** workflow와 resolution control을 함께 렌더하기 위한 측정 전용 synthetic capability context. */
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
          <section className="detail" aria-labelledby="case-resolution-fixture-heading">
            <div className="page-head">
              <h2 id="case-resolution-fixture-heading">
                Case resolution geometry fixture (test only)
              </h2>
              <p>
                Browser geometry measurement of the production case resolution form. No request is made.
              </p>
            </div>
            <CaseWorkflowSection
              detail={GEOMETRY_RESOLVABLE_DETAIL}
              reconciliationGeneration={1}
              detailRefreshState="idle"
              onReconcile={() => undefined}
            />
          </section>
        </main>
      </div>
    </AuthProvider>
  </StrictMode>,
);
