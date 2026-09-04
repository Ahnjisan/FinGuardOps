import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";
import type { RouteObject } from "react-router-dom";
import { AuthCallbackPage } from "./AuthCallbackPage";
import { HealthPage } from "./HealthPage";
import { HomePage } from "./HomePage";
import { AuthCallbackError, safeAuthErrorMessage } from "../auth/authErrors";
import { OIDC_TRANSACTION_STORE_PREFIX } from "../auth/transactionStorage";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { renderRoutesWithAuth } from "../test/renderWithAuth";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";

const ROUTES: RouteObject[] = [
  { path: "/", element: <HomePage /> },
  { path: "/health", element: <HealthPage /> },
  { path: "/auth/callback", element: <AuthCallbackPage /> },
];

const CALLBACK_ORIGIN = "http://localhost:3000";

let replaceStateSpy: ReturnType<typeof vi.spyOn>;
let currentHref: string;

function setCallbackHref(search: string, hash = ""): void {
  currentHref = `${CALLBACK_ORIGIN}/auth/callback${search}${hash}`;
}

function renderCallback(client: FakeAuthClient, search = "?code=abc&state=xyz", hash = "") {
  setCallbackHref(search, hash);
  return renderRoutesWithAuth(ROUTES, {
    client,
    initialEntries: [`/auth/callback${search}${hash}`],
  });
}

/** What the address bar looks like after any replaceState the page performed. */
function currentSearch(): string {
  return new URL(currentHref).search;
}

/** Flushes the microtask that settles a synchronously created outcome. */
async function flushCallback(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
  });
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  window.sessionStorage.clear();
  currentHref = `${CALLBACK_ORIGIN}/auth/callback`;

  vi.spyOn(window, "location", "get").mockImplementation(
    () => ({ href: currentHref, origin: CALLBACK_ORIGIN }) as unknown as Location,
  );
  replaceStateSpy = vi
    .spyOn(window.history, "replaceState")
    .mockImplementation((_data: unknown, _unused: string, url?: string | URL | null) => {
      currentHref = new URL(String(url ?? ""), CALLBACK_ORIGIN).href;
    });
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  window.sessionStorage.clear();
});

describe("AuthCallbackPage URL handling", () => {
  it("clears the authorization response before contacting the provider", async () => {
    const client = createFakeAuthClient();
    const deferred = client.deferCompleteSignIn();
    renderCallback(client);

    // The provider call has not settled yet, and the URL is already clean.
    expect(client.calls.completeSignIn).toHaveLength(1);
    expect(currentSearch()).toBe("");
    expect(replaceStateSpy).toHaveBeenCalledWith(null, "", "/auth/callback");

    await act(async () => {
      deferred.resolve({ session: { subject: "sub-1" }, returnTo: "/" });
      await deferred.promise;
    });
  });

  it("passes the captured URL, not the cleaned one, to the provider", async () => {
    const client = createFakeAuthClient();
    renderCallback(client, "?code=abc&state=xyz");

    await waitFor(() => {
      expect(client.calls.completeSignIn[0]).toContain("code=abc");
    });
    expect(client.calls.completeSignIn[0]).toContain("state=xyz");
  });

  it("clears the URL on a provider failure too", async () => {
    const client = createFakeAuthClient();
    const deferred = client.deferCompleteSignIn();
    renderCallback(client, "?error=access_denied&error_description=User%20said%20no");

    await act(async () => {
      deferred.reject(new Error("provider rejected: state=xyz code=SECRET"));
      await deferred.promise.catch(() => undefined);
    });

    expect(currentSearch()).toBe("");
  });

  it("fails closed without contacting the provider when the URL cannot be cleared", async () => {
    replaceStateSpy.mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    const client = createFakeAuthClient();
    renderCallback(client);
    await flushCallback();

    expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    expect(client.calls.completeSignIn).toHaveLength(0);
    expect(screen.queryByRole("heading", { name: /finguardops frontend/i })).not.toBeInTheDocument();
  });

  it("does not authenticate when the URL cannot be cleared", async () => {
    replaceStateSpy.mockImplementation(() => {
      throw new DOMException("blocked", "SecurityError");
    });
    const client = createFakeAuthClient();
    renderCallback(client);
    await flushCallback();

    expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    expect(screen.queryByRole("heading", { name: /backend health/i })).not.toBeInTheDocument();
  });

  it("does not leak the raw DOMException when the URL cannot be cleared", async () => {
    replaceStateSpy.mockImplementation(() => {
      throw new DOMException("blocked by policy at evil.example", "SecurityError");
    });
    const client = createFakeAuthClient();
    renderCallback(client);
    await flushCallback();

    expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    expect(document.body.textContent).not.toContain("blocked by policy");
    expect(document.body.textContent).not.toContain("SecurityError");
  });
});

