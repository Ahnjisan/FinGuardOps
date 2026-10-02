import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { NotFoundPage } from "./NotFoundPage";
import { renderWithRouter } from "../test/renderWithRouter";

describe("NotFoundPage", () => {
  it("renders a not found heading", () => {
    renderWithRouter([{ path: "/", element: <NotFoundPage /> }]);

    expect(screen.getByRole("heading", { name: /페이지를 찾을 수 없습니다/i })).toBeInTheDocument();
  });

  it("provides an accessible link back home", () => {
    renderWithRouter([{ path: "/", element: <NotFoundPage /> }]);

    const link = screen.getByRole("link", { name: /홈으로 돌아가기/i });
    expect(link).toHaveAttribute("href", "/");
  });
});
