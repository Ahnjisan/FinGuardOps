import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { expect, it, vi } from "vitest";
import { CaseAiReportPanel } from "./CaseAiReportSection";
import type { AiReportCurrent } from "../../api/aiReportApi";

const caseId = "11111111-1111-4111-8111-111111111111";
const reportId = "22222222-2222-4222-8222-222222222222";
const requestId = "33333333-3333-4333-8333-333333333333";
const current: AiReportCurrent = {
  caseId,
  currentReport: {
    reportId, executionId: "44444444-4444-4444-8444-444444444444",
    initiatingAiRequestId: "55555555-5555-4555-8555-555555555555", caseId,
    detectionResultVersion: 2, reportStatus: "FALLBACK_COMPLETED",
    reportSource: "TEMPLATE_FALLBACK", summary: "채택 RULE 기반 초안",
    keyReasons: [{ reasonCode: "RISK_RULE", description: "RULE 근거 확인" }],
    timelineSummary: "공개된 행동 타임라인 자료가 없어 요약하지 않았습니다.",
    investigationChecklist: ["원거래 확인"], promptVersion: "prompt-1",
    modelVersion: "local-opaque", generatedAt: "2026-10-05T00:00:00Z",
    failureCode: "TIMEOUT", traceId: "trace-test-001",
  },
  latestRequest: {
    aiRequestId: requestId, executionId: "66666666-6666-4666-8666-666666666666",
    executionShared: false, initiatingAiRequestId: requestId, reportId: null,
    caseId, detectionResultVersion: 3,
    reportStatus: "GENERATING", reportSource: null, sourceAiRequestId: null, cacheHit: false,
    requestedAt: "2026-10-05T01:00:00Z", generatedAt: null,
    failureCode: null, resultLocation: `/api/v1/cases/${caseId}/ai-reports/current`,
    traceId: "trace-test-002",
  },
  traceId: "trace-test-003",
};

it("distinguishes previous saved report from a newer generation request", () => {
  render(<CaseAiReportPanel current={current} loading={false} error={null} busy={false}
    canCreate onCreate={() => undefined} onRefresh={() => undefined} />);
  expect(screen.getByText(/이전에 저장된 리포트/)).toBeInTheDocument();
  expect(screen.getByText(/최근 요청: GENERATING · 탐지 버전 3/)).toBeInTheDocument();
  expect(screen.getByText(/출처: RULE 근거 템플릿 · 탐지 버전 2/)).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "리포트 생성 요청" })).toBeDisabled();
  expect(screen.queryByText(/0원/)).not.toBeInTheDocument();
});

it("hides create control without capability and keeps manual refresh keyboard operable", async () => {
  const user = userEvent.setup();
  const refresh = vi.fn();
  render(<CaseAiReportPanel current={null} loading={false} error={null} busy={false}
    canCreate={false} onCreate={() => undefined} onRefresh={refresh} />);
  expect(screen.queryByRole("button", { name: "리포트 생성 요청" })).not.toBeInTheDocument();
  await user.tab();
  expect(screen.getByRole("button", { name: "리포트 새로고침" })).toHaveFocus();
  await user.keyboard("{Enter}");
  expect(refresh).toHaveBeenCalledOnce();
});
