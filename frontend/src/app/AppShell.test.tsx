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
import type { UserRole } from "../auth/userRoles";

const CANONICAL_TRANSACTION_ID = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const CANONICAL_DETAIL_ROUTE = `/transactions/${CANONICAL_TRANSACTION_ID}`;

/**
 * The shell, with stand-ins where the business screens sit.
 *
 * The transaction destinations are here so the shell can be rendered *at* them
 * and asked what it would do about signing in from there. What those screens
 * themselves send, refuse or display is decided by their own tests; a stand-in
 * that fetches nothing is what makes "no request was made" a statement about
 * the shell rather than about a page that happened not to have loaded yet.
 */
const ROUTES: RouteObject[] = [
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <HomePage /> },
      { path: "health", element: <HealthPage /> },
      { path: "transactions", element: <p>Transaction list stands in here.</p> },
      {
        path: "transactions/:transactionId",
        element: <p>Transaction detail stands in here.</p>,
      },
      { path: "cases", element: <p>Case list stands in here.</p> },
      { path: "cases/:caseId", element: <p>Case detail stands in here.</p> },
      // The production router's catch-all, mirrored here rather than omitted.
      // A 404 renders *inside* the shell, so the navigation is on screen at
      // `/cases/`, `/casesx` and every other unrouted address - which is
      // precisely where a prefix-matched "current page" marker would lie.
      { path: "*", element: <p>Not found stands in here.</p> },
    ],
  },
];

/** A canonical lowercase UUID v4 that names no case. */
const CANONICAL_CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";

/**
 * The shell renders nothing that depends on which roles a session holds, so
 * these tests state the least privileged real role rather than a convenient
 * one. Naming it is not decoration: `AuthSession.roles` is non-empty because a
 * sign-in that decided no role is refused outright, and a fixture that skipped
 * the field would be a session the adapter cannot publish.
 */
const SHELL_ROLES = ["FDS_VIEWER"] as const;

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

/**
 * The three roles that hold `transaction:view`, and the three that do not.
 *
 * Taken from the capability table rather than restated here, so a role gaining
 * or losing transaction authority shows up as a failure in this file rather
 * than as a menu item nobody noticed.
 */
const TRANSACTION_ROLES: readonly UserRole[] = ["FDS_VIEWER", "FDS_ANALYST", "FDS_APPROVER"];
const NON_TRANSACTION_ROLES: readonly UserRole[] = [
  "RULE_OPERATOR",
  "RECOVERY_OPERATOR",
  "PLATFORM_ADMIN",
];

/**
 * The three roles that hold `case:view`, and the three that do not.
 *
 * Written out separately from the transaction lists rather than aliased to
 * them. The two capabilities happen to be granted by the same three roles
 * today, and an alias would quietly turn "the case destination follows the case
 * capability" into "it follows whatever the ledger does" - which is exactly the
 * regression this file has to catch if the two tables ever diverge.
 */
const CASE_ROLES: readonly UserRole[] = ["FDS_VIEWER", "FDS_ANALYST", "FDS_APPROVER"];
const NON_CASE_ROLES: readonly UserRole[] = [
  "RULE_OPERATOR",
  "RECOVERY_OPERATOR",
  "PLATFORM_ADMIN",
];

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

  it("opens with a skip link that reaches the main landmark", async () => {
    const user = userEvent.setup();
    renderShell(createFakeAuthClient());

    const skipLink = screen.getByRole("link", { name: "Skip to main content" });
    expect(skipLink).toHaveAttribute("href", "#main-content");
    expect(screen.getByRole("main")).toHaveAttribute("id", "main-content");

    // The very first Tab from the top of the document lands on it, before any
    // navigation item, which is the only thing that makes it useful.
    await user.tab();
    expect(skipLink).toHaveFocus();
  });

  it("marks the current destination with aria-current", async () => {
    renderShell(createFakeAuthClient(), "/health");

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: "Health" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("link", { name: "Home" })).not.toHaveAttribute("aria-current");
  });
});

