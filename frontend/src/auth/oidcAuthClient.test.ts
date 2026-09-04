import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { InMemoryWebStorage, WebStorageStateStore } from "oidc-client-ts";
import type { AuthEnv } from "../config/env";
import { AuthCallbackError, AuthSignInError } from "./authErrors";
import {
  createOidcAuthClient,
  createOidcSettings,
  resolveSessionDeadline,
  SESSION_HARD_DEADLINE_MS,
  type OidcAuthClientOptions,
  type OidcUserLike,
  type UserManagerLike,
} from "./oidcAuthClient";
import {
  AuthStorageUnavailableError,
  OIDC_TRANSACTION_STORE_PREFIX,
  OIDC_USER_STORE_PREFIX,
} from "./transactionStorage";

const ENV: AuthEnv = {
  oidcAuthority: "https://as.example/realms/finguardops",
  oidcClientId: "finguardops-frontend",
};

const USER: OidcUserLike = {
  profile: { sub: "11111111-1111-4111-8111-111111111111", name: "Test Analyst" },
  state: { returnTo: "/health" },
};

interface FakeUserManager extends UserManagerLike {
  readonly calls: {
    signinRedirect: Array<{ state: unknown }>;
    signinRedirectCallback: string[];
    removeUser: number;
    getUser: number;
  };
  emitAccessTokenExpired(): void;
  expiredListenerCount(): number;
  setUser(user: OidcUserLike): void;
  setStoredUser(user: OidcUserLike | null): void;
  failGetUser(): void;
  /** Makes the next getUser() hang until the returned release runs. */
  deferGetUser(): () => void;
  failCallback(): void;
  failRemoveUser(): void;
  deferRemoveUser(): { resolve: () => void };
}

function createFakeUserManager(): FakeUserManager {
  const expiredListeners = new Set<() => void>();
  const calls = {
    signinRedirect: [] as Array<{ state: unknown }>,
    signinRedirectCallback: [] as string[],
    removeUser: 0,
    getUser: 0,
  };
  let user: OidcUserLike = USER;
  let storedUser: OidcUserLike | null = null;
  let getUserShouldFail = false;
  let getUserGate: Promise<void> | undefined;
  let callbackShouldFail = false;
  let removeUserShouldFail = false;
  let removeUserGate: Promise<void> | undefined;

  return {
    calls,
    emitAccessTokenExpired(): void {
      for (const listener of [...expiredListeners]) {
        listener();
      }
    },
    expiredListenerCount(): number {
      return expiredListeners.size;
    },
    setUser(next: OidcUserLike): void {
      user = next;
    },
    setStoredUser(next: OidcUserLike | null): void {
      storedUser = next;
    },
    failGetUser(): void {
      getUserShouldFail = true;
    },
    deferGetUser(): () => void {
      let release!: () => void;
      getUserGate = new Promise<void>((resolvePromise) => {
        release = () => {
          resolvePromise();
        };
      });
      return release;
    },
    failCallback(): void {
      callbackShouldFail = true;
    },
    failRemoveUser(): void {
      removeUserShouldFail = true;
    },
    deferRemoveUser(): { resolve: () => void } {
      let release!: () => void;
      removeUserGate = new Promise<void>((resolvePromise) => {
        release = () => {
          resolvePromise();
        };
      });
      return { resolve: release };
    },

    async signinRedirect(args: { state: unknown }): Promise<void> {
      calls.signinRedirect.push(args);
    },
    async signinRedirectCallback(url: string): Promise<OidcUserLike> {
      calls.signinRedirectCallback.push(url);
      if (callbackShouldFail) {
        throw new Error("state mismatch: expected nonce=abc, code=SECRET_CODE");
      }
      // A real callback installs the user in the store the adapter later reads.
      storedUser = user;
      return user;
    },
    async getUser(): Promise<OidcUserLike | null> {
      calls.getUser += 1;
      if (getUserGate !== undefined) {
        await getUserGate;
      }
      if (getUserShouldFail) {
        throw new Error("user store unavailable");
      }
      return storedUser;
    },
    async removeUser(): Promise<void> {
      calls.removeUser += 1;
      storedUser = null;
      if (removeUserGate !== undefined) {
        await removeUserGate;
      }
      if (removeUserShouldFail) {
        throw new DOMException("blocked", "SecurityError");
      }
    },
    events: {
      addAccessTokenExpired(callback: () => void): () => void {
        expiredListeners.add(callback);
        return () => {
          expiredListeners.delete(callback);
        };
      },
    },
  };
}

/**
 * Builds a client over a fixed runtime. The runtime factory is what production
 * defers, so tests that care about acquisition failure supply their own
 * throwing factory instead of using this helper.
 */
function clientFor(
  userManager: UserManagerLike,
  storage: Storage = window.sessionStorage,
  options: OidcAuthClientOptions = {},
) {
  return createOidcAuthClient(() => ({ userManager, storage }), {
    // Every path except the callback route itself; the callback-route tests opt
    // in explicitly, because that is where the sweep must not happen.
    isCallbackRoute: () => false,
    ...options,
  });
}

/** A storage whose named operation throws, with everything else working. */
function hostileStorage(failing: "length" | "key" | "removeItem"): Storage {
  const boom = () => {
    throw new DOMException("blocked at https://embed.example", "SecurityError");
  };
  return {
    get length(): number {
      if (failing === "length") {
        boom();
      }
      return 1;
    },
    key: (index: number) => {
      if (failing === "key") {
        boom();
      }
      return index === 0 ? `${OIDC_TRANSACTION_STORE_PREFIX}state-a` : null;
    },
    getItem: () => null,
    setItem: () => undefined,
    clear: () => undefined,
    removeItem: () => {
      if (failing === "removeItem") {
        boom();
      }
    },
  } as unknown as Storage;
}

/** Counts prefix sweeps, so shared initialization work is observable. */
function countingStorage(): { storage: Storage; sweeps: () => number } {
  let sweeps = 0;
  const backing = window.sessionStorage;
  const storage = {
    get length(): number {
      sweeps += 1;
      return backing.length;
    },
    key: (index: number) => backing.key(index),
    getItem: (key: string) => backing.getItem(key),
    setItem: (key: string, value: string) => {
      backing.setItem(key, value);
    },
    removeItem: (key: string) => {
      backing.removeItem(key);
    },
    clear: () => {
      backing.clear();
    },
  } as unknown as Storage;
  return { storage, sweeps: () => sweeps };
}

beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
});

afterEach(() => {
  vi.useRealTimers();
  window.sessionStorage.clear();
  window.localStorage.clear();
});

