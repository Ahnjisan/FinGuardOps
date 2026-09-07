import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { safeAuthErrorMessage } from "../auth/authErrors";
import type { AuthSession } from "../auth/authClient";
import type { UserRole } from "../auth/userRoles";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { renderRoutesWithAuth } from "../test/renderWithAuth";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";

/**
 * The transaction screen reaches for the OIDC adapter at its own credential
 * boundary. Standing in here keeps these route tests about routing, and keeps
 * them from building a real `UserManager` for a route they only want to see
 * rendered or refused.
 */
const adapter = vi.hoisted(() => ({ client: null as unknown }));

vi.mock("../auth/oidcAuthClient", () => ({
  getOidcAuthClient: () => adapter.client,
}));

const { routes } = await import("./router");

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  vi.spyOn(window.history, "replaceState").mockImplementation(() => undefined);
  adapter.client = null;
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

const ANALYST_SUBJECT = "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f";

const TRANSACTION_ROLES: readonly UserRole[] = ["FDS_VIEWER", "FDS_ANALYST", "FDS_APPROVER"];
const NON_TRANSACTION_ROLES: readonly UserRole[] = [
  "RULE_OPERATOR",
  "RECOVERY_OPERATOR",
  "PLATFORM_ADMIN",
];

/** Renders the production routes at a path, with a session already in place. */
function renderSignedInAt(path: string, roles: readonly UserRole[]) {
  const session: AuthSession = {
    subject: ANALYST_SUBJECT,
    displayName: "Local Analyst",
    roles: roles as AuthSession["roles"],
  };
  const client = createFakeAuthClient({ initialSession: session });
  adapter.client = client;
  const view = renderRoutesWithAuth(routes, { client, initialEntries: [path] });
  return { client, view };
}

describe("the /transactions production route", () => {
  it.each(TRANSACTION_ROLES)("renders the screen on direct entry for %s", async (role) => {
    const fetchSpy = vi.fn().mockImplementation(() => new Promise<Response>(() => {}));
    vi.stubGlobal("fetch", fetchSpy);

    renderSignedInAt("/transactions", [role]);

    expect(
      await screen.findByRole("heading", { name: "Transactions", level: 2 }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Apply filters" })).toBeInTheDocument();
  });

  it.each(NON_TRANSACTION_ROLES)("refuses direct entry for %s, sending nothing", async (role) => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    const { client } = renderSignedInAt("/transactions", [role]);

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Transactions", level: 2 }),
    ).not.toBeInTheDocument();
    // The refusal costs the Backend nothing at all, and takes no credential.
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
    // It also says nothing about which role would have worked.
    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain(role);
    expect(rendered).not.toContain("transaction:view");
  });

  it("asks an unauthenticated visitor to sign in, and sends nothing", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    renderRoutesWithAuth(routes, { client, initialEntries: ["/transactions"] });

    expect(await screen.findByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.signIn).toHaveLength(0);
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("shows neither the screen nor a refusal while authentication is undecided", () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;
    client.deferInitialize();

    renderRoutesWithAuth(routes, { client, initialEntries: ["/transactions"] });

    expect(authStatus()).toHaveTextContent(PREPARING);
    expect(screen.getByText("Checking access...")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Access denied" })).not.toBeInTheDocument();
    expect(
      screen.queryByRole("heading", { name: "Transactions", level: 2 }),
    ).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("removes the screen the moment the session is invalidated", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => new Promise<Response>(() => {})),
    );
    const { client } = renderSignedInAt("/transactions", ["FDS_ANALYST"]);
    await screen.findByRole("heading", { name: "Transactions", level: 2 });

    act(() => {
      client.emitSessionInvalidated();
    });

    expect(screen.getByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Apply filters" })).not.toBeInTheDocument();
  });

  it("returns to exactly /transactions after signing in from it", async () => {
    const user = userEvent.setup();
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    renderRoutesWithAuth(routes, { client, initialEntries: ["/transactions"] });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(client.calls.signIn).toEqual(["/transactions"]);
  });

  it("does not treat a path under /transactions as the transactions route", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    renderSignedInAt("/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001", ["FDS_ANALYST"]);

    // There is no detail route in this scope, so the nested path is a 404
    // rather than a screen that half exists.
    expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("does not treat a sibling path sharing the prefix as the transactions route", async () => {
    vi.stubGlobal("fetch", vi.fn());

    renderSignedInAt("/transactionsx", ["FDS_ANALYST"]);

    expect(await screen.findByRole("heading", { name: "Page not found" })).toBeInTheDocument();
  });

  it("offers the destination in the rail and reaches it by keyboard", async () => {
    const user = userEvent.setup();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => new Promise<Response>(() => {})),
    );
    renderSignedInAt("/", ["FDS_VIEWER"]);

    const link = await screen.findByRole("link", { name: "Transactions" });
    link.focus();
    await user.keyboard("{Enter}");

    expect(
      await screen.findByRole("heading", { name: "Transactions", level: 2 }),
    ).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Transactions" })).toHaveAttribute(
      "aria-current",
      "page",
    );
  });
});
