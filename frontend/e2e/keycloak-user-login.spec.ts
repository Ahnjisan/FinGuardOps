import { createHash, randomBytes, randomUUID } from "node:crypto";
import { lstatSync, readFileSync, realpathSync } from "node:fs";
import { request as httpsRequest } from "node:https";
import { dirname, resolve } from "node:path";
import { env } from "node:process";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import { expect, test, type Page, type Request as PlaywrightRequest, type Route } from "@playwright/test";

const APP_ORIGIN = "http://localhost:5173";
const CALLBACK_URL = `${APP_ORIGIN}/auth/callback`;
const AUTHORITY = "https://localhost:8443/realms/finguardops-local";
const AUTHORIZE_URL = `${AUTHORITY}/protocol/openid-connect/auth`;
const TOKEN_URL = `${AUTHORITY}/protocol/openid-connect/token`;
const TRANSACTION_PREFIX = "finguardops.oidc.transaction.";
const USER_PREFIX = "finguardops.oidc.user.";
const USERNAME = "local-fds-analyst";
const BACKEND_AUDIENCE = "finguardops-backend-api";
const CANONICAL_UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const REPO_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "..", "..");
const PASSWORD_PATH = resolve(REPO_ROOT, "infra", "keycloak", ".local", "secrets", "user-password");
const TLS_CERTIFICATE_PATH = resolve(REPO_ROOT, "infra", "keycloak", ".local", "tls", "localhost.crt");
const COMPOSE_PROJECT = env.FINGUARDOPS_E2E_COMPOSE_PROJECT;

interface ProtocolRecord {
  readonly key: string;
  readonly nonce: string;
  readonly codeVerifier: string;
  readonly redirectUri: string;
  readonly scope: string;
}

interface AuthorizationCapture {
  readonly state: string;
  readonly nonce: string;
  readonly codeChallenge: string;
  readonly codeChallengeMethod: string;
  readonly redirectUri: string;
  readonly scope: string;
  readonly record: ProtocolRecord;
}

interface TokenMaterial {
  readonly accessToken: string;
  readonly idToken: string;
}

interface BackendObservation {
  readonly method: string;
  readonly pathname: string;
  readonly status: number;
}

type TransactionMutation = "none" | "state" | "nonce-removed" | "nonce-blank" | "nonce-mismatch" | "pkce";