describe("createOidcSettings", () => {
  const settings = () => createOidcSettings(ENV, window.sessionStorage, "http://localhost:5173");

  it("uses the authority and client ID verbatim", () => {
    expect(settings().authority).toBe("https://as.example/realms/finguardops");
    expect(settings().client_id).toBe("finguardops-frontend");
  });

  it("derives the redirect URI from the current origin", () => {
    expect(settings().redirect_uri).toBe("http://localhost:5173/auth/callback");
  });

  it("defaults the redirect URI to the real window origin", () => {
    expect(createOidcSettings(ENV, window.sessionStorage).redirect_uri).toBe(`${window.location.origin}/auth/callback`);
  });

  it("requests exactly the authorization code response type", () => {
    expect(settings().response_type).toBe("code");
  });

  it("requests exactly the openid profile scope", () => {
    expect(settings().scope).toBe("openid profile");
  });

  it("does not request offline access", () => {
    expect(settings().scope).not.toContain("offline_access");
  });

  it("disables automatic silent renew explicitly", () => {
    expect(settings().automaticSilentRenew).toBe(false);
  });

  it("disables session monitoring explicitly", () => {
    expect(settings().monitorSession).toBe(false);
  });

  it("disables the userinfo round trip explicitly", () => {
    expect(settings().loadUserInfo).toBe(false);
  });

  it("keeps the user store in memory", () => {
    const userStore = settings().userStore;

    expect(userStore).toBeInstanceOf(WebStorageStateStore);
    const backing = (userStore as unknown as { _store: unknown })._store;
    expect(backing).toBeInstanceOf(InMemoryWebStorage);
    expect(backing).not.toBe(window.sessionStorage);
    expect(backing).not.toBe(window.localStorage);
  });

  it("keeps the transaction store in sessionStorage under its own prefix", () => {
    const stateStore = settings().stateStore;

    expect(stateStore).toBeInstanceOf(WebStorageStateStore);
    const store = stateStore as unknown as { _store: unknown; _prefix: string };
    expect(store._store).toBe(window.sessionStorage);
    expect(store._prefix).toBe(OIDC_TRANSACTION_STORE_PREFIX);
  });

  it("gives the memory user store a prefix distinct from the transaction store", () => {
    const userStore = settings().userStore as unknown as { _prefix: string };

    expect(userStore._prefix).toBe(OIDC_USER_STORE_PREFIX);
    expect(userStore._prefix).not.toBe(OIDC_TRANSACTION_STORE_PREFIX);
  });

  it("declares no client secret", () => {
    expect(settings()).not.toHaveProperty("client_secret");
  });

  it("declares no silent renew or logout callback URI", () => {
    expect(settings()).not.toHaveProperty("silent_redirect_uri");
    expect(settings()).not.toHaveProperty("post_logout_redirect_uri");
  });
});

describe("resolveSessionDeadline", () => {
  const now = 1_000_000;

  it("applies the hard cap when expires_at is missing", () => {
    expect(resolveSessionDeadline(now)).toBe(now + SESSION_HARD_DEADLINE_MS);
  });

  it.each([
    ["NaN", Number.NaN],
    ["Infinity", Number.POSITIVE_INFINITY],
    ["negative Infinity", Number.NEGATIVE_INFINITY],
  ])("applies the hard cap when expires_at is %s", (_label, value) => {
    expect(resolveSessionDeadline(now, value)).toBe(now + SESSION_HARD_DEADLINE_MS);
  });

  it("applies the hard cap when expires_at is not a number", () => {
    expect(resolveSessionDeadline(now, "9999999999" as unknown as number)).toBe(
      now + SESSION_HARD_DEADLINE_MS,
    );
  });

  it("caps a longer token lifetime at fifteen minutes", () => {
    const oneHourFromNow = now / 1000 + 3600;
    expect(resolveSessionDeadline(now, oneHourFromNow)).toBe(now + SESSION_HARD_DEADLINE_MS);
  });

  it("honours a shorter token lifetime", () => {
    const fiveMinutesFromNow = now / 1000 + 300;
    expect(resolveSessionDeadline(now, fiveMinutesFromNow)).toBe(now + 300_000);
  });

  it("invalidates immediately for an already expired token", () => {
    expect(resolveSessionDeadline(now, now / 1000 - 1)).toBe(now);
    expect(resolveSessionDeadline(now, 0)).toBe(now);
  });
});

describe("createOidcAuthClient initialization", () => {
  it("shares one in-flight promise across concurrent callers", async () => {
    const counting = countingStorage();
    const client = clientFor(createFakeUserManager(), counting.storage);

    const first = client.initialize();
    const second = client.initialize();
    await Promise.all([first, second]);

    expect(counting.sweeps()).toBe(1);
  });

  it("releases the in-flight entry so a later initialization runs again", async () => {
    const counting = countingStorage();
    const client = clientFor(createFakeUserManager(), counting.storage);

    await client.initialize();
    await client.initialize();

    expect(counting.sweeps()).toBe(2);
  });

  it("removes an abandoned transaction record off the callback route", async () => {
    const abandoned = `${OIDC_TRANSACTION_STORE_PREFIX}abandoned`;
    window.sessionStorage.setItem(abandoned, "left over");
    window.sessionStorage.setItem("other-app.key", "keep");
    const client = clientFor(createFakeUserManager());

    await client.initialize();

    expect(window.sessionStorage.getItem(abandoned)).toBeNull();
    expect(window.sessionStorage.getItem("other-app.key")).toBe("keep");
  });

  it("fails initialization when the abandoned-record sweep cannot complete", async () => {
    const client = clientFor(createFakeUserManager(), hostileStorage("removeItem"));

    await expect(client.initialize()).rejects.toBeDefined();
  });

  it.each(["length", "key", "removeItem"] as const)(
    "fails initialization when storage %s throws, without redirecting",
    async (failing) => {
      const manager = createFakeUserManager();
      const client = clientFor(manager, hostileStorage(failing));

      await expect(client.initialize()).rejects.toBeDefined();
      expect(manager.calls.signinRedirect).toHaveLength(0);
    },
  );

  it("fails initialization rather than throwing when the storage getter throws", async () => {
    const client = createOidcAuthClient(
      () => {
        throw new AuthStorageUnavailableError();
      },
      { isCallbackRoute: () => false },
    );

    await expect(client.initialize()).rejects.toBeInstanceOf(AuthStorageUnavailableError);
  });

  it("acquires no runtime at all on the callback route", async () => {
    let factoryCalls = 0;
    const client = createOidcAuthClient(
      () => {
        factoryCalls += 1;
        throw new AuthStorageUnavailableError();
      },
      { isCallbackRoute: () => true },
    );

    await expect(client.initialize()).resolves.toEqual({ session: null });
    expect(factoryCalls).toBe(0);
  });

  it("performs no network request", async () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const client = clientFor(createFakeUserManager());

    await client.initialize();

    expect(fetchSpy).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  it("reports no session on a fresh page load", async () => {
    const client = clientFor(createFakeUserManager());

    await expect(client.initialize()).resolves.toEqual({ session: null });
  });

  it("preserves the transaction record of an in-flight callback", async () => {
    const key = `${OIDC_TRANSACTION_STORE_PREFIX}current-state`;
    window.sessionStorage.setItem(key, "in-flight");
    const client = clientFor(createFakeUserManager(), window.sessionStorage, {
      isCallbackRoute: () => true,
    });

    await client.initialize();

    expect(window.sessionStorage.getItem(key)).toBe("in-flight");
  });

  it("completes a callback that was seeded before initialization", async () => {
    const key = `${OIDC_TRANSACTION_STORE_PREFIX}current-state`;
    window.sessionStorage.setItem(key, "in-flight");
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage, { isCallbackRoute: () => true });

    await client.initialize();
    const result = await client.completeSignIn("http://localhost/auth/callback?code=a&state=b");

    expect(result.session.subject).toBe(USER.profile.sub);
    expect(window.sessionStorage.getItem(key)).toBeNull();
  });
});

describe("createOidcAuthClient sign-in", () => {
  it("clears prior transaction records before redirecting", async () => {
    const stale = `${OIDC_TRANSACTION_STORE_PREFIX}stale`;
    window.sessionStorage.setItem(stale, "old");
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);

    await client.signIn("/health");

    expect(window.sessionStorage.getItem(stale)).toBeNull();
    expect(manager.calls.signinRedirect).toHaveLength(1);
  });

  it("passes the return route as transaction state, never as url_state", async () => {
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);

    await client.signIn("/health");

    const args = manager.calls.signinRedirect[0];
    expect(args).toEqual({ state: { returnTo: "/health" } });
    expect(args).not.toHaveProperty("url_state");
  });

  it("fails closed without redirecting when storage cannot be cleared", async () => {
    const manager = createFakeUserManager();
    const client = clientFor(manager, hostileStorage("removeItem"));

    await expect(client.signIn("/")).rejects.toBeInstanceOf(AuthSignInError);
    expect(manager.calls.signinRedirect).toHaveLength(0);
  });

  it("does not leak a provider failure out of the port", async () => {
    const manager = createFakeUserManager();
    manager.signinRedirect = async () => {
      throw new Error("discovery failed at https://as.example/.well-known");
    };
    const client = clientFor(manager, window.sessionStorage);

    const error = await client.signIn("/").catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(AuthSignInError);
    expect((error as Error).message).not.toContain("as.example");
  });
});

