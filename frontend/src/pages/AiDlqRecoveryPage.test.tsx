import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { AuthContext } from "../auth/authContext";
import type { AuthSession } from "../auth/authClient";
import { createFakeAuthClient } from "../test/fakeAuthClient";
import { AiDlqRecoveryPage } from "./AiDlqRecoveryPage";

const state = vi.hoisted(() => ({ calls: [] as string[] }));
vi.mock("../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => ({}) }));
vi.mock("../api/aiDlqApi", () => ({
  fetchDlq: async () => {
    state.calls.push("inspect");
    return { topicId: "33333333-3333-4333-8333-333333333333", partition: 0, offset: 7,
      failureCategory: "UNKNOWN", sourceVerified: false, sourceRecovered: false,
      eventId: null, executionId: null, executionStatus: null, reportExists: false,
      attemptExists: false, action: null, dispatchStatus: null, startSource: null,
      ackPartition: null, ackOffset: null, replayAllowed: false,
      rejectionReason: "SOURCE_UNVERIFIED", traceId: "trace-dlq" };
  },
  decideDlq: async (_auth: unknown, _prior: unknown, action: string) => {
    state.calls.push(action);
    return { topicId: "33333333-3333-4333-8333-333333333333", partition: 0, offset: 7,
      failureCategory: "UNKNOWN", sourceVerified: false, sourceRecovered: false,
      eventId: null, executionId: null, executionStatus: null, reportExists: false,
      attemptExists: false, action: "QUARANTINE", dispatchStatus: null, startSource: null,
      ackPartition: null, ackOffset: null, replayAllowed: false,
      rejectionReason: "ALREADY_DECIDED", traceId: "trace-dlq" };
  },
}));

afterEach(() => { state.calls.length = 0; });

it("shows poison quarantine and keeps replay disabled for UNKNOWN", async () => {
  const session: AuthSession = { subject: "44444444-4444-4444-8444-444444444444", roles: ["PLATFORM_ADMIN"] };
  const client = createFakeAuthClient({ initialSession: session });
  const auth = { state: { status: "authenticated" as const, session }, client,
    signIn: () => undefined, signOut: () => undefined,
    notifyCallbackStarted: () => undefined, notifyCallbackSucceeded: () => undefined,
    notifyCallbackFailed: () => undefined };
  render(<AuthContext.Provider value={auth}><MemoryRouter><AiDlqRecoveryPage />
    </MemoryRouter></AuthContext.Provider>);
  fireEvent.change(screen.getByLabelText("DLQ topic ID"),
    { target: { value: "33333333-3333-4333-8333-333333333333" } });
  fireEvent.change(screen.getByLabelText("Partition"), { target: { value: "0" } });
  fireEvent.change(screen.getByLabelText("Offset"), { target: { value: "7" } });
  fireEvent.click(screen.getByRole("button", { name: "단건 진단" }));
  await waitFor(() => expect(state.calls).toEqual(["inspect"]));
  expect(screen.getByRole("button", { name: "조건부 단건 재처리" })).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "명시적 격리" }));
  await waitFor(() => expect(state.calls).toEqual(["inspect", "quarantine"]));
  expect(screen.queryByRole("button", { name: "조건부 단건 재처리" })).toBeNull();
  expect(screen.queryByText(/sensitive payload/)).toBeNull();
});