function requireCondition(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function readUserPassword(): string {
  const parent = realpathSync(dirname(PASSWORD_PATH));
  const target = realpathSync(PASSWORD_PATH);
  requireCondition(dirname(target) === parent, "The USER password path escaped its owned directory.");
  requireCondition(!lstatSync(PASSWORD_PATH).isSymbolicLink(), "The USER password must not be a link.");

  const bytes = readFileSync(target);
  requireCondition(bytes.length >= 32, "The USER password is invalid.");
  for (const byte of bytes) {
    requireCondition(byte >= 0x21 && byte <= 0x7e, "The USER password is invalid.");
  }
  return bytes.toString("ascii");
}

function base64UrlSha256(value: string): string {
  return createHash("sha256").update(value, "ascii").digest("base64url");
}

function decodeJwtPayload(token: string): Record<string, unknown> {
  const parts = token.split(".");
  requireCondition(parts.length === 3, "A token did not have the required JWT shape.");
  try {
    const parsed: unknown = JSON.parse(Buffer.from(parts[1], "base64url").toString("utf8"));
    requireCondition(typeof parsed === "object" && parsed !== null, "A JWT payload was invalid.");
    return parsed as Record<string, unknown>;
  } catch {
    throw new Error("A JWT payload was invalid.");
  }
}

function mutateJwtPayload(
  token: string,
  mutate: (payload: Record<string, unknown>) => void,
): string {
  const parts = token.split(".");
  requireCondition(parts.length === 3, "A token did not have the required JWT shape.");
  const payload = decodeJwtPayload(token);
  mutate(payload);
  return `${parts[0]}.${Buffer.from(JSON.stringify(payload), "utf8").toString("base64url")}.${parts[2]}`;
}

function requireNonBlankString(value: unknown, message: string): asserts value is string {
  requireCondition(typeof value === "string" && value.trim() !== "", message);
}

function requireUniqueStringArray(value: unknown, message: string): asserts value is string[] {
  requireCondition(Array.isArray(value), message);
  requireCondition(value.every((item) => typeof item === "string" && item !== ""), message);
  requireCondition(new Set(value).size === value.length, message);
}

function installSessionPublicationProbe(page: Page): Promise<void> {
  return page.addInitScript(() => {
    const probe = { count: 0, observed: false };
    Object.defineProperty(window, "__finguardopsSessionProbe", {
      value: probe,
      configurable: false,
      enumerable: false,
      writable: false,
    });
    const inspect = () => {
      const status = document.querySelector('[aria-label="Authentication status"]')?.textContent ?? "";
      if (!probe.observed && status.startsWith("Signed in")) {
        probe.observed = true;
        probe.count += 1;
      }
    };
    document.addEventListener("DOMContentLoaded", () => {
      inspect();
      new MutationObserver(inspect).observe(document.documentElement, {
        childList: true,
        subtree: true,
        characterData: true,
      });
    });
  });
}

async function publicationCount(page: Page): Promise<number> {
  return page.evaluate(() => {
    const candidate = (window as typeof window & {
      __finguardopsSessionProbe?: { count: number };
    }).__finguardopsSessionProbe;
    return candidate?.count ?? 0;
  });
}

interface AuthorizationObserver {
  readonly capture: Promise<AuthorizationCapture>;
}

async function installAuthorizationCapture(
  page: Page,
  mutation: TransactionMutation,
): Promise<AuthorizationObserver> {
  let resolveRecord: (record: ProtocolRecord) => void = () => undefined;
  let rejectRecord: (error: Error) => void = () => undefined;
  const recordPromise = new Promise<ProtocolRecord>((resolvePromise, rejectPromise) => {
    resolveRecord = resolvePromise;
    rejectRecord = rejectPromise;
  });

  await page.exposeFunction(
    "__finguardopsCaptureTransaction",
    (key: string, serialized: string) => {
      try {
        const value: unknown = JSON.parse(serialized);
        requireCondition(typeof value === "object" && value !== null, "The OIDC transaction record was invalid.");
        const fields = value as Record<string, unknown>;
        requireNonBlankString(key, "The transaction key was invalid.");
        requireNonBlankString(fields.code_verifier, "The PKCE verifier was invalid.");
        requireNonBlankString(fields.redirect_uri, "The transaction redirect URI was invalid.");
        requireNonBlankString(fields.scope, "The transaction scope was invalid.");
        resolveRecord({
          key,
          nonce: typeof fields.nonce === "string" ? fields.nonce : "",
          codeVerifier: fields.code_verifier,
          redirectUri: fields.redirect_uri,
          scope: fields.scope,
        });
      } catch {
        rejectRecord(new Error("The OIDC transaction record could not be inspected safely."));
      }
    },
  );

  const replacement = randomBytes(48).toString("base64url");
  await page.addInitScript(
    ({ prefix, selectedMutation, replacementValue }) => {
      const originalSetItem = Storage.prototype.setItem;
      Storage.prototype.setItem = function setItem(key: string, value: string): void {
        let keyToStore = key;
        let valueToStore = value;
        if (this === window.sessionStorage && key.startsWith(prefix)) {
          try {
            const transaction = JSON.parse(value) as Record<string, unknown>;
            const capture = (window as typeof window & {
              __finguardopsCaptureTransaction: (capturedKey: string, serialized: string) => Promise<void>;
            }).__finguardopsCaptureTransaction;
            void capture(key, value);
            if (selectedMutation === "state") {
              keyToStore = `${prefix}${replacementValue}`;
            } else if (selectedMutation === "nonce-removed") {
              delete transaction.nonce;
              valueToStore = JSON.stringify(transaction);
            } else if (selectedMutation === "nonce-blank") {
              transaction.nonce = " ";
              valueToStore = JSON.stringify(transaction);
            } else if (selectedMutation === "nonce-mismatch") {
              transaction.nonce = replacementValue;
              valueToStore = JSON.stringify(transaction);
            } else if (selectedMutation === "pkce") {
              transaction.code_verifier = replacementValue;
              valueToStore = JSON.stringify(transaction);
            }
          } catch {
            // The production storage call still receives its original value;
            // the fixed test-side timeout/error owns an unreadable record.
          }
        }
        originalSetItem.call(this, keyToStore, valueToStore);
      };
    },
    { prefix: TRANSACTION_PREFIX, selectedMutation: mutation, replacementValue: replacement },
  );

  const requestPromise = page.waitForRequest(
    (request) => request.url().startsWith(`${AUTHORIZE_URL}?`) && request.method() === "GET",
  );
  const capture = Promise.all([requestPromise, recordPromise]).then(([request, record]) => {
    const authorize = new URL(request.url());
    return {
      state: authorize.searchParams.get("state") ?? "",
      nonce: authorize.searchParams.get("nonce") ?? "",
      codeChallenge: authorize.searchParams.get("code_challenge") ?? "",
      codeChallengeMethod: authorize.searchParams.get("code_challenge_method") ?? "",
      redirectUri: authorize.searchParams.get("redirect_uri") ?? "",
      scope: authorize.searchParams.get("scope") ?? "",
      record,
    };
  });
  return { capture };
}

async function beginLogin(
  page: Page,
  password: string,
  mutation: TransactionMutation = "none",
): Promise<AuthorizationCapture> {
  const observer = await installAuthorizationCapture(page, mutation);
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in" }).click();
  const capture = await observer.capture;
  await expect(page.locator("#username")).toBeVisible();
  await page.locator("#username").fill(USERNAME);
  await page.locator("#password").fill(password);
  return capture;
}

async function submitLogin(page: Page): Promise<void> {
  await page.locator("#kc-login").click();
  if (new URL(page.url()).origin === new URL(AUTHORITY).origin) {
    const credentialFormRemains = await page.locator("#kc-form-login").isVisible();
    throw new Error(
      credentialFormRemains
        ? "Keycloak rejected the configured test credential."
        : "Keycloak required an unexpected post-login action.",
    );
  }
}

function parseTokenResponse(value: unknown): TokenMaterial {
  requireCondition(typeof value === "object" && value !== null, "The token response was invalid.");
  const response = value as Record<string, unknown>;
  requireCondition(!Object.prototype.hasOwnProperty.call(response, "refresh_token"), "A refresh token was issued.");
  requireNonBlankString(response.access_token, "The access token was missing.");
  requireNonBlankString(response.id_token, "The ID token was missing.");
  return { accessToken: response.access_token, idToken: response.id_token };
}

function requireTokenClaims(tokens: TokenMaterial): void {
  const access = decodeJwtPayload(tokens.accessToken);
  const identity = decodeJwtPayload(tokens.idToken);

  requireNonBlankString(access.sub, "The access token subject was invalid.");
  requireNonBlankString(identity.sub, "The ID token subject was invalid.");
  requireCondition(access.sub === identity.sub, "The token subjects differed.");
  requireCondition(CANONICAL_UUID_V4.test(access.sub), "The access token subject was not canonical UUID v4.");
  requireCondition(CANONICAL_UUID_V4.test(identity.sub), "The ID token subject was not canonical UUID v4.");
  requireCondition(access.principal_type === "USER", "The access token principal type was invalid.");
  requireCondition(identity.principal_type === "USER", "The ID token principal type was invalid.");
  requireNonBlankString(access.scope, "The access token scope was invalid.");
  const accessScopes = access.scope.split(" ");
  requireCondition(
    accessScopes.length === 2 && new Set(accessScopes).size === 2 && accessScopes.includes("openid") && accessScopes.includes("profile"),
    "The access token did not contain the exact requested scopes.",
  );
  requireCondition(identity.preferred_username === USERNAME, "The stock profile claim was not issued.");
  requireCondition(identity.given_name === "Local", "The stock given-name claim was not issued.");
  requireCondition(identity.family_name === "Analyst", "The stock family-name claim was not issued.");
  requireCondition(identity.name === "Local Analyst", "The stock full-name claim was not issued.");

  requireUniqueStringArray(access.roles, "The access token roles were invalid.");
  requireUniqueStringArray(identity.roles, "The ID token roles were invalid.");
  requireCondition(
    access.roles.length === identity.roles.length &&
      access.roles.every((role) => identity.roles.includes(role)),
    "The access and ID token roles differed.",
  );
  requireCondition(access.roles.length === 1 && access.roles[0] === "FDS_ANALYST", "The USER role set was invalid.");

  const audience = access.aud;
  requireCondition(
    audience === BACKEND_AUDIENCE ||
      (Array.isArray(audience) && audience.length === 1 && audience[0] === BACKEND_AUDIENCE),
    "The access token audience was not the exact singleton.",
  );
}

function parseHttpStatus(output: string): number {
  const match = /^HTTP\/1\.[01] ([0-9]{3})\b/.exec(output.trim());
  requireCondition(match !== null, "The Backend relay returned an invalid status line.");
  return Number(match[1]);
}

function relayToBackend(request: PlaywrightRequest): number {
  requireNonBlankString(COMPOSE_PROJECT, "The dedicated Compose project was not configured.");
  const url = new URL(request.url());
  requireCondition(url.origin === "http://localhost:8080", "An unexpected Backend origin was requested.");
  requireCondition(/^[A-Z]+$/.test(request.method()), "An invalid Backend method was requested.");
  requireCondition(/^\/api\/v1\/[a-z0-9\-/]+$/.test(url.pathname), "An invalid Backend path was requested.");

  const credential = request.headers()["authorization"] ?? "";
  requireCondition(!credential.includes("\r") && !credential.includes("\n"), "An invalid credential header was refused.");
  const body = request.postData() ?? "";
  requireCondition(!body.includes("\r") && !body.includes("\n"), "A multiline E2E body was refused.");

  const script = [
    "set -euo pipefail",
    "IFS= read -r method",
    "IFS= read -r path",
    "IFS= read -r credential",
    "IFS= read -r body",
    "exec 3<>/dev/tcp/127.0.0.1/8080",
    "printf '%s %s HTTP/1.1\\r\\nHost: localhost\\r\\nAccept: application/json\\r\\nConnection: close\\r\\n' \"$method\" \"$path\" >&3",
    "if [[ -n $credential ]]; then printf 'Authorization: %s\\r\\n' \"$credential\" >&3; fi",
    "if [[ -n $body ]]; then printf 'Content-Type: application/json\\r\\nContent-Length: %s\\r\\n' \"${#body}\" >&3; fi",
    "printf '\\r\\n%s' \"$body\" >&3",
    "IFS= read -r status <&3",
    "printf '%s\\n' \"$status\"",
  ].join("\n");

  const result = spawnSync(
    "docker",
    [
      "compose",
      "-p",
      COMPOSE_PROJECT,
      "--env-file",
      "infra/.env.example",
      "-f",
      "infra/compose.yml",
      "-f",
      "infra/compose.keycloak-local-e2e.yml",
      "exec",
      "-T",
      "backend",
      "bash",
      "-c",
      script,
    ],
    {
      cwd: REPO_ROOT,
      encoding: "utf8",
      input: `${request.method()}\n${url.pathname}\n${credential}\n${body}\n`,
      timeout: 15_000,
      windowsHide: true,
    },
  );
  requireCondition(result.status === 0, "The Backend relay failed.");
  return parseHttpStatus(result.stdout);
}

async function installBackendRelay(page: Page): Promise<BackendObservation[]> {
  const observations: BackendObservation[] = [];
  await page.route("http://localhost:8080/**", async (route: Route) => {
    const request = route.request();
    if (request.method() === "OPTIONS") {
      await route.fulfill({
        status: 204,
        headers: {
          "access-control-allow-origin": APP_ORIGIN,
          "access-control-allow-methods": "GET, POST, PATCH",
          "access-control-allow-headers": "authorization, content-type",
        },
      });
      return;
    }
    const status = relayToBackend(request);
    observations.push({ method: request.method(), pathname: new URL(request.url()).pathname, status });
    await route.fulfill({
      status,
      contentType: "application/json",
      headers: { "access-control-allow-origin": APP_ORIGIN },
      body: "{}",
    });
  });
  return observations;
}

async function browserContainsAny(page: Page, values: readonly string[]): Promise<boolean> {
  return page.evaluate((needles) => {
    const haystacks = [document.documentElement.textContent ?? "", window.location.href];
    for (const storage of [window.localStorage, window.sessionStorage]) {
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index);
        if (key !== null) {
          haystacks.push(key, storage.getItem(key) ?? "");
        }
      }
    }
    return needles.some((needle) => needle !== "" && haystacks.some((value) => value.includes(needle)));
  }, values);
}