describe("createOidcAuthClient callback", () => {
  it("returns only the subject, display name and untrusted return route", async () => {
    const client = clientFor(createFakeUserManager());

    const result = await client.completeSignIn("http://localhost/auth/callback?code=a&state=b");

    expect(result.session).toEqual({
      subject: "11111111-1111-4111-8111-111111111111",
      displayName: "Test Analyst",
    });
    expect(result.returnTo).toBe("/health");
    expect(JSON.stringify(result)).not.toMatch(/access_token|id_token|refresh_token/);
  });

  it("clears the transaction record exactly once on success", async () => {
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`, "value");
    window.sessionStorage.setItem("other-app.key", "keep");
    const client = clientFor(createFakeUserManager());

    await client.completeSignIn("http://localhost/auth/callback?code=a&state=b");

    expect(window.sessionStorage.getItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`)).toBeNull();
    expect(window.sessionStorage.getItem("other-app.key")).toBe("keep");
  });

  it("clears the transaction record when the provider rejects the response", async () => {
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`, "value");
    const manager = createFakeUserManager();
    manager.failCallback();
    const client = clientFor(manager, window.sessionStorage);

    await expect(
      client.completeSignIn("http://localhost/auth/callback?code=a&state=b"),
    ).rejects.toBeInstanceOf(AuthCallbackError);
    expect(window.sessionStorage.getItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`)).toBeNull();
  });

  it("does not leak the provider failure text", async () => {
    const manager = createFakeUserManager();
    manager.failCallback();
    const client = clientFor(manager, window.sessionStorage);

    const error = await client
      .completeSignIn("http://localhost/auth/callback?code=a&state=b")
      .catch((caught: unknown) => caught);

    expect((error as Error).message).not.toContain("SECRET_CODE");
    expect((error as Error).message).not.toContain("nonce");
  });

  it("abandons a validated sign-in when the transaction cannot be cleared", async () => {
    const manager = createFakeUserManager();
    const client = clientFor(manager, hostileStorage("removeItem"));

    await expect(
      client.completeSignIn("http://localhost/auth/callback?code=a&state=b"),
    ).rejects.toBeInstanceOf(AuthCallbackError);
    // The local user is discarded rather than published.
    expect(manager.calls.removeUser).toBe(1);
  });

  it("arms no deadline timer when cleanup fails after validation", async () => {
    vi.useFakeTimers();
    const manager = createFakeUserManager();
    const client = clientFor(manager, hostileStorage("removeItem"));
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);

    await client.completeSignIn("http://localhost/auth/callback?code=a").catch(() => undefined);
    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS + 1000);

    expect(invalidated).not.toHaveBeenCalled();
  });

  it("uses the display name only when the provider supplies a string", async () => {
    const manager = createFakeUserManager();
    manager.setUser({ profile: { sub: "sub-1" } });
    const client = clientFor(manager, window.sessionStorage);

    const result = await client.completeSignIn("http://localhost/auth/callback?code=a");

    expect(result.session.displayName).toBeUndefined();
  });

  it("reports an absent return route rather than inventing one", async () => {
    const manager = createFakeUserManager();
    manager.setUser({ profile: { sub: "sub-1" }, state: undefined });
    const client = clientFor(manager, window.sessionStorage);

    const result = await client.completeSignIn("http://localhost/auth/callback?code=a");

    expect(result.returnTo).toBeUndefined();
  });
});

describe("createOidcAuthClient session lifetime", () => {
  async function signedInClient(expiresAtSeconds?: number) {
    const manager = createFakeUserManager();
    manager.setUser({ ...USER, expires_at: expiresAtSeconds });
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    await client.completeSignIn("http://localhost/auth/callback?code=a&state=b");
    return { manager, client, invalidated };
  }

  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-04T00:00:00.000Z"));
  });

  it("keeps the session alive at 14 minutes 59.999 seconds", async () => {
    const { invalidated } = await signedInClient();

    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS - 1);

    expect(invalidated).not.toHaveBeenCalled();
  });

  it("invalidates the session at exactly 15 minutes", async () => {
    const { invalidated, manager } = await signedInClient();

    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS);

    expect(invalidated).toHaveBeenCalledTimes(1);
    expect(manager.calls.removeUser).toBe(1);
  });

  it("still invalidates at 15 minutes when the server grants an hour", async () => {
    const { invalidated } = await signedInClient(Date.now() / 1000 + 3600);

    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS - 1);
    expect(invalidated).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("honours a shorter token lifetime", async () => {
    const { invalidated } = await signedInClient(Date.now() / 1000 + 300);

    await vi.advanceTimersByTimeAsync(299_999);
    expect(invalidated).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("does not notify twice when the deadline fires after a shorter expiry", async () => {
    const { invalidated } = await signedInClient(Date.now() / 1000 + 300);

    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS + 1000);

    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("replaces the previous deadline when a new session starts", async () => {
    const { client, invalidated } = await signedInClient();

    await vi.advanceTimersByTimeAsync(600_000);
    await client.completeSignIn("http://localhost/auth/callback?code=b&state=c");
    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS - 1);

    expect(invalidated).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("does not extend the deadline when initialize runs again", async () => {
    const { client, invalidated } = await signedInClient();

    await vi.advanceTimersByTimeAsync(600_000);
    await client.initialize();
    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS - 600_000 - 1);
    expect(invalidated).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("clears the timer on logout so it cannot fire later", async () => {
    const { client, invalidated } = await signedInClient();

    await client.signOut();
    invalidated.mockClear();
    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS + 1000);

    expect(invalidated).not.toHaveBeenCalled();
  });

  it("does not leave an unhandled rejection when teardown fails on the deadline", async () => {
    const manager = createFakeUserManager();
    manager.setUser(USER);
    manager.failRemoveUser();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);

    await client.completeSignIn("http://localhost/auth/callback?code=a");
    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS);
    await vi.advanceTimersByTimeAsync(0);
    process.off("unhandledRejection", unhandled);

    expect(invalidated).toHaveBeenCalledTimes(1);
    expect(unhandled).not.toHaveBeenCalled();
  });
});

describe("createOidcAuthClient invalidation boundary", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-04T00:00:00.000Z"));
  });

  it("registers no listener, and touches no storage, until an operation runs", () => {
    const manager = createFakeUserManager();
    clientFor(manager, window.sessionStorage);

    expect(manager.expiredListenerCount()).toBe(0);
  });

  it("registers exactly one access token expiry listener for its lifetime", async () => {
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);

    await client.initialize();
    await client.initialize();
    await client.signIn("/");
    await client.completeSignIn("http://localhost/auth/callback?code=a");

    expect(manager.expiredListenerCount()).toBe(1);
  });

  it("caches no runtime when acquisition fails, so a later attempt retries", async () => {
    const manager = createFakeUserManager();
    let attempts = 0;
    const client = createOidcAuthClient(
      () => {
        attempts += 1;
        if (attempts === 1) {
          throw new AuthStorageUnavailableError();
        }
        return { userManager: manager, storage: window.sessionStorage };
      },
      { isCallbackRoute: () => false },
    );

    await expect(client.initialize()).rejects.toBeDefined();
    await expect(client.initialize()).resolves.toEqual({ session: null });
    expect(attempts).toBe(2);
    expect(manager.expiredListenerCount()).toBe(1);
  });

  it("converges token expiry onto the same local invalidation", async () => {
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    await client.completeSignIn("http://localhost/auth/callback?code=a");

    manager.emitAccessTokenExpired();

    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("ignores a second expiry event", async () => {
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    await client.completeSignIn("http://localhost/auth/callback?code=a");

    manager.emitAccessTokenExpired();
    manager.emitAccessTokenExpired();

    expect(invalidated).toHaveBeenCalledTimes(1);
    expect(manager.calls.removeUser).toBe(1);
  });

  it("ignores an expiry event that arrives after logout", async () => {
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    await client.completeSignIn("http://localhost/auth/callback?code=a");

    await client.signOut();
    expect(invalidated).toHaveBeenCalledTimes(1);

    invalidated.mockClear();
    const removeUserAfterLogout = manager.calls.removeUser;
    manager.emitAccessTokenExpired();

    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(removeUserAfterLogout);
  });

  it("notifies once when the deadline and an expiry event race", async () => {
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    await client.completeSignIn("http://localhost/auth/callback?code=a");

    const pending = vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS);
    manager.emitAccessTokenExpired();
    await pending;

    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("keeps the local state invalidated even when removeUser rejects", async () => {
    const manager = createFakeUserManager();
    manager.failRemoveUser();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    await client.completeSignIn("http://localhost/auth/callback?code=a");

    await client.signOut();

    expect(invalidated).toHaveBeenCalledTimes(1);
    // A second logout finds nothing left to invalidate.
    invalidated.mockClear();
    await client.signOut();
    expect(invalidated).not.toHaveBeenCalled();
  });

  it("stops notifying a listener that has unsubscribed", async () => {
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    const unsubscribe = client.onSessionInvalidated(invalidated);
    await client.completeSignIn("http://localhost/auth/callback?code=a");

    unsubscribe();
    manager.emitAccessTokenExpired();

    expect(invalidated).not.toHaveBeenCalled();
  });

  it("removes the local user and transaction records on logout", async () => {
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`, "value");
    window.sessionStorage.setItem("theme", "dark");
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);

    await client.signOut();

    expect(manager.calls.removeUser).toBe(1);
    expect(window.sessionStorage.getItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`)).toBeNull();
    expect(window.sessionStorage.getItem("theme")).toBe("dark");
  });
});

