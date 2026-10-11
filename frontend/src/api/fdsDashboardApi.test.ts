import { describe, expect, it } from "vitest";
import { isCaseDashboard, isTransactionDashboard } from "./fdsDashboardApi";
import { buildBackendRequestUrl } from "./backendEndpoints";

const base = { from: "2026-09-10", to: "2026-09-11", timeZone: "Asia/Seoul",
  asOf: "2026-10-12T00:00:00Z", traceId: "trace_dashboard_test" };
const levels = { LOW: 0, MEDIUM: 1, HIGH: 0, CRITICAL: 0 };
const scenarios = { SCN001: 1, SCN002: 1, SCN003: 0, SCN004: 0,
  SCN005: 0, SCN006: 0, SCN007: 0, SCN008: 0 };

describe("FDS dashboard response", () => {
  it("accepts two scenario hits on one adopted transaction and rejects leaked fields", () => {
    const body = { ...base, days: [{ date: base.from, total: 2, unclassified: 1,
      classified: 1, riskLevels: levels, scenarios }] };
    expect(isTransactionDashboard(body)).toBe(true);
    expect(isTransactionDashboard({ ...body, customerRef: "secret" })).toBe(false);
    expect(isTransactionDashboard({ ...body, days: [{ ...body.days[0], total: 3 }] })).toBe(false);
    expect(isTransactionDashboard({ ...body, days: [{ ...body.days[0], scenarios: { ...scenarios, SCN001: 2 } }] })).toBe(false);
  });

  it("requires zero-filled days and current case status cohort sum", () => {
    const body = { ...base, days: [{ date: base.from, newCases: 1 }],
      currentStatuses: { OPEN: 1, IN_REVIEW: 0, ADDITIONAL_INFORMATION_REQUIRED: 0, CLOSED: 0 } };
    expect(isCaseDashboard(body)).toBe(true);
    expect(isCaseDashboard({ ...body, currentStatuses: { ...body.currentStatuses, CLOSED: 1 } })).toBe(false);
    expect(isCaseDashboard({ ...body, days: [] })).toBe(false);
  });

  it("accepts only bounded KST date filters in the endpoint registry", () => {
    expect(buildBackendRequestUrl("http://localhost:8080", "fds-dashboard-transactions", undefined,
      { from: "2026-09-10", to: "2026-09-11" }).url).toContain("from=2026-09-10&to=2026-09-11");
    expect(() => buildBackendRequestUrl("http://localhost:8080", "fds-dashboard-transactions", undefined,
      { from: "2026-09-10", to: "2026-10-12" })).toThrow();
  });
});
