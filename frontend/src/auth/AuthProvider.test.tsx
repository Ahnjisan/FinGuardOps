import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AuthProvider } from "./AuthProvider";
import { useAuth } from "./useAuth";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";

function AuthProbe() {
  const { state, signIn, signOut } = useAuth();
  return (
    <div>
      <span data-testid="status">{state.status}</span>
      <span data-testid="kind">{state.status === "error" ? state.kind : ""}</span>
      <span data-testid="subject">
        {state.status === "authenticated" ? state.session.subject : ""}
      </span>
      <button
        type="button"
        onClick={() => {
          signIn("/health");
        }}
      >
        Sign in
      </button>
      <button type="button" onClick={signOut}>
        Sign out
      </button>
    </div>
  );
}

function renderProbe(client: FakeAuthClient, strict = true) {
  const tree = (
    <AuthProvider client={client}>
      <AuthProbe />
    </AuthProvider>
  );
  return render(strict ? <StrictMode>{tree}</StrictMode> : tree);
}

function status(): string {
  return screen.getByTestId("status").textContent ?? "";
}

let consoleErrorSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  consoleErrorSpy = vi.spyOn(console, "error");
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe("AuthProvider initialization", () => {
  it("moves from initializing to unauthenticated", async () => {
    const client = createFakeAuthClient();
    renderProbe(client);

    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });
  });

  it("initializes exactly once under StrictMode", async () => {
    const client = createFakeAuthClient();
    renderProbe(client);

    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });
    // Two effect setups, one shared initialization run.
    expect(client.calls.initialize).toBe(2);
    expect(client.calls.initializeWork).toBe(1);
  });

  it("subscribes on every setup and unsubscribes only its own listener", async () => {
    const client = createFakeAuthClient();
    renderProbe(client);

    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });
    // setup, cleanup, setup: two registrations, one removal, one live listener.
    expect(client.calls.listenerAdds).toBe(2);
    expect(client.calls.listenerRemoves).toBe(1);
    expect(client.listenerCount()).toBe(1);
  });

  it("keeps the surviving StrictMode effect subscribed to invalidation", async () => {
    const client = createFakeAuthClient({
      initialSession: { subject: "sub-1" },
    });
    renderProbe(client);

    await waitFor(() => {
      expect(status()).toBe("authenticated");
    });

    act(() => {
      client.emitSessionInvalidated();
    });

    expect(status()).toBe("unauthenticated");
  });

  it("balances listener registration and removal on unmount", async () => {
    const client = createFakeAuthClient();
    const view = renderProbe(client);

    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });
    view.unmount();

    expect(client.calls.listenerAdds).toBe(client.calls.listenerRemoves);
    expect(client.listenerCount()).toBe(0);
  });

  it("ignores an invalidation event that arrives after unmount", async () => {
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1" } });
    const view = renderProbe(client);

    await waitFor(() => {
      expect(status()).toBe("authenticated");
    });
    view.unmount();
    client.emitSessionInvalidated();

    expect(consoleErrorSpy).not.toHaveBeenCalled();
  });

  it("delivers a late initialize result only to the surviving effect", async () => {
    const client = createFakeAuthClient();
    const deferred = client.deferInitialize();
    renderProbe(client);

    expect(status()).toBe("initializing");
    await act(async () => {
      deferred.resolve({ session: null });
      await deferred.promise;
    });

    expect(status()).toBe("unauthenticated");
    expect(client.calls.initializeWork).toBe(1);
  });

  it("drops a late initialize result after unmount", async () => {
    const client = createFakeAuthClient();
    const deferred = client.deferInitialize();
    const view = renderProbe(client);

    view.unmount();
    await act(async () => {
      deferred.resolve({ session: { subject: "sub-1" } });
      await deferred.promise;
    });

    expect(consoleErrorSpy).not.toHaveBeenCalled();
  });

  it("reports a fixed configuration error when initialization fails", async () => {
    const client = createFakeAuthClient();
    client.failInitialize();
    renderProbe(client);

    await waitFor(() => {
      expect(status()).toBe("error");
    });
    expect(screen.getByTestId("kind").textContent).toBe("configuration");
  });

  it("restores an in-memory session found at initialization", async () => {
    const client = createFakeAuthClient({
      initialSession: { subject: "sub-1", displayName: "Analyst" },
    });
    renderProbe(client);

    await waitFor(() => {
      expect(status()).toBe("authenticated");
    });
    expect(screen.getByTestId("subject").textContent).toBe("sub-1");
  });

  it("never starts a sign-in on its own", async () => {
    const client = createFakeAuthClient();
    renderProbe(client);

    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });
    expect(client.calls.signIn).toHaveLength(0);
    expect(client.calls.completeSignIn).toHaveLength(0);
  });
});

describe("AuthProvider sign-in", () => {
  it("starts a redirect once per user action", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });

    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(client.calls.signIn).toEqual(["/health"]);
    expect(status()).toBe("authenticating");
  });

  it("ignores a duplicate sign-in click", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });

    const button = screen.getByRole("button", { name: "Sign in" });
    await user.click(button);
    await user.click(button);
    await user.click(button);

    expect(client.calls.signIn).toHaveLength(1);
  });

  it("reports a fixed sign-in error when the redirect cannot start", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    client.failSignIn();
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });

    await user.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => {
      expect(status()).toBe("error");
    });
    expect(screen.getByTestId("kind").textContent).toBe("sign-in");
  });

  it("allows a retry after a failed sign-in", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    client.failSignIn();
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });

    await user.click(screen.getByRole("button", { name: "Sign in" }));
    await waitFor(() => {
      expect(status()).toBe("error");
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(client.calls.signIn).toHaveLength(2);
  });
});