async function hasOwnedStorage(page: Page): Promise<boolean> {
  return page.evaluate(
    ({ transactionPrefix, userPrefix }) => {
      for (const storage of [window.localStorage, window.sessionStorage]) {
        for (let index = 0; index < storage.length; index += 1) {
          const key = storage.key(index) ?? "";
          if (key.startsWith(transactionPrefix) || key.startsWith(userPrefix)) {
            return true;
          }
        }
      }
      return false;
    },
    { transactionPrefix: TRANSACTION_PREFIX, userPrefix: USER_PREFIX },
  );
}

async function runRejectedCallback(
  page: Page,
  password: string,
  mutation: "state" | Exclude<TransactionMutation, "none">,
): Promise<void> {
  const tokenGrantTypes: string[] = [];
  const backendRequests: string[] = [];
  page.on("request", (request) => {
    if (request.url() === TOKEN_URL && request.method() === "POST") {
      tokenGrantTypes.push(new URLSearchParams(request.postData() ?? "").get("grant_type") ?? "");
    }
    if (request.url().startsWith("http://localhost:8080/")) {
      backendRequests.push(request.method());
    }
  });
  await beginLogin(page, password, mutation);
  await submitLogin(page);
  await expectAuthenticationFailure(page);
  requireCondition((await publicationCount(page)) === 0, "A rejected callback published a session.");
  requireCondition(backendRequests.length === 0, "A rejected callback reached the Backend.");
  requireCondition(!(await hasOwnedStorage(page)), "A rejected callback retained OIDC storage.");
  requireCondition(tokenGrantTypes.every((grant) => grant === "authorization_code"), "A forbidden grant was attempted.");
  const expectedCodeExchanges =
    mutation === "nonce-mismatch" || mutation === "pkce" ? 1 : 0;
  requireCondition(
    tokenGrantTypes.length === expectedCodeExchanges,
    "The rejected callback made an unexpected authorization-code exchange.",
  );
  await page.waitForTimeout(500);
  requireCondition(
    tokenGrantTypes.length === expectedCodeExchanges,
    "A rejected callback attempted silent renewal or a retry.",
  );
}

