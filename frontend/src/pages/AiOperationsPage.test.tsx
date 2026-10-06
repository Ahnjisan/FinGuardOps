import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";

const data = vi.hoisted(() => ({
  list: { loading: false, error: false, data: { content: [{
    aiRequestId: "33333333-3333-4333-8333-333333333333", requestedAt: "2026-10-01T00:00:00Z",
    reportStatus: "FAILED", reportSource: null, executionShared: true, executionId: "e",
    cacheHit: false, totalTokens: null,
  }], page: { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true } } },
  summary: { loading: false, error: false, data: { requestCount: 2, executionCount: 1,
    providerCallCount: 1, fallbackCount: 0, cacheHitCount: 0, inputTokens: null, outputTokens: null } },
}));
vi.mock("../api/useAiOperations", () => ({ useAiUsage: () => data }));
const { AiOperationsPage } = await import("./AiOperationsPage");

it("labels persisted attempts and shows unknown cost without an amount", () => {
  render(<MemoryRouter><AiOperationsPage /></MemoryRouter>);
  expect(screen.getByText("기록된 attempt 수", { exact: true })).toBeInTheDocument();
  expect(screen.getByText("비용 미측정")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "33333333-3333-4333-8333-333333333333" }))
    .toHaveAttribute("href", "/ai-operations/33333333-3333-4333-8333-333333333333");
});

it("distinguishes zero recorded calls from unmeasured call cost", () => {
  data.summary.data.providerCallCount = 0;
  try {
    render(<MemoryRouter><AiOperationsPage /></MemoryRouter>);
    expect(screen.getByText("기록된 Provider 호출 없음")).toBeInTheDocument();
    expect(screen.queryByText("비용 미측정")).not.toBeInTheDocument();
  } finally {
    data.summary.data.providerCallCount = 1;
  }
});
