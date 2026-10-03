import { act, fireEvent, screen, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useLocation } from "react-router-dom";
import { createFakeAuthClient } from "../../test/fakeAuthClient";
import { jsonResponse } from "../../test/mockFetch";
import { renderRoutesWithAuth } from "../../test/renderWithAuth";
import type { NonEmptyUserRoles } from "../../auth/userRoles";

const adapter = vi.hoisted(() => ({ client: null as unknown }));
vi.mock("../../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => adapter.client }));
const { HomePage } = await import("../HomePage");

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const TRACE_ID = "trace_home_case_01";
const row = {
  caseId: CASE_ID, caseStatus: "OPEN", finalDisposition: null,
  assigneeRef: null, relatedTransactionCount: 1,
  createdAt: "2026-07-23T01:15:30Z", lastChangedAt: "2026-07-24T02:20:40Z",
};

function body(size: number, totalElements: number, content: unknown[] = []) {
  return {
    content,
    page: { number: 0, size, totalElements,
      totalPages: totalElements === 0 ? 0 : Math.ceil(totalElements / size),
      first: true, last: totalElements <= size },
    traceId: TRACE_ID,
  };
}

interface Call {
  readonly request: Request;
  answer(response: Response): void;
}

function controlledFetch() {
  const calls: Call[] = [];
  vi.stubGlobal("fetch", vi.fn().mockImplementation((request: Request) => new Promise<Response>((resolve) => {
    calls.push({ request, answer: resolve });
  })));
  return calls;
}

async function flush() {
  await act(async () => { await Promise.resolve(); await Promise.resolve(); });
}

async function answer(call: Call, response: Response) {
  await act(async () => { call.answer(response); await Promise.resolve(); await Promise.resolve(); });
}

function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname}{location.search}</p>;
}

function renderHome(roles: NonEmptyUserRoles = ["FDS_ANALYST"]) {
  const client = createFakeAuthClient({ initialSession: { subject: "home-test", roles } });
  adapter.client = client;
  renderRoutesWithAuth([
    { path: "/", element: <HomePage /> },
    { path: "/cases", element: <LocationProbe /> },
    { path: "/cases/:caseId", element: <LocationProbe /> },
  ], { client });
  return client;
}

