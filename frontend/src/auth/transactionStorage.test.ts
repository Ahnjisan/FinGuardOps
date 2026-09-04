import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  acquireTransactionStorage,
  AuthStorageUnavailableError,
  clearAuthTransactionState,
  OIDC_TRANSACTION_STORE_PREFIX,
  OIDC_USER_STORE_PREFIX,
} from "./transactionStorage";

beforeEach(() => {
  window.sessionStorage.clear();
  window.localStorage.clear();
});

afterEach(() => {
  vi.restoreAllMocks();
  window.sessionStorage.clear();
  window.localStorage.clear();
});

/** Replaces the `sessionStorage` property getter, which is what actually throws. */
function stubSessionStorageGetter(get: () => Storage | null): void {
  vi.spyOn(window, "sessionStorage", "get").mockImplementation(
    get as unknown as () => Storage,
  );
}

describe("acquireTransactionStorage", () => {
  it("returns the real session storage when the property is readable", () => {
    expect(acquireTransactionStorage()).toBe(window.sessionStorage);
  });

  it("converts a throwing property getter into a fixed error", () => {
    stubSessionStorageGetter(() => {
      throw new DOMException("blocked at https://embed.example", "SecurityError");
    });

    expect(() => acquireTransactionStorage()).toThrow(AuthStorageUnavailableError);
  });

  it("does not carry the raw DOMException message, name or stack", () => {
    stubSessionStorageGetter(() => {
      throw new DOMException("blocked at https://embed.example", "SecurityError");
    });

    const error = (() => {
      try {
        acquireTransactionStorage();
        return null;
      } catch (caught) {
        return caught as Error;
      }
    })();

    expect(error).toBeInstanceOf(AuthStorageUnavailableError);
    expect(error?.name).toBe("AuthStorageUnavailableError");
    expect(error?.message).not.toContain("embed.example");
    expect(error?.message).not.toContain("SecurityError");
    expect(error?.stack ?? "").not.toContain("embed.example");
    expect((error as { cause?: unknown } | null)?.cause).toBeUndefined();
  });

  it("treats a null property as unavailable rather than usable", () => {
    stubSessionStorageGetter(() => null);

    expect(() => acquireTransactionStorage()).toThrow(AuthStorageUnavailableError);
  });
});

const JWT_SHAPED = /^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/;

describe("transaction store prefixes", () => {
  it("keeps the user store and transaction store prefixes distinct", () => {
    expect(OIDC_USER_STORE_PREFIX).toBe("finguardops.oidc.user.");
    expect(OIDC_TRANSACTION_STORE_PREFIX).toBe("finguardops.oidc.transaction.");
    expect(OIDC_USER_STORE_PREFIX.startsWith(OIDC_TRANSACTION_STORE_PREFIX)).toBe(false);
    expect(OIDC_TRANSACTION_STORE_PREFIX.startsWith(OIDC_USER_STORE_PREFIX)).toBe(false);
  });
});

describe("clearAuthTransactionState", () => {
  it("removes every transaction record", () => {
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-a`, "a");
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-b`, "b");
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-c`, "c");

    clearAuthTransactionState(window.sessionStorage);

    expect(window.sessionStorage.length).toBe(0);
  });

  it("does not remove keys under the user store prefix", () => {
    window.sessionStorage.setItem(`${OIDC_USER_STORE_PREFIX}anything`, "keep");
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-a`, "drop");

    clearAuthTransactionState(window.sessionStorage);

    expect(window.sessionStorage.getItem(`${OIDC_USER_STORE_PREFIX}anything`)).toBe("keep");
    expect(window.sessionStorage.getItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-a`)).toBeNull();
  });

  it("does not remove keys owned by other applications", () => {
    window.sessionStorage.setItem("other-app.session", "keep");
    window.sessionStorage.setItem("theme", "dark");
    window.sessionStorage.setItem("finguardops.other", "keep");
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-a`, "drop");

    clearAuthTransactionState(window.sessionStorage);

    expect(window.sessionStorage.getItem("other-app.session")).toBe("keep");
    expect(window.sessionStorage.getItem("theme")).toBe("dark");
    expect(window.sessionStorage.getItem("finguardops.other")).toBe("keep");
    expect(window.sessionStorage.length).toBe(3);
  });

  it("removes every record in one pass despite index shifting", () => {
    for (let index = 0; index < 5; index += 1) {
      window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-${index}`, String(index));
    }
    window.sessionStorage.setItem("unrelated", "keep");

    clearAuthTransactionState(window.sessionStorage);

    expect(window.sessionStorage.length).toBe(1);
    expect(window.sessionStorage.getItem("unrelated")).toBe("keep");
  });

  it("leaves localStorage untouched", () => {
    window.localStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-a`, "local");
    window.sessionStorage.setItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-a`, "session");

    clearAuthTransactionState(window.sessionStorage);

    expect(window.localStorage.length).toBe(1);
    expect(window.localStorage.getItem(`${OIDC_TRANSACTION_STORE_PREFIX}state-a`)).toBe("local");
  });

  it("does not leave a JWT-shaped value behind", () => {
    window.sessionStorage.setItem(
      `${OIDC_TRANSACTION_STORE_PREFIX}state-a`,
      "aaaa.bbbb.cccc",
    );

    clearAuthTransactionState(window.sessionStorage);

    for (let index = 0; index < window.sessionStorage.length; index += 1) {
      const key = window.sessionStorage.key(index);
      const value = key === null ? "" : (window.sessionStorage.getItem(key) ?? "");
      expect(JWT_SHAPED.test(value)).toBe(false);
    }
  });

  it("is a no-op on empty storage", () => {
    expect(() => {
      clearAuthTransactionState(window.sessionStorage);
    }).not.toThrow();
    expect(window.sessionStorage.length).toBe(0);
  });

  it("propagates a storage failure so callers can fail closed", () => {
    const hostileStorage = {
      length: 1,
      key: () => `${OIDC_TRANSACTION_STORE_PREFIX}state-a`,
      getItem: () => null,
      setItem: () => undefined,
      clear: () => undefined,
      removeItem: () => {
        throw new DOMException("access denied", "SecurityError");
      },
    } as unknown as Storage;

    expect(() => {
      clearAuthTransactionState(hostileStorage);
    }).toThrow();
  });
});
