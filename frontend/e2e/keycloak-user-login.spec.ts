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
/**
 * The canonical lowercase UUID v4 this suite recognises, as a pattern fragment.
 *
 * One source for the identifier shape, so the value checks below and the
 * relay's address descriptors cannot drift apart. An uppercase identifier, a
 * version other than 4, an RFC variant outside `[89ab]` and an unhyphenated
 * string are each a different string, and each is refused in both places.
 */
const CANONICAL_UUID_V4_PATTERN =
  "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
const CANONICAL_UUID_V4 = new RegExp(`^${CANONICAL_UUID_V4_PATTERN}$`);
const BACKEND_ORIGIN = "http://localhost:8080";
const TRANSACTION_LIST_PATH = "/api/v1/transactions";
const CASE_LIST_PATH = "/api/v1/cases";

/**
 * A canonical lowercase UUID v4 that names no transaction.
 *
 * Synthetic on purpose. This runtime holds no seeded ledger row, so the detail
 * screen's real-Backend evidence is the 404 boundary rather than a record: the
 * request is authorized, reaches Spring Boot, and is answered with "no such
 * transaction". The 200 state is proved in the component and hook tests against
 * the typed API contract, and is deliberately not simulated here - an API mock
 * would not be evidence of anything this suite exists to show.
 */
const SYNTHETIC_TRANSACTION_ID = "e2e00000-0000-4000-8000-000000000e2e";

/**
 * Every Backend address this suite is willing to write onto the socket.
 *
 * A closed list of exact endpoints, and deliberately not a path syntax. That
 * `/api/v1/...` is well-formed says nothing about whether this suite may read
 * it: the console's screens reach exactly six read addresses and one
 * authorization probe. The six are the two collections, `/api/v1/transactions`
 * and `/api/v1/cases`; transaction and case detail at one canonical lowercase
 * UUID v4 segment; and that case's notes and audit log. The two detail reads
 * carry no query. Notes and audit each carry their own closed page/size/sort
 * contract. Everything else under `/api/v1/**` - a case's status, its assignee, its
 * related transactions, its current AI report, a `GET` of its resolution, any
 * other unapproved suffix, a case status or assignee write, a note create, an
 * endpoint that does not exist yet - is refused here rather than relayed. The
 * list grows when a screen's E2E really needs an address and not before: an
 * endpoint admitted ahead of the test that needs it is an address this suite
 * can reach for no stated reason.
 *
 * Each descriptor carries the one method its address may be reached by, the
 * exact address it recognises, and the query names that address may carry -
 * `null` meaning it may carry none at all. Nothing is shared between
 * descriptors, so a case filter cannot be relayed to the ledger endpoint or the
 * other way round, and a method allowed on one address is not allowed on
 * another.
 */
interface RelayableEndpoint {
  /** Reads as a label in the list; never printed into a failure. */
  readonly name: string;
  /** The one HTTP method this address may be reached by. */
  readonly method: string;
  /** Whether an exact request path names this endpoint. */
  readonly matches: (pathname: string) => boolean;
  /**
   * The query names this endpoint declares, from `TransactionQueryValidator`
   * and `FraudCaseQueryValidator` by way of the endpoint registry, or `null`
   * when the endpoint takes no query at all.
   *
   * Membership only - order and encoding are decided by the canonical builder
   * in the application and asserted against the exact targets below, never
   * re-derived here.
   */
  readonly queryNames: readonly string[] | null;
  /** Endpoint-specific meaning checks after canonical parsing. */
  readonly acceptsQuery?: (query: URLSearchParams) => boolean;
}

/** `/api/v1/transactions/{canonical lowercase UUID v4}`, and nothing after it. */
const TRANSACTION_DETAIL_PATH = new RegExp(
  `^${TRANSACTION_LIST_PATH}/${CANONICAL_UUID_V4_PATTERN}$`,
);

/** `/api/v1/cases/{canonical lowercase UUID v4}`, and nothing after it. */
const CASE_DETAIL_PATH = new RegExp(`^${CASE_LIST_PATH}/${CANONICAL_UUID_V4_PATTERN}$`);

/** `/api/v1/cases/{canonical lowercase UUID v4}/audit-logs`, exactly. */
const CASE_AUDIT_PATH = new RegExp(
  `^${CASE_LIST_PATH}/${CANONICAL_UUID_V4_PATTERN}/audit-logs$`,
);

/** `/api/v1/cases/{canonical lowercase UUID v4}/notes`, exactly. */
const CASE_NOTES_PATH = new RegExp(
  `^${CASE_LIST_PATH}/${CANONICAL_UUID_V4_PATTERN}/notes$`,
);

function acceptsAuditQuery(query: URLSearchParams): boolean {
  const page = query.get("page");
  const size = query.get("size");
  const sort = query.get("sort");
  const pageOk =
    page === null ||
    (/^(?:0|[1-9][0-9]*)$/.test(page) && BigInt(page) <= 2_147_483_647n);
  const sizeOk = size === null || /^(?:[1-9]|[1-9][0-9]|100)$/.test(size);
  const sortOk = sort === null || sort === "changedAt,asc" || sort === "changedAt,desc";
  return pageOk && sizeOk && sortOk;
}

function acceptsNotesQuery(query: URLSearchParams): boolean {
  const page = query.get("page");
  const size = query.get("size");
  const sort = query.get("sort");
  const pageOk =
    page === null ||
    (/^(?:0|[1-9][0-9]*)$/.test(page) && BigInt(page) <= 2_147_483_647n);
  const sizeOk = size === null || /^(?:[1-9]|[1-9][0-9]|100)$/.test(size);
  const sortOk = sort === null || sort === "createdAt,asc" || sort === "createdAt,desc";
  return pageOk && sizeOk && sortOk;
}

/** `/api/v1/cases/{canonical lowercase UUID v4}/resolution`, and nothing else. */
const CASE_RESOLUTION_PROBE_PATH = new RegExp(
  `^${CASE_LIST_PATH}/${CANONICAL_UUID_V4_PATTERN}/resolution$`,
);

/**
 * The six read addresses this suite relays, and only these six.
 *
 * A `GET` carrying no query is not a lesser request. It opens the same socket
 * and reaches the same Spring Boot handler as one carrying a query, so it
 * passes the same exact-address check. Canonical case detail, investigation
 * notes and audit-history `GET` addresses are all approved reads. Notes alone
 * declare page, size and `createdAt` sort; their POST/PATCH/PUT/DELETE forms,
 * trailing or extra paths, non-canonical identifiers, and mixed endpoint query
 * names remain absent from this list and are refused.
 */
const RELAYABLE_READ_PATHS: readonly RelayableEndpoint[] = [
  {
    name: "transaction-list",
    method: "GET",
    matches: (pathname) => pathname === TRANSACTION_LIST_PATH,
    queryNames: [
      "occurredAtFrom",
      "occurredAtTo",
      "transactionType",
      "processingStatus",
      "externalCustomerRef",
      "accountRef",
      "page",
      "size",
      "sort",
    ],
  },
  {
    // One canonical lowercase UUID v4 segment and nothing after it. The detail
    // endpoint declares no query, so it may carry none.
    name: "transaction-detail",
    method: "GET",
    matches: (pathname) => TRANSACTION_DETAIL_PATH.test(pathname),
    queryNames: null,
  },
  {
    name: "case-list",
    method: "GET",
    matches: (pathname) => pathname === CASE_LIST_PATH,
    queryNames: [
      "caseStatus",
      "finalDisposition",
      "assigneeRef",
      "createdAtFrom",
      "createdAtTo",
      "lastChangedAtFrom",
      "lastChangedAtTo",
      "transactionId",
      "page",
      "size",
      "sort",
    ],
  },
  {
    // One canonical lowercase UUID v4 segment under `/cases/` and nothing after
    // it. The detail endpoint declares no query, so it may carry none - not
    // `?page=0`, not a case filter it shares a prefix with, and not a bare `?`.
    name: "case-detail",
    method: "GET",
    matches: (pathname) => CASE_DETAIL_PATH.test(pathname),
    queryNames: null,
  },
  {
    name: "case-note-list",
    method: "GET",
    matches: (pathname) => CASE_NOTES_PATH.test(pathname),
    queryNames: ["page", "size", "sort"],
    acceptsQuery: acceptsNotesQuery,
  },
  {
    name: "case-audit-list",
    method: "GET",
    matches: (pathname) => CASE_AUDIT_PATH.test(pathname),
    queryNames: ["page", "size", "sort"],
    acceptsQuery: acceptsAuditQuery,
  },
];

/**
 * The only non-`GET` request this suite will write onto the Backend socket.
 *
 * A single authorization-boundary probe: an `FDS_ANALYST` session attempting a
 * case resolution, which Spring Boot refuses with 403. It is declared as one
 * exact method at one exact address carrying no query - rather than as a
 * general permission to relay writes - so it can neither be reached by another
 * method, nor stretched to another suffix under the same case identifier, nor
 * given a query.
 *
 * Everything else is a read. `POST /api/v1/cases`, `PATCH /api/v1/cases`,
 * `POST /api/v1/transactions`, and every status, assignee or note write on a
 * case are refused here, with or without a query, before a process is spawned
 * or a socket is opened.
 */
const RELAYABLE_WRITE_PROBES: readonly RelayableEndpoint[] = [
  {
    name: "case-resolution-probe",
    method: "POST",
    matches: (pathname) => CASE_RESOLUTION_PROBE_PATH.test(pathname),
    queryNames: null,
  },
];

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

/**
 * The synthetic assignee reference this suite types into the case filter.
 *
 * Fixed, so the expected request target is a constant rather than something
 * rebuilt from the value at assertion time. It carries inner spaces and mixed
 * case but no surrounding whitespace, which is exactly what
 * `FraudCaseQueryValidator` accepts: a case reference must equal its own Java
 * `trim()`. A case fold or an inner-space collapse anywhere between the field
 * and the Backend changes the target and fails. It is never interpolated into a
 * message: every assertion below reports a fixed sentence.
 */
const E2E_ASSIGNEE_REF = "E2E Assignee 01";

/** `page`, `size` and `sort`, in the order and encoding the builder emits. */
const INITIAL_CASE_TARGET = `${CASE_LIST_PATH}?page=0&size=20&sort=lastChangedAt%2Cdesc`;

/**
 * The same three, plus the two filters the analyst applies, in the registry's
 * declared order. The reference travels as `+`-encoded inner spaces around the
 * exact characters typed.
 */
const APPLIED_CASE_TARGET =
  `${CASE_LIST_PATH}?caseStatus=OPEN&assigneeRef=E2E+Assignee+01` +
  "&page=0&size=20&sort=lastChangedAt%2Cdesc";
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
  /** Request-body octets observed at the relay boundary. */
  readonly requestBodyByteLength: number;
  /**
   * The request target written onto the Backend socket, byte for byte: the
   * path and, where there is one, the query. Recorded from what the relay
   * actually sent rather than from the browser request, so an observation can
   * never describe a URL the Backend was not asked for.
   */
  readonly target: string;
  readonly status: number;
  /**
   * What the Backend answered with, kept only for the one endpoint a test asked
   * for it and only in this process.
   *
   * A relayed body is already carried through here on its way to the browser;
   * what this field adds is the ability to read the same bytes afterwards, so a
   * test can ask whether the values Spring Boot actually returned reached the
   * screen. It is opt-in per run, never written to a file, a report or a
   * stream, and no assertion ever prints it.
   */
  readonly body?: string;
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
 * too - and because it travels, it is bounded here rather than trusted.
 *
 * The order below is the contract, and each step answers a different question:
 *
 * 1. is this address the Backend at all - the one origin, no userinfo, no
 *    empty query delimiter, no fragment - and is the path even written in a
 *    shape this suite parses? This is syntax, and syntax is not approval. A
 *    path can be perfectly well-formed and still be an endpoint this suite has
 *    no business reaching;
 * 2. is this method at this exact address one of the six approved endpoint
 *    kinds declared above - six reads plus one write probe? Method and address are
 *    decided together, so `POST` to a read address and `GET` to the write probe
 *    are both refused here;
 * 3. the declared write probe carries no query, which is checked rather than
 *    assumed;
 * 4. a read address is matched against `RELAYABLE_READ_PATHS` whether or not it
 *    carries a query. A `GET` carrying no query at all reaches the same socket
 *    as one with a filter on it, so it passes the same check: there is no path
 *    by which an unapproved endpoint is relayed on the accident of carrying no
 *    query. "No query" here means no `?`; a `?` with nothing behind it was
 *    already refused at step 1 rather than treated as one;
 * 5. only then is a query looked at, against the parameter names that one
 *    endpoint declares - never a shared list;
 * 6. each name appears once and carries a value, and re-serialising the parsed
 *    pairs has to reproduce the received bytes, so a non-canonical, double or
 *    partial encoding is refused rather than relayed;
 * 7. what is left is printable ASCII with no whitespace, which is what makes it
 *    safe as the target of a request line, and it is returned unchanged.
 *
 * Every refusal is a fixed sentence. No part of an address, a query or a
 * credential appears in one.
 */
/**
 * Whether a request target carries a query delimiter with nothing behind it.
 *
 * `?` with an empty query is not "no query": it is a request target this suite
 * never writes and has no rule for. WHATWG parsing does not say so - it records
 * an empty query, and `URL.search` then reads back as `""`, exactly as it does
 * for an address that carries no `?` at all. Left alone, `GET /api/v1/cases?`
 * would take the no-query branch and be forwarded as `/api/v1/cases`: a target
 * silently rewritten into a different one, which is not something a relay whose
 * whole claim is "it forwards what the application wrote" may do.
 *
 * So the delimiter is looked for structurally, in the request target itself,
 * rather than inferred from what the parser made of it. The fragment is cut off
 * first, because `?#content` is the same empty query with something after it,
 * and `href.endsWith("?")` would miss exactly that combination. The check is
 * then applied to the address as received *and* to its parsed serialization, so
 * neither a form the parser normalises away nor one it introduces can slip past.
 */
function hasEmptyQueryMarker(address: string): boolean {
  const fragment = address.indexOf("#");
  const beforeFragment = fragment === -1 ? address : address.slice(0, fragment);
  const query = beforeFragment.indexOf("?");
  return query !== -1 && query === beforeFragment.length - 1;
}

