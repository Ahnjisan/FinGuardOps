export class EnvConfigError extends Error {
  constructor() {
    super("Application environment configuration is invalid.");
    this.name = "EnvConfigError";
  }
}

export interface AppEnv {
  readonly apiBaseUrl: string;
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
