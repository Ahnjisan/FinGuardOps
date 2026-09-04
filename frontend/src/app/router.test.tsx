import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor, within } from "@testing-library/react";
import { routes } from "./router";
import { safeAuthErrorMessage } from "../auth/authErrors";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { renderRoutesWithAuth } from "../test/renderWithAuth";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  vi.spyOn(window.history, "replaceState").mockImplementation(() => undefined);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

/** What the shell shows while `AuthProvider` is still in `initializing`. */
const PREPARING = "Preparing sign-in...";

/** What the shell shows while a sign-in or callback is in flight. */
const SIGNING_IN = "Signing in...";

function authStatus(): HTMLElement {
  return screen.getByRole("status", { name: "Authentication status" });
}

interface RenderAtOptions {
  readonly client?: FakeAuthClient;
  /** How the controlled initialization settles. */
  readonly initialize?: "resolve" | "reject";
  /** The status shown before initialization settles. */
  readonly pendingStatus?: string;
  /**
   * The auth state the shell must have reached once everything has settled:
   * either signed out and offering the button, or a specific fixed status.
   */
  readonly settled: "signed-out" | { readonly status: string };
}

/**
 * Renders the router under a real `AuthProvider` whose initialization this test
 * controls, and asserts the transition across it.
 *
 * Initialization is deliberately deferred rather than resolved for us, so the
 * pending state is a fact the test observes rather than a race it hopes to win.
 * The assertions after the `act` block are what hold the settlement in place:
 * drop the block and the shell is still showing the pending status, so the
 * final-state assertion fails outright instead of merely warning on stderr.
 */
async function renderAt(path: string, options: RenderAtOptions) {
  const {
    client = createFakeAuthClient(),
    initialize = "resolve",
    pendingStatus = PREPARING,
    settled,
  } = options;
  const deferred = client.deferInitialize();

  const view = renderRoutesWithAuth(routes, { client, initialEntries: [path] });

  // Initialization has not settled yet, and the shell says exactly that.
  expect(authStatus()).toHaveTextContent(pendingStatus);

  await act(async () => {
    if (initialize === "resolve") {
      deferred.resolve({ session: null });
    } else {
      deferred.reject(new Error("initialize failed"));
    }
    await deferred.promise.catch(() => undefined);
    await Promise.resolve();
    await Promise.resolve();
  });

  // Settled: the pending status is gone and a final auth state is on screen.
  expect(authStatus()).not.toHaveTextContent(pendingStatus);
  if (settled === "signed-out") {
    expect(authStatus()).toBeEmptyDOMElement();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
  } else {
    expect(authStatus()).toHaveTextContent(settled.status);
  }

  return { view, client };
}

describe("app router", () => {
  it("renders HomePage at the root path", async () => {
    await renderAt("/", { settled: "signed-out" });

    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
  });

  it("renders HealthPage at /health", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    await renderAt("/health", { settled: "signed-out" });

    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /backend health/i })).toBeInTheDocument();
    await waitFor(() => {
      expect(within(screen.getByRole("main")).getByRole("status")).toHaveTextContent(/healthy/i);
    });
  });

  it("renders the callback screen at /auth/callback", async () => {
    // The callback route claims the pending status before initialization even
    // settles, and there is no authorization response in the address bar here,
    // so it converges on the fixed callback failure rather than hanging.
    const { client } = await renderAt("/auth/callback", {
      pendingStatus: SIGNING_IN,
      settled: { status: safeAuthErrorMessage("callback") },
    });

    expect(screen.getByRole("heading", { name: /signing in/i })).toBeInTheDocument();
    expect(client.calls.completeSignIn).toHaveLength(0);
  });

  it("renders NotFoundPage for an unmatched path", async () => {
    await renderAt("/does-not-exist", { settled: "signed-out" });

    expect(screen.getByRole("heading", { name: /page not found/i })).toBeInTheDocument();
  });

  it("has no logout callback route", async () => {
    await renderAt("/auth/logout/callback", { settled: "signed-out" });

    expect(screen.getByRole("heading", { name: /page not found/i })).toBeInTheDocument();
  });

  it("has no dedicated login route", async () => {
    await renderAt("/login", { settled: "signed-out" });

    expect(screen.getByRole("heading", { name: /page not found/i })).toBeInTheDocument();
  });

  it("has no silent renew callback route", async () => {
    await renderAt("/auth/silent-renew", { settled: "signed-out" });

    expect(screen.getByRole("heading", { name: /page not found/i })).toBeInTheDocument();
  });

  it("renders the primary navigation landmark provided by AppShell", async () => {
    await renderAt("/", { settled: "signed-out" });

    expect(screen.getByRole("navigation", { name: /primary/i })).toBeInTheDocument();
  });
});

describe("public route boundary", () => {
  it("reaches the Authorization Server on no route by itself", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const client = createFakeAuthClient();

    await renderAt("/", { client, settled: "signed-out" });

    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.signIn).toHaveLength(0);
    expect(client.calls.completeSignIn).toHaveLength(0);
  });

  it("issues exactly one backend request on /health, with no Authorization header", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(jsonResponse({ status: "UP", service: "backend" }));
    vi.stubGlobal("fetch", fetchSpy);
    const client = createFakeAuthClient();

    await renderAt("/health", { client, settled: "signed-out" });
    await waitFor(() => {
      expect(within(screen.getByRole("main")).getByRole("status")).toHaveTextContent(/healthy/i);
    });

    expect(fetchSpy).toHaveBeenCalledTimes(1);
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit | undefined];
    expect(url).toBe("http://localhost:8080/api/health");
    const headers = new Headers(init?.headers ?? {});
    expect(headers.has("Authorization")).toBe(false);
    expect(headers.has("authorization")).toBe(false);
    expect(client.calls.signIn).toHaveLength(0);
  });

  it("does not complete a callback outside the callback route", async () => {
    const client = createFakeAuthClient();

    await renderAt("/does-not-exist", { client, settled: "signed-out" });

    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(client.calls.completeSignIn).toHaveLength(0);
  });

  it("keeps the public outlet usable when authentication initialization fails", async () => {
    const client = createFakeAuthClient();

    await renderAt("/", {
      client,
      initialize: "reject",
      settled: { status: safeAuthErrorMessage("configuration") },
    });

    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Health" })).toBeInTheDocument();
  });
});
