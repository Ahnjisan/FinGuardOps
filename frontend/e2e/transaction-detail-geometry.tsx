/** Production page with synthetic validated data. No credential or Backend is used. */
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MemoryRouter } from "react-router-dom";
import type { TransactionDetail } from "../src/api/transactionApi";
import { TransactionDetailPage } from "../src/pages/TransactionDetailPage";
import { AdoptedDetectionPanel } from "../src/pages/transactions/AdoptedDetectionSection";
import { AuthProvider } from "../src/auth/AuthProvider";
import type { AuthClient, AuthSession } from "../src/auth/authClient";
import "../src/styles/app.css";

const transactionId = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const longReference = "geometry-reference-".repeat(6).slice(0, 128);
const detail: TransactionDetail = {
  transactionId,
  transactionType: "ACCOUNT_TRANSFER",
  amount: "999999999999999",
  currencyCode: "KRW",
  occurredAt: "2026-01-02T03:04:05Z",
  externalCustomerRef: longReference,
  senderAccountRef: longReference,
  recipientAccountRef: null,
  channel: "MOBILE_BANKING",
  deviceRef: null,
  processingStatus: "ADDITIONAL_AUTH_REQUIRED",
  createdAt: "2026-01-02T03:04:06Z",
  updatedAt: "2026-01-02T03:05:07Z",
};
const session: AuthSession = { subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f", roles: ["FDS_ANALYST"] };
const client: AuthClient = {
  initialize: async () => ({ session }), signIn: async () => undefined,
  completeSignIn: async () => ({ session, returnTo: "/" }), signOut: async () => undefined,
  onSessionInvalidated: () => () => undefined,
};

const root = document.getElementById("root");
if (!root) throw new Error("Root element not found");
createRoot(root).render(
  <StrictMode>
    <AuthProvider client={client}>
    <MemoryRouter initialEntries={[`/transactions/${transactionId}`]}>
      <div className="app">
        <header className="rail" aria-label="합성 탐색 영역" />
        <main className="main" id="main-content">
          <p>합성 데이터 배치 검증 · 인증된 Backend 화면이 아닙니다.</p>
          <TransactionDetailPage useDetail={() => ({ state: { status: "success", data: detail }, retry: () => undefined })}
            renderDetection={() => <AdoptedDetectionPanel onRetry={() => undefined} state={{ status: "success", data: {
              transactionId, availability: "AVAILABLE", latestDetectionResultVersion: 2,
              latestAnalysisStatus: "IN_PROGRESS", adoptedResult: {
                detectionResultId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101", detectionResultVersion: 1,
                riskLevel: "HIGH", riskScore: 55, analysisCompletedAt: "2026-01-02T03:05:08Z",
                ruleSetVersion: "a".repeat(64), scoringPolicyVersion: "scoring-policy-v1",
                ruleEvidence: [{ ruleCode: "R001", ruleVersion: "1",
                  reasonCode: "TRANSFER_ABSOLUTE_HIGH_AMOUNT", scoreContribution: 15 }],
              },
            } }} />} />
        </main>
      </div>
    </MemoryRouter>
    </AuthProvider>
  </StrictMode>,
);