describe("createOidcAuthClient shared invalidation teardown", () => {
  async function signedIn() {
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    await client.completeSignIn("http://localhost/auth/callback?code=a&state=b");
    return { manager, client, invalidated };
  }

  it("shares one teardown when logout races a pending expiry teardown", async () => {
    const { manager, client, invalidated } = await signedIn();
    const gate = manager.deferRemoveUser();
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`, "value");

    // Expiry starts teardown; removeUser is now pending.
    manager.emitAccessTokenExpired();
    expect(manager.calls.removeUser).toBe(1);

    // Logout arrives while that teardown is still in flight.
    const loggedOut = client.signOut();
    gate.resolve();
    await loggedOut;

    expect(manager.calls.removeUser).toBe(1);
    expect(invalidated).toHaveBeenCalledTimes(1);
    expect(window.sessionStorage.getItem(`${OIDC_TRANSACTION_STORE_PREFIX}state`)).toBeNull();
  });

  it("hands concurrent callers the very same teardown promise", async () => {
    const { manager, client } = await signedIn();
    const gate = manager.deferRemoveUser();

    const first = client.signOut();
    const second = client.signOut();
    gate.resolve();
    await Promise.all([first, second]);

    expect(first).toBe(second);
    expect(manager.calls.removeUser).toBe(1);
  });

  it("tears down once when logout is followed by an expiry event", async () => {
    const { manager, client, invalidated } = await signedIn();

    await client.signOut();
    invalidated.mockClear();
    manager.emitAccessTokenExpired();

    expect(manager.calls.removeUser).toBe(1);
    expect(invalidated).not.toHaveBeenCalled();
  });

  it("tears down once when the deadline and an expiry event race", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-04T00:00:00.000Z"));
    const { manager, client, invalidated } = await signedIn();
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);

    const pending = vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS);
    manager.emitAccessTokenExpired();
    await pending;
    await vi.advanceTimersByTimeAsync(0);
    process.off("unhandledRejection", unhandled);

    expect(manager.calls.removeUser).toBe(1);
    expect(invalidated).toHaveBeenCalledTimes(1);
    expect(unhandled).not.toHaveBeenCalled();
    void client;
  });

  it("shares a failing teardown without surfacing the raw storage error", async () => {
    const { manager, client, invalidated } = await signedIn();
    manager.failRemoveUser();
    const gate = manager.deferRemoveUser();

    manager.emitAccessTokenExpired();
    const loggedOut = client.signOut();
    gate.resolve();

    await expect(loggedOut).resolves.toBeUndefined();
    expect(manager.calls.removeUser).toBe(1);
    expect(invalidated).toHaveBeenCalledTimes(1);
    // The failure did not restore the session.
    invalidated.mockClear();
    manager.emitAccessTokenExpired();
    expect(invalidated).not.toHaveBeenCalled();
  });

  it("releases the entry so a later session tears down again", async () => {
    const { manager, client } = await signedIn();

    await client.signOut();
    await client.completeSignIn("http://localhost/auth/callback?code=b&state=c");
    await client.signOut();

    expect(manager.calls.removeUser).toBe(2);
  });

  it("does not let a previous teardown remove the next session", async () => {
    const { manager, client, invalidated } = await signedIn();
    const gate = manager.deferRemoveUser();

    // Teardown is in flight when a fresh callback completes.
    manager.emitAccessTokenExpired();
    const completing = client.completeSignIn("http://localhost/auth/callback?code=b&state=c");
    gate.resolve();
    const result = await completing;

    // The new session was published after the old teardown finished, so its
    // removeUser() could not reach the record this callback wrote.
    expect(result.session.subject).toBe(USER.profile.sub);
    expect(manager.calls.removeUser).toBe(1);
    invalidated.mockClear();
    await client.signOut();
    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("tears nothing down when storage was never available", async () => {
    const client = createOidcAuthClient(
      () => {
        throw new AuthStorageUnavailableError();
      },
      { isCallbackRoute: () => false },
    );

    await expect(client.signOut()).resolves.toBeUndefined();
  });
});

describe("createOidcAuthClient teardown before a new transaction", () => {
  const B_KEY = OIDC_TRANSACTION_STORE_PREFIX + "b";

  /**
   * A sign-in that writes a real transaction record, the way the library does.
   * The Major this guards against is not an ordering detail: it is this exact
   * key being deleted by the previous session's sweep.
   */
  function writingSignIn(manager: FakeUserManager): void {
    manager.signinRedirect = async (args: { state: unknown }) => {
      manager.calls.signinRedirect.push(args);
      window.sessionStorage.setItem(B_KEY, JSON.stringify({ id: "b" }));
    };
  }

  async function signedInSessionA() {
    const manager = createFakeUserManager();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    await client.completeSignIn("http://localhost/auth/callback?code=a&state=a");
    return { manager, client, invalidated };
  }

  it("keeps the new sign-in transaction that a pending teardown would have swept", async () => {
    const { manager, client } = await signedInSessionA();
    writingSignIn(manager);
    window.sessionStorage.setItem(OIDC_TRANSACTION_STORE_PREFIX + "a", "session A");
    const gate = manager.deferRemoveUser();
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);

    // Session A expires; its teardown is now parked inside removeUser().
    manager.emitAccessTokenExpired();
    expect(manager.calls.removeUser).toBe(1);

    // Session B starts while that teardown is still pending.
    const signingIn = client.signIn("/health");
    await Promise.resolve();
    await Promise.resolve();

    // Nothing has been created yet: the redirect is still waiting on teardown.
    expect(manager.calls.signinRedirect).toHaveLength(0);
    expect(window.sessionStorage.getItem(B_KEY)).toBeNull();

    gate.resolve();
    await signingIn;

    // Teardown finished first, then B was written.
    expect(manager.calls.signinRedirect).toHaveLength(1);
    expect(window.sessionStorage.getItem(B_KEY)).toBe(JSON.stringify({ id: "b" }));
    // Session A's own record was swept on the way through.
    expect(window.sessionStorage.getItem(OIDC_TRANSACTION_STORE_PREFIX + "a")).toBeNull();

    // A late continuation of the old teardown must not reach B either.
    await new Promise((resolve) => setTimeout(resolve, 0));
    process.off("unhandledRejection", unhandled);
    expect(window.sessionStorage.getItem(B_KEY)).toBe(JSON.stringify({ id: "b" }));
    expect(unhandled).not.toHaveBeenCalled();
  });

  it("does not start the token exchange until a pending teardown has settled", async () => {
    const { manager, client } = await signedInSessionA();
    const gate = manager.deferRemoveUser();
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);

    // Session A already ran one protocol step; only new ones matter here.
    const callbacksBefore = manager.calls.signinRedirectCallback.length;

    manager.emitAccessTokenExpired();
    const completing = client.completeSignIn("http://localhost/auth/callback?code=b&state=b");
    await Promise.resolve();
    await Promise.resolve();

    expect(manager.calls.signinRedirectCallback).toHaveLength(callbacksBefore);

    gate.resolve();
    const result = await completing;
    await new Promise((resolve) => setTimeout(resolve, 0));
    process.off("unhandledRejection", unhandled);

    // Session B was installed after the old teardown, and survives it.
    expect(result.session.subject).toBe(USER.profile.sub);
    expect(manager.calls.signinRedirectCallback).toHaveLength(callbacksBefore + 1);
    // One removeUser for A; none of it reached B.
    expect(manager.calls.removeUser).toBe(1);
    expect(unhandled).not.toHaveBeenCalled();

    // B has a live deadline of its own, and tears down exactly once.
    const invalidatedB = vi.fn();
    client.onSessionInvalidated(invalidatedB);
    await client.signOut();
    expect(invalidatedB).toHaveBeenCalledTimes(1);
    expect(manager.calls.removeUser).toBe(2);
  });

  it("arms the hard deadline for the session installed after a teardown", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-09-04T00:00:00.000Z"));
    const { manager, client } = await signedInSessionA();
    const gate = manager.deferRemoveUser();
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);

    manager.emitAccessTokenExpired();
    const completing = client.completeSignIn("http://localhost/auth/callback?code=b&state=b");
    gate.resolve();
    await completing;
    invalidated.mockClear();

    await vi.advanceTimersByTimeAsync(SESSION_HARD_DEADLINE_MS - 1);
    expect(invalidated).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("lets the user sign in again after a teardown that failed outright", async () => {
    const { manager, client } = await signedInSessionA();
    writingSignIn(manager);
    manager.failRemoveUser();
    const gate = manager.deferRemoveUser();
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);

    manager.emitAccessTokenExpired();
    const signingIn = client.signIn("/");
    gate.resolve();

    // The failed teardown settles safely and does not become the caller's error.
    await expect(signingIn).resolves.toBeUndefined();
    await new Promise((resolve) => setTimeout(resolve, 0));
    process.off("unhandledRejection", unhandled);

    expect(manager.calls.signinRedirect).toHaveLength(1);
    expect(window.sessionStorage.getItem(B_KEY)).toBe(JSON.stringify({ id: "b" }));
    expect(unhandled).not.toHaveBeenCalled();
  });

  it("lets the user sign in again when the teardown sweep itself throws", async () => {
    const manager = createFakeUserManager();
    let sweepShouldThrow = true;
    const backing = window.sessionStorage;
    const flaky = {
      get length(): number {
        if (sweepShouldThrow) {
          throw new DOMException("blocked", "SecurityError");
        }
        return backing.length;
      },
      key: (index: number) => backing.key(index),
      getItem: (key: string) => backing.getItem(key),
      setItem: (key: string, value: string) => backing.setItem(key, value),
      removeItem: (key: string) => backing.removeItem(key),
      clear: () => backing.clear(),
    } as unknown as Storage;
    const client = clientFor(manager, flaky);
    sweepShouldThrow = false;
    await client.completeSignIn("http://localhost/auth/callback?code=a&state=a");
    writingSignIn(manager);
    const gate = manager.deferRemoveUser();
    const unhandled = vi.fn();
    process.on("unhandledRejection", unhandled);

    sweepShouldThrow = true;
    manager.emitAccessTokenExpired();
    gate.resolve();
    await new Promise((resolve) => setTimeout(resolve, 0));

    // The teardown swallowed its sweep failure; sign-in is available again.
    sweepShouldThrow = false;
    await expect(client.signIn("/")).resolves.toBeUndefined();
    process.off("unhandledRejection", unhandled);

    expect(manager.calls.signinRedirect).toHaveLength(1);
    expect(window.sessionStorage.getItem(B_KEY)).toBe(JSON.stringify({ id: "b" }));
    expect(unhandled).not.toHaveBeenCalled();
  });

  it("starts one redirect when the pending guard drives repeated sign-in clicks", async () => {
    const { manager, client } = await signedInSessionA();
    writingSignIn(manager);
    const gate = manager.deferRemoveUser();

    // Mirrors AuthProvider.signIn: one redirect in flight at a time.
    let signInPending = false;
    const clickSignIn = () => {
      if (signInPending) {
        return undefined;
      }
      signInPending = true;
      return client.signIn("/").catch(() => {
        signInPending = false;
      });
    };

    manager.emitAccessTokenExpired();
    const first = clickSignIn();
    clickSignIn();
    clickSignIn();
    await Promise.resolve();
    await Promise.resolve();

    expect(manager.calls.signinRedirect).toHaveLength(0);

    gate.resolve();
    await first;

    expect(manager.calls.signinRedirect).toHaveLength(1);
    const transactionKeys: string[] = [];
    for (let index = 0; index < window.sessionStorage.length; index += 1) {
      const key = window.sessionStorage.key(index);
      if (key !== null && key.startsWith(OIDC_TRANSACTION_STORE_PREFIX)) {
        transactionKeys.push(key);
      }
    }
    expect(transactionKeys).toEqual([B_KEY]);
    expect(manager.calls.removeUser).toBe(1);
  });
});

describe("createOidcAuthClient storage acquisition failure", () => {
  function unavailableClient(onCall?: () => void) {
    return createOidcAuthClient(
      () => {
        onCall?.();
        throw new AuthStorageUnavailableError();
      },
      { isCallbackRoute: () => false },
    );
  }

  it("refuses to redirect, with a fixed sign-in error", async () => {
    const client = unavailableClient();

    const error = await client.signIn("/").catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(AuthSignInError);
    expect((error as Error).message).not.toContain("SecurityError");
  });

  it("refuses to complete a callback, with a fixed callback error", async () => {
    const client = unavailableClient();

    const error = await client
      .completeSignIn("http://localhost/auth/callback?code=SECRET_CODE&state=SECRET_STATE")
      .catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(AuthCallbackError);
    expect((error as Error).message).not.toContain("SECRET_CODE");
    expect((error as Error).message).not.toContain("SECRET_STATE");
  });

  it("converts a raw SecurityError from the storage getter into a fixed error", async () => {
    const client = createOidcAuthClient(
      () => {
        throw new DOMException("blocked at https://embed.example", "SecurityError");
      },
      { isCallbackRoute: () => false },
    );

    const error = await client.signIn("/").catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(AuthSignInError);
    expect((error as Error).message).not.toContain("embed.example");
    expect((error as Error).name).not.toBe("SecurityError");
  });
});

describe("createOidcAuthClient conflicting authorization response", () => {
  it("still refuses to publish a session when the state store rejects", async () => {
    const manager = createFakeUserManager();
    manager.failCallback();
    const client = clientFor(manager, window.sessionStorage);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);

    await expect(
      client.completeSignIn("http://localhost/auth/callback?code=a&error=access_denied&state=b"),
    ).rejects.toBeInstanceOf(AuthCallbackError);
    expect(invalidated).not.toHaveBeenCalled();
  });
});

describe("createOidcAuthClient storage boundary", () => {
  it("writes no token to localStorage, sessionStorage or IndexedDB", async () => {
    const indexedDbOpen = vi.fn();
    vi.stubGlobal("indexedDB", { open: indexedDbOpen });
    const client = clientFor(createFakeUserManager());

    await client.initialize();
    await client.signIn("/health");
    await client.completeSignIn("http://localhost/auth/callback?code=a&state=b");

    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
    expect(indexedDbOpen).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });
});

const API_BASE_URL = "http://localhost:8080";
const TOKEN = "header.payload.signature";
const CASE_ID = "20000000-0000-4000-9000-000000000003";
const TRANSACTION_ID = "11111111-2222-4333-8444-555555555555";
/** Contains hex letters, so case sensitivity is actually observable. */
const LETTERED_CASE_ID = "6f1e0b6c-3a2b-4c8d-9e0f-1a2b3c4d5e6f";

function tokenUser(overrides: Partial<OidcUserLike> = {}): OidcUserLike {
  return { ...USER, access_token: TOKEN, ...overrides };
}

async function signedInClient(manager: FakeUserManager) {
  manager.setUser(tokenUser());
  const client = clientFor(manager);
  await client.completeSignIn("http://localhost/auth/callback?code=a&state=b");
  return client;
}

describe("createOidcAuthClient authorizeRequest — destination validation", () => {
  beforeEach(() => {
    vi.stubEnv("VITE_API_BASE_URL", API_BASE_URL);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  /**
   * Every destination below is handed straight to the real adapter, bypassing
   * the transport entirely. This capability holds the credential, so it has to
   * refuse on its own rather than assume someone checked first. A refusal must
   * happen before the user store is read: no token lookup, no header, no
   * returned request.
   */
  const FORBIDDEN_DESTINATIONS: ReadonlyArray<{ url: string; method?: string }> = [
    { url: "https://evil.example/collect" },
    { url: "https://evil.example/api/v1/cases" },
    { url: "http://localhost.evil.example:8080/api/v1/cases" },
    { url: "http://evil.localhost:8080/api/v1/cases" },
    { url: "http://localhost:9090/api/v1/cases" },
    { url: "https://localhost:8080/api/v1/cases" },
    // public health: credential-free by contract
    { url: "http://localhost:8080/api/health" },
    // SERVICE ingestion
    { url: "http://localhost:8080/api/v1/transactions", method: "POST" },
    { url: "http://localhost:8080/api/v1/behavior-events", method: "POST" },
    // management listener and actuator
    { url: "http://localhost:8081/actuator/health" },
    { url: "http://localhost:8081/actuator/prometheus" },
    { url: "http://localhost:8080/actuator/health" },
    // AI service and observability
    { url: "http://localhost:8000/api/v1/scoring", method: "POST" },
    { url: "http://prometheus:9090/api/v1/query" },
    { url: "http://grafana:3000/api/dashboards" },
    { url: "http://alertmanager:9093/api/v2/alerts" },
    { url: "https://risk-provider.example/score", method: "POST" },
    // approved path, wrong shape
    { url: `http://localhost:8080/api/v1/cases/${CASE_ID}/` },
    { url: "http://localhost:8080/api/v1/cases/" },
    { url: "http://localhost:8080/api/v1/cases?page=0" },
    { url: "http://localhost:8080/api/v1/cases#top" },
    // approved path, wrong method
    { url: "http://localhost:8080/api/v1/cases", method: "POST" },
    { url: "http://localhost:8080/api/v1/cases", method: "DELETE" },
    { url: `http://localhost:8080/api/v1/cases/${CASE_ID}/status`, method: "POST" },
    { url: `http://localhost:8080/api/v1/cases/${CASE_ID}/notes`, method: "PATCH" },
    // encoded and traversal forms
    { url: "http://localhost:8080/api/v1/cases/%2e%2e/actuator" },
    { url: `http://localhost:8080/api/v1/cases/${CASE_ID}%2Fnotes` },
    { url: `http://localhost:8080/api/v1/cases/${CASE_ID}%25` },
    { url: `http://localhost:8080/api/v1/cases/${CASE_ID};jsessionid=abc` },
    { url: `http://localhost:8080/api/v1/cases/${LETTERED_CASE_ID.toUpperCase()}` },
    { url: "http://localhost:8080/api/v1/cases/not-a-uuid" },
    { url: "http://localhost:8080/api/v1/unknown" },
  ];

  it("refuses every forbidden destination without reading the user store", async () => {
    for (const destination of FORBIDDEN_DESTINATIONS) {
      const manager = createFakeUserManager();
      const client = await signedInClient(manager);
      const getUserBefore = manager.calls.getUser;
      const original = new Request(destination.url, { method: destination.method ?? "GET" });

      const authorized = await client.authorizeRequest(original);

      expect(authorized, `expected refusal for ${destination.method ?? "GET"} ${destination.url}`)
        .toBeNull();
      expect(manager.calls.getUser).toBe(getUserBefore);
      expect(original.headers.get("Authorization")).toBeNull();
    }
  });

  /**
   * A URL carrying userinfo cannot even become a `Request`, so this capability
   * never sees one. Recorded here so the absence of such a case from the list
   * above is a platform guarantee rather than an oversight.
   */
  it("cannot be handed a request whose URL carries userinfo", () => {
    for (const url of [
      "http://user:pass@localhost:8080/api/v1/cases",
      "http://localhost:8080@evil.example/api/v1/cases",
    ]) {
      expect(() => new Request(url)).toThrow(TypeError);
    }
  });

  it("authorizes every approved USER endpoint and method", async () => {
    const approved: ReadonlyArray<[string, string]> = [
      ["GET", "/api/v1/transactions"],
      ["GET", `/api/v1/transactions/${TRANSACTION_ID}`],
      ["GET", "/api/v1/cases"],
      ["GET", `/api/v1/cases/${CASE_ID}`],
      ["GET", `/api/v1/cases/${CASE_ID}/notes`],
      ["GET", `/api/v1/cases/${CASE_ID}/audit-logs`],
      ["PATCH", `/api/v1/cases/${CASE_ID}/status`],
      ["PATCH", `/api/v1/cases/${CASE_ID}/assignee`],
      ["POST", `/api/v1/cases/${CASE_ID}/resolution`],
      ["POST", `/api/v1/cases/${CASE_ID}/notes`],
    ];

    for (const [method, path] of approved) {
      const manager = createFakeUserManager();
      const client = await signedInClient(manager);
      const request = new Request(`${API_BASE_URL}${path}`, {
        method,
        body: method === "GET" ? undefined : "{}",
      });

      const authorized = await client.authorizeRequest(request);

      expect(authorized, `expected authorization for ${method} ${path}`).not.toBeNull();
      expect(authorized?.request.headers.get("Authorization")).toBe(`Bearer ${TOKEN}`);
    }
  });

  /**
   * The Backend base URL is memoized on first read, so this needs a fresh
   * module registry to observe an unusable configuration at all. Fail closed:
   * without a base URL there is no destination to approve, so nothing is signed.
   */
  it("refuses everything when the Backend base URL cannot be resolved", async () => {
    vi.resetModules();
    vi.stubEnv("VITE_API_BASE_URL", "not a url");
    const isolated = await import("./oidcAuthClient");

    const manager = createFakeUserManager();
    manager.setUser(tokenUser());
    const client = isolated.createOidcAuthClient(
      () => ({ userManager: manager, storage: window.sessionStorage }),
      { isCallbackRoute: () => false },
    );
    await client.completeSignIn("http://localhost/auth/callback?code=a&state=b");
    const getUserBefore = manager.calls.getUser;

    await expect(
      client.authorizeRequest(new Request(`${API_BASE_URL}/api/v1/cases`)),
    ).resolves.toBeNull();
    expect(manager.calls.getUser).toBe(getUserBefore);

    vi.resetModules();
  });
});

describe("createOidcAuthClient authorizeRequest — credential shape", () => {
  const REQUEST_URL = `${API_BASE_URL}/api/v1/cases`;

  beforeEach(() => {
    vi.stubEnv("VITE_API_BASE_URL", API_BASE_URL);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it("returns a new request carrying exactly one Bearer credential", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const original = new Request(REQUEST_URL);

    const authorized = await client.authorizeRequest(original);

    expect(authorized).not.toBeNull();
    expect(authorized?.request).not.toBe(original);
    expect(authorized?.request.headers.get("Authorization")).toBe(`Bearer ${TOKEN}`);
    const credentials = [...(authorized?.request.headers ?? [])].filter(
      ([name]) => name === "authorization",
    );
    expect(credentials).toHaveLength(1);
  });

  it("leaves the caller's request without a credential", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const original = new Request(REQUEST_URL, { headers: { Accept: "application/json" } });

    await client.authorizeRequest(original);

    expect(original.headers.get("Authorization")).toBeNull();
    expect(original.headers.get("Accept")).toBe("application/json");
  });

  it("preserves the URL, method, body and transport options", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const writeUrl = `${API_BASE_URL}/api/v1/cases/${CASE_ID}/notes`;
    const original = new Request(writeUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ content: "note" }),
      credentials: "omit",
      redirect: "error",
    });

    const authorized = await client.authorizeRequest(original);

    expect(authorized?.request.url).toBe(writeUrl);
    expect(authorized?.request.method).toBe("POST");
    expect(authorized?.request.headers.get("Content-Type")).toBe("application/json");
    expect(authorized?.request.credentials).toBe("omit");
    expect(authorized?.request.redirect).toBe("error");
    await expect(authorized?.request.text()).resolves.toBe(JSON.stringify({ content: "note" }));
  });

  it("replaces rather than merges a credential the caller already set", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const original = new Request(REQUEST_URL, {
      headers: { Authorization: "Bearer attacker.supplied.value" },
    });

    const authorized = await client.authorizeRequest(original);

    expect(authorized?.request.headers.get("Authorization")).toBe(`Bearer ${TOKEN}`);
    expect(authorized?.request.headers.get("Authorization")).not.toContain("attacker");
  });

  it("puts the token nowhere but the header", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);

    const authorized = await client.authorizeRequest(new Request(REQUEST_URL));

    expect(authorized?.request.url).toBe(REQUEST_URL);
    expect(authorized?.request.url).not.toContain(TOKEN);
    expect(new URL(authorized?.request.url ?? REQUEST_URL).search).toBe("");
    expect(window.localStorage.length).toBe(0);
    expect(JSON.stringify(Object.keys(window.sessionStorage))).not.toContain(TOKEN);
  });

  it("returns null before sign-in and after logout", async () => {
    const manager = createFakeUserManager();
    manager.setUser(tokenUser());
    const client = clientFor(manager);

    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.toBeNull();

    await client.completeSignIn("http://localhost/auth/callback?code=a&state=b");
    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.not.toBeNull();

    await client.signOut();
    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.toBeNull();
  });

  it("returns null when the memory user store is empty", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    manager.setStoredUser(null);

    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.toBeNull();
  });

  it("returns null when the user store read throws, exposing no provider error", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    manager.failGetUser();

    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.toBeNull();
  });

  it("returns null for a missing or empty access token", async () => {
    for (const token of [undefined, ""]) {
      const manager = createFakeUserManager();
      const client = await signedInClient(manager);
      manager.setStoredUser({ ...USER, access_token: token });

      await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.toBeNull();
    }
  });

  it("returns null for a token whose expiry has passed", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    manager.setStoredUser(tokenUser({ expires_at: Math.floor(Date.now() / 1000) - 1 }));

    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.toBeNull();
  });

  it("authorizes a token whose expiry is still ahead", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    manager.setStoredUser(tokenUser({ expires_at: Math.floor(Date.now() / 1000) + 300 }));

    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.not.toBeNull();
  });

  it("returns null when the memory user disagrees with the published session", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    manager.setStoredUser({
      profile: { sub: "99999999-9999-4999-8999-999999999999" },
      access_token: TOKEN,
    });

    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.toBeNull();
  });

  it("returns null when the session ends while the store read is pending", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);

    const pending = client.authorizeRequest(new Request(REQUEST_URL));
    await client.signOut();

    await expect(pending).resolves.toBeNull();
  });

  /**
   * Case E: a whole new session is published while the store read is in
   * flight. The credential must not be issued at all — not for A, whose
   * session is gone, and not for B, which never asked for it.
   */
  it("returns null when a new session is published while the store read is pending", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const release = manager.deferGetUser();

    const pending = client.authorizeRequest(new Request(REQUEST_URL));
    await client.completeSignIn("http://localhost/auth/callback?code=b2&state=b2");
    release();

    await expect(pending).resolves.toBeNull();
  });

  it("exposes no token accessor on the port surface", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);

    for (const forbidden of [
      "getAccessToken",
      "getToken",
      "accessToken",
      "token",
      "getUser",
      "invalidateSession",
      "ownership",
    ]) {
      expect(Object.keys(client)).not.toContain(forbidden);
      expect((client as unknown as Record<string, unknown>)[forbidden]).toBeUndefined();
    }
  });

  it("hands out no session identity, only a callback", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);

    const authorized = await client.authorizeRequest(new Request(REQUEST_URL));

    expect(Object.keys(authorized ?? {}).sort()).toEqual(["invalidateIfCurrent", "request"]);
    expect(JSON.stringify(Object.keys(authorized ?? {}))).not.toContain(TOKEN);
  });
});

