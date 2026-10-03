import { render, screen } from "@testing-library/react";
import { expect, it } from "vitest";
import { AdoptedDetectionPanel } from "./AdoptedDetectionSection";

const transactionId = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";

it("shows only the adopted version and keeps a later analysis separate", () => {
  render(<AdoptedDetectionPanel onRetry={() => undefined} state={{ status: "success", data: {
    transactionId, availability: "AVAILABLE", latestDetectionResultVersion: 2,
    latestAnalysisStatus: "IN_PROGRESS", adoptedResult: {
      detectionResultId: "7f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430101",
      detectionResultVersion: 1, riskLevel: "HIGH", riskScore: 55,
      analysisCompletedAt: "2026-07-23T01:15:32Z", ruleSetVersion: "rule-v1",
      scoringPolicyVersion: "scoring-policy-v1", ruleEvidence: [
        { ruleCode: "R001", ruleVersion: "1", reasonCode: "TRANSFER_ABSOLUTE_HIGH_AMOUNT",
          scoreContribution: 15 },
        { ruleCode: "R003", ruleVersion: "1", reasonCode: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
          scoreContribution: 40 },
      ],
    },
  } }} />);
  expect(screen.getByText(/이후 탐지 실행 버전 2/)).toHaveTextContent("IN_PROGRESS");
  expect(screen.getByText("55 / 100")).toBeInTheDocument();
  expect(screen.getByText("HIGH")).toBeInTheDocument();
  expect(screen.getByText(/개별 기여도의 합은/)).toBeInTheDocument();
  expect(document.querySelector("time")?.getAttribute("datetime")).toBe("2026-07-23T01:15:32Z");
  expect(screen.queryByText(/사기 확정률/)).not.toBeInTheDocument();
});

it("shows failed analysis without a synthetic risk grade", () => {
  render(<AdoptedDetectionPanel onRetry={() => undefined} state={{ status: "success", data: {
    transactionId, availability: "FAILED", latestDetectionResultVersion: 1,
    latestAnalysisStatus: "FAILED", adoptedResult: null,
  } }} />);
  expect(screen.getByText(/최근 탐지 분석이 실패/)).toBeInTheDocument();
  expect(screen.queryByText("위험 등급")).not.toBeInTheDocument();
});