describe("AuthProvider redirect cancellation and BFCache restore", () => {
  /** The event the browser fires when a document is restored, not re-created. */
  function firePageShow(persisted: boolean): void {
    act(() => {
      window.dispatchEvent(new PageTransitionEvent("pageshow", { persisted }));
    });
  }

  it("restores an explicit retry after a BFCache return with no callback", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });

    // The redirect started and resolved; the callback was never reached.
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(status()).toBe("authenticating");
    expect(client.calls.signIn).toHaveLength(1);

    firePageShow(true);

    // The Sign in affordance is back, and a second click actually redirects.
    expect(status()).not.toBe("authenticating");
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    expect(client.calls.signIn).toHaveLength(2);
  });

  it("does not re-authenticate or redirect on its own after the restore", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    firePageShow(true);
    await act(async () => {
      await Promise.resolve();
    });

    expect(client.calls.signIn).toHaveLength(1);
    expect(client.calls.completeSignIn).toHaveLength(0);
    expect(status()).not.toBe("authenticated");
  });

  it("shows only a fixed message after the restore", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    firePageShow(true);

    expect(status()).toBe("error");
    expect(screen.getByTestId("kind").textContent).toBe("sign-in");
  });

  it("leaves a live sign-in alone on an ordinary non-persisted pageshow", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient();
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    firePageShow(false);

    expect(status()).toBe("authenticating");
    expect(client.calls.signIn).toHaveLength(1);
  });

  it("does not disturb an authenticated session on a persisted pageshow", async () => {
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1" } });
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("authenticated");
    });

    firePageShow(true);

    expect(status()).toBe("authenticated");
  });

  it("does nothing on a persisted pageshow when no sign-in is pending", async () => {
    const client = createFakeAuthClient();
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });

    firePageShow(true);

    expect(status()).toBe("unauthenticated");
    expect(consoleErrorSpy).not.toHaveBeenCalled();
  });

  it("balances pageshow registration under StrictMode and leaves none after unmount", async () => {
    const addSpy = vi.spyOn(window, "addEventListener");
    const removeSpy = vi.spyOn(window, "removeEventListener");
    const client = createFakeAuthClient();
    const view = renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });

    const added = addSpy.mock.calls.filter(([type]) => type === "pageshow");
    expect(added).toHaveLength(2);
    expect(removeSpy.mock.calls.filter(([type]) => type === "pageshow")).toHaveLength(1);

    view.unmount();

    expect(removeSpy.mock.calls.filter(([type]) => type === "pageshow")).toHaveLength(2);
    // Every registered handler was handed back to removeEventListener.
    const removedHandlers = removeSpy.mock.calls
      .filter(([type]) => type === "pageshow")
      .map(([, handler]) => handler);
    for (const [, handler] of added) {
      expect(removedHandlers).toContain(handler);
    }
  });

  it("ignores a persisted pageshow after unmount", async () => {
    const client = createFakeAuthClient();
    const user = userEvent.setup();
    const view = renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("unauthenticated");
    });
    await user.click(screen.getByRole("button", { name: "Sign in" }));
    view.unmount();

    firePageShow(true);

    expect(consoleErrorSpy).not.toHaveBeenCalled();
  });
});

describe("AuthProvider sign-out and expiry", () => {
  it("drops the local session immediately, before teardown settles", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1" } });
    let released!: () => void;
    client.signOut = () => {
      client.calls.signOut += 1;
      return new Promise<void>((resolve) => {
        released = resolve;
      });
    };
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("authenticated");
    });

    await user.click(screen.getByRole("button", { name: "Sign out" }));

    expect(status()).toBe("unauthenticated");
    expect(client.calls.signOut).toBe(1);
    released();
  });

  it("stays unauthenticated when teardown rejects", async () => {
    const user = userEvent.setup();
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1" } });
    client.signOut = () => {
      client.calls.signOut += 1;
      return Promise.reject(new DOMException("blocked", "SecurityError"));
    };
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("authenticated");
    });

    await user.click(screen.getByRole("button", { name: "Sign out" }));
    await act(async () => {
      await Promise.resolve();
    });

    expect(status()).toBe("unauthenticated");
    expect(consoleErrorSpy).not.toHaveBeenCalled();
  });

  it("moves to unauthenticated on session invalidation", async () => {
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1" } });
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("authenticated");
    });

    act(() => {
      client.emitSessionInvalidated();
    });

    expect(status()).toBe("unauthenticated");
  });

  it("does not redirect or re-authenticate after invalidation", async () => {
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1" } });
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("authenticated");
    });

    act(() => {
      client.emitSessionInvalidated();
    });

    expect(client.calls.signIn).toHaveLength(0);
    expect(client.calls.completeSignIn).toHaveLength(0);
    expect(client.calls.initializeWork).toBe(1);
  });

  it("ignores a repeated invalidation", async () => {
    const client = createFakeAuthClient({ initialSession: { subject: "sub-1" } });
    renderProbe(client);
    await waitFor(() => {
      expect(status()).toBe("authenticated");
    });

    act(() => {
      client.emitSessionInvalidated();
      client.emitSessionInvalidated();
    });

    expect(status()).toBe("unauthenticated");
  });
});

describe("useAuth", () => {
  it("refuses to run outside a provider", () => {
    const suppressed = vi.spyOn(console, "error").mockImplementation(() => undefined);

    expect(() => render(<AuthProbe />)).toThrow(/AuthProvider/);

    suppressed.mockRestore();
  });
});
