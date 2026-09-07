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
const END_SESSION_URL = `${AUTHORITY}/protocol/openid-connect/logout`;
const POST_LOGOUT_REDIRECT_URI = `${APP_ORIGIN}/`;
const SIGN_OUT_FAILURE_MESSAGE =
  "Sign-out could not be completed. You are signed out of this browser.";
const TRANSACTION_PREFIX = "finguardops.oidc.transaction.";
const USER_PREFIX = "finguardops.oidc.user.";
const USERNAME = "local-fds-analyst";
const BACKEND_AUDIENCE = "finguardops-backend-api";
const CANONICAL_UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const BACKEND_ORIGIN = "http://localhost:8080";
const TRANSACTION_LIST_PATH = "/api/v1/transactions";

/**
 * The query names each relayable endpoint may carry, from
 * `TransactionQueryValidator` by way of the endpoint registry.
 *
 * A path absent from this map accepts no query at all, and a name absent from
 * its list is refused before anything is written to the Backend. The list is
 * membership only - order and encoding are decided by the canonical builder in
 * the application and asserted against the exact targets below, not re-derived
 * here.
 */
const RELAYABLE_QUERY_NAMES: ReadonlyMap<string, readonly string[]> = new Map([
  [
    TRANSACTION_LIST_PATH,
    [
      "occurredAtFrom",
      "occurredAtTo",
      "transactionType",
      "processingStatus",
      "externalCustomerRef",
      "accountRef",
      "page",
      "size",
      "sort",
    ] as readonly string[],
  ],
]);

/**
 * The synthetic customer reference this suite types into the filter.
 *
 * Fixed, so the expected request target is a constant rather than something
 * rebuilt from the value at assertion time; padded with spaces and mixed in
 * case, so a trim or a case fold anywhere between the field and the Backend
 * changes the target and fails. It is never interpolated into a message: every
 * assertion below reports a fixed sentence.
 */
const E2E_CUSTOMER_REF = " E2E-Reference-01 ";

/** `page`, `size` and `sort`, in the order and encoding the builder emits. */
const INITIAL_TRANSACTION_TARGET = `${TRANSACTION_LIST_PATH}?page=0&size=20&sort=occurredAt%2Cdesc`;

/**
 * The same three, plus the two filters the analyst applies, in the registry's
 * declared order. The reference travels as `+`-encoded spaces around the exact
 * characters typed.
 */
const APPLIED_TRANSACTION_TARGET =
  `${TRANSACTION_LIST_PATH}?processingStatus=HELD&externalCustomerRef=+E2E-Reference-01+` +
  "&page=0&size=20&sort=occurredAt%2Cdesc";
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
  /**
   * The request target written onto the Backend socket, byte for byte: the
   * path and, where there is one, the query. Recorded from what the relay
   * actually sent rather than from the browser request, so an observation can
   * never describe a URL the Backend was not asked for.
   */
  readonly target: string;
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
  // Keycloak compiles its login theme on the first request that asks for it,
  // and the first navigation of the suite is that request. The default
  // expectation timeout is about the application being wrong, not about an
  // Authorization Server that has been up for seconds rather than minutes, so
  // this one wait is given room for that first render.
  await expect(page.locator("#username")).toBeVisible({ timeout: 30_000 });
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

/** What the Backend actually answered: its status, and its body verbatim. */
interface RelayedResponse {
  readonly status: number;
  readonly body: string;
  /** The request target that produced it. The same string the socket carried. */
  readonly target: string;
}

/** A relayed body this suite will accept into the browser. */
const MAX_RELAYED_BODY_BYTES = 2 * 1024 * 1024;

/**
 * Decodes `Transfer-Encoding: chunked`, which is what Spring Boot answers a
 * JSON GET with once the response is streamed rather than buffered.
 *
 * Done over bytes rather than over a decoded string: a chunk size counts octets,
 * and a body carrying any non-ASCII character would be cut in the wrong place by
 * a UTF-16 slice.
 */
