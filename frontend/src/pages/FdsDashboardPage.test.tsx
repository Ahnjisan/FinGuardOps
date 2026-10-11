import { describe, expect, it, vi } from "vitest";
import { screen, waitFor } from "@testing-library/react";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { renderRoutesWithAuth } from "../test/renderWithAuth";
import { ForbiddenError } from "../api/errors";

const mocks = vi.hoisted(() => ({ transactions: vi.fn(), cases: vi.fn() }));
vi.mock("../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => ({}) }));
vi.mock("../api/fdsDashboardApi", () => ({
  fetchTransactionDashboard: mocks.transactions,
  fetchCaseDashboard: mocks.cases,
}));
const { FdsDashboardPage } = await import("./FdsDashboardPage");

const common = { from: "2026-09-10", to: "2026-09-11", timeZone: "Asia/Seoul",
  asOf: "2026-10-12T00:00:00Z", traceId: "trace_dashboard_test" };
const transaction = { ...common, days: [{ date: common.from, total: 2, unclassified: 1,
  classified: 1, riskLevels: { LOW: 0, MEDIUM: 1, HIGH: 0, CRITICAL: 0 },
  scenarios: { SCN001: 1, SCN002: 1, SCN003: 0, SCN004: 0,
    SCN005: 0, SCN006: 0, SCN007: 0, SCN008: 0 } }] };
const cases = { ...common, days: [{ date: common.from, newCases: 1 }],
  currentStatuses: { OPEN: 1, IN_REVIEW: 0, ADDITIONAL_INFORMATION_REQUIRED: 0, CLOSED: 0 } };

function renderDashboard() {
  renderRoutesWithAuth([{ path: "/", element: <FdsDashboardPage /> }], {
    client: createFakeAuthClient({ initialSession: { subject: "dashboard-user", roles: ["FDS_ANALYST"] } }),
    strict: false,
  });
}

describe("FDS dashboard page", () => {
  it("pins one KST seven-day range for both requests across midnight", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-11T14:59:59.999Z"));
    try {
      mocks.transactions.mockResolvedValue(transaction);
      mocks.cases.mockResolvedValue(cases);
      renderDashboard();
      await waitFor(() => expect(mocks.transactions).toHaveBeenCalled());
      vi.setSystemTime(new Date("2026-10-11T15:00:00.001Z"));
      expect(mocks.transactions.mock.calls.at(-1)?.[1]).toEqual({
        from: "2026-10-05", to: "2026-10-12",
      });
      expect(mocks.cases.mock.calls.at(-1)?.[1]).toEqual(
        mocks.transactions.mock.calls.at(-1)?.[1]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("uses the same aggregate response for cards, trend and scenario counts", async () => {
    mocks.transactions.mockResolvedValue(transaction);
    mocks.cases.mockResolvedValue(cases);
    renderDashboard();
    expect(await screen.findByText(/전체 거래/)).toHaveTextContent("2");
    expect(screen.getByText(/SCN001: 1건/)).toBeInTheDocument();
    expect(screen.getByText(/SCN002: 1건/)).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "일별 거래·위험등급 건수" })).toHaveTextContent("2026-09-10");
    expect(screen.getByRole("table", { name: "일별 신규 사건 추세" })).toHaveTextContent("1");
    expect(screen.queryByText("synthetic-customer")).not.toBeInTheDocument();
  });

  it("keeps a successful section visible when the other is forbidden", async () => {
    mocks.transactions.mockResolvedValue(transaction);
    mocks.cases.mockRejectedValue(new ForbiddenError());
    renderDashboard();
    expect(await screen.findByText(/사건 집계 조회 권한이 없습니다/)).toBeInTheDocument();
    expect(screen.getByText(/전체 거래/)).toHaveTextContent("2");
  });

  it("distinguishes empty data from request failure", async () => {
    mocks.transactions.mockResolvedValue({ ...transaction, days: [{ ...transaction.days[0],
      total: 0, unclassified: 0, classified: 0,
      riskLevels: { LOW: 0, MEDIUM: 0, HIGH: 0, CRITICAL: 0 },
      scenarios: Object.fromEntries(Object.keys(transaction.days[0].scenarios).map(key => [key, 0])) }] });
    mocks.cases.mockRejectedValue(new Error("network"));
    renderDashboard();
    expect(await screen.findByText("선택 기간에 거래가 없습니다.")).toBeInTheDocument();
    expect(screen.getByText(/사건 집계 조회에 실패했습니다/)).toBeInTheDocument();
  });
});
