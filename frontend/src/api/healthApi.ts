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

  // 검증된 body의 status·service만 새 data에 복사하고 result root도 새로 만든다. transport가 돌려준 raw
  // 객체의 prototype, 열거되지 않는 field, symbol key와 이후 변경은 반환값에 도달하지 않는다.
  const validated = response.body;
  return {
    data: { status: validated.status, service: validated.service },
    traceId: extractSafeTraceId(response.headers),
  };
}
