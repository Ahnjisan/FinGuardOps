import { StrictMode, type ReactNode } from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CaseStatus } from "../../api/caseApi";
import type { AuthSession } from "../../auth/authClient";
import { AuthProvider } from "../../auth/AuthProvider";
import { createFakeAuthClient, type FakeAuthClient } from "../../test/fakeAuthClient";
import { jsonResponse } from "../../test/mockFetch";

const adapter = vi.hoisted(() => ({ client: null as unknown }));
vi.mock("../../auth/oidcAuthClient", () => ({ getOidcAuthClient: () => adapter.client }));

const { InvestigationNoteComposer } = await import("./InvestigationNoteComposer");

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const NOTE_ID = "8d2e3f40-5b6c-4d7e-9f01-1b2c3d4e5f60";
const USER_REF = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";

interface PendingCall {
  readonly request: Request;
  resolve: (response: Response) => void;
}

function controlledFetch(): PendingCall[] {
  const calls: PendingCall[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation((request: Request) => {
      let resolve!: (response: Response) => void;
      const promise = new Promise<Response>((done) => {
        resolve = done;
      });
      calls.push({ request, resolve });
      return promise;
    }),
  );
  return calls;
}

function session(role: AuthSession["roles"][number] = "FDS_ANALYST"): AuthSession {
  return { subject: USER_REF, roles: [role] };
}

function renderComposer({
  role = "FDS_ANALYST",
  status = "IN_REVIEW",
  reconcile = vi.fn(),
}: {
  readonly role?: AuthSession["roles"][number];
  readonly status?: CaseStatus;
  readonly reconcile?: () => void;
} = {}) {
  const client: FakeAuthClient = createFakeAuthClient({ initialSession: session(role) });
  adapter.client = client;
  const tree: ReactNode = (
    <AuthProvider client={client}>
      <InvestigationNoteComposer
        caseId={CASE_ID}
        caseStatus={status}
        expectedVersion={6}
        reconciliationGeneration={1}
        onReconcile={reconcile}
      />
    </AuthProvider>
  );
  return { client, view: render(<StrictMode>{tree}</StrictMode>) };
}

function created(content: string): Record<string, unknown> {
  return {
    noteId: NOTE_ID,
    caseId: CASE_ID,
    authorType: "USER",
    authorRef: "6d3e2f10-8b9c-4d0e-8f01-2b3c4d5e6f71",
    content,
    createdAt: "2026-09-02T00:00:00.123456Z",
    concurrencyVersion: 7,
    traceId: "trace_demo_note_composer_01",
  };
}