async function expectAuthenticationFailure(page: Page): Promise<void> {
  await expect(page.getByRole("status", { name: "Authentication status" })).toHaveText(
    "Sign-in could not be completed. Please try signing in again.",
  );
}

async function fetchTokenResponse(route: Route): Promise<{
  readonly status: number;
  readonly body: Record<string, unknown>;
}> {
  const request = route.request();
  const contentType = request.headers()["content-type"] ?? "application/x-www-form-urlencoded";
  const postData = request.postData() ?? "";
  const result = await new Promise<{ status: number; text: string }>((resolvePromise, rejectPromise) => {
    const upstream = httpsRequest(
      TOKEN_URL,
      {
        method: "POST",
        ca: readFileSync(TLS_CERTIFICATE_PATH),
        headers: {
          "content-type": contentType,
          "content-length": Buffer.byteLength(postData),
        },
      },
      (response) => {
        const chunks: Buffer[] = [];
        let length = 0;
        response.on("data", (chunk: Buffer) => {
          length += chunk.length;
          if (length > 1_048_576) {
            upstream.destroy(new Error("The upstream token response was too large."));
            return;
          }
          chunks.push(chunk);
        });
        response.on("end", () => {
          resolvePromise({ status: response.statusCode ?? 0, text: Buffer.concat(chunks).toString("utf8") });
        });
      },
    );
    upstream.setTimeout(10_000, () => upstream.destroy(new Error("The upstream token request timed out.")));
    upstream.on("error", () => rejectPromise(new Error("The upstream token request failed.")));
    upstream.end(postData);
  });
  let parsed: unknown;
  try {
    parsed = JSON.parse(result.text);
  } catch {
    throw new Error("The upstream token response was invalid.");
  }
  requireCondition(typeof parsed === "object" && parsed !== null, "The upstream token response was invalid.");
  return { status: result.status, body: parsed as Record<string, unknown> };
}

