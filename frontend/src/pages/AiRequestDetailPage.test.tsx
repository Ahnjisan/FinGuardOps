import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";

const id = "33333333-3333-4333-8333-333333333333";
const detailData = vi.hoisted(() => ({ attempts: [{ attemptNumber: 1, provider: "OLLAMA_LOCAL",
  model: null, outcome: "TIMEOUT", inputTokens: null, outputTokens: null, latencyMs: 4000 }] }));
vi.mock("../api/useAiOperations", () => ({ useAiRequestDetail: () => ({ loading: false,
  error: false, data: { aiRequestId: id, reportStatus: "FAILED", executionId: id,
    executionShared: true, cacheHit: false, fallbackUsed: false, failureCode: "TIMEOUT",
    attempts: detailData.attempts,
    inputTokens: null, outputTokens: null } }) }));
const { AiRequestDetailPage } = await import("./AiRequestDetailPage");

it("shows stored attempt outcome and rejects malformed detail addresses", () => {
  render(<MemoryRouter initialEntries={[`/ai-operations/${id}`]}><AiRequestDetailPage /></MemoryRouter>);
  expect(screen.getAllByText("TIMEOUT", { exact: true })).toHaveLength(2);
  expect(screen.getByText("비용 미측정")).toBeInTheDocument();
});

it("labels no recorded Provider calls separately from unknown cost", () => {
  const saved = detailData.attempts;
  detailData.attempts = [];
  try {
    render(<MemoryRouter initialEntries={[`/ai-operations/${id}`]}><AiRequestDetailPage /></MemoryRouter>);
    expect(screen.getByText("기록된 Provider 호출 없음", { exact: true })).toBeInTheDocument();
    expect(screen.queryByText("비용 미측정")).not.toBeInTheDocument();
  } finally {
    detailData.attempts = saved;
  }
});

it("does not query an invalid request identifier", () => {
  render(<MemoryRouter initialEntries={["/ai-operations/invalid"]}><AiRequestDetailPage /></MemoryRouter>);
  expect(screen.getByText(/찾을 수 없습니다|존재하지/)).toBeInTheDocument();
});