/**
 * The token exactly as the Authorization Server issued it.
 *
 * Every case here starts from a real OIDC user in the store and runs the
 * production adapter, because the defect this guards against is invisible once
 * `Headers.set` has normalized the value: a padded token becomes a well-formed
 * credential on the way into a header, so only the raw value can prove the
 * grammar was actually applied.
 */
describe("createOidcAuthClient authorizeRequest — raw access token grammar", () => {
  const REQUEST_URL = `${API_BASE_URL}/api/v1/cases`;

  beforeEach(() => {
    vi.stubEnv("VITE_API_BASE_URL", API_BASE_URL);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
    vi.restoreAllMocks();
  });

  it("authorizes every raw token the b64token grammar allows, verbatim", async () => {
    for (const token of ["opaque.token", "abc", "abc.def", "abc-._~+/", "abc=", "abc=="]) {
      const manager = createFakeUserManager();
      const client = await signedInClient(manager);
      manager.setStoredUser(tokenUser({ access_token: token }));

      const authorized = await client.authorizeRequest(new Request(REQUEST_URL));

      expect(authorized, `expected ${JSON.stringify(token)} to be authorized`).not.toBeNull();
      expect(authorized?.request.headers.get("Authorization")).toBe(`Bearer ${token}`);
    }
  });

  /**
   * Whitespace in every position the platform would have hidden, plus a `=`
   * outside the padding and the empty string. None of them may reach a header.
   */
  it("refuses every malformed raw token without building a header", async () => {
    for (const token of [
      "opaque.token ",
      " opaque.token",
      "opaque token",
      "opaque.token\t",
      "opaque.token\r",
      "opaque.token\n",
      "abc=def",
      "=abc",
      "",
    ]) {
      const label = JSON.stringify(token);
      const manager = createFakeUserManager();
      const client = await signedInClient(manager);
      const invalidated = vi.fn();
      client.onSessionInvalidated(invalidated);
      manager.setStoredUser(tokenUser({ access_token: token }));
      const removeUserBefore = manager.calls.removeUser;
      const getUserBefore = manager.calls.getUser;

      const original = new Request(REQUEST_URL, { headers: { Accept: "application/json" } });
      const setHeader = vi.spyOn(Headers.prototype, "set");
      const outcome = await client.authorizeRequest(original).then(
        (value) => value,
        (error: unknown) => error,
      );
      const credentialWrites = setHeader.mock.calls.filter(
        ([name]) => String(name).toLowerCase() === "authorization",
      );
      setHeader.mockRestore();

      // Refused, and refused by returning nothing rather than by throwing, so
      // no message exists that could carry the token.
      expect(outcome, `expected ${label} to be refused`).toBeNull();
      expect(credentialWrites, `expected no header for ${label}`).toHaveLength(0);

      // The caller's own request is untouched.
      expect(original.headers.get("Authorization")).toBeNull();
      expect(original.headers.get("Accept")).toBe("application/json");
      expect(original.url).toBe(REQUEST_URL);
      expect(original.method).toBe("GET");

      // Not a 401: nothing is invalidated, nobody is notified, nothing is torn
      // down, and the single store read is not repeated.
      expect(invalidated).not.toHaveBeenCalled();
      expect(manager.calls.removeUser).toBe(removeUserBefore);
      expect(manager.calls.getUser).toBe(getUserBefore + 1);
    }
  });

  it("keeps a malformed raw token out of every observable surface", async () => {
    const MALFORMED = "unlikely.sentinel.value ";
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    manager.setStoredUser(tokenUser({ access_token: MALFORMED }));
    const consoleSpies = (["error", "warn", "log", "info", "debug"] as const).map((level) =>
      vi.spyOn(console, level).mockImplementation(() => undefined),
    );

    const outcome = await client.authorizeRequest(new Request(REQUEST_URL)).then(
      (value) => value,
      (error: unknown) => error,
    );

    expect(outcome).toBeNull();
    for (const spy of consoleSpies) {
      expect(spy).not.toHaveBeenCalled();
    }
    const stored = [window.sessionStorage, window.localStorage]
      .flatMap((store) => Object.entries({ ...store }))
      .join("|");
    expect(stored).not.toContain("unlikely.sentinel.value");
    expect(document.body.innerHTML).not.toContain("unlikely.sentinel.value");
  });

  /**
   * The premise of the whole grammar check, asserted rather than assumed: a
   * padded token really does arrive at a header already trimmed, so a check
   * that only ever sees the finished header cannot tell the two apart.
   */
  it("proves the platform would have hidden the padding", () => {
    const headers = new Headers();
    headers.set("Authorization", "Bearer opaque.token ");

    expect(headers.get("Authorization")).toBe("Bearer opaque.token");
  });

  it("still refuses a malformed token that the session is otherwise ready for", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    manager.setStoredUser(
      tokenUser({ access_token: "opaque.token ", expires_at: Math.floor(Date.now() / 1000) + 300 }),
    );

    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.toBeNull();

    // And the session is still live: the refusal was about this token only.
    manager.setStoredUser(tokenUser());
    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.not.toBeNull();
  });
});

