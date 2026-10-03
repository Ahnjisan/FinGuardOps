import { describe, expect, it } from "vitest";
import { act, screen } from "@testing-library/react";
import { HomePage } from "./HomePage";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { renderRoutesWithAuth } from "../test/renderWithAuth";
import type { NonEmptyUserRoles } from "../auth/userRoles";

function renderHome(roles: NonEmptyUserRoles = ["FDS_VIEWER"]) {
  renderRoutesWithAuth(
    [{ path: "/", element: <HomePage /> }],
    {
      client: createFakeAuthClient({
        initialSession: { subject: "fixture-subject", roles },
      }),
    },
  );
}

describe("HomePage", () => {
  it("renders the home heading", async () => {
    renderHome();

    await act(async () => { await Promise.resolve(); });

    expect(screen.getByRole("heading", { name: "FinGuardOps" })).toBeInTheDocument();
    expect(screen.queryByText(/Business screens are not implemented yet/i)).not.toBeInTheDocument();
  });

  it("provides an accessible link to the health page", async () => {
    renderHome();

    await act(async () => { await Promise.resolve(); });

    const link = screen.getByRole("link", { name: /서비스 상태 확인/i });
    expect(link).toHaveAttribute("href", "/health");
  });

  it("offers the case and transaction routes to an FDS viewer", async () => {
    renderHome();

    expect(await screen.findByRole("link", { name: /사건을 찾고 현재 상태를 확인합니다/ })).toHaveAttribute("href", "/cases");
    expect(screen.getByRole("link", { name: /거래 기록을 조회합니다/ })).toHaveAttribute("href", "/transactions");
  });

  it("shows only destinations available to the signed-in role", async () => {
    renderHome(["PLATFORM_ADMIN"]);

    await act(async () => {
      await Promise.resolve();
    });

    expect(screen.getByRole("link", { name: "서비스 상태 확인" })).toHaveAttribute("href", "/health");
    expect(screen.queryByRole("link", { name: /사건/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: /거래/i })).not.toBeInTheDocument();
  });
});
