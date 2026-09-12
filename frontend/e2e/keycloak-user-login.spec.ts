import { createHash, randomBytes, randomUUID } from "node:crypto";
import { spawn } from "node:child_process";
import { EventEmitter } from "node:events";
import { lstatSync, readFileSync, realpathSync } from "node:fs";
import { request as httpsRequest } from "node:https";
import { dirname, resolve } from "node:path";
import { env } from "node:process";
import { fileURLToPath } from "node:url";
import {
  expect,
  test,
  type Page,
  type Request as PlaywrightRequest,
  type Route,
} from "@playwright/test";

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
const EXPECTED_COMPOSE_PROJECT = "finguardops-keycloak-browser-e2e";
const BACKEND_CONTAINER_NAME = `${EXPECTED_COMPOSE_PROJECT}-backend-1`;

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

function decodeJwtHeader(token: string): Record<string, unknown> {
  const parts = token.split(".");
  requireCondition(parts.length === 3, "A token did not have the required JWT shape.");
  try {
    const parsed: unknown = JSON.parse(Buffer.from(parts[0], "base64url").toString("utf8"));
    requireCondition(typeof parsed === "object" && parsed !== null, "A JWT header was invalid.");
    return parsed as Record<string, unknown>;
  } catch {
    throw new Error("A JWT header was invalid.");
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
  const accessHeader = decodeJwtHeader(tokens.accessToken);
  const access = decodeJwtPayload(tokens.accessToken);
  const identity = decodeJwtPayload(tokens.idToken);

  requireCondition(
    accessHeader.alg === "RS256" &&
      typeof accessHeader.kid === "string" &&
      accessHeader.kid.trim() !== "" &&
      !Object.prototype.hasOwnProperty.call(accessHeader, "jku") &&
      !Object.prototype.hasOwnProperty.call(accessHeader, "x5u"),
    "The access token header did not satisfy the Backend contract.",
  );
  requireCondition(
    access.iss === AUTHORITY,
    "The access token issuer differed from the Backend contract.",
  );

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

/** What the Backend actually answered: its status, and its body verbatim. */
interface RelayedResponse {
  readonly status: number;
  readonly body: string;
  /** The request target that produced it. The same string the socket carried. */
  readonly target: string;
}

/** The largest complete raw response accepted from the case-list size=100 contract. */
const MAX_RELAY_STDOUT_BYTES = 2 * 1024 * 1024;
const MAX_RELAY_RESPONSE_HEADER_BYTES = 64 * 1024;
const MAX_RELAY_RESPONSE_HEADER_COUNT = 100;
const MAX_RELAY_DECODED_BODY_BYTES = MAX_RELAY_STDOUT_BYTES - MAX_RELAY_RESPONSE_HEADER_BYTES;
const HTTP_TOKEN = /^[!#$%&'*+.^_`|~0-9A-Za-z-]+$/;

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
  let decodedByteLength = 0;
  for (;;) {
    const lineEnd = raw.indexOf("\r\n", offset, "latin1");
    requireCondition(lineEnd !== -1, "The Backend relay returned an unterminated chunk header.");
    const header = raw.toString("latin1", offset, lineEnd);
    requireCondition(/^(?:0|[1-9a-fA-F][0-9a-fA-F]{0,7})$/.test(header), "The Backend relay returned an invalid chunk size.");
    const size = Number.parseInt(header, 16);
    const start = lineEnd + 2;
    if (size === 0) {
      requireCondition(
        raw.length === start + 2 && raw[start] === 0x0d && raw[start + 1] === 0x0a,
        "The Backend relay returned invalid chunk termination.",
      );
      return Buffer.concat(parts, decodedByteLength);
    }
    const end = start + size;
    requireCondition(
      end + 2 <= raw.length && raw[end] === 0x0d && raw[end + 1] === 0x0a,
      "The Backend relay returned invalid chunk framing.",
    );
    decodedByteLength += size;
    requireCondition(
      decodedByteLength <= MAX_RELAY_DECODED_BODY_BYTES,
      "The Backend relay response was too large.",
    );
    parts.push(raw.subarray(start, start + size));
    offset = end + 2;
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
  requireCondition(raw.length <= MAX_RELAY_STDOUT_BYTES, "The Backend relay response was too large.");
  const separator = raw.indexOf("\r\n\r\n", 0, "latin1");
  requireCondition(separator !== -1, "The Backend relay returned no header boundary.");
  requireCondition(
    separator <= MAX_RELAY_RESPONSE_HEADER_BYTES,
    "The Backend relay response headers were too large.",
  );
  const lines = raw.toString("latin1", 0, separator).split("\r\n");
  const statusMatch = /^HTTP\/1\.[01] ([0-9]{3})(?: ([\x20-\x7e]*))?$/.exec(lines[0]);
  requireCondition(statusMatch !== null, "The Backend relay returned an invalid status line.");
  const status = Number(statusMatch[1]);
  requireCondition(status >= 100 && status <= 599, "The Backend relay returned an invalid status code.");
  requireCondition(
    lines.length - 1 <= MAX_RELAY_RESPONSE_HEADER_COUNT,
    "The Backend relay returned too many headers.",
  );

  const headers = new Map<string, string[]>();
  for (const line of lines.slice(1)) {
    const colon = line.indexOf(":");
    requireCondition(colon > 0, "The Backend relay returned a malformed header.");
    const name = line.slice(0, colon);
    const rawValue = line.slice(colon + 1);
    requireCondition(
      !rawValue.startsWith("\t") &&
        !rawValue.startsWith("  ") &&
        !rawValue.endsWith(" ") &&
        !rawValue.endsWith("\t"),
      "The Backend relay returned non-canonical header whitespace.",
    );
    const value = rawValue.startsWith(" ") ? rawValue.slice(1) : rawValue;
    requireCondition(HTTP_TOKEN.test(name), "The Backend relay returned a malformed header name.");
    requireCondition(
      [...value].every((character) => {
        const code = character.charCodeAt(0);
        return code >= 32 && code <= 126;
      }),
      "The Backend relay returned a malformed header value.",
    );
    const key = name.toLowerCase();
    const values = headers.get(key) ?? [];
    values.push(value);
    headers.set(key, values);
  }

  const contentLengths = headers.get("content-length") ?? [];
  const transferEncodings = headers.get("transfer-encoding") ?? [];
  requireCondition(contentLengths.length <= 1, "The Backend relay returned duplicate Content-Length.");
  requireCondition(transferEncodings.length <= 1, "The Backend relay returned duplicate Transfer-Encoding.");
  requireCondition(
    contentLengths.length === 0 || transferEncodings.length === 0,
    "The Backend relay returned ambiguous response framing.",
  );
  const framedBody = raw.subarray(separator + 4);
  let body: Buffer;
  if (contentLengths.length === 1) {
    const declaredText = contentLengths[0];
    requireCondition(
      /^(?:0|[1-9][0-9]*)$/.test(declaredText),
      "The Backend relay returned an invalid Content-Length.",
    );
    const declared = Number(declaredText);
    requireCondition(Number.isSafeInteger(declared), "The Backend relay returned an unsafe Content-Length.");
    requireCondition(
      declared === framedBody.byteLength,
      "The Backend relay returned a body with the wrong Content-Length.",
    );
    body = framedBody;
  } else if (transferEncodings.length === 1) {
    requireCondition(
      transferEncodings[0].toLowerCase() === "chunked",
      "The Backend relay returned an unsupported Transfer-Encoding.",
    );
    body = decodeChunkedBody(framedBody);
  } else {
    const connectionValues = headers.get("connection") ?? [];
    const connectionTokens = connectionValues.flatMap((value) => value.toLowerCase().split(",").map((token) => token.trim()));
    requireCondition(
      connectionTokens.includes("close"),
      "The Backend relay returned an unframed persistent response.",
    );
    body = framedBody;
  }
  requireCondition(body.byteLength <= MAX_RELAY_DECODED_BODY_BYTES, "The Backend relay response was too large.");
  return { status, body: body.toString("utf8") };
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
 * relay really does spawn `docker exec` and really does open
 * `/dev/tcp`. "A refused request reached neither" is only a claim worth making
 * if it is measured at the two places where it would stop being true, so both
 * are incremented at the exact statement that performs the act.
 */
let relaySpawnCount = 0;
let relayObservationCount = 0;

/** production authorized transport가 인증 요청 하나에 허용하는 전체 시간. */
const PRODUCTION_AUTHENTICATED_REQUEST_TIMEOUT_MS = 5_000;
/**
 * route가 이 harness에 도착한 순간부터 fulfill 또는 abort를 시작해야 하는 상한.
 *
 * production 제한 시간은 credential 조회 직전에 시작하고 route 도착은 그 직후이다. barrier
 * 대기, docker exec, 응답 해석과 detail 전달 순서 대기가 모두 이 안에 들어간다. 로그인과
 * Keycloak callback 시간은 포함하지 않는다.
 */
const RELAY_REQUEST_DEADLINE_MS = 4_000;
/** fulfill/abort 호출이 settle해야 하는 상한. 넘기면 성공으로 보지 않고 harness 실패로 센다. */
const RELAY_ROUTE_ACTION_TIMEOUT_MS = 750;
/** host docker CLI에 TERM을 요청한 뒤 KILL을 요청하기까지의 유예. */
const RELAY_HOST_KILL_GRACE_MS = 500;
/**
 * container 안 relay process group의 수명 상한.
 *
 * GNU timeout은 `--foreground` 없이 실행되면 자기 process group 전체에 TERM을, 유예 뒤 KILL을
 * 보낸다. owner·writer·reader가 모두 그 group에 속하므로 host CLI가 먼저 사라져도 container 안의
 * relay는 이 상한 안에 스스로 끝난다. teardown은 이 종료를 기다리기만 하고 신호를 보내지 않는다.
 */
const CONTAINER_RELAY_TIMEOUT_SECONDS = "3";
const CONTAINER_RELAY_KILL_AFTER_SECONDS = "0.5";
/** teardown이 host child의 실제 close와 route handler settle을 각각 기다리는 상한. */
const RELAY_HOST_CLOSE_TIMEOUT_MS = 5_000;
const RELAY_HANDLER_SETTLE_TIMEOUT_MS = 5_000;
/** container 안에서 marker process-zero를 poll하는 상한(초). relay group 수명보다 넉넉하다. */
const CONTAINER_AUDIT_POLL_SECONDS = "8";
/** audit docker exec 한 번의 host 상한. poll 상한에 Docker Desktop exec 시작·종료 변동을 더한다. */
const CONTAINER_AUDIT_HOST_TIMEOUT_MS = 15_000;
/**
 * route가 이미 끝난 뒤의 증거를 기다리는 상한. 요청 처리 상한이 아니며 production 5초
 * 요청 제한보다 먼저 실패한다.
 */
const BACKEND_OBSERVATION_WAIT_TIMEOUT_MS = 4_000;
const MAX_RELAY_STDERR_BYTES = 64 * 1024;
const BACKEND_RELAY_FAILURE_MESSAGE = "The Backend relay failed.";
const RELAY_MARKER_PREFIX = "fgo-e2e-relay-";
const CONTAINER_MARKER_AUDIT_LABEL = "container-marker-audit";

/**
 * harness의 시간 원천. 실제 relay는 monotonic clock과 Node timer를 쓰고, 결정적 테스트는 같은
 * production class에 수동 clock만 주입한다.
 */
interface RelayClock {
  now(): number;
  setTimeout(callback: () => void, delayMs: number): unknown;
  clearTimeout(handle: unknown): void;
}

const realRelayClock: RelayClock = {
  now: () => performance.now(),
  setTimeout: (callback, delayMs) => setTimeout(callback, delayMs),
  clearTimeout: (handle) => {
    clearTimeout(handle as ReturnType<typeof setTimeout>);
  },
};

interface RelayChildStdin {
  write(chunk: Buffer): boolean;
  end(): void;
  on(event: "error", listener: (error: Error) => void): unknown;
  once(event: "drain" | "finish" | "close", listener: () => void): unknown;
}

interface RelayChildOutput {
  on(event: "data", listener: (chunk: Buffer) => void): unknown;
  on(event: "error", listener: (error: Error) => void): unknown;
}

/** relay가 사용하는 child process 표면. 실제 Node child와 결정적 테스트의 fake가 같은 계약을 따른다. */
interface RelayChild {
  readonly pid?: number;
  readonly stdin: RelayChildStdin;
  readonly stdout: RelayChildOutput;
  readonly stderr: RelayChildOutput;
  kill(signal: NodeJS.Signals): boolean;
  on(event: "error", listener: (error: Error) => void): unknown;
  once(event: "spawn", listener: () => void): unknown;
  once(
    event: "close",
    listener: (code: number | null, signal: NodeJS.Signals | null) => void,
  ): unknown;
}

/** executable과 argv만 전달한다. shell, 추가 환경 변수, stdin 외의 입력 경로는 없다. */
interface RelaySpawnOptions {
  readonly cwd: string;
  readonly shell: false;
  readonly windowsHide: true;
}

type RelaySpawn = (
  executable: "docker",
  args: readonly string[],
  options: RelaySpawnOptions,
) => RelayChild;

const RELAY_SPAWN_OPTIONS: RelaySpawnOptions = Object.freeze({
  cwd: REPO_ROOT,
  shell: false,
  windowsHide: true,
} as const);

const realRelaySpawn: RelaySpawn = (executable, args, options) =>
  spawn(executable, [...args], {
    cwd: options.cwd,
    shell: options.shell,
    windowsHide: options.windowsHide,
    stdio: ["pipe", "pipe", "pipe"],
  }) as unknown as RelayChild;

type RelayProcessFailureReason =
  | "spawn-error"
  | "stdin-error"
  | "stream-error"
  | "stdout-limit"
  | "stderr-limit"
  | "non-zero-exit"
  | "signal"
  | "deadline"
  | "stopped"
  | "malformed-response";

/** 원문 없이 분류만 갖는 relay 실패. message는 항상 고정 문구이다. */
class RelayProcessError extends Error {
  constructor(readonly reason: RelayProcessFailureReason) {
    super(BACKEND_RELAY_FAILURE_MESSAGE);
  }
}

type RelayCleanupStage =
  | "unroute"
  | "handlers"
  | "host-close"
  | "container-audit"
  | "container-present"
  | "internal";

/** teardown 실패. 실패한 단계 이름 외에는 어떤 값도 담지 않는다. */
class RelayCleanupError extends Error {
  constructor(readonly stage: RelayCleanupStage) {
    super(`The Backend relay cleanup failed at ${stage}.`);
  }
}

interface RelayProcessEvent {
  readonly sequence: number;
  readonly kind: "spawn" | "close";
  readonly label: string;
}

/**
 * relay와 audit가 띄운 host `docker` child를 소유한다.
 *
 * child는 실제 `close` 이벤트를 받을 때까지 소유 목록에 남는다. 결과 Promise가 먼저 끝나도
 * (요청 deadline, 출력 상한, stdin 오류, 명시적 중지) 소유는 해제되지 않으며, teardown은
 * `waitForClose`로 실제 close를 bounded하게 기다린다. container 안의 process-zero는 이 class의
 * 책임이 아니므로 요청 경로는 그것을 기다리지 않는다.
 */
class RelayProcessPool {
  private readonly open = new Map<RelayChild, (reason: RelayProcessFailureReason) => void>();
  private readonly emptyWaiters = new Set<() => void>();
  private readonly recorded: RelayProcessEvent[] = [];
  private sequence = 0;
  private lateEvents = 0;

  constructor(
    private readonly spawnChild: RelaySpawn = realRelaySpawn,
    private readonly clock: RelayClock = realRelayClock,
  ) {}

  run(label: string, args: readonly string[], input: Buffer, timeoutMs: number): Promise<Buffer> {
    return new Promise<Buffer>((resolveRun, rejectRun) => {
      if (!(timeoutMs > 0)) {
        rejectRun(new RelayProcessError("deadline"));
        return;
      }
      let child: RelayChild;
      try {
        child = this.spawnChild("docker", args, RELAY_SPAWN_OPTIONS);
      } catch {
        rejectRun(new RelayProcessError("spawn-error"));
        return;
      }

      const stdoutChunks: Buffer[] = [];
      let stdoutBytes = 0;
      let stderrBytes = 0;
      let settled = false;
      let spawned = false;
      let closed = false;
      let stopRequested = false;
      let stdinFinished = false;
      let deadlineHandle: unknown = null;
      let killHandle: unknown = null;

      const fail = (reason: RelayProcessFailureReason): void => {
        if (settled) {
          return;
        }
        settled = true;
        stdoutChunks.length = 0;
        rejectRun(new RelayProcessError(reason));
      };
      // 결과를 먼저 실패로 확정한 뒤 host child에만 TERM, 유예 뒤 KILL을 요청한다. 소유 해제는
      // 실제 close 이벤트만 결정한다.
      const stop = (reason: RelayProcessFailureReason): void => {
        fail(reason);
        if (closed || stopRequested) {
          return;
        }
        stopRequested = true;
        try {
          child.kill("SIGTERM");
        } catch {
          // close 이벤트가 소유 해제를 결정한다.
        }
        killHandle = this.clock.setTimeout(() => {
          killHandle = null;
          if (!closed) {
            try {
              child.kill("SIGKILL");
            } catch {
              // close 이벤트가 소유 해제를 결정한다.
            }
          }
        }, RELAY_HOST_KILL_GRACE_MS);
      };
      const finishClose = (): void => {
        if (closed) {
          return;
        }
        closed = true;
        if (deadlineHandle !== null) {
          this.clock.clearTimeout(deadlineHandle);
          deadlineHandle = null;
        }
        if (killHandle !== null) {
          this.clock.clearTimeout(killHandle);
          killHandle = null;
        }
        this.open.delete(child);
        this.record("close", label);
        if (this.open.size === 0) {
          for (const waiter of [...this.emptyWaiters]) {
            waiter();
          }
        }
      };
      const noteLate = (): void => {
        if (settled) {
          this.lateEvents += 1;
        }
      };

      this.open.set(child, stop);
      child.once("spawn", () => {
        spawned = true;
        this.record("spawn", label);
      });
      child.on("error", () => {
        noteLate();
        if (!spawned && child.pid === undefined) {
          // 시작하지 못한 child는 close를 보장하지 않으므로 여기서 소유를 끝낸다.
          fail("spawn-error");
          finishClose();
          return;
        }
        stop("stream-error");
      });
      child.once("close", (code, signal) => {
        finishClose();
        if (settled) {
          return;
        }
        if (signal !== null) {
          fail("signal");
          return;
        }
        if (code !== 0) {
          fail("non-zero-exit");
          return;
        }
        settled = true;
        const output = Buffer.concat(stdoutChunks, stdoutBytes);
        stdoutChunks.length = 0;
        resolveRun(output);
      });
      child.stdout.on("data", (chunk) => {
        if (settled) {
          this.lateEvents += 1;
          return;
        }
        // 상한은 append 전에 검사한다. 상한을 넘기는 chunk는 한 byte도 보관하지 않는다.
        if (chunk.byteLength > MAX_RELAY_STDOUT_BYTES - stdoutBytes) {
          stop("stdout-limit");
          return;
        }
        stdoutBytes += chunk.byteLength;
        stdoutChunks.push(chunk);
      });
      child.stdout.on("error", () => {
        noteLate();
        stop("stream-error");
      });
      child.stderr.on("data", (chunk) => {
        if (settled) {
          this.lateEvents += 1;
          return;
        }
        // stderr는 저장하지 않고 크기만 센다. 어떤 오류나 보고에도 반사되지 않는다.
        if (chunk.byteLength > MAX_RELAY_STDERR_BYTES - stderrBytes) {
          stop("stderr-limit");
          return;
        }
        stderrBytes += chunk.byteLength;
      });
      child.stderr.on("error", () => {
        noteLate();
        stop("stream-error");
      });
      child.stdin.on("error", () => {
        noteLate();
        stop("stdin-error");
      });
      child.stdin.once("finish", () => {
        stdinFinished = true;
      });
      child.stdin.once("close", () => {
        if (!stdinFinished) {
          stop("stdin-error");
        }
      });

      deadlineHandle = this.clock.setTimeout(() => {
        deadlineHandle = null;
        stop("deadline");
      }, timeoutMs);
      try {
        if (child.stdin.write(input)) {
          child.stdin.end();
        } else {
          // backpressure: drain 전에는 stdin을 닫지 않는다.
          child.stdin.once("drain", () => {
            if (!settled && !closed) {
              child.stdin.end();
            }
          });
        }
      } catch {
        stop("stdin-error");
      }
    });
  }

  /** 아직 close하지 않은 모든 host child에 중지를 요청한다. container process에는 신호를 보내지 않는다. */
  stopAll(): void {
    for (const stop of [...this.open.values()]) {
      stop("stopped");
    }
  }

  waitForClose(timeoutMs: number): Promise<void> {
    if (this.open.size === 0) {
      return Promise.resolve();
    }
    return new Promise<void>((resolveClose, rejectClose) => {
      let handle: unknown = null;
      const onEmpty = (): void => {
        this.emptyWaiters.delete(onEmpty);
        if (handle !== null) {
          this.clock.clearTimeout(handle);
          handle = null;
        }
        resolveClose();
      };
      this.emptyWaiters.add(onEmpty);
      handle = this.clock.setTimeout(() => {
        handle = null;
        this.emptyWaiters.delete(onEmpty);
        rejectClose(new RelayCleanupError("host-close"));
      }, timeoutMs);
    });
  }

  openChildCount(): number {
    return this.open.size;
  }

  events(): readonly RelayProcessEvent[] {
    return this.recorded.map((event) => ({ ...event }));
  }

  lateEventCount(): number {
    return this.lateEvents;
  }

  private record(kind: RelayProcessEvent["kind"], label: string): void {
    this.sequence += 1;
    this.recorded.push({ sequence: this.sequence, kind, label });
  }
}

/**
 * container 안에서 요청 bytes를 Backend socket으로 복사하는 script.
 *
 * stdin은 이미 완성된 HTTP 요청 bytes이며 script는 그 내용을 해석하거나 조립하지 않는다. writer와
 * reader는 relay marker를 argv[0]으로 갖고, 이 script와 같은 process group에 남아 바깥 GNU timeout의
 * group 신호를 함께 받는다. background job을 분리하거나 process group을 바꾸는 명령은 없다.
 */
const RELAY_SOCKET_SCRIPT = [
  "set -euo pipefail",
  "readonly relay_marker=$1",
  "writer_pid=",
  'cleanup() { rc=$?; trap - EXIT TERM INT; if [[ -n ${writer_pid:-} ]]; then kill "$writer_pid" 2>/dev/null || true; wait "$writer_pid" 2>/dev/null || true; fi; exit "$rc"; }',
  "trap cleanup EXIT TERM INT",
  "exec 4<&0",
  "exec 3<>/dev/tcp/127.0.0.1/8080",
  '( exec -a "$relay_marker" cat <&4 >&3 ) &',
  "writer_pid=$!",
  '( exec -a "$relay_marker" cat <&3 )',
  'wait "$writer_pid"',
  "writer_pid=",
].join("\n");

/**
 * docker exec의 진입 script. 검증된 token으로 marker를 만들고 GNU timeout을 그 marker 이름으로 exec한다.
 * timeout은 새 process group의 leader가 되므로 relay의 모든 descendant가 그 group에 남는다.
 */
const RELAY_OWNER_SCRIPT = [
  "set -euo pipefail",
  "readonly relay_token=$1",
  "readonly relay_script=$2",
  `[[ $relay_token =~ ^${CANONICAL_UUID_V4_PATTERN}$ ]] || exit 70`,
  `readonly relay_marker="${RELAY_MARKER_PREFIX}\${relay_token}"`,
  `exec -a "$relay_marker" timeout --signal=TERM --kill-after=${CONTAINER_RELAY_KILL_AFTER_SECONDS}s ${CONTAINER_RELAY_TIMEOUT_SECONDS}s bash -c "$relay_script" -- "$relay_marker"`,
].join("\n");

function relayDockerArguments(token: string): readonly string[] {
  requireCondition(CANONICAL_UUID_V4.test(token), "The Backend relay token was invalid.");
  return [
    "exec",
    "-i",
    BACKEND_CONTAINER_NAME,
    "bash",
    "-c",
    RELAY_OWNER_SCRIPT,
    "--",
    token,
    RELAY_SOCKET_SCRIPT,
  ];
}

/**
 * relay marker의 수를 읽기만 하는 audit script. 어떤 process에도 신호를 보내지 않는다.
 *
 * stdin의 token이 있으면 그 relay의 exact marker만, 빈 줄이면 이 suite가 쓰는 marker 형식 전체를 센다.
 * `/proc/<pid>/cmdline`의 argv[0]만 비교하므로 audit 자신(`bash`, `sleep`)은 세지 않는다.
 * `until-zero`는 0이 될 때까지, `until-present`는 1 이상이 될 때까지 poll하고, 상한에 도달하면 그때의
 * 수를 그대로 보고한다. 출력은 `zero` 또는 `present:<n>` 한 줄뿐이다.
 */
const CONTAINER_MARKER_AUDIT_SCRIPT = [
  "set -euo pipefail",
  "readonly mode=$1",
  "readonly poll_seconds=$2",
  "[[ $mode == until-zero || $mode == until-present ]] || exit 70",
  `[[ $poll_seconds == 0 || $poll_seconds == ${CONTAINER_AUDIT_POLL_SECONDS} ]] || exit 70`,
  "relay_token=",
  "IFS= read -r relay_token || true",
  `[[ -z $relay_token || $relay_token =~ ^${CANONICAL_UUID_V4_PATTERN}$ ]] || exit 70`,
  "count_markers() {",
  "  local count=0 process argument",
  "  for process in /proc/[0-9]*; do",
  "    argument=",
  `    IFS= read -r -d '' argument 2>/dev/null < "$process/cmdline" || true`,
  "    if [[ -z $relay_token ]]; then",
  `      if [[ $argument =~ ^${RELAY_MARKER_PREFIX}${CANONICAL_UUID_V4_PATTERN}$ ]]; then count=$((count + 1)); fi`,
  `    elif [[ $argument == "${RELAY_MARKER_PREFIX}\${relay_token}" ]]; then`,
  "      count=$((count + 1))",
  "    fi",
  "  done",
  "  printf '%d' \"$count\"",
  "}",
  "started=$SECONDS",
  "while :; do",
  "  count=$(count_markers)",
  "  if [[ $mode == until-zero ]] && (( count == 0 )); then printf 'zero\\n'; exit 0; fi",
  "  if [[ $mode == until-present ]] && (( count > 0 )); then printf 'present:%d\\n' \"$count\"; exit 0; fi",
  "  if (( SECONDS - started >= poll_seconds )); then",
  "    if (( count == 0 )); then printf 'zero\\n'; else printf 'present:%d\\n' \"$count\"; fi",
  "    exit 0",
  "  fi",
  "  sleep 0.1",
  "done",
].join("\n");

type RelayMarkerAuditMode = "until-zero" | "until-present";
type RelayMarkerAuditPoll = "0" | typeof CONTAINER_AUDIT_POLL_SECONDS;
/** token의 marker 수를 반환한다. `null`은 suite 전체 marker이다. 실패는 `RelayCleanupError`이다. */
type RelayMarkerAudit = (
  token: string | null,
  mode: RelayMarkerAuditMode,
  poll: RelayMarkerAuditPoll,
) => Promise<number>;

function markerAuditArguments(
  mode: RelayMarkerAuditMode,
  poll: RelayMarkerAuditPoll,
): readonly string[] {
  return [
    "exec",
    "-i",
    BACKEND_CONTAINER_NAME,
    "bash",
    "-c",
    CONTAINER_MARKER_AUDIT_SCRIPT,
    "--",
    mode,
    poll,
  ];
}

function parseMarkerAuditOutput(output: Buffer): number {
  const text = output.toString("latin1");
  if (text === "zero\n") {
    return 0;
  }
  const match = /^present:([1-9][0-9]{0,5})\n$/.exec(text);
  if (match === null) {
    throw new RelayCleanupError("container-audit");
  }
  return Number(match[1]);
}

function createDockerMarkerAudit(pool: RelayProcessPool): RelayMarkerAudit {
  return async (token, mode, poll) => {
    if (token !== null && !CANONICAL_UUID_V4.test(token)) {
      throw new RelayCleanupError("container-audit");
    }
    let output: Buffer;
    try {
      output = await pool.run(
        CONTAINER_MARKER_AUDIT_LABEL,
        markerAuditArguments(mode, poll),
        Buffer.from(`${token ?? ""}\n`, "ascii"),
        CONTAINER_AUDIT_HOST_TIMEOUT_MS,
      );
    } catch {
      throw new RelayCleanupError("container-audit");
    }
    return parseMarkerAuditOutput(output);
  };
}

type RelayCleanupState = "active" | "cleaning" | "failed" | "clean";
/** cleanup owner와 그 owner를 만든 test ID. process-zero가 확인된 owner만 스스로 빠진다. */
type RelayCleanupRegistry = Map<RelayResourceOwner, string>;

const relayCleanupRegistry: RelayCleanupRegistry = new Map();
let currentRelayTestId: string | null = null;
/** 현재 test의 relay가 route action을 상한 안에 settle하지 못한 횟수를 읽는 함수들. */
const relayRouteStallReaders: (() => number)[] = [];

interface RelayResourceOwnerOptions {
  readonly token: string;
  readonly pool: RelayProcessPool;
  readonly audit: RelayMarkerAudit;
  readonly registry: RelayCleanupRegistry;
  readonly testId: string;
  /** host child 정리 전에 route·handler 같은 호출자 자원을 정리한다. `RelayCleanupError`로 실패한다. */
  readonly releaseCallers?: () => Promise<void>;
}

/**
 * relay token 하나와 그 host child를 소유하고 teardown 상태를 관리한다.
 *
 * active → cleaning → clean | failed 이며, failed에서 다시 cleanup을 부르면 새 bounded attempt를
 * 시작한다. cleaning 중의 동시 호출은 같은 Promise를 공유하고 clean 이후의 호출은 아무 일도 하지 않는다.
 * 실패한 attempt의 Promise는 보관하지 않는다.
 *
 * attempt는 business HTTP 요청을 다시 보내지 않는다. 호출자 자원을 정리하고, host child의 실제
 * close를 기다린 뒤, container의 exact marker가 0인지 읽기 전용으로 확인한다. 0이 확인되어야만
 * registry에서 빠지며, 그 전에는 token·child·registry entry를 그대로 유지한다.
 */
class RelayResourceOwner {
  readonly token: string;
  private readonly pool: RelayProcessPool;
  private readonly audit: RelayMarkerAudit;
  private readonly registry: RelayCleanupRegistry;
  private readonly releaseCallers: (() => Promise<void>) | undefined;
  private currentState: RelayCleanupState = "active";
  private inFlight: Promise<void> | null = null;
  private attemptCount = 0;

  constructor(options: RelayResourceOwnerOptions) {
    requireCondition(CANONICAL_UUID_V4.test(options.token), "The Backend relay token was invalid.");
    this.token = options.token;
    this.pool = options.pool;
    this.audit = options.audit;
    this.registry = options.registry;
    this.releaseCallers = options.releaseCallers;
    options.registry.set(this, options.testId);
  }

  state(): RelayCleanupState {
    return this.currentState;
  }

  attempts(): number {
    return this.attemptCount;
  }

  cleanup(): Promise<void> {
    if (this.currentState === "clean") {
      return Promise.resolve();
    }
    if (this.inFlight !== null) {
      return this.inFlight;
    }
    this.currentState = "cleaning";
    this.attemptCount += 1;
    const attempt = this.runAttempt().then(
      () => {
        this.currentState = "clean";
        this.inFlight = null;
        this.registry.delete(this);
      },
      (error: unknown) => {
        this.currentState = "failed";
        this.inFlight = null;
        throw error instanceof RelayCleanupError ? error : new RelayCleanupError("internal");
      },
    );
    void attempt.catch(() => undefined);
    this.inFlight = attempt;
    return attempt;
  }

  private async runAttempt(): Promise<void> {
    if (this.releaseCallers !== undefined) {
      await this.releaseCallers();
    }
    this.pool.stopAll();
    await this.pool.waitForClose(RELAY_HOST_CLOSE_TIMEOUT_MS);
    const remaining = await this.audit(this.token, "until-zero", CONTAINER_AUDIT_POLL_SECONDS);
    // audit child 자신도 host가 소유한 자원이다. clean 전에 그 close까지 확인한다.
    await this.pool.waitForClose(RELAY_HOST_CLOSE_TIMEOUT_MS);
    if (remaining !== 0) {
      throw new RelayCleanupError("container-present");
    }
  }
}

/**
 * 이 worker에서 relay를 처음 설치하기 전에 한 번 확인한다. 성공만 기억하므로 실패한 확인은 다음
 * 설치에서 다시 수행한다.
 *
 * Backend container의 이름·Compose 소유권, GNU timeout 존재, 그리고 이전 worker가 남긴 suite
 * marker가 없는지를 읽기 전용으로 확인한다. 남은 marker가 있어도 종료하지 않고 실패한다.
 */
let relayRuntimeVerified = false;

async function verifyRelayRuntime(): Promise<void> {
  if (relayRuntimeVerified) {
    return;
  }
  requireCondition(
    COMPOSE_PROJECT === EXPECTED_COMPOSE_PROJECT,
    "The dedicated Compose project was not configured.",
  );
  const pool = new RelayProcessPool();
  let primary: unknown = null;
  try {
    const inspect = await pool.run(
      "runtime-inspect",
      [
        "inspect",
        "--format",
        '{{.Name}}|{{index .Config.Labels "com.docker.compose.project"}}|{{index .Config.Labels "com.docker.compose.service"}}|{{.Id}}',
        BACKEND_CONTAINER_NAME,
      ],
      Buffer.alloc(0),
      CONTAINER_AUDIT_HOST_TIMEOUT_MS,
    );
    requireCondition(
      new RegExp(
        `^/${BACKEND_CONTAINER_NAME}\\|${EXPECTED_COMPOSE_PROJECT}\\|backend\\|[0-9a-f]{64}$`,
      ).test(inspect.toString("ascii").trim()),
      "The Backend relay container ownership check failed.",
    );
    const timeoutVersion = await pool.run(
      "runtime-timeout",
      ["exec", BACKEND_CONTAINER_NAME, "timeout", "--version"],
      Buffer.alloc(0),
      CONTAINER_AUDIT_HOST_TIMEOUT_MS,
    );
    requireCondition(
      timeoutVersion.toString("ascii").startsWith("timeout (GNU coreutils)"),
      "The Backend relay container has no verified GNU timeout executable.",
    );
    const leftovers = await createDockerMarkerAudit(pool)(
      null,
      "until-zero",
      CONTAINER_AUDIT_POLL_SECONDS,
    );
    requireCondition(
      leftovers === 0,
      "Backend relay processes from an earlier test remained in the container.",
    );
  } catch (error: unknown) {
    primary = error;
  }
  pool.stopAll();
  let closeFailure: unknown = null;
  try {
    await pool.waitForClose(RELAY_HOST_CLOSE_TIMEOUT_MS);
  } catch (error: unknown) {
    closeFailure = error;
  }
  if (primary !== null && closeFailure !== null) {
    throw new AggregateError(
      [primary, closeFailure],
      "The Backend relay runtime check and its cleanup failed.",
    );
  }
  if (primary !== null) {
    throw primary;
  }
  if (closeFailure !== null) {
    throw closeFailure;
  }
  relayRuntimeVerified = true;
}

interface BuiltRelayRequest {
  readonly method: string;
  readonly target: string;
  readonly bodyByteLength: number;
  readonly bytes: Buffer;
}

const MAX_RELAY_REQUEST_BODY_BYTES = 64 * 1024;

function buildRelayRequestBytes(request: PlaywrightRequest): BuiltRelayRequest {
  const method = request.method();
  requireCondition(/^[A-Z]+$/.test(method), "An invalid Backend method was requested.");
  requireCondition(method === "GET" || method === "POST", "A Backend request used a method this relay will not write.");

  const target = resolveRelayTarget(request);
  requireCondition(
    /^[\x21-\x7e]+$/.test(target),
    "A Backend request target carried a character this relay will not write.",
  );

  const bodyText = request.postData() ?? "";
  requireCondition(!bodyText.includes("\u0000"), "An invalid Backend request body was refused.");
  const body = Buffer.from(bodyText, "utf8");
  requireCondition(
    body.byteLength <= MAX_RELAY_REQUEST_BODY_BYTES &&
      ((method === "GET" && body.byteLength === 0) || (method === "POST" && body.byteLength > 0)),
    "An invalid Backend request body was refused.",
  );

  const credential = request.headers()["authorization"] ?? "";
  requireCondition(
    credential === "" || /^Bearer [\x21-\x7e]+$/.test(credential),
    "An invalid credential header was refused.",
  );
  const headerLines = [
    `${method} ${target} HTTP/1.1`,
    "Host: localhost:8080",
    "Accept: application/json",
    "Connection: close",
    ...(credential === "" ? [] : [`Authorization: ${credential}`]),
    `Content-Length: ${String(body.byteLength)}`,
    ...(method === "POST" ? ["Content-Type: application/json"] : []),
  ];
  requireCondition(
    headerLines.every((line) => !line.includes("\u0000") && !line.includes("\r") && !line.includes("\n")),
    "An invalid Backend request header was refused.",
  );
  const head = Buffer.from(`${headerLines.join("\r\n")}\r\n\r\n`, "ascii");
  const bytes = Buffer.concat([head, body], head.byteLength + body.byteLength);
  requireCondition(
    bytes.subarray(head.byteLength).byteLength === body.byteLength && bytes.byteLength === head.byteLength + body.byteLength,
    "The Backend request bytes were not exact.",
  );
  return { method, target, bodyByteLength: body.byteLength, bytes };
}

/** 검증된 요청 bytes를 relay token의 container process로 보내고 응답을 엄격하게 해석한다. */
function relayBuiltRequest(
  built: BuiltRelayRequest,
  pool: RelayProcessPool,
  token: string,
  timeoutMs: number,
): Promise<RelayedResponse> {
  relaySpawnCount += 1;
  return pool.run(built.target, relayDockerArguments(token), built.bytes, timeoutMs).then((stdout) => {
    let parsed: Omit<RelayedResponse, "target">;
    try {
      parsed = parseRelayedResponse(stdout);
    } catch {
      throw new RelayProcessError("malformed-response");
    }
    return { ...parsed, target: built.target };
  });
}

/**
 * 요청 하나를 검증하고 relay한다. closed allowlist 판정과 요청 bytes 생성은 동기 단계에서 끝나므로
 * 거부된 요청은 process·socket·observation을 만들기 전에 고정 문구로 throw한다.
 */
function relayToBackend(
  request: PlaywrightRequest,
  pool?: RelayProcessPool,
  token?: string,
  timeoutMs: number = RELAY_REQUEST_DEADLINE_MS,
): Promise<RelayedResponse> {
  requireCondition(
    COMPOSE_PROJECT === EXPECTED_COMPOSE_PROJECT,
    "The dedicated Compose project was not configured.",
  );
  const built = buildRelayRequestBytes(request);
  requireCondition(pool !== undefined && token !== undefined, BACKEND_RELAY_FAILURE_MESSAGE);
  return relayBuiltRequest(built, pool, token, timeoutMs);
}

function requireParserRejection(raw: Buffer): void {
  let rejected = false;
  try {
    parseRelayedResponse(raw);
  } catch (error: unknown) {
    rejected = error instanceof Error && error.message.startsWith("The Backend relay returned");
  }
  requireCondition(rejected, "The strict Backend response parser accepted ambiguous bytes.");
}

function requireFixedHeaderValueRejection(raw: Buffer, forbiddenValue: string): void {
  let message: string | null = null;
  try {
    parseRelayedResponse(raw);
  } catch (error: unknown) {
    message = error instanceof Error ? error.message : null;
  }
  requireCondition(
    message === "The Backend relay returned a malformed header value." &&
      !message.includes(forbiddenValue),
    "A NUL response header did not use the fixed parser error.",
  );
}

function verifyRelayByteFramingAndParser(): void {
  const credential = "Bearer deterministic-byte-secret";
  const get = buildRelayRequestBytes(
    relayCandidate("GET", `${BACKEND_ORIGIN}${INITIAL_CASE_TARGET}`, credential),
  );
  const expectedGet = Buffer.from(
    `GET ${INITIAL_CASE_TARGET} HTTP/1.1\r\n` +
      "Host: localhost:8080\r\n" +
      "Accept: application/json\r\n" +
      "Connection: close\r\n" +
      `Authorization: ${credential}\r\n` +
      "Content-Length: 0\r\n\r\n",
    "ascii",
  );
  requireCondition(
    get.bytes.equals(expectedGet) &&
      get.bodyByteLength === 0 &&
      !get.bytes.includes(Buffer.from("Content-Type:")),
    "The approved GET request bytes were not exact.",
  );

  const bodyText = '{"resolution":"FRAUD","comment":"한글"}';
  const post = buildRelayRequestBytes(
    relayCandidate("POST", `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`, credential, bodyText),
  );
  const separator = post.bytes.indexOf("\r\n\r\n", 0, "latin1");
  const body = Buffer.from(bodyText, "utf8");
  const head = post.bytes.toString("ascii", 0, separator + 4);
  requireCondition(
    separator > 0 &&
      post.bodyByteLength === body.byteLength &&
      body.byteLength !== bodyText.length &&
      post.bytes.subarray(separator + 4).equals(body) &&
      head.includes(`Content-Length: ${String(body.byteLength)}\r\n`) &&
      (head.match(/Authorization:/g) ?? []).length === 1 &&
      (head.match(/Content-Length:/g) ?? []).length === 1 &&
      (head.match(/Content-Type:/g) ?? []).length === 1,
    "The approved write request was not byte-exact.",
  );

  for (const invalid of [
    relayCandidate("GET", `${BACKEND_ORIGIN}${INITIAL_CASE_TARGET}`, credential, "x"),
    relayCandidate("POST", `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`, credential, null),
    relayCandidate("GET", `${BACKEND_ORIGIN}${INITIAL_CASE_TARGET}`, `${credential}\r\nInjected: yes`),
    relayCandidate("GET", `${BACKEND_ORIGIN}${INITIAL_CASE_TARGET}`, `${credential}\u0000`),
    relayCandidate("GET", `${BACKEND_ORIGIN}${INITIAL_CASE_TARGET}`, `${credential}\nInjected: yes`),
    relayCandidate("POST", `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`, credential, "\u0000"),
    relayCandidate(
      "POST",
      `${BACKEND_ORIGIN}${CASE_RESOLUTION_PATH}`,
      credential,
      "x".repeat(MAX_RELAY_REQUEST_BODY_BYTES + 1),
    ),
  ]) {
    let rejected = false;
    try {
      buildRelayRequestBytes(invalid);
    } catch {
      rejected = true;
    }
    requireCondition(rejected, "The Backend request byte builder accepted unsafe input.");
  }

  const validBody = Buffer.from("한글", "utf8");
  const validContentLength = Buffer.concat([
    Buffer.from(
      `HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${String(validBody.byteLength)}\r\n\r\n`,
      "ascii",
    ),
    validBody,
  ]);
  requireCondition(
    parseRelayedResponse(validContentLength).body === "한글",
    "The strict Backend response parser changed a byte-framed body.",
  );
  requireCondition(
    parseRelayedResponse(
      Buffer.from("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"),
    ).body === "abc",
    "The strict Backend response parser rejected canonical chunk framing.",
  );

  for (const value of ["\u0000alpha", "alpha\u0000beta", "alpha\u0000"] as const) {
    requireFixedHeaderValueRejection(
      Buffer.from(
        `HTTP/1.1 200 OK\r\nX-Test: ${value}\r\nContent-Length: 0\r\n\r\n`,
        "utf8",
      ),
      value,
    );
  }

  for (const raw of [
    "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\na",
    "HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nab",
    "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\na",
    "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\na",
    "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\na",
    "HTTP/1.1 OK\r\nContent-Length: 0\r\n\r\n",
    "HTTP/1.1 200 OK\r\nBad Header: value\r\nContent-Length: 0\r\n\r\n",
    "HTTP/1.1 200 OK\r\n folded\r\nContent-Length: 0\r\n\r\n",
    "HTTP/1.1 200 OK\r\nX-Test: alpha\tbeta\r\nContent-Length: 0\r\n\r\n",
    "HTTP/1.1 200 OK\r\nX-Test:\tbeta\r\nContent-Length: 0\r\n\r\n",
    "HTTP/1.1 200 OK\r\nX-Test: beta\t\r\nContent-Length: 0\r\n\r\n",
    "HTTP/1.1 200 OK\r\nX-Test: alpha\u0001beta\r\nContent-Length: 0\r\n\r\n",
    "HTTP/1.1 200 OK\r\nX-Test: alpha\u007fbeta\r\nContent-Length: 0\r\n\r\n",
    "HTTP/1.1 200 OK\r\nContent-Length: +1\r\n\r\na",
    "HTTP/1.1 200 OK\r\nContent-Length: 01\r\n\r\na",
    "HTTP/1.1 200 OK\r\nContent-Length: 9007199254740992\r\n\r\n",
    "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\na",
    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\naX\r\n0\r\n\r\n",
    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n0\r\n\r\ntrailing",
    "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n1\r\na\r\n",
  ]) {
    requireParserRejection(Buffer.from(raw, "utf8"));
  }
}

type ParallelStartBarrierState = "pending" | "released" | "failed" | "disposed";
type ParallelStartArrivalWatchdogState = "idle" | "armed" | "disarmed" | "expired" | "cancelled";

interface ParallelStartTarget {
  readonly method: string;
  readonly target: string;
}

interface ParallelStartBarrierSnapshot {
  readonly state: ParallelStartBarrierState;
  readonly expectedTargetCount: number;
  readonly arrivedTargetCount: number;
  readonly pendingWaiterCount: number;
  /** 첫 exact route가 시작하는 barrier timer만 센다. */
  readonly activeTimerCount: number;
  /** 로그인 완료 뒤 첫 exact route를 기다리는 no-arrival watchdog timer만 센다. */
  readonly activeArrivalWatchdogTimerCount: number;
  /** waiter와 두 timer를 합한 잔존 callback 수. */
  readonly activeCallbackCount: number;
  readonly completionResolveCount: number;
  readonly completionRejectCount: number;
  /** barrier timer 만료 횟수. no-arrival watchdog 만료는 포함하지 않는다. */
  readonly timeoutCallbackCount: number;
  readonly arrivalWatchdogState: ParallelStartArrivalWatchdogState;
  readonly arrivalWatchdogCallbackCount: number;
}

/** 세 read가 모이기를 기다리는 상한. 첫 exact route 도착 시 시작하며 request deadline 안에 끝난다. */
const PARALLEL_START_TIMEOUT_MS = 1_500;
/**
 * 로그인 완료 뒤 첫 exact route가 도착하기를 기다리는 별도 상한.
 *
 * barrier timer와 timer·상태·고정 오류가 모두 분리되어 있다. 첫 exact route가 도착하면 해제되고,
 * 그 시점부터 barrier 상한이 따로 시작한다. 두 상한을 합쳐 늘리지 않는다.
 */
const PARALLEL_START_ARRIVAL_WATCHDOG_MS = 1_500;
const PARALLEL_START_TIMEOUT_MESSAGE = "The parallel-start barrier timed out.";
const PARALLEL_START_NO_ARRIVAL_MESSAGE = "The parallel-start reads did not arrive.";
const PARALLEL_START_DUPLICATE_MESSAGE =
  "The parallel-start barrier received a duplicate target.";
const PARALLEL_START_DISPOSED_MESSAGE = "The parallel-start barrier is unavailable.";

// 요청 종결은 production 5초보다 먼저, container relay는 audit poll 상한보다 먼저 끝나야 한다.
requireCondition(
  PARALLEL_START_TIMEOUT_MS < RELAY_REQUEST_DEADLINE_MS &&
    RELAY_REQUEST_DEADLINE_MS + RELAY_ROUTE_ACTION_TIMEOUT_MS <
      PRODUCTION_AUTHENTICATED_REQUEST_TIMEOUT_MS &&
    Number(CONTAINER_RELAY_TIMEOUT_SECONDS) + Number(CONTAINER_RELAY_KILL_AFTER_SECONDS) <
      Number(CONTAINER_AUDIT_POLL_SECONDS) &&
    Number(CONTAINER_AUDIT_POLL_SECONDS) * 1_000 < CONTAINER_AUDIT_HOST_TIMEOUT_MS,
  "The E2E relay bounds no longer end requests before the production deadline or outlast the relay lifetime.",
);

class ParallelStartBarrierError extends Error {}

function parallelStartTargetKey(method: string, target: string): string {
  return `${method} ${target}`;
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
 *
 * 두 상한은 timer·상태·고정 오류가 분리되어 있다. no-arrival watchdog은 로그인 완료 뒤 첫 exact
 * route를 기다리고, barrier timer는 첫 exact route가 도착한 순간부터 세 route가 모이기를 기다린다.
 */
class ParallelStartBarrier {
  readonly completion: Promise<void>;

  private state: ParallelStartBarrierState = "pending";
  private readonly clock: RelayClock;
  private readonly timeoutMs: number;
  private readonly configuredTargetKeys: ReadonlySet<string>;
  private readonly targetKeys: Set<string>;
  private readonly arrivedTargetKeys = new Set<string>();
  private readonly waiters = new Set<{
    readonly resolve: () => void;
    readonly reject: (error: ParallelStartBarrierError) => void;
  }>();
  private timerHandle: unknown | null = null;
  private arrivalWatchdogHandle: unknown | null = null;
  private arrivalWatchdogState: ParallelStartArrivalWatchdogState = "idle";
  private arrivalWatchdogCallbackCount = 0;
  private resolveCompletion!: () => void;
  private rejectCompletion!: (error: ParallelStartBarrierError) => void;
  private completionResolveCount = 0;
  private completionRejectCount = 0;
  private timeoutCallbackCount = 0;

  constructor(
    targets: readonly ParallelStartTarget[],
    timeoutMs: number,
    clock: RelayClock = realRelayClock,
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
    this.clock = clock;
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

  /**
   * barrier timer를 시작한다. 첫 exact `wait()`와 결정적 lifecycle matrix만 호출하며
   * production-like scenario는 호출하지 않는다. 이미 시작했거나 끝났다면 아무 일도 하지 않는다.
   */
  start(): void {
    if (this.state !== "pending" || this.timerHandle !== null) {
      return;
    }
    this.timerHandle = this.clock.setTimeout(() => {
      if (this.state !== "pending") {
        return;
      }
      this.timeoutCallbackCount += 1;
      this.fail(PARALLEL_START_TIMEOUT_MESSAGE);
    }, this.timeoutMs);
  }

  /**
   * 로그인 완료 뒤 첫 exact route를 기다리는 no-arrival watchdog을 한 번만 arm한다.
   *
   * barrier timer는 시작하지 않는다. 이미 arm·해제·만료·취소됐거나, route가 이미 도착해 barrier
   * timer가 시작됐거나, barrier가 끝났다면 timer를 추가하지 않는다. 만료하면 barrier timeout과 다른
   * 고정 오류로 completion을 끝낸다.
   */
  armArrivalWatchdog(timeoutMs: number): void {
    requireCondition(
      Number.isSafeInteger(timeoutMs) && timeoutMs > 0,
      "The parallel-start arrival watchdog requires a bounded timeout.",
    );
    if (
      this.state !== "pending" ||
      this.arrivalWatchdogState !== "idle" ||
      this.arrivedTargetKeys.size > 0 ||
      this.timerHandle !== null
    ) {
      return;
    }
    this.arrivalWatchdogState = "armed";
    this.arrivalWatchdogHandle = this.clock.setTimeout(() => {
      if (this.state !== "pending" || this.arrivalWatchdogState !== "armed") {
        return;
      }
      this.arrivalWatchdogHandle = null;
      this.arrivalWatchdogCallbackCount += 1;
      this.arrivalWatchdogState = "expired";
      this.fail(PARALLEL_START_NO_ARRIVAL_MESSAGE);
    }, timeoutMs);
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
    // 첫 exact route가 no-arrival watchdog을 해제하고 barrier timer 시작과 도착 등록을 함께 수행한다.
    // 로그인 완료만으로는 barrier timer가 시작되지 않는다.
    this.disarmArrivalWatchdog();
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
    this.cancelArrivalWatchdog();
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
    const activeArrivalWatchdogTimerCount = this.arrivalWatchdogHandle === null ? 0 : 1;
    return {
      state: this.state,
      expectedTargetCount: this.targetKeys.size,
      arrivedTargetCount: this.arrivedTargetKeys.size,
      pendingWaiterCount: this.waiters.size,
      activeTimerCount,
      activeArrivalWatchdogTimerCount,
      activeCallbackCount: this.waiters.size + activeTimerCount + activeArrivalWatchdogTimerCount,
      completionResolveCount: this.completionResolveCount,
      completionRejectCount: this.completionRejectCount,
      timeoutCallbackCount: this.timeoutCallbackCount,
      arrivalWatchdogState: this.arrivalWatchdogState,
      arrivalWatchdogCallbackCount: this.arrivalWatchdogCallbackCount,
    };
  }

  private release(): void {
    if (this.state !== "pending") {
      return;
    }
    this.clearTimer();
    this.cancelArrivalWatchdog();
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
    this.cancelArrivalWatchdog();
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
    this.clock.clearTimeout(this.timerHandle);
    this.timerHandle = null;
  }

  /** 첫 exact route가 도착하면 no-arrival watchdog을 해제한다. barrier timer에는 영향을 주지 않는다. */
  private disarmArrivalWatchdog(): void {
    this.clearArrivalWatchdogTimer();
    if (this.arrivalWatchdogState === "armed") {
      this.arrivalWatchdogState = "disarmed";
    }
  }

  /** release·fail·dispose가 끝낸 barrier의 watchdog을 정리한다. 해제·만료 기록은 바꾸지 않는다. */
  private cancelArrivalWatchdog(): void {
    this.clearArrivalWatchdogTimer();
    if (this.arrivalWatchdogState === "armed") {
      this.arrivalWatchdogState = "cancelled";
    }
  }

  private clearArrivalWatchdogTimer(): void {
    if (this.arrivalWatchdogHandle === null) {
      return;
    }
    this.clock.clearTimeout(this.arrivalWatchdogHandle);
    this.arrivalWatchdogHandle = null;
  }
}

interface DeferredValue<T> {
  readonly promise: Promise<T>;
  readonly resolve: (value: T) => void;
}

function deferredValue<T>(): DeferredValue<T> {
  let resolveValue!: (value: T) => void;
  const promise = new Promise<T>((resolvePromise) => {
    resolveValue = resolvePromise;
  });
  return { promise, resolve: resolveValue };
}

type BoundedOutcome = "settled" | "rejected" | "timeout";

/** Promise가 상한 안에 settle했는지만 알려 준다. timeout은 성공으로 취급하지 않는다. */
function settleWithin(
  promise: Promise<unknown>,
  timeoutMs: number,
  clock: RelayClock,
): Promise<BoundedOutcome> {
  return new Promise<BoundedOutcome>((resolveOutcome) => {
    let done = false;
    let handle: unknown = null;
    const finish = (outcome: BoundedOutcome): void => {
      if (done) {
        return;
      }
      done = true;
      if (handle !== null) {
        clock.clearTimeout(handle);
        handle = null;
      }
      resolveOutcome(outcome);
    };
    handle = clock.setTimeout(() => {
      handle = null;
      finish("timeout");
    }, timeoutMs);
    promise.then(
      () => finish("settled"),
      () => finish("rejected"),
    );
  });
}

/** 요청 deadline 전에 settle한 결과만 돌려준다. 원래 rejection은 그대로 전달한다. */
function awaitBeforeDeadline<T>(
  promise: Promise<T>,
  deadlineAt: number,
  clock: RelayClock,
): Promise<T> {
  const remaining = deadlineAt - clock.now();
  if (remaining <= 0) {
    return Promise.reject(new RelayProcessError("deadline"));
  }
  return new Promise<T>((resolveValue, rejectValue) => {
    let done = false;
    const handle = clock.setTimeout(() => {
      if (!done) {
        done = true;
        rejectValue(new RelayProcessError("deadline"));
      }
    }, remaining);
    promise.then(
      (value) => {
        if (!done) {
          done = true;
          clock.clearTimeout(handle);
          resolveValue(value);
        }
      },
      (error: unknown) => {
        if (!done) {
          done = true;
          clock.clearTimeout(handle);
          rejectValue(error);
        }
      },
    );
  });
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
  /** barrier 대상 중 이 target은 나머지 barrier route가 종결된 뒤에 전달한다. */
  readonly deliverLast?: string;
  /** 결정적 계약 테스트 seam. 실제 relay는 Node spawn을 쓴다. */
  readonly spawnChild?: RelaySpawn;
  /** 결정적 계약 테스트 seam. 실제 relay는 monotonic clock을 쓴다. */
  readonly clock?: RelayClock;
  /** 결정적 계약 테스트 seam. 실제 relay는 Docker marker audit을 쓴다. */
  readonly markerAudit?: (pool: RelayProcessPool) => RelayMarkerAudit;
  /** 결정적 계약 테스트 seam. 실제 relay는 공통 registry와 현재 test ID를 쓴다. */
  readonly registry?: RelayCleanupRegistry;
  readonly testId?: string;
}

interface BackendRelay extends Array<BackendObservation> {
  /** teardown을 기다린다. process-zero가 확인되지 않으면 고정 단계 이름과 함께 실패한다. */
  readonly dispose: () => Promise<void>;
  readonly cleanupState: () => RelayCleanupState;
  readonly activeHandlerCount: () => number;
  readonly openProcessCount: () => number;
  readonly processEvents: () => readonly RelayProcessEvent[];
  readonly routeFulfillCount: () => number;
  readonly routeAbortCount: () => number;
  readonly routeActionFailureCount: () => number;
  readonly routeActionStallCount: () => number;
  readonly relayFailureCount: () => number;
  readonly barrierArrivalCountAtFirstForwarding: () => number | null;
  readonly barrierAbortCount: () => number;
  readonly parallelFulfillmentOrder: () => readonly string[];
}

/**
 * page의 Backend 요청을 closed allowlist 뒤의 실제 Backend로 relay한다.
 *
 * 요청마다 route 도착 시점 기준의 독립 deadline 안에서 fulfill 또는 abort를 정확히 한 번 시작한다.
 * relay가 띄운 host child와 container process의 정리는 요청 경로와 분리된 teardown owner의 책임이며,
 * owner는 route 설치보다 먼저 공통 registry에 등록된다.
 */
async function installBackendRelay(
  page: Page,
  options: RelayOptions = {},
): Promise<BackendRelay> {
  if (options.spawnChild === undefined) {
    await verifyRelayRuntime();
  }
  const registry = options.registry ?? relayCleanupRegistry;
  const testId = options.testId ?? currentRelayTestId;
  requireCondition(testId !== null, "The Backend relay had no owning test.");
  const clock = options.clock ?? realRelayClock;
  const pool = new RelayProcessPool(options.spawnChild, clock);
  const observations = [] as unknown as BackendRelay;
  const capturedPaths =
    typeof options.captureBodyOf === "string"
      ? [options.captureBodyOf]
      : (options.captureBodyOf ?? []);
  const relayPattern = "http://localhost:8080/**";
  const activeHandlers = new Set<Promise<void>>();
  const parallelRouteDone = new Map<string, DeferredValue<void>>();
  const parallelFulfillmentOrder: string[] = [];
  let routeHandler: ((route: Route) => Promise<void>) | null = null;
  let disposed = false;
  let routeInstalled = false;
  let routeFulfillCount = 0;
  let routeAbortCount = 0;
  let routeActionFailureCount = 0;
  let routeActionStallCount = 0;
  let relayFailureCount = 0;
  let barrierArrivalCountAtFirstForwarding: number | null = null;
  let barrierAbortCount = 0;

  // 새 요청을 받지 않게 한 뒤 route를 제거하고, in-flight host child를 멈춰 handler가 종결되기를
  // 기다린다. 어느 단계든 상한을 넘기면 cleanup 실패이며 다음 attempt가 같은 단계부터 다시 확인한다.
  const releaseCallers = async (): Promise<void> => {
    disposed = true;
    options.parallelStartBarrier?.dispose();
    for (const done of parallelRouteDone.values()) {
      done.resolve();
    }
    if (routeInstalled) {
      if (page.isClosed() || routeHandler === null) {
        routeInstalled = false;
      } else {
        let unrouting: Promise<void>;
        try {
          unrouting = page.unroute(relayPattern, routeHandler);
        } catch {
          throw new RelayCleanupError("unroute");
        }
        if ((await settleWithin(unrouting, RELAY_HANDLER_SETTLE_TIMEOUT_MS, clock)) !== "settled") {
          throw new RelayCleanupError("unroute");
        }
        routeInstalled = false;
      }
    }
    pool.stopAll();
    const handlers = await settleWithin(
      Promise.allSettled([...activeHandlers]),
      RELAY_HANDLER_SETTLE_TIMEOUT_MS,
      clock,
    );
    if (handlers !== "settled" || activeHandlers.size !== 0) {
      throw new RelayCleanupError("handlers");
    }
  };

  const owner = new RelayResourceOwner({
    token: randomUUID(),
    pool,
    audit: (options.markerAudit ?? createDockerMarkerAudit)(pool),
    registry,
    testId,
    releaseCallers,
  });
  if (registry === relayCleanupRegistry) {
    relayRouteStallReaders.push(() => routeActionStallCount);
  }

  const processRoute = async (route: Route): Promise<void> => {
    const deadlineAt = clock.now() + RELAY_REQUEST_DEADLINE_MS;
    let terminal = false;
    let parallelTarget: string | null = null;
    // 요청마다 fulfill/abort는 정확히 한 번만 시작한다. 닫힌 page에는 action을 보내지 않는다.
    const terminate = async (
      kind: "fulfill" | "abort",
      action: () => Promise<void>,
    ): Promise<boolean> => {
      if (terminal) {
        return false;
      }
      terminal = true;
      if (page.isClosed()) {
        return false;
      }
      if (kind === "fulfill") {
        routeFulfillCount += 1;
      } else {
        routeAbortCount += 1;
      }
      let pending: Promise<void>;
      try {
        pending = action();
      } catch {
        routeActionFailureCount += 1;
        return false;
      }
      const outcome = await settleWithin(pending, RELAY_ROUTE_ACTION_TIMEOUT_MS, clock);
      if (outcome === "timeout") {
        routeActionStallCount += 1;
      } else if (outcome === "rejected") {
        routeActionFailureCount += 1;
      }
      return outcome === "settled";
    };
    const abort = (): Promise<boolean> => terminate("abort", () => route.abort("failed"));

    try {
      if (disposed) {
        await abort();
        return;
      }
      const request = route.request();
      if (request.method() === "OPTIONS") {
        await terminate("fulfill", () =>
          route.fulfill({
            status: 204,
            headers: {
              "access-control-allow-origin": APP_ORIGIN,
              "access-control-allow-methods": "GET, POST, PATCH",
              "access-control-allow-headers": "authorization, content-type",
            },
          }),
        );
        return;
      }
      // closed allowlist와 요청 bytes는 barrier·spawn보다 먼저 확정한다.
      const built = buildRelayRequestBytes(request);
      const barrier = options.parallelStartBarrier;
      const barrierWait = barrier?.wait(request.method(), built.target) ?? null;
      if (barrier !== undefined && barrierWait !== null) {
        parallelTarget = built.target;
        if (!parallelRouteDone.has(built.target)) {
          parallelRouteDone.set(built.target, deferredValue<void>());
        }
        try {
          await awaitBeforeDeadline(barrierWait, deadlineAt, clock);
        } catch (error: unknown) {
          if (error instanceof ParallelStartBarrierError) {
            barrierAbortCount += 1;
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
      const relayed = await relayBuiltRequest(built, pool, owner.token, deadlineAt - clock.now());
      if (parallelTarget !== null && parallelTarget === options.deliverLast) {
        // detail 404는 하위 section을 제거하므로 notes·audit route가 먼저 끝난 뒤 전달한다.
        const others = [...parallelRouteDone.entries()]
          .filter(([target]) => target !== parallelTarget)
          .map(([, done]) => done.promise);
        await awaitBeforeDeadline(Promise.all(others), deadlineAt, clock);
      }
      if (disposed) {
        await abort();
        return;
      }
      requireCondition(clock.now() < deadlineAt, BACKEND_RELAY_FAILURE_MESSAGE);
      // Observation begins only after real forwarding and strict byte parsing.
      const pathname = relayed.target.split("?")[0];
      relayObservationCount += 1;
      observations.push({
        method: request.method(),
        pathname,
        target: relayed.target,
        status: relayed.status,
        requestBodyByteLength: built.bodyByteLength,
        ...(capturedPaths.includes(pathname) ? { body: relayed.body } : {}),
      });
      const fulfilled = await terminate("fulfill", () =>
        route.fulfill({
          status: relayed.status,
          contentType: "application/json",
          headers: { "access-control-allow-origin": APP_ORIGIN },
          body: relayed.body === "" ? "{}" : relayed.body,
        }),
      );
      if (fulfilled && parallelTarget !== null) {
        parallelFulfillmentOrder.push(parallelTarget);
      }
    } catch {
      // 원문 parser·process 오류는 보관하거나 반사하지 않고 고정 abort로만 끝낸다.
      relayFailureCount += 1;
      await abort();
    } finally {
      if (parallelTarget !== null) {
        parallelRouteDone.get(parallelTarget)?.resolve();
      }
    }
  };

  const installedHandler = (route: Route): Promise<void> => {
    const handler = processRoute(route);
    activeHandlers.add(handler);
    void handler.then(
      () => activeHandlers.delete(handler),
      () => activeHandlers.delete(handler),
    );
    return handler;
  };
  routeHandler = installedHandler;
  await page.route(relayPattern, installedHandler);
  routeInstalled = true;

  Object.defineProperties(observations, {
    dispose: { value: () => owner.cleanup() },
    cleanupState: { value: () => owner.state() },
    activeHandlerCount: { value: () => activeHandlers.size },
    openProcessCount: { value: () => pool.openChildCount() },
    processEvents: { value: () => pool.events() },
    routeFulfillCount: { value: () => routeFulfillCount },
    routeAbortCount: { value: () => routeAbortCount },
    routeActionFailureCount: { value: () => routeActionFailureCount },
    routeActionStallCount: { value: () => routeActionStallCount },
    relayFailureCount: { value: () => relayFailureCount },
    barrierArrivalCountAtFirstForwarding: { value: () => barrierArrivalCountAtFirstForwarding },
    barrierAbortCount: { value: () => barrierAbortCount },
    parallelFulfillmentOrder: { value: () => [...parallelFulfillmentOrder] },
  });
  return observations;
}

/** 결정적 테스트용 수동 clock. 시간은 `advance`로만 흐른다. */
class ManualRelayClock implements RelayClock {
  private current = 0;
  private nextHandle = 1;
  private readonly timers = new Map<number, { readonly at: number; readonly callback: () => void }>();

  now(): number {
    return this.current;
  }

  setTimeout(callback: () => void, delayMs: number): unknown {
    const handle = this.nextHandle;
    this.nextHandle += 1;
    this.timers.set(handle, { at: this.current + Math.max(0, delayMs), callback });
    return handle;
  }

  clearTimeout(handle: unknown): void {
    this.timers.delete(handle as number);
  }

  pendingTimerCount(): number {
    return this.timers.size;
  }

  advance(delayMs: number): void {
    const target = this.current + delayMs;
    for (;;) {
      const due = [...this.timers.entries()]
        .filter(([, timer]) => timer.at <= target)
        .sort((left, right) => left[1].at - right[1].at || left[0] - right[0])[0];
      if (due === undefined) {
        break;
      }
      this.timers.delete(due[0]);
      this.current = Math.max(this.current, due[1].at);
      due[1].callback();
    }
    this.current = target;
  }

  /** 시간과 무관하게 현재 예약된 callback을 모두 실행하고 실행 수를 돌려준다. */
  runAll(): number {
    const pending = [...this.timers.values()];
    this.timers.clear();
    for (const timer of pending) {
      timer.callback();
    }
    return pending.length;
  }
}

async function flushRelayTasks(turns = 4): Promise<void> {
  for (let index = 0; index < turns; index += 1) {
    await new Promise<void>((resolveTurn) => {
      setImmediate(resolveTurn);
    });
  }
}

type ObservedOutcome<T> =
  | { readonly status: "fulfilled"; readonly value: T }
  | { readonly status: "rejected"; readonly error: unknown };

function observeOutcome<T>(promise: Promise<T>): Promise<ObservedOutcome<T>> {
  return promise.then<ObservedOutcome<T>, ObservedOutcome<T>>(
    (value) => ({ status: "fulfilled", value }),
    (error: unknown) => ({ status: "rejected", error }),
  );
}

function requireProcessFailure(
  outcome: ObservedOutcome<unknown>,
  reason: RelayProcessFailureReason,
): void {
  requireCondition(
    outcome.status === "rejected" &&
      outcome.error instanceof RelayProcessError &&
      outcome.error.reason === reason &&
      outcome.error.message === BACKEND_RELAY_FAILURE_MESSAGE,
    `The relay process did not fail with the fixed ${reason} outcome.`,
  );
}

function requireCleanupFailure(outcome: ObservedOutcome<unknown>, stage: RelayCleanupStage): void {
  requireCondition(
    outcome.status === "rejected" &&
      outcome.error instanceof RelayCleanupError &&
      outcome.error.stage === stage,
    `The relay cleanup did not fail at ${stage}.`,
  );
}

class FakeRelayStdin extends EventEmitter {
  readonly writes: Buffer[] = [];
  endCount = 0;
  writeResult = true;
  finishOnEnd = true;

  write(chunk: Buffer): boolean {
    this.writes.push(Buffer.from(chunk));
    return this.writeResult;
  }

  end(): void {
    this.endCount += 1;
    if (this.finishOnEnd) {
      this.emit("finish");
    }
  }
}

/** Node child와 같은 이벤트 계약을 따르는 fake. pool의 상태 머신은 production class 그대로다. */
class FakeRelayChild extends EventEmitter {
  readonly stdin = new FakeRelayStdin();
  readonly stdout = new EventEmitter();
  readonly stderr = new EventEmitter();
  readonly signals: NodeJS.Signals[] = [];
  pid: number | undefined = 4242;

  constructor(
    readonly args: readonly string[],
    readonly options: RelaySpawnOptions,
  ) {
    super();
  }

  kill(signal: NodeJS.Signals): boolean {
    this.signals.push(signal);
    return true;
  }

  spawnForTest(): void {
    this.emit("spawn");
  }

  writeForTest(stream: "stdout" | "stderr", bytes: Buffer | string): void {
    this[stream].emit("data", Buffer.isBuffer(bytes) ? bytes : Buffer.from(bytes, "utf8"));
  }

  closeForTest(code: number | null, signal: NodeJS.Signals | null = null): void {
    this.emit("close", code, signal);
  }

  asChild(): RelayChild {
    return this as unknown as RelayChild;
  }
}

interface FakeRelaySpawner {
  readonly spawn: RelaySpawn;
  readonly children: FakeRelayChild[];
  throwOnSpawn: boolean;
  onSpawn: ((child: FakeRelayChild) => void) | null;
}

function createFakeRelaySpawner(): FakeRelaySpawner {
  const children: FakeRelayChild[] = [];
  const spawner: FakeRelaySpawner = {
    children,
    throwOnSpawn: false,
    onSpawn: null,
    spawn: (executable, args, options) => {
      requireCondition(executable === "docker", "The fake relay spawner received another executable.");
      if (spawner.throwOnSpawn) {
        throw new Error("fake spawn secret");
      }
      const child = new FakeRelayChild(args, options);
      children.push(child);
      spawner.onSpawn?.(child);
      return child.asChild();
    },
  };
  return spawner;
}

type FakeRouteActionBehavior = "resolve" | "reject" | "stall";

class FakeBackendRelayPage {
  routeInstallCount = 0;
  routeRemovalCount = 0;
  private closed = false;
  private handler: ((route: Route) => Promise<void>) | null = null;
  private lastHandler: ((route: Route) => Promise<void>) | null = null;

  async route(_pattern: string, handler: (route: Route) => Promise<void>): Promise<void> {
    this.routeInstallCount += 1;
    this.handler = handler;
    this.lastHandler = handler;
  }

  unroute(_pattern: string, handler: (route: Route) => Promise<void>): Promise<void> {
    requireCondition(this.handler === handler, "The fake relay page removed another handler.");
    this.routeRemovalCount += 1;
    this.handler = null;
    return Promise.resolve();
  }

  isClosed(): boolean {
    return this.closed;
  }

  closeForTest(): void {
    this.closed = true;
  }

  invoke(route: Route): Promise<void> {
    requireCondition(this.handler !== null, "The fake relay page had no route handler.");
    return this.handler(route);
  }

  /** unroute 직전에 브라우저가 이미 잡아 둔 route가 늦게 도착한 경우를 재현한다. */
  invokeAfterUnroute(route: Route): Promise<void> {
    requireCondition(this.lastHandler !== null, "The fake relay page never installed a handler.");
    return this.lastHandler(route);
  }

  asPage(): Page {
    return this as unknown as Page;
  }
}

class FakeBackendRoute {
  abortCount = 0;
  fulfillCount = 0;
  fulfilledStatus: number | null = null;
  fulfilledBody: string | null = null;

  constructor(
    private readonly requestValue: PlaywrightRequest,
    private readonly behavior: FakeRouteActionBehavior = "resolve",
  ) {}

  request(): PlaywrightRequest {
    return this.requestValue;
  }

  abort(): Promise<void> {
    this.abortCount += 1;
    return this.outcome();
  }

  fulfill(response: Parameters<Route["fulfill"]>[0]): Promise<void> {
    this.fulfillCount += 1;
    this.fulfilledStatus = response?.status ?? null;
    this.fulfilledBody = typeof response?.body === "string" ? response.body : null;
    return this.outcome();
  }

  asRoute(): Route {
    return this as unknown as Route;
  }

  private outcome(): Promise<void> {
    if (this.behavior === "resolve") {
      return Promise.resolve();
    }
    if (this.behavior === "reject") {
      return Promise.reject(new Error("fake route action secret"));
    }
    return new Promise<void>(() => undefined);
  }
}

function httpResponseBytes(status: number, body: string): Buffer {
  const payload = Buffer.from(body, "utf8");
  return Buffer.concat([
    Buffer.from(
      `HTTP/1.1 ${String(status)} X\r\nContent-Type: application/json\r\nContent-Length: ${String(payload.byteLength)}\r\n\r\n`,
      "ascii",
    ),
    payload,
  ]);
}

type ObservedParallelStartOutcome =
  | { readonly status: "fulfilled" }
  | { readonly status: "rejected"; readonly error: unknown };

function observeParallelStart(promise: Promise<void>): Promise<ObservedParallelStartOutcome> {
  return promise.then<ObservedParallelStartOutcome, ObservedParallelStartOutcome>(
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
  clock: ManualRelayClock,
): void {
  const snapshot = barrier.snapshot();
  requireCondition(snapshot.pendingWaiterCount === 0, "A parallel-start waiter remained pending.");
  requireCondition(snapshot.activeTimerCount === 0, "A parallel-start timer remained active.");
  requireCondition(
    snapshot.activeArrivalWatchdogTimerCount === 0,
    "A parallel-start no-arrival watchdog timer remained active.",
  );
  requireCondition(snapshot.activeCallbackCount === 0, "A parallel-start callback remained active.");
  requireCondition(clock.pendingTimerCount() === 0, "The parallel-start clock retained a callback.");
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
    void relayToBackend(relayCandidate("GET", `${BACKEND_ORIGIN}${rejectedTarget}`));
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

/**
 * 첫 exact route 이전에 barrier timer를 시작하는 경로가 0개임을 spec 원문으로 고정한다.
 *
 * timer 시작 호출은 class 내부의 첫 exact `wait()`와 아래 결정적 lifecycle matrix에만 허용한다.
 * production-like 사건 상세 404 scenario는 시작 호출이 0개이고, 로그인 완료 뒤 별도 no-arrival
 * watchdog만 정확히 한 번 arm한다. 경계 문자열은 조각을 이어 만들어 이 함수 자신과 겹치지 않게 한다.
 */
function verifyParallelStartBarrierStartSites(): void {
  const source = readFileSync(fileURLToPath(import.meta.url), "utf8").replace(/\r\n/g, "\n");
  const lifecycleHeader = [
    "async function ",
    "verifyParallelStartBarrierLifecycle",
    "(): Promise<void> {",
  ].join("");
  const scenarioHeader = [
    'test("a real USER opens a case detail address ',
    'and meets the real Backend 404"',
  ].join("");
  const lifecycleBegin = source.indexOf(lifecycleHeader);
  const lifecycleEnd = lifecycleBegin < 0 ? -1 : source.indexOf("\n}\n", lifecycleBegin);
  const scenarioBegin = source.indexOf(scenarioHeader);
  const scenarioEnd = scenarioBegin < 0 ? -1 : source.indexOf("\n});\n", scenarioBegin);
  requireCondition(
    lifecycleBegin >= 0 &&
      lifecycleEnd > lifecycleBegin &&
      source.indexOf(lifecycleHeader, lifecycleBegin + 1) < 0 &&
      scenarioBegin >= 0 &&
      scenarioEnd > scenarioBegin &&
      source.indexOf(scenarioHeader, scenarioBegin + 1) < 0,
    "The parallel-start start-site check could not locate its source boundaries.",
  );
  const startCall = /([A-Za-z_$][\w$]*)\s*\.\s*start\s*\(\s*\)/g;
  for (const match of source.matchAll(startCall)) {
    requireCondition(
      match[1] === "this" || (match.index > lifecycleBegin && match.index < lifecycleEnd),
      "A parallel-start barrier timer can start outside its first exact route or lifecycle matrix.",
    );
  }
  const scenario = source.slice(scenarioBegin, scenarioEnd);
  requireCondition(
    [...scenario.matchAll(startCall)].length === 0,
    "The case detail scenario starts the parallel-start barrier before its first exact route.",
  );
  requireCondition(
    [...scenario.matchAll(/\.\s*armArrivalWatchdog\s*\(/g)].length === 1,
    "The case detail scenario did not arm exactly one no-arrival watchdog.",
  );
}

/** Deterministic lifecycle matrix for the spec-local barrier helper. */
async function verifyParallelStartBarrierLifecycle(): Promise<void> {
  verifyParallelStartBarrierStartSites();
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
    const clock = new ManualRelayClock();
    const barrier = new ParallelStartBarrier(targets, 1, clock);
    barrier.start();
    return { barrier, clock };
  };

  // A: three distinct exact targets release exactly once and cancel the timer.
  {
    const { barrier, clock } = createBarrier();
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
    requireNoParallelStartResources(barrier, clock);
    requireCondition(clock.runAll() === 0, "A released barrier still ran a timeout callback.");
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
    requireNoParallelStartResources(barrier, clock);
    requireCondition(clock.runAll() === 0, "A disposed released barrier ran a callback.");
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
    const { barrier, clock } = createBarrier();
    const completion = observeParallelStart(barrier.completion);
    const waits = targets.slice(0, arrivalCount).map(({ method, target }) =>
      observeParallelStart(requireParallelStartWait(barrier.wait(method, target))),
    );
    requireCondition(clock.runAll() === 1, "A pending barrier did not run its timeout once.");
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
    requireNoParallelStartResources(barrier, clock);
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
    requireNoParallelStartResources(barrier, clock);
  };

  // B-D: one, two, or all three missing targets fail without real-time waits.
  await verifyMissingTargets(2);
  await verifyMissingTargets(1);
  await verifyMissingTargets(0);

  // E: a duplicate cannot impersonate the third distinct target.
  {
    const { barrier, clock } = createBarrier();
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
    requireNoParallelStartResources(barrier, clock);
    requireCondition(clock.runAll() === 0, "A duplicate failure left a timeout callback.");
    barrier.dispose();
  }

  // F: an unexpected target neither completes nor allocates a waiter.
  {
    const { barrier, clock } = createBarrier();
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
    requireNoParallelStartResources(barrier, clock);
    requireCondition(clock.runAll() === 0, "A disposed barrier ran a callback.");
  }

  // G: disposal immediately before release and timeout is one-shot and empty.
  {
    const { barrier, clock } = createBarrier();
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
    requireNoParallelStartResources(barrier, clock);
    requireCondition(clock.runAll() === 0, "Dispose immediately before timeout ran a callback.");
  }

  // H: 명시적 start 전에는 timer가 없고, 첫 exact 도착이 timer 시작과 도착 등록을 함께 수행한다.
  {
    const clock = new ManualRelayClock();
    const barrier = new ParallelStartBarrier(targets, 1, clock);
    const idle = barrier.snapshot();
    requireCondition(
      idle.state === "pending" && idle.activeTimerCount === 0 && clock.pendingTimerCount() === 0,
      "A parallel-start barrier started before its first exact route.",
    );
    const completion = observeParallelStart(barrier.completion);
    const first = observeParallelStart(
      requireParallelStartWait(barrier.wait(targets[0].method, targets[0].target)),
    );
    const armed = barrier.snapshot();
    requireCondition(
      armed.activeTimerCount === 1 && armed.arrivedTargetCount === 1 && clock.pendingTimerCount() === 1,
      "The first exact route did not arm the barrier and register its arrival together.",
    );
    barrier.start();
    requireCondition(clock.pendingTimerCount() === 1, "A second start armed another barrier timer.");
    clock.advance(1);
    for (const outcome of await Promise.all([completion, first])) {
      requireFixedParallelStartFailure(outcome, PARALLEL_START_TIMEOUT_MESSAGE, forbiddenValues);
    }
    requireNoParallelStartResources(barrier, clock);
  }

  // I: 로그인 완료에 해당하는 watchdog arm은 barrier timer를 시작하지 않고, 중복 arm도 timer를 늘리지 않는다.
  //    route가 하나도 오지 않으면 barrier timeout이 아닌 no-arrival 고정 오류로 끝난다.
  {
    const clock = new ManualRelayClock();
    const barrier = new ParallelStartBarrier(targets, 1, clock);
    const completion = observeParallelStart(barrier.completion);
    barrier.armArrivalWatchdog(2);
    barrier.armArrivalWatchdog(2);
    const armed = barrier.snapshot();
    requireCondition(
      armed.state === "pending" &&
        armed.activeTimerCount === 0 &&
        armed.activeArrivalWatchdogTimerCount === 1 &&
        armed.arrivalWatchdogState === "armed" &&
        armed.activeCallbackCount === 1 &&
        clock.pendingTimerCount() === 1,
      "Arming the no-arrival watchdog started the barrier or added a second timer.",
    );
    clock.advance(1);
    requireCondition(
      barrier.snapshot().state === "pending",
      "The no-arrival watchdog expired on the barrier bound.",
    );
    clock.advance(1);
    requireFixedParallelStartFailure(
      await completion,
      PARALLEL_START_NO_ARRIVAL_MESSAGE,
      forbiddenValues,
    );
    const expired = barrier.snapshot();
    requireCondition(
      expired.state === "failed" &&
        expired.arrivalWatchdogState === "expired" &&
        expired.arrivalWatchdogCallbackCount === 1 &&
        expired.timeoutCallbackCount === 0 &&
        expired.completionResolveCount === 0 &&
        expired.completionRejectCount === 1,
      "A no-arrival watchdog expiry was classified as a barrier timeout.",
    );
    requireNoParallelStartResources(barrier, clock);
    barrier.armArrivalWatchdog(2);
    requireNoParallelStartResources(barrier, clock);
    barrier.dispose();
    requireNoParallelStartResources(barrier, clock);
    requireCondition(clock.runAll() === 0, "An expired no-arrival watchdog left a callback.");
  }

  // J: 첫 exact route가 watchdog을 해제하고 barrier timer를 시작한다. 일부 route만 오면 barrier 고정 오류다.
  {
    const clock = new ManualRelayClock();
    const barrier = new ParallelStartBarrier(targets, 3, clock);
    const completion = observeParallelStart(barrier.completion);
    barrier.armArrivalWatchdog(2);
    clock.advance(1);
    const first = observeParallelStart(
      requireParallelStartWait(barrier.wait(targets[0].method, targets[0].target)),
    );
    const disarmed = barrier.snapshot();
    requireCondition(
      disarmed.arrivalWatchdogState === "disarmed" &&
        disarmed.activeArrivalWatchdogTimerCount === 0 &&
        disarmed.activeTimerCount === 1 &&
        disarmed.arrivedTargetCount === 1 &&
        clock.pendingTimerCount() === 1,
      "The first exact route did not disarm the no-arrival watchdog and start the barrier timer.",
    );
    barrier.armArrivalWatchdog(2);
    requireCondition(
      clock.pendingTimerCount() === 1 && barrier.snapshot().arrivalWatchdogState === "disarmed",
      "Re-arming after the first exact route added a no-arrival watchdog timer.",
    );
    const second = observeParallelStart(
      requireParallelStartWait(barrier.wait(targets[1].method, targets[1].target)),
    );
    clock.advance(2);
    requireCondition(
      barrier.snapshot().state === "pending",
      "A disarmed no-arrival watchdog still expired.",
    );
    clock.advance(1);
    for (const outcome of await Promise.all([completion, first, second])) {
      requireFixedParallelStartFailure(outcome, PARALLEL_START_TIMEOUT_MESSAGE, forbiddenValues);
    }
    const failed = barrier.snapshot();
    requireCondition(
      failed.state === "failed" &&
        failed.timeoutCallbackCount === 1 &&
        failed.arrivalWatchdogCallbackCount === 0 &&
        failed.arrivalWatchdogState === "disarmed",
      "A partial arrival was not classified as a barrier timeout.",
    );
    requireNoParallelStartResources(barrier, clock);
    requireCondition(clock.runAll() === 0, "A partial-arrival barrier left a callback.");
  }

  // K: release 뒤 re-arm·dispose 경쟁에서도 두 timer·waiter·callback이 0이고 다시 settle하지 않는다.
  {
    const clock = new ManualRelayClock();
    const barrier = new ParallelStartBarrier(targets, 3, clock);
    const completion = observeParallelStart(barrier.completion);
    barrier.armArrivalWatchdog(2);
    const waits = targets.map(({ method, target }) =>
      observeParallelStart(requireParallelStartWait(barrier.wait(method, target))),
    );
    const outcomes = await Promise.all([completion, ...waits]);
    requireCondition(
      outcomes.every(({ status }) => status === "fulfilled"),
      "A watched parallel-start barrier did not release every observer.",
    );
    barrier.armArrivalWatchdog(2);
    requireNoParallelStartResources(barrier, clock);
    barrier.dispose();
    const disposed = barrier.snapshot();
    requireCondition(
      disposed.state === "disposed" &&
        disposed.completionResolveCount === 1 &&
        disposed.completionRejectCount === 0 &&
        disposed.timeoutCallbackCount === 0 &&
        disposed.arrivalWatchdogCallbackCount === 0,
      "Release followed by re-arm and dispose armed or settled the barrier again.",
    );
    requireNoParallelStartResources(barrier, clock);
    requireCondition(clock.runAll() === 0, "A released watched barrier ran a callback.");
  }

  // L: watchdog이 arm된 채 dispose되면 watchdog은 한 번만 취소되고 no-arrival 오류로 바뀌지 않는다.
  {
    const clock = new ManualRelayClock();
    const barrier = new ParallelStartBarrier(targets, 3, clock);
    const completion = observeParallelStart(barrier.completion);
    barrier.armArrivalWatchdog(2);
    barrier.dispose();
    barrier.dispose();
    requireFixedParallelStartFailure(
      await completion,
      PARALLEL_START_DISPOSED_MESSAGE,
      forbiddenValues,
    );
    const disposed = barrier.snapshot();
    requireCondition(
      disposed.state === "disposed" &&
        disposed.arrivalWatchdogState === "cancelled" &&
        disposed.arrivalWatchdogCallbackCount === 0 &&
        disposed.completionRejectCount === 1,
      "Dispose while the no-arrival watchdog was armed did not cancel it exactly once.",
    );
    requireNoParallelStartResources(barrier, clock);
    requireCondition(clock.runAll() === 0, "A disposed no-arrival watchdog ran a callback.");
  }
}

/** host process pool의 결정적 계약. fake child만 주입하고 상태 머신은 production class를 쓴다. */
async function verifyRelayProcessPoolLifecycle(): Promise<void> {
  const credentialSecret = "deterministic-pool-secret";
  const request = buildRelayRequestBytes(
    relayCandidate("GET", `${BACKEND_ORIGIN}${INITIAL_CASE_TARGET}`, `Bearer ${credentialSecret}`),
  );
  const token = "0badcafe-0000-4000-8000-000000000001";
  const args = relayDockerArguments(token);
  const createPool = () => {
    const spawner = createFakeRelaySpawner();
    const clock = new ManualRelayClock();
    return { spawner, clock, pool: new RelayProcessPool(spawner.spawn, clock) };
  };

  // A: argv에는 고정 script와 token만, stdin에는 정확한 요청 bytes만 들어간다.
  {
    const { spawner, clock, pool } = createPool();
    const outcome = observeOutcome(pool.run("success", args, request.bytes, 1_000));
    const child = spawner.children[0];
    requireCondition(
      spawner.children.length === 1 &&
        child.options.shell === false &&
        child.options.windowsHide === true &&
        child.args.every(
          (argument) =>
            !argument.includes(credentialSecret) && !argument.includes(INITIAL_CASE_TARGET),
        ) &&
        child.stdin.writes.length === 1 &&
        child.stdin.writes[0].equals(request.bytes) &&
        child.stdin.endCount === 1,
      "The relay process received request or credential data outside stdin.",
    );
    const response = httpResponseBytes(200, "{}");
    child.spawnForTest();
    child.writeForTest("stdout", response.subarray(0, 7));
    child.writeForTest("stdout", response.subarray(7));
    child.writeForTest("stderr", "stderr secret");
    child.closeForTest(0);
    const result = await outcome;
    const events = pool.events();
    requireCondition(
      result.status === "fulfilled" &&
        result.value.equals(response) &&
        pool.openChildCount() === 0 &&
        clock.pendingTimerCount() === 0 &&
        events.length === 2 &&
        events[0].kind === "spawn" &&
        events[1].kind === "close",
      "A successful relay process did not return its exact bytes and release ownership on close.",
    );
  }

  // B: 출력 상한은 append 전에 검사하고, 상한 초과는 실패 확정 뒤에도 실제 close까지 소유한다.
  for (const [stream, limit, reason] of [
    ["stdout", MAX_RELAY_STDOUT_BYTES, "stdout-limit"],
    ["stderr", MAX_RELAY_STDERR_BYTES, "stderr-limit"],
  ] as const) {
    const { spawner, clock, pool } = createPool();
    const outcome = observeOutcome(pool.run(stream, args, request.bytes, 1_000));
    const child = spawner.children[0];
    child.spawnForTest();
    child.writeForTest(stream, Buffer.alloc(limit, 0x61));
    requireCondition(child.signals.length === 0, "An output of exactly the bound was rejected.");
    child.writeForTest(stream, Buffer.alloc(1, 0x61));
    // 상한 초과가 무시되면 결과가 끝나지 않으므로 기다리기 전에 중지 요청부터 확인한다.
    requireCondition(
      child.signals.join(",") === "SIGTERM" && pool.openChildCount() === 1,
      "An output bound did not stop the child while keeping ownership.",
    );
    requireProcessFailure(await outcome, reason);
    child.writeForTest(stream, "late secret");
    clock.advance(RELAY_HOST_KILL_GRACE_MS);
    requireCondition(child.signals.join(",") === "SIGTERM,SIGKILL", "A stopped child did not escalate to KILL.");
    child.closeForTest(null, "SIGKILL");
    requireCondition(
      pool.openChildCount() === 0 && pool.lateEventCount() === 1 && clock.pendingTimerCount() === 0,
      "A stopped child did not release ownership exactly on close.",
    );
  }

  // C: spawn 실패는 child 없이 끝나고, spawn 전 error는 소유를 남기지 않는다.
  {
    const { spawner, clock, pool } = createPool();
    spawner.throwOnSpawn = true;
    requireProcessFailure(await observeOutcome(pool.run("throw", args, request.bytes, 1_000)), "spawn-error");
    requireCondition(
      spawner.children.length === 0 && pool.openChildCount() === 0 && clock.pendingTimerCount() === 0,
      "A synchronous spawn failure retained ownership.",
    );
    spawner.throwOnSpawn = false;
    const outcome = observeOutcome(pool.run("error", args, request.bytes, 1_000));
    const child = spawner.children[0];
    child.pid = undefined;
    child.emit("error", new Error("spawn error secret"));
    requireProcessFailure(await outcome, "spawn-error");
    requireCondition(
      pool.openChildCount() === 0 && clock.pendingTimerCount() === 0 && pool.events().length === 1,
      "A child that never spawned retained ownership.",
    );
  }

  // D: backpressure는 drain 전 stdin을 닫지 않고, stdin 오류와 조기 close는 고정 실패이다.
  {
    const { spawner, pool } = createPool();
    spawner.onSpawn = (child) => {
      child.stdin.writeResult = false;
    };
    const outcome = observeOutcome(pool.run("drain", args, request.bytes, 1_000));
    const child = spawner.children[0];
    // asserts 함수가 mutable property를 literal로 좁히지 않도록 관찰값을 따로 읽는다.
    const endCountBeforeDrain: number = child.stdin.endCount;
    requireCondition(endCountBeforeDrain === 0, "Stdin was closed before drain.");
    child.stdin.emit("drain");
    requireCondition(child.stdin.endCount === 1, "Stdin was not closed after drain.");
    child.spawnForTest();
    child.writeForTest("stdout", "ok");
    child.closeForTest(0);
    const result = await outcome;
    requireCondition(result.status === "fulfilled", "A drained relay process did not complete.");
  }
  for (const failure of ["error", "premature-close"] as const) {
    const { spawner, pool } = createPool();
    spawner.onSpawn = (child) => {
      child.stdin.finishOnEnd = failure !== "premature-close";
    };
    const outcome = observeOutcome(pool.run(failure, args, request.bytes, 1_000));
    const child = spawner.children[0];
    child.spawnForTest();
    if (failure === "error") {
      child.stdin.emit("error", new Error("stdin secret"));
    } else {
      child.stdin.emit("close");
    }
    requireProcessFailure(await outcome, "stdin-error");
    child.closeForTest(null, "SIGTERM");
    requireCondition(
      child.signals[0] === "SIGTERM" && pool.openChildCount() === 0,
      "A stdin failure did not stop and then release its child.",
    );
  }

  // E: non-zero exit와 signal은 서로 다른 분류이지만 같은 고정 문구로 끝난다.
  for (const [code, signal, reason] of [
    [7, null, "non-zero-exit"],
    [null, "SIGTERM", "signal"],
  ] as const) {
    const { spawner, pool } = createPool();
    const outcome = observeOutcome(pool.run(reason, args, request.bytes, 1_000));
    const child = spawner.children[0];
    child.spawnForTest();
    child.writeForTest("stdout", "partial secret");
    child.closeForTest(code, signal);
    requireProcessFailure(await outcome, reason);
    requireCondition(pool.openChildCount() === 0, "An exited child retained ownership.");
  }

  // F: request deadline은 결과를 먼저 확정하고, 늦은 출력·오류·close는 관찰만 된다.
  {
    const { spawner, clock, pool } = createPool();
    const outcome = observeOutcome(pool.run("deadline", args, request.bytes, 1_000));
    const child = spawner.children[0];
    child.spawnForTest();
    clock.advance(999);
    requireCondition(child.signals.length === 0, "A relay process stopped before its deadline.");
    clock.advance(1);
    requireProcessFailure(await outcome, "deadline");
    requireCondition(child.signals.join(",") === "SIGTERM", "The deadline did not request TERM.");
    clock.advance(RELAY_HOST_KILL_GRACE_MS);
    child.writeForTest("stdout", httpResponseBytes(200, "{}"));
    child.emit("error", new Error("late error secret"));
    child.closeForTest(0);
    requireCondition(
      child.signals.join(",") === "SIGTERM,SIGKILL" &&
        pool.openChildCount() === 0 &&
        pool.lateEventCount() === 2 &&
        clock.pendingTimerCount() === 0,
      "Late relay events changed the outcome or retained ownership.",
    );
    const before = spawner.children.length;
    requireProcessFailure(await observeOutcome(pool.run("expired", args, request.bytes, 0)), "deadline");
    requireCondition(spawner.children.length === before, "An expired relay deadline still spawned.");
  }

  // G: close가 오지 않으면 teardown 대기는 bounded 실패하고 child 소유를 유지한다.
  {
    const { spawner, clock, pool } = createPool();
    const outcome = observeOutcome(pool.run("never-closes", args, request.bytes, 10_000));
    const child = spawner.children[0];
    child.spawnForTest();
    pool.stopAll();
    requireProcessFailure(await outcome, "stopped");
    const waiting = observeOutcome(pool.waitForClose(RELAY_HOST_CLOSE_TIMEOUT_MS));
    clock.advance(RELAY_HOST_CLOSE_TIMEOUT_MS);
    requireCleanupFailure(await waiting, "host-close");
    requireCondition(
      pool.openChildCount() === 1 && child.signals.join(",") === "SIGTERM,SIGKILL",
      "A child without close lost ownership after a bounded teardown failure.",
    );
    child.closeForTest(null, "SIGKILL");
    await pool.waitForClose(RELAY_HOST_CLOSE_TIMEOUT_MS);
    requireCondition(pool.openChildCount() === 0 && clock.pendingTimerCount() === 0, "A late close was not observed.");
  }

  // H: 세 relay는 첫 close 전에 모두 spawn되어 병렬로 존재할 수 있다.
  {
    const { spawner, pool } = createPool();
    const outcomes = ["a", "b", "c"].map((label) =>
      observeOutcome(pool.run(label, args, request.bytes, 1_000)),
    );
    for (const child of spawner.children) {
      child.spawnForTest();
    }
    spawner.children[1].closeForTest(0);
    spawner.children[0].closeForTest(0);
    spawner.children[2].closeForTest(0);
    const events = pool.events();
    const firstClose = events.findIndex(({ kind }) => kind === "close");
    requireCondition(
      firstClose === 3 &&
        events.slice(0, 3).every(({ kind }) => kind === "spawn") &&
        (await Promise.all(outcomes)).every(({ status }) => status === "fulfilled") &&
        pool.openChildCount() === 0,
      "Three relay processes were not concurrently owned before the first close.",
    );
  }
}

/** container marker audit의 argv·stdin·출력 계약. 실제 script는 어떤 process에도 신호를 보내지 않는다. */
async function verifyContainerMarkerAuditContract(): Promise<void> {
  requireCondition(
    parseMarkerAuditOutput(Buffer.from("zero\n", "latin1")) === 0 &&
      parseMarkerAuditOutput(Buffer.from("present:3\n", "latin1")) === 3,
    "A canonical marker audit output was refused.",
  );
  for (const malformed of [
    "zero",
    "present:0\n",
    "present:-1\n",
    "present:01\n",
    "present:3\r\n",
    "zero\nzero\n",
    "ZERO\n",
    "",
  ]) {
    let stage: RelayCleanupStage | null = null;
    try {
      parseMarkerAuditOutput(Buffer.from(malformed, "latin1"));
    } catch (error: unknown) {
      stage = error instanceof RelayCleanupError ? error.stage : null;
    }
    requireCondition(stage === "container-audit", "A malformed marker audit output was accepted.");
  }
  requireCondition(
    !/\bkill\b|pkill|killall|timeout/.test(CONTAINER_MARKER_AUDIT_SCRIPT),
    "The marker audit script can signal or bound another process.",
  );

  const spawner = createFakeRelaySpawner();
  const pool = new RelayProcessPool(spawner.spawn, new ManualRelayClock());
  const audit = createDockerMarkerAudit(pool);
  const token = "0badcafe-0000-4000-8000-000000000002";

  const counted = observeOutcome(audit(token, "until-zero", CONTAINER_AUDIT_POLL_SECONDS));
  const tokenChild = spawner.children[0];
  requireCondition(
    tokenChild.args.join(" ") ===
      markerAuditArguments("until-zero", CONTAINER_AUDIT_POLL_SECONDS).join(" ") &&
      !tokenChild.args.includes(token) &&
      tokenChild.stdin.writes[0].toString("latin1") === `${token}\n`,
    "The marker audit did not keep its token on stdin with fixed argv.",
  );
  tokenChild.spawnForTest();
  tokenChild.writeForTest("stdout", "present:2\n");
  tokenChild.closeForTest(0);
  const tokenResult = await counted;
  requireCondition(
    tokenResult.status === "fulfilled" && tokenResult.value === 2,
    "The marker audit did not report the exact present count.",
  );

  const suite = observeOutcome(audit(null, "until-zero", "0"));
  const suiteChild = spawner.children[1];
  requireCondition(
    suiteChild.stdin.writes[0].toString("latin1") === "\n" && suiteChild.args.at(-1) === "0",
    "The suite-wide marker audit did not use an empty token line.",
  );
  suiteChild.spawnForTest();
  suiteChild.writeForTest("stdout", "zero\n");
  suiteChild.closeForTest(0);
  const suiteResult = await suite;
  requireCondition(
    suiteResult.status === "fulfilled" && suiteResult.value === 0,
    "The suite-wide marker audit did not report zero.",
  );

  const before = spawner.children.length;
  requireCleanupFailure(await observeOutcome(audit("not-a-token", "until-zero", "0")), "container-audit");
  requireCondition(spawner.children.length === before, "An invalid marker token reached Docker.");

  const failing = observeOutcome(audit(token, "until-zero", "0"));
  const failingChild = spawner.children[before];
  failingChild.spawnForTest();
  failingChild.writeForTest("stdout", "zero\n");
  failingChild.closeForTest(1);
  requireCleanupFailure(await failing, "container-audit");
}

/** cleanup owner 상태 머신의 결정적 계약. production owner에 pool·audit·release seam만 주입한다. */
async function verifyRelayCleanupOwnerLifecycle(): Promise<void> {
  const token = "0badcafe-0000-4000-8000-000000000003";
  const scriptedAudit = (answers: (number | RelayCleanupError)[]) => {
    const calls: string[] = [];
    const audit: RelayMarkerAudit = async (auditToken, mode, poll) => {
      calls.push(`${String(auditToken)}:${mode}:${poll}`);
      const next = answers.shift();
      requireCondition(next !== undefined, "The owner requested an unexpected marker audit.");
      if (next instanceof RelayCleanupError) {
        throw next;
      }
      return next;
    };
    return { audit, calls };
  };

  // A: 성공은 clean terminal이며 동시 호출은 같은 Promise를 공유한다.
  {
    const registry: RelayCleanupRegistry = new Map();
    const pool = new RelayProcessPool(createFakeRelaySpawner().spawn, new ManualRelayClock());
    const { audit, calls } = scriptedAudit([0]);
    const owner = new RelayResourceOwner({ token, pool, audit, registry, testId: "deterministic" });
    requireCondition(
      owner.state() === "active" && registry.get(owner) === "deterministic",
      "A relay owner was not registered before cleanup.",
    );
    const first = owner.cleanup();
    const second = owner.cleanup();
    requireCondition(first === second && owner.state() === "cleaning", "Concurrent cleanup did not share one attempt.");
    await first;
    requireCondition(
      owner.state() === "clean" &&
        registry.size === 0 &&
        owner.attempts() === 1 &&
        calls.join("|") === `${token}:until-zero:${CONTAINER_AUDIT_POLL_SECONDS}`,
      "A successful cleanup did not reach clean after one exact audit.",
    );
    await owner.cleanup();
    requireCondition(owner.attempts() === 1 && calls.length === 1, "A clean owner started another attempt.");
  }

  // B: 남은 marker와 audit 실패는 failed로 남기고, 재호출은 새 attempt이다.
  for (const firstAnswer of [2, new RelayCleanupError("container-audit")]) {
    const registry: RelayCleanupRegistry = new Map();
    const pool = new RelayProcessPool(createFakeRelaySpawner().spawn, new ManualRelayClock());
    const { audit, calls } = scriptedAudit([firstAnswer, 0]);
    const owner = new RelayResourceOwner({ token, pool, audit, registry, testId: "deterministic" });
    const firstAttempt = owner.cleanup();
    requireCleanupFailure(
      await observeOutcome(firstAttempt),
      typeof firstAnswer === "number" ? "container-present" : "container-audit",
    );
    requireCondition(
      owner.state() === "failed" && registry.get(owner) === "deterministic" && owner.token === token,
      "A failed cleanup removed ownership or registry evidence.",
    );
    const retry = owner.cleanup();
    requireCondition(retry !== firstAttempt, "A failed cleanup Promise was memoized.");
    await retry;
    requireCondition(
      owner.state() === "clean" && registry.size === 0 && owner.attempts() === 2 && calls.length === 2,
      "A failed cleanup could not be retried to clean.",
    );
  }

  // C: host close가 오지 않으면 bounded 실패하고 child 소유를 유지한다. 재시도는 business 요청을 다시 보내지 않는다.
  {
    const registry: RelayCleanupRegistry = new Map();
    const spawner = createFakeRelaySpawner();
    const clock = new ManualRelayClock();
    const pool = new RelayProcessPool(spawner.spawn, clock);
    const relay = observeOutcome(
      pool.run("in-flight", relayDockerArguments(token), Buffer.from("GET / HTTP/1.1\r\n\r\n", "ascii"), 4_000),
    );
    const child = spawner.children[0];
    child.spawnForTest();
    const { audit, calls } = scriptedAudit([0]);
    const owner = new RelayResourceOwner({ token, pool, audit, registry, testId: "deterministic" });
    const attempt = observeOutcome(owner.cleanup());
    await flushRelayTasks();
    requireCondition(child.signals.join(",") === "SIGTERM", "Cleanup did not stop the in-flight host child.");
    clock.advance(RELAY_HOST_KILL_GRACE_MS);
    clock.advance(RELAY_HOST_CLOSE_TIMEOUT_MS);
    requireCleanupFailure(await attempt, "host-close");
    requireProcessFailure(await relay, "stopped");
    const auditsBeforeRetry: number = calls.length;
    requireCondition(
      owner.state() === "failed" &&
        pool.openChildCount() === 1 &&
        registry.has(owner) &&
        auditsBeforeRetry === 0 &&
        child.signals.join(",") === "SIGTERM,SIGKILL",
      "A host-close failure lost child ownership or audited too early.",
    );
    child.closeForTest(null, "SIGKILL");
    await owner.cleanup();
    requireCondition(
      owner.state() === "clean" && spawner.children.length === 1 && calls.length === 1 && registry.size === 0,
      "Cleanup retry spawned a business request or skipped the container audit.",
    );
  }

  // D: 호출자 자원 정리 실패도 failed로 남고 같은 owner로 재시도한다.
  {
    const registry: RelayCleanupRegistry = new Map();
    const pool = new RelayProcessPool(createFakeRelaySpawner().spawn, new ManualRelayClock());
    const { audit, calls } = scriptedAudit([0]);
    let remainingFailures = 1;
    const owner = new RelayResourceOwner({
      token,
      pool,
      audit,
      registry,
      testId: "deterministic",
      releaseCallers: async () => {
        if (remainingFailures > 0) {
          remainingFailures -= 1;
          throw new RelayCleanupError("handlers");
        }
      },
    });
    requireCleanupFailure(await observeOutcome(owner.cleanup()), "handlers");
    const auditsAfterHandlerFailure: number = calls.length;
    requireCondition(
      owner.state() === "failed" && auditsAfterHandlerFailure === 0 && registry.has(owner),
      "A handler cleanup failure was hidden.",
    );
    await owner.cleanup();
    requireCondition(owner.state() === "clean" && calls.length === 1 && registry.size === 0, "A handler cleanup failure could not be retried.");
  }
}

/** 설치된 route handler의 결정적 계약. fake page·route·child만 주입한다. */
async function verifyBackendRelayRouteLifecycle(): Promise<void> {
  const credentialSecret = "deterministic-route-secret";
  const okBody = '{"content":[]}';
  const createHarness = async (
    extra: (clock: ManualRelayClock) => Partial<RelayOptions> = () => ({}),
  ) => {
    const spawner = createFakeRelaySpawner();
    const clock = new ManualRelayClock();
    const page = new FakeBackendRelayPage();
    const registry: RelayCleanupRegistry = new Map();
    const audits: string[] = [];
    const relay = await installBackendRelay(page.asPage(), {
      spawnChild: spawner.spawn,
      clock,
      registry,
      testId: "deterministic",
      markerAudit: () => async (token) => {
        audits.push(String(token));
        return 0;
      },
      ...extra(clock),
    });
    return { spawner, clock, page, registry, relay, audits };
  };
  const routeTo = (method: string, target: string, behavior: FakeRouteActionBehavior = "resolve") =>
    new FakeBackendRoute(
      relayCandidate(method, `${BACKEND_ORIGIN}${target}`, `Bearer ${credentialSecret}`),
      behavior,
    );
  const requireClean = async (harness: Awaited<ReturnType<typeof createHarness>>): Promise<void> => {
    await harness.relay.dispose();
    requireCondition(
      harness.relay.cleanupState() === "clean" &&
        harness.registry.size === 0 &&
        harness.relay.activeHandlerCount() === 0 &&
        harness.relay.openProcessCount() === 0 &&
        harness.clock.pendingTimerCount() === 0,
      "A deterministic relay did not reach clean teardown.",
    );
  };

  // A: 거부 요청은 spawn·observation 없이 abort 한 번으로 끝난다.
  {
    const harness = await createHarness();
    const spawnsBefore = relaySpawnCount;
    const observationsBefore = relayObservationCount;
    const refused = routeTo("PATCH", `${CASE_LIST_PATH}/${SYNTHETIC_CASE_ID}/status`);
    await harness.page.invoke(refused.asRoute());
    requireCondition(
      refused.abortCount === 1 &&
        refused.fulfillCount === 0 &&
        harness.spawner.children.length === 0 &&
        harness.relay.length === 0 &&
        relaySpawnCount === spawnsBefore &&
        relayObservationCount === observationsBefore,
      "A refused Backend write spawned, observed or settled more than once.",
    );
    await requireClean(harness);
  }

  // B: 성공은 실제 응답 bytes를 한 번 관찰하고 한 번 fulfill한다.
  {
    const harness = await createHarness(() => ({ captureBodyOf: CASE_LIST_PATH }));
    const success = routeTo("GET", INITIAL_CASE_TARGET);
    const handling = harness.page.invoke(success.asRoute());
    await flushRelayTasks();
    const child = harness.spawner.children[0];
    requireCondition(
      child !== undefined &&
        child.args.every(
          (argument) => !argument.includes(credentialSecret) && !argument.includes(INITIAL_CASE_TARGET),
        ) &&
        child.stdin.writes[0].includes(Buffer.from(`Authorization: Bearer ${credentialSecret}\r\n`, "ascii")),
      "The installed relay did not keep the request on stdin.",
    );
    child.spawnForTest();
    child.writeForTest("stdout", httpResponseBytes(200, okBody));
    child.closeForTest(0);
    await handling;
    requireCondition(
      success.fulfillCount === 1 &&
        success.abortCount === 0 &&
        success.fulfilledStatus === 200 &&
        success.fulfilledBody === okBody &&
        harness.relay.length === 1 &&
        harness.relay[0].target === INITIAL_CASE_TARGET &&
        harness.relay[0].body === okBody &&
        harness.relay.relayFailureCount() === 0,
      "A successful relay was not observed and fulfilled exactly once.",
    );
    await requireClean(harness);
  }

  // C: process 실패와 malformed 응답은 abort 한 번이며, 거부된 route action은 성공으로 보지 않는다.
  for (const failure of ["non-zero-exit", "malformed-response", "action-rejected"] as const) {
    const harness = await createHarness();
    const route = routeTo("GET", INITIAL_CASE_TARGET, failure === "action-rejected" ? "reject" : "resolve");
    const handling = harness.page.invoke(route.asRoute());
    await flushRelayTasks();
    const child = harness.spawner.children[0];
    child.spawnForTest();
    child.writeForTest(
      "stdout",
      failure === "malformed-response"
        ? "HTTP/1.1 200 X\r\nContent-Length: 2\r\n\r\nraw-response-secret"
        : httpResponseBytes(200, okBody),
    );
    child.closeForTest(failure === "non-zero-exit" ? 7 : 0);
    await handling;
    const rejectedAction = failure === "action-rejected";
    requireCondition(
      route.fulfillCount === (rejectedAction ? 1 : 0) &&
        route.abortCount === (rejectedAction ? 0 : 1) &&
        harness.relay.length === (rejectedAction ? 1 : 0) &&
        harness.relay.routeActionFailureCount() === (rejectedAction ? 1 : 0) &&
        harness.relay.relayFailureCount() === (rejectedAction ? 0 : 1),
      `The ${failure} relay path did not settle its route exactly once.`,
    );
    await requireClean(harness);
  }

  // D: request deadline에 TERM을 요청하고 production 5초 전에 abort하며, 늦은 결과는 무시한다.
  {
    const harness = await createHarness();
    const slow = routeTo("GET", INITIAL_CASE_TARGET);
    const handling = harness.page.invoke(slow.asRoute());
    await flushRelayTasks();
    const child = harness.spawner.children[0];
    child.spawnForTest();
    harness.clock.advance(RELAY_REQUEST_DEADLINE_MS - 1);
    await flushRelayTasks();
    const abortsBeforeDeadline: number = slow.abortCount;
    const signalsBeforeDeadline: number = child.signals.length;
    requireCondition(
      abortsBeforeDeadline === 0 && signalsBeforeDeadline === 0,
      "A relay route ended before its request deadline.",
    );
    harness.clock.advance(1);
    await flushRelayTasks();
    // deadline이 무시되면 handler가 끝나지 않으므로 기다리기 전에 abort부터 확인한다.
    const abortsAtDeadline: number = slow.abortCount;
    requireCondition(abortsAtDeadline === 1, "The request deadline did not abort the route.");
    await handling;
    requireCondition(
      slow.abortCount === 1 &&
        slow.fulfillCount === 0 &&
        child.signals.join(",") === "SIGTERM" &&
        harness.clock.now() < PRODUCTION_AUTHENTICATED_REQUEST_TIMEOUT_MS &&
        harness.relay.openProcessCount() === 1,
      "The request deadline did not abort before production timeout while keeping host ownership.",
    );
    child.writeForTest("stdout", httpResponseBytes(200, okBody));
    child.closeForTest(0);
    await flushRelayTasks();
    requireCondition(
      slow.fulfillCount === 0 && slow.abortCount === 1 && harness.relay.length === 0 && harness.relay.openProcessCount() === 0,
      "A late relay result reached the browser.",
    );
    await requireClean(harness);
  }

  // E: 멈춘 route action은 상한 뒤 handler를 끝내지만 성공으로 세지 않는다.
  {
    const harness = await createHarness();
    const stalled = routeTo("GET", INITIAL_CASE_TARGET, "stall");
    const handling = harness.page.invoke(stalled.asRoute());
    await flushRelayTasks();
    const child = harness.spawner.children[0];
    child.spawnForTest();
    child.writeForTest("stdout", httpResponseBytes(200, okBody));
    child.closeForTest(0);
    await flushRelayTasks();
    requireCondition(stalled.fulfillCount === 1 && harness.relay.activeHandlerCount() === 1, "A stalled fulfill was not pending.");
    harness.clock.advance(RELAY_ROUTE_ACTION_TIMEOUT_MS);
    await handling;
    requireCondition(
      harness.relay.routeActionStallCount() === 1 && stalled.abortCount === 0 && harness.relay.activeHandlerCount() === 0,
      "A stalled route action was treated as settled or retried.",
    );
    await requireClean(harness);
  }

  // F: 이미 닫힌 page에는 terminal action을 보내지 않는다.
  {
    const harness = await createHarness();
    const closing = routeTo("GET", INITIAL_CASE_TARGET);
    const handling = harness.page.invoke(closing.asRoute());
    await flushRelayTasks();
    const child = harness.spawner.children[0];
    child.spawnForTest();
    harness.page.closeForTest();
    child.writeForTest("stdout", httpResponseBytes(200, okBody));
    child.closeForTest(0);
    await handling;
    requireCondition(closing.fulfillCount === 0 && closing.abortCount === 0, "A closed page received a route action.");
    await requireClean(harness);
    requireCondition(harness.page.routeRemovalCount === 0, "A closed page was unrouted.");
  }

  // G: dispose는 route를 제거하고 in-flight host child를 멈춘 뒤 handler 종결과 close를 기다린다.
  {
    const harness = await createHarness();
    const pending = routeTo("GET", INITIAL_CASE_TARGET);
    const handling = harness.page.invoke(pending.asRoute());
    await flushRelayTasks();
    const child = harness.spawner.children[0];
    child.spawnForTest();
    const disposal = observeOutcome(harness.relay.dispose());
    await flushRelayTasks();
    requireCondition(
      harness.relay.cleanupState() === "cleaning" &&
        harness.page.routeRemovalCount === 1 &&
        child.signals.join(",") === "SIGTERM" &&
        pending.abortCount === 1,
      "Dispose did not unroute, stop the host child and abort the in-flight route.",
    );
    child.closeForTest(null, "SIGTERM");
    await handling;
    const disposed = await disposal;
    requireCondition(
      disposed.status === "fulfilled" &&
        pending.fulfillCount === 0 &&
        harness.relay.cleanupState() === "clean" &&
        harness.audits.length === 1 &&
        harness.registry.size === 0,
      "Dispose did not reach clean after the host child closed.",
    );
    const late = routeTo("GET", INITIAL_CASE_TARGET);
    await harness.page.invokeAfterUnroute(late.asRoute());
    requireCondition(
      late.abortCount === 1 && late.fulfillCount === 0 && harness.spawner.children.length === 1,
      "A route arriving after dispose spawned a relay.",
    );
    await harness.relay.dispose();
    requireCondition(harness.audits.length === 1, "A clean relay audited again.");
  }

  // H: 세 barrier read는 첫 close 전에 모두 spawn되고, detail은 먼저 응답해도 마지막에 전달된다.
  {
    const targets = [CASE_DETAIL_TARGET, INITIAL_CASE_NOTES_TARGET, INITIAL_CASE_AUDIT_TARGET];
    const harness = await createHarness((clock) => ({
      parallelStartBarrier: new ParallelStartBarrier(
        targets.map((target) => ({ method: "GET", target })),
        PARALLEL_START_TIMEOUT_MS,
        clock,
      ),
      deliverLast: CASE_DETAIL_TARGET,
    }));
    const routes = targets.map((target) => routeTo("GET", target));
    const handlings = routes.map((route) => harness.page.invoke(route.asRoute()));
    await flushRelayTasks();
    const childFor = (target: string): FakeRelayChild => {
      const found = harness.spawner.children.find((child) =>
        child.stdin.writes[0]?.toString("latin1").startsWith(`GET ${target} HTTP/1.1\r\n`),
      );
      requireCondition(found !== undefined, "A barrier read did not spawn a relay process.");
      return found;
    };
    requireCondition(harness.spawner.children.length === 3, "The released barrier did not start three relays.");
    for (const child of harness.spawner.children) {
      child.spawnForTest();
    }
    const detailChild = childFor(CASE_DETAIL_TARGET);
    detailChild.writeForTest("stdout", httpResponseBytes(404, "{}"));
    detailChild.closeForTest(0);
    await flushRelayTasks();
    requireCondition(routes[0].fulfillCount === 0, "Detail was delivered before notes and audit.");
    for (const target of [INITIAL_CASE_NOTES_TARGET, INITIAL_CASE_AUDIT_TARGET]) {
      const child = childFor(target);
      child.writeForTest("stdout", httpResponseBytes(404, "{}"));
      child.closeForTest(0);
    }
    await Promise.all(handlings);
    const order = harness.relay.parallelFulfillmentOrder();
    const events = harness.relay.processEvents();
    const firstClose = events.findIndex(({ kind }) => kind === "close");
    requireCondition(
      order.length === 3 &&
        new Set(order).size === 3 &&
        order[2] === CASE_DETAIL_TARGET &&
        routes.every((route) => route.fulfillCount === 1 && route.abortCount === 0) &&
        firstClose === 3 &&
        events.slice(0, 3).every(({ kind }) => kind === "spawn") &&
        harness.relay.barrierArrivalCountAtFirstForwarding() === 3,
      "The three barrier reads were not concurrent or detail was not delivered last.",
    );
    await requireClean(harness);
  }

  // I: 세 번째 read가 오지 않으면 도착한 route만 barrier 상한 뒤 abort하고 spawn하지 않는다.
  {
    const targets = [CASE_DETAIL_TARGET, INITIAL_CASE_NOTES_TARGET, INITIAL_CASE_AUDIT_TARGET];
    const harness = await createHarness((clock) => ({
      parallelStartBarrier: new ParallelStartBarrier(
        targets.map((target) => ({ method: "GET", target })),
        PARALLEL_START_TIMEOUT_MS,
        clock,
      ),
    }));
    const routes = targets.slice(0, 2).map((target) => routeTo("GET", target));
    const handlings = routes.map((route) => harness.page.invoke(route.asRoute()));
    await flushRelayTasks();
    harness.clock.advance(PARALLEL_START_TIMEOUT_MS);
    await Promise.all(handlings);
    requireCondition(
      routes.every((route) => route.abortCount === 1 && route.fulfillCount === 0) &&
        harness.relay.barrierAbortCount() === 2 &&
        harness.spawner.children.length === 0,
      "A timed-out barrier forwarded a read or settled it more than once.",
    );
    await requireClean(harness);
  }
}

/**
 * TERM을 무시하는 두 descendant를 가진 process group. GNU timeout의 group KILL로만 끝나며
 * marker 격리와 wait-only cleanup을 실제 Docker에서 확인하는 데만 쓴다. token은 stdin으로 받는다.
 */
const ACTUAL_TERM_IGNORING_GROUP_SCRIPT = [
  "set -euo pipefail",
  "IFS= read -r relay_token",
  `[[ $relay_token =~ ^${CANONICAL_UUID_V4_PATTERN}$ ]] || exit 70`,
  `readonly relay_marker="${RELAY_MARKER_PREFIX}\${relay_token}"`,
  `exec -a "$relay_marker" timeout --signal=TERM --kill-after=0.5s 9s bash -c 'trap "" TERM; (exec -a "$1" sleep 30) & (exec -a "$1" sleep 30) & wait' -- "$relay_marker"`,
].join("\n");

const ACTUAL_TERM_IGNORING_GROUP_ARGUMENTS: readonly string[] = [
  "exec",
  "-i",
  BACKEND_CONTAINER_NAME,
  "bash",
  "-c",
  ACTUAL_TERM_IGNORING_GROUP_SCRIPT,
];

/**
 * 실제 Docker 증거: marker A·B 격리, wait-only process-zero, 설치된 handler를 통한 Backend 왕복.
 *
 * A는 production relay script에 끝나지 않은 요청을 보내 reader가 Backend 응답을 기다리게 한다. B는
 * TERM을 무시하는 descendant group이다. 두 owner는 공통 registry에 등록되므로 어느 assertion에서
 * 실패해도 여기의 cleanup과 afterEach의 재시도가 같은 owner를 정리한다.
 */
async function verifyActualRelayMarkerIsolationAndRoundTrip(): Promise<void> {
  await verifyRelayRuntime();
  const testId = currentRelayTestId;
  requireCondition(testId !== null, "The actual relay verification had no owning test.");
  const cleanups: (() => Promise<void>)[] = [];
  const hostOutcomes: Promise<unknown>[] = [];
  let primary: unknown = null;
  try {
    const observerPool = new RelayProcessPool();
    cleanups.push(async () => {
      observerPool.stopAll();
      await observerPool.waitForClose(RELAY_HOST_CLOSE_TIMEOUT_MS);
    });
    const observe = createDockerMarkerAudit(observerPool);
    const poolA = new RelayProcessPool();
    const ownerA = new RelayResourceOwner({
      token: randomUUID(),
      pool: poolA,
      audit: createDockerMarkerAudit(poolA),
      registry: relayCleanupRegistry,
      testId,
    });
    cleanups.push(() => ownerA.cleanup());
    const poolB = new RelayProcessPool();
    const ownerB = new RelayResourceOwner({
      token: randomUUID(),
      pool: poolB,
      audit: createDockerMarkerAudit(poolB),
      registry: relayCleanupRegistry,
      testId,
    });
    cleanups.push(() => ownerB.cleanup());

    const unfinished = buildRelayRequestBytes(
      relayCandidate("GET", `${BACKEND_ORIGIN}${INITIAL_CASE_TARGET}`),
    );
    requireCondition(
      unfinished.bytes.subarray(unfinished.bytes.byteLength - 4).toString("latin1") === "\r\n\r\n",
      "The unfinished relay request was not built from a complete request.",
    );
    hostOutcomes.push(
      observeOutcome(
        poolA.run(
          "actual-unfinished-relay",
          relayDockerArguments(ownerA.token),
          unfinished.bytes.subarray(0, unfinished.bytes.byteLength - 2),
          CONTAINER_AUDIT_HOST_TIMEOUT_MS,
        ),
      ),
    );
    hostOutcomes.push(
      observeOutcome(
        poolB.run(
          "actual-term-ignoring-group",
          ACTUAL_TERM_IGNORING_GROUP_ARGUMENTS,
          Buffer.from(`${ownerB.token}\n`, "ascii"),
          CONTAINER_AUDIT_HOST_TIMEOUT_MS,
        ),
      ),
    );
    requireCondition(
      (await observe(ownerA.token, "until-present", CONTAINER_AUDIT_POLL_SECONDS)) > 0,
      "The unfinished relay marker was not present in the container.",
    );
    requireCondition(
      (await observe(ownerB.token, "until-present", CONTAINER_AUDIT_POLL_SECONDS)) > 0,
      "The TERM-ignoring relay group was not present in the container.",
    );

    // A cleanup은 host CLI만 멈추고 container 안의 A가 GNU timeout으로 끝나기를 기다린다.
    await ownerA.cleanup();
    requireCondition(
      ownerA.state() === "clean" && poolA.openChildCount() === 0,
      "Marker A did not reach clean host and container process-zero.",
    );
    requireCondition(
      (await observe(ownerA.token, "until-zero", "0")) === 0,
      "Marker A remained after its clean state.",
    );
    requireCondition(
      (await observe(ownerB.token, "until-zero", "0")) > 0,
      "Cleaning marker A ended marker B or reported it as zero.",
    );
    // B는 TERM을 무시하므로 GNU timeout의 process group KILL 이후에만 0이 된다.
    await ownerB.cleanup();
    requireCondition(
      ownerB.state() === "clean" &&
        poolB.openChildCount() === 0 &&
        (await observe(ownerB.token, "until-zero", "0")) === 0,
      "Marker B did not reach process-zero after its TERM-ignoring group was killed.",
    );

    // 실제 installed handler를 통한 Backend 왕복. credential 없는 approved read는 실제 401이다.
    const page = new FakeBackendRelayPage();
    const relay = await installBackendRelay(page.asPage());
    cleanups.push(() => relay.dispose());
    const unauthenticated = new FakeBackendRoute(
      relayCandidate("GET", `${BACKEND_ORIGIN}${INITIAL_CASE_TARGET}`),
    );
    await page.invoke(unauthenticated.asRoute());
    requireCondition(
      unauthenticated.fulfillCount === 1 &&
        unauthenticated.abortCount === 0 &&
        unauthenticated.fulfilledStatus === 401 &&
        relay.length === 1 &&
        relay[0].status === 401 &&
        relay[0].target === INITIAL_CASE_TARGET &&
        relay.relayFailureCount() === 0,
      "The installed relay handler did not complete a real Backend 401 round trip.",
    );
    await relay.dispose();
    requireCondition(
      relay.cleanupState() === "clean" &&
        relay.openProcessCount() === 0 &&
        relay.activeHandlerCount() === 0 &&
        page.routeRemovalCount === 1,
      "The installed relay did not reach host and container process-zero.",
    );
    requireCondition(
      (await observe(null, "until-zero", "0")) === 0,
      "Backend relay markers remained after the actual verification.",
    );
  } catch (error: unknown) {
    primary = error;
  }
  const cleanupOutcomes = await Promise.allSettled(cleanups.map((cleanup) => cleanup()));
  await Promise.allSettled(hostOutcomes);
  const cleanupFailed = cleanupOutcomes.some(({ status }) => status === "rejected");
  if (primary !== null && cleanupFailed) {
    throw new AggregateError(
      [primary, new Error("The actual Backend relay cleanup failed.")],
      "The actual Backend relay verification and its cleanup failed.",
    );
  }
  if (primary !== null) {
    throw primary;
  }
  requireCondition(!cleanupFailed, "The actual Backend relay cleanup failed.");
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

test.beforeEach(async ({ page }, testInfo) => {
  // 이전 test의 relay가 process-zero를 확인하지 못했다면 같은 worker에서 새 test를 시작하지 않는다.
  // 새 worker는 relay 설치 전의 읽기 전용 suite marker audit가 같은 확인을 맡는다.
  requireCondition(
    relayCleanupRegistry.size === 0,
    "A previous test still owns Backend relay resources.",
  );
  currentRelayTestId = testInfo.testId;
  relayRouteStallReaders.length = 0;
  await installSessionPublicationProbe(page);
});

test.afterEach(async ({ page }, testInfo) => {
  void page;
  const owned = [...relayCleanupRegistry]
    .filter(([, testId]) => testId === testInfo.testId)
    .map(([owner]) => owner);
  // active는 첫 attempt, failed는 bounded 재시도이다. clean인 owner는 이미 registry에 없다.
  const outcomes = await Promise.allSettled(owned.map((owner) => owner.cleanup()));
  const stalls = relayRouteStallReaders.reduce((sum, read) => sum + read(), 0);
  relayRouteStallReaders.length = 0;
  currentRelayTestId = null;
  const failedStages = [
    ...new Set(
      outcomes.flatMap((outcome) =>
        outcome.status === "rejected"
          ? [outcome.reason instanceof RelayCleanupError ? outcome.reason.stage : "internal"]
          : [],
      ),
    ),
  ];
  requireCondition(
    failedStages.length === 0,
    `The Backend relay cleanup did not reach process-zero (${failedStages.join(",")}).`,
  );
  requireCondition(stalls === 0, "A Backend relay route action did not settle within its bound.");
  requireCondition(
    relayCleanupRegistry.size === 0,
    "The Backend relay registry retained cleanup owned by another test.",
  );
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
function relayCandidate(
  method: string,
  url: string,
  authorization = "",
  body: string | null = null,
): PlaywrightRequest {
  return {
    method: () => method,
    url: () => url,
    headers: () => (authorization === "" ? {} : { authorization }),
    postData: () => body,
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
 * `docker exec` is spawned and before `/dev/tcp` is opened. The two
 * counters are the evidence; a refusal that happened one statement later would
 * leave them moved.
 *
 * The messages are checked too. Every one of them is a fixed sentence from a
 * closed list: no address, no query name, no query value and no credential is
 * reflected back into a failure, so a run's output cannot become the place a
 * filter value is finally written down.
 */
test("the Backend relay refuses a write, a foreign filter and a non-canonical query", async () => {
  verifyRelayByteFramingAndParser();
  const unhandledRejections: unknown[] = [];
  const onUnhandledRejection = (reason: unknown): void => {
    unhandledRejections.push(reason);
  };
  process.on("unhandledRejection", onUnhandledRejection);
  try {
    await verifyParallelStartBarrierLifecycle();
    await verifyRelayProcessPoolLifecycle();
    await verifyContainerMarkerAuditContract();
    await verifyRelayCleanupOwnerLifecycle();
    await verifyBackendRelayRouteLifecycle();
    await test.step(
      "actual marker isolation and relay round trip",
      verifyActualRelayMarkerIsolationAndRoundTrip,
    );
    await flushRelayTasks();
  } finally {
    process.off("unhandledRejection", onUnhandledRejection);
  }
  requireCondition(unhandledRejections.length === 0, "A relay promise rejection was unhandled.");
  requireCondition(REFUSED_RELAY_REQUESTS.length === 64, "The relay query-refusal matrix drifted.");
  requireUniqueRelayDeclarations(REFUSED_RELAY_REQUESTS, "relay query-refusal");
  const spawnsBefore = relaySpawnCount;
  const observationsBefore = relayObservationCount;

  for (const refused of REFUSED_RELAY_REQUESTS) {
    let message: string | null = null;
    try {
      void relayToBackend(relayCandidate(refused.method, refused.url));
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
 * `docker exec` is spawned and before `/dev/tcp` is opened, and the two
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
  requireCondition(
    RELAYABLE_READ_PATHS.length === 6 &&
      new Set(RELAYABLE_READ_PATHS.map(({ name }) => name)).size === 6,
    "The six read relay descriptors were not unique.",
  );
  requireCondition(
    RELAYABLE_WRITE_PROBES.length === 1 &&
      RELAYABLE_WRITE_PROBES[0].method === "POST" &&
      RELAYABLE_WRITE_PROBES[0].name === "case-resolution-probe",
    "The one write-probe descriptor drifted.",
  );
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
    let resolveTokenMutation: () => void = () => undefined;
    let rejectTokenMutation: (error: Error) => void = () => undefined;
    const tokenMutationCompleted = new Promise<void>((resolvePromise, rejectPromise) => {
      resolveTokenMutation = resolvePromise;
      rejectTokenMutation = rejectPromise;
    });
    // Observe the rejection at creation time. The awaited original promise below
    // still fails the test, while a route failure that races with the click can
    // never become an unhandled rejection.
    void tokenMutationCompleted.catch(() => undefined);
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
      try {
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
        resolveTokenMutation();
      } catch {
        const safeError = new Error("The ID token mutation route failed.");
        rejectTokenMutation(safeError);
        throw safeError;
      }
    });

    await beginLogin(page, password);
    await submitLogin(page);
    // `click()` may finish once callback navigation commits while the async
    // token route is still forwarding. Waiting on the concrete route event (not
    // a longer assertion timeout) keeps the existing five-second UI deadline
    // focused on application settlement after the mutated response is delivered.
    await tokenMutationCompleted;
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

const NOTES_GEOMETRY_VIEWPORTS: readonly { readonly width: number; readonly height: number }[] = [
  ...CONSOLE_VIEWPORTS,
  { width: 390, height: 844 },
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
  const appliedResponse = page.waitForResponse(
    (response) =>
      response.url() === `${BACKEND_ORIGIN}${APPLIED_TRANSACTION_TARGET}` &&
      response.request().method() === "GET",
    { timeout: BACKEND_OBSERVATION_WAIT_TIMEOUT_MS },
  );
  await page.getByRole("button", { name: "Apply filters" }).click();
  await appliedResponse;
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
  await expect
    .poll(() => detailRequests().length, {
      timeout: BACKEND_OBSERVATION_WAIT_TIMEOUT_MS,
    })
    .toBe(1);
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
 * the transaction test above. No API or authentication test double is used,
 * so what the screen shows is what Spring Boot answered after real login.
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
  const appliedResponse = page.waitForResponse(
    (response) =>
      response.url() === `${BACKEND_ORIGIN}${APPLIED_CASE_TARGET}` &&
      response.request().method() === "GET",
    { timeout: BACKEND_OBSERVATION_WAIT_TIMEOUT_MS },
  );
  await page.getByRole("button", { name: "Apply filters" }).click();
  await appliedResponse;
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
  // relay 자원은 공통 registry가 소유한다. 중간 assertion이 실패해도 afterEach가 같은 owner를 정리하고
  // 두 실패를 모두 보고하므로 finally로 원래 오류를 덮지 않는다.
  const backend = await installBackendRelay(page, {
    captureBodyOf: [CASE_DETAIL_TARGET, CASE_NOTES_TARGET, CASE_AUDIT_TARGET],
    parallelStartBarrier,
    deliverLast: CASE_DETAIL_TARGET,
  });
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
  // barrier timer와 no-arrival watchdog 모두 로그인 제출 전에는 시작하지 않는다. relay deadline은
  // route마다 도착 시점부터 따로 센다.
  const beforeSubmission = parallelStartBarrier.snapshot();
  requireCondition(
    beforeSubmission.state === "pending" &&
      beforeSubmission.activeTimerCount === 0 &&
      beforeSubmission.activeArrivalWatchdogTimerCount === 0,
    "The parallel-start barrier started before login submission.",
  );
  const loginSubmission = submitLogin(page);
  const tokenResponse = await tokenResponsePromise;
  await loginSubmission;
  // 로그인 완료 뒤에는 barrier timer가 아니라 별도 no-arrival watchdog만 arm한다. barrier timer는 첫
  // exact route가 도착할 때만 시작되고 그때 watchdog은 해제된다. 이미 route가 도착했거나 barrier가
  // 끝났다면 arm은 아무 일도 하지 않는다.
  parallelStartBarrier.armArrivalWatchdog(PARALLEL_START_ARRIVAL_WATCHDOG_MS);
  const afterLogin = parallelStartBarrier.snapshot();
  requireCondition(
    afterLogin.activeTimerCount === 0 || afterLogin.arrivedTargetCount > 0,
    "The parallel-start barrier started before its first exact route.",
  );
  const tokens = parseTokenResponse(await tokenResponse.json());
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
  const caseNotFoundAlert = page.getByRole("alert").filter({ hasText: "Case not found" });
  await expect(caseNotFoundAlert).toBeVisible({
    timeout: BACKEND_OBSERVATION_WAIT_TIMEOUT_MS,
  });
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
      releasedBarrier.arrivalWatchdogCallbackCount === 0 &&
      releasedBarrier.pendingWaiterCount === 0 &&
      releasedBarrier.activeTimerCount === 0 &&
      releasedBarrier.activeArrivalWatchdogTimerCount === 0 &&
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
  // 404 확정 직후: Case workflow DOM이 없어야 한다. 이 시점의 Backend 관찰 수를 기록해 뒤의 재검사와 비교한다.
  await requireNoCaseWorkflowDom(page);
  const observationsAtNotFound = backend.length;
  // 응답이 화면에 나타난 뒤에도 Playwright의 fulfill Promise가 늦게 끝날 수 있다. 증거 대기 상한 안에서
  // handler 종결을 기다린 뒤 순서를 읽으며, production이나 브라우저 deadline은 늘리지 않는다.
  await expect
    .poll(() => backend.activeHandlerCount(), { timeout: BACKEND_OBSERVATION_WAIT_TIMEOUT_MS })
    .toBe(0);
  const initialTargets = new Set([
    CASE_DETAIL_TARGET,
    INITIAL_CASE_NOTES_TARGET,
    INITIAL_CASE_AUDIT_TARGET,
  ]);
  const initialEvents = backend
    .processEvents()
    .filter(({ label }) => initialTargets.has(label));
  const initialStarts = initialEvents.filter(({ kind }) => kind === "spawn");
  const firstInitialClose = initialEvents.find(({ kind }) => kind === "close");
  requireCondition(
    initialStarts.length === 3 &&
      new Set(initialStarts.map(({ label }) => label)).size === 3 &&
      firstInitialClose !== undefined &&
      initialStarts.every(({ sequence }) => sequence < firstInitialClose.sequence),
    "The three case reads did not start as concurrent relay processes before the first close.",
  );
  const parallelFulfillmentOrder = backend.parallelFulfillmentOrder();
  requireCondition(
    parallelFulfillmentOrder.length === 3 &&
      new Set(parallelFulfillmentOrder).size === 3 &&
      parallelFulfillmentOrder[2] === CASE_DETAIL_TARGET &&
      parallelFulfillmentOrder.every((target) => initialTargets.has(target)),
    "The three real Backend responses were not delivered once with detail last.",
  );
  requireCondition(
    backend.relayFailureCount() === 0 &&
      backend.routeAbortCount() === 0 &&
      backend.routeActionFailureCount() === 0 &&
      backend.routeActionStallCount() === 0,
    "A successful case relay failed, aborted, or did not settle its route action.",
  );

  // The fixed not-found screen, and not one field of a record.
  await expect(caseNotFoundAlert).toContainText("Case not found");
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
  requireCondition(
    !screenText.toLowerCase().includes("timed out") &&
      !screenText.toLowerCase().includes("timeout"),
    "The real Backend 404 was replaced by a timeout alert.",
  );

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
  // late-settlement 관찰 뒤에도 workflow DOM은 0개이고, 두 검사 사이 Backend 요청은 하나도 늘지 않았다.
  await requireNoCaseWorkflowDom(page);
  requireCondition(
    backend.length === observationsAtNotFound &&
      backend.filter((entry) => entry.method !== "GET").length === 0,
    "The not-found case screen made another Backend request between the workflow DOM checks.",
  );

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
  // This real 404 never renders the note composer; workflow, reassignment and
  // resolution controls remain unavailable as well.
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
  const returnedResults = page.getByRole("main").getByRole("status");
  await expect(returnedResults).not.toContainText("Loading cases", {
    timeout: BACKEND_OBSERVATION_WAIT_TIMEOUT_MS,
  });
  await expect(page.getByRole("alert")).toHaveCount(0);
  await expect
    .poll(() => backend.activeHandlerCount(), {
      timeout: BACKEND_OBSERVATION_WAIT_TIMEOUT_MS,
    })
    .toBe(0);
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
  const relayStarts = backend
    .processEvents()
    .filter(({ kind, label }) => kind === "spawn" && label !== CONTAINER_MARKER_AUDIT_LABEL);
  for (const target of [
    CASE_DETAIL_TARGET,
    INITIAL_CASE_NOTES_TARGET,
    INITIAL_CASE_AUDIT_TARGET,
    INITIAL_CASE_TARGET,
  ]) {
    requireCondition(
      relayStarts.filter(({ label }) => label === target).length === 1,
      "A case relay target did not start exactly one relay process.",
    );
  }
  requireCondition(
    relayStarts.length === 4 &&
      backend.relayFailureCount() === 0 &&
      backend.routeAbortCount() === 0 &&
      backend.routeActionFailureCount() === 0 &&
      backend.routeActionStallCount() === 0,
    "An unexpected Backend relay started, failed or did not settle before teardown.",
  );

  // 테스트 본문이 teardown 완료를 직접 기다려 host child와 container marker의 process-zero를 확인한다.
  // 실패하면 이 오류와 afterEach의 재시도 결과가 함께 보고되며, 앞선 assertion 오류를 덮지 않는다.
  await backend.dispose();
  requireCondition(
    backend.cleanupState() === "clean" &&
      backend.openProcessCount() === 0 &&
      backend.activeHandlerCount() === 0,
    "The case relay did not reach host and container process-zero.",
  );
  requireCondition(
    backend
      .processEvents()
      .filter(({ kind, label }) => kind === "spawn" && label !== CONTAINER_MARKER_AUDIT_LABEL)
      .length === 4,
    "Teardown admitted another Backend relay request.",
  );
  const disposedBarrier = parallelStartBarrier.snapshot();
  requireCondition(
    disposedBarrier.state === "disposed" &&
      disposedBarrier.expectedTargetCount === 0 &&
      disposedBarrier.arrivedTargetCount === 0 &&
      disposedBarrier.pendingWaiterCount === 0 &&
      disposedBarrier.activeTimerCount === 0 &&
      disposedBarrier.activeCallbackCount === 0,
    "The parallel-start barrier retained cleanup work.",
  );
});

/**
 * 실제 404 화면에 Case workflow DOM이 하나도 없는지 production accessible name으로 확인한다.
 *
 * class 이름이나 test 전용 data 속성에 기대지 않고 대표 요소를 role과 exact name으로 찾는다. 대기나
 * timeout 없이 호출 시점의 개수만 읽으며, 실패 문구는 고정 label만 담는다.
 */
async function requireNoCaseWorkflowDom(page: Page): Promise<void> {
  const workflowElements = [
    { label: "heading", locator: page.getByRole("heading", { name: "Case workflow", exact: true }) },
    { label: "review status group", locator: page.getByRole("group", { name: "Review status", exact: true }) },
    { label: "assignee group", locator: page.getByRole("group", { name: "Assignee", exact: true }) },
    { label: "start review group", locator: page.getByRole("group", { name: "Start review", exact: true }) },
    {
      label: "additional information action",
      locator: page.getByRole("button", { name: "Request additional information", exact: true }),
    },
    { label: "resume review action", locator: page.getByRole("button", { name: "Resume review", exact: true }) },
    { label: "start review action", locator: page.getByRole("button", { name: "Start review", exact: true }) },
    { label: "assignee UUID textbox", locator: page.getByRole("textbox", { name: "Assignee UUID", exact: true }) },
    { label: "assign action", locator: page.getByRole("button", { name: "Assign analyst", exact: true }) },
    { label: "change assignee action", locator: page.getByRole("button", { name: "Change assignee", exact: true }) },
    { label: "release assignee action", locator: page.getByRole("button", { name: "Release assignee", exact: true }) },
    {
      label: "refresh control",
      locator: page.getByRole("button", { name: "Refresh workflow information", exact: true }),
    },
    { label: "result live region", locator: page.getByRole("status", { name: "Case workflow result", exact: true }) },
  ];
  for (const { label, locator } of workflowElements) {
    requireCondition(
      (await locator.count()) === 0,
      `The not-found case screen rendered the workflow ${label}.`,
    );
  }
}

/**
 * The address of the test-only geometry fixture, served by the same Vite dev
 * server that serves the application.
 *
 * A real origin and real module graphs: the pages import production components
 * and `app.css`, and Vite transforms and serves them as it does for the console.
 * The notes fixture injects a synthetic FDS_ANALYST AuthClient/session so the
 * production workflow section and capability-gated composer can be measured.
 * It has no credential, token or Keycloak login and is not authentication or
 * authorization evidence; those boundaries are covered by unit, router and
 * real Keycloak integration tests. None of the geometry fixtures makes an API
 * request.
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
  await expect(page.getByRole("heading", { name: "Case workflow", level: 3 })).toBeVisible();
  await expect(page.getByRole("button", { name: "Resume review" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Change assignee" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Release assignee" })).toBeVisible();
  const displayedWorkflowStatus = page
    .locator("section.case-workflow > p.case-workflow__summary > strong")
    .filter({ hasText: /^Information required$/ });
  await expect(displayedWorkflowStatus).toHaveCount(1);
  await expect(displayedWorkflowStatus).toBeVisible();
  await expect(displayedWorkflowStatus).toHaveText("Information required");
  const workflowAssignee = page.locator(".case-workflow__assignee code");
  // Deliberately independent from the fixture source: this literal is not
  // imported or shared, so a fixture edit cannot silently rewrite the oracle.
  const expectedWorkflowAssignee = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
  await expect(workflowAssignee).toHaveText(expectedWorkflowAssignee);
  const renderedAssigneeEvidence = await workflowAssignee.evaluate((element) => {
    const text = element.textContent ?? "";
    return {
      text,
      codePointLength: Array.from(text).length,
      asciiOnly: Array.from(text).every((character) => character.codePointAt(0)! <= 0x7f),
    };
  });
  requireCondition(
    renderedAssigneeEvidence.text === expectedWorkflowAssignee &&
      renderedAssigneeEvidence.codePointLength === 36 &&
      renderedAssigneeEvidence.asciiOnly &&
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(
        renderedAssigneeEvidence.text,
      ),
    "The workflow DOM did not contain the independently expected 36-code-point ASCII UUID v4.",
  );
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

  for (const viewport of NOTES_GEOMETRY_VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    requireCondition(
      !(await documentOverflowsHorizontally(page)),
      `The populated investigation notes scrolled the document at ${String(viewport.width)}px.`,
    );
    const workflowGeometry = await page.locator(".case-workflow").evaluate((element) => {
      const panel = element.getBoundingClientRect();
      const main = element.closest("main")?.getBoundingClientRect() ?? null;
      const assignee = element.querySelector<HTMLElement>(".case-workflow__assignee > code");
      const assigneeBox = assignee?.getBoundingClientRect() ?? null;
      const assigneeParent = assignee?.parentElement ?? null;
      const assigneeParentBox = assigneeParent?.getBoundingClientRect() ?? null;
      const assigneeStyle = assignee === null ? null : window.getComputedStyle(assignee);
      const assigneeParentStyle =
        assigneeParent === null ? null : window.getComputedStyle(assigneeParent);
      const statusSummary = element.querySelector<HTMLElement>(".case-workflow__summary");
      const statusLabel = statusSummary?.querySelector<HTMLElement>(":scope > strong") ?? null;
      const statusLabelBox = statusLabel?.getBoundingClientRect() ?? null;
      const statusStyle = statusLabel === null ? null : window.getComputedStyle(statusLabel);
      const statusParentStyle =
        statusSummary === null ? null : window.getComputedStyle(statusSummary);
      const statusRange = document.createRange();
      if (statusLabel !== null) {
        statusRange.selectNodeContents(statusLabel);
      }
      const statusLineBoxes =
        statusLabel === null
          ? []
          : Array.from(statusRange.getClientRects())
              .filter((box) => box.width > 0 && box.height > 0)
              .map((box) => ({
                left: box.left,
                right: box.right,
                top: box.top,
                bottom: box.bottom,
              }));
      const isDisplayed = (target: HTMLElement, box: DOMRect): boolean => {
        const style = window.getComputedStyle(target);
        return (
          !target.hidden &&
          style.display !== "none" &&
          style.visibility !== "hidden" &&
          style.visibility !== "collapse" &&
          box.width > 0 &&
          box.height > 0 &&
          target.getClientRects().length > 0
        );
      };
      const controls = Array.from(element.querySelectorAll("button, input")).map((control) => {
        const box = control.getBoundingClientRect();
        const group = control.closest("fieldset")?.getBoundingClientRect() ?? null;
        const style = window.getComputedStyle(control);
        return {
          tag: control.tagName,
          text: control.textContent ?? "",
          left: box.left,
          right: box.right,
          top: box.top,
          bottom: box.bottom,
          width: box.width,
          groupWidth: group?.width ?? 0,
          whiteSpace: style.whiteSpace,
          overflowWrap: style.overflowWrap,
        };
      });
      return {
        panel: { left: panel.left, right: panel.right },
        main: main === null ? null : { left: main.left, right: main.right },
        viewportWidth: window.innerWidth,
        assignee:
          assignee === null ||
          assigneeBox === null ||
          assigneeParent === null ||
          assigneeParentBox === null ||
          assigneeStyle === null ||
          assigneeParentStyle === null
            ? null
            : {
                text: assignee.textContent ?? "",
                tag: assignee.tagName,
                displayed: isDisplayed(assignee, assigneeBox),
                left: assigneeBox.left,
                right: assigneeBox.right,
                width: assigneeBox.width,
                scrollWidth: assignee.scrollWidth,
                clientWidth: assignee.clientWidth,
                overflowWrap: assigneeStyle.overflowWrap,
                wordBreak: assigneeStyle.wordBreak,
                parent: {
                  tag: assigneeParent.tagName,
                  isDirectAssigneeContainer:
                    assignee.parentElement === assigneeParent &&
                    assigneeParent.matches("p.case-workflow__assignee"),
                  displayed: isDisplayed(assigneeParent, assigneeParentBox),
                  left: assigneeParentBox.left,
                  right: assigneeParentBox.right,
                  width: assigneeParentBox.width,
                  borderLeftWidth: parseFloat(assigneeParentStyle.borderLeftWidth),
                  borderRightWidth: parseFloat(assigneeParentStyle.borderRightWidth),
                  paddingLeft: parseFloat(assigneeParentStyle.paddingLeft),
                  paddingRight: parseFloat(assigneeParentStyle.paddingRight),
                  contentLeft:
                    assigneeParentBox.left +
                    parseFloat(assigneeParentStyle.borderLeftWidth) +
                    parseFloat(assigneeParentStyle.paddingLeft),
                  contentRight:
                    assigneeParentBox.right -
                    parseFloat(assigneeParentStyle.borderRightWidth) -
                    parseFloat(assigneeParentStyle.paddingRight),
                  contentWidth:
                    assigneeParentBox.width -
                    parseFloat(assigneeParentStyle.borderLeftWidth) -
                    parseFloat(assigneeParentStyle.borderRightWidth) -
                    parseFloat(assigneeParentStyle.paddingLeft) -
                    parseFloat(assigneeParentStyle.paddingRight),
                  scrollWidth: assigneeParent.scrollWidth,
                  clientWidth: assigneeParent.clientWidth,
                },
              },
        status:
          statusSummary === null ||
          statusLabel === null ||
          statusLabelBox === null ||
          statusStyle === null ||
          statusParentStyle === null
            ? null
            : {
                text: statusLabel.textContent ?? "",
                tag: statusLabel.tagName,
                displayed: isDisplayed(statusLabel, statusLabelBox),
                whiteSpace: statusStyle.whiteSpace,
                overflowWrap: statusStyle.overflowWrap,
                wordBreak: statusStyle.wordBreak,
                scrollWidth: statusLabel.scrollWidth,
                clientWidth: statusLabel.clientWidth,
                parent: {
                  tag: statusSummary.tagName,
                  isDirectSummary:
                    statusLabel.parentElement === statusSummary &&
                    statusSummary.matches("p.case-workflow__summary"),
                  displayed: isDisplayed(statusSummary, statusSummary.getBoundingClientRect()),
                  contentLeft:
                    statusSummary.getBoundingClientRect().left +
                    parseFloat(statusParentStyle.borderLeftWidth) +
                    parseFloat(statusParentStyle.paddingLeft),
                  contentRight:
                    statusSummary.getBoundingClientRect().right -
                    parseFloat(statusParentStyle.borderRightWidth) -
                    parseFloat(statusParentStyle.paddingRight),
                  scrollWidth: statusSummary.scrollWidth,
                  clientWidth: statusSummary.clientWidth,
                },
                lineBoxes: statusLineBoxes,
              },
        controls,
      };
    });
    requireCondition(workflowGeometry.main !== null, "The workflow fixture had no main region.");
    requireCondition(
      workflowGeometry.panel.left >= workflowGeometry.main.left - 1 &&
        workflowGeometry.panel.right <= workflowGeometry.main.right + 1 &&
        workflowGeometry.panel.left >= -1 &&
        workflowGeometry.panel.right <= workflowGeometry.viewportWidth + 1,
      `The workflow section escaped its container at ${String(viewport.width)}px.`,
    );
    requireCondition(workflowGeometry.assignee !== null, "The workflow assignee UUID was absent.");
    const workflowGeometryTolerance = 1;
    requireCondition(
      workflowGeometry.assignee.text === expectedWorkflowAssignee &&
        workflowGeometry.assignee.tag === "CODE" &&
        workflowGeometry.assignee.displayed &&
        workflowGeometry.assignee.parent.tag === "P" &&
        workflowGeometry.assignee.parent.isDirectAssigneeContainer &&
        workflowGeometry.assignee.parent.displayed &&
        workflowGeometry.assignee.left >=
          workflowGeometry.assignee.parent.contentLeft - workflowGeometryTolerance &&
        workflowGeometry.assignee.right <=
          workflowGeometry.assignee.parent.contentRight + workflowGeometryTolerance &&
        workflowGeometry.assignee.width <=
          workflowGeometry.assignee.parent.contentWidth + workflowGeometryTolerance &&
        workflowGeometry.assignee.scrollWidth <=
          workflowGeometry.assignee.clientWidth + workflowGeometryTolerance &&
        workflowGeometry.assignee.parent.scrollWidth <=
          workflowGeometry.assignee.parent.clientWidth + workflowGeometryTolerance &&
        workflowGeometry.assignee.overflowWrap === "anywhere" &&
        workflowGeometry.assignee.wordBreak === "break-word",
      `The workflow UUID did not remain inside its direct parent content box at ${String(viewport.width)}px.`,
    );
    // closure 안에서도 null 판정이 유지되도록 검증한 값을 local constant로 고정한다.
    const workflowStatus = workflowGeometry.status;
    requireCondition(workflowStatus !== null, "The workflow status label was absent.");
    requireCondition(
      workflowStatus.text === "Information required" &&
        workflowStatus.tag === "STRONG" &&
        workflowStatus.displayed &&
        workflowStatus.parent.tag === "P" &&
        workflowStatus.parent.isDirectSummary &&
        workflowStatus.parent.displayed &&
        workflowStatus.whiteSpace === "normal" &&
        workflowStatus.overflowWrap === "anywhere" &&
        workflowStatus.scrollWidth <=
          workflowStatus.clientWidth + workflowGeometryTolerance &&
        workflowStatus.parent.scrollWidth <=
          workflowStatus.parent.clientWidth + workflowGeometryTolerance &&
        workflowStatus.lineBoxes.length > 0 &&
        workflowStatus.lineBoxes.every(
          (box) =>
            box.left >=
              workflowStatus.parent.contentLeft - workflowGeometryTolerance &&
            box.right <=
              workflowStatus.parent.contentRight + workflowGeometryTolerance &&
            box.left >= -workflowGeometryTolerance &&
            box.right <= workflowGeometry.viewportWidth + workflowGeometryTolerance,
        ),
      `The longest production status label did not remain inside its direct parent content box at ${String(viewport.width)}px.`,
    );
    requireCondition(
      workflowGeometry.controls.length === 4,
      `The workflow fixture did not render three actions and one UUID input at ${String(viewport.width)}px.`,
    );
    for (const control of workflowGeometry.controls) {
      requireCondition(
        control.left >= workflowGeometry.panel.left - 1 &&
          control.right <= workflowGeometry.panel.right + 1,
        `A workflow control escaped its panel at ${String(viewport.width)}px.`,
      );
      if (control.tag === "BUTTON") {
        requireCondition(
          control.whiteSpace === "normal" && control.overflowWrap === "anywhere",
          `A workflow action label could not wrap at ${String(viewport.width)}px.`,
        );
      }
    }
    for (let left = 0; left < workflowGeometry.controls.length; left += 1) {
      for (let right = left + 1; right < workflowGeometry.controls.length; right += 1) {
        const first = workflowGeometry.controls[left];
        const second = workflowGeometry.controls[right];
        const overlaps =
          first.left < second.right - 0.5 &&
          first.right > second.left + 0.5 &&
          first.top < second.bottom - 0.5 &&
          first.bottom > second.top + 0.5;
        requireCondition(
          !overlaps,
          `Workflow controls overlapped at ${String(viewport.width)}px.`,
        );
      }
    }
    const composer = page.locator(".investigation-note-composer");
    const textarea = page.getByRole("textbox", { name: "Investigation note" });
    await expect(composer).toBeVisible();
    await expect(textarea).toBeVisible();
    const measured = await textarea.evaluate((element) => {
      const style = window.getComputedStyle(element);
      const box = element.getBoundingClientRect();
      const parent = element.parentElement;
      if (parent === null) {
        throw new Error("The investigation note textarea has no containing form.");
      }
      const parentStyle = window.getComputedStyle(parent);
      const parentBox = parent.getBoundingClientRect();
      const parentContentWidth =
        parentBox.width -
        parseFloat(parentStyle.paddingLeft) -
        parseFloat(parentStyle.paddingRight) -
        parseFloat(parentStyle.borderLeftWidth) -
        parseFloat(parentStyle.borderRightWidth);
      return {
        left: box.left,
        right: box.right,
        width: box.width,
        computedWidth: parseFloat(style.width),
        boxSizing: style.boxSizing,
        parentContentWidth,
        viewportWidth: window.innerWidth,
        resize: style.resize,
        minWidth: style.minWidth,
      };
    });
    requireCondition(
      measured.left >= -1 && measured.right <= measured.viewportWidth + 1,
      `The investigation note textarea escaped the viewport at ${String(viewport.width)}px.`,
    );
    const widthTolerance = 1.5;
    requireCondition(
      measured.boxSizing === "border-box",
      "The investigation note textarea did not use border-box sizing.",
    );
    requireCondition(
      measured.width <= measured.parentContentWidth + widthTolerance,
      `The investigation note textarea exceeded its containing content box at ${String(viewport.width)}px.`,
    );
    requireCondition(
      Math.abs(measured.width - measured.parentContentWidth) <= widthTolerance &&
        Math.abs(measured.computedWidth - measured.width) <= widthTolerance,
      `The investigation note textarea did not render at 100% of its containing content width at ${String(viewport.width)}px.`,
    );
    requireCondition(measured.resize === "vertical", "The investigation note textarea was not vertical-resize only.");
    requireCondition(measured.minWidth === "0px", "The investigation note textarea did not keep min-width zero.");

    if (viewport.width === 390 && viewport.height === 844) {
      for (const control of workflowGeometry.controls.filter(({ tag }) => tag === "BUTTON")) {
        requireCondition(
          Math.abs(control.width - control.groupWidth) <= 1.5,
          "A mobile workflow action did not render at its fieldset width.",
        );
      }
      const actions = await page.locator(".investigation-note-composer__actions").evaluate((element) => {
        const style = window.getComputedStyle(element);
        const box = element.getBoundingClientRect();
        const buttons = Array.from(element.querySelectorAll("button")).map((button) => {
          const buttonBox = button.getBoundingClientRect();
          return {
            left: buttonBox.left,
            right: buttonBox.right,
            top: buttonBox.top,
            bottom: buttonBox.bottom,
            width: buttonBox.width,
          };
        });
        return { flexDirection: style.flexDirection, box: { left: box.left, right: box.right, width: box.width }, buttons };
      });
      requireCondition(actions.flexDirection === "column", "Mobile composer actions were not laid out as a column.");
      requireCondition(actions.buttons.length === 2, "Mobile composer did not render both actions.");
      const [cancel, submit] = actions.buttons;
      const actionTolerance = 1.5;
      requireCondition(
        cancel.bottom <= submit.top + actionTolerance,
        "Mobile Cancel and Add note actions overlapped instead of stacking vertically.",
      );
      for (const button of actions.buttons) {
        requireCondition(
          button.left >= actions.box.left - actionTolerance &&
            button.right <= actions.box.right + actionTolerance,
          "A mobile composer action escaped its container.",
        );
        requireCondition(
          Math.abs(button.width - actions.box.width) <= actionTolerance,
          "A mobile composer action did not render full width.",
        );
      }
    }
  }

  // The four design widths above prove the production console contract. This
  // additional narrow layout makes wrapping observable rather than merely
  // permitted: the same production summary and stylesheet must occupy at least
  // two real line boxes without widening the document.
  await page.setViewportSize({ width: 280, height: 844 });
  const narrowStatusEvidence = await displayedWorkflowStatus.evaluate((element) => {
    // Playwright는 element를 HTMLElement | SVGElement로 넘긴다. production label이 HTMLElement가 아니면
    // 측정하지 않고 null을 돌려 아래 필수 조건에서 fail-closed한다.
    if (!(element instanceof HTMLElement)) {
      return null;
    }
    const lineGroupingTolerance = 0.5;
    const range = document.createRange();
    range.selectNodeContents(element);
    const lineRects = Array.from(range.getClientRects())
      .filter((rect) => rect.width > 0 && rect.height > 0)
      .map((rect) => ({
        left: rect.left,
        right: rect.right,
        top: rect.top,
        bottom: rect.bottom,
      }));
    const uniqueLines: { top: number; bottom: number }[] = [];
    for (const rect of lineRects) {
      const isKnownLine = uniqueLines.some(
        (line) =>
          Math.abs(line.top - rect.top) <= lineGroupingTolerance ||
          Math.abs(line.bottom - rect.bottom) <= lineGroupingTolerance,
      );
      if (!isKnownLine) {
        uniqueLines.push({ top: rect.top, bottom: rect.bottom });
      }
    }
    const box = element.getBoundingClientRect();
    const parent = element.parentElement;
    if (parent === null) {
      return null;
    }
    const parentBox = parent.getBoundingClientRect();
    const parentStyle = window.getComputedStyle(parent);
    const labelStyle = window.getComputedStyle(element);
    const isDisplayed = (target: HTMLElement, targetBox: DOMRect): boolean => {
      const style = window.getComputedStyle(target);
      return (
        !target.hidden &&
        style.display !== "none" &&
        style.visibility !== "hidden" &&
        style.visibility !== "collapse" &&
        targetBox.width > 0 &&
        targetBox.height > 0 &&
        target.getClientRects().length > 0
      );
    };
    const borderLeftWidth = parseFloat(parentStyle.borderLeftWidth);
    const borderRightWidth = parseFloat(parentStyle.borderRightWidth);
    const paddingLeft = parseFloat(parentStyle.paddingLeft);
    const paddingRight = parseFloat(parentStyle.paddingRight);
    const contentLeft = parentBox.left + borderLeftWidth + paddingLeft;
    const contentRight = parentBox.right - borderRightWidth - paddingRight;
    return {
      text: element.textContent ?? "",
      tag: element.tagName,
      isProductionStatusLabel:
        element.parentElement === parent &&
        parent.matches("p.case-workflow__summary") &&
        element.closest("section.case-workflow") !== null,
      displayed: isDisplayed(element, box),
      parentDisplayed: isDisplayed(parent, parentBox),
      lineCount: uniqueLines.length,
      lineRects,
      labelScrollWidth: element.scrollWidth,
      labelClientWidth: element.clientWidth,
      parentScrollWidth: parent.scrollWidth,
      parentClientWidth: parent.clientWidth,
      left: box.left,
      right: box.right,
      overflowWrap: labelStyle.overflowWrap,
      parent: {
        tag: parent.tagName,
        left: parentBox.left,
        right: parentBox.right,
        width: parentBox.width,
        borderLeftWidth,
        borderRightWidth,
        paddingLeft,
        paddingRight,
        contentLeft,
        contentRight,
        contentWidth: contentRight - contentLeft,
      },
      viewportWidth: window.innerWidth,
    };
  });
  requireCondition(narrowStatusEvidence !== null, "The narrow workflow status summary was absent.");
  const narrowGeometryTolerance = 1;
  requireCondition(
    narrowStatusEvidence.text === "Information required" &&
      narrowStatusEvidence.tag === "STRONG" &&
      narrowStatusEvidence.parent.tag === "P" &&
      narrowStatusEvidence.isProductionStatusLabel &&
      narrowStatusEvidence.displayed &&
      narrowStatusEvidence.parentDisplayed &&
      narrowStatusEvidence.lineCount >= 2 &&
      narrowStatusEvidence.lineRects.length >= narrowStatusEvidence.lineCount &&
      narrowStatusEvidence.lineRects.every(
        (rect) =>
          rect.left >= narrowStatusEvidence.parent.contentLeft - narrowGeometryTolerance &&
          rect.right <= narrowStatusEvidence.parent.contentRight + narrowGeometryTolerance &&
          rect.left >= -narrowGeometryTolerance &&
          rect.right <= narrowStatusEvidence.viewportWidth + narrowGeometryTolerance,
      ) &&
      narrowStatusEvidence.labelScrollWidth <=
        narrowStatusEvidence.labelClientWidth + narrowGeometryTolerance &&
      narrowStatusEvidence.parentScrollWidth <=
        narrowStatusEvidence.parentClientWidth + narrowGeometryTolerance &&
      narrowStatusEvidence.left >=
        narrowStatusEvidence.parent.contentLeft - narrowGeometryTolerance &&
      narrowStatusEvidence.right <=
        narrowStatusEvidence.parent.contentRight + narrowGeometryTolerance &&
      narrowStatusEvidence.overflowWrap === "anywhere" &&
      !(await documentOverflowsHorizontally(page)),
    "The production status label itself did not produce contained multi-line wrapping at 280px.",
  );
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