function resolveRelayTarget(request: PlaywrightRequest): string {
  const address = request.url();
  const url = new URL(address);
  const method = request.method();
  requireCondition(url.origin === BACKEND_ORIGIN, "An unexpected Backend origin was requested.");
  requireCondition(url.username === "" && url.password === "", "A Backend request carried userinfo.");
  // Before the fragment rule rather than after it, so the one combination a
  // post-parse test would miss - an empty query followed by a fragment - is
  // refused for the reason it is actually wrong.
  requireCondition(
    !hasEmptyQueryMarker(address) && !hasEmptyQueryMarker(url.href),
    "A Backend request target carried an empty query.",
  );
  requireCondition(url.hash === "", "A Backend request carried a fragment.");
  requireCondition(/^[A-Z]+$/.test(method), "An invalid Backend method was requested.");
  // Syntax, and only syntax. An uppercase segment, a percent-encoded slash or
  // backslash and a percent-encoded identifier character are all refused here
  // because they are not written the way this suite reads a path - not because
  // the endpoint behind them was considered and approved. That decision is the
  // next two steps, and a lowercase path reaches them with nothing decided.
  requireCondition(/^\/api\/v1\/[a-z0-9\-/]+$/.test(url.pathname), "An invalid Backend path was requested.");

  // Method and address together, before the query is looked at. A write is
  // refused for being a write, not for the shape of a query it happens to
  // carry: moving this below a query branch would let `POST /api/v1/cases`
  // through on the accident of carrying none, and the negative tests below say
  // so.
  if (method !== "GET") {
    const probe = RELAYABLE_WRITE_PROBES.find(
      (candidate) => candidate.method === method && candidate.matches(url.pathname),
    );
    requireCondition(
      probe !== undefined,
      "A Backend request used a method this relay will not write.",
    );
    // A declared write probe is a fixed request to a fixed address. It declares
    // `queryNames: null`, and that it carries none is checked rather than
    // assumed - there is no query allowlist to consult here and none to bypass.
    requireCondition(
      probe.queryNames === null && url.search === "",
      "A Backend write probe carried a query.",
    );
    return url.pathname;
  }

  // The exact read address, checked for every `GET` - including one with no
  // query at all. Canonical case detail, investigation notes and audit-history
  // reads are present only in their exact declared forms above. A notes write,
  // an extra or trailing path, a non-canonical identifier, `.../status`,
  // `.../assignee`, or any other absent address stops here before a process is
  // spawned or a socket is opened.
  const endpoint = RELAYABLE_READ_PATHS.find(
    (candidate) => candidate.method === method && candidate.matches(url.pathname),
  );
  requireCondition(
    endpoint !== undefined,
    "A Backend request named an endpoint this relay does not read.",
  );
  if (url.search === "") {
    return url.pathname;
  }

  const approved = endpoint.queryNames;
  requireCondition(approved !== null, "A Backend query was requested on an endpoint that takes none.");
  const parsed = new URLSearchParams(url.search);
  const names = [...parsed.keys()];
  requireCondition(new Set(names).size === names.length, "A Backend query repeated a parameter name.");
  // An empty name or an empty value is not something the application's query
  // builder can produce - an unset filter is omitted, not sent blank - so it is
  // refused rather than forwarded as a parameter Backend would have to decide
  // about.
  requireCondition(
    [...parsed.entries()].every(([name, value]) => name !== "" && value !== ""),
    "A Backend query carried an empty name or value.",
  );
  requireCondition(
    names.every((name) => approved.includes(name)),
    "A Backend query carried a parameter this endpoint does not declare.",
  );
  const canonical = parsed.toString();
  requireCondition(canonical === url.search.slice(1), "A Backend query was not canonically encoded.");
  requireCondition(
    endpoint.acceptsQuery === undefined || endpoint.acceptsQuery(parsed),
    "A Backend query carried a value this endpoint does not accept.",
  );

  const target = `${url.pathname}?${canonical}`;
  requireCondition(
    /^[\u0021-\u007e]+$/.test(target),
    "A Backend request target carried a character this relay will not write.",
  );
  return target;
}

/**
 * How many times this suite has written a request onto the Backend socket, and
 * how many Backend answers it has recorded.
 *
 * Counters rather than assertions about a mock, because there is no mock: the
 * relay really does spawn `docker compose exec` and really does open
 * `/dev/tcp`. "A refused request reached neither" is only a claim worth making
 * if it is measured at the two places where it would stop being true, so both
 * are incremented at the exact statement that performs the act.
 */
let relaySpawnCount = 0;
let relayObservationCount = 0;

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

  relaySpawnCount += 1;
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

interface RelayOptions {
  /**
   * The endpoint path or paths whose response body is retained in memory.
   *
   * Opt-in, and named paths rather than all of them: a body is only kept where a
   * test has a reason to read it back, so a run that does not ask keeps nothing
   * at all. Nothing about the relay's behaviour towards the browser changes -
   * the same bytes are forwarded either way.
   */
  readonly captureBodyOf?: string | readonly string[];
  /** Holds only these approved reads until every target has reached the relay. */
  readonly parallelStartBarrier?: ParallelStartBarrier;
}

type ParallelStartBarrierState = "pending" | "released" | "failed" | "disposed";

interface ParallelStartTarget {
  readonly method: string;
  readonly target: string;
}

interface ParallelStartScheduler {
  schedule(callback: () => void, delayMs: number): unknown;
  cancel(handle: unknown): void;
}

interface ParallelStartBarrierSnapshot {
  readonly state: ParallelStartBarrierState;
  readonly expectedTargetCount: number;
  readonly arrivedTargetCount: number;
  readonly pendingWaiterCount: number;
  readonly activeTimerCount: number;
  readonly activeCallbackCount: number;
  readonly completionResolveCount: number;
  readonly completionRejectCount: number;
  readonly timeoutCallbackCount: number;
}

const PARALLEL_START_TIMEOUT_MS = 15_000;
const PARALLEL_START_TIMEOUT_MESSAGE = "The parallel-start barrier timed out.";
const PARALLEL_START_DUPLICATE_MESSAGE =
  "The parallel-start barrier received a duplicate target.";
const PARALLEL_START_DISPOSED_MESSAGE = "The parallel-start barrier is unavailable.";

class ParallelStartBarrierError extends Error {}

const realParallelStartScheduler: ParallelStartScheduler = {
  schedule(callback, delayMs) {
    return setTimeout(callback, delayMs);
  },
  cancel(handle) {
    clearTimeout(handle as ReturnType<typeof setTimeout>);
  },
};

function parallelStartTargetKey(method: string, target: string): string {
  return `${method}\u0000${target}`;
}

function rejectedParallelStartWait(message: string): Promise<void> {
  const rejected = Promise.reject<void>(new ParallelStartBarrierError(message));
  void rejected.catch(() => undefined);
  return rejected;
}

/**
 * A bounded, one-shot rendezvous for the three reads made by the case screen.
 *
 * It owns its timer and waiter callbacks, never includes a target in an error,
 * and keeps release, failure and disposal as distinct states. The relay only
 * forwards a target after `wait` resolves; an unexpected target is not enrolled
 * and remains the closed allowlist's responsibility.
 */
class ParallelStartBarrier {
  readonly completion: Promise<void>;

  private state: ParallelStartBarrierState = "pending";
  private readonly scheduler: ParallelStartScheduler;
  private readonly timeoutMs: number;
  private readonly configuredTargetKeys: ReadonlySet<string>;
  private readonly targetKeys: Set<string>;
  private readonly arrivedTargetKeys = new Set<string>();
  private readonly waiters = new Set<{
    readonly resolve: () => void;
    readonly reject: (error: ParallelStartBarrierError) => void;
  }>();
  private timerHandle: unknown | null = null;
  private resolveCompletion!: () => void;
  private rejectCompletion!: (error: ParallelStartBarrierError) => void;
  private completionResolveCount = 0;
  private completionRejectCount = 0;
  private timeoutCallbackCount = 0;

  constructor(
    targets: readonly ParallelStartTarget[],
    timeoutMs: number,
    scheduler: ParallelStartScheduler = realParallelStartScheduler,
  ) {
    const targetKeys = targets.map(({ method, target }) => parallelStartTargetKey(method, target));
    requireCondition(
      targets.length === 3 && new Set(targetKeys).size === 3,
      "The parallel-start barrier requires three distinct targets.",
    );
    requireCondition(
      Number.isSafeInteger(timeoutMs) && timeoutMs > 0,
      "The parallel-start barrier requires a bounded timeout.",
    );
    this.scheduler = scheduler;
    this.timeoutMs = timeoutMs;
    this.configuredTargetKeys = new Set(targetKeys);
    this.targetKeys = new Set(targetKeys);
    this.completion = new Promise<void>((resolve, reject) => {
      this.resolveCompletion = resolve;
      this.rejectCompletion = reject;
    });
    // A barrier may have no external completion observer (for example while a
    // page is closing). Keep its rejection handled without changing what an
    // explicit observer receives from the original promise.
    void this.completion.catch(() => undefined);
  }

  start(): void {
    if (this.state !== "pending" || this.timerHandle !== null) {
      return;
    }
    this.timerHandle = this.scheduler.schedule(() => {
      if (this.state !== "pending") {
        return;
      }
      this.timeoutCallbackCount += 1;
      this.fail(PARALLEL_START_TIMEOUT_MESSAGE);
    }, this.timeoutMs);
  }

  wait(method: string, target: string): Promise<void> | null {
    const key = parallelStartTargetKey(method, target);
    if (!this.configuredTargetKeys.has(key)) {
      return null;
    }
    // The barrier only coordinates the first three requests. Once terminal,
    // even a configured target belongs to the relay's normal allowlist and
    // request-count checks rather than to this completed rendezvous.
    if (this.state !== "pending") {
      return null;
    }
    // A matching request that wins the race with the explicit arm still gets
    // the same bounded lifetime rather than waiting without a timer.
    this.start();
    if (this.arrivedTargetKeys.has(key)) {
      this.fail(PARALLEL_START_DUPLICATE_MESSAGE);
      return rejectedParallelStartWait(PARALLEL_START_DUPLICATE_MESSAGE);
    }

    this.arrivedTargetKeys.add(key);
    const wait = new Promise<void>((resolve, reject) => {
      this.waiters.add({ resolve, reject });
    });
    void wait.catch(() => undefined);

    if (this.arrivedTargetKeys.size === this.targetKeys.size) {
      this.release();
    }
    return wait;
  }

  dispose(): void {
    if (this.state === "disposed") {
      return;
    }
    if (this.state === "pending") {
      this.rejectOnce(new ParallelStartBarrierError(PARALLEL_START_DISPOSED_MESSAGE));
    }
    this.clearTimer();
    const error = new ParallelStartBarrierError(PARALLEL_START_DISPOSED_MESSAGE);
    for (const waiter of this.waiters) {
      waiter.reject(error);
    }
    this.waiters.clear();
    this.arrivedTargetKeys.clear();
    this.targetKeys.clear();
    this.state = "disposed";
  }

  snapshot(): ParallelStartBarrierSnapshot {
    const activeTimerCount = this.timerHandle === null ? 0 : 1;
    return {
      state: this.state,
      expectedTargetCount: this.targetKeys.size,
      arrivedTargetCount: this.arrivedTargetKeys.size,
      pendingWaiterCount: this.waiters.size,
      activeTimerCount,
      activeCallbackCount: this.waiters.size + activeTimerCount,
      completionResolveCount: this.completionResolveCount,
      completionRejectCount: this.completionRejectCount,
      timeoutCallbackCount: this.timeoutCallbackCount,
    };
  }

  private release(): void {
    if (this.state !== "pending") {
      return;
    }
    this.clearTimer();
    this.state = "released";
    this.completionResolveCount += 1;
    this.resolveCompletion();
    for (const waiter of this.waiters) {
      waiter.resolve();
    }
    this.waiters.clear();
  }

  private fail(message: string): void {
    if (this.state !== "pending") {
      return;
    }
    this.clearTimer();
    this.state = "failed";
    const error = new ParallelStartBarrierError(message);
    this.rejectOnce(error);
    for (const waiter of this.waiters) {
      waiter.reject(error);
    }
    this.waiters.clear();
    this.arrivedTargetKeys.clear();
    this.targetKeys.clear();
  }

  private rejectOnce(error: ParallelStartBarrierError): void {
    this.completionRejectCount += 1;
    this.rejectCompletion(error);
  }

  private clearTimer(): void {
    if (this.timerHandle === null) {
      return;
    }
    this.scheduler.cancel(this.timerHandle);
    this.timerHandle = null;
  }
}

interface ControllableParallelStartScheduler extends ParallelStartScheduler {
  readonly pendingCount: () => number;
  readonly runAll: () => number;
}

function createControllableParallelStartScheduler(): ControllableParallelStartScheduler {
  const callbacks = new Map<object, () => void>();
  return {
    schedule(callback) {
      const handle = {};
      callbacks.set(handle, callback);
      return handle;
    },
    cancel(handle) {
      callbacks.delete(handle as object);
    },
    pendingCount: () => callbacks.size,
    runAll: () => {
      const pending = [...callbacks.values()];
      callbacks.clear();
      for (const callback of pending) {
        callback();
      }
      return pending.length;
    },
  };
}

type ObservedParallelStartOutcome =
  | { readonly status: "fulfilled" }
  | { readonly status: "rejected"; readonly error: unknown };

function observeParallelStart(promise: Promise<void>): Promise<ObservedParallelStartOutcome> {
  return promise.then<ObservedParallelStartOutcome>(
    () => ({ status: "fulfilled" }),
    (error: unknown) => ({ status: "rejected", error }),
  );
}

function requireParallelStartWait(wait: Promise<void> | null): Promise<void> {
  requireCondition(wait !== null, "An exact parallel-start target was not enrolled.");
  return wait;
}

function requireFixedParallelStartFailure(
  outcome: ObservedParallelStartOutcome,
  expectedMessage: string,
  forbiddenValues: readonly string[],
): void {
  requireCondition(outcome.status === "rejected", "A parallel-start failure resolved instead.");
  if (outcome.status !== "rejected") {
    return;
  }
  requireCondition(
    outcome.error instanceof ParallelStartBarrierError &&
      outcome.error.message === expectedMessage,
    "A parallel-start failure did not use its fixed error.",
  );
  const message = outcome.error instanceof Error ? outcome.error.message : "";
  requireCondition(
    forbiddenValues.every((value) => value !== "" && !message.includes(value)),
    "A parallel-start failure reflected request or credential data.",
  );
}

function requireNoParallelStartResources(
  barrier: ParallelStartBarrier,
  scheduler: ControllableParallelStartScheduler,
): void {
  const snapshot = barrier.snapshot();
  requireCondition(snapshot.pendingWaiterCount === 0, "A parallel-start waiter remained pending.");
  requireCondition(snapshot.activeTimerCount === 0, "A parallel-start timer remained active.");
  requireCondition(snapshot.activeCallbackCount === 0, "A parallel-start callback remained active.");
  requireCondition(scheduler.pendingCount() === 0, "The parallel-start scheduler retained a callback.");
}

function requireUnchangedParallelStartSnapshot(
  barrier: ParallelStartBarrier,
  before: ParallelStartBarrierSnapshot,
  message: string,
): void {
  requireCondition(JSON.stringify(barrier.snapshot()) === JSON.stringify(before), message);
}

function verifyTerminalBarrierRelayPassThrough(
  barrier: ParallelStartBarrier,
  label: string,
): void {
  const before = barrier.snapshot();
  const approvedTarget = "/api/v1/cases?page=0&size=20&sort=lastChangedAt%2Cdesc";
  const rejectedTarget = "/api/v1/cases/0badcafe-0000-4000-8000-000000000259/unapproved";
  requireCondition(
    barrier.wait("GET", approvedTarget) === null && barrier.wait("GET", rejectedTarget) === null,
    `A ${label} barrier intercepted an unrelated relay target.`,
  );
  requireUnchangedParallelStartSnapshot(
    barrier,
    before,
    `An unrelated relay target changed the ${label} barrier.`,
  );

  requireCondition(
    resolveRelayTarget(relayCandidate("GET", `${BACKEND_ORIGIN}${approvedTarget}`)) ===
      approvedTarget,
    `The relay rejected an approved read after the barrier was ${label}.`,
  );
  const spawnsBefore = relaySpawnCount;
  const observationsBefore = relayObservationCount;
  let refusal: string | null = null;
  try {
    relayToBackend(relayCandidate("GET", `${BACKEND_ORIGIN}${rejectedTarget}`));
  } catch (error: unknown) {
    refusal = error instanceof Error ? error.message : "unknown";
  }
  requireCondition(
    refusal !== null && RELAY_REFUSALS.includes(refusal),
    `The relay did not independently refuse an unapproved read after the barrier was ${label}.`,
  );
  requireCondition(
    relaySpawnCount === spawnsBefore && relayObservationCount === observationsBefore,
    `An unapproved read crossed the relay after the barrier was ${label}.`,
  );
}