describe("AppShell capability navigation", () => {
  it.each(TRANSACTION_ROLES)("offers the transactions destination to %s", async (role) => {
    renderShell(createFakeAuthClient({ initialSession: { subject: "sub-1", roles: [role] } }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: "Transactions" })).toHaveAttribute(
      "href",
      "/transactions",
    );
  });

  it.each(NON_TRANSACTION_ROLES)("leaves no trace of it in the DOM for %s", async (role) => {
    const { container } = renderShell(
      createFakeAuthClient({ initialSession: { subject: "sub-1", roles: [role] } }),
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    // Not hidden, not disabled, not `aria-hidden`: absent. A control that is
    // only styled away is still in the accessibility tree and returns with one
    // attribute change.
    expect(screen.queryByRole("link", { name: "Transactions" })).not.toBeInTheDocument();
    expect(container.querySelector('a[href="/transactions"]')).toBeNull();
    expect(container.innerHTML).not.toContain("/transactions");
  });

  it("offers it once to a session holding several roles", async () => {
    renderShell(
      createFakeAuthClient({
        initialSession: { subject: "sub-1", roles: ["PLATFORM_ADMIN", "FDS_VIEWER"] },
      }),
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    expect(screen.getAllByRole("link", { name: "Transactions" })).toHaveLength(1);
  });

  it("does not offer it while authentication is still initializing", () => {
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } });
    client.deferInitialize();
    renderShell(client);

    expect(authStatus()).toHaveTextContent("Preparing sign-in...");
    expect(screen.queryByRole("link", { name: "Transactions" })).not.toBeInTheDocument();
  });

  it("does not offer it while unauthenticated", async () => {
    renderShell(createFakeAuthClient());

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    expect(screen.queryByRole("link", { name: "Transactions" })).not.toBeInTheDocument();
  });

  it("withdraws it the moment sign-out starts", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } });
    client.deferSignOut();
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "Transactions" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(screen.queryByRole("link", { name: "Transactions" })).not.toBeInTheDocument();
  });

  it("withdraws it when the session is invalidated", async () => {
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } });
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "Transactions" })).toBeInTheDocument();
    });
    act(() => {
      client.emitSessionInvalidated();
    });

    expect(screen.queryByRole("link", { name: "Transactions" })).not.toBeInTheDocument();
  });

  it("does not offer it after an authentication error", async () => {
    const client = createFakeAuthClient();
    client.failInitialize();
    renderShell(client);

    await waitFor(() => {
      expect(authStatus()).toHaveTextContent(safeAuthErrorMessage("configuration"));
    });
    expect(screen.queryByRole("link", { name: "Transactions" })).not.toBeInTheDocument();
  });

  it.each(CASE_ROLES)("offers the cases destination to %s", async (role) => {
    renderShell(createFakeAuthClient({ initialSession: { subject: "sub-1", roles: [role] } }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    expect(screen.getByRole("link", { name: "Cases" })).toHaveAttribute("href", "/cases");
  });

  it.each(NON_CASE_ROLES)("leaves no trace of the cases destination for %s", async (role) => {
    const { container } = renderShell(
      createFakeAuthClient({ initialSession: { subject: "sub-1", roles: [role] } }),
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    // Not hidden, not disabled, not `aria-hidden`: absent, and the address
    // itself is nowhere in the markup either.
    expect(screen.queryByRole("link", { name: "Cases" })).not.toBeInTheDocument();
    expect(container.querySelector('a[href="/cases"]')).toBeNull();
    expect(container.innerHTML).not.toContain("/cases");
  });

  it("offers the cases destination once to a session holding several roles", async () => {
    renderShell(
      createFakeAuthClient({
        initialSession: { subject: "sub-1", roles: ["PLATFORM_ADMIN", "FDS_VIEWER"] },
      }),
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    expect(screen.getAllByRole("link", { name: "Cases" })).toHaveLength(1);
  });

  it("does not offer the cases destination while unauthenticated", async () => {
    const { container } = renderShell(createFakeAuthClient());

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    });
    expect(screen.queryByRole("link", { name: "Cases" })).not.toBeInTheDocument();
    expect(container.innerHTML).not.toContain("/cases");
  });

  it("does not offer the cases destination while authentication is undecided", () => {
    const client = createFakeAuthClient({
      initialSession: { subject: "sub-1", roles: SHELL_ROLES },
    });
    client.deferInitialize();
    const { container } = renderShell(client);

    expect(authStatus()).toHaveTextContent("Preparing sign-in...");
    expect(screen.queryByRole("link", { name: "Cases" })).not.toBeInTheDocument();
    expect(container.innerHTML).not.toContain("/cases");
  });

  it("withdraws the cases destination the moment sign-out starts", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({
      initialSession: { subject: "sub-1", roles: SHELL_ROLES },
    });
    client.deferSignOut();
    const { container } = renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "Cases" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(screen.queryByRole("link", { name: "Cases" })).not.toBeInTheDocument();
    expect(container.innerHTML).not.toContain("/cases");
  });

  it("withdraws the cases destination when the session is invalidated", async () => {
    const client = createFakeAuthClient({
      initialSession: { subject: "sub-1", roles: SHELL_ROLES },
    });
    const { container } = renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "Cases" })).toBeInTheDocument();
    });
    act(() => {
      client.emitSessionInvalidated();
    });

    expect(screen.queryByRole("link", { name: "Cases" })).not.toBeInTheDocument();
    expect(container.innerHTML).not.toContain("/cases");
  });

  it("does not offer the cases destination after an authentication error", async () => {
    const client = createFakeAuthClient();
    client.failInitialize();
    const { container } = renderShell(client);

    await waitFor(() => {
      expect(authStatus()).toHaveTextContent(safeAuthErrorMessage("configuration"));
    });
    expect(screen.queryByRole("link", { name: "Cases" })).not.toBeInTheDocument();
    expect(container.innerHTML).not.toContain("/cases");
  });

  it.each([
    ["the case list", "/cases"],
    ["a canonical case detail address", `/cases/${CANONICAL_CASE_ID}`],
    ["a case detail address with the lowest canonical identifier", "/cases/00000000-0000-4000-8000-000000000000"],
    ["a case detail address with the highest canonical identifier", "/cases/ffffffff-ffff-4fff-bfff-ffffffffffff"],
  ])("marks the cases destination current at %s", async (_description, path) => {
    renderShell(
      createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } }),
      path,
    );

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "Cases" })).toBeInTheDocument();
    });
    const casesLink = screen.getByRole("link", { name: "Cases" });
    expect(casesLink).toHaveAttribute("aria-current", "page");
    // The destination still points at the list itself. Being *inside* the case
    // section is what the marker announces; it is not a second link.
    expect(casesLink).toHaveAttribute("href", "/cases");
    // Exactly one `aria-current` in the markup, and its value is the token -
    // never the string "false", which is an attribute that is present.
    expect(casesLink.getAttributeNames().filter((name) => name === "aria-current")).toHaveLength(
      1,
    );
    expect(document.querySelectorAll("[aria-current]")).toHaveLength(1);
    expect(document.querySelectorAll('[aria-current="false"]')).toHaveLength(0);
    expect(screen.getByRole("link", { name: "Transactions" })).not.toHaveAttribute(
      "aria-current",
    );
    expect(screen.getByRole("link", { name: "Home" })).not.toHaveAttribute("aria-current");
  });

  /**
   * Every address that is *not* in the case section, including the ones a
   * prefix match would claim.
   *
   * `aria-current="page"` tells a screen-reader user "this link is where you
   * are". React Router decides that by prefix, so a `NavLink` to `/cases` calls
   * itself current at every unrouted address beneath it, and `end` narrows that
   * only to the pathname - `/cases?caseStatus=OPEN` and `/cases#content` would
   * still claim it. The section is exactly two addresses: the list, and one
   * case's detail named by a canonical lowercase UUID v4.
   *
   * The near-miss identifiers are the interesting half. An uppercase UUID, a
   * UUID v1 and an invalid RFC variant each match the *route pattern* - one
   * segment under `/cases/` - and are each a 404, because the screen behind
   * that pattern refuses them. Announcing one as the current page would send
   * someone looking for a record that is not on the page.
   *
   * This is a statement about the marker, not about routing. What each address
   * renders is decided by the router's own tests; what the shell may claim
   * about it is decided here.
   */
  const nonCurrentCaseAddresses: readonly [string, string][] = [
    ["the case list with a trailing slash", "/cases/"],
    ["an address that merely starts with the same characters", "/casesx"],
    ["the case list carrying a query", "/cases?caseStatus=OPEN"],
    ["the case list carrying a fragment", "/cases#content"],
    ["a case detail address carrying a query", `/cases/${CANONICAL_CASE_ID}?tab=raw`],
    ["a case detail address carrying a fragment", `/cases/${CANONICAL_CASE_ID}#assignee`],
    ["a case detail address with a trailing slash", `/cases/${CANONICAL_CASE_ID}/`],
    ["an uppercase case identifier", "/cases/5C2D1E0F-7A8B-4C9D-9E0F-1A2B3C4D5E60"],
    ["a version 1 case identifier", "/cases/5c2d1e0f-7a8b-1c9d-9e0f-1a2b3c4d5e60"],
    ["a case identifier with an invalid RFC variant", "/cases/5c2d1e0f-7a8b-4c9d-1e0f-1a2b3c4d5e60"],
    ["a malformed case identifier", "/cases/not-a-uuid"],
    ["a path below a case", `/cases/${CANONICAL_CASE_ID}/notes`],
    ["an unrelated address that is not routed at all", "/nowhere/at/all"],
  ];

  it.each(nonCurrentCaseAddresses)(
    "does not mark the cases destination current at %s",
    async (_description, path) => {
      renderShell(
        createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } }),
        path,
      );

      await waitFor(() => {
        expect(screen.getByRole("link", { name: "Cases" })).toBeInTheDocument();
      });
      const casesLink = screen.getByRole("link", { name: "Cases" });
      // The destination is still offered, and still points at the exact list
      // address: this is about what the shell claims, not about withdrawing a
      // link the session is entitled to.
      expect(casesLink).toHaveAttribute("href", "/cases");
      // Absent, not "false": the attribute itself is not in the markup.
      expect(casesLink).not.toHaveAttribute("aria-current");
      expect(casesLink.getAttributeNames()).not.toContain("aria-current");
      expect(document.querySelectorAll('[aria-current="false"]')).toHaveLength(0);
    },
  );

  it("leaves no navigation item claiming to be the current page on a 404", async () => {
    // An address under `/cases/` that the detail route does not match: two
    // segments, so it falls to the catch-all. The rail is on screen there, and
    // none of its destinations may claim it.
    renderShell(
      createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } }),
      `/cases/${CANONICAL_CASE_ID}/notes`,
    );

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "Cases" })).toBeInTheDocument();
    });
    // Not the case list, not the ledger, not Home, not Health. An address the
    // application does not route is announced as none of its destinations.
    expect(document.querySelectorAll("[aria-current]")).toHaveLength(0);
    expect(screen.getByText("Not found stands in here.")).toBeInTheDocument();
  });

  it("never names a role or an authority in the navigation", async () => {
    renderShell(
      createFakeAuthClient({ initialSession: { subject: "sub-1", roles: ["FDS_APPROVER"] } }),
    );

    await waitFor(() => {
      expect(screen.getByRole("link", { name: "Transactions" })).toBeInTheDocument();
    });
    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("FDS_APPROVER");
    expect(rendered).not.toContain("transaction:view");
    expect(rendered).not.toContain("transaction:read");
    expect(rendered).not.toContain("case:view");
    expect(rendered).not.toContain("case:read");
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

  /**
   * What the shell offers to return to is decided from the whole location -
   * path, query and fragment - and not from the path alone.
   *
   * The distinction is the point of these cases. `/transactions/{uuid}?tab=raw`
   * is not a route this application has; checking only its pathname would
   * silently turn it into one, and send someone after login to an address they
   * never asked for. So the query and the fragment travel into the allowlist
   * with the path, an address carrying either fails it whole, and the fallback
   * is the default route rather than a repaired version of the input.
   */
  const returnRouteCases: Array<[string, string, string | null]> = [
    ["the canonical detail route", CANONICAL_DETAIL_ROUTE, null],
    ["a detail route carrying a query", `${CANONICAL_DETAIL_ROUTE}?tab=raw`, "tab=raw"],
    ["a detail route carrying a fragment", `${CANONICAL_DETAIL_ROUTE}#raw`, "#raw"],
    [
      "a detail route carrying both",
      `${CANONICAL_DETAIL_ROUTE}?tab=raw#raw`,
      "tab=raw",
    ],
    ["the transaction list", "/transactions", null],
    ["the case list", "/cases", null],
    ["a case list carrying a query", "/cases?status=OPEN", "status=OPEN"],
    ["a case list carrying a fragment", "/cases#content", "#content"],
    ["a case list carrying both", "/cases?status=OPEN#content", "status=OPEN"],
    ["the canonical case detail route", `/cases/${CANONICAL_CASE_ID}`, null],
    [
      "a case detail route carrying a query",
      `/cases/${CANONICAL_CASE_ID}?tab=raw`,
      "tab=raw",
    ],
    [
      "a case detail route carrying a fragment",
      `/cases/${CANONICAL_CASE_ID}#assignee`,
      "#assignee",
    ],
    ["the root route", "/", null],
  ];

  it.each(returnRouteCases)(
    "resolves the return target for %s",
    async (_label, path, smuggled) => {
      const fetchSpy = vi.fn();
      vi.stubGlobal("fetch", fetchSpy);
      const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
      const consoleWarn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
      const consoleLog = vi.spyOn(console, "log").mockImplementation(() => undefined);
      const user = userEvent.setup();
      const client = createFakeAuthClient();
      renderShell(client, path);

      await waitFor(() => {
        expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
      });
      await user.click(screen.getByRole("button", { name: "Sign in" }));

      // An address the allowlist knows returns to itself, exactly; one it does
      // not know returns to the default route, whole.
      const expected = smuggled === null ? path : "/";
      expect(client.calls.signIn).toEqual([expected]);

      if (smuggled !== null) {
        // The part that made the address unknown is not carried anywhere: not
        // into the sign-in, not into the document, not into the console.
        expect(client.calls.signIn[0]).not.toContain(smuggled);
        expect(document.body.innerHTML).not.toContain(smuggled);
        for (const spy of [consoleError, consoleWarn, consoleLog]) {
          for (const call of spy.mock.calls) {
            expect(JSON.stringify(call)).not.toContain(smuggled);
          }
        }
      }
      // The shell asks the Backend for nothing on the way to a sign-in.
      expect(fetchSpy).not.toHaveBeenCalled();
    },
  );

  /**
   * Case addresses that are *almost* the canonical detail route.
   *
   * Each matches the route pattern - one segment under `/cases/` - and none is
   * an address this application can open, so the return target is the default
   * route rather than a repaired version of the input. Sending someone back to
   * one of these after signing in would land them on a 404 with a case
   * identifier in the address bar.
   */
  it.each([
    ["a trailing slash", `/cases/${CANONICAL_CASE_ID}/`],
    ["an uppercase identifier", "/cases/5C2D1E0F-7A8B-4C9D-9E0F-1A2B3C4D5E60"],
    ["a version 1 identifier", "/cases/5c2d1e0f-7a8b-1c9d-9e0f-1a2b3c4d5e60"],
    ["an invalid RFC variant", "/cases/5c2d1e0f-7a8b-4c9d-1e0f-1a2b3c4d5e60"],
    ["a malformed identifier", "/cases/not-a-uuid"],
    ["a path below a case", `/cases/${CANONICAL_CASE_ID}/notes`],
  ])(
    "returns to the default route after signing in from a case address with %s",
    async (_label, path) => {
      const fetchSpy = vi.fn();
      vi.stubGlobal("fetch", fetchSpy);
      const user = userEvent.setup();
      const client = createFakeAuthClient();
      renderShell(client, path);

      await waitFor(() => {
        expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
      });
      await user.click(screen.getByRole("button", { name: "Sign in" }));

      expect(client.calls.signIn).toEqual(["/"]);
      expect(fetchSpy).not.toHaveBeenCalled();
    },
  );

  // `/health` is the sixth case, and it is the test above this block: it needs
  // a fetch of its own, so it cannot join a table whose whole point is that the
  // shell sends nothing.

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
      initialSession: { subject: "sub-1", displayName: "Test Analyst", roles: SHELL_ROLES },
    });
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    expect(authStatus()).toHaveTextContent("Signed in as Test Analyst.");
    expect(screen.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("says only that the user is signed in when there is no display name", async () => {
    renderShell(createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } }));

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    expect(authStatus()).toHaveTextContent("Signed in.");
  });

  it("announces the signing-out state and withdraws both controls", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } });
    client.deferSignOut();
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(client.calls.signOut).toBe(1);
    expect(authStatus()).toHaveTextContent("Signing out...");
    // Neither affordance is offered while the end-session redirect is in
    // flight, so neither can be clicked a second time.
    expect(screen.queryByRole("button", { name: "Sign out" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sign in" })).not.toBeInTheDocument();
  });

  it("shows no signed-in name once sign-out has started", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({
      initialSession: { subject: "sub-1", displayName: "Test Analyst", roles: SHELL_ROLES },
    });
    client.deferSignOut();
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(authStatus()).not.toHaveTextContent("Test Analyst");
    expect(document.body.textContent ?? "").not.toContain("Test Analyst");
  });

  it("starts exactly one sign-out however many times the control is clicked", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } });
    client.deferSignOut();
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    const button = screen.getByRole("button", { name: "Sign out" });
    await user.click(button);
    await user.click(button);
    await user.click(button);

    expect(client.calls.signOut).toBe(1);
  });

  it("shows the fixed sign-out message and offers an explicit retry on failure", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } });
    client.failSignOut();
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    await waitFor(() => {
      expect(authStatus()).toHaveTextContent(safeAuthErrorMessage("sign-out"));
    });
    // The local logout is not undone: there is no session to sign out of, and
    // the only thing offered is starting a new sign-in.
    expect(screen.queryByRole("button", { name: "Sign out" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(client.calls.signIn).toHaveLength(0);
  });

  it("keeps the public outlet reachable while signing out", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } });
    client.deferSignOut();
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Home" })).toBeInTheDocument();
  });

  it("returns to the sign-in control when the session is invalidated", async () => {
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1", roles: SHELL_ROLES } });
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

  it("renders no credential or provider payload while signing out", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({
      initialSession: {
        subject: "11111111-1111-4111-8111-111111111111",
        displayName: "Analyst",
        roles: SHELL_ROLES,
      },
    });
    client.deferSignOut();
    renderShell(client);

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Sign out" })).toBeInTheDocument();
    });
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("11111111-1111-4111-8111-111111111111");
    expect(rendered).not.toMatch(/[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]{10,}/);
    expect(document.body.innerHTML).not.toMatch(/bearer|access_token|id_token|state=/i);
  });

  it("never renders a subject, token or provider payload", async () => {
    const client = createFakeAuthClient({
      initialSession: {
        subject: "11111111-1111-4111-8111-111111111111",
        displayName: "Analyst",
        roles: SHELL_ROLES,
      },
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