describe("createOidcAuthClient invalidateIfCurrent — session ownership", () => {
  const REQUEST_URL = `${API_BASE_URL}/api/v1/cases`;

  beforeEach(() => {
    vi.stubEnv("VITE_API_BASE_URL", API_BASE_URL);
  });

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  async function authorizeOnce(client: Awaited<ReturnType<typeof signedInClient>>) {
    const authorized = await client.authorizeRequest(new Request(REQUEST_URL));
    if (authorized === null) {
      throw new Error("expected an authorized request");
    }
    return authorized;
  }

  it("invalidates the issuing session once and notifies subscribers once", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const authorized = await authorizeOnce(client);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);

    authorized.invalidateIfCurrent();

    expect(invalidated).toHaveBeenCalledTimes(1);
  });

  it("is idempotent when the same callback is invoked repeatedly", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const authorized = await authorizeOnce(client);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const removeUserBefore = manager.calls.removeUser;

    authorized.invalidateIfCurrent();
    authorized.invalidateIfCurrent();
    authorized.invalidateIfCurrent();
    await Promise.resolve();
    await Promise.resolve();

    expect(invalidated).toHaveBeenCalledTimes(1);
    expect(manager.calls.removeUser - removeUserBefore).toBe(1);
  });

  /** Case A at the port: three requests from one session, one teardown. */
  it("collapses concurrent callbacks from one session into a single teardown", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const first = await authorizeOnce(client);
    const second = await authorizeOnce(client);
    const third = await authorizeOnce(client);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const removeUserBefore = manager.calls.removeUser;

    first.invalidateIfCurrent();
    second.invalidateIfCurrent();
    third.invalidateIfCurrent();
    await Promise.resolve();
    await Promise.resolve();

    expect(invalidated).toHaveBeenCalledTimes(1);
    expect(manager.calls.removeUser - removeUserBefore).toBe(1);
  });

  /** Case B at the port: session A's late verdict must not touch session B. */
  it("does nothing when a newer session has since been published", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const staleAuthorization = await authorizeOnce(client);

    await client.completeSignIn("http://localhost/auth/callback?code=b2&state=b2");
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const removeUserBefore = manager.calls.removeUser;

    staleAuthorization.invalidateIfCurrent();
    await Promise.resolve();

    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(removeUserBefore);
    // The new session is still usable.
    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.not.toBeNull();
  });

  /** Case C at the port. */
  it("does nothing after the user has already signed out", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const authorized = await authorizeOnce(client);
    await client.signOut();
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const removeUserBefore = manager.calls.removeUser;

    authorized.invalidateIfCurrent();
    await Promise.resolve();

    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(removeUserBefore);
  });

  /** Case D at the port: expiry already ended the session. */
  it("does nothing after token expiry has already invalidated the session", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const authorized = await authorizeOnce(client);
    manager.emitAccessTokenExpired();
    await Promise.resolve();

    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const removeUserBefore = manager.calls.removeUser;

    authorized.invalidateIfCurrent();
    await Promise.resolve();

    expect(invalidated).not.toHaveBeenCalled();
    expect(manager.calls.removeUser).toBe(removeUserBefore);
  });

  it("stops authorizing requests immediately, without waiting for teardown", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const authorized = await authorizeOnce(client);
    manager.deferRemoveUser();

    authorized.invalidateIfCurrent();

    await expect(client.authorizeRequest(new Request(REQUEST_URL))).resolves.toBeNull();
  });

  it("shares one teardown with a logout that races it", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const authorized = await authorizeOnce(client);
    const invalidated = vi.fn();
    client.onSessionInvalidated(invalidated);
    const removeUserBefore = manager.calls.removeUser;

    authorized.invalidateIfCurrent();
    await client.signOut();

    expect(invalidated).toHaveBeenCalledTimes(1);
    expect(manager.calls.removeUser - removeUserBefore).toBe(1);
  });

  /**
   * Case F: the new sign-in must still wait for the previous teardown, so the
   * transaction record and memory user it installs survive that sweep.
   */
  it("keeps a new sign-in ordered after the previous session's teardown", async () => {
    const manager = createFakeUserManager();
    const client = await signedInClient(manager);
    const authorized = await authorizeOnce(client);
    const gate = manager.deferRemoveUser();

    authorized.invalidateIfCurrent();
    const signingIn = client.signIn("/health");
    let started = false;
    void signingIn.then(() => {
      started = true;
    });
    await Promise.resolve();
    expect(started).toBe(false);
    expect(manager.calls.signinRedirect).toHaveLength(0);

    gate.resolve();
    await signingIn;

    expect(manager.calls.signinRedirect).toHaveLength(1);
  });
});
