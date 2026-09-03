export interface HealthResponse {
  readonly status: "UP";
  readonly service: "backend";
}

export interface HealthResult {
  readonly data: HealthResponse;
  readonly traceId?: string;
}