/** Deterministic lifecycle matrix for the spec-local barrier helper. */
async function verifyParallelStartBarrierLifecycle(): Promise<void> {
  const barrierCaseId = "0badcafe-0000-4000-8000-000000000259";
  const targets: readonly ParallelStartTarget[] = [
    { method: "GET", target: `/parallel/${barrierCaseId}` },
    {
      method: "GET",
      target: `/parallel/${barrierCaseId}/notes?page=0&size=20&sort=createdAt%2Casc`,
    },
    {
      method: "GET",
      target: `/parallel/${barrierCaseId}/audit?page=0&size=20&sort=changedAt%2Cdesc`,
    },
  ];
  const forbiddenValues = [
    ...targets.map(({ target }) => target),
    ...targets.map(({ target }) => `http://localhost:8080${target}`),
    barrierCaseId,
    "parallel-secret-credential",
    "Bearer parallel-secret-token",
    "parallel-secret-cookie",
    "page=0",
    "createdAt%2Casc",
  ];
  const createBarrier = () => {
    const scheduler = createControllableParallelStartScheduler();
    const barrier = new ParallelStartBarrier(targets, 1, scheduler);
    barrier.start();
    return { barrier, scheduler };
  };

  // A: three distinct exact targets release exactly once and cancel the timer.
  {
    const { barrier, scheduler } = createBarrier();
    const completion = observeParallelStart(barrier.completion);
    const waits = targets.map(({ method, target }) =>
      observeParallelStart(requireParallelStartWait(barrier.wait(method, target))),
    );
    const outcomes = await Promise.all([completion, ...waits]);
    requireCondition(
      outcomes.every(({ status }) => status === "fulfilled"),
      "A complete parallel-start barrier did not release every observer.",
    );
    const released = barrier.snapshot();
    requireCondition(
      released.state === "released" &&
        released.expectedTargetCount === 3 &&
        released.arrivedTargetCount === 3 &&
        released.completionResolveCount === 1 &&
        released.completionRejectCount === 0 &&
        released.timeoutCallbackCount === 0,
      "A complete parallel-start barrier recorded the wrong terminal state.",
    );
    requireNoParallelStartResources(barrier, scheduler);
    requireCondition(scheduler.runAll() === 0, "A released barrier still ran a timeout callback.");
    const beforeReleasedPassThrough = barrier.snapshot();
    requireCondition(
      barrier.wait("GET", "/parallel/unexpected") === null &&
        barrier.wait(targets[0].method, targets[0].target) === null,
      "A released barrier intercepted a later request.",
    );
    requireUnchangedParallelStartSnapshot(
      barrier,
      beforeReleasedPassThrough,
      "A later request changed a released barrier.",
    );
    verifyTerminalBarrierRelayPassThrough(barrier, "released");
    barrier.dispose();
    barrier.dispose();
    requireNoParallelStartResources(barrier, scheduler);
    requireCondition(scheduler.runAll() === 0, "A disposed released barrier ran a callback.");
    const beforeDisposedPassThrough = barrier.snapshot();
    requireCondition(
      barrier.wait("GET", "/parallel/unexpected") === null &&
        barrier.wait(targets[0].method, targets[0].target) === null,
      "A disposed barrier intercepted a later request.",
    );
    requireUnchangedParallelStartSnapshot(
      barrier,
      beforeDisposedPassThrough,
      "A later request changed a disposed barrier.",
    );
    verifyTerminalBarrierRelayPassThrough(barrier, "disposed");
  }

  const verifyMissingTargets = async (arrivalCount: 0 | 1 | 2): Promise<void> => {
    const { barrier, scheduler } = createBarrier();
    const completion = observeParallelStart(barrier.completion);
    const waits = targets.slice(0, arrivalCount).map(({ method, target }) =>
      observeParallelStart(requireParallelStartWait(barrier.wait(method, target))),
    );
    requireCondition(scheduler.runAll() === 1, "A pending barrier did not run its timeout once.");
    const outcomes = await Promise.all([completion, ...waits]);
    for (const outcome of outcomes) {
      requireFixedParallelStartFailure(outcome, PARALLEL_START_TIMEOUT_MESSAGE, forbiddenValues);
    }
    const failed = barrier.snapshot();
    requireCondition(
      failed.state === "failed" &&
        failed.expectedTargetCount === 0 &&
        failed.arrivedTargetCount === 0 &&
        failed.completionResolveCount === 0 &&
        failed.completionRejectCount === 1 &&
        failed.timeoutCallbackCount === 1,
      "A timed-out parallel-start barrier retained targets or settled incorrectly.",
    );
    requireNoParallelStartResources(barrier, scheduler);
    const beforeFailedPassThrough = barrier.snapshot();
    requireCondition(
      barrier.wait("GET", "/parallel/unexpected") === null &&
        barrier.wait(targets[0].method, targets[0].target) === null,
      "A failed barrier intercepted a later request.",
    );
    requireUnchangedParallelStartSnapshot(
      barrier,
      beforeFailedPassThrough,
      "A later request changed a failed barrier.",
    );
    verifyTerminalBarrierRelayPassThrough(barrier, "failed");
    barrier.dispose();
    requireNoParallelStartResources(barrier, scheduler);
  };

  // B-D: one, two, or all three missing targets fail without real-time waits.
  await verifyMissingTargets(2);
  await verifyMissingTargets(1);
  await verifyMissingTargets(0);

  // E: a duplicate cannot impersonate the third distinct target.
  {
    const { barrier, scheduler } = createBarrier();
    const completion = observeParallelStart(barrier.completion);
    const first = observeParallelStart(
      requireParallelStartWait(barrier.wait(targets[0].method, targets[0].target)),
    );
    const duplicate = observeParallelStart(
      requireParallelStartWait(barrier.wait(targets[0].method, targets[0].target)),
    );
    for (const outcome of await Promise.all([completion, first, duplicate])) {
      requireFixedParallelStartFailure(
        outcome,
        PARALLEL_START_DUPLICATE_MESSAGE,
        forbiddenValues,
      );
    }
    const failed = barrier.snapshot();
    requireCondition(
      failed.state === "failed" &&
        failed.completionResolveCount === 0 &&
        failed.completionRejectCount === 1,
      "A duplicate parallel-start target completed the barrier.",
    );
    requireNoParallelStartResources(barrier, scheduler);
    requireCondition(scheduler.runAll() === 0, "A duplicate failure left a timeout callback.");
    barrier.dispose();
  }

  // F: an unexpected target neither completes nor allocates a waiter.
  {
    const { barrier, scheduler } = createBarrier();
    const completion = observeParallelStart(barrier.completion);
    const beforeUnexpected = barrier.snapshot();
    requireCondition(
      barrier.wait("GET", "/parallel/unexpected") === null,
      "An unexpected parallel-start target enrolled a waiter.",
    );
    requireUnchangedParallelStartSnapshot(
      barrier,
      beforeUnexpected,
      "An unexpected target changed a pending barrier.",
    );
    const pending = barrier.snapshot();
    requireCondition(
      pending.state === "pending" &&
        pending.arrivedTargetCount === 0 &&
        pending.pendingWaiterCount === 0 &&
        pending.activeTimerCount === 1,
      "An unexpected parallel-start target changed barrier progress.",
    );
    barrier.dispose();
    requireFixedParallelStartFailure(
      await completion,
      PARALLEL_START_DISPOSED_MESSAGE,
      forbiddenValues,
    );
    requireNoParallelStartResources(barrier, scheduler);
    requireCondition(scheduler.runAll() === 0, "A disposed barrier ran a callback.");
  }

  // G: disposal immediately before release and timeout is one-shot and empty.
  {
    const { barrier, scheduler } = createBarrier();
    const completion = observeParallelStart(barrier.completion);
    const waits = targets.slice(0, 2).map(({ method, target }) =>
      observeParallelStart(requireParallelStartWait(barrier.wait(method, target))),
    );
    barrier.dispose();
    const beforeLateArrival = barrier.snapshot();
    requireCondition(
      barrier.wait(targets[2].method, targets[2].target) === null,
      "A disposed barrier intercepted a late configured target.",
    );
    requireUnchangedParallelStartSnapshot(
      barrier,
      beforeLateArrival,
      "A late configured target changed a disposed barrier.",
    );
    const outcomes = await Promise.all([completion, ...waits]);
    for (const outcome of outcomes) {
      requireFixedParallelStartFailure(
        outcome,
        PARALLEL_START_DISPOSED_MESSAGE,
        forbiddenValues,
      );
    }
    const disposed = barrier.snapshot();
    requireCondition(
      disposed.state === "disposed" &&
        disposed.completionResolveCount === 0 &&
        disposed.completionRejectCount === 1 &&
        disposed.timeoutCallbackCount === 0,
      "Dispose immediately before release settled the barrier more than once.",
    );
    requireNoParallelStartResources(barrier, scheduler);
    requireCondition(scheduler.runAll() === 0, "Dispose immediately before timeout ran a callback.");
  }
}

interface BackendRelay extends Array<BackendObservation> {
  readonly dispose: () => Promise<void>;
  readonly pendingHandlerCount: () => number;
  readonly listenerCount: () => number;
  readonly isDisposed: () => boolean;
  readonly barrierArrivalCountAtFirstForwarding: () => number | null;
  readonly barrierAbortCount: () => number;
}

