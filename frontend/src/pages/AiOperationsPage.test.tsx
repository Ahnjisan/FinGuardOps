import { fireEvent, render, screen, waitFor } from "@testing-library/react";
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
const observed = vi.hoisted(() => ({ queries: [] as Record<string, unknown>[] }));
vi.mock("../api/useAiOperations", () => ({ useAiUsage: (query: Record<string, unknown>) => {
  observed.queries.push(query);
  return data;
} }));
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

it("omits empty optional filters when submitting an AI usage query", async () => {
  observed.queries.length = 0;
  render(<MemoryRouter><AiOperationsPage /></MemoryRouter>);
  const fromInput = screen.getByLabelText("시작 (UTC)") as HTMLInputElement;
  const earlier = new Date(Date.parse(fromInput.value) - 60 * 60 * 1000).toISOString();
  fireEvent.change(fromInput, { target: { value: earlier } });
  fireEvent.click(screen.getByRole("button", { name: "조회" }));
  await waitFor(() => expect(observed.queries.at(-1)?.from).toBe(earlier));
  const submitted = observed.queries.at(-1)!;
  expect(submitted).not.toHaveProperty("reportStatus");
  expect(submitted).not.toHaveProperty("provider");
  expect(submitted).not.toHaveProperty("model");
});

it("includes selected AI usage filters in the submitted query", async () => {
  observed.queries.length = 0;
  render(<MemoryRouter><AiOperationsPage /></MemoryRouter>);
  fireEvent.change(screen.getByLabelText("상태"), { target: { value: "FAILED" } });
  fireEvent.change(screen.getByLabelText("Provider"), { target: { value: "OLLAMA" } });
  fireEvent.change(screen.getByLabelText("모델 digest"), { target: { value: "qwen3" } });
  fireEvent.click(screen.getByRole("button", { name: "조회" }));
  await waitFor(() => expect(observed.queries.at(-1)?.reportStatus).toBe("FAILED"));
  expect(observed.queries.at(-1)).toMatchObject({ provider: "OLLAMA", model: "qwen3" });
});
