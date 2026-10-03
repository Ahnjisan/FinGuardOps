import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it } from "vitest";
import type { RouteObject } from "react-router-dom";
import { CaseTransactionsPanel } from "../cases/CaseTransactionsSection";
import { TransactionDetailPage } from "../TransactionDetailPage";
import { AdoptedDetectionPanel } from "./AdoptedDetectionSection";
import { createFakeAuthClient } from "../../test/fakeAuthClient";
import { renderRoutesWithAuth } from "../../test/renderWithAuth";
import type { TransactionDetail } from "../../api/transactionApi";

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const detail: TransactionDetail = {
  transactionId: ID, transactionType: "ACCOUNT_TRANSFER", amount: "10000", currencyCode: "KRW",
  occurredAt: "2026-07-23T01:15:30Z", externalCustomerRef: "cust_demo",
  senderAccountRef: "sender_demo", recipientAccountRef: null,
  channel: "MOBILE_BANKING", deviceRef: null, processingStatus: "ANALYZED",
  createdAt: "2026-07-23T01:15:30Z", updatedAt: "2026-07-23T01:15:31Z",
};
const casePanel = <CaseTransactionsPanel caseId={CASE_ID} state={{ status: "success", ids: [ID],
  page: { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true } }}
  pageNumber={0} onPageChange={() => undefined} onRetry={() => undefined} mayViewTransaction />;
const transactionPage = <TransactionDetailPage
  useDetail={() => ({ state: { status: "success", data: detail }, retry: () => undefined })}
  renderDetection={() => <AdoptedDetectionPanel onRetry={() => undefined}
    state={{ status: "success", data: { transactionId: ID, availability: "NO_HISTORY",
      latestDetectionResultVersion: null, latestAnalysisStatus: null, adoptedResult: null } }} />} />;
const routes: RouteObject[] = [
  { path: "/cases/:caseId", element: <>{casePanel}<h2>사건 기록</h2></> },
  { path: "/transactions/:transactionId", element: transactionPage },
  { path: "/transactions", element: <p>거래 목록</p> },
];
const client = () => createFakeAuthClient({ initialSession: {
  subject: "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f", roles: ["FDS_VIEWER"] } });

it("returns from a case-linked transaction through a canonical case route", async () => {
  const user = userEvent.setup();
  renderRoutesWithAuth(routes, { client: client(), initialEntries: [`/cases/${CASE_ID}`] });
  await user.click(await screen.findByRole("link", { name: `거래 ${ID} 상세 보기` }));
  expect(await screen.findByRole("heading", { name: "채택된 탐지 결과" })).toBeInTheDocument();
  const back = screen.getByRole("link", { name: "사건으로 돌아가기" });
  expect(back).toHaveAttribute("href", `/cases/${CASE_ID}`);
  await user.tab();
  expect(screen.getByRole("link", { name: "거래 목록으로" })).toHaveFocus();
  await user.tab();
  expect(back).toHaveFocus();
  await user.keyboard("{Enter}");
  expect(await screen.findByRole("heading", { name: "사건 기록" })).toBeInTheDocument();
});

it("direct transaction entry retains only the list return", async () => {
  renderRoutesWithAuth(routes, { client: client(), initialEntries: [`/transactions/${ID}`] });
  expect(await screen.findByRole("link", { name: "거래 목록으로" })).toHaveAttribute("href", "/transactions");
  expect(screen.queryByRole("link", { name: "사건으로 돌아가기" })).not.toBeInTheDocument();
});
