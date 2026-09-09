import { StrictMode, type ReactNode } from "react";
import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthSession } from "../../auth/authClient";
import { AuthProvider } from "../../auth/AuthProvider";
import { createFakeAuthClient, type FakeAuthClient } from "../../test/fakeAuthClient";
import { jsonResponse } from "../../test/mockFetch";
import type { CaseInvestigationNotesState } from "../../api/useCaseInvestigationNotes";

const adapter = vi.hoisted(() => ({ client: null as unknown }));

vi.mock("../../auth/oidcAuthClient", () => ({
  getOidcAuthClient: () => adapter.client,
}));

const { CaseInvestigationNotesPanel, CaseInvestigationNotesSection } = await import(
  "./CaseInvestigationNotesSection"
);

const CASE_ID = "5c2d1e0f-7a8b-4c9d-9e0f-1a2b3c4d5e60";
const USER_REF = "7c1d2e3f-4a5b-4c6d-8e9f-0a1b2c3d4e5f";
const TRACE_ID = "trace_demo_case_notes_section_01";
const PLAIN_CONTENT =
  "  조사 메모\r\nsecond  line <script>window.pwned=true</script> https://evil.invalid/path  ";

const SESSION: AuthSession = {
  subject: USER_REF,
  displayName: "Local Analyst",
  roles: ["FDS_ANALYST"],
};

function note(index = 0, overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    noteId: `8d2e3f40-5b6c-4d7e-9f01-${index.toString(16).padStart(12, "0")}`,
    caseId: CASE_ID,
    authorType: "USER",
    authorRef: USER_REF,
    content: PLAIN_CONTENT,
    createdAt: "2026-09-02T00:00:00.123456Z",
    ...overrides,
  };
}

function notesBody(
  items: readonly Record<string, unknown>[] = [note()],
  pageOverrides: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    items,
    page: {
      number: 0,
      size: 20,
      totalElements: items.length,
      totalPages: items.length === 0 ? 0 : 1,
      first: true,
      last: true,
      ...pageOverrides,
    },
    traceId: TRACE_ID,
  };
}

interface PendingCall {
  readonly promise: Promise<Response>;
  readonly request: Request;
  settle: (response: Response) => void;
  fail: (error: unknown) => void;
}

function controlledFetch(): { readonly calls: PendingCall[]; readonly spy: ReturnType<typeof vi.fn> } {
  const calls: PendingCall[] = [];
  const spy = vi.fn().mockImplementation((request: Request) => {
    let settle!: (response: Response) => void;
    let fail!: (error: unknown) => void;
    const promise = new Promise<Response>((resolve, reject) => {
      settle = resolve;
      fail = reject;
    });
    promise.catch(() => undefined);
    calls.push({ promise, request, settle, fail });
    return promise;
  });
  vi.stubGlobal("fetch", spy);
  return { calls, spy };
}

function signedIn(): FakeAuthClient {
  const client = createFakeAuthClient({ initialSession: SESSION });
  adapter.client = client;
  return client;
}

function renderSection(client: FakeAuthClient = signedIn()) {
  const tree: ReactNode = (
    <AuthProvider client={client}>
      <CaseInvestigationNotesSection caseId={CASE_ID} />
    </AuthProvider>
  );
  return render(<StrictMode>{tree}</StrictMode>);
}

