import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen } from "@testing-library/react";

function setUpRootElement(): void {
  document.body.innerHTML = '<div id="root"></div>';
}

function stubValidEnv(): void {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  vi.stubEnv("VITE_OIDC_AUTHORITY", "http://localhost:8002");
  vi.stubEnv("VITE_OIDC_CLIENT_ID", "finguardops-frontend");
}

beforeEach(() => {
  setUpRootElement();
  window.history.replaceState(null, "", "/");
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.resetModules();
  vi.restoreAllMocks();
  window.history.replaceState(null, "", "/");
  window.sessionStorage.clear();
  document.body.innerHTML = "";
});

describe("application entry (bootstrap)", () => {
  it("fails fast on import when VITE_API_BASE_URL is missing, before any render", async () => {
    stubValidEnv();
    vi.stubEnv("VITE_API_BASE_URL", "");

    await expect(import("./main")).rejects.toThrow();

    expect(document.getElementById("root")?.childElementCount).toBe(0);
  });

  it("fails fast on import when VITE_API_BASE_URL is invalid, before any render", async () => {
    stubValidEnv();
    vi.stubEnv("VITE_API_BASE_URL", "not-a-valid-url");

    await expect(import("./main")).rejects.toThrow();

    expect(document.getElementById("root")?.childElementCount).toBe(0);
  });

  it("fails fast on import when VITE_OIDC_AUTHORITY is missing, before any render", async () => {
    stubValidEnv();
    vi.stubEnv("VITE_OIDC_AUTHORITY", "");

    await expect(import("./main")).rejects.toThrow();

    expect(document.getElementById("root")?.childElementCount).toBe(0);
  });

  it("fails fast on import when VITE_OIDC_AUTHORITY is invalid, before any render", async () => {
    stubValidEnv();
    vi.stubEnv("VITE_OIDC_AUTHORITY", "https://user@as.example");

    await expect(import("./main")).rejects.toThrow();

    expect(document.getElementById("root")?.childElementCount).toBe(0);
  });

  it("fails fast on import when VITE_OIDC_CLIENT_ID is missing, before any render", async () => {
    stubValidEnv();
    vi.stubEnv("VITE_OIDC_CLIENT_ID", "");

    await expect(import("./main")).rejects.toThrow();

    expect(document.getElementById("root")?.childElementCount).toBe(0);
  });

  it("fails fast on import when VITE_OIDC_CLIENT_ID has surrounding whitespace", async () => {
    stubValidEnv();
    vi.stubEnv("VITE_OIDC_CLIENT_ID", " finguardops-frontend");

    await expect(import("./main")).rejects.toThrow();

    expect(document.getElementById("root")?.childElementCount).toBe(0);
  });

  it("validates configuration before checking for the root element", async () => {
    document.body.innerHTML = "";
    stubValidEnv();
    vi.stubEnv("VITE_API_BASE_URL", "not-a-valid-url");

    const error = await import("./main").catch((caught: unknown) => caught);

    expect((error as Error).name).toBe("EnvConfigError");
  });

  it("validates the OIDC configuration before checking for the root element", async () => {
    document.body.innerHTML = "";
    stubValidEnv();
    vi.stubEnv("VITE_OIDC_AUTHORITY", "not-a-valid-url");

    const error = await import("./main").catch((caught: unknown) => caught);

    expect((error as Error).name).toBe("EnvConfigError");
  });

  it("renders the application when configuration is valid", async () => {
    stubValidEnv();

    await act(async () => {
      await import("./main");
    });

    expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
  });

  it("mounts the authentication boundary around the router", async () => {
    stubValidEnv();

    await act(async () => {
      await import("./main");
    });

    expect(screen.getByRole("status", { name: "Authentication status" })).toBeInTheDocument();
  });

  it("runs environment validation exactly once on a valid start", async () => {
    stubValidEnv();
    const envModule = await import("./config/env");
    const getEnvSpy = vi.spyOn(envModule, "getEnv");
    const getAuthEnvSpy = vi.spyOn(envModule, "getAuthEnv");

    await act(async () => {
      await import("./main");
    });

    expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    expect(getEnvSpy).toHaveBeenCalledTimes(1);
    // The auth client asks for the same configuration when it is built, so what
    // matters is that validation itself ran once: every call hands back the one
    // memoized object rather than re-parsing the environment.
    expect(getAuthEnvSpy).toHaveBeenCalled();
    const authEnvResults = getAuthEnvSpy.mock.results.map((result) => result.value);
    expect(new Set(authEnvResults).size).toBe(1);
  });

  it("does not include the raw invalid input in the thrown error", async () => {
    stubValidEnv();
    const secretLikeValue = "not-a-valid-url-secret-token-xyz";
    vi.stubEnv("VITE_API_BASE_URL", secretLikeValue);

    const error = await import("./main").catch((caught: unknown) => caught);

    expect((error as Error).message).not.toContain(secretLikeValue);
  });

  it("does not include the raw invalid OIDC input in the thrown error", async () => {
    stubValidEnv();
    const secretLikeValue = "https://operator:hunter2@secret-host.internal";
    vi.stubEnv("VITE_OIDC_AUTHORITY", secretLikeValue);

    const error = await import("./main").catch((caught: unknown) => caught);

    expect((error as Error).message).not.toContain("hunter2");
    expect((error as Error).message).not.toContain("secret-host.internal");
  });

  it("throws the standard 'root element not found' error only when configuration is already valid", async () => {
    document.body.innerHTML = "";
    stubValidEnv();

    const error = await import("./main").catch((caught: unknown) => caught);

    expect((error as Error).message).toBe("Root element not found.");
  });
});

