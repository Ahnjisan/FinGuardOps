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

  it("does not treat a deeper path under /transactions as a transactions route", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    renderSignedInAt("/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001/evidence", [
      "FDS_ANALYST",
    ]);

    // Two segments under `/transactions` is no route this application has, so
    // it is a 404 rather than a screen that half exists.
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

const CANONICAL_TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const CANONICAL_DETAIL_ROUTE = `/transactions/${CANONICAL_TRANSACTION_ID}`;

/** The fixed refusal a malformed transaction address produces. */
const INVALID_ADDRESS_HEADING = "This is not a transaction address";

/** Any origin: what is being modelled is the path, not where it points. */
const PARSER_ORIGIN = "https://console.example";

/**
 * What a standard URL parser leaves of an address - the same algorithm the
 * address bar, an `<a href>` and a redirect all go through before a single line
 * of this application runs.
 *
 * A `MemoryRouter` performs no such step: it publishes the string it is handed.
 * That makes it the right tool for asking what the screen does with a given
 * location, and the wrong tool for claiming a browser would ever produce one.
 * This function is how the tests below tell those two things apart.
 */
function parsedLocation(address: string): string {
  const url = new URL(address, PARSER_ORIGIN);
  return `${url.pathname}${url.search}${url.hash}`;
}

/**
 * The boundary in front of this application, stated as what it is.
 *
 * These assertions are about the browser, not about FinGuardOps. A dot segment,
 * a raw backslash and a removable control character are resolved away by the
 * URL parser, so the location React Router publishes is already the canonical
 * one and the original text never existed as far as this application is
 * concerned. It cannot recover it, cannot tell it apart from a click on a real
 * link, and nothing here claims it refused it: what it is left holding is the
 * canonical detail route, answered like any other under the same capability
 * guard and the same Backend authorization.
 *
 * Distinguishing a pre-parse request target is a hosting concern - a reverse
 * proxy or a web server in front of the SPA - and not something a React
 * application can implement.
 */
describe("the browser URL parser boundary", () => {
  it.each([
    ["a dot segment", `/x/../transactions/${CANONICAL_TRANSACTION_ID}`],
    [
      "an encoded dot segment",
      `/transactions/%2e%2e/transactions/${CANONICAL_TRANSACTION_ID}`,
    ],
    ["a raw backslash separator", `/transactions\\${CANONICAL_TRANSACTION_ID}`],
    ["a trailing carriage return", `/transactions/${CANONICAL_TRANSACTION_ID}\r`],
    ["a trailing tab", `/transactions/${CANONICAL_TRANSACTION_ID}\t`],
    ["a trailing space", `/transactions/${CANONICAL_TRANSACTION_ID} `],
  ])("resolves %s to the canonical route before the application runs", (_label, address) => {
    const url = new URL(address, PARSER_ORIGIN);

    expect(url.pathname).toBe(CANONICAL_DETAIL_ROUTE);
    expect(url.search).toBe("");
    expect(url.hash).toBe("");
    // So this is not evidence the application inspected the original text: the
    // location it receives is indistinguishable from a canonical one.
    expect(parsedLocation(address)).toBe(CANONICAL_DETAIL_ROUTE);
  });

  it("keeps the capability guard on the canonical location it is left holding", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const normalized = parsedLocation(`/x/../transactions/${CANONICAL_TRANSACTION_ID}`);

    const { client } = renderSignedInAt(normalized, ["PLATFORM_ADMIN"]);

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("still asks an unauthenticated visitor to sign in there, and sends nothing", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const normalized = parsedLocation(`/transactions\\${CANONICAL_TRANSACTION_ID}`);
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    renderRoutesWithAuth(routes, { client, initialEntries: [normalized] });

    expect(await screen.findByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });
});

describe("the /transactions/:transactionId production route", () => {
  it.each(TRANSACTION_ROLES)("renders the screen on direct entry for %s", async (role) => {
    const fetchSpy = vi.fn().mockImplementation(() => new Promise<Response>(() => {}));
    vi.stubGlobal("fetch", fetchSpy);

    renderSignedInAt(CANONICAL_DETAIL_ROUTE, [role]);

    expect(
      await screen.findByRole("heading", { name: `Transaction ${CANONICAL_TRANSACTION_ID}` }),
    ).toBeInTheDocument();
    expect(screen.getByText("Loading transaction...")).toBeInTheDocument();
    // The direct URL entry really did reach the Backend, for this transaction
    // and no other.
    await waitFor(() => {
      expect(fetchSpy).toHaveBeenCalledTimes(1);
    });
    const sent = fetchSpy.mock.calls[0][0] as Request;
    expect(new URL(sent.url).pathname).toBe(`/api/v1/transactions/${CANONICAL_TRANSACTION_ID}`);
  });

  it.each(NON_TRANSACTION_ROLES)("refuses direct entry for %s, sending nothing", async (role) => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    const { client } = renderSignedInAt(CANONICAL_DETAIL_ROUTE, [role]);

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^Transaction / })).not.toBeInTheDocument();
    // The refusal costs the Backend nothing at all, and takes no credential.
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
    // It also says nothing about which role would have worked, and repeats no
    // part of the address it refused.
    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain(role);
    expect(rendered).not.toContain("transaction:view");
    expect(rendered).not.toContain(CANONICAL_TRANSACTION_ID);
  });

  it("asks an unauthenticated visitor to sign in, and sends nothing", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    renderRoutesWithAuth(routes, { client, initialEntries: [CANONICAL_DETAIL_ROUTE] });

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

    renderRoutesWithAuth(routes, { client, initialEntries: [CANONICAL_DETAIL_ROUTE] });

    expect(authStatus()).toHaveTextContent(PREPARING);
    expect(screen.getByText("Checking access...")).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Access denied" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^Transaction / })).not.toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("sends nothing when authentication itself failed", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    await renderAt(CANONICAL_DETAIL_ROUTE, {
      client,
      initialize: "reject",
      settled: { status: safeAuthErrorMessage("configuration") },
    });

    expect(screen.getByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("removes the screen the moment the session is invalidated", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => new Promise<Response>(() => {})),
    );
    const { client } = renderSignedInAt(CANONICAL_DETAIL_ROUTE, ["FDS_ANALYST"]);
    await screen.findByRole("heading", { name: `Transaction ${CANONICAL_TRANSACTION_ID}` });

    act(() => {
      client.emitSessionInvalidated();
    });

    expect(screen.getByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /^Transaction / })).not.toBeInTheDocument();
  });

  it("returns to exactly the detail route after signing in from it", async () => {
    const user = userEvent.setup();
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    renderRoutesWithAuth(routes, { client, initialEntries: [CANONICAL_DETAIL_ROUTE] });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(client.calls.signIn).toEqual([CANONICAL_DETAIL_ROUTE]);
  });

  /**
   * A detail address carrying a query or a fragment is not a route this
   * application has, so it is not a place to be sent back to. The return target
   * falls back to `/` whole, rather than being repaired into the canonical
   * detail route by dropping the part that made it unknown.
   */
  it.each([
    ["a query string", `${CANONICAL_DETAIL_ROUTE}?tab=raw`, "tab=raw"],
    ["a fragment", `${CANONICAL_DETAIL_ROUTE}#raw`, "#raw"],
    ["a query string and a fragment", `${CANONICAL_DETAIL_ROUTE}?tab=raw#raw`, "tab=raw"],
  ])("returns to the default route after signing in from %s", async (_label, path, smuggled) => {
    const user = userEvent.setup();
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const client = createFakeAuthClient({ initialSession: null });
    adapter.client = client;

    renderRoutesWithAuth(routes, { client, initialEntries: [path] });
    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(client.calls.signIn).toEqual(["/"]);
    // Nothing of the address it refused is carried into the sign-in, and
    // nothing was asked of the Backend on the way.
    expect(client.calls.signIn[0]).not.toContain(smuggled);
    expect(document.body.innerHTML).not.toContain(smuggled);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  /**
   * Locations a browser really can hand this application, none of which is a
   * transaction address.
   *
   * Each one survives a standard URL parser unchanged - the test below asserts
   * that first, so the list cannot quietly fill up with representations no
   * browser would ever deliver - and each matches the route pattern: one
   * segment under `/transactions/`. The screen therefore renders, refuses, and
   * sends nothing.
   *
   * The percent-encoded entries are the ones that matter most. React Router
   * hands a route parameter over already decoded, so `%32f4c0a4e-...` arrives
   * at `useParams()` as a perfectly canonical UUID. The screen reads the path
   * segment as the browser preserved it instead, which still carries its `%32`.
   */
  const malformedAddresses: Array<[string, string]> = [
    ["an uppercase UUID", "/transactions/2F4C0A4E-8A9D-4C2F-9A1B-7D6E5F430001"],
    ["a version 1 UUID", "/transactions/2f4c0a4e-8a9d-1c2f-9a1b-7d6e5f430001"],
    ["a version 3 UUID", "/transactions/2f4c0a4e-8a9d-3c2f-9a1b-7d6e5f430001"],
    ["a version 5 UUID", "/transactions/2f4c0a4e-8a9d-5c2f-9a1b-7d6e5f430001"],
    ["an invalid RFC variant", "/transactions/2f4c0a4e-8a9d-4c2f-1a1b-7d6e5f430001"],
    ["a numeric identifier", "/transactions/1"],
    ["a UUID with no hyphens", "/transactions/2f4c0a4e8a9d4c2f9a1b7d6e5f430001"],
    ["a percent-encoded first digit", "/transactions/%32f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001"],
    ["an encoded slash", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001%2fedit"],
    ["an encoded backslash", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001%5cedit"],
    ["a double-encoded slash", `${CANONICAL_DETAIL_ROUTE}%252Fedit`],
    ["a malformed percent sequence", `${CANONICAL_DETAIL_ROUTE}%2`],
    ["an encoded space", "/transactions/2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001%20"],
    ["an encoded control character", `${CANONICAL_DETAIL_ROUTE}%0d`],
    ["a matrix parameter", `${CANONICAL_DETAIL_ROUTE};v=1`],
    ["a trailing slash", `${CANONICAL_DETAIL_ROUTE}/`],
    ["a query string", `${CANONICAL_DETAIL_ROUTE}?tab=raw`],
    ["a fragment", `${CANONICAL_DETAIL_ROUTE}#amount`],
  ];

  it.each(malformedAddresses)("refuses %s before any request", async (_label, path) => {
    // A browser would deliver this location as written rather than resolving it
    // away, so it is one the application really has to answer for.
    expect(parsedLocation(path)).toBe(path);

    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    const { client } = renderSignedInAt(path, ["FDS_ANALYST"]);

    const refusal = await screen.findByRole("alert");
    expect(refusal).toHaveTextContent(INVALID_ADDRESS_HEADING);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
    expect(screen.queryByText("Loading transaction...")).not.toBeInTheDocument();
  });

  /**
   * Defence in depth, and labelled as such.
   *
   * A browser resolves `%2e%2e` away as a double-dot path segment before this
   * application runs, so this location arrives only through a `MemoryRouter`.
   * The refusal is real and worth keeping, but it is not a boundary a browser
   * ever asks this application to hold, and it is not evidence that the screen
   * inspected anything the browser had already rewritten.
   */
  it("still refuses an encoded dot segment that reaches it directly", async () => {
    const address = "/transactions/%2e%2e";
    expect(parsedLocation(address)).not.toBe(address);

    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);

    const { client } = renderSignedInAt(address, ["FDS_ANALYST"]);

    expect(await screen.findByRole("alert")).toHaveTextContent(INVALID_ADDRESS_HEADING);
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(client.calls.authorizeRequest).toBe(0);
  });

  it("prints no part of a malformed address anywhere on the page", async () => {
    vi.stubGlobal("fetch", vi.fn());
    const smuggled = "%32f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";

    renderSignedInAt(`/transactions/${smuggled}`, ["FDS_ANALYST"]);
    await screen.findByRole("alert");

    expect(document.body.textContent ?? "").not.toContain("2f4c0a4e");
    expect(document.body.innerHTML).not.toContain("2f4c0a4e");
  });

  it("offers a way back to the list and keeps the rail destination", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(() => new Promise<Response>(() => {})),
    );

    renderSignedInAt(CANONICAL_DETAIL_ROUTE, ["FDS_VIEWER"]);

    const back = await screen.findByRole("link", { name: "Back to transactions" });
    expect(back).toHaveAttribute("href", "/transactions");
    expect(screen.getByRole("link", { name: "Transactions" })).toBeInTheDocument();
  });
});