async function installBackendRelay(
  page: Page,
  options: RelayOptions = {},
): Promise<BackendRelay> {
  const observations = [] as unknown as BackendRelay;
  const capturedPaths =
    typeof options.captureBodyOf === "string"
      ? [options.captureBodyOf]
      : (options.captureBodyOf ?? []);
  const relayPattern = "http://localhost:8080/**";
  const activeHandlers = new Set<Promise<void>>();
  let disposed = false;
  let closeListenerActive = true;
  let barrierArrivalCountAtFirstForwarding: number | null = null;
  let barrierAbortCount = 0;

  const processRoute = async (route: Route): Promise<void> => {
    if (disposed) {
      if (!page.isClosed()) {
        await route.abort("failed");
      }
      return;
    }
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
    const requestedUrl = new URL(request.url());
    const requestedTarget = `${requestedUrl.pathname}${requestedUrl.search}`;
    const barrier = options.parallelStartBarrier;
    const barrierWait = barrier?.wait(request.method(), requestedTarget) ?? null;
    if (barrierWait !== null) {
      try {
        await barrierWait;
      } catch (error: unknown) {
        if (error instanceof ParallelStartBarrierError) {
          if (!page.isClosed()) {
            barrierAbortCount += 1;
            await route.abort("failed");
          }
          return;
        }
        throw error;
      }
      const snapshot = barrier.snapshot();
      requireCondition(
        snapshot.state === "released" && snapshot.arrivedTargetCount === 3,
        "A parallel-start target was forwarded before all three reads arrived.",
      );
      barrierArrivalCountAtFirstForwarding ??= snapshot.arrivedTargetCount;
    }
    const relayed = relayToBackend(request);
    // Recorded from the relay's own target, so what this suite observes and
    // what the Backend was asked for cannot drift into two different things.
    const pathname = relayed.target.split("?")[0];
    relayObservationCount += 1;
    observations.push({
      method: request.method(),
      pathname,
      target: relayed.target,
      status: relayed.status,
      requestBodyByteLength: Buffer.byteLength(request.postData() ?? ""),
      ...(capturedPaths.includes(pathname) ? { body: relayed.body } : {}),
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
  };

  const routeHandler = (route: Route): Promise<void> => {
    const handler = processRoute(route);
    activeHandlers.add(handler);
    void handler.then(
      () => activeHandlers.delete(handler),
      () => activeHandlers.delete(handler),
    );
    return handler;
  };
  const pageCloseListener = (): void => {
    page.off("close", pageCloseListener);
    closeListenerActive = false;
    disposed = true;
    options.parallelStartBarrier?.dispose();
  };
  page.on("close", pageCloseListener);
  await page.route(relayPattern, routeHandler);

  const dispose = async (): Promise<void> => {
    const cleanupAlreadyStarted = disposed;
    disposed = true;
    options.parallelStartBarrier?.dispose();
    if (closeListenerActive) {
      page.off("close", pageCloseListener);
      closeListenerActive = false;
    }
    if (!cleanupAlreadyStarted && !page.isClosed()) {
      await page.unroute(relayPattern, routeHandler);
    }
    await Promise.allSettled([...activeHandlers]);
  };
  Object.defineProperties(observations, {
    dispose: { value: dispose },
    pendingHandlerCount: { value: () => activeHandlers.size },
    listenerCount: { value: () => (closeListenerActive ? 1 : 0) },
    isDisposed: { value: () => disposed },
    barrierArrivalCountAtFirstForwarding: {
      value: () => barrierArrivalCountAtFirstForwarding,
    },
    barrierAbortCount: { value: () => barrierAbortCount },
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

/**
 * The three fields a FinGuardOps error response carries that a console must
 * never put on screen.
 *
 * `code` names the failure to another system, `message` is Backend copy written
 * for an operator, and `traceId` correlates one request across the platform's
 * logs. None of them is for a browser, and a screen that reflects any of them
 * has handed the reader a piece of the Backend's own vocabulary.
 */
interface BackendErrorFields {
  readonly code: string;
  readonly message: string;
  readonly traceId: string;
}

/**
 * The fixed console copy this screen shows for a transaction that is not there.
 *
 * Read here so a collision cannot go unnoticed. If a Backend value ever turned
 * out to be part of one of these sentences, then finding that value in the
 * document would prove nothing - the string on screen would be the console's
 * own - and the run says so rather than passing on an ambiguity it cannot
 * resolve without changing production copy or the response.
 */
const NOT_FOUND_SCREEN_COPY: readonly string[] = [
  "Transaction not found",
  "No transaction with this identifier is available. Return to the transaction list.",
  "No record shown.",
];

/**
 * The same, for the case detail screen. Its own list rather than a shared one:
 * the two screens carry different sentences, and a collision check is only
 * meaningful against the copy actually on the page under test.
 */
const CASE_NOT_FOUND_SCREEN_COPY: readonly string[] = [
  "Case not found",
  "No case with this identifier is available. Return to the case list.",
  "No record shown.",
];

/**
 * JSON, or nothing. The caller's fixed refusal owns the failure, so the text
 * that would not parse is not named, quoted or re-thrown from here.
 */
function parseJsonOrNull(raw: string): unknown {
  try {
    return JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
}

/** A relayed body as a JSON object, or a fixed failure. Nothing in between. */
function parseJsonObject(raw: string | undefined, missing: string, invalid: string): Record<string, unknown> {
  requireCondition(typeof raw === "string" && raw !== "", missing);
  const parsed = parseJsonOrNull(raw);
  requireCondition(typeof parsed === "object" && parsed !== null && !Array.isArray(parsed), invalid);
  return parsed as Record<string, unknown>;
}

/**
 * The `code`, `message` and `traceId` the Backend really answered with, read in
 * this process and nowhere else.
 *
 * Fail-closed at every step: a body that is absent, is not JSON, is not an
 * object, or carries any of the three fields with the wrong type or as blank
 * stops the run. It stops with a fixed sentence, because the whole point of the
 * values being read here is that they must not be printed - and a failure
 * message that quoted the body to explain itself would be the very disclosure
 * the assertions below exist to rule out.
 */
function readBackendErrorFields(raw: string | undefined): BackendErrorFields {
  const body = parseJsonObject(
    raw,
    "The Backend error response body was not observed.",
    "The Backend error response body was not a JSON object.",
  );
  const fields: Record<string, string> = {};
  for (const [name, refusal] of [
    ["code", "The Backend error response carried no usable code."],
    ["message", "The Backend error response carried no usable message."],
    ["traceId", "The Backend error response carried no usable trace identifier."],
  ] as const) {
    const value = body[name];
    requireCondition(typeof value === "string", refusal);
    // Blank is a contract failure rather than a value to search for: an empty
    // or whitespace-only field would make every non-reflection check below
    // vacuously true.
    requireCondition(value.trim() !== "", refusal);
    fields[name] = value;
  }
  return { code: fields.code, message: fields.message, traceId: fields.traceId };
}

/**
 * Whether one exact string is anywhere in the page a reader or a script could
 * reach it: rendered text, markup, any attribute, the title, the address bar,
 * `history.state`, and both Web Storages.
 *
 * Returns a boolean and only a boolean. What is being searched for is a real
 * Backend error value, so nothing about where it was found - or what was around
 * it - comes back out of the browser.
 */
async function documentExposes(page: Page, value: string): Promise<boolean> {
  return page.evaluate((needle) => {
    if (needle === "") {
      return false;
    }
    const haystacks: string[] = [
      document.documentElement.outerHTML,
      document.documentElement.textContent ?? "",
      document.body.innerText,
      document.title,
      window.location.href,
    ];
    try {
      haystacks.push(JSON.stringify(window.history.state ?? null));
    } catch {
      haystacks.push("");
    }
    for (const element of Array.from(document.querySelectorAll("*"))) {
      for (const attribute of Array.from(element.attributes)) {
        haystacks.push(attribute.name, attribute.value);
      }
    }
    for (const storage of [window.localStorage, window.sessionStorage]) {
      for (let index = 0; index < storage.length; index += 1) {
        const key = storage.key(index);
        if (key !== null) {
          haystacks.push(key, storage.getItem(key) ?? "");
        }
      }
    }
    return haystacks.some((haystack) => haystack.includes(needle));
  }, value);
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

/**
 * A request the relay is asked about, with nothing behind it.
 *
 * The relay only ever reads a method, a URL, the authorization header and the
 * post body off a request, so these four are the whole of what a negative case
 * needs. Nothing here reaches a browser: the point of these tests is that the
 * refusal happens in `resolveRelayTarget`, before a process, a socket or an
 * observation exists.
 */
function relayCandidate(method: string, url: string): PlaywrightRequest {
  return {
    method: () => method,
    url: () => url,
    headers: () => ({}),
    postData: () => null,
  } as unknown as PlaywrightRequest;
}

/** Every fixed sentence `resolveRelayTarget` and `relayToBackend` may fail with. */
const RELAY_REFUSALS: readonly string[] = [
  "An unexpected Backend origin was requested.",
  "A Backend request carried userinfo.",
  "A Backend request target carried an empty query.",
  "A Backend request carried a fragment.",
  "An invalid Backend method was requested.",
  "An invalid Backend path was requested.",
  "A Backend request used a method this relay will not write.",
  "A Backend write probe carried a query.",
  "A Backend request named an endpoint this relay does not read.",
  "A Backend query was requested on an endpoint that takes none.",
  "A Backend query repeated a parameter name.",
  "A Backend query carried an empty name or value.",
  "A Backend query carried a parameter this endpoint does not declare.",
  "A Backend query was not canonically encoded.",
  "A Backend query carried a value this endpoint does not accept.",
  "A Backend request target carried a character this relay will not write.",
];

/** A canonical lowercase UUID v4 that names no case. */
const SYNTHETIC_CASE_ID = "e2e00000-0000-4000-8000-00000000ca5e";
const CASE_RESOLUTION_PATH = `/api/v1/cases/${SYNTHETIC_CASE_ID}/resolution`;
/** `/api/v1/cases/{SYNTHETIC_CASE_ID}`, the one identified case address. */
const CASE_DETAIL_TARGET = `${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}`;
const CASE_NOTES_TARGET = `${CASE_DETAIL_TARGET}/notes`;
const INITIAL_CASE_NOTES_TARGET =
  `${CASE_NOTES_TARGET}?page=0&size=20&sort=createdAt%2Casc`;
const CASE_AUDIT_TARGET = `${CASE_DETAIL_TARGET}/audit-logs`;
const INITIAL_CASE_AUDIT_TARGET =
  `${CASE_AUDIT_TARGET}?page=0&size=20&sort=changedAt%2Cdesc`;

/**
 * Every request this relay must refuse, and why it is on the list.
 *
 * The first three are the finding this block exists for: a write with no query
 * at all. While the method was only checked inside the query branch, each of
 * them was relayed - `POST /api/v1/cases` reached Spring Boot on the accident
 * of carrying nothing after the `?`.
 *
 * The middle group is the other half of the same boundary: the two list
 * endpoints declare their filters separately, so a case filter on the ledger
 * endpoint and a ledger filter on the case endpoint are both refusals. Merging
 * the two lists into one shared allowlist would relay all five.
 *
 * The rest are the encoding and address rules, stated as cases rather than as
 * prose.
 */
const REFUSED_RELAY_REQUESTS: readonly {
  readonly why: string;
  readonly method: string;
  readonly url: string;
}[] = [
  // A write, refused for being a write, with no query to hide behind.
  { why: "a write to the case list", method: "POST", url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}` },
  { why: "a patch of the case list", method: "PATCH", url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}` },
  {
    why: "a write to the ledger list",
    method: "POST",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}`,
  },
  // The same write, now carrying a query this endpoint really does declare. The
  // method decides it, so a declared name changes nothing.
  {
    why: "a write to the case list carrying a declared case filter",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?caseStatus=OPEN`,
  },
  // The one declared write probe, reached by the wrong method, and reached
  // correctly but carrying a query it may not have.
  {
    why: "the resolution probe reached by the wrong method",
    method: "PUT",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`,
  },
  {
    why: "the resolution probe carrying a query",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}?page=0`,
  },
  // Cross-endpoint contamination, both directions. One case per filter that
  // belongs to exactly one of the two lists.
  {
    why: "a ledger-only processing status on the case endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?processingStatus=HELD`,
  },
  {
    why: "a ledger-only customer reference on the case endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?externalCustomerRef=E2E`,
  },
  {
    why: "a case-only status on the ledger endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}?caseStatus=OPEN`,
  },
  {
    why: "a case-only disposition on the ledger endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}?finalDisposition=NORMAL`,
  },
  {
    why: "a case-only assignee reference on the ledger endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}?assigneeRef=E2E+Assignee+01`,
  },
  // Names, duplicates and encodings.
  {
    why: "a name neither endpoint declares",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?unknownFilter=1`,
  },
  {
    why: "a declared name sent twice",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?page=0&page=1`,
  },
  {
    why: "a page number written non-canonically",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?page=%30`,
  },
  {
    why: "a sort whose comma is not the canonical encoding",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?sort=lastChangedAt,desc`,
  },
  {
    why: "an empty query name",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?=0`,
  },
  {
    why: "an empty query value",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?caseStatus=`,
  },
  // This group covers a query delimiter with nothing behind it on the four
  // non-audit reads and the one write probe. The audit-list read's bare query
  // marker is covered below with the audit-specific query validation cases.
  // Each of these five addresses is admitted when it carries no `?` at all, so
  // each could otherwise be quietly rewritten into the approved target instead
  // of refused. The final fragment case is the combination a post-parse
  // `endsWith("?")` test would miss.
  {
    why: "an empty query on the ledger list",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}?`,
  },
  {
    why: "an empty query on the case list",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?`,
  },
  {
    why: "an empty query on the transaction detail address",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${SYNTHETIC_TRANSACTION_ID}?`,
  },
  {
    why: "an empty query on the case detail address",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}?`,
  },
  {
    why: "an empty query on the resolution probe",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}?`,
  },
  {
    why: "an empty query followed by a fragment",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}?#content`,
  },
  // The identified case address takes no query at all, so every one of these
  // is refused on an address the same `GET` reaches when it carries nothing
  // after the path. `page` is a name the *list* endpoint really declares, which
  // is precisely why it must not be honoured one segment deeper.
  {
    why: "a page number on the case detail address",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}?page=0`,
  },
  {
    why: "a declared case filter on the case detail address",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}?caseStatus=OPEN`,
  },
  {
    why: "an arbitrary query on the case detail address",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}?include=notes`,
  },
  {
    why: "a fragment on the case detail address",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}#assignee`,
  },
  // Audit page/size/sort grammar and meaning, including cross-endpoint query
  // contamination. These seventeen are the exact delta from the Issue #255
  // query boundary. The notes-specific matrix below brings the complete current
  // query/method refusal set to 64.
  {
    why: "a duplicate audit query name",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?page=0&page=1`,
  },
  {
    why: "an empty audit query name",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?=0`,
  },
  {
    why: "an empty audit query value",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?page=`,
  },
  {
    why: "an unknown audit query name",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?unknown=1`,
  },
  {
    why: "a bare audit query marker",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?`,
  },
  {
    why: "a non-canonical audit sort comma",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?sort=changedAt,desc`,
  },
  {
    why: "a percent-encoded audit page digit",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?page=%30`,
  },
  {
    why: "a negative audit page",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?page=-1`,
  },
  {
    why: "an audit page with a leading zero",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?page=01`,
  },
  {
    why: "a fractional audit page",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?page=1.0`,
  },
  {
    why: "an audit page beyond int32",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?page=2147483648`,
  },
  {
    why: "a zero audit page size",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?size=0`,
  },
  {
    why: "an audit page size above one hundred",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?size=101`,
  },
  {
    why: "an audit sort with another field",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?sort=createdAt%2Cdesc`,
  },
  {
    why: "an audit sort with another direction",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?sort=changedAt%2Csideways`,
  },
  {
    why: "a transaction-list filter on the audit endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?processingStatus=HELD`,
  },
  {
    why: "a case-list filter on the audit endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}?caseStatus=OPEN`,
  },
  // Investigation-note query grammar. Kept separate from audit even though
  // page and size have the same bounds: the sort fields are endpoint-owned and
  // must never leak across the two descriptors.
  {
    why: "a duplicate notes query name",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?page=0&page=1`,
  },
  {
    why: "an empty notes query name",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?=0`,
  },
  {
    why: "an empty notes query value",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?page=`,
  },
  {
    why: "an unknown notes query name",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?unknown=1`,
  },
  {
    why: "a bare notes query marker",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?`,
  },
  {
    why: "a non-canonical notes sort comma",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?sort=createdAt,asc`,
  },
  {
    why: "a negative notes page",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?page=-1`,
  },
  {
    why: "a notes page with a leading zero",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?page=01`,
  },
  {
    why: "a notes page beyond int32",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?page=2147483648`,
  },
  {
    why: "a zero notes page size",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?size=0`,
  },
  {
    why: "a notes page size above one hundred",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?size=101`,
  },
  {
    why: "an audit sort on the notes endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?sort=changedAt%2Cdesc`,
  },
  {
    why: "a notes sort with another field",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?sort=noteId%2Casc`,
  },
  {
    why: "a notes sort with another direction",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?sort=createdAt%2Csideways`,
  },
  {
    why: "a transaction-list filter on the notes endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?processingStatus=HELD`,
  },
  {
    why: "a case-list filter on the notes endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}?caseStatus=OPEN`,
  },
  // The address rules.
  { why: "a fragment", method: "GET", url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}#content` },
  {
    why: "userinfo",
    method: "GET",
    url: `http://relay-negative-user:relay-negative-userinfo@localhost:8080${CASE_LIST_PATH}`,
  },
  {
    why: "a path this suite does not recognise",
    method: "GET",
    url: `${BACKEND_ORIGIN}/api/v1/ADMIN/cases`,
  },
  {
    why: "an origin that is not the Backend",
    method: "GET",
    url: `http://localhost:9999${CASE_LIST_PATH}`,
  },
];

function requireUniqueRelayDeclarations(
  entries: readonly { readonly why: string; readonly method: string; readonly url: string }[],
  label: string,
): void {
  const requestKeys = entries.map((entry) => `${entry.method}\u0000${entry.url}`);
  const reasons = entries.map((entry) => entry.why);
  requireCondition(
    new Set(requestKeys).size === entries.length,
    `The ${label} matrix contains a duplicate method and URL.`,
  );
  requireCondition(
    reasons.every((reason) => reason.trim() !== "") && new Set(reasons).size === entries.length,
    `The ${label} matrix contains an empty or duplicate reason.`,
  );
}

/**
 * The relay boundary, stated as refusals rather than as a comment.
 *
 * No browser, no Keycloak and no Backend: `relayToBackend` is called directly,
 * which is the only way to observe that a refused request is refused *before*
 * `docker compose exec` is spawned and before `/dev/tcp` is opened. The two
 * counters are the evidence; a refusal that happened one statement later would
 * leave them moved.
 *
 * The messages are checked too. Every one of them is a fixed sentence from a
 * closed list: no address, no query name, no query value and no credential is
 * reflected back into a failure, so a run's output cannot become the place a
 * filter value is finally written down.
 */
test("the Backend relay refuses a write, a foreign filter and a non-canonical query", async () => {
  await verifyParallelStartBarrierLifecycle();
  requireCondition(REFUSED_RELAY_REQUESTS.length === 64, "The relay query-refusal matrix drifted.");
  requireUniqueRelayDeclarations(REFUSED_RELAY_REQUESTS, "relay query-refusal");
  const spawnsBefore = relaySpawnCount;
  const observationsBefore = relayObservationCount;

  for (const refused of REFUSED_RELAY_REQUESTS) {
    let message: string | null = null;
    try {
      relayToBackend(relayCandidate(refused.method, refused.url));
    } catch (error: unknown) {
      message = error instanceof Error ? error.message : "unknown";
    }
    requireCondition(message !== null, `The relay accepted ${refused.why}.`);
    requireCondition(
      RELAY_REFUSALS.includes(message),
      `The relay refused ${refused.why} with a message that is not a fixed sentence.`,
    );
    // The refusal names the rule and nothing else. Neither the address nor any
    // part of the query it carried appears in what a run would print.
    const url = new URL(refused.url);
    const reflected = [
      url.href,
      url.host,
      url.pathname,
      url.search,
      url.username,
      url.password,
      ...[...new URLSearchParams(url.search).entries()].flat(),
    ].filter((value) => value !== "");
    requireCondition(
      !reflected.some((value) => message.includes(value)),
      `The relay reflected part of ${refused.why} into its failure.`,
    );
  }

  // Nothing was written to the Backend, and nothing was recorded as having
  // been.
  requireCondition(
    relaySpawnCount === spawnsBefore,
    "A refused Backend request spawned a relay process.",
  );
  requireCondition(
    relayObservationCount === observationsBefore,
    "A refused Backend request was recorded as a Backend observation.",
  );
});

/**
 * Identifiers that are *almost* the canonical lowercase UUID v4 the two
 * identified addresses declare, and are therefore a different address.
 *
 * Each is one deviation from `SYNTHETIC_TRANSACTION_ID` or `SYNTHETIC_CASE_ID`:
 * a case fold, a version digit, an RFC variant nibble, the hyphens, or a
 * percent-encoded character. None of them is repaired on its way through this
 * relay - a relay that case-folded or decoded an identifier would be writing an
 * address the application never asked for.
 */
const UPPERCASE_TRANSACTION_ID = "E2E00000-0000-4000-8000-000000000E2E";
const VERSION_1_TRANSACTION_ID = "e2e00000-0000-1000-8000-000000000e2e";
const INVALID_VARIANT_TRANSACTION_ID = "e2e00000-0000-4000-c000-000000000e2e";
const UNHYPHENATED_TRANSACTION_ID = "e2e0000000004000800000000000e2e";
/** The same identifier with its last character written as `%65`. */
const PERCENT_ENCODED_TRANSACTION_ID = "e2e00000-0000-4000-8000-000000000e2%65";

const UPPERCASE_CASE_ID = "E2E00000-0000-4000-8000-00000000CA5E";
const VERSION_1_CASE_ID = "e2e00000-0000-1000-8000-00000000ca5e";
const INVALID_VARIANT_CASE_ID = "e2e00000-0000-4000-c000-00000000ca5e";
/** The same identifier with its hyphens removed, and with its last character as `%65`. */
const UNHYPHENATED_CASE_ID = SYNTHETIC_CASE_ID.replaceAll("-", "");
const PERCENT_ENCODED_CASE_ID = "e2e00000-0000-4000-8000-00000000ca5%65";


/**
 * Every address this relay must refuse for being one it was never approved to
 * reach, stated as literals rather than as a rule.
 *
 * This is the finding this block exists for. While a `GET` carrying no query
 * returned its own path before any address list was consulted, the only thing
 * standing between this suite and the whole of `/api/v1/**` was the path
 * *syntax* check - so `GET /api/v1/cases/{caseId}`, its `/notes`, an
 * audit-log mutation and an endpoint that does not exist at all would have been
 * written onto the Backend socket on the strength of being lowercase. A
 * well-formed path is not an approved endpoint, and these cases are what says
 * so.
 *
 * The current coverage groups contain 70 refused endpoints in total:
 *
 * - reads this suite has no screen for. Every one is a valid lowercase
 *   `/api/v1/...` path carrying no query at all, and every one is refused.
 *   They are also the endpoints a later Issue is most likely to want, which is
 *   exactly why they stay refused until the test that needs them exists;
 * - the transaction detail address written non-canonically. A case fold, a
 *   version, an RFC variant, the hyphens, a trailing slash, an extra segment,
 *   an encoded slash, an encoded backslash and a percent-encoded character are
 *   each a different address, and none is repaired into the approved one;
 * - the case detail address written the same non-canonical ways. It is the read
 *   this Issue admitted, so each deviation from its canonical lowercase UUID v4
 *   is asserted against an address that really is allowed now;
 * - the audit address written with a non-canonical case identifier, trailing
 *   slash, extra segment, encoded separator or fragment;
 * - the case detail address reached by a write method. It is admitted as a
 *   `GET` and only a `GET`, so admitting the read admitted no write;
 * - the audit address reached by POST, PATCH, PUT or DELETE. Its bare and
 *   canonical page/size/sort GET forms are reads, never mutation permission;
 * - the write probe, reached by another suffix, by another method, on another
 *   identifier shape, or carrying a query. The probe is one method at one
 *   address with nothing after the `?`, so the case status, assignee, note and
 *   audit-log writes below are refused before a process is spawned.
 */
const REFUSED_UNAPPROVED_ENDPOINTS: readonly {
  readonly why: string;
  readonly method: string;
  readonly url: string;
}[] = [
  // Valid, lowercase, query-free reads this suite is not approved to make.
  // The case *detail* address is no longer among them - it is the one read this
  // Issue admitted - so every one of these is a sibling of an address that is
  // now allowed, which is exactly what makes their refusal worth asserting.
  {
    why: "a case status read",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/status`,
  },
  {
    why: "a case assignee read",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/assignee`,
  },
  {
    why: "a case resolution read",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`,
  },
  {
    why: "a case related-transactions read",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/transactions`,
  },
  {
    why: "a current AI case report read",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/ai-reports/current`,
  },
  {
    why: "an unnamed suffix under a case",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/anything`,
  },
  {
    why: "a behaviour-event read",
    method: "GET",
    url: `${BACKEND_ORIGIN}/api/v1/behavior-events`,
  },
  {
    why: "an endpoint that does not exist",
    method: "GET",
    url: `${BACKEND_ORIGIN}/api/v1/unknown`,
  },
  {
    why: "an unnamed suffix under a transaction",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${SYNTHETIC_TRANSACTION_ID}/anything`,
  },
  // The transaction detail address, written non-canonically.
  {
    why: "an uppercase transaction identifier",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${UPPERCASE_TRANSACTION_ID}`,
  },
  {
    why: "a version 1 transaction identifier",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${VERSION_1_TRANSACTION_ID}`,
  },
  {
    why: "a transaction identifier with an invalid RFC variant",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${INVALID_VARIANT_TRANSACTION_ID}`,
  },
  {
    why: "an unhyphenated transaction identifier",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${UNHYPHENATED_TRANSACTION_ID}`,
  },
  {
    why: "a transaction detail address with a trailing slash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${SYNTHETIC_TRANSACTION_ID}/`,
  },
  {
    why: "a transaction detail address with an extra segment",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${SYNTHETIC_TRANSACTION_ID}/notes`,
  },
  {
    why: "a transaction identifier followed by an encoded slash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${SYNTHETIC_TRANSACTION_ID}%2Fnotes`,
  },
  {
    why: "a transaction identifier followed by an encoded backslash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${SYNTHETIC_TRANSACTION_ID}%5Cnotes`,
  },
  {
    why: "a percent-encoded character inside a transaction identifier",
    method: "GET",
    url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${PERCENT_ENCODED_TRANSACTION_ID}`,
  },
  // The case detail address, written non-canonically. Each of these is one
  // deviation from the address the relay now admits, and none is repaired into
  // it: a relay that case-folded, re-hyphenated or decoded an identifier would
  // be writing an address the application never asked for.
  {
    why: "an uppercase case identifier",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${UPPERCASE_CASE_ID}`,
  },
  {
    why: "a version 1 case identifier",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${VERSION_1_CASE_ID}`,
  },
  {
    why: "a case identifier with an invalid RFC variant",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${INVALID_VARIANT_CASE_ID}`,
  },
  {
    why: "an unhyphenated case identifier",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${UNHYPHENATED_CASE_ID}`,
  },
  {
    why: "a percent-encoded character inside a case identifier",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${PERCENT_ENCODED_CASE_ID}`,
  },
  {
    why: "a case detail address with a trailing slash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}/`,
  },
  {
    why: "a case identifier followed by an encoded slash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}%2Fnotes`,
  },
  {
    why: "a case identifier followed by an encoded backslash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}%5Cnotes`,
  },
  // The notes endpoint is an approved read only at its exact case identity.
  {
    why: "an uppercase case identifier on the notes endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${UPPERCASE_CASE_ID}/notes`,
  },
  {
    why: "a version 1 case identifier on the notes endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${VERSION_1_CASE_ID}/notes`,
  },
  {
    why: "an invalid RFC variant case identifier on the notes endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${INVALID_VARIANT_CASE_ID}/notes`,
  },
  {
    why: "an unhyphenated case identifier on the notes endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${UNHYPHENATED_CASE_ID}/notes`,
  },
  {
    why: "a percent-encoded case identifier on the notes endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${PERCENT_ENCODED_CASE_ID}/notes`,
  },
  {
    why: "a notes endpoint with a trailing slash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}/`,
  },
  {
    why: "a notes endpoint with an extra segment",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}/extra`,
  },
  {
    why: "an approved notes endpoint followed by an encoded slash and extra segment",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}%2Fextra`,
  },
  {
    why: "an approved notes endpoint followed by an encoded backslash and extra segment",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}%5Cextra`,
  },
  {
    why: "a fragment on the notes endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}#content`,
  },
  // The audit endpoint is now an approved read, so its own path identity is
  // fixed independently of the case detail path above.
  {
    why: "an uppercase case identifier on the audit endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${UPPERCASE_CASE_ID}/audit-logs`,
  },
  {
    why: "a version 1 case identifier on the audit endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${VERSION_1_CASE_ID}/audit-logs`,
  },
  {
    why: "an invalid RFC variant case identifier on the audit endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${INVALID_VARIANT_CASE_ID}/audit-logs`,
  },
  {
    why: "an unhyphenated case identifier on the audit endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${UNHYPHENATED_CASE_ID}/audit-logs`,
  },
  {
    why: "a percent-encoded case identifier on the audit endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${PERCENT_ENCODED_CASE_ID}/audit-logs`,
  },
  {
    why: "an audit endpoint with a trailing slash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}/`,
  },
  {
    why: "an audit endpoint with an extra segment",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}/extra`,
  },
  {
    why: "an audit identifier followed by an encoded slash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}%2Faudit-logs`,
  },
  {
    why: "an audit identifier followed by an encoded backslash",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}%5Caudit-logs`,
  },
  {
    why: "a fragment on the audit endpoint",
    method: "GET",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}#history`,
  },
  // Method confusion on the one address the relay now reads. It is a `GET` and
  // only a `GET`: the same address reached by any write method is refused
  // before a process is spawned, so admitting the read admitted no write.
  {
    why: "the case detail address posted to",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}`,
  },
  {
    why: "the case detail address patched",
    method: "PATCH",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}`,
  },
  {
    why: "the case detail address put",
    method: "PUT",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}`,
  },
  {
    why: "the case detail address deleted",
    method: "DELETE",
    url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}`,
  },
  {
    why: "the audit endpoint patched",
    method: "PATCH",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}`,
  },
  {
    why: "the audit endpoint put",
    method: "PUT",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}`,
  },
  {
    why: "the audit endpoint deleted",
    method: "DELETE",
    url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}`,
  },
  {
    why: "the notes endpoint patched",
    method: "PATCH",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}`,
  },
  {
    why: "the notes endpoint put",
    method: "PUT",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}`,
  },
  {
    why: "the notes endpoint deleted",
    method: "DELETE",
    url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}`,
  },
  // The write probe, which is one method at one address and nothing else.
  {
    why: "a case status write",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/status`,
  },
  {
    why: "a case assignee write",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/assignee`,
  },
  {
    why: "a case note create",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/notes`,
  },
  {
    why: "a case audit-log write",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/audit-logs`,
  },
  {
    why: "an unnamed write suffix under a case",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/anything`,
  },
  {
    why: "the resolution probe patched",
    method: "PATCH",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`,
  },
  {
    why: "the resolution probe put",
    method: "PUT",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`,
  },
  {
    why: "the resolution probe deleted",
    method: "DELETE",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`,
  },
  {
    why: "the resolution probe carrying a declared case filter",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}?caseStatus=OPEN`,
  },
  {
    why: "the resolution probe on an uppercase case identifier",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${UPPERCASE_CASE_ID}/resolution`,
  },
  {
    why: "the resolution probe on a version 1 case identifier",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${VERSION_1_CASE_ID}/resolution`,
  },
  {
    why: "the resolution probe on an invalid RFC variant case identifier",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}/${INVALID_VARIANT_CASE_ID}/resolution`,
  },
  {
    why: "the resolution probe with a trailing slash",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}/`,
  },
  {
    why: "the resolution probe with an extra segment",
    method: "POST",
    url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}/confirm`,
  },
];