test.beforeEach(async ({ page }) => {
  await installSessionPublicationProbe(page);
});

test("real USER login enforces PKCE, token claims, and Backend boundaries", async ({ page }) => {
  const password = readUserPassword();
  const consoleMessages: string[] = [];
  page.on("console", (message) => consoleMessages.push(message.text()));
  const backend = await installBackendRelay(page);
  let callbackUrl = "";
  page.on("request", (request) => {
    if (request.url().startsWith(`${CALLBACK_URL}?`)) {
      callbackUrl = request.url();
    }
  });

  const capture = await beginLogin(page, password);
  requireNonBlankString(capture.state, "The authorization state was blank.");
  requireNonBlankString(capture.nonce, "The authorization nonce was blank.");
  requireCondition(/^[A-Za-z0-9_-]{43}$/.test(capture.nonce), "The authorization nonce was not 256-bit base64url.");
  requireCondition(capture.redirectUri === CALLBACK_URL, "The authorization redirect URI differed.");
  requireCondition(capture.record.redirectUri === CALLBACK_URL, "The transaction redirect URI differed.");
  requireCondition(capture.scope === "openid profile", "The authorization scope differed.");
  requireCondition(capture.record.scope === "openid profile", "The transaction scope differed.");
  requireCondition(capture.record.key === `${TRANSACTION_PREFIX}${capture.state}`, "The transaction state differed.");
  requireCondition(capture.record.nonce === capture.nonce, "The transaction nonce differed.");
  requireCondition(capture.codeChallengeMethod === "S256", "The PKCE method was not S256.");
  requireCondition(
    base64UrlSha256(capture.record.codeVerifier) === capture.codeChallenge,
    "The PKCE challenge did not match its verifier.",
  );

  const tokenRequestPromise = page.waitForRequest(
    (request) => request.url() === TOKEN_URL && request.method() === "POST",
  );
  const tokenResponsePromise = page.waitForResponse(
    (response) => response.url() === TOKEN_URL && response.request().method() === "POST",
  );
  await submitLogin(page);
  const tokenRequest = await tokenRequestPromise;
  const tokenResponse = await tokenResponsePromise;
  requireCondition(tokenResponse.status() === 200, "The authorization-code exchange failed.");
  const form = new URLSearchParams(tokenRequest.postData() ?? "");
  requireCondition(form.get("grant_type") === "authorization_code", "The token grant was invalid.");
  requireCondition(form.get("redirect_uri") === CALLBACK_URL, "The token redirect URI differed.");
  requireCondition(form.get("code_verifier") === capture.record.codeVerifier, "The token verifier differed.");

  const tokens = parseTokenResponse(await tokenResponse.json());
  requireTokenClaims(tokens);
  requireNonBlankString(callbackUrl, "The browser callback URL was not observed.");
  const callback = new URL(callbackUrl);
  requireCondition(callback.searchParams.get("state") === capture.state, "The callback state differed.");
  requireCondition(callback.searchParams.get("code") === form.get("code"), "The exchanged code differed.");

  await expect(page.getByLabel("Authentication status")).toContainText("Signed in as");
  await page.waitForFunction((expected) => window.location.href === expected, `${APP_ORIGIN}/`);
  requireCondition(page.url() === `${APP_ORIGIN}/`, "The callback did not return to the exact application URL.");
  requireCondition((await publicationCount(page)) === 1, "The application session was not published exactly once.");
  requireCondition(!new URL(page.url()).searchParams.has("code"), "The authorization code remained in the address bar.");
  requireCondition(!(await hasOwnedStorage(page)), "OIDC transaction state remained after login.");

  const caseListResult = await page.evaluate(async () => {
    const [{ getOidcAuthClient }, { sendAuthorizedBackendRequest }] = await Promise.all([
      import("/src/auth/oidcAuthClient.ts"),
      import("/src/api/authorizedClient.ts"),
    ]);
    try {
      await sendAuthorizedBackendRequest(getOidcAuthClient(), {
        endpoint: "case-list",
        validate: (body: unknown): body is Record<string, unknown> => typeof body === "object" && body !== null,
      });
      return "ok";
    } catch (error: unknown) {
      return error instanceof Error ? error.name : "unknown";
    }
  });
  requireCondition(caseListResult === "ok", "The authenticated case-list request failed.");

  const unauthenticatedStatus = await page.evaluate(async () => {
    const response = await fetch("http://localhost:8080/api/v1/cases", {
      credentials: "omit",
      redirect: "error",
    });
    return response.status;
  });
  requireCondition(unauthenticatedStatus === 401, "The missing-credential boundary did not return 401.");

  const damagedStatus = await page.evaluate(async () => {
    const response = await fetch("http://localhost:8080/api/v1/cases", {
      headers: { Authorization: "Bearer damaged-token" },
      credentials: "omit",
      redirect: "error",
    });
    return response.status;
  });
  requireCondition(damagedStatus === 401, "The damaged-token boundary did not return 401.");

  const resolutionResult = await page.evaluate(async (caseId) => {
    const [{ getOidcAuthClient }, { sendAuthorizedBackendRequest }] = await Promise.all([
      import("/src/auth/oidcAuthClient.ts"),
      import("/src/api/authorizedClient.ts"),
    ]);
    try {
      await sendAuthorizedBackendRequest(getOidcAuthClient(), {
        endpoint: "case-resolution-create",
        params: { caseId },
        body: {
          finalDisposition: "NORMAL",
          reasonCode: "CASE_RESOLUTION_COMPLETED",
          expectedVersion: 0,
        },
        validate: () => null,
      });
      return "unexpected-success";
    } catch (error: unknown) {
      return error instanceof Error ? error.name : "unknown";
    }
  }, randomUUID());
  requireCondition(resolutionResult === "ForbiddenError", "The analyst resolution boundary did not return 403.");
  await expect(page.getByLabel("Authentication status")).toContainText("Signed in as");
  requireCondition((await publicationCount(page)) === 1, "A 403 invalidated the application session.");

  requireCondition(
    backend.some((entry) => entry.method === "GET" && entry.pathname === "/api/v1/cases" && entry.status === 200),
    "The real USER case-list request did not return 200.",
  );
  requireCondition(backend.filter((entry) => entry.status === 401).length === 2, "The 401 boundary count differed.");
  requireCondition(
    backend.filter((entry) => entry.method === "POST" && entry.pathname.endsWith("/resolution") && entry.status === 403).length === 1,
    "The resolution request count differed.",
  );
  requireCondition(
    backend.filter((entry) => entry.method !== "GET" && entry.status >= 200 && entry.status < 300).length === 0,
    "A forbidden business mutation succeeded.",
  );

  const sensitive = [password, tokens.accessToken, tokens.idToken, form.get("code") ?? ""];
  requireCondition(!(await browserContainsAny(page, sensitive)), "A credential reached DOM, URL, or Web Storage.");
  requireCondition(
    !consoleMessages.some((message) => sensitive.some((value) => value !== "" && message.includes(value))),
    "A credential reached the browser console.",
  );
});

