import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";

const id = "33333333-3333-4333-8333-333333333333";
const detailData = vi.hoisted(() => ({ attempts: [{ attemptNumber: 1, provider: "OLLAMA_LOCAL",
  model: null, outcome: "TIMEOUT", inputTokens: null, outputTokens: null, latencyMs: 4000 }],
  fallbackUsed: false, fallbackTriggerCode: "LLM_TIMEOUT" as string | null }));
vi.mock("../api/useAiOperations", () => ({ useAiRequestDetail: () => ({ loading: false,
  error: false, data: { aiRequestId: id, reportStatus: "FAILED", executionId: id,
    executionShared: true, cacheHit: false, fallbackUsed: detailData.fallbackUsed,
    failureCode: "TIMEOUT", fallbackTriggerCode: detailData.fallbackTriggerCode,
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
    expect(screen.getByText("기록된 attempt 없음", { exact: true })).toBeInTheDocument();
    expect(screen.getByText(/실제 호출 여부는 확인할 수 없습니다/)).toBeInTheDocument();
    expect(screen.queryByText("비용 미측정")).not.toBeInTheDocument();
  } finally {
    detailData.attempts = saved;
  }
});

it("labels the legacy fallback code without inventing a V17 trigger", () => {
  detailData.fallbackUsed = true;
  detailData.fallbackTriggerCode = null;
  try {
    render(<MemoryRouter initialEntries={[`/ai-operations/${id}`]}><AiRequestDetailPage /></MemoryRouter>);
    expect(screen.getByText("기존 저장 코드")).toBeInTheDocument();
    expect(screen.getByText("미기록 (기존 저장 형식)")).toBeInTheDocument();
  } finally {
    detailData.fallbackUsed = false;
    detailData.fallbackTriggerCode = "LLM_TIMEOUT";
  }
});

it("does not query an invalid request identifier", () => {
  render(<MemoryRouter initialEntries={["/ai-operations/invalid"]}><AiRequestDetailPage /></MemoryRouter>);
  expect(screen.getByText(/찾을 수 없습니다|존재하지/)).toBeInTheDocument();
});
