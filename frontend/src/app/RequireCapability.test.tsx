import { afterEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Outlet, type RouteObject } from "react-router-dom";
import { RequireCapability } from "./RequireCapability";
import type { AuthSession } from "../auth/authClient";
import { useAuth } from "../auth/useAuth";
import { useCapabilities } from "../auth/useCapabilities";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { renderRoutesWithAuth } from "../test/renderWithAuth";

/**
 * The guard, exercised on routes that exist only here.
 *
 * Production has no protected route yet, so mounting the guard on a test-only
 * `MemoryRouter` route is what makes direct URL entry a real case rather than a
 * claim: `initialEntries` is the address bar, and nothing in the tree navigated
 * there. The routes and the action probe below are fixtures, not screens.
 */

const CASE_CONTENT = "Case content behind the guard";
const RESOLUTION_CONTENT = "Resolution content behind the guard";

const VIEWER = "11111111-1111-4111-8111-111111111111";

function viewerSession(): AuthSession {
  return { subject: VIEWER, roles: ["FDS_VIEWER"] };
}

/**
 * The only place in the codebase that builds a session the port cannot produce.
 *
 * `AuthSession.roles` is a non-empty tuple and `resolveUserRoles()` refuses
 * both an empty array and a missing claim, so neither shape can reach the React
 * tree from the adapter or from the shared fake. The cast exists so the guard
 * can still be asked what it does if one ever did — a permission decision has
 * to fail closed on an input the type says is impossible, and the failure mode
 * of not checking is a refused user seeing the page.
 *
 * It stays local on purpose. Exporting it, or relaxing the shared fake to allow
 * the same shape, would let ordinary tests drift onto a session production
 * never publishes and stop being evidence about production at all.
 */
function unsafeSessionWithRoles(roles: unknown): AuthSession {
  return { subject: VIEWER, roles } as unknown as AuthSession;
}

/**
 * Renders exactly the actions the session may use. Absence is the policy: a
 * refused action leaves no node behind, so it cannot be focused, read by a
 * screen reader, or re-enabled from the console.
 */
function CaseActions() {
  const capabilities = useCapabilities();
  return (
    <div>
      {capabilities.has("case:workflow") && <button type="button">Change case status</button>}
      {capabilities.has("case:note-write") && <button type="button">Add investigation note</button>}
      {capabilities.has("case:resolve") && <button type="button">Resolve case</button>}
    </div>
  );
}

/** Drives the real context transitions a session replacement goes through. */
function SessionProbe() {
  const { state, signOut, notifyCallbackStarted, notifyCallbackSucceeded } = useAuth();
  return (
    <div>
      <span data-testid="auth-status">{state.status}</span>
      <button type="button" onClick={signOut}>
        Sign out
      </button>
      <button type="button" onClick={notifyCallbackStarted}>
        Start callback
      </button>
      <button
        type="button"
        onClick={() => {
          notifyCallbackStarted();
          notifyCallbackSucceeded({ subject: "22222222-2222-4222-8222-222222222222", roles: ["FDS_APPROVER"] });
        }}
      >
        Replace session
      </button>
    </div>
  );
}

function TestLayout() {
  return (
    <div>
      <SessionProbe />
      <main>
        <Outlet />
      </main>
    </div>
  );
}

const ROUTES: RouteObject[] = [
  {
    path: "/",
    element: <TestLayout />,
    children: [
      { index: true, element: <p>Home placeholder</p> },
      {
        path: "test-only/cases",
        element: (
          <RequireCapability capability="case:view">
            <div>
              <p>{CASE_CONTENT}</p>
              <CaseActions />
            </div>
          </RequireCapability>
        ),
      },
      {
        path: "test-only/resolution",
        element: (
          <RequireCapability capability="case:resolve">
            <p>{RESOLUTION_CONTENT}</p>
          </RequireCapability>
        ),
      },
    ],
  },
];

function renderGuard(client: FakeAuthClient, path = "/test-only/cases") {
  return renderRoutesWithAuth(ROUTES, { client, initialEntries: [path] });
}

function authStatus(): string {
  return screen.getByTestId("auth-status").textContent ?? "";
}

function accessDeniedHeading() {
  return screen.queryByRole("heading", { level: 2, name: "Access denied" });
}