test("synthetic refresh token is rejected before session publication", async ({ page }) => {
  const password = readUserPassword();
  const sentinel = `refresh-sentinel-${randomUUID()}`;
  const consoleMessages: string[] = [];
  const backendRequests: string[] = [];
  const grantTypes: string[] = [];
  page.on("console", (message) => consoleMessages.push(message.text()));
  page.on("request", (request) => {
    if (request.url().startsWith("http://localhost:8080/")) {
      backendRequests.push(request.method());
    }
    if (request.url() === TOKEN_URL && request.method() === "POST") {
      grantTypes.push(new URLSearchParams(request.postData() ?? "").get("grant_type") ?? "");
    }
  });
  await page.route((url) => url.toString() === TOKEN_URL, async (route) => {
    const upstream = await fetchTokenResponse(route);
    requireCondition(!Object.prototype.hasOwnProperty.call(upstream.body, "refresh_token"), "The provider issued a refresh token.");
    await route.fulfill({
      status: upstream.status,
      contentType: "application/json",
      body: JSON.stringify({ ...upstream.body, refresh_token: sentinel }),
    });
  });

  await beginLogin(page, password);
  await submitLogin(page);
  await expectAuthenticationFailure(page);
  requireCondition((await publicationCount(page)) === 0, "The refresh-token response published a session.");
  requireCondition(backendRequests.length === 0, "The refresh-token response reached the Backend.");
  requireCondition(!(await hasOwnedStorage(page)), "The refresh-token response retained user state.");
  requireCondition(grantTypes.length === 1 && grantTypes[0] === "authorization_code", "A forbidden token grant was attempted.");
  await page.waitForTimeout(500);
  requireCondition(grantTypes.length === 1, "Silent renewal or a refresh grant was attempted.");
  requireCondition(!(await browserContainsAny(page, [sentinel])), "The refresh sentinel reached DOM or browser storage.");
  requireCondition(!consoleMessages.some((message) => message.includes(sentinel)), "The refresh sentinel reached the console.");
});