describe("AuthCallbackPage direct entry", () => {
  it("never calls the provider without an authorization response", async () => {
    const client = createFakeAuthClient();
    renderCallback(client, "");
    await flushCallback();

    expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    expect(client.calls.completeSignIn).toHaveLength(0);
  });

  it("clears the transaction record on direct entry", async () => {
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`, "value");
    window.sessionStorage.setItem("other-app.key", "keep");
    const client = createFakeAuthClient();
    renderCallback(client, "");
    await flushCallback();

    expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    expect(window.sessionStorage.getItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`)).toBeNull();
    expect(window.sessionStorage.getItem("other-app.key")).toBe("keep");
  });

  it("ignores a query parameter that merely looks like a callback parameter", async () => {
    const client = createFakeAuthClient();
    renderCallback(client, "?zipcode=12345&next=code");
    await flushCallback();

    expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    expect(client.calls.completeSignIn).toHaveLength(0);
  });

  it("offers only an explicit link home, with no automatic navigation", async () => {
    const client = createFakeAuthClient();
    renderCallback(client, "");
    await flushCallback();

    expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    expect(screen.getByRole("link", { name: /return home/i })).toHaveAttribute("href", "/");
    expect(screen.queryByRole("heading", { name: /finguardops frontend/i })).not.toBeInTheDocument();
  });
});

describe("AuthCallbackPage conflicting authorization response", () => {
  const CONFLICTING = "?code=abc&error=access_denied&state=xyz";

  it("never hands a response carrying both code and error to the provider", async () => {
    const client = createFakeAuthClient();
    renderCallback(client, CONFLICTING);
    await flushCallback();

    expect(client.calls.completeSignIn).toHaveLength(0);
    expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
  });

  it("clears the URL immediately, before refusing the response", async () => {
    const client = createFakeAuthClient();
    renderCallback(client, CONFLICTING);

    // Already clean before anything settles.
    expect(currentSearch()).toBe("");
    expect(replaceStateSpy).toHaveBeenCalledWith(null, "", "/auth/callback");
    await flushCallback();
  });

  it("clears the transaction record exactly once", async () => {
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`, "value");
    window.sessionStorage.setItem("other-app.key", "keep");
    const removeItemSpy = vi.spyOn(Storage.prototype, "removeItem");
    const client = createFakeAuthClient();
    renderCallback(client, CONFLICTING);
    await flushCallback();

    const transactionRemovals = removeItemSpy.mock.calls.filter(([key]) =>
      String(key).startsWith(OIDC_TRANSACTION_STORE_PREFIX),
    );
    expect(transactionRemovals).toHaveLength(1);
    expect(window.sessionStorage.getItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`)).toBeNull();
    expect(window.sessionStorage.getItem("other-app.key")).toBe("keep");
  });

  it("does not navigate, and does not authenticate", async () => {
    const client = createFakeAuthClient();
    renderCallback(client, CONFLICTING);
    await flushCallback();

    expect(screen.getByRole("heading", { name: /signing in/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /finguardops frontend/i })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /backend health/i })).not.toBeInTheDocument();
  });

  it("leaves no part of the response in the DOM", async () => {
    const client = createFakeAuthClient();
    renderCallback(client, CONFLICTING);
    await flushCallback();

    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("abc");
    expect(rendered).not.toContain("access_denied");
    expect(rendered).not.toContain("xyz");
  });

  it("writes nothing about the response to the console", async () => {
    const consoleError = vi.spyOn(console, "error");
    const consoleWarn = vi.spyOn(console, "warn");
    const client = createFakeAuthClient();
    renderCallback(client, CONFLICTING);
    await flushCallback();

    expect(consoleError).not.toHaveBeenCalled();
    expect(consoleWarn).not.toHaveBeenCalled();
  });
});