/**
 * The closed endpoint allowlist, stated as the addresses it closes.
 *
 * Separate from the query test above because it is a separate claim. That one
 * says a relayed query is bounded; this one says a relayed *address* is, with a
 * query or without one. Both are asserted through `relayToBackend` rather than
 * through the resolver alone, because "refused" here has to mean refused before
 * `docker compose exec` is spawned and before `/dev/tcp` is opened, and the two
 * counters are the only way to observe that difference.
 */
test("the Backend relay refuses every endpoint it was not approved to reach", () => {
  requireCondition(
    REFUSED_UNAPPROVED_ENDPOINTS.length === 70,
    "The relay endpoint-refusal matrix drifted.",
  );
  requireUniqueRelayDeclarations(REFUSED_UNAPPROVED_ENDPOINTS, "relay endpoint-refusal");
  const spawnsBefore = relaySpawnCount;
  const observationsBefore = relayObservationCount;

  for (const refused of REFUSED_UNAPPROVED_ENDPOINTS) {
    let message: string | null = null;
    try {
      relayToBackend(relayCandidate(refused.method, refused.url));
    } catch (error: unknown) {
      message = error instanceof Error ? error.message : "unknown";
    }
    requireCondition(message !== null, `The relay accepted ${refused.why}.`);
    requireCondition(
      RELAY_REFUSALS.includes(message),
      `The relay refused ${refused.why} with a message that is not a fixed sentence.`,
    );
    // The refusal names the rule and nothing else. Neither the address, nor any
    // identifier inside it, nor any query it carried appears in what a run
    // would print.
    const url = new URL(refused.url);
    const reflected = [
      url.href,
      url.host,
      url.pathname,
      url.search,
      url.username,
      url.password,
      ...url.pathname.split("/").filter((segment) => segment !== ""),
      ...[...new URLSearchParams(url.search).entries()].flat(),
    ].filter((value) => value !== "");
    requireCondition(
      !reflected.some((value) => message.includes(value)),
      `The relay reflected part of ${refused.why} into its failure.`,
    );
  }

  // Nothing was written to the Backend, and nothing was recorded as having
  // been.
  requireCondition(
    relaySpawnCount === spawnsBefore,
    "An unapproved Backend endpoint spawned a relay process.",
  );
  requireCondition(
    relayObservationCount === observationsBefore,
    "An unapproved Backend endpoint was recorded as a Backend observation.",
  );
});

/**
 * The requests the relay must keep admitting, in the exact form the application
 * sends them.
 *
 * A closed endpoint allowlist is only worth having if it did not also close the
 * door on the reads and the one authorization probe this suite depends on. The
 * twelve reads are the two collection addresses with and without their real
 * queries, the two identified detail addresses, and the bare and canonical
 * page/size/sort notes and audit reads; the one write is the case resolution probe. These are
 * resolved rather than relayed - the target is compared, no socket is opened -
 * so the assertion is about the boundary and not about the Backend.
 *
 * Each target is also compared against the address it was asked for, byte for
 * byte. The relay forwards what the application wrote; it does not normalise,
 * re-encode or reorder it on the way.
 */
