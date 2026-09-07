import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  acquireTransactionStorage,
  AuthStorageUnavailableError,
  clearAuthTransactionState,
  isApprovedTransactionRecord,
  LOGOUT_TRANSACTION_DATA,
  OIDC_LOGIN_REQUEST_TYPE,
  OIDC_LOGOUT_REQUEST_TYPE,
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

const STATE_ID = "9f6d2b1a-1c2d-4e3f-8a9b-0c1d2e3f4a5b";

/**
 * The exact record `SigninState.toStorageString()` writes for the redirect
 * sign-in this client performs, pinned to oidc-client-ts 3.5.0. Tests vary one
 * field at a time from here, so a rejection is always about that field.
 */
function loginRecord(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: STATE_ID,
    data: { returnTo: "/health" },
    created: 1_770_000_000,
    request_type: OIDC_LOGIN_REQUEST_TYPE,
    code_verifier: "yE3Vk1s0Q7yq7yQ0v0kZ8fXyH2mZ0Q9d4o1bQe2wUvA",
    authority: "https://as.example/realms/finguardops",
    client_id: "finguardops-frontend",
    redirect_uri: "http://localhost:5173/auth/callback",
    scope: "openid profile",
    extraTokenParams: {},
    nonce: "Jz1nQyF6iJ8H3lPq2oXw0aZbC4dE5fG6hI7jK8lM9nO",
    ...overrides,
  };
}

/** The exact record `State.toStorageString()` writes for a signout request. */
function logoutRecord(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: STATE_ID,
    data: { ...LOGOUT_TRANSACTION_DATA },
    created: 1_770_000_000,
    request_type: OIDC_LOGOUT_REQUEST_TYPE,
    ...overrides,
  };
}

function approve(record: Record<string, unknown>, key: string = STATE_ID): boolean {
  return isApprovedTransactionRecord(key, JSON.stringify(record));
}

/** Drops a field, which `JSON.stringify` also does for an undefined value. */
function without(record: Record<string, unknown>, field: string): Record<string, unknown> {
  const copy = { ...record };
  delete copy[field];
  return copy;
}

describe("transaction request types", () => {
  it("names exactly the two redirect flows this client runs", () => {
    expect(OIDC_LOGIN_REQUEST_TYPE).toBe("si:r");
    expect(OIDC_LOGOUT_REQUEST_TYPE).toBe("so:r");
  });

  it("carries no session-specific application payload through logout", () => {
    expect(Object.keys(LOGOUT_TRANSACTION_DATA)).toEqual(["kind"]);
    expect(LOGOUT_TRANSACTION_DATA.kind).toBe("sign-out");
    expect(Object.isFrozen(LOGOUT_TRANSACTION_DATA)).toBe(true);
  });
});