/** Boots the real StrictMode + AuthProvider + RouterProvider tree at `path`. */
async function bootAt(path: string): Promise<void> {
  window.history.replaceState(null, "", path);
  await act(async () => {
    await import("./main");
  });
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe("application entry with unusable Web Storage", () => {
  const AUTHORITY = "http://localhost:8002";

  /**
   * The sessionStorage property getter throws, as it does in a partitioned or
   * cookie-blocked embedding. This is the real production fault: not a helper
   * returning null, but a property read that raises SecurityError.
   */
  function breakSessionStorage(): void {
    vi.spyOn(window, "sessionStorage", "get").mockImplementation(() => {
      throw new DOMException("blocked at https://embed.example", "SecurityError");
    });
  }

  function consoleSpies() {
    return {
      error: vi.spyOn(console, "error"),
      warn: vi.spyOn(console, "warn"),
    };
  }

  it("still renders the public outlet at the root path", async () => {
    stubValidEnv();
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const spies = consoleSpies();
    breakSessionStorage();

    await bootAt("/");

    expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: /primary/i })).toBeInTheDocument();
    expect(spies.error).not.toHaveBeenCalled();
    expect(spies.warn).not.toHaveBeenCalled();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("still renders the public outlet at /health", async () => {
    stubValidEnv();
    const fetchSpy = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ status: "UP", service: "backend" }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchSpy);
    const spies = consoleSpies();
    breakSessionStorage();

    await bootAt("/health");

    expect(screen.getByRole("heading", { name: /backend health/i })).toBeInTheDocument();
    expect(spies.error).not.toHaveBeenCalled();
    // Only the backend health call; nothing reached the Authorization Server.
    for (const call of fetchSpy.mock.calls as Array<[string]>) {
      expect(String(call[0])).not.toContain(AUTHORITY);
    }
  });

  it("confines the failure to a fixed authentication error, with no redirect", async () => {
    stubValidEnv();
    vi.stubGlobal("fetch", vi.fn());
    breakSessionStorage();

    await bootAt("/");

    const authStatus = screen.getByRole("status", { name: "Authentication status" });
    expect(authStatus.textContent).toBe(
      "Authentication is unavailable right now. Please contact an administrator.",
    );
    // The user is offered a button; nothing signed in on its own.
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sign out" })).not.toBeInTheDocument();
  });

  it("exposes no raw DOMException text anywhere in the document", async () => {
    stubValidEnv();
    vi.stubGlobal("fetch", vi.fn());
    breakSessionStorage();

    await bootAt("/");

    const rendered = document.body.textContent ?? "";
    expect(rendered).not.toContain("SecurityError");
    expect(rendered).not.toContain("embed.example");
    expect(rendered).not.toContain("blocked");
  });

  it("clears the callback URL before any storage access, then fails safely", async () => {
    stubValidEnv();
    vi.stubGlobal("fetch", vi.fn());
    const spies = consoleSpies();
    breakSessionStorage();

    await bootAt("/auth/callback?code=SECRET_CODE&state=SECRET_STATE#SECRET_FRAGMENT");

    // The one-time response is out of the address bar even though storage threw.
    expect(window.location.pathname).toBe("/auth/callback");
    expect(window.location.search).toBe("");
    expect(window.location.hash).toBe("");

    // And nothing was authenticated on the way.
    expect(screen.getByRole("heading", { name: /signing in/i })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Sign out" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: /finguardops frontend/i })).not.toBeInTheDocument();

    const rendered = document.body.textContent ?? "";
    expect(rendered).toContain("Sign-in could not be completed.");
    expect(rendered).not.toContain("SECRET_CODE");
    expect(rendered).not.toContain("SECRET_STATE");
    expect(rendered).not.toContain("SECRET_FRAGMENT");
    expect(rendered).not.toContain("SecurityError");
    expect(spies.error).not.toHaveBeenCalled();
  });

  it("leaves no unhandled rejection behind when storage is unusable", async () => {
    stubValidEnv();
    vi.stubGlobal("fetch", vi.fn());
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);
    breakSessionStorage();

    await bootAt("/auth/callback?code=SECRET_CODE&state=SECRET_STATE");
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    process.off("unhandledRejection", unhandled);

    expect(unhandled).not.toHaveBeenCalled();
  });
});