describe("AuthCallbackPage storage acquisition failure", () => {
  const SECRETS = "?code=SECRET_CODE&state=SECRET_STATE";

  /** The `sessionStorage` property getter itself throws, as in a blocked context. */
  function breakSessionStorage(): void {
    vi.spyOn(window, "sessionStorage", "get").mockImplementation(() => {
      throw new DOMException("blocked at https://embed.example", "SecurityError");
    });
  }

  /**
   * A client standing in for the real adapter under the same fault: it cannot
   * acquire storage either, so it rejects with the fixed callback error.
   */
  function unavailableStorageClient(): FakeAuthClient {
    const client = createFakeAuthClient();
    client.completeSignIn = (callbackUrl: string) => {
      client.calls.completeSignIn.push(callbackUrl);
      return Promise.reject(new AuthCallbackError());
    };
    return client;
  }

  it("clears the URL query and fragment before touching storage", async () => {
    breakSessionStorage();
    const client = createFakeAuthClient();
    client.completeSignIn = () => Promise.reject(new Error("must not run"));
    renderCallback(client, SECRETS, "#SECRET_FRAGMENT");

    expect(replaceStateSpy).toHaveBeenCalledWith(null, "", "/auth/callback");
    expect(currentSearch()).toBe("");
    expect(new URL(currentHref).hash).toBe("");
    await flushCallback();
  });

  it("still fails safely, with no navigation and no session", async () => {
    breakSessionStorage();
    const client = unavailableStorageClient();
    renderCallback(client, SECRETS);
    await flushCallback();

    expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    expect(screen.getByRole("heading", { name: /signing in/i })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /finguardops frontend/i })).not.toBeInTheDocument();
  });

  it("exposes neither the response nor the raw storage error", async () => {
    const consoleError = vi.spyOn(console, "error");
    breakSessionStorage();
    const client = unavailableStorageClient();
    renderCallback(client, SECRETS, "#SECRET_FRAGMENT");
    await flushCallback();

    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("SECRET_CODE");
    expect(rendered).not.toContain("SECRET_STATE");
    expect(rendered).not.toContain("SECRET_FRAGMENT");
    expect(rendered).not.toContain("SecurityError");
    expect(rendered).not.toContain("embed.example");
    expect(consoleError).not.toHaveBeenCalled();
  });
});

