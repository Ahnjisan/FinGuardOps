import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { expect, it, vi } from "vitest";

const id = "33333333-3333-4333-8333-333333333333";
const detailData = vi.hoisted(() => ({ attempts: [{ attemptNumber: 1, provider: "OLLAMA_LOCAL",
  model: null, outcome: "TIMEOUT", inputTokens: null, outputTokens: null, latencyMs: 4000 }],
  fallbackUsed: false, fallbackTriggerCode: "LLM_TIMEOUT" as string | null }));
const outboxData = vi.hoisted(() => ({ diagnostic: null as null | {
  eventId: string; outboxStatus: string; executionStatus: string;
  failureCode: string | null; rejectionReason: string | null;
  requests: { aiRequestId: string; status: string }[];
  reportExists: boolean; attemptExists: boolean; requeueAllowed: boolean },
  accepted: false, canRequeue: false, error: null as null | "conflict" | "forbidden" | "not-found" | "other",
  requeue: vi.fn(), refresh: vi.fn() }));
vi.mock("../api/useAiOperations", () => ({ useAiOutbox: () => ({
  data: outboxData.diagnostic, error: outboxData.error, accepted: outboxData.accepted,
  busy: false, canRequeue: outboxData.canRequeue, requeue: outboxData.requeue,
  refresh: outboxData.refresh }), useAiRequestDetail: () => ({ loading: false,
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

it("shows rejection and limits the action to permitted operators", () => {
  outboxData.diagnostic = { eventId: "44444444-4444-4444-8444-444444444444",
    outboxStatus: "BLOCKED", executionStatus: "PENDING", failureCode: "PUBLISH_FAILED",
    rejectionReason: "REQUEST_MISMATCH", requests: [{ aiRequestId: id, status: "FAILED" }],
    reportExists: false, attemptExists: false, requeueAllowed: false };
  outboxData.canRequeue = true;
  try {
    render(<MemoryRouter initialEntries={[`/ai-operations/${id}`]}><AiRequestDetailPage /></MemoryRouter>);
    expect(screen.getByText("REQUEST_MISMATCH")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "단건 재대기" })).toBeDisabled();
  } finally { outboxData.diagnostic = null; outboxData.canRequeue = false; }
});

it("shows 202 as DB waiting and never describes PUBLISHED as completion", () => {
  outboxData.diagnostic = { eventId: "44444444-4444-4444-8444-444444444444",
    outboxStatus: "PUBLISHED", executionStatus: "PENDING", failureCode: null,
    rejectionReason: "OUTBOX_NOT_BLOCKED", requests: [{ aiRequestId: id, status: "PENDING" }],
    reportExists: false, attemptExists: false, requeueAllowed: false };
  outboxData.accepted = true;
  try {
    render(<MemoryRouter initialEntries={[`/ai-operations/${id}`]}><AiRequestDetailPage /></MemoryRouter>);
    expect(screen.getByText(/DB 발행 대기만 수락/)).toBeInTheDocument();
    expect(screen.getByText(/PUBLISHED는 broker 발행 표시/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "상태 다시 조회" }));
    expect(outboxData.refresh).toHaveBeenCalled();
  } finally { outboxData.diagnostic = null; outboxData.accepted = false; }
});

it("offers one action only for an eligible platform operator", () => {
  outboxData.diagnostic = { eventId: "44444444-4444-4444-8444-444444444444",
    outboxStatus: "BLOCKED", executionStatus: "PENDING", failureCode: "PUBLISH_FAILED",
    rejectionReason: null, requests: [{ aiRequestId: id, status: "PENDING" }],
    reportExists: false, attemptExists: false, requeueAllowed: true };
  outboxData.canRequeue = true;
  try {
    render(<MemoryRouter initialEntries={[`/ai-operations/${id}`]}><AiRequestDetailPage /></MemoryRouter>);
    fireEvent.click(screen.getByRole("button", { name: "단건 재대기" }));
    expect(outboxData.requeue).toHaveBeenCalledTimes(1);
  } finally { outboxData.diagnostic = null; outboxData.canRequeue = false;
    outboxData.requeue.mockClear(); }
});

it("blocks a stale action after 409 and keeps refresh available after 403", () => {
  outboxData.diagnostic = { eventId: "44444444-4444-4444-8444-444444444444",
    outboxStatus: "BLOCKED", executionStatus: "PENDING", failureCode: "PUBLISH_FAILED",
    rejectionReason: null, requests: [{ aiRequestId: id, status: "PENDING" }],
    reportExists: false, attemptExists: false, requeueAllowed: true };
  outboxData.canRequeue = true;
  outboxData.error = "conflict";
  try {
    const view = render(<MemoryRouter initialEntries={[`/ai-operations/${id}`]}><AiRequestDetailPage /></MemoryRouter>);
    expect(screen.getByText("재대기 조건이 변경됐습니다. 현재 상태를 다시 확인하세요.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "단건 재대기" })).toBeDisabled();
    outboxData.diagnostic = null;
    outboxData.error = "forbidden";
    view.rerender(<MemoryRouter initialEntries={[`/ai-operations/${id}`]}><AiRequestDetailPage /></MemoryRouter>);
    expect(screen.getByText("AI outbox 조회 또는 재대기 권한이 없습니다.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "상태 다시 조회" }));
    expect(outboxData.refresh).toHaveBeenCalled();
  } finally {
    outboxData.diagnostic = null; outboxData.error = null; outboxData.canRequeue = false;
    outboxData.refresh.mockClear();
  }
});