beforeEach(() => vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080"));
afterEach(() => { vi.unstubAllGlobals(); vi.unstubAllEnvs(); vi.restoreAllMocks(); });

describe("Home case work overview", () => {
  it("does not mount case reads before authentication is confirmed", async () => {
    const calls = controlledFetch();
    const session = { subject: "confirmed", roles: ["FDS_VIEWER"] as const };
    const client = createFakeAuthClient({ initialSession: session });
    const initialization = client.deferInitialize();
    adapter.client = client;
    renderRoutesWithAuth([{ path: "/", element: <HomePage /> }], { client });
    await flush();
    expect(calls).toHaveLength(0);
    expect(screen.queryByRole("heading", { name: "현재 사건 현황" })).not.toBeInTheDocument();
    await act(async () => {
      initialization.resolve({ session });
      await initialization.promise;
    });
    await flush();
    expect(calls).toHaveLength(2);
  });

  it.each(["FDS_VIEWER", "FDS_ANALYST", "FDS_APPROVER"] as const)(
    "uses exactly one OPEN response for count and preview and one information-required response for %s", async (role) => {
      const calls = controlledFetch();
      renderHome([role]);
      await flush();
      expect(calls).toHaveLength(2);
      const byStatus = new Map(calls.map((call) => [new URL(call.request.url).searchParams.get("caseStatus"), call]));
      const open = byStatus.get("OPEN")!;
      const information = byStatus.get("ADDITIONAL_INFORMATION_REQUIRED")!;
      expect(new URL(open.request.url).searchParams.toString()).toBe("caseStatus=OPEN&page=0&size=5&sort=lastChangedAt%2Cdesc");
      expect(new URL(information.request.url).searchParams.toString()).toBe("caseStatus=ADDITIONAL_INFORMATION_REQUIRED&page=0&size=1&sort=lastChangedAt%2Cdesc");
      await answer(open, jsonResponse(body(5, 1, [row])));
      await answer(information, jsonResponse(body(1, 1, [{ ...row, caseStatus: "ADDITIONAL_INFORMATION_REQUIRED" }])));
      const cards = screen.getAllByRole("link", { name: /사건 목록/ });
      expect(cards[0]).toHaveAttribute("href", "/cases?caseStatus=OPEN");
      expect(cards[1]).toHaveAttribute("href", "/cases?caseStatus=ADDITIONAL_INFORMATION_REQUIRED");
      expect(screen.getAllByText("1건")).toHaveLength(2);
      const preview = screen.getByRole("list", { name: /OPEN 사건/ });
      expect(within(preview).getAllByRole("listitem")).toHaveLength(1);
      expect(within(preview).getByRole("link", { name: `사건 ${CASE_ID} 상세 보기` })).toHaveAttribute("href", `/cases/${CASE_ID}`);
      expect(calls).toHaveLength(2);
    },
  );

  it("keeps the successful OPEN preview during an independent information failure", async () => {
    const calls = controlledFetch();
    renderHome();
    await flush();
    const open = calls.find((call) => new URL(call.request.url).searchParams.get("caseStatus") === "OPEN")!;
    const information = calls.find((call) => call !== open)!;
    await answer(open, jsonResponse(body(5, 1, [row])));
    await answer(information, jsonResponse({ code: "FORBIDDEN" }, { status: 403 }));
    expect(screen.getByText("1건")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: `사건 ${CASE_ID} 상세 보기` })).toBeInTheDocument();
    expect(screen.getByText("조회 권한 없음")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "다시 시도" })).not.toBeInTheDocument();
  });

  it("removes prior counts and rows as soon as the session is invalidated", async () => {
    const calls = controlledFetch();
    const client = renderHome();
    await flush();
    const open = calls.find((call) => new URL(call.request.url).searchParams.get("caseStatus") === "OPEN")!;
    await answer(open, jsonResponse(body(5, 1, [row])));
    expect(screen.getByRole("link", { name: `사건 ${CASE_ID} 상세 보기` })).toBeInTheDocument();
    await act(async () => { client.emitSessionInvalidated(); });
    expect(screen.queryByRole("heading", { name: "현재 사건 현황" })).not.toBeInTheDocument();
    expect(screen.queryByText("1건")).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: `사건 ${CASE_ID} 상세 보기` })).not.toBeInTheDocument();
  });

  it("shows two independent errors after both requests fail", async () => {
    const calls = controlledFetch();
    renderHome();
    await flush();
    for (const call of calls) await answer(call, jsonResponse({}, { status: 500 }));
    expect(screen.getAllByText("조회 실패")).toHaveLength(2);
    expect(document.querySelectorAll(".home-work__card strong")).toHaveLength(0);
    expect(screen.queryByRole("link", { name: `사건 ${CASE_ID} 상세 보기` })).not.toBeInTheDocument();
  });

  it("distinguishes zero rows and offers manual retry after a retryable failure", async () => {
    const calls = controlledFetch();
    renderHome();
    await flush();
    const open = calls.find((call) => new URL(call.request.url).searchParams.get("caseStatus") === "OPEN")!;
    const information = calls.find((call) => call !== open)!;
    await answer(open, jsonResponse(body(5, 0)));
    await answer(information, jsonResponse({}, { status: 500 }));
    expect(screen.getByText("0건")).toBeInTheDocument();
    expect(screen.getByText("표시할 OPEN 사건이 없습니다.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "다시 시도" }));
    await flush();
    expect(calls).toHaveLength(3);
  });

  it("never mounts a case request for an operations-only role", async () => {
    const calls = controlledFetch();
    renderHome(["PLATFORM_ADMIN"]);
    await flush();
    expect(calls).toHaveLength(0);
    expect(screen.queryByRole("heading", { name: "현재 사건 현황" })).not.toBeInTheDocument();
  });
});