describe("application entry transaction hygiene", () => {
  const TRANSACTION_PREFIX = "finguardops.oidc.transaction.";

  it("removes an abandoned transaction record when starting off the callback route", async () => {
    stubValidEnv();
    vi.stubGlobal("fetch", vi.fn());
    window.sessionStorage.setItem(TRANSACTION_PREFIX + "abandoned", "left over");
    window.sessionStorage.setItem("other-app.key", "keep");

    await bootAt("/");

    expect(window.sessionStorage.getItem(TRANSACTION_PREFIX + "abandoned")).toBeNull();
    expect(window.sessionStorage.getItem("other-app.key")).toBe("keep");
  });

  it("still has the in-flight record when the protocol step reads it", async () => {
    stubValidEnv();
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network blocked")));
    const stateKey = TRANSACTION_PREFIX + "SECRET_STATE";
    window.sessionStorage.setItem(stateKey, "{}");
    const getItemSpy = vi.spyOn(Storage.prototype, "getItem");

    await bootAt("/auth/callback?code=abc&state=SECRET_STATE");

    // If initialization had swept the prefix, the library would have found
    // nothing to read. That it looked the record up is what proves the record
    // belonging to this callback survived initialization.
    expect(getItemSpy.mock.calls.map((call) => String(call[0]))).toContain(stateKey);
    // The callback still ends in its fixed failure, and cleanup still happened.
    expect(window.sessionStorage.getItem(stateKey)).toBeNull();
    expect(document.body.textContent ?? "").toContain("Sign-in could not be completed.");
  });

  it("cleans the transaction record after the callback route settles", async () => {
    stubValidEnv();
    vi.stubGlobal("fetch", vi.fn());
    window.sessionStorage.setItem(TRANSACTION_PREFIX + "current", "in-flight");
    window.sessionStorage.setItem("other-app.key", "keep");

    await bootAt("/auth/callback");

    // Direct entry carries no authorization response: the page abandons it and
    // owns the cleanup, touching nothing outside its own prefix.
    expect(window.sessionStorage.getItem(TRANSACTION_PREFIX + "current")).toBeNull();
    expect(window.sessionStorage.getItem("other-app.key")).toBe("keep");
    expect(screen.getByRole("heading", { name: /signing in/i })).toBeInTheDocument();
  });
});