function decodeChunkedBody(raw: Buffer): Buffer {
  const parts: Buffer[] = [];
  let offset = 0;
  for (;;) {
    const lineEnd = raw.indexOf("\r\n", offset, "latin1");
    requireCondition(lineEnd !== -1, "The Backend relay returned an unterminated chunk header.");
    const header = raw.toString("latin1", offset, lineEnd).split(";")[0].trim();
    requireCondition(/^[0-9a-fA-F]{1,8}$/.test(header), "The Backend relay returned an invalid chunk size.");
    const size = Number.parseInt(header, 16);
    if (size === 0) {
      return Buffer.concat(parts);
    }
    const start = lineEnd + 2;
    requireCondition(start + size <= raw.length, "The Backend relay returned a truncated chunk.");
    parts.push(raw.subarray(start, start + size));
    offset = start + size + 2;
  }
}

/**
 * Splits a raw HTTP/1.1 response into its status and its body.
 *
 * The body is parsed here rather than in the shell for one reason: the shell
 * half of this relay writes a request and copies bytes back, and every decision
 * about what those bytes mean belongs where it can be read and bounded.
 */
function parseRelayedResponse(raw: Buffer): Omit<RelayedResponse, "target"> {
  requireCondition(raw.length <= MAX_RELAYED_BODY_BYTES, "The Backend relay response was too large.");
  const separator = raw.indexOf("\r\n\r\n", 0, "latin1");
  requireCondition(separator !== -1, "The Backend relay returned no header boundary.");
  const head = raw.toString("latin1", 0, separator).split("\r\n");
  const status = parseHttpStatus(head[0]);
  const chunked = head
    .slice(1)
    .some((line) => /^transfer-encoding:\s*chunked\s*$/i.test(line));
  const rest = raw.subarray(separator + 4);
  return { status, body: (chunked ? decodeChunkedBody(rest) : rest).toString("utf8") };
}

/**
 * The exact request target this suite is willing to write onto the Backend
 * socket, decided before anything is written.
 *
 * Relaying the path alone would make every query assertion vacuous: the screen
 * could stop sending `page`, `size`, `sort` or a filter entirely and the
 * Backend would still answer its default list with a 200. So the query travels
 * too - and because it travels, it is bounded here rather than trusted:
 *
 * - the destination is the one Backend origin, carrying no userinfo and no
 *   fragment, and the path is one this suite recognises;
 * - a query is accepted only on a `GET` to an endpoint declared to take one,
 *   and only with that endpoint's own parameter names, each appearing once;
 * - re-serialising the parsed pairs has to reproduce the received bytes, so a
 *   non-canonical, double or partial encoding is refused rather than relayed;
 * - what is left is printable ASCII with no whitespace, which is what makes it
 *   safe as the target of a request line.
 *
 * Every refusal is a fixed sentence. No part of a query, and no credential,
 * appears in one.
 */
function resolveRelayTarget(request: PlaywrightRequest): string {
  const url = new URL(request.url());
  requireCondition(url.origin === BACKEND_ORIGIN, "An unexpected Backend origin was requested.");
  requireCondition(url.username === "" && url.password === "", "A Backend request carried userinfo.");
  requireCondition(url.hash === "", "A Backend request carried a fragment.");
  requireCondition(/^[A-Z]+$/.test(request.method()), "An invalid Backend method was requested.");
  requireCondition(/^\/api\/v1\/[a-z0-9\-/]+$/.test(url.pathname), "An invalid Backend path was requested.");
  if (url.search === "") {
    return url.pathname;
  }

  requireCondition(request.method() === "GET", "A Backend query was requested on a non-GET method.");
  const approved = RELAYABLE_QUERY_NAMES.get(url.pathname);
  requireCondition(approved !== undefined, "A Backend query was requested on an endpoint that takes none.");
  const parsed = new URLSearchParams(url.search);
  const names = [...parsed.keys()];
  requireCondition(new Set(names).size === names.length, "A Backend query repeated a parameter name.");
  requireCondition(
    names.every((name) => approved.includes(name)),
    "A Backend query carried a parameter this endpoint does not declare.",
  );
  const canonical = parsed.toString();
  requireCondition(canonical === url.search.slice(1), "A Backend query was not canonically encoded.");

  const target = `${url.pathname}?${canonical}`;
  requireCondition(
    /^[\u0021-\u007e]+$/.test(target),
    "A Backend request target carried a character this relay will not write.",
  );
  return target;
}

