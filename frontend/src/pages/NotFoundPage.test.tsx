import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { NotFoundPage } from "./NotFoundPage";
import { renderWithRouter } from "../test/renderWithRouter";

describe("NotFoundPage", () => {
  it("renders a not found heading", () => {
    renderWithRouter([{ path: "/", element: <NotFoundPage /> }]);

    expect(screen.getByRole("heading", { name: /page not found/i })).toBeInTheDocument();
  });

  it("provides an accessible link back home", () => {
    renderWithRouter([{ path: "/", element: <NotFoundPage /> }]);

    const link = screen.getByRole("link", { name: /return home/i });
    expect(link).toHaveAttribute("href", "/");
  });
});
