import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { routes } from "./router";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

function renderAt(path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  return render(<RouterProvider router={router} />);
}

describe("app router", () => {
  it("renders HomePage at the root path", () => {
    renderAt("/");

    expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
  });

  it("renders HealthPage at /health", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    renderAt("/health");

    expect(screen.getByRole("heading", { name: /backend health/i })).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/healthy/i);
    });
  });

  it("renders NotFoundPage for an unmatched path", () => {
    renderAt("/does-not-exist");

    expect(screen.getByRole("heading", { name: /page not found/i })).toBeInTheDocument();
  });

  it("renders the primary navigation landmark provided by AppShell", () => {
    renderAt("/");

    expect(screen.getByRole("navigation", { name: /primary/i })).toBeInTheDocument();
  });
});