function signInRequiredHeading() {
  return screen.queryByRole("heading", { level: 2, name: "Sign in required" });
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("RequireCapability - not decided yet", () => {
  it("waits rather than refusing while initialization is pending", async () => {
    const client = createFakeAuthClient({ initialSession: viewerSession() });
    client.deferInitialize();
    renderGuard(client);

    await waitFor(() => {
      expect(authStatus()).toBe("initializing");
    });
    expect(screen.getByRole("status")).toHaveTextContent("Checking access...");
    expect(screen.queryByText(CASE_CONTENT)).not.toBeInTheDocument();
    expect(accessDeniedHeading()).not.toBeInTheDocument();
    expect(signInRequiredHeading()).not.toBeInTheDocument();
  });

  it("waits rather than refusing while a callback is in flight", async () => {
    const user = userEvent.setup();
    renderGuard(createFakeAuthClient());

    await waitFor(() => {
      expect(authStatus()).toBe("unauthenticated");
    });
    await user.click(screen.getByRole("button", { name: "Start callback" }));

    expect(authStatus()).toBe("authenticating");
    expect(screen.getByRole("status")).toHaveTextContent("Checking access...");
    expect(accessDeniedHeading()).not.toBeInTheDocument();
  });

  it("resolves to the content once a permitted session arrives", async () => {
    const client = createFakeAuthClient({ initialSession: viewerSession() });
    const deferred = client.deferInitialize();
    renderGuard(client);

    await waitFor(() => {
      expect(screen.getByRole("status")).toBeInTheDocument();
    });
    act(() => {
      deferred.resolve({ session: viewerSession() });
    });

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});

describe("RequireCapability - no session", () => {
  it("asks an unauthenticated visitor to sign in", async () => {
    renderGuard(createFakeAuthClient());

    expect(await screen.findByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
    expect(screen.getByText("Sign in to view this page.")).toBeInTheDocument();
    expect(screen.queryByText(CASE_CONTENT)).not.toBeInTheDocument();
    expect(accessDeniedHeading()).not.toBeInTheDocument();
  });

  it("treats an authentication error the same way, without repeating the reason", async () => {
    const client = createFakeAuthClient();
    client.failInitialize();
    renderGuard(client);

    await waitFor(() => {
      expect(authStatus()).toBe("error");
    });
    expect(signInRequiredHeading()).toBeInTheDocument();
    expect(screen.queryByText(CASE_CONTENT)).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "Sign in required" }).textContent).not.toContain(
      "configuration",
    );
  });

  it("starts no sign-in of its own", async () => {
    const client = createFakeAuthClient();
    renderGuard(client);

    await waitFor(() => {
      expect(signInRequiredHeading()).toBeInTheDocument();
    });
    expect(client.calls.signIn).toHaveLength(0);
  });
});

describe("RequireCapability - the six USER roles", () => {
  const CASE_VIEW: ReadonlyArray<[string, AuthSession["roles"], boolean]> = [
    ["FDS_VIEWER", ["FDS_VIEWER"], true],
    ["FDS_ANALYST", ["FDS_ANALYST"], true],
    ["FDS_APPROVER", ["FDS_APPROVER"], true],
    ["RULE_OPERATOR", ["RULE_OPERATOR"], false],
    ["RECOVERY_OPERATOR", ["RECOVERY_OPERATOR"], false],
    ["PLATFORM_ADMIN", ["PLATFORM_ADMIN"], false],
  ];

  it.each(CASE_VIEW)("decides case:view for %s", async (_label, roles, allowed) => {
    renderGuard(createFakeAuthClient({ initialSession: { subject: VIEWER, roles } }));

    if (allowed) {
      expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
      expect(accessDeniedHeading()).not.toBeInTheDocument();
    } else {
      expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
      expect(screen.queryByText(CASE_CONTENT)).not.toBeInTheDocument();
    }
  });

  const CASE_RESOLVE: ReadonlyArray<[string, AuthSession["roles"], boolean]> = [
    ["FDS_VIEWER", ["FDS_VIEWER"], false],
    ["FDS_ANALYST", ["FDS_ANALYST"], false],
    ["FDS_APPROVER", ["FDS_APPROVER"], true],
    ["RULE_OPERATOR", ["RULE_OPERATOR"], false],
    ["RECOVERY_OPERATOR", ["RECOVERY_OPERATOR"], false],
    ["PLATFORM_ADMIN", ["PLATFORM_ADMIN"], false],
  ];

  it.each(CASE_RESOLVE)("decides case:resolve for %s", async (_label, roles, allowed) => {
    renderGuard(
      createFakeAuthClient({ initialSession: { subject: VIEWER, roles } }),
      "/test-only/resolution",
    );

    if (allowed) {
      expect(await screen.findByText(RESOLUTION_CONTENT)).toBeInTheDocument();
    } else {
      expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
      expect(screen.queryByText(RESOLUTION_CONTENT)).not.toBeInTheDocument();
    }
  });

  /**
   * Both of these are refused before a session exists, so neither can arrive
   * through the port. The guard is asked anyway, because "cannot happen" is a
   * property of today's adapter and the cost of being wrong is a refused user
   * seeing the page.
   */
  it("refuses a signed-in user holding an empty role set", async () => {
    renderGuard(createFakeAuthClient({ initialSession: unsafeSessionWithRoles([]) }));

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(signInRequiredHeading()).not.toBeInTheDocument();
  });

  it("refuses a session that carries no decided role set at all", async () => {
    renderGuard(createFakeAuthClient({ initialSession: unsafeSessionWithRoles(undefined) }));

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(screen.queryByText(CASE_CONTENT)).not.toBeInTheDocument();
  });
});

describe("RequireCapability - several roles", () => {
  it("grants the union to an analyst who is also an approver", async () => {
    renderGuard(
      createFakeAuthClient({
        initialSession: { subject: VIEWER, roles: ["FDS_ANALYST", "FDS_APPROVER"] },
      }),
      "/test-only/resolution",
    );

    expect(await screen.findByText(RESOLUTION_CONTENT)).toBeInTheDocument();
  });

  it("adds nothing when a role granting nothing is held alongside", async () => {
    renderGuard(
      createFakeAuthClient({
        initialSession: { subject: VIEWER, roles: ["FDS_ANALYST", "PLATFORM_ADMIN"] },
      }),
      "/test-only/resolution",
    );

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
  });

  it("does not depend on the order the roles arrived in", async () => {
    renderGuard(
      createFakeAuthClient({
        initialSession: { subject: VIEWER, roles: ["FDS_APPROVER", "FDS_ANALYST"] },
      }),
      "/test-only/resolution",
    );

    expect(await screen.findByText(RESOLUTION_CONTENT)).toBeInTheDocument();
  });
});

describe("RequireCapability - direct URL entry", () => {
  it("applies the same refusal to an address typed straight into the bar", async () => {
    renderGuard(
      createFakeAuthClient({ initialSession: { subject: VIEWER, roles: ["FDS_ANALYST"] } }),
      "/test-only/resolution",
    );

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(screen.queryByText(RESOLUTION_CONTENT)).not.toBeInTheDocument();
  });

  it("applies the same sign-in requirement to a direct entry with no session", async () => {
    renderGuard(createFakeAuthClient(), "/test-only/resolution");

    expect(await screen.findByRole("heading", { name: "Sign in required" })).toBeInTheDocument();
  });
});

describe("RequireCapability - action exposure", () => {
  function actionNames(): string[] {
    return screen
      .queryAllByRole("button")
      .map((button) => button.textContent ?? "")
      .filter((name) => !["Sign out", "Start callback", "Replace session"].includes(name));
  }

  it("gives a viewer no write action at all", async () => {
    renderGuard(createFakeAuthClient({ initialSession: viewerSession() }));

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    expect(actionNames()).toEqual([]);
  });

  it("gives an analyst workflow and note actions but not resolution", async () => {
    renderGuard(
      createFakeAuthClient({ initialSession: { subject: VIEWER, roles: ["FDS_ANALYST"] } }),
    );

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    expect(actionNames()).toEqual(["Change case status", "Add investigation note"]);
    expect(screen.queryByRole("button", { name: "Resolve case" })).not.toBeInTheDocument();
  });

  it("gives an approver resolution but not workflow or notes", async () => {
    renderGuard(
      createFakeAuthClient({ initialSession: { subject: VIEWER, roles: ["FDS_APPROVER"] } }),
    );

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    expect(actionNames()).toEqual(["Resolve case"]);
  });

  /**
   * Removed, never disabled. A disabled control stays in the accessibility
   * tree, announces itself, and is one attribute away from being usable.
   */
  it("leaves no disabled remnant of a refused action", async () => {
    const { container } = renderGuard(createFakeAuthClient({ initialSession: viewerSession() }));

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    expect(container.querySelectorAll("[disabled], [aria-disabled], [hidden]")).toHaveLength(0);
  });
});

describe("RequireCapability - the session ending", () => {
  it("removes the content the moment the session is invalidated", async () => {
    const client = createFakeAuthClient({ initialSession: viewerSession() });
    renderGuard(client);

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    act(() => {
      client.emitSessionInvalidated();
    });

    expect(screen.queryByText(CASE_CONTENT)).not.toBeInTheDocument();
    expect(signInRequiredHeading()).toBeInTheDocument();
  });

  it("removes the actions along with the content", async () => {
    const client = createFakeAuthClient({
      initialSession: { subject: VIEWER, roles: ["FDS_ANALYST"] },
    });
    renderGuard(client);

    expect(await screen.findByRole("button", { name: "Change case status" })).toBeInTheDocument();
    act(() => {
      client.emitSessionInvalidated();
    });

    expect(screen.queryByRole("button", { name: "Change case status" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add investigation note" })).not.toBeInTheDocument();
  });

  it("removes the content on sign-out", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({ initialSession: viewerSession() });
    renderGuard(client);

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(screen.queryByText(CASE_CONTENT)).not.toBeInTheDocument();
    expect(signInRequiredHeading()).toBeInTheDocument();
  });

  it("re-decides for a replacement session rather than reusing the old answer", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({
      initialSession: { subject: VIEWER, roles: ["FDS_ANALYST"] },
    });
    renderGuard(client, "/test-only/resolution");

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Sign out" }));
    await user.click(screen.getByRole("button", { name: "Replace session" }));

    expect(authStatus()).toBe("authenticated");
    expect(await screen.findByText(RESOLUTION_CONTENT)).toBeInTheDocument();
    expect(accessDeniedHeading()).not.toBeInTheDocument();
  });

  it("withdraws a capability the replacement session does not hold", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({
      initialSession: { subject: VIEWER, roles: ["FDS_ANALYST"] },
    });
    renderGuard(client);

    expect(await screen.findByRole("button", { name: "Change case status" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Sign out" }));
    await user.click(screen.getByRole("button", { name: "Replace session" }));

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Change case status" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Resolve case" })).toBeInTheDocument();
  });
});

describe("RequireCapability - StrictMode and disclosure", () => {
  it("renders one decision under the StrictMode double invoke", async () => {
    renderGuard(createFakeAuthClient({ initialSession: viewerSession() }));

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    expect(screen.getAllByText(CASE_CONTENT)).toHaveLength(1);
    expect(screen.queryAllByRole("status")).toHaveLength(0);
  });

  it("renders one refusal under the StrictMode double invoke", async () => {
    renderGuard(
      createFakeAuthClient({ initialSession: { subject: VIEWER, roles: ["PLATFORM_ADMIN"] } }),
    );

    expect(await screen.findByRole("heading", { name: "Access denied" })).toBeInTheDocument();
    expect(screen.getAllByRole("heading", { name: "Access denied" })).toHaveLength(1);
  });

  /**
   * The decision is visible in what is rendered; the inputs to it are not. A
   * role name, the principal type or a subject in the DOM would put session
   * detail into the page, into a screenshot and into any error reporter that
   * serializes the document.
   */
  it.each([
    ["FDS_ANALYST", ["FDS_ANALYST"]],
    ["PLATFORM_ADMIN", ["PLATFORM_ADMIN"]],
  ] as ReadonlyArray<[string, AuthSession["roles"]]>)(
    "puts no claim of a %s session into the DOM",
    async (_label, roles) => {
      const { container } = renderGuard(
        createFakeAuthClient({ initialSession: { subject: VIEWER, roles } }),
      );

      await waitFor(() => {
        expect(authStatus()).toBe("authenticated");
      });
      const markup = container.innerHTML;
      for (const secret of [
        "FDS_VIEWER",
        "FDS_ANALYST",
        "FDS_APPROVER",
        "RULE_OPERATOR",
        "RECOVERY_OPERATOR",
        "PLATFORM_ADMIN",
        "principal_type",
        "roles",
        VIEWER,
        "Bearer",
        "access_token",
        "id_token",
      ]) {
        expect(markup).not.toContain(secret);
      }
    },
  );

  it("never asks the port to authorize anything", async () => {
    const client = createFakeAuthClient({ initialSession: viewerSession() });
    renderGuard(client);

    expect(await screen.findByText(CASE_CONTENT)).toBeInTheDocument();
    expect(client.calls.authorizeRequest).toBe(0);
    expect(client.calls.signOut).toBe(0);
  });
});