function relayToBackend(request: PlaywrightRequest): RelayedResponse {
  requireNonBlankString(COMPOSE_PROJECT, "The dedicated Compose project was not configured.");
  const target = resolveRelayTarget(request);

  const credential = request.headers()["authorization"] ?? "";
  requireCondition(!credential.includes("\r") && !credential.includes("\n"), "An invalid credential header was refused.");
  const body = request.postData() ?? "";
  requireCondition(!body.includes("\r") && !body.includes("\n"), "A multiline E2E body was refused.");

  const script = [
    "set -euo pipefail",
    "IFS= read -r method",
    // The whole request target - path and query - as one already-validated,
    // whitespace-free token, read as data rather than assembled into a command
    // string. Compose is still invoked as an argument vector.
    "IFS= read -r target",
    "IFS= read -r credential",
    "IFS= read -r body",
    "exec 3<>/dev/tcp/127.0.0.1/8080",
    "printf '%s %s HTTP/1.1\\r\\nHost: localhost\\r\\nAccept: application/json\\r\\nConnection: close\\r\\n' \"$method\" \"$target\" >&3",
    "if [[ -n $credential ]]; then printf 'Authorization: %s\\r\\n' \"$credential\" >&3; fi",
    "if [[ -n $body ]]; then printf 'Content-Type: application/json\\r\\nContent-Length: %s\\r\\n' \"${#body}\" >&3; fi",
    "printf '\\r\\n%s' \"$body\" >&3",
    // The whole response, byte for byte. The status line alone was enough while
    // every assertion was about a status code; a screen that renders what the
    // Backend actually returned needs the body it actually returned, and
    // inventing one here would make this an assertion about the relay.
    "cat <&3",
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
      // No `encoding`, so stdout stays a Buffer: chunk sizes are octet counts
      // and a decoded string cannot be sliced by them.
      maxBuffer: MAX_RELAYED_BODY_BYTES,
      input: `${request.method()}\n${target}\n${credential}\n${body}\n`,
      // A Backend answer relayed inside an already running container. This is
      // the bound the run holds Compose exec to; exceeding it is a failure of
      // this relay, reported as the same fixed sentence as any other, and never
      // widened to absorb a slow Backend.
      timeout: 15_000,
      windowsHide: true,
    },
  );
  requireCondition(result.status === 0, "The Backend relay failed.");
  return { ...parseRelayedResponse(result.stdout), target };
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
    const relayed = relayToBackend(request);
    // Recorded from the relay's own target, so what this suite observes and
    // what the Backend was asked for cannot drift into two different things.
    observations.push({
      method: request.method(),
      pathname: relayed.target.split("?")[0],
      target: relayed.target,
      status: relayed.status,
    });
    await route.fulfill({
      status: relayed.status,
      contentType: "application/json",
      headers: { "access-control-allow-origin": APP_ORIGIN },
      // What Backend answered, unchanged. An empty body becomes `{}` so a
      // no-content answer is still parseable JSON rather than a transport error
      // this suite would then be measuring instead of the application.
      body: relayed.body === "" ? "{}" : relayed.body,
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
  requireCondition(
    caseListResult === "ok",
    // The observed value is an error *name* and nothing else, so naming it here
    // says why the request failed without carrying a body, claim or credential.
    `The authenticated case-list request failed: ${caseListResult}`,
  );

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

async function expectSignOutFailure(page: Page): Promise<void> {
  await expect(page.getByRole("status", { name: "Authentication status" })).toHaveText(
    SIGN_OUT_FAILURE_MESSAGE,
  );
}

/**
 * The real RP-initiated logout, end to end: a real USER session, the real
 * Keycloak end-session endpoint, the real post-logout redirect back to the
 * application root, and the one-time logout transaction that lands with it.
 */
test("real USER sign-out ends the Keycloak session and cannot be replayed", async ({ page }) => {
  const password = readUserPassword();
  const consoleMessages: string[] = [];
  const tokenGrantTypes: string[] = [];
  const backendRequests: string[] = [];
  const endSessionRequests: string[] = [];
  const postLogoutCallbacks: string[] = [];
  page.on("console", (message) => consoleMessages.push(message.text()));
  page.on("request", (request) => {
    const url = request.url();
    if (url === END_SESSION_URL || url.startsWith(`${END_SESSION_URL}?`)) {
      endSessionRequests.push(url);
    }
    if (url.startsWith(`${APP_ORIGIN}/?`) && request.method() === "GET") {
      postLogoutCallbacks.push(url);
    }
    if (url === TOKEN_URL && request.method() === "POST") {
      tokenGrantTypes.push(new URLSearchParams(request.postData() ?? "").get("grant_type") ?? "");
    }
    if (url.startsWith("http://localhost:8080/")) {
      backendRequests.push(request.method());
    }
  });

  await beginLogin(page, password);
  const tokenResponsePromise = page.waitForResponse(
    (response) => response.url() === TOKEN_URL && response.request().method() === "POST",
  );
  await submitLogin(page);
  const tokens = parseTokenResponse(await (await tokenResponsePromise).json());
  await expect(page.getByLabel("Authentication status")).toContainText("Signed in as");
  requireCondition((await publicationCount(page)) === 1, "The session was not published once.");

  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL(POST_LOGOUT_REDIRECT_URI);

  // Exactly one end-session request, to the exact endpoint of the configured
  // issuer, carrying exactly the three parameters this client sends.
  requireCondition(endSessionRequests.length === 1, "The end-session endpoint request count differed.");
  const endSession = new URL(endSessionRequests[0]);
  requireCondition(
    `${endSession.origin}${endSession.pathname}` === END_SESSION_URL,
    "The end-session destination differed.",
  );
  requireCondition(endSession.hash === "", "The end-session request carried a fragment.");
  requireCondition(endSession.username === "" && endSession.password === "", "The end-session request carried userinfo.");
  const endSessionKeys = [...endSession.searchParams.keys()].sort();
  requireCondition(
    endSessionKeys.length === 3 &&
      endSessionKeys[0] === "id_token_hint" &&
      endSessionKeys[1] === "post_logout_redirect_uri" &&
      endSessionKeys[2] === "state",
    "The end-session parameter set differed.",
  );
  requireCondition(
    endSession.searchParams.get("post_logout_redirect_uri") === POST_LOGOUT_REDIRECT_URI,
    "The post-logout redirect URI was not the exact allowlisted root.",
  );
  requireCondition(
    endSession.searchParams.get("id_token_hint") === tokens.idToken,
    "The end-session hint was not the ID token the library validated.",
  );
  const logoutState = endSession.searchParams.get("state") ?? "";
  requireNonBlankString(logoutState, "The logout state was blank.");
  requireCondition(/^[A-Za-z0-9._~-]{1,256}$/.test(logoutState), "The logout state shape was invalid.");

  // The response landed on the exact application root, carrying only that state.
  requireCondition(postLogoutCallbacks.length === 1, "The post-logout callback count differed.");
  const postLogoutCallbackUrl = postLogoutCallbacks[0];
  const callback = new URL(postLogoutCallbackUrl);
  requireCondition(callback.origin === APP_ORIGIN && callback.pathname === "/", "The post-logout callback address differed.");
  requireCondition([...callback.searchParams.keys()].join(",") === "state", "The post-logout callback parameter set differed.");
  requireCondition(callback.searchParams.get("state") === logoutState, "The post-logout callback state differed.");

  // The address bar was cleaned, the local session is gone and the one-time
  // logout transaction was consumed.
  requireCondition(page.url() === POST_LOGOUT_REDIRECT_URI, "The browser did not settle on the exact application root.");
  await expect(page.getByRole("button", { name: "Sign in" })).toBeVisible();
  await expect(page.getByLabel("Authentication status")).not.toContainText("Signed in");
  requireCondition(!(await hasOwnedStorage(page)), "Sign-out retained OIDC transaction or user state.");
  requireCondition((await publicationCount(page)) === 0, "The signed-out page published a session.");
  requireCondition(backendRequests.length === 0, "Sign-out reached the Backend.");
  requireCondition(
    tokenGrantTypes.length === 1 && tokenGrantTypes[0] === "authorization_code",
    "Sign-out attempted a refresh grant or a silent renewal.",
  );

  const sensitive = [password, tokens.accessToken, tokens.idToken, logoutState];
  requireCondition(!(await browserContainsAny(page, sensitive)), "A credential or state survived sign-out.");
  requireCondition(
    !consoleMessages.some((message) => sensitive.some((value) => value !== "" && message.includes(value))),
    "A credential or state reached the browser console.",
  );

  // Replaying the consumed response is refused, and changes nothing.
  await page.goto(postLogoutCallbackUrl);
  await expectSignOutFailure(page);
  requireCondition(page.url() === POST_LOGOUT_REDIRECT_URI, "The replayed callback left the address bar dirty.");
  requireCondition((await publicationCount(page)) === 0, "A replayed logout callback published a session.");
  requireCondition(!(await hasOwnedStorage(page)), "A replayed logout callback restored OIDC storage.");
  requireCondition(endSessionRequests.length === 1, "A replayed logout callback started another end-session request.");
  requireCondition(tokenGrantTypes.length === 1, "A replayed logout callback exchanged a grant.");
  requireCondition(backendRequests.length === 0, "A replayed logout callback reached the Backend.");
  requireCondition(!(await browserContainsAny(page, sensitive)), "A replayed logout callback exposed a credential or state.");
  // Rendered text, not `documentElement.textContent`. The dev server injects the
  // application stylesheet as a `<style>` element, whose text content is CSS
  // rather than anything a person reads - and a class name such as
  // `.notice--error` would otherwise look like a provider error on screen.
  const bodyText = await page.evaluate(() => document.body.innerText);
  requireCondition(!bodyText.includes("error"), "A provider error surfaced in the sign-out message.");

  // The Keycloak SSO session really ended: signing in again asks for credentials
  // instead of silently reusing the session that was just closed.
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator("#username")).toBeVisible();
  requireCondition(
    new URL(page.url()).origin === new URL(AUTHORITY).origin,
    "The second sign-in did not reach the Authorization Server.",
  );
  requireCondition(
    !consoleMessages.some((message) => sensitive.some((value) => value !== "" && message.includes(value))),
    "A credential or state reached the console during the second sign-in.",
  );
});

/**
 * A logout response that is not exactly the one shape this application accepts
 * never reaches the library, never consumes anything and never signs anyone in.
 */
test("a tampered root logout response is refused without touching the library", async ({ page }) => {
  const password = readUserPassword();
  const tokenGrantTypes: string[] = [];
  const endSessionRequests: string[] = [];
  page.on("request", (request) => {
    const url = request.url();
    if (url === END_SESSION_URL || url.startsWith(`${END_SESSION_URL}?`)) {
      endSessionRequests.push(url);
    }
    if (url === TOKEN_URL && request.method() === "POST") {
      tokenGrantTypes.push(new URLSearchParams(request.postData() ?? "").get("grant_type") ?? "");
    }
  });

  await beginLogin(page, password);
  await submitLogin(page);
  await expect(page.getByLabel("Authentication status")).toContainText("Signed in as");
  await page.getByRole("button", { name: "Sign out" }).click();
  await page.waitForURL(POST_LOGOUT_REDIRECT_URI);
  requireCondition(endSessionRequests.length === 1, "The end-session endpoint request count differed.");
  const state = new URL(endSessionRequests[0]).searchParams.get("state") ?? "";
  requireNonBlankString(state, "The logout state was blank.");

  const grantsBefore = tokenGrantTypes.length;
  for (const search of [
    `?state=${state}&error=access_denied&error_description=provider-detail`,
    `?state=${state}&code=injected-code`,
    `?state=${state}&state=${state}`,
    "?state=",
    `?state=${state}%3Bhttps%3A%2F%2Fevil.example`,
  ]) {
    await page.goto(`${APP_ORIGIN}/${search}`);
    await expectSignOutFailure(page);
    requireCondition(page.url() === POST_LOGOUT_REDIRECT_URI, "A refused response left the address bar dirty.");
    requireCondition((await publicationCount(page)) === 0, "A refused response published a session.");
    requireCondition(!(await hasOwnedStorage(page)), "A refused response wrote OIDC storage.");
    requireCondition(
      !(await browserContainsAny(page, [state, "provider-detail", "access_denied", "injected-code"])),
      "A refused response exposed provider payload.",
    );
  }
  requireCondition(tokenGrantTypes.length === grantsBefore, "A refused response exchanged a grant.");
  requireCondition(endSessionRequests.length === 1, "A refused response started another end-session request.");
});

/**
 * The three widths the console is designed against.
 *
 * Checked in one browser session rather than three, because the thing worth
 * proving is that the same live screen re-lays out correctly - not that three
 * separate loads each render something.
 */
const CONSOLE_VIEWPORTS: readonly {
  readonly width: number;
  readonly height: number;
  readonly railWidth: number;
  readonly filterColumns: number;
}[] = [
  { width: 1440, height: 900, railWidth: 240, filterColumns: 4 },
  { width: 1280, height: 800, railWidth: 208, filterColumns: 3 },
  { width: 1024, height: 768, railWidth: 180, filterColumns: 2 },
];

async function measuredRailWidth(page: Page): Promise<number> {
  const box = await page.locator("header.rail").boundingBox();
  requireCondition(box !== null, "The navigation rail was not laid out.");
  return Math.round(box.width);
}

async function filterGridColumnCount(page: Page): Promise<number> {
  return page.evaluate(() => {
    const grid = document.querySelector(".filters__grid");
    if (grid === null) {
      return 0;
    }
    return window
      .getComputedStyle(grid)
      .gridTemplateColumns.split(" ")
      .filter((track) => track !== "").length;
  });
}

/**
 * A snapshot of everything about this document that outlives a keystroke.
 *
 * Compared before and after Apply: a filter held in component memory changes
 * none of it.
 */
async function navigationState(page: Page): Promise<string> {
  return page.evaluate(() =>
    JSON.stringify({
      href: window.location.href,
      entries: window.history.length,
      state: window.history.state,
    }),
  );
}

/**
 * Every place in the document that carries a given value, apart from the one
 * place it belongs: the visible control the analyst typed it into.
 *
 * Named sites rather than a boolean, and never the value itself, so a failure
 * says where the leak was without repeating what leaked. The address bar,
 * history state, both Web Storages, the document title, every text node, every
 * attribute of every element and the current value of every other form control
 * are all in scope - which is what makes a `title`, a `data-*` or a hidden
 * mirror of the filter a failure rather than an invisible detail.
 */
async function referenceLeakSites(
  page: Page,
  value: string,
  allowedControlId: string,
): Promise<string[]> {
  return page.evaluate(
    ({ needle, controlId }) => {
      const sites: string[] = [];
      if (window.location.href.includes(needle)) {
        sites.push("address-bar");
      }
      if (JSON.stringify(window.history.state ?? null).includes(needle)) {
        sites.push("history-state");
      }
      if (document.title.includes(needle)) {
        sites.push("title");
      }
      for (const [name, storage] of [
        ["local-storage", window.localStorage],
        ["session-storage", window.sessionStorage],
      ] as const) {
        for (let index = 0; index < storage.length; index += 1) {
          const key = storage.key(index) ?? "";
          if (key.includes(needle) || (storage.getItem(key) ?? "").includes(needle)) {
            sites.push(name);
            break;
          }
        }
      }
      const allowed = document.getElementById(controlId);
      if (allowed === null) {
        sites.push("missing-control");
      }
      for (const element of Array.from(document.querySelectorAll("*"))) {
        if (element === allowed) {
          continue;
        }
        for (const attribute of Array.from(element.attributes)) {
          if (attribute.value.includes(needle)) {
            sites.push(`attribute:${element.tagName.toLowerCase()}/${attribute.name}`);
          }
        }
        for (const node of Array.from(element.childNodes)) {
          if ((node.nodeValue ?? "").includes(needle)) {
            sites.push(`text:${element.tagName.toLowerCase()}`);
          }
        }
        if (
          (element instanceof HTMLInputElement ||
            element instanceof HTMLTextAreaElement ||
            element instanceof HTMLSelectElement) &&
          element.value.includes(needle)
        ) {
          sites.push("other-control");
        }
      }
      return [...new Set(sites)];
    },
    { needle: value, controlId: allowedControlId },
  );
}

async function documentOverflowsHorizontally(page: Page): Promise<boolean> {
  return page.evaluate(
    () => document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
  );
}

test("a real USER reaches the transaction console over the real Backend", async ({ page }) => {
  const password = readUserPassword();
  const consoleMessages: string[] = [];
  page.on("console", (message) => consoleMessages.push(message.text()));
  const backend = await installBackendRelay(page);

  const tokenResponsePromise = page.waitForResponse(
    (response) => response.url() === TOKEN_URL && response.request().method() === "POST",
  );
  await beginLogin(page, password);
  await submitLogin(page);
  const tokens = parseTokenResponse(await (await tokenResponsePromise).json());
  requireTokenClaims(tokens);
  await expect(page.getByLabel("Authentication status")).toContainText("Signed in as");

  // The capability navigation, decided from the real role claim of a real
  // Keycloak session rather than from a fixture.
  const transactionsLink = page.getByRole("link", { name: "Transactions" });
  await expect(transactionsLink).toBeVisible();
  await transactionsLink.click();
  await page.waitForFunction((expected) => window.location.href === expected, `${APP_ORIGIN}/transactions`);
  await expect(page.getByRole("heading", { name: "Transactions", level: 2 })).toBeVisible();
  await expect(transactionsLink).toHaveAttribute("aria-current", "page");

  // The opening query, answered by the real Spring Boot endpoint.
  const results = page.getByRole("main").getByRole("status");
  await expect(results).not.toContainText("Loading transactions", { timeout: 15_000 });
  await expect(page.getByRole("alert")).toHaveCount(0);

  const listRequests = backend.filter(
    (entry) => entry.method === "GET" && entry.pathname === TRANSACTION_LIST_PATH,
  );
  requireCondition(listRequests.length === 1, "The transaction list was not requested exactly once.");
  requireCondition(listRequests[0].status === 200, "The real transaction list request did not return 200.");
  // The request target the Backend was actually asked for, not the one the
  // browser built: page 0, twenty rows, newest first, no filter, one value per
  // name and nothing else, in the canonical order and encoding the query
  // builder emits. Compared as one fixed string, so a failure names the
  // contract rather than printing the query.
  requireCondition(
    listRequests[0].target === INITIAL_TRANSACTION_TARGET,
    "The opening transaction query that reached the Backend was not the exact default query.",
  );

  // Whatever this runtime holds, the screen converges on one of exactly two
  // states and never on a partial or error one.
  const summary = (await results.textContent()) ?? "";
  const showingRows = /^Showing \d+-\d+ of \d+ transactions\.$/.test(summary.trim());
  const emptyResult = summary.trim() === "No transactions found.";
  requireCondition(
    showingRows || emptyResult,
    `The transaction screen did not settle on a result state: ${summary.trim()}`,
  );
  if (showingRows) {
    await expect(page.getByRole("table")).toBeVisible();
    // Every displayed instant states its zone and carries the untouched UTC
    // value the Backend sent.
    const firstTime = page.locator("tbody time").first();
    await expect(firstTime).toContainText("KST");
    const machineReadable = await firstTime.getAttribute("datetime");
    requireCondition(
      machineReadable !== null && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?Z$/.test(machineReadable),
      "A rendered time carried no UTC machine-readable value.",
    );
  } else {
    await expect(page.getByText("No transactions match these filters")).toBeVisible();
  }

  // Nothing retries on its own: the count is unchanged after the screen has
  // been sitting there.
  await page.waitForTimeout(1_000);
  requireCondition(
    backend.filter((entry) => entry.method === "GET" && entry.pathname === "/api/v1/transactions").length === 1,
    "The transaction screen retried or polled on its own.",
  );

  // The design widths, on the live screen.
  for (const viewport of CONSOLE_VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await expect(page.locator("header.rail")).toBeVisible();
    await expect(transactionsLink).toBeVisible();
    const railWidth = await measuredRailWidth(page);
    requireCondition(
      railWidth === viewport.railWidth,
      `The navigation rail was ${String(railWidth)}px at ${String(viewport.width)}px.`,
    );
    const columns = await filterGridColumnCount(page);
    requireCondition(
      columns === viewport.filterColumns,
      `The filter grid had ${String(columns)} columns at ${String(viewport.width)}px.`,
    );
    requireCondition(
      !(await documentOverflowsHorizontally(page)),
      `The page scrolled horizontally at ${String(viewport.width)}px.`,
    );
  }
  await page.setViewportSize({ width: 1440, height: 900 });

  // The keyboard path into the screen.
  //
  // Stated as document order rather than as "press Tab once": Chromium keeps a
  // sequential focus navigation starting point from the last interaction, so a
  // single Tab after clicking the rail link would measure that starting point
  // rather than the page. What the skip link has to be is the first focusable
  // element in the document, and it has to reach the main landmark.
  const firstFocusableText = await page.evaluate(() => {
    const candidates = document.querySelectorAll<HTMLElement>(
      'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])',
    );
    return candidates.length === 0 ? "" : (candidates[0].textContent ?? "");
  });
  requireCondition(
    firstFocusableText === "Skip to main content",
    `The skip link was not the first focusable element: ${firstFocusableText}`,
  );
  await page.getByRole("link", { name: "Skip to main content" }).press("Enter");
  const skipTarget = await page.evaluate(() => ({
    hash: window.location.hash,
    landmark: document.getElementById("main-content")?.tagName ?? "",
  }));
  requireCondition(skipTarget.hash === "#main-content", "The skip link did not move to the main landmark.");
  requireCondition(skipTarget.landmark === "MAIN", "The skip link target was not the main landmark.");

  // Applying a filter is one more real request, carrying the filters, and
  // nothing else.
  const navigationBeforeApply = await navigationState(page);
  await page.getByLabel("Processing status").selectOption("HELD");
  await page.getByLabel("Customer reference").fill(E2E_CUSTOMER_REF);
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(results).not.toContainText("Applying filters", { timeout: 15_000 });
  const filtered = backend.filter(
    (entry) => entry.method === "GET" && entry.pathname === TRANSACTION_LIST_PATH,
  );
  requireCondition(filtered.length === 2, "Applying a filter did not send exactly one request.");
  requireCondition(filtered[1].status === 200, "The filtered transaction request did not return 200.");
  // Both filters, the page reset to 0, the unchanged size and sort, each name
  // once and nothing extra - and the reference exactly as typed, its
  // surrounding spaces and its capitalisation intact. A trim, a case fold or a
  // dropped filter anywhere between the field and the socket changes this
  // string.
  requireCondition(
    filtered[1].target === APPLIED_TRANSACTION_TARGET,
    "The applied transaction query that reached the Backend was not the exact filtered query.",
  );
  await expect(page.getByRole("alert")).toHaveCount(0);

  // And still nothing retries: applying a filter sent one request, not one and
  // a repeat of it.
  await page.waitForTimeout(1_000);
  requireCondition(
    backend.filter((entry) => entry.method === "GET" && entry.pathname === TRANSACTION_LIST_PATH)
      .length === 2,
    "The transaction screen retried the filtered query on its own.",
  );

  // No filter value and no credential reaches the address bar, Web Storage or
  // the console.
  // Compared part by part rather than as one string: the skip link above left a
  // `#main-content` fragment, and what this asserts is that no filter value -
  // and no query string at all - reached the address bar.
  const addressBar = new URL(page.url());
  requireCondition(
    addressBar.origin === APP_ORIGIN &&
      addressBar.pathname === "/transactions" &&
      addressBar.search === "",
    "A filter reached the address bar.",
  );
  // Applying a filter is not navigation: the address, the history depth and the
  // history state are the ones from before Apply, so the filter is held in
  // component memory and nowhere a reload or a Back would reach it.
  requireCondition(
    (await navigationState(page)) === navigationBeforeApply,
    "Applying a filter changed the browser location or history.",
  );
  // The reference belongs in the field the analyst typed it into and in the
  // Backend query asserted above. Everywhere else it is a leak. The sites are
  // named; the value is not.
  const leaks = await referenceLeakSites(page, E2E_CUSTOMER_REF, "filter-customer-ref");
  requireCondition(
    leaks.length === 0,
    `A reference filter was recorded outside the field it was typed into: ${leaks.join(", ")}`,
  );
  requireCondition(
    !consoleMessages.some((message) => message.includes(E2E_CUSTOMER_REF)),
    "A reference filter reached the browser console.",
  );
  const sensitive = [password, tokens.accessToken, tokens.idToken];
  requireCondition(!(await browserContainsAny(page, sensitive)), "A credential reached DOM, URL, or Web Storage.");
  requireCondition(
    !consoleMessages.some((message) => sensitive.some((value) => value !== "" && message.includes(value))),
    "A credential reached the browser console.",
  );
  requireCondition(
    backend.filter((entry) => entry.method !== "GET").length === 0,
    "The transaction screen sent a business mutation.",
  );
});