async function settle(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  adapter.client = null;
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("InvestigationNoteComposer visibility", () => {
  it.each(["FDS_VIEWER", "FDS_APPROVER", "PLATFORM_ADMIN"] as const)(
    "renders no composer or status guidance for %s",
    async (role) => {
      renderComposer({ role });
      await settle();
      expect(screen.queryByLabelText("Investigation note")).not.toBeInTheDocument();
      expect(screen.queryByText(/cannot be added/i)).not.toBeInTheDocument();
    },
  );

  it.each(["IN_REVIEW", "ADDITIONAL_INFORMATION_REQUIRED"] as const)(
    "renders the form for an analyst in %s",
    async (status) => {
      renderComposer({ status });
      await settle();
      expect(screen.getByLabelText("Investigation note")).toBeInTheDocument();
      expect(screen.getByRole("button", { name: "Add note" })).toHaveAttribute("type", "submit");
      expect(screen.getByRole("button", { name: "Cancel" })).toHaveAttribute("type", "button");
    },
  );

  it.each([
    ["OPEN", "open"],
    ["CLOSED", "closed"],
  ] as const)("shows fixed status guidance instead of a form for %s", async (status, word) => {
    renderComposer({ status });
    await settle();
    expect(screen.queryByLabelText("Investigation note")).not.toBeInTheDocument();
    expect(screen.getByText(`Investigation notes cannot be added while this case is ${word}.`)).toBeVisible();
  });
});

describe("InvestigationNoteComposer interaction", () => {
  it("counts code points, leaves Enter as a newline, and submits exact text by shortcut", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    renderComposer();
    await settle();
    const textarea = screen.getByLabelText("Investigation note");
    expect(textarea).toHaveAttribute("autocomplete", "off");
    expect(textarea).not.toHaveAttribute("maxlength");

    await user.type(textarea, "😀");
    expect(screen.getByText("1 / 4,000")).toBeVisible();
    await user.type(textarea, "{enter}second");
    expect(calls).toHaveLength(0);

    fireEvent.keyDown(textarea, { key: "Enter", ctrlKey: true });
    await waitFor(() => expect(calls).toHaveLength(1));
    const body = JSON.parse(await calls[0].request.clone().text()) as Record<string, unknown>;
    expect(body).toEqual({ content: "😀\nsecond", expectedVersion: 6 });
  });

  it("rejects invalid text with focus and preserves the draft", async () => {
    const user = userEvent.setup();
    renderComposer();
    await settle();
    const textarea = screen.getByLabelText("Investigation note");
    await user.type(textarea, "   ");
    await user.click(screen.getByRole("button", { name: "Add note" }));
    expect(textarea).toHaveFocus();
    expect(textarea).toHaveAttribute("aria-invalid", "true");
    expect(textarea).toHaveValue("   ");
    expect(
      screen.getByText(
        "Enter 1–4,000 Unicode characters and include at least one non-whitespace character.",
      ),
    ).toBeVisible();
  });

  it("blocks double click, repeated shortcut and IME composition while pending", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    renderComposer();
    await settle();
    const textarea = screen.getByLabelText("Investigation note");
    await user.type(textarea, "one request");

    fireEvent.compositionStart(textarea);
    fireEvent.keyDown(textarea, { key: "Enter", ctrlKey: true, isComposing: true });
    expect(calls).toHaveLength(0);
    fireEvent.compositionEnd(textarea);
    fireEvent.keyDown(textarea, { key: "Enter", metaKey: true });
    fireEvent.keyDown(textarea, { key: "Enter", metaKey: true });
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(screen.getByRole("button", { name: "Adding note…" })).toBeDisabled();
    expect(textarea).toBeDisabled();
  });

  it("clears and refocuses only after success and announces fixed copy", async () => {
    const calls = controlledFetch();
    const reconcile = vi.fn();
    const user = userEvent.setup();
    renderComposer({ reconcile });
    await settle();
    const textarea = screen.getByLabelText("Investigation note");
    const content = "  preserved \n text  ";
    fireEvent.change(textarea, { target: { value: content } });
    await user.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() => expect(calls).toHaveLength(1));

    await act(async () => {
      calls[0].resolve(jsonResponse(created(content), { status: 201 }));
      await Promise.resolve();
      await Promise.resolve();
    });
    await waitFor(() => expect(textarea).toHaveValue(""));
    expect(textarea).toHaveFocus();
    expect(screen.getByRole("status")).toHaveTextContent("Investigation note added.");
    expect(reconcile).toHaveBeenCalledTimes(1);
    expect(screen.getByRole("button", { name: "Add note" })).toBeDisabled();
  });

  it.each([403, 404, 409, 422, 500, 503])(
    "preserves draft and focuses sanitized feedback after HTTP %i",
    async (status) => {
      const calls = controlledFetch();
      const user = userEvent.setup();
      renderComposer();
      await settle();
      const textarea = screen.getByLabelText("Investigation note");
      const content = "draft must remain";
      await user.type(textarea, content);
      await user.click(screen.getByRole("button", { name: "Add note" }));
      await waitFor(() => expect(calls).toHaveLength(1));
      await act(async () => {
        calls[0].resolve(jsonResponse({ code: "PRIVATE", message: content }, { status }));
        await Promise.resolve();
        await Promise.resolve();
      });
      expect(textarea).toHaveValue(content);
      expect(screen.getByRole("heading", { name: "Note not added" })).toHaveFocus();
      expect(document.body.textContent?.match(/draft must remain/g)).toHaveLength(1);
    },
  );

  it("Cancel clears an idle or failed draft but never aborts a pending request", async () => {
    const calls = controlledFetch();
    const user = userEvent.setup();
    renderComposer();
    await settle();
    const textarea = screen.getByLabelText("Investigation note");
    await user.type(textarea, "clear me");
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(textarea).toHaveValue("");

    await user.type(textarea, "pending draft");
    await user.click(screen.getByRole("button", { name: "Add note" }));
    await waitFor(() => expect(calls).toHaveLength(1));
    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(calls[0].request.signal.aborted).toBe(false);
  });
});
