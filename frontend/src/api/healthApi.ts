import { getEnv } from "../config/env";
import { InvalidResponseError } from "./errors";
import { httpGet } from "./httpClient";
import { extractSafeTraceId } from "./traceId";
import type { HealthResponse, HealthResult } from "./types";

const HEALTH_REQUEST_TIMEOUT_MS = 5000;

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== "object" || value === null) {
    return false;
  }
  const entries = Object.keys(value);
  if (entries.length !== 2) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return record.status === "UP" && record.service === "backend";
}

/**
 * The public Health path. It stays deliberately independent of the
 * authenticated transport: no endpoint registry, no AuthClient, no
 * Authorization header, no credentials. `/api/health` is credential-free on the
 * Backend and must remain credential-free here.
 */
export async function fetchHealth(signal?: AbortSignal): Promise<HealthResult> {
  const { apiBaseUrl } = getEnv();
  const url = `${apiBaseUrl}/api/health`;

  const response = await httpGet(url, { timeoutMs: HEALTH_REQUEST_TIMEOUT_MS, signal });

  if (!isHealthResponse(response.body)) {
    throw new InvalidResponseError();
  }

  return {
    data: response.body,
    traceId: extractSafeTraceId(response.headers),
  };
}
