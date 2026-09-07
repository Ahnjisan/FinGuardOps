import { afterEach, describe, expect, it } from "vitest";
import {
  classifyLogoutCallbackUrl,
  clearLogoutCallbackUrl,
  LOGOUT_CALLBACK_PATH,
} from "./logoutCallbackUrl";

const ORIGIN = "http://localhost:5173";
const STATE = "9f6d2b1a-1c2d-4e3f-8a9b-0c1d2e3f4a5b";

function classify(url: string, origin: string = ORIGIN) {
  return classifyLogoutCallbackUrl(url, origin);
}

afterEach(() => {
  window.history.replaceState(null, "", "/");
});

describe("logout callback path", () => {
  it("is the exact application root the Keycloak client allowlists", () => {
    expect(LOGOUT_CALLBACK_PATH).toBe("/");
  });
});

describe("classifyLogoutCallbackUrl — ordinary page loads", () => {
  it("treats a bare root visit as no callback at all", () => {
    expect(classify(`${ORIGIN}/`)).toBe("none");
  });

  it("treats an unparseable URL as no callback", () => {
    expect(classify("not a url")).toBe("none");
    expect(classify("")).toBe("none");
  });

  it("treats a root visit carrying only unrelated parameters as no callback", () => {
    expect(classify(`${ORIGIN}/?utm_source=mail`)).toBe("none");
  });

  it("treats a root fragment with no response parameters as no callback", () => {
    expect(classify(`${ORIGIN}/#section`)).toBe("none");
  });
});

describe("classifyLogoutCallbackUrl — destination", () => {
  it("accepts the exact origin and root path", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}`)).toBe("logout-response");
  });

  it.each([
    ["a different host", `http://evil.example/?state=${STATE}`],
    ["a different scheme", `https://localhost:5173/?state=${STATE}`],
    ["a different port", `http://localhost:5174/?state=${STATE}`],
    ["a subdomain", `http://app.localhost:5173/?state=${STATE}`],
  ])("refuses to see a response on %s", (_label, url) => {
    expect(classify(url)).toBe("none");
  });

  it.each([
    ["a deeper path", `${ORIGIN}/logout?state=${STATE}`],
    ["the sign-in callback path", `${ORIGIN}/auth/callback?state=${STATE}`],
    ["a trailing path segment", `${ORIGIN}//?state=${STATE}`],
  ])("refuses to see a response at %s", (_label, url) => {
    expect(classify(url)).toBe("none");
  });

  it("refuses a userinfo authority even though the origin still matches", () => {
    expect(new URL(`http://user:secret@localhost:5173/?state=${STATE}`).origin).toBe(ORIGIN);
    expect(classify(`http://user:secret@localhost:5173/?state=${STATE}`)).toBe("invalid");
    expect(classify(`http://user@localhost:5173/?state=${STATE}`)).toBe("invalid");
  });

  it("refuses a response that carries a fragment", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}#anything`)).toBe("invalid");
  });
});

describe("classifyLogoutCallbackUrl — state", () => {
  it("requires a state", () => {
    expect(classify(`${ORIGIN}/?error=access_denied`)).toBe("invalid");
  });

  it("refuses a blank or whitespace-only state", () => {
    expect(classify(`${ORIGIN}/?state=`)).toBe("invalid");
    expect(classify(`${ORIGIN}/?state=%20%20`)).toBe("invalid");
  });

  it("refuses a repeated state", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}&state=${STATE}`)).toBe("invalid");
    expect(classify(`${ORIGIN}/?state=${STATE}&state=other`)).toBe("invalid");
  });

  it("refuses a state carrying the library's url-state delimiter", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}%3Bhttps%3A%2F%2Fevil.example`)).toBe("invalid");
  });

  it("refuses a state carrying a path, slash or percent-encoded payload", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}%2F..%2Fadmin`)).toBe("invalid");
    expect(classify(`${ORIGIN}/?state=%3Cscript%3E`)).toBe("invalid");
  });

  it("refuses an over-long state", () => {
    expect(classify(`${ORIGIN}/?state=${"a".repeat(257)}`)).toBe("invalid");
  });

  it("accepts the unreserved characters a library state id actually uses", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}`)).toBe("logout-response");
    expect(classify(`${ORIGIN}/?state=abc_DEF-123.~`)).toBe("logout-response");
  });
});

describe("classifyLogoutCallbackUrl — parameter set", () => {
  it("refuses an unknown parameter alongside the state", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}&code=abc`)).toBe("invalid");
    expect(classify(`${ORIGIN}/?state=${STATE}&session_state=abc`)).toBe("invalid");
    expect(classify(`${ORIGIN}/?state=${STATE}&iss=https%3A%2F%2Fevil.example`)).toBe("invalid");
  });

  it("refuses a success response carrying an error description", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}&error_description=oops`)).toBe("invalid");
    expect(classify(`${ORIGIN}/?state=${STATE}&error_uri=https%3A%2F%2Fas.example%2Fe`)).toBe(
      "invalid",
    );
  });

  it("refuses a repeated error parameter", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}&error=a&error=b`)).toBe("invalid");
  });

  it("classifies a named error response as an error", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}&error=access_denied`)).toBe(
      "logout-error-response",
    );
    expect(
      classify(`${ORIGIN}/?state=${STATE}&error=access_denied&error_description=nope`),
    ).toBe("logout-error-response");
  });

  it("refuses a blank error", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}&error=`)).toBe("invalid");
    expect(classify(`${ORIGIN}/?state=${STATE}&error=%20`)).toBe("invalid");
  });

  it("classifies a success response as exactly one state parameter", () => {
    expect(classify(`${ORIGIN}/?state=${STATE}`)).toBe("logout-response");
  });
});

describe("clearLogoutCallbackUrl", () => {
  it("replaces the address bar with the bare root path", () => {
    window.history.replaceState(null, "", `/?state=${STATE}&error=access_denied`);

    clearLogoutCallbackUrl();

    expect(window.location.pathname).toBe("/");
    expect(window.location.search).toBe("");
    expect(window.location.hash).toBe("");
  });

  it("leaves no state or provider description anywhere in the address bar", () => {
    window.history.replaceState(
      null,
      "",
      `/?state=${STATE}&error=access_denied&error_description=provider-detail#frag`,
    );

    clearLogoutCallbackUrl();

    expect(window.location.href).not.toContain(STATE);
    expect(window.location.href).not.toContain("access_denied");
    expect(window.location.href).not.toContain("provider-detail");
    expect(window.location.href).not.toContain("frag");
  });

  it("adds no history entry", () => {
    const before = window.history.length;
    window.history.replaceState(null, "", `/?state=${STATE}`);

    clearLogoutCallbackUrl();

    expect(window.history.length).toBe(before);
  });
});
