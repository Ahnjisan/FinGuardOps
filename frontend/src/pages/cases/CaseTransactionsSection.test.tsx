import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { CaseTransactionsPanel } from "./CaseTransactionsSection";
import type { CaseTransactionsState } from "../../api/useCaseTransactions";

const FIRST = "91a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5003";
const SECOND = "12a2b3c4-d5e6-47f8-9a0b-1c2d3e4f5004";
const success: CaseTransactionsState = { status: "success", ids: [FIRST, SECOND],
  page: { number: 0, size: 20, totalElements: 22, totalPages: 2, first: true, last: false } };

function show(state: CaseTransactionsState, mayViewTransaction = true) {
  const onPageChange = vi.fn();
  const onRetry = vi.fn();
  render(<MemoryRouter><CaseTransactionsPanel state={state} pageNumber={0}
    onPageChange={onPageChange} onRetry={onRetry}
    mayViewTransaction={mayViewTransaction} /></MemoryRouter>);
  return { onPageChange, onRetry };
}

it("shows ID links only with transaction:view and keeps the full UUID", () => {
  show(success);
  const link = screen.getByRole("link", { name: `거래 ${FIRST} 상세 보기` });
  expect(link).toHaveAttribute("href", `/transactions/${FIRST}`);
  expect(screen.getByText(SECOND)).toBeInTheDocument();
});

it("shows IDs without links to a case:view-only session", () => {
  show(success, false);
  expect(screen.getByText(FIRST)).toBeInTheDocument();
  expect(screen.queryByRole("link")).not.toBeInTheDocument();
});

it("paginates by keyboard and distinguishes empty relation from empty later page", async () => {
  const user = userEvent.setup();
  const { onPageChange } = show(success);
  await user.tab();
  expect(screen.getByRole("link", { name: `거래 ${FIRST} 상세 보기` })).toHaveFocus();
  await user.tab();
  await user.tab();
  expect(screen.getByRole("button", { name: "다음" })).toHaveFocus();
  await user.keyboard("{Enter}");
  expect(onPageChange).toHaveBeenCalledExactlyOnceWith(1);
});

it("renders zero relations, an empty later page, and a manual retry", async () => {
  const user = userEvent.setup();
  const { rerender } = render(<MemoryRouter><CaseTransactionsPanel
    state={{ status: "success", ids: [], page: {
      number: 0, size: 20, totalElements: 0, totalPages: 0, first: true, last: true,
    } }} pageNumber={0} onPageChange={() => undefined} onRetry={() => undefined}
    mayViewTransaction /></MemoryRouter>);
  expect(screen.getByText("저장된 연관 거래가 없습니다.")).toBeInTheDocument();
  rerender(<MemoryRouter><CaseTransactionsPanel state={{ status: "success", ids: [], page: {
    number: 2, size: 20, totalElements: 1, totalPages: 1, first: false, last: true,
  } }} pageNumber={2} onPageChange={() => undefined} onRetry={() => undefined}
    mayViewTransaction /></MemoryRouter>);
  expect(screen.getByText("이 페이지에는 거래 ID가 없습니다. 이전 페이지를 확인하세요.")).toBeInTheDocument();
  const retry = vi.fn();
  rerender(<MemoryRouter><CaseTransactionsPanel state={{ status: "error", kind: "network" }}
    pageNumber={0} onPageChange={() => undefined} onRetry={retry}
    mayViewTransaction /></MemoryRouter>);
  await user.click(screen.getByRole("button", { name: "다시 시도" }));
  expect(retry).toHaveBeenCalledOnce();
});

it("keeps 403, 404 and session expiry separate from retryable failures", () => {
  const { rerender } = render(<MemoryRouter><CaseTransactionsPanel state={{ status: "forbidden" }}
    pageNumber={0} onPageChange={() => undefined} onRetry={() => undefined}
    mayViewTransaction /></MemoryRouter>);
  for (const [status, title] of [["forbidden", "연관 거래를 볼 권한이 없습니다"],
    ["not-found", "사건을 찾을 수 없습니다"],
    ["authentication-required", "세션이 종료되었습니다"]] as const) {
    rerender(<MemoryRouter><CaseTransactionsPanel state={{ status }} pageNumber={0}
      onPageChange={() => undefined} onRetry={() => undefined}
      mayViewTransaction /></MemoryRouter>);
    expect(screen.getByText(title)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();
  }
});