async function settle(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

async function answer(call: PendingCall, body: unknown, status = 200): Promise<void> {
  await act(async () => {
    call.settle(jsonResponse(body, { status }));
    await call.promise;
    await Promise.resolve();
  });
}

async function show(items: readonly Record<string, unknown>[] = [note()]): Promise<void> {
  const { calls } = controlledFetch();
  renderSection();
  await settle();
  await answer(calls[0], notesBody(items));
  await waitFor(() => {
    expect(screen.queryByText("Loading investigation notes...")).not.toBeInTheDocument();
  });
}

function valueOf(article: HTMLElement, term: string): HTMLElement {
  const dt = within(article).getByText(term, { selector: "dt" });
  const dd = dt.nextElementSibling;
  if (!(dd instanceof HTMLElement) || dd.tagName !== "DD") {
    throw new Error(`No value found for ${term}`);
  }
  return dd;
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
  adapter.client = null;
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("CaseInvestigationNotesSection request and content", () => {
  it("starts the exact fixed query once and renders its own loading section", async () => {
    const { calls, spy } = controlledFetch();
    renderSection();
    await settle();

    expect(spy).toHaveBeenCalledTimes(1);
    expect(calls[0].request.url).toBe(
      `http://localhost:8080/api/v1/cases/${CASE_ID}/notes?page=0&size=20&sort=createdAt%2Casc`,
    );
    expect(screen.getByRole("heading", { name: "Investigation notes", level: 3 })).toBeVisible();
    expect(screen.getByText("Loading investigation notes...")).toBeVisible();
  });

  it("renders SYSTEM and USER notes as ordered articles with raw opaque metadata", async () => {
    await show([
      note(1, { authorType: "SYSTEM", authorRef: "finguardops-backend" }),
      note(2),
    ]);

    const list = screen.getByRole("list");
    expect(list.tagName).toBe("OL");
    const articles = screen.getAllByRole("article");
    expect(articles).toHaveLength(2);
    expect(valueOf(articles[0], "Author type")).toHaveTextContent("SYSTEM");
    expect(valueOf(articles[0], "Author reference")).toHaveTextContent("finguardops-backend");
    expect(valueOf(articles[1], "Author type")).toHaveTextContent("USER");
    expect(valueOf(articles[1], "Author reference")).toHaveTextContent(USER_REF);
    expect(valueOf(articles[0], "Note ID").closest("a")).toBeNull();
    const time = within(articles[0]).getByText("2026-09-02 09:00:00 KST");
    expect(time).toHaveAttribute("datetime", "2026-09-02T00:00:00.123456Z");
  });

  it("shows untrusted content in full as escaped plain text without autolinking", async () => {
    const long = `${PLAIN_CONTENT}${"가".repeat(4000 - Array.from(PLAIN_CONTENT).length)}`;
    expect(Array.from(long)).toHaveLength(4000);
    await show([note(3, { content: long })]);

    const content = valueOf(screen.getByRole("article"), "Content");
    expect(content.textContent).toBe(long);
    expect(content.querySelector("script")).toBeNull();
    expect(content.querySelector("a")).toBeNull();
    expect(content).not.toHaveAttribute("title");
    expect(content).not.toHaveAttribute("aria-label");
    expect(document.body.textContent).not.toContain(TRACE_ID);
    expect(document.body.textContent).not.toContain(CASE_ID);
  });

  it("offers no investigation-note mutation or export controls", async () => {
    await show();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.queryByRole("checkbox")).not.toBeInTheDocument();
    for (const name of [/create/i, /edit/i, /delete/i, /copy/i, /download/i, /export/i]) {
      expect(screen.queryByRole("button", { name })).not.toBeInTheDocument();
      expect(screen.queryByRole("link", { name })).not.toBeInTheDocument();
    }
  });
});

describe("CaseInvestigationNotesSection pagination", () => {
  it("distinguishes true empty and out-of-range empty without correction", async () => {
    const first = controlledFetch();
    renderSection();
    await settle();
    await answer(first.calls[0], notesBody([]));
    expect(screen.getByText("No investigation notes.", { selector: ".notice" })).toBeVisible();
    expect(first.spy).toHaveBeenCalledTimes(1);
  });

  it("uses local Previous/Next and resets page to zero when size changes", async () => {
    const user = userEvent.setup();
    const { calls } = controlledFetch();
    const initial = Array.from({ length: 20 }, (_, index) => note(index));
    renderSection();
    await settle();
    await answer(
      calls[0],
      notesBody(initial, {
        totalElements: 41,
        totalPages: 3,
        first: true,
        last: false,
      }),
    );

    const pager = screen.getByRole("navigation", { name: "Investigation notes pages" });
    expect(within(pager).getByRole("button", { name: "Previous" })).toBeDisabled();
    expect(within(pager).getByRole("combobox", { name: "Notes per page" })).toHaveValue("20");
    expect(within(pager).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "20",
      "50",
      "100",
    ]);

    await user.click(within(pager).getByRole("button", { name: "Next" }));
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
    await settle();
    expect(calls[1].request.url).toContain("page=1&size=20&sort=createdAt%2Casc");
    await answer(
      calls[1],
      notesBody([], {
        number: 1,
        totalElements: 1,
        totalPages: 1,
        first: false,
        last: true,
      }),
    );
    expect(screen.getByText("No investigation notes on this page.")).toBeVisible();
    expect(calls).toHaveLength(2);

    await user.selectOptions(
      screen.getByRole("combobox", { name: "Notes per page" }),
      "50",
    );
    await settle();
    expect(calls[2].request.url).toContain("page=0&size=50&sort=createdAt%2Casc");
    expect(window.location.search).toBe("");
    expect(window.history.length).toBeGreaterThan(0);
  });
});