describe("isApprovedTransactionRecord — shared rules", () => {
  it("accepts the exact record each flow writes", () => {
    expect(approve(loginRecord())).toBe(true);
    expect(approve(logoutRecord())).toBe(true);
  });

  it("refuses a record that is not parseable JSON", () => {
    expect(isApprovedTransactionRecord(STATE_ID, "")).toBe(false);
    expect(isApprovedTransactionRecord(STATE_ID, "{")).toBe(false);
    expect(isApprovedTransactionRecord(STATE_ID, "not json")).toBe(false);
  });

  it("refuses a record that is not a plain object", () => {
    for (const value of ["null", "42", '"text"', "[]", '[{"id":"x"}]', "true"]) {
      expect(isApprovedTransactionRecord(STATE_ID, value)).toBe(false);
    }
  });

  it("refuses a record whose id disagrees with its storage key", () => {
    expect(approve(loginRecord(), "another-state")).toBe(false);
    expect(approve(logoutRecord(), "another-state")).toBe(false);
    expect(approve(loginRecord({ id: "other" }))).toBe(false);
  });

  it("refuses a missing, blank or non-string id", () => {
    expect(approve(without(loginRecord(), "id"))).toBe(false);
    expect(isApprovedTransactionRecord("", JSON.stringify(loginRecord({ id: "" })))).toBe(false);
    expect(isApprovedTransactionRecord("  ", JSON.stringify(loginRecord({ id: "  " })))).toBe(
      false,
    );
    expect(approve(loginRecord({ id: 1 }))).toBe(false);
  });

  it("refuses a missing or unusable created stamp", () => {
    expect(approve(without(loginRecord(), "created"))).toBe(false);
    expect(approve(loginRecord({ created: "1770000000" }))).toBe(false);
    expect(approve(loginRecord({ created: 0 }))).toBe(false);
    expect(approve(loginRecord({ created: -1 }))).toBe(false);
    expect(approve(logoutRecord({ created: null }))).toBe(false);
  });

  it("refuses every request type this client does not run", () => {
    for (const requestType of ["si:s", "si:p", "so:p", "so:s", "", "SI:R", "si:r ", null, 1]) {
      expect(approve(loginRecord({ request_type: requestType }))).toBe(false);
    }
    expect(approve(without(loginRecord(), "request_type"))).toBe(false);
  });

  it("refuses a smuggled prototype key", () => {
    expect(
      isApprovedTransactionRecord(
        STATE_ID,
        JSON.stringify(loginRecord()).replace(/^\{/u, '{"__proto__":{"admin":true},'),
      ),
    ).toBe(false);
  });
});

describe("isApprovedTransactionRecord — sign-in schema", () => {
  it("keeps the nonblank nonce contract", () => {
    expect(approve(without(loginRecord(), "nonce"))).toBe(false);
    expect(approve(loginRecord({ nonce: "" }))).toBe(false);
    expect(approve(loginRecord({ nonce: "   " }))).toBe(false);
    expect(approve(loginRecord({ nonce: 12345 }))).toBe(false);
    expect(approve(loginRecord({ nonce: null }))).toBe(false);
  });

  it("keeps the PKCE verifier contract", () => {
    expect(approve(without(loginRecord(), "code_verifier"))).toBe(false);
    expect(approve(loginRecord({ code_verifier: "" }))).toBe(false);
    expect(approve(loginRecord({ code_verifier: " " }))).toBe(false);
    expect(approve(loginRecord({ code_verifier: { length: 43 } }))).toBe(false);
  });

  it("requires the authority, client, redirect URI and scope it was written with", () => {
    for (const field of ["authority", "client_id", "redirect_uri", "scope"]) {
      expect(approve(without(loginRecord(), field))).toBe(false);
      expect(approve(loginRecord({ [field]: "" }))).toBe(false);
      expect(approve(loginRecord({ [field]: 7 }))).toBe(false);
    }
  });

  it("requires exactly the return-route payload this application sends", () => {
    expect(approve(without(loginRecord(), "data"))).toBe(false);
    expect(approve(loginRecord({ data: null }))).toBe(false);
    expect(approve(loginRecord({ data: "/health" }))).toBe(false);
    expect(approve(loginRecord({ data: [] }))).toBe(false);
    expect(approve(loginRecord({ data: {} }))).toBe(false);
    expect(approve(loginRecord({ data: { returnTo: "/health", extra: 1 } }))).toBe(false);
    expect(approve(loginRecord({ data: { returnTo: 1 } }))).toBe(false);
  });

  it("requires the extraTokenParams the library always writes", () => {
    expect(approve(without(loginRecord(), "extraTokenParams"))).toBe(false);
    expect(approve(loginRecord({ extraTokenParams: {} }))).toBe(true);
  });

  it("requires extraTokenParams to be exactly an empty object", () => {
    for (const value of [null, "", "{}", 0, false, [], [{}], { length: 0 }, { "": "" }]) {
      expect(approve(loginRecord({ extraTokenParams: value }))).toBe(false);
    }
  });

  /**
   * `extraTokenParams` is spread straight into the token request body, so any
   * key surviving here is a parameter this client did not choose. These are the
   * ones that would actually change what the code exchange proves, and the
   * emptiness rule refuses all of them without having to name them in the
   * production code.
   */
  it("refuses every token-request parameter smuggled through extraTokenParams", () => {
    for (const key of [
      "code_verifier",
      "redirect_uri",
      "client_id",
      "client_secret",
      "scope",
      "grant_type",
      "code",
      "resource",
      "audience",
      "subject_token",
    ]) {
      expect(approve(loginRecord({ extraTokenParams: { [key]: "attacker-value" } }))).toBe(false);
    }
  });

  it("refuses a prototype-shaped extraTokenParams", () => {
    // `JSON.parse` makes this an ordinary own key rather than a prototype
    // mutation, and the exact-emptiness rule refuses it either way.
    expect(
      isApprovedTransactionRecord(
        STATE_ID,
        JSON.stringify(loginRecord()).replace(
          '"extraTokenParams":{}',
          '"extraTokenParams":{"__proto__":{"code_verifier":"attacker"}}',
        ),
      ),
    ).toBe(false);
  });

  it("refuses any field outside the pinned sign-in schema", () => {
    for (const field of [
      "url_state",
      "client_secret",
      "skipUserInfo",
      "response_mode",
      "access_token",
      "id_token",
      "refresh_token",
      "kind",
    ]) {
      expect(approve(loginRecord({ [field]: "anything" }))).toBe(false);
    }
  });
});

describe("isApprovedTransactionRecord — sign-out schema", () => {
  it("requires exactly the fixed logout marker", () => {
    expect(approve(without(logoutRecord(), "data"))).toBe(false);
    expect(approve(logoutRecord({ data: {} }))).toBe(false);
    expect(approve(logoutRecord({ data: null }))).toBe(false);
    expect(approve(logoutRecord({ data: [] }))).toBe(false);
    expect(approve(logoutRecord({ data: { kind: "sign-in" } }))).toBe(false);
    expect(approve(logoutRecord({ data: { kind: "sign-out", returnTo: "/" } }))).toBe(false);
    expect(approve(logoutRecord({ data: { returnTo: "/" } }))).toBe(false);
  });

  it("refuses an injected nonce", () => {
    expect(approve(logoutRecord({ nonce: "Jz1nQyF6iJ8H3lPq2oXw0aZbC4dE5fG6hI7jK8lM9nO" }))).toBe(
      false,
    );
  });

  it("refuses an injected PKCE verifier or sign-in field", () => {
    for (const field of [
      "code_verifier",
      "authority",
      "client_id",
      "redirect_uri",
      "scope",
      "extraTokenParams",
      "url_state",
    ]) {
      expect(approve(logoutRecord({ [field]: "anything" }))).toBe(false);
    }
  });
});

describe("isApprovedTransactionRecord — the two schemas cannot be crossed", () => {
  it("refuses a sign-in record stamped as a logout", () => {
    expect(approve(loginRecord({ request_type: OIDC_LOGOUT_REQUEST_TYPE }))).toBe(false);
  });

  it("refuses a logout record stamped as a sign-in", () => {
    expect(approve(logoutRecord({ request_type: OIDC_LOGIN_REQUEST_TYPE }))).toBe(false);
  });

  it("refuses a logout record wearing a sign-in payload", () => {
    expect(
      approve(
        logoutRecord({
          request_type: OIDC_LOGIN_REQUEST_TYPE,
          data: { returnTo: "/health" },
        }),
      ),
    ).toBe(false);
  });
});
