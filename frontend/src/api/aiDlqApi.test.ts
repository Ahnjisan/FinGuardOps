import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse, mockFetchOnce } from "../test/mockFetch";
import { decideDlq, fetchDlq } from "./aiDlqApi";
import type { DlqDiagnostic } from "./aiDlqApi";
import { InvalidResponseError } from "./errors";

const topicId = "33333333-3333-4333-8333-333333333333";
const executionId = "44444444-4444-4444-8444-444444444444";
const coordinate = { topicId, partition: 0, offset: 7 };
const diagnostic: DlqDiagnostic = { ...coordinate, failureCategory: "PRE_CLAIM_TRANSIENT",
  sourceVerified: true, sourceRecovered: true, eventId: executionId, executionId,
  executionStatus: "PENDING", reportExists: false, attemptExists: false,
  action: null, dispatchStatus: null, startSource: null, ackPartition: null,
  ackOffset: null, replayAllowed: true, rejectionReason: null, traceId: "trace-dlq" };
const auth = () => createFakeAuthClient({ initialSession: { subject: executionId,
  roles: ["PLATFORM_ADMIN"] } });
beforeEach(() => vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080"));
afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); });

it("accepts only safe exact metadata and separates DB approval from broker ack", async () => {
  mockFetchOnce(async () => jsonResponse(diagnostic));
  expect((await fetchDlq(auth(), coordinate)).replayAllowed).toBe(true);
  mockFetchOnce(async () => jsonResponse({ ...diagnostic, payload: "sensitive" }));
  await expect(fetchDlq(auth(), coordinate)).rejects.toBeInstanceOf(InvalidResponseError);
  mockFetchOnce(async () => jsonResponse({ ...diagnostic, action: "REPLAY",
    dispatchStatus: "PENDING", replayAllowed: false, rejectionReason: "ALREADY_DECIDED" }, { status: 202 }));
  const accepted = await decideDlq(auth(), diagnostic, "replay");
  expect(accepted.dispatchStatus).toBe("PENDING");
  expect(accepted.ackOffset).toBeNull();
});

it("never accepts a mismatched coordinate or invented action", async () => {
  mockFetchOnce(async () => jsonResponse({ ...diagnostic, offset: 8 }));
  await expect(fetchDlq(auth(), coordinate)).rejects.toBeInstanceOf(InvalidResponseError);
  mockFetchOnce(async () => jsonResponse({ ...diagnostic, action: "REPLAY" }, { status: 202 }));
  await expect(decideDlq(auth(), diagnostic, "quarantine")).rejects.toBeInstanceOf(InvalidResponseError);
});
