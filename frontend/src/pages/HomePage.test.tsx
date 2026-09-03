import { describe, expect, it } from "vitest";
import { screen } from "@testing-library/react";
import { HomePage } from "./HomePage";
import { renderWithRouter } from "../test/renderWithRouter";

describe("HomePage", () => {
  it("renders the home heading", () => {
    renderWithRouter([{ path: "/", element: <HomePage /> }]);

    expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
  });

  it("provides an accessible link to the health page", () => {
    renderWithRouter([{ path: "/", element: <HomePage /> }]);

    const link = screen.getByRole("link", { name: /check backend health/i });
    expect(link).toHaveAttribute("href", "/health");
  });
});