describe("AuthCallbackPage completion", () => {
  it("navigates to an allowlisted return route on success", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));
    const client = createFakeAuthClient({
      completeSignInResult: { session: { subject: "sub-1" }, returnTo: "/health" },
    });
    renderCallback(client);

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: /backend health/i })).toBeInTheDocument();
    });
  });

  it("falls back to the root route for a hostile return value", async () => {
    const client = createFakeAuthClient({
      completeSignInResult: {
        session: { subject: "sub-1" },
        returnTo: "https://evil.example/steal",
      },
    });
    renderCallback(client);

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    });
    expect(document.body.textContent).not.toContain("evil.example");
  });

  it("processes the callback exactly once under StrictMode", async () => {
    const client = createFakeAuthClient();
    renderCallback(client);

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    });
    expect(client.calls.completeSignIn).toHaveLength(1);
  });

  it("still reaches the success UI when the result settles after the StrictMode replay", async () => {
    const client = createFakeAuthClient();
    const deferred = client.deferCompleteSignIn();
    renderCallback(client);

    expect(client.calls.completeSignIn).toHaveLength(1);
    await act(async () => {
      deferred.resolve({ session: { subject: "sub-1" }, returnTo: "/" });
      await deferred.promise;
    });

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    });
  });

  it("still reaches the error UI when the failure settles after the StrictMode replay", async () => {
    const client = createFakeAuthClient();
    const deferred = client.deferCompleteSignIn();
    renderCallback(client);

    expect(client.calls.completeSignIn).toHaveLength(1);
    await act(async () => {
      deferred.reject(new Error("state mismatch: nonce=abc code=SECRET_CODE"));
      await deferred.promise.catch(() => undefined);
    });

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    });
  });

  it("shows a fixed message and hides the provider failure text", async () => {
    const client = createFakeAuthClient();
    const deferred = client.deferCompleteSignIn();
    renderCallback(client, "?error=access_denied&error_description=Consent%20refused");

    await act(async () => {
      deferred.reject(new Error("state mismatch: nonce=abc code=SECRET_CODE"));
      await deferred.promise.catch(() => undefined);
    });

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    });
    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("SECRET_CODE");
    expect(rendered).not.toContain("nonce");
    expect(rendered).not.toContain("access_denied");
    expect(rendered).not.toContain("Consent refused");
    expect(rendered).not.toContain("state mismatch");
  });

  it("does not navigate after a failure", async () => {
    const client = createFakeAuthClient();
    const deferred = client.deferCompleteSignIn();
    renderCallback(client);

    await act(async () => {
      deferred.reject(new Error("provider failure"));
      await deferred.promise.catch(() => undefined);
    });

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(safeAuthErrorMessage("callback"));
    });
    expect(screen.getByRole("heading", { name: /signing in/i })).toBeInTheDocument();
  });

  it("drops a result that settles after the route unmounted", async () => {
    const consoleError = vi.spyOn(console, "error");
    const client = createFakeAuthClient();
    const deferred = client.deferCompleteSignIn();
    const view = renderCallback(client);

    view.unmount();
    await act(async () => {
      deferred.resolve({ session: { subject: "sub-1" }, returnTo: "/health" });
      await deferred.promise;
    });

    expect(consoleError).not.toHaveBeenCalled();
  });

  it("releases the shared record so a genuine later callback runs again", async () => {
    const client = createFakeAuthClient();
    const first = renderCallback(client);

    await waitFor(() => {
      expect(client.calls.completeSignIn).toHaveLength(1);
    });
    first.unmount();

    const second = renderCallback(client, "?code=def&state=uvw");
    await waitFor(() => {
      expect(client.calls.completeSignIn).toHaveLength(2);
    });
    expect(client.calls.completeSignIn[1]).toContain("code=def");
    second.unmount();
  });

  it("completes a second genuine callback exactly once after the first settled", async () => {
    const client = createFakeAuthClient();
    const firstDeferred = client.deferCompleteSignIn();
    const first = renderCallback(client);

    await act(async () => {
      firstDeferred.resolve({ session: { subject: "sub-1" }, returnTo: "/" });
      await firstDeferred.promise;
    });
    first.unmount();

    const secondDeferred = client.deferCompleteSignIn();
    const second = renderCallback(client, "?code=def&state=uvw");
    expect(client.calls.completeSignIn).toHaveLength(2);

    await act(async () => {
      secondDeferred.resolve({ session: { subject: "sub-2" }, returnTo: "/" });
      await secondDeferred.promise;
    });

    // The second callback completed exactly once, undisturbed by the first.
    expect(client.calls.completeSignIn).toHaveLength(2);
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    });
    second.unmount();
  });
});