for (const mutation of ["state", "nonce-removed", "nonce-blank", "nonce-mismatch", "pkce"] as const) {
  test(`${mutation} tampering is rejected before session publication`, async ({ page }) => {
    const password = readUserPassword();
    await runRejectedCallback(page, password, mutation);
  });
}

for (const idTokenNonceMutation of ["missing", "mismatch"] as const) {
  test(`ID token nonce ${idTokenNonceMutation} is rejected before publication`, async ({ page }) => {
    const password = readUserPassword();
    const sentinel = `id-token-nonce-${idTokenNonceMutation}-${randomUUID()}`;
    const tokenGrantTypes: string[] = [];
    const backendRequests: string[] = [];
    const consoleMessages: string[] = [];
    page.on("console", (message) => consoleMessages.push(message.text()));
    page.on("request", (request) => {
      if (request.url() === TOKEN_URL && request.method() === "POST") {
        tokenGrantTypes.push(new URLSearchParams(request.postData() ?? "").get("grant_type") ?? "");
      }
      if (request.url().startsWith("http://localhost:8080/")) {
        backendRequests.push(request.method());
      }
    });
    await page.route((url) => url.toString() === TOKEN_URL, async (route) => {
      const upstream = await fetchTokenResponse(route);
      const fields = upstream.body;
      requireNonBlankString(fields.id_token, "The ID token was missing.");
      const idToken = mutateJwtPayload(fields.id_token, (payload) => {
        if (idTokenNonceMutation === "missing") {
          delete payload.nonce;
        } else {
          payload.nonce = sentinel;
        }
      });
      await route.fulfill({
        status: upstream.status,
        contentType: "application/json",
        body: JSON.stringify({ ...fields, id_token: idToken }),
      });
    });

    await beginLogin(page, password);
    await submitLogin(page);
    await expectAuthenticationFailure(page);

    requireCondition(
      tokenGrantTypes.length === 1 && tokenGrantTypes[0] === "authorization_code",
      "ID token validation did not perform exactly the required code exchange.",
    );
    requireCondition((await publicationCount(page)) === 0, "An invalid ID token nonce published a session.");
    requireCondition(backendRequests.length === 0, "An invalid ID token nonce reached the Backend.");
    requireCondition(!(await hasOwnedStorage(page)), "An invalid ID token nonce retained OIDC storage.");
    requireCondition(!(await browserContainsAny(page, [sentinel])), "An invalid ID token nonce reached the browser surface.");
    requireCondition(!consoleMessages.some((message) => message.includes(sentinel)), "An invalid ID token nonce reached the console.");
  });
}