test("the Backend relay still admits the real reads and the one declared write probe", () => {
  const spawnsBefore = relaySpawnCount;
  const admitted: readonly {
    readonly method: string;
    readonly url: string;
    readonly target: string;
  }[] = [
    {
      method: "GET",
      url: `${BACKEND_ORIGIN}${INITIAL_TRANSACTION_TARGET}`,
      target: INITIAL_TRANSACTION_TARGET,
    },
    {
      method: "GET",
      url: `${BACKEND_ORIGIN}${APPLIED_TRANSACTION_TARGET}`,
      target: APPLIED_TRANSACTION_TARGET,
    },
    {
      // The bare collection address. A read with no query is admitted by the
      // same exact-address rule as one with a query, not by skipping it.
      method: "GET",
      url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}`,
      target: TRANSACTION_LIST_PATH,
    },
    { method: "GET", url: `${BACKEND_ORIGIN}${INITIAL_CASE_TARGET}`, target: INITIAL_CASE_TARGET },
    { method: "GET", url: `${BACKEND_ORIGIN}${APPLIED_CASE_TARGET}`, target: APPLIED_CASE_TARGET },
    { method: "GET", url: `${BACKEND_ORIGIN}${CASE_LIST_PATH}`, target: CASE_LIST_PATH },
    {
      method: "GET",
      url: `${BACKEND_ORIGIN}${TRANSACTION_LIST_PATH}/${SYNTHETIC_TRANSACTION_ID}`,
      target: `${TRANSACTION_LIST_PATH}/${SYNTHETIC_TRANSACTION_ID}`,
    },
    {
      // The identified case address, in exactly the form the detail screen
      // sends it: one canonical lowercase UUID v4 segment, no query, no
      // fragment and no trailing slash. This is the one address this Issue
      // added, and it is admitted by the same exact-address rule as the rest.
      method: "GET",
      url: `${BACKEND_ORIGIN}${CASE_DETAIL_TARGET}`,
      target: CASE_DETAIL_TARGET,
    },
    {
      method: "GET",
      url: `${BACKEND_ORIGIN}${CASE_AUDIT_TARGET}`,
      target: CASE_AUDIT_TARGET,
    },
    {
      method: "GET",
      url: `${BACKEND_ORIGIN}${CASE_NOTES_TARGET}`,
      target: CASE_NOTES_TARGET,
    },
    {
      method: "GET",
      url: `${BACKEND_ORIGIN}${INITIAL_CASE_NOTES_TARGET}`,
      target: INITIAL_CASE_NOTES_TARGET,
    },
    {
      method: "GET",
      url: `${BACKEND_ORIGIN}${INITIAL_CASE_AUDIT_TARGET}`,
      target: INITIAL_CASE_AUDIT_TARGET,
    },
    {
      method: "POST",
      url: `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`,
      target: CASE_RESOLUTION_PATH,
    },
  ];

  requireCondition(admitted.length === 13, "The relay positive admission matrix drifted.");
  requireCondition(
    new Set(admitted.map((entry) => `${entry.method}\u0000${entry.url}`)).size === admitted.length,
    "The relay positive admission matrix contains a duplicate method and URL.",
  );
  requireCondition(
    new Set(admitted.map((entry) => entry.target)).size === admitted.length,
    "The relay positive admission matrix contains a duplicate target.",
  );

  for (const candidate of admitted) {
    const resolved = resolveRelayTarget(relayCandidate(candidate.method, candidate.url));
    requireCondition(
      resolved === candidate.target,
      "The relay changed or refused a request the application really sends.",
    );
    // Byte for byte what was asked for. The resolver returns a target rather
    // than rebuilding one, so a re-encoded comma, a case fold or a dropped
    // parameter would show up here as a different string.
    const asked = new URL(candidate.url);
    requireCondition(
      resolved === `${asked.pathname}${asked.search}`,
      "The relay rewrote a request the application really sends.",
    );
  }
  requireCondition(
    relaySpawnCount === spawnsBefore,
    "Resolving a relay target spawned a relay process.",
  );
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

test("a real USER opens a transaction detail address and meets the real Backend 404", async ({
  page,
}) => {
  const password = readUserPassword();
  const consoleMessages: string[] = [];
  page.on("console", (message) => consoleMessages.push(message.text()));

  requireCondition(
    CANONICAL_UUID_V4.test(SYNTHETIC_TRANSACTION_ID),
    "The synthetic transaction identifier is not a canonical UUID v4.",
  );
  const detailRoute = `/transactions/${SYNTHETIC_TRANSACTION_ID}`;
  const detailPath = `${TRANSACTION_LIST_PATH}/${SYNTHETIC_TRANSACTION_ID}`;
  // The detail endpoint's answer is kept in this process so the assertions at
  // the end can ask whether what Spring Boot actually said reached the screen.
  // Nothing else about the relay changes: the same bytes reach the browser
  // either way, and no sentinel is injected into them.
  const backend = await installBackendRelay(page, { captureBodyOf: detailPath });
  const detailRequests = () =>
    backend.filter((entry) => entry.method === "GET" && entry.pathname === detailPath);

  // A direct visit to the detail address while signed out. The guard removes
  // the screen, and nothing is asked of the Backend: no credential lookup, no
  // request, no probe.
  await page.goto(`${APP_ORIGIN}${detailRoute}`);
  await expect(page.getByRole("heading", { name: "Sign in required" })).toBeVisible();
  requireCondition(backend.length === 0, "An unauthenticated detail address reached the Backend.");
  requireCondition((await publicationCount(page)) === 0, "A session existed before sign-in.");

  // Signing in from that address, against the real Keycloak.
  const tokenResponsePromise = page.waitForResponse(
    (response) => response.url() === TOKEN_URL && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator("#username")).toBeVisible({ timeout: 30_000 });
  await page.locator("#username").fill(USERNAME);
  await page.locator("#password").fill(password);
  await submitLogin(page);
  const tokens = parseTokenResponse(await (await tokenResponsePromise).json());
  requireTokenClaims(tokens);

  // The return route, decided by the literal allowlist: back to exactly the
  // canonical detail address, with no query and no fragment added to it.
  await page.waitForFunction(
    (expected) => window.location.href === expected,
    `${APP_ORIGIN}${detailRoute}`,
  );
  await expect(page.getByLabel("Authentication status")).toContainText("Signed in as");
  await expect(
    page.getByRole("heading", { name: `Transaction ${SYNTHETIC_TRANSACTION_ID}`, level: 2 }),
  ).toBeVisible();

  // One authorized request to the real detail endpoint, answered by Spring Boot.
  await expect(page.getByRole("alert")).toBeVisible({ timeout: 15_000 });
  const requested = detailRequests();
  requireCondition(requested.length === 1, "The transaction detail was not requested exactly once.");
  requireCondition(requested[0].target === detailPath, "The detail request carried a query string.");
  requireCondition(requested[0].status === 404, "The real transaction detail request did not return 404.");

  // The fixed not-found screen, and not one field of a record.
  await expect(page.getByRole("alert")).toContainText("Transaction not found");
  await expect(page.getByRole("main").getByRole("status")).toContainText("No record shown.");
  requireCondition(
    (await page.getByRole("main").locator("dd").count()) === 0,
    "A transaction that does not exist still rendered record fields.",
  );
  // Nothing the Backend answered with is on screen, and nothing invented is
  // either: no status code, no trace id, no risk or detection language.
  const screenText = (await page.getByRole("main").textContent()) ?? "";
  for (const forbidden of ["404", "risk", "Risk", "score", "Detection", "Evidence", "traceId"]) {
    requireCondition(!screenText.includes(forbidden), "The not-found screen disclosed more than it should.");
  }

  // A 404 is not a session verdict: the analyst is still signed in and can
  // still leave the way they came.
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Back to transactions" })).toBeVisible();
  requireCondition((await publicationCount(page)) === 1, "The 404 changed the published session.");

  // Nothing retries on its own: the count is unchanged after the screen has
  // been sitting there, and no Retry control was offered for a 404.
  requireCondition(
    (await page.getByRole("button", { name: "Try again" }).count()) === 0,
    "A transaction that does not exist offered a retry.",
  );
  await page.waitForTimeout(1_000);
  requireCondition(detailRequests().length === 1, "The detail screen retried or polled on its own.");

  // The address bar holds the transaction identifier and nothing else, and the
  // credentials reached neither the document, the URL, Web Storage nor the
  // console.
  const addressBar = new URL(page.url());
  requireCondition(
    addressBar.origin === APP_ORIGIN &&
      addressBar.pathname === detailRoute &&
      addressBar.search === "" &&
      addressBar.hash === "",
    "The detail address carried more than the canonical route.",
  );
  const sensitive = [password, tokens.accessToken, tokens.idToken];
  requireCondition(!(await browserContainsAny(page, sensitive)), "A credential reached DOM, URL, or Web Storage.");
  requireCondition(
    !consoleMessages.some((message) => sensitive.some((value) => value !== "" && message.includes(value))),
    "A credential reached the browser console.",
  );
  requireCondition(
    backend.filter((entry) => entry.method !== "GET").length === 0,
    "The transaction detail screen sent a business mutation.",
  );

  // What the Backend actually answered, read from the relayed body rather than
  // assumed. Every one of these values exists; none of them is for a reader.
  const backendError = readBackendErrorFields(requested[0].body);
  for (const value of [backendError.code, backendError.message, backendError.traceId]) {
    requireCondition(
      !NOT_FOUND_SCREEN_COPY.some((copy) => copy.includes(value)),
      "A fixed console phrase contains a Backend error value, so non-reflection cannot be proven.",
    );
  }
  // Each field, checked on its own so the refusal can name which boundary broke
  // without ever naming the value that crossed it.
  requireCondition(!(await documentExposes(page, backendError.code)), "Backend error code was exposed.");
  requireCondition(
    !(await documentExposes(page, backendError.message)),
    "Backend error message was exposed.",
  );
  requireCondition(
    !(await documentExposes(page, backendError.traceId)),
    "Backend trace identifier was exposed.",
  );
  requireCondition(
    !consoleMessages.some((entry) => entry.includes(backendError.code)),
    "Backend error code was exposed.",
  );
  requireCondition(
    !consoleMessages.some((entry) => entry.includes(backendError.message)),
    "Backend error message was exposed.",
  );
  requireCondition(
    !consoleMessages.some((entry) => entry.includes(backendError.traceId)),
    "Backend trace identifier was exposed.",
  );

  // The design widths, on the live not-found screen.
  for (const viewport of CONSOLE_VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await expect(page.locator("header.rail")).toBeVisible();
    requireCondition(
      !(await documentOverflowsHorizontally(page)),
      `The detail page scrolled horizontally at ${String(viewport.width)}px.`,
    );
  }
  await page.setViewportSize({ width: 1440, height: 900 });
});

/**
 * The case list, over the same real Keycloak session and the same real Spring
 * Boot as the ledger screens.
 *
 * Deliberately a second screen rather than a second assertion on the first one:
 * `/cases` is guarded by its own capability, served by its own Backend endpoint
 * and filtered by its own query validator, and none of those is exercised by
 * the transaction test above. Nothing is mocked here either - no API body, no
 * auth bypass - so what the screen shows is what Spring Boot answered.
 *
 * This runtime holds no seeded case rows, so the screen is allowed to settle on
 * a deterministic empty state. That is a real 200 from a real endpoint, and it
 * is the honest evidence available here; the populated table is proved by the
 * component and hook tests against the typed API contract, and no fixture is
 * injected to manufacture one.
 */
test("a real USER reaches the case console over the real Backend", async ({ page }) => {
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

  // The case navigation, decided from the real role claim of a real Keycloak
  // session rather than from a fixture.
  const casesLink = page.getByRole("link", { name: "Cases" });
  await expect(casesLink).toBeVisible();
  await casesLink.click();
  await page.waitForFunction((expected) => window.location.href === expected, `${APP_ORIGIN}/cases`);
  await expect(page.getByRole("heading", { name: "Cases", level: 2 })).toBeVisible();
  await expect(casesLink).toHaveAttribute("aria-current", "page");

  // The opening query, answered by the real Spring Boot endpoint.
  const results = page.getByRole("main").getByRole("status");
  await expect(results).not.toContainText("Loading cases", { timeout: 15_000 });
  await expect(page.getByRole("alert")).toHaveCount(0);

  const caseRequests = backend.filter(
    (entry) => entry.method === "GET" && entry.pathname === CASE_LIST_PATH,
  );
  requireCondition(caseRequests.length === 1, "The case list was not requested exactly once.");
  requireCondition(caseRequests[0].status === 200, "The real case list request did not return 200.");
  // The request target the Backend was actually asked for, not the one the
  // browser built: page 0, twenty rows, most recently changed first, no filter,
  // one value per name and nothing else, in the canonical order and encoding
  // the query builder emits. Compared as one fixed string, so a failure names
  // the contract rather than printing the query.
  requireCondition(
    caseRequests[0].target === INITIAL_CASE_TARGET,
    "The opening case query that reached the Backend was not the exact default query.",
  );

  // Whatever this runtime holds, the screen converges on one of exactly two
  // states and never on a partial or error one.
  const summary = (await results.textContent()) ?? "";
  const showingRows = /^Showing \d+-\d+ of \d+ cases\.$/.test(summary.trim());
  const emptyResult = summary.trim() === "No cases found.";
  requireCondition(
    showingRows || emptyResult,
    `The case screen did not settle on a result state: ${summary.trim()}`,
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
      "A rendered case time carried no UTC machine-readable value.",
    );
    // No detail affordance in this Issue: the identifier is text, not a link.
    requireCondition(
      (await page.locator("tbody a").count()) === 0,
      "The case sheet offered a link this Issue does not implement.",
    );
  } else {
    await expect(page.getByText("There are no cases to show yet.")).toBeVisible();
  }

  // Nothing retries on its own: the count is unchanged after the screen has
  // been sitting there.
  await page.waitForTimeout(1_000);
  requireCondition(
    backend.filter((entry) => entry.method === "GET" && entry.pathname === CASE_LIST_PATH)
      .length === 1,
    "The case screen retried or polled on its own.",
  );

  // The design widths, on the live screen.
  for (const viewport of CONSOLE_VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await expect(page.locator("header.rail")).toBeVisible();
    await expect(casesLink).toBeVisible();
    const railWidth = await measuredRailWidth(page);
    requireCondition(
      railWidth === viewport.railWidth,
      `The navigation rail was ${String(railWidth)}px at ${String(viewport.width)}px.`,
    );
    const columns = await filterGridColumnCount(page);
    requireCondition(
      columns === viewport.filterColumns,
      `The case filter grid had ${String(columns)} columns at ${String(viewport.width)}px.`,
    );
    requireCondition(
      !(await documentOverflowsHorizontally(page)),
      `The case page scrolled horizontally at ${String(viewport.width)}px.`,
    );
  }
  await page.setViewportSize({ width: 1440, height: 900 });

  // Applying a filter is one more real request, carrying the filters, and
  // nothing else.
  const navigationBeforeApply = await navigationState(page);
  await page.getByLabel("Case status").selectOption("OPEN");
  await page.getByLabel("Assignee reference").fill(E2E_ASSIGNEE_REF);
  await page.getByRole("button", { name: "Apply filters" }).click();
  await expect(results).not.toContainText("Applying filters", { timeout: 15_000 });
  const filtered = backend.filter(
    (entry) => entry.method === "GET" && entry.pathname === CASE_LIST_PATH,
  );
  requireCondition(filtered.length === 2, "Applying a case filter did not send exactly one request.");
  requireCondition(filtered[1].status === 200, "The filtered case request did not return 200.");
  // Both filters, the page reset to 0, the unchanged size and sort, each name
  // once and nothing extra - and the reference exactly as typed, its inner
  // spaces and its capitalisation intact. A trim, a case fold or a dropped
  // filter anywhere between the field and the socket changes this string.
  requireCondition(
    filtered[1].target === APPLIED_CASE_TARGET,
    "The applied case query that reached the Backend was not the exact filtered query.",
  );
  await expect(page.getByRole("alert")).toHaveCount(0);

  // And still nothing retries: applying a filter sent one request, not one and
  // a repeat of it.
  await page.waitForTimeout(1_000);
  requireCondition(
    backend.filter((entry) => entry.method === "GET" && entry.pathname === CASE_LIST_PATH)
      .length === 2,
    "The case screen retried the filtered query on its own.",
  );

  // No filter value and no credential reaches the address bar, Web Storage or
  // the console.
  const addressBar = new URL(page.url());
  requireCondition(
    addressBar.origin === APP_ORIGIN &&
      addressBar.pathname === "/cases" &&
      addressBar.search === "" &&
      addressBar.hash === "",
    "A case filter reached the address bar.",
  );
  // Applying a filter is not navigation: the address, the history depth and the
  // history state are the ones from before Apply, so the filter is held in
  // component memory and nowhere a reload or a Back would reach it.
  requireCondition(
    (await navigationState(page)) === navigationBeforeApply,
    "Applying a case filter changed the browser location or history.",
  );
  // The reference belongs in the field the analyst typed it into and in the
  // Backend query asserted above. Everywhere else it is a leak. The sites are
  // named; the value is not.
  const leaks = await referenceLeakSites(page, E2E_ASSIGNEE_REF, "case-filter-assignee-ref");
  requireCondition(
    leaks.length === 0,
    `A case reference filter was recorded outside the field it was typed into: ${leaks.join(", ")}`,
  );
  requireCondition(
    !consoleMessages.some((message) => message.includes(E2E_ASSIGNEE_REF)),
    "A case reference filter reached the browser console.",
  );
  const sensitive = [password, tokens.accessToken, tokens.idToken];
  requireCondition(!(await browserContainsAny(page, sensitive)), "A credential reached DOM, URL, or Web Storage.");
  requireCondition(
    !consoleMessages.some((message) => sensitive.some((value) => value !== "" && message.includes(value))),
    "A credential reached the browser console.",
  );
  // A read-only screen: no status change, no reassignment, no resolution.
  requireCondition(
    backend.filter((entry) => entry.method !== "GET").length === 0,
    "The case screen sent a business mutation.",
  );
});

/**
 * One case, over the real Keycloak session and the real Spring Boot.
 *
 * The twin of the transaction detail test, for the second identified read this
 * console makes. The identifier is synthetic and canonical, so Spring Boot
 * really answers 404: nothing is seeded, no fixture is inserted and no response
 * is invented, which is what makes the not-found screen evidence about the
 * application rather than about a mock. The populated 200 screen is proved by
 * the component and hook tests against the typed API contract, and an API mock
 * is never presented here as real-Backend evidence.
 */
test("a real USER opens a case detail address and meets the real Backend 404", async ({
  page,
}) => {
  const password = readUserPassword();
  const consoleMessages: string[] = [];
  page.on("console", (message) => consoleMessages.push(message.text()));

  requireCondition(
    CANONICAL_UUID_V4.test(SYNTHETIC_CASE_ID),
    "The synthetic case identifier is not a canonical UUID v4.",
  );
  const detailRoute = `/cases/${SYNTHETIC_CASE_ID}`;
  // The detail endpoint's answer is kept in this process so the assertions at
  // the end can ask whether what Spring Boot actually said reached the screen.
  // Nothing else about the relay changes: the same bytes reach the browser
  // either way, and no sentinel is injected into them.
  const parallelStartBarrier = new ParallelStartBarrier(
    [
      { method: "GET", target: CASE_DETAIL_TARGET },
      { method: "GET", target: INITIAL_CASE_NOTES_TARGET },
      { method: "GET", target: INITIAL_CASE_AUDIT_TARGET },
    ],
    PARALLEL_START_TIMEOUT_MS,
  );
  const backend = await installBackendRelay(page, {
    captureBodyOf: [CASE_DETAIL_TARGET, CASE_NOTES_TARGET, CASE_AUDIT_TARGET],
    parallelStartBarrier,
  });
  try {
  const detailRequests = () =>
    backend.filter((entry) => entry.method === "GET" && entry.pathname === CASE_DETAIL_TARGET);
  const auditRequests = () =>
    backend.filter((entry) => entry.method === "GET" && entry.pathname === CASE_AUDIT_TARGET);
  const noteRequests = () =>
    backend.filter((entry) => entry.method === "GET" && entry.pathname === CASE_NOTES_TARGET);

  // A direct visit to the detail address while signed out. The guard removes
  // the screen, and nothing is asked of the Backend: no credential lookup, no
  // request, no probe.
  await page.goto(`${APP_ORIGIN}${detailRoute}`);
  await expect(page.getByRole("heading", { name: "Sign in required" })).toBeVisible();
  requireCondition(backend.length === 0, "An unauthenticated case address reached the Backend.");
  requireCondition((await publicationCount(page)) === 0, "A session existed before sign-in.");

  // Signing in from that address, against the real Keycloak.
  const tokenResponsePromise = page.waitForResponse(
    (response) => response.url() === TOKEN_URL && response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator("#username")).toBeVisible({ timeout: 30_000 });
  await page.locator("#username").fill(USERNAME);
  await page.locator("#password").fill(password);
  parallelStartBarrier.start();
  await submitLogin(page);
  const tokens = parseTokenResponse(await (await tokenResponsePromise).json());
  requireTokenClaims(tokens);

  // The return route, decided by the allowlist from the validated identifier:
  // back to exactly the canonical detail address, with no query and no fragment
  // added to it.
  await page.waitForFunction(
    (expected) => window.location.href === expected,
    `${APP_ORIGIN}${detailRoute}`,
  );
  await expect(page.getByLabel("Authentication status")).toContainText("Signed in as");
  await expect(
    page.getByRole("heading", { name: `Case ${SYNTHETIC_CASE_ID}`, level: 2 }),
  ).toBeVisible();

  // One authorized request to the real case detail endpoint, answered by
  // Spring Boot.
  await parallelStartBarrier.completion;
  await expect(page.getByRole("alert")).toBeVisible({ timeout: 15_000 });
  const releasedBarrier = parallelStartBarrier.snapshot();
  requireCondition(
    releasedBarrier.state === "released" &&
      releasedBarrier.expectedTargetCount === 3 &&
      releasedBarrier.arrivedTargetCount === 3,
    "The detail, notes and audit reads did not all reach the relay before forwarding.",
  );
  requireCondition(
    releasedBarrier.completionResolveCount === 1 &&
      releasedBarrier.completionRejectCount === 0 &&
      releasedBarrier.timeoutCallbackCount === 0 &&
      releasedBarrier.pendingWaiterCount === 0 &&
      releasedBarrier.activeTimerCount === 0 &&
      releasedBarrier.activeCallbackCount === 0,
    "The released parallel-start barrier retained work or settled more than once.",
  );
  requireCondition(
    backend.barrierArrivalCountAtFirstForwarding() === 3,
    "A case read reached Backend forwarding before all three exact targets arrived.",
  );
  const requested = detailRequests();
  requireCondition(requested.length === 1, "The case detail was not requested exactly once.");
  requireCondition(
    requested[0].target === CASE_DETAIL_TARGET,
    "The case detail request carried a query string.",
  );
  requireCondition(requested[0].status === 404, "The real case detail request did not return 404.");
  await expect.poll(() => noteRequests().length).toBe(1);
  const notesRequested = noteRequests();
  requireCondition(notesRequested.length === 1, "The case notes were not requested exactly once.");
  requireCondition(
    notesRequested[0].target === INITIAL_CASE_NOTES_TARGET,
    "The notes request target was not the exact initial page query.",
  );
  requireCondition(
    notesRequested[0].status === 404,
    "The real case notes request did not return 404.",
  );
  await expect.poll(() => auditRequests().length).toBe(1);
  const auditRequested = auditRequests();
  requireCondition(auditRequested.length === 1, "The case audit history was not requested exactly once.");
  requireCondition(
    auditRequested[0].target === INITIAL_CASE_AUDIT_TARGET,
    "The audit request target was not the exact initial page query.",
  );
  requireCondition(
    auditRequested[0].status === 404,
    "The real case audit request did not return 404.",
  );

  // The fixed not-found screen, and not one field of a record.
  await expect(page.getByRole("alert")).toContainText("Case not found");
  await expect(page.getByRole("main").getByRole("status")).toContainText("No record shown.");
  await expect(page.getByRole("heading", { name: "Investigation notes" })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Audit history" })).toHaveCount(0);
  requireCondition(
    (await page.getByRole("main").locator("dd").count()) === 0,
    "A case that does not exist still rendered record fields.",
  );
  // Nothing the Backend answered with is on screen, and nothing invented is
  // either: no status code, no trace id, and none of the case vocabulary this
  // Issue does not implement.
  const screenText = (await page.getByRole("main").textContent()) ?? "";
  for (const forbidden of [
    "404",
    "risk",
    "Risk",
    "score",
    "Detection",
    "Evidence",
    "traceId",
    "concurrencyVersion",
    "Audit",
    "AI report",
  ]) {
    requireCondition(!screenText.includes(forbidden), "The not-found screen disclosed more than it should.");
  }

  // A 404 is not a session verdict: the analyst is still signed in and can
  // still leave the way they came.
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Back to cases" })).toBeVisible();
  requireCondition((await publicationCount(page)) === 1, "The 404 changed the published session.");

  // The rail announces the case section as the current one at a canonical
  // detail address, and says nothing about the ledger.
  const railCases = page.getByRole("link", { name: "Cases", exact: true });
  await expect(railCases).toHaveAttribute("href", "/cases");
  await expect(railCases).toHaveAttribute("aria-current", "page");
  requireCondition(
    (await page.locator('[aria-current="page"]').count()) === 1,
    "More than one navigation item claimed to be the current page.",
  );
  requireCondition(
    (await page.locator('[aria-current="false"]').count()) === 0,
    "A navigation item carried aria-current as the string false.",
  );

  // Nothing retries on its own: the count is unchanged after the screen has
  // been sitting there, and no Retry control was offered for a 404.
  requireCondition(
    (await page.getByRole("button", { name: "Try again" }).count()) === 0,
    "A case that does not exist offered a retry.",
  );
  await page.waitForTimeout(1_000);
  requireCondition(detailRequests().length === 1, "The case screen retried or polled on its own.");
  requireCondition(noteRequests().length === 1, "The notes section retried or polled on its own.");
  requireCondition(auditRequests().length === 1, "The audit section retried or polled on its own.");

  // The address bar holds the case identifier and nothing else, and the
  // credentials reached neither the document, the URL, Web Storage nor the
  // console.
  const addressBar = new URL(page.url());
  requireCondition(
    addressBar.origin === APP_ORIGIN &&
      addressBar.pathname === detailRoute &&
      addressBar.search === "" &&
      addressBar.hash === "",
    "The case detail address carried more than the canonical route.",
  );
  const cookieValues = (await page.context().cookies(AUTHORITY))
    .map((cookie) => cookie.value)
    .filter((value) => value !== "");
  const sensitive = [password, tokens.accessToken, tokens.idToken, ...cookieValues];
  for (const value of sensitive) {
    requireCondition(!(await documentExposes(page, value)), "A credential reached a browser surface.");
    requireCondition(
      !consoleMessages.some((message) => message.includes(value)),
      "A credential reached the browser console.",
    );
  }
  // A read-only screen: no status change, no reassignment and no resolution.
  requireCondition(
    backend.filter((entry) => entry.method !== "GET").length === 0,
    "The case detail screen sent a business mutation.",
  );
  requireCondition(
    backend.every(
      (entry) =>
        entry.pathname === CASE_DETAIL_TARGET ||
        entry.pathname === CASE_NOTES_TARGET ||
        entry.pathname === CASE_AUDIT_TARGET ||
        entry.pathname === CASE_LIST_PATH,
    ),
    "The case detail screen reached an endpoint outside the case read contract.",
  );

  // What the Backend actually answered, read from the relayed body rather than
  // assumed. Every one of these values exists; none of them is for a reader.
  const backendErrors = [
    readBackendErrorFields(requested[0].body),
    readBackendErrorFields(notesRequested[0].body),
    readBackendErrorFields(auditRequested[0].body),
  ];
  for (const backendError of backendErrors) {
    for (const value of [backendError.code, backendError.message, backendError.traceId]) {
      requireCondition(
        !CASE_NOT_FOUND_SCREEN_COPY.some((copy) => copy.includes(value)),
        "A fixed console phrase contains a Backend error value, so non-reflection cannot be proven.",
      );
      requireCondition(!(await documentExposes(page, value)), "A Backend error field was exposed.");
      requireCondition(
        !consoleMessages.some((entry) => entry.includes(value)),
        "A Backend error field was exposed.",
      );
    }
  }

  // The design widths, on the live not-found screen.
  for (const viewport of CONSOLE_VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await expect(page.locator("header.rail")).toBeVisible();
    requireCondition(
      !(await documentOverflowsHorizontally(page)),
      `The case detail page scrolled horizontally at ${String(viewport.width)}px.`,
    );
  }
  await page.setViewportSize({ width: 1440, height: 900 });

  // The way back really is the list. This request happens after barrier
  // release, so it must pass through the normal relay rather than being
  // enrolled or aborted by the completed three-read rendezvous.
  const observationsBeforeReturn = backend.length;
  const caseListRequestsBeforeReturn = backend.filter(
    (entry) => entry.method === "GET" && entry.pathname === CASE_LIST_PATH,
  ).length;
  const barrierBeforeReturn = parallelStartBarrier.snapshot();
  const barrierAbortsBeforeReturn = backend.barrierAbortCount();
  await page.getByRole("link", { name: "Back to cases" }).click();
  await page.waitForFunction((expected) => window.location.href === expected, `${APP_ORIGIN}/cases`);
  await expect
    .poll(
      () =>
        backend.filter(
          (entry) => entry.method === "GET" && entry.pathname === CASE_LIST_PATH,
        ).length,
    )
    .toBe(caseListRequestsBeforeReturn + 1);
  requireCondition(
    backend.length === observationsBeforeReturn + 1,
    "Returning to cases did not add exactly one Backend observation.",
  );
  const returnedCaseListRequest = backend[observationsBeforeReturn];
  requireCondition(
    returnedCaseListRequest.method === "GET" &&
      returnedCaseListRequest.pathname === CASE_LIST_PATH &&
      returnedCaseListRequest.target === INITIAL_CASE_TARGET &&
      returnedCaseListRequest.requestBodyByteLength === 0 &&
      returnedCaseListRequest.status === 200,
    "Returning to cases did not forward the exact bodyless initial list read.",
  );
  requireUnchangedParallelStartSnapshot(
    parallelStartBarrier,
    barrierBeforeReturn,
    "The case-list request changed the released parallel-start barrier.",
  );
  requireCondition(
    barrierAbortsBeforeReturn === 0 && backend.barrierAbortCount() === 0,
    "The parallel-start barrier aborted a request after release.",
  );
  requireCondition(
    backend.filter((entry) => entry.method !== "GET").length === 0,
    "Returning to cases sent a business mutation.",
  );
  await expect(page.getByRole("heading", { name: "Cases", level: 2 })).toBeVisible();
  requireCondition(
    !(await documentOverflowsHorizontally(page)),
    "The case list scrolled horizontally after returning from the detail screen.",
  );
  requireCondition((await publicationCount(page)) === 1, "Returning to the list changed the session.");
  for (const value of sensitive) {
    requireCondition(!(await documentExposes(page, value)), "A credential reached the returned list.");
    requireCondition(
      !consoleMessages.some((message) => message.includes(value)),
      "A credential reached the browser console after returning to cases.",
    );
  }
  } finally {
    await backend.dispose();
    const disposedBarrier = parallelStartBarrier.snapshot();
    requireCondition(
      backend.isDisposed() &&
        backend.pendingHandlerCount() === 0 &&
        backend.listenerCount() === 0 &&
        disposedBarrier.state === "disposed" &&
        disposedBarrier.expectedTargetCount === 0 &&
        disposedBarrier.arrivedTargetCount === 0 &&
        disposedBarrier.pendingWaiterCount === 0 &&
        disposedBarrier.activeTimerCount === 0 &&
        disposedBarrier.activeCallbackCount === 0,
      "The case relay or parallel-start barrier retained cleanup work.",
    );
  }
});

/**
 * The address of the test-only geometry fixture, served by the same Vite dev
 * server that serves the application.
 *
 * A real origin and a real module graph: the page imports the production
 * `CaseTable` and the production `app.css`, and Vite transforms and serves both
 * exactly as it does for the console itself. What it does not have is a
 * transport - the fixture makes no request, so there is no API to mock, no
 * route to intercept and no session to bypass.
 */
const CASE_TABLE_GEOMETRY_URL = `${APP_ORIGIN}/e2e/case-table-geometry.html`;
const CASE_AUDIT_GEOMETRY_URL = `${APP_ORIGIN}/e2e/case-audit-geometry.html`;
const CASE_NOTES_GEOMETRY_URL =
  `${APP_ORIGIN}/e2e/case-investigation-notes-geometry.html`;

/** The 128-character assignee reference the fixture renders. Backend's bound. */
const GEOMETRY_ASSIGNEE_REF =
  "e2e-geometry-assignee-reference-" +
  "e2e-geometry-assignee-reference-" +
  "e2e-geometry-assignee-reference-" +
  "e2e-geometry-assignee-reference-";

/** The canonical identifier of the fixture's first row. */
const GEOMETRY_CASE_ID = "0a1b2c3d-4e5f-4a6b-8c9d-0e1f2a3b4c5d";

interface CaseSheetGeometry {
  readonly documentScrollWidth: number;
  readonly documentClientWidth: number;
  readonly bodyScrollWidth: number;
  readonly containerClientWidth: number;
  readonly containerLeft: number;
  readonly containerRight: number;
  readonly mainLeft: number;
  readonly mainRight: number;
  readonly viewportWidth: number;
  readonly tableScrollWidth: number;
  readonly tableWidth: number;
  readonly tableMinWidth: string;
  readonly overflowX: string;
  readonly headerCells: number;
  readonly firstRowCells: number;
  readonly hiddenCells: number;
  readonly rows: number;
}

async function measureCaseSheet(page: Page): Promise<CaseSheetGeometry> {
  const measured = await page.evaluate(() => {
    const container = document.querySelector(".sheet--cases .sheet__scroll");
    const main = document.querySelector("main.main");
    const table = container?.querySelector("table") ?? null;
    if (container === null || main === null || table === null) {
      return null;
    }
    const containerBox = container.getBoundingClientRect();
    const mainBox = main.getBoundingClientRect();
    const hiddenCells = [...document.querySelectorAll("thead th, tbody td")].filter((cell) => {
      const style = window.getComputedStyle(cell);
      return style.display === "none" || style.visibility === "hidden";
    }).length;
    return {
      documentScrollWidth: document.documentElement.scrollWidth,
      documentClientWidth: document.documentElement.clientWidth,
      bodyScrollWidth: document.body.scrollWidth,
      containerClientWidth: container.clientWidth,
      containerLeft: containerBox.left,
      containerRight: containerBox.right,
      mainLeft: mainBox.left,
      mainRight: mainBox.right,
      viewportWidth: window.innerWidth,
      tableScrollWidth: table.scrollWidth,
      tableWidth: table.getBoundingClientRect().width,
      tableMinWidth: window.getComputedStyle(table).minWidth,
      overflowX: window.getComputedStyle(container).overflowX,
      headerCells: document.querySelectorAll("thead th").length,
      firstRowCells: document.querySelectorAll("tbody tr:first-child td").length,
      hiddenCells,
      rows: document.querySelectorAll("tbody tr").length,
    };
  });
  requireCondition(measured !== null, "The case sheet, its scroll container or its table was absent.");
  return measured;
}

/**
 * The populated case sheet, measured in a real browser.
 *
 * This is deliberately *not* a Backend test and must never be reported as one.
 * The runtime seeds no fraud cases, so the real-Backend case test above meets a
 * genuine empty 200 and an empty `tbody`, which cannot overflow anything. The
 * two honest options are to invent a response body - which would make the
 * real-Backend test a test of the invention - or to render the production
 * component itself with rows in it and measure that. This is the second: the
 * fixture imports the production `CaseTable` and the production `app.css`,
 * mounts them with `createRoot`, and issues no request at all.
 *
 * What it proves is the claim the stylesheet makes and the README repeats: at
 * every console width the sheet keeps all seven columns, scrolls sideways
 * inside its own container when it must, and never pushes the document. Along
 * the way it also renders every branch of the final-disposition column - the
 * three verdicts and the unresolved `null` - so the words an analyst reads
 * under each are observed rather than assumed.
 */
test("the populated case sheet scrolls inside its container and never the document", async ({
  page,
}) => {
  // Nothing may leave this page. Recorded rather than asserted at the end
  // alone, so a request would name itself.
  const offPageRequests: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.origin !== APP_ORIGIN) {
      offPageRequests.push(url.origin);
    }
  });
  const spawnsBefore = relaySpawnCount;
  const observationsBefore = relayObservationCount;

  await page.goto(CASE_TABLE_GEOMETRY_URL);

  // A real table, with real rows in it, rendered by the production component.
  const table = page.getByRole("table");
  await expect(table).toBeVisible();
  const rows = page.locator("tbody tr");
  await expect(rows).toHaveCount(5);

  // Every final-disposition branch the column can take, actually rendered, and
  // each one in the row that carries it. The expected words are written out
  // here rather than read back from `CASE_FINAL_DISPOSITION_LABELS`: reusing
  // the production map as the expectation would make this assertion agree with
  // any renaming, including one that showed a resolved-normal case as a
  // confirmed fraud. The third column is "Final disposition".
  const dispositions: readonly string[] = [
    "Not resolved",
    "Confirmed fraud",
    "Not resolved",
    "False positive",
    "Normal",
  ];
  for (const [index, expected] of dispositions.entries()) {
    await expect(rows.nth(index).locator("td").nth(2)).toHaveText(expected);
  }

  // The two values that decide the width of the two widest columns are in
  // actual cells, in full. The identifier is inside its detail anchor, whose
  // accessible name states the anchor's purpose and names the case, and whose
  // `href` is the canonical case route - no query, no fragment, no trailing
  // slash.
  const identifierCell = page.locator("td.cell-ref--id").first();
  const identifierLink = identifierCell.locator("a");
  await expect(identifierLink).toHaveAttribute("href", `/cases/${GEOMETRY_CASE_ID}`);
  await expect(identifierLink).toHaveAccessibleName(
    `View case details for ${GEOMETRY_CASE_ID}`,
  );
  // The identifier is in the cell once and only once, and the cell's text is
  // the identifier alone: the anchor's purpose is carried by `aria-label`, not
  // by a `.visually-hidden` prefix that would be laid out - absolutely
  // positioned, in an unpositioned scroll container - past the edge of the
  // document at the narrowest console width. What must not happen is the
  // identifier itself appearing twice in the text, which is what a `title`, a
  // hidden mirror or a duplicated cell would look like.
  const identifierCellText = (await identifierCell.textContent()) ?? "";
  requireCondition(
    identifierCellText.split(GEOMETRY_CASE_ID).length - 1 === 1,
    "The case identifier cell did not print the identifier exactly once.",
  );
  requireCondition(
    identifierCellText.trim() === GEOMETRY_CASE_ID,
    "The case identifier cell carried text beyond the identifier itself.",
  );
  const longReference = page.locator("td.cell-ref--long").first();
  await expect(longReference).toHaveText(GEOMETRY_ASSIGNEE_REF);
  requireCondition(
    ((await longReference.textContent()) ?? "").length === 128,
    "The fixture did not render an assignee reference at Backend's 128-character bound.",
  );

  for (const viewport of CONSOLE_VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    const geometry = await measureCaseSheet(page);
    const at = `${String(viewport.width)}px`;

    // Seven columns, every one of them rendered. Not hidden at a narrow width,
    // not collapsed away: an analyst is never shown a partial record.
    requireCondition(geometry.headerCells === 7, `The case sheet did not render seven headings at ${at}.`);
    requireCondition(geometry.firstRowCells === 7, `A case row did not render seven cells at ${at}.`);
    requireCondition(geometry.hiddenCells === 0, `A case sheet cell was hidden at ${at}.`);
    requireCondition(geometry.rows === 5, `The case sheet lost a row at ${at}.`);

    // The document does not scroll sideways, whatever the sheet is doing.
    requireCondition(
      geometry.documentScrollWidth <= geometry.documentClientWidth + 1,
      `The document scrolled horizontally at ${at}.`,
    );
    requireCondition(
      geometry.bodyScrollWidth <= geometry.documentClientWidth + 1,
      `The document body scrolled horizontally at ${at}.`,
    );

    // The scroll container stays inside the working area and inside the
    // viewport. A container that had escaped either would be scrolling the page
    // rather than itself.
    requireCondition(
      geometry.containerLeft >= geometry.mainLeft - 1 &&
        geometry.containerRight <= geometry.mainRight + 1,
      `The case sheet escaped the main region at ${at}.`,
    );
    requireCondition(
      geometry.containerLeft >= -1 && geometry.containerRight <= geometry.viewportWidth + 1,
      `The case sheet escaped the viewport at ${at}.`,
    );

    // The floor the stylesheet sets on the case table, actually in force.
    requireCondition(
      geometry.tableMinWidth === "980px",
      `The case table min-width was not applied at ${at}.`,
    );
    requireCondition(
      geometry.tableWidth >= 980,
      `The case table was narrower than its own minimum at ${at}.`,
    );

    if (viewport.width === 1024) {
      // The width the sheet is designed to outgrow. Here, and only here, the
      // container must actually be scrollable and must actually have content
      // wider than itself - which is what makes the two document assertions
      // above a statement about a real overflow rather than about a table that
      // happened to fit.
      requireCondition(
        geometry.tableScrollWidth > geometry.containerClientWidth,
        "The case table did not exceed its container at 1024px, so nothing was being contained.",
      );
      requireCondition(
        geometry.overflowX === "auto" || geometry.overflowX === "scroll",
        "The case sheet container was not scrollable at 1024px.",
      );
    }
  }
  await page.setViewportSize({ width: 1440, height: 900 });

  // No transport, and nothing that could be mistaken for one.
  requireCondition(
    offPageRequests.length === 0,
    "The geometry fixture requested something outside the application origin.",
  );
  requireCondition(
    relaySpawnCount === spawnsBefore && relayObservationCount === observationsBefore,
    "The geometry fixture reached the Backend relay.",
  );
});

test("the populated case audit history wraps inside the document at every design width", async ({
  page,
}) => {
  const offPageRequests: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.origin !== APP_ORIGIN) {
      offPageRequests.push(url.origin);
    }
  });
  const spawnsBefore = relaySpawnCount;
  const observationsBefore = relayObservationCount;

  await page.goto(CASE_AUDIT_GEOMETRY_URL);
  await expect(page.getByRole("heading", { name: "Audit history", level: 3 })).toBeVisible();
  const articles = page.getByRole("article");
  await expect(articles).toHaveCount(6);

  const actions = [
    "CASE_CREATED",
    "CASE_TRANSACTION_LINKED",
    "CASE_STATUS_CHANGED",
    "CASE_ASSIGNEE_CHANGED",
    "CASE_RESOLVED",
    "CASE_NOTE_CREATED",
  ] as const;
  for (const [index, action] of actions.entries()) {
    await expect(articles.nth(index).getByRole("heading", { name: new RegExp(`^${action}, changed`) }))
      .toBeVisible();
  }

  await expect(page.getByText("CASE_ADDITIONAL_INFORMATION_REQUESTED")).toBeVisible();
  await expect(page.getByText("Not applicable")).toHaveCount(4);
  await expect(page.getByText("Unassigned")).toBeVisible();
  const noteId = page.getByText("8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60");
  await expect(noteId).toBeVisible();
  requireCondition((await noteId.evaluate((element) => element.closest("a"))) === null, "The note ID became a link.");
  const longReference =
    "e2e-geometry-audit-reference-000" +
    "e2e-geometry-audit-reference-111" +
    "e2e-geometry-audit-reference-222" +
    "e2e-geometry-audit-reference-333";
  requireCondition(longReference.length === 128, "The audit width probe was not 128 characters.");
  await expect(page.getByText(longReference).first()).toBeVisible();

  for (const viewport of CONSOLE_VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    requireCondition(
      !(await documentOverflowsHorizontally(page)),
      `The populated audit history scrolled the document at ${String(viewport.width)}px.`,
    );
  }
  await page.setViewportSize({ width: 1440, height: 900 });

  requireCondition(
    offPageRequests.length === 0,
    "The audit geometry fixture requested something outside the application origin.",
  );
  requireCondition(
    relaySpawnCount === spawnsBefore && relayObservationCount === observationsBefore,
    "The audit geometry fixture reached the Backend relay.",
  );
});

test("populated investigation notes preserve plain text and wrap at every design width", async ({
  page,
}) => {
  const offPageRequests: string[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.origin !== APP_ORIGIN) {
      offPageRequests.push(url.origin);
    }
  });
  const spawnsBefore = relaySpawnCount;
  const observationsBefore = relayObservationCount;

  await page.goto(CASE_NOTES_GEOMETRY_URL);
  await expect(page.getByRole("heading", { name: "Investigation notes", level: 3 })).toBeVisible();
  const articles = page.getByRole("article");
  await expect(articles).toHaveCount(3);
  await expect(page.getByText("SYSTEM", { exact: true })).toBeVisible();
  await expect(page.getByText("USER", { exact: true })).toHaveCount(2);
  await expect(page.getByRole("navigation", { name: "Investigation notes pages" })).toBeVisible();

  const longNoteId = "note-id-unbroken-".padEnd(128, "n");
  const longAuthorRef = "author-reference-unbroken-".padEnd(128, "a");
  await expect(page.getByText(longNoteId, { exact: true })).toBeVisible();
  await expect(page.getByText(longAuthorRef, { exact: true })).toBeVisible();
  requireCondition(
    (await page.getByText(longNoteId, { exact: true }).evaluate((element) => element.closest("a"))) ===
      null,
    "A note identifier became a link.",
  );

  const content = page.locator(".investigation-notes__content");
  await expect(content).toHaveCount(3);
  const maximum = content.nth(1);
  requireCondition(
    (await maximum.evaluate((element) => Array.from(element.textContent ?? "").length)) === 4000,
    "The maximum note content was not displayed in full.",
  );
  requireCondition(
    (await maximum.locator("script, a").count()) === 0,
    "HTML-like or URL-like note content became markup.",
  );
  const contentStyle = await maximum.evaluate((element) => {
    const style = window.getComputedStyle(element);
    return { whiteSpace: style.whiteSpace, maxHeight: style.maxHeight, overflowY: style.overflowY };
  });
  requireCondition(contentStyle.whiteSpace === "pre-wrap", "Note whitespace was not preserved.");
  requireCondition(
    contentStyle.maxHeight === "none" && contentStyle.overflowY === "visible",
    "Note content was truncated or put behind an internal scroller.",
  );

  for (const viewport of CONSOLE_VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    requireCondition(
      !(await documentOverflowsHorizontally(page)),
      `The populated investigation notes scrolled the document at ${String(viewport.width)}px.`,
    );
  }
  await page.setViewportSize({ width: 1440, height: 900 });

  requireCondition(
    offPageRequests.length === 0,
    "The notes geometry fixture requested something outside the application origin.",
  );
  requireCondition(
    relaySpawnCount === spawnsBefore && relayObservationCount === observationsBefore,
    "The notes geometry fixture reached the Backend relay.",
  );
});
