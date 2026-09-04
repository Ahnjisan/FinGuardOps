export class EnvConfigError extends Error {
  constructor() {
    super("Application environment configuration is invalid.");
    this.name = "EnvConfigError";
  }
}

export interface AppEnv {
  readonly apiBaseUrl: string;
}

export interface AuthEnv {
  readonly oidcAuthority: string;
  readonly oidcClientId: string;
}

/**
 * ASCII control characters, rejected before any URL parsing or normalization.
 * Checked by code point rather than by a regular expression, so neither the
 * characters nor their escapes have to appear in a pattern here.
 */
function hasControlCharacter(value: string): boolean {
  for (const character of value) {
    const codePoint = character.codePointAt(0) ?? 0;
    if (codePoint <= 0x1f || codePoint === 0x7f) {
      return true;
    }
  }
  return false;
}

/**
 * The only hostnames allowed to use plain http, and only outside production.
 * `new URL("http://[::1]:8081").hostname` keeps the brackets, so the IPv6
 * loopback is compared in its bracketed form.
 */
const LOOPBACK_HOSTNAMES = new Set(["localhost", "127.0.0.1", "[::1]"]);

/**
 * Detects a userinfo delimiter in the URL authority component only. A "@" in
 * the issuer path (e.g. `https://as.example/realms/@tenant`) is legitimate and
 * must not be rejected, so the raw string cannot simply be searched for "@".
 */
function hasUserInfoDelimiter(rawValue: string): boolean {
  const schemeSeparatorIndex = rawValue.indexOf("://");
  if (schemeSeparatorIndex === -1) {
    return false;
  }
  const authorityStart = schemeSeparatorIndex + "://".length;
  let authorityEnd = rawValue.length;
  for (let index = authorityStart; index < rawValue.length; index += 1) {
    const character = rawValue[index];
    if (character === "/" || character === "?" || character === "#") {
      authorityEnd = index;
      break;
    }
  }
  return rawValue.slice(authorityStart, authorityEnd).includes("@");
}

/**
 * Validates the OIDC issuer without normalizing it. The URL parser is used for
 * validation only: the operator's original string is returned unchanged, so a
 * trailing slash stays a meaningful issuer difference rather than something the
 * application silently rewrites.
 */
export function parseOidcAuthority(
  rawValue: string | undefined,
  options: { readonly isProduction: boolean },
): string {
  if (rawValue === undefined || rawValue === "") {
    throw new EnvConfigError();
  }

  // Checked on the raw string, before the URL parser can strip tab/CR/LF or
  // silently drop an empty "?"/"#"/userinfo delimiter.
  if (rawValue !== rawValue.trim()) {
    throw new EnvConfigError();
  }
  if (hasControlCharacter(rawValue)) {
    throw new EnvConfigError();
  }
  if (rawValue.includes("?") || rawValue.includes("#")) {
    throw new EnvConfigError();
  }
  if (hasUserInfoDelimiter(rawValue)) {
    throw new EnvConfigError();
  }

  let url: URL;
  try {
    url = new URL(rawValue);
  } catch {
    throw new EnvConfigError();
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new EnvConfigError();
  }
  if (url.protocol === "http:") {
    if (options.isProduction) {
      throw new EnvConfigError();
    }
    if (!LOOPBACK_HOSTNAMES.has(url.hostname)) {
      throw new EnvConfigError();
    }
  }

  return rawValue;
}

/** Validates the public SPA client ID and returns the operator value verbatim. */
export function parseOidcClientId(rawValue: string | undefined): string {
  if (rawValue === undefined || rawValue === "") {
    throw new EnvConfigError();
  }
  if (rawValue !== rawValue.trim()) {
    throw new EnvConfigError();
  }
  if (hasControlCharacter(rawValue)) {
    throw new EnvConfigError();
  }
  return rawValue;
}

export function parseApiBaseUrl(rawValue: string | undefined): string {
  if (!rawValue || rawValue.trim() === "") {
    throw new EnvConfigError();
  }

  let url: URL;
  try {
    url = new URL(rawValue);
  } catch {
    throw new EnvConfigError();
  }

  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new EnvConfigError();
  }

  if (url.username !== "" || url.password !== "") {
    throw new EnvConfigError();
  }

  if (url.search !== "" || url.hash !== "") {
    throw new EnvConfigError();
  }

  const normalizedPath = url.pathname === "/" ? "" : url.pathname.replace(/\/+$/, "");
  return `${url.protocol}//${url.host}${normalizedPath}`;
}

let cachedEnv: AppEnv | undefined;

export function getEnv(): AppEnv {
  if (!cachedEnv) {
    cachedEnv = { apiBaseUrl: parseApiBaseUrl(import.meta.env.VITE_API_BASE_URL) };
  }
  return cachedEnv;
}

let cachedAuthEnv: AuthEnv | undefined;

/**
 * Kept separate from getEnv() on purpose: the public health path has no
 * business failing because the Authorization Server is not configured. Both are
 * still validated together at start-up, so an incomplete environment never
 * reaches the UI.
 */
export function getAuthEnv(): AuthEnv {
  if (!cachedAuthEnv) {
    cachedAuthEnv = {
      oidcAuthority: parseOidcAuthority(import.meta.env.VITE_OIDC_AUTHORITY, {
        isProduction: import.meta.env.PROD === true,
      }),
      oidcClientId: parseOidcClientId(import.meta.env.VITE_OIDC_CLIENT_ID),
    };
  }
  return cachedAuthEnv;
}