test("a consumed callback cannot be reused", async ({ page }) => {
  const password = readUserPassword();
  let callbackUrl = "";
  page.on("request", (request) => {
    if (request.url().startsWith(`${CALLBACK_URL}?`)) {
      callbackUrl = request.url();
    }
  });

  await beginLogin(page, password);
  await submitLogin(page);
  await expect(page.getByLabel("Authentication status")).toContainText("Signed in as");
  requireNonBlankString(callbackUrl, "The first callback URL was not observed.");
  requireCondition(!(await hasOwnedStorage(page)), "The first callback retained transaction state.");

  await page.goto("/");
  const tokenGrantTypes: string[] = [];
  const backendRequests: string[] = [];
  page.on("request", (request) => {
    if (request.url() === TOKEN_URL && request.method() === "POST") {
      tokenGrantTypes.push(new URLSearchParams(request.postData() ?? "").get("grant_type") ?? "");
    }
    if (request.url().startsWith("http://localhost:8080/")) {
      backendRequests.push(request.method());
    }
  });

  await page.goto(callbackUrl);
  await expectAuthenticationFailure(page);
  requireCondition(tokenGrantTypes.length === 0, "A reused callback exchanged its consumed code.");
  requireCondition((await publicationCount(page)) === 0, "A reused callback published a session.");
  requireCondition(backendRequests.length === 0, "A reused callback reached the Backend.");
  requireCondition(!(await hasOwnedStorage(page)), "A reused callback restored transaction state.");
});