describe("CaseInvestigationNotesSection failures", () => {
  it.each([
    [403, "You do not have permission to view investigation notes."],
    [404, "Investigation notes are unavailable because this case was not found."],
  ] as const)("keeps HTTP %s inside the section with no retry", async (status, copy) => {
    const { calls, spy } = controlledFetch();
    renderSection();
    await settle();
    await answer(calls[0], { code: "PRIVATE", message: "hidden", traceId: TRACE_ID }, status);

    expect(screen.getByText(copy)).toBeVisible();
    expect(screen.queryByRole("button", { name: /again/i })).not.toBeInTheDocument();
    await settle();
    expect(spy).toHaveBeenCalledTimes(1);
    expect(document.body.textContent).not.toMatch(/PRIVATE|hidden|trace_demo/);
  });

  it("focuses a retryable error heading and retries exactly once per press", async () => {
    const user = userEvent.setup();
    const { calls, spy } = controlledFetch();
    renderSection();
    await settle();
    await act(async () => {
      calls[0].fail(new TypeError("offline private text"));
      await Promise.resolve();
    });

    const heading = await screen.findByRole("heading", {
      name: "The backend could not be reached",
      level: 4,
    });
    expect(heading).toHaveFocus();
    expect(document.body.textContent).not.toContain("offline private text");
    await user.click(screen.getByRole("button", { name: /again/i }));
    await settle();
    expect(spy).toHaveBeenCalledTimes(2);
    expect(screen.queryByRole("article")).not.toBeInTheDocument();
  });
});

describe("CaseInvestigationNotesPanel geometry seam", () => {
  it("renders a settled populated state without auth, transport, or router", () => {
    const fetchSpy = vi.fn();
    vi.stubGlobal("fetch", fetchSpy);
    const state: CaseInvestigationNotesState = {
      status: "success",
      data: {
        items: [
          {
            noteId: "8d2e3f40-5b6c-4d7e-9f01-000000000001",
            authorType: "SYSTEM",
            authorRef: "finguardops-backend",
            content: PLAIN_CONTENT,
            createdAt: "2026-09-02T00:00:00.123456Z",
          },
        ],
        page: {
          number: 0,
          size: 20,
          totalElements: 1,
          totalPages: 1,
          first: true,
          last: true,
        },
      },
    };
    render(
      <CaseInvestigationNotesPanel
        state={state}
        onPageChange={() => undefined}
        onPageSizeChange={() => undefined}
        onRetry={() => undefined}
      />,
    );
    expect(screen.getByRole("article")).toBeVisible();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it("keeps the authoritative list visible beside an isolated refresh failure", async () => {
    const user = userEvent.setup();
    const onRefresh = vi.fn();
    const state: CaseInvestigationNotesState = {
      status: "success",
      data: {
        items: [{
          noteId: "8d2e3f40-5b6c-4d7e-9f01-000000000001",
          authorType: "USER",
          authorRef: USER_REF,
          content: PLAIN_CONTENT,
          createdAt: "2026-09-02T00:00:00.123456Z",
        }],
        page: { number: 0, size: 20, totalElements: 1, totalPages: 1, first: true, last: true },
      },
    };
    render(
      <CaseInvestigationNotesPanel
        state={state}
        onPageChange={() => undefined}
        onPageSizeChange={() => undefined}
        onRetry={() => undefined}
        refreshState="failed"
        onRefresh={onRefresh}
      />,
    );
    expect(screen.getByRole("article")).toBeVisible();
    expect(screen.getByRole("alert")).toHaveTextContent("submission result is unchanged");
    await user.click(screen.getByRole("button", { name: "Refresh investigation notes" }));
    expect(onRefresh).toHaveBeenCalledTimes(1);
  });
});
