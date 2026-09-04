import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { RouteObject } from "react-router-dom";
import { AppShell } from "./AppShell";
import { HomePage } from "../pages/HomePage";
import { HealthPage } from "../pages/HealthPage";
import { safeAuthErrorMessage } from "../auth/authErrors";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { renderRoutesWithAuth } from "../test/renderWithAuth";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";

const ROUTES: RouteObject[] = [
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <HomePage /> },
      { path: "health", element: <HealthPage /> },
    ],
  },
];

function renderShell(client: FakeAuthClient, path = "/") {
  return renderRoutesWithAuth(ROUTES, { client, initialEntries: [path] });
}

function authStatus(): HTMLElement {
  return screen.getByRole("status", { name: "Authentication status" });
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("AppShell navigation", () => {
  it("keeps the primary navigation landmark", async () => {
    renderShell(createFakeAuthClient());

    expect(screen.getByRole("navigation", { name: /primary/i })).toBeInTheDocument();
    await waitFor(() => {
      expect(authStatus()).toBeInTheDocument();
    });
  });

  it("keeps the public links reachable while unauthenticated", async () => {
    renderShell(createFakeAuthClient());

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: "Home" })).toHaveAttribute("href", "/");
    expect(screen.getByRole("link", { name: "Health" })).toHaveAttribute("href", "/health");
  });
});

describe("AppShell authentication controls", () => {
  it("announces the initializing state", () => {
    const client = createFakeAuthClient();
    client.deferInitialize();
    renderShell(client);

    expect(authStatus()).toHaveTextContent("Preparing sign-in...");
    expect(screen.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("offers sign-in once unauthenticated", async () => {
    renderShell(createFakeAuthClient());

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    expect(screen.queryByRole("button", { name: "Sign out" })).not.toBeInTheDocument();
  });

  it("starts sign-in with the current public route as the return target", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    renderShell(client, "/health");

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(client.calls.signIn).toEqual(["/health"]);
  });

  it("announces the authenticating state and withdraws the sign-in button", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(authStatus()).toHaveTextContent("Signing in...");
    expect(screen.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("offers sign-out and a display name once authenticated", async () => {
    const client = createFakeAuthClient({
      initialSession: { subject: "sub-1", displayName: "Test Analyst" },
    });
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    expect(authStatus()).toHaveTextContent("Signed in as Test Analyst.");
    expect(screen.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("says only that the user is signed in when there is no display name", async () => {
    renderShell(createFakeAuthClient({ initialSession: { subject: "sub-1" } }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    expect(authStatus()).toHaveTextContent("Signed in.");
  });

  it("signs out on request and returns to the sign-in control", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1" } });
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(client.calls.signOut).toBe(1);
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
  });

  it("returns to the sign-in control when the session is invalidated", async () => {
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1" } });
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    act(() => {
      client.emitSessionInvalidated();
    });

    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(client.calls.signIn).toHaveLength(0);
  });

  it("shows a fixed message and still allows retry after an error", async () => {
    const client = createFakeAuthClient();
    client.failInitialize();
    renderShell(client);

    await waitFor(() => {
      expect(authStatus()).toHaveTextContent(safeAuthErrorMessage("configuration"));
    });
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
  });
});

describe("AppShell public boundary", () => {
  it("keeps the home outlet visible while authentication is in error", async () => {
    const client = createFakeAuthClient();
    client.failInitialize();
    renderShell(client);

    await waitFor(() => {
      expect(authStatus()).toHaveTextContent(safeAuthErrorMessage("configuration"));
    });
    expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
  });

  it("keeps the health outlet reachable while unauthenticated", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));
    renderShell(createFakeAuthClient(), "/health");

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    const main = within(screen.getByRole("main"));
    expect(main.getByRole("heading", { name: /backend health/i })).toBeInTheDocument();
    // The page keeps its own status region, distinct from the auth one.
    expect(main.getByRole("status")).toBeInTheDocument();
  });

  it("never renders a subject, token or provider payload", async () => {
    const client = createFakeAuthClient({
      initialSession: { subject: "11111111-1111-4111-8111-111111111111", displayName: "Analyst" },
    });
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("11111111-1111-4111-8111-111111111111");
    expect(rendered).not.toMatch(/[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{10,}/);
    expect(document.body.innerHTML).not.toMatch(/bearer|access_token|id_token/i);
  });
});
