import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { AuthSession } from "../auth/authClient";
import { createFakeAuthClient, type FakeAuthClient } from "../test/fakeAuthClient";
import { jsonResponse, mockFetchOnce, mockFetchRejectOnce } from "../test/mockFetch";
import {
  ForbiddenError,
  HttpError,
  InvalidResponseError,
  NetworkError,
  RequestNotAllowedError,
  UnauthorizedError,
} from "./errors";
import {
  createInvestigationNote,
  fetchInvestigationNoteList,
  isInvestigationNoteCreated,
  isInvestigationNotePage,
  SYSTEM_NOTE_AUTHOR_REF,
  type InvestigationNoteCreateRequest,
  type InvestigationNoteListQuery,
} from "./investigationNoteApi";

const BASE = "http://localhost:8080";
const CASE_ID = "5c671624-8714-4bd7-871a-a9445e6f453e";
/** Carries hex letters, so case sensitivity is actually observable. */
const NOTE_ID = "10a0b0c0-0d0e-4f00-8a00-0b0c0d0e0f01";
const USER_REF = "2f4c0a4e-8a9d-4c2f-9a1b-7d6e5f430001";
const TRACE_ID = "trace_demo_case_notes_01";

const SESSION: AuthSession = {
  subject: USER_REF,
  roles: ["FDS_ANALYST"],
};

function signedIn(): FakeAuthClient {
  return createFakeAuthClient({ initialSession: SESSION });
}

function note(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    noteId: NOTE_ID,
    caseId: CASE_ID,
    authorType: "USER",
    authorRef: USER_REF,
    content: "조사 메모 원문",
    createdAt: "2026-09-02T00:00:00.123456Z",
    ...overrides,
  };
}

function created(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    ...note(),
    concurrencyVersion: 7,
    traceId: TRACE_ID,
    ...overrides,
  };
}

function listBody(
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

function sentRequest(): Request {
  return vi.mocked(fetch).mock.calls[0][0] as Request;
}

const VALID_CREATE: InvestigationNoteCreateRequest = {
  content: "조사 메모 원문",
  expectedVersion: 6,
};

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", BASE);
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

describe("fetchInvestigationNoteList", () => {
  it("sends GET with the canonical page query", async () => {
    mockFetchOnce(async () => jsonResponse(listBody()));

    await fetchInvestigationNoteList(signedIn(), CASE_ID, {
      sort: "createdAt,asc",
      size: 20,
      page: 0,
    });

    expect(sentRequest().url).toBe(
      `${BASE}/api/v1/cases/${CASE_ID}/notes?page=0&size=20&sort=createdAt%2Casc`,
    );
    expect(sentRequest().method).toBe("GET");
  });

  it("sends no query at all when none is supplied", async () => {
    mockFetchOnce(async () => jsonResponse(listBody()));
    await fetchInvestigationNoteList(signedIn(), CASE_ID);
    expect(sentRequest().url).toBe(`${BASE}/api/v1/cases/${CASE_ID}/notes`);
  });

  it("refuses a filter this endpoint does not accept", async () => {
    for (const query of [
      { caseStatus: "OPEN" },
      { authorType: "USER" },
      { sort: "changedAt,asc" },
      { sort: "createdAt,DESC" },
      { page: -1 },
      { size: 101 },
      { page: 1.5 },
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(listBody()));
      await expect(
        fetchInvestigationNoteList(client, CASE_ID, query as InvestigationNoteListQuery),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("uses `items`, not `content`, as the envelope key", async () => {
    expect(isInvestigationNotePage(listBody())).toBe(true);
    const wrongKey = { ...listBody() } as Record<string, unknown>;
    wrongKey.content = wrongKey.items;
    delete wrongKey.items;
    expect(isInvestigationNotePage(wrongKey)).toBe(false);
  });

  it("accepts an empty page for an existing case", async () => {
    mockFetchOnce(async () => jsonResponse(listBody([])));
    const result = await fetchInvestigationNoteList(signedIn(), CASE_ID);
    expect(result.data.items).toEqual([]);
  });

  it("rejects the whole page when one note is malformed", async () => {
    const body = listBody([note(), note({ authorType: "ADMIN" })], { totalElements: 2 });
    expect(isInvestigationNotePage(body)).toBe(false);

    mockFetchOnce(async () => jsonResponse(body));
    await expect(fetchInvestigationNoteList(signedIn(), CASE_ID)).rejects.toBeInstanceOf(
      InvalidResponseError,
    );
  });

  it("refuses page metadata that does not add up", async () => {
    for (const pageOverrides of [
      { totalElements: 45, totalPages: 2 },
      { totalElements: 1, totalPages: 1, first: false },
      { totalElements: 2, totalPages: 1 },
    ]) {
      expect(isInvestigationNotePage(listBody([note()], pageOverrides))).toBe(false);
    }
  });
});

describe("investigation note author pair", () => {
  it("accepts a SYSTEM note only with the system reference", async () => {
    expect(
      isInvestigationNotePage(
        listBody([note({ authorType: "SYSTEM", authorRef: SYSTEM_NOTE_AUTHOR_REF })]),
      ),
    ).toBe(true);
    expect(
      isInvestigationNotePage(listBody([note({ authorType: "SYSTEM", authorRef: USER_REF })])),
    ).toBe(false);
  });

  it("accepts a USER note only with a canonical UUID v4 reference", async () => {
    expect(isInvestigationNotePage(listBody([note({ authorType: "USER" })]))).toBe(true);
    for (const authorRef of [
      SYSTEM_NOTE_AUTHOR_REF,
      USER_REF.toUpperCase(),
      "analyst_ref_demo_07",
      null,
    ]) {
      expect(isInvestigationNotePage(listBody([note({ authorType: "USER", authorRef })]))).toBe(
        false,
      );
    }
  });

  it("refuses an author type outside the enum", async () => {
    for (const authorType of ["ADMIN", "user", "", null]) {
      expect(isInvestigationNotePage(listBody([note({ authorType })]))).toBe(false);
    }
  });
});

describe("investigation note content", () => {
  it("preserves untrusted plain text exactly, without trimming or normalizing", async () => {
    for (const content of [
      "  leading and trailing  ",
      "<script>alert(1)</script>",
      "<img src=x onerror=alert(1)>",
      "'; DROP TABLE fraud_case; --",
      "line one\r\nline two",
      "https://evil.example?a=b&c=d#frag",
    ]) {
      mockFetchOnce(async () => jsonResponse(listBody([note({ content })])));
      const result = await fetchInvestigationNoteList(signedIn(), CASE_ID);
      expect(result.data.items[0].content).toBe(content);
      vi.unstubAllGlobals();
    }
  });

  it("accepts up to 4,000 code points and refuses more", async () => {
    expect(isInvestigationNotePage(listBody([note({ content: "a".repeat(4000) })]))).toBe(true);
    expect(isInvestigationNotePage(listBody([note({ content: "🙂".repeat(4000) })]))).toBe(true);
    expect(isInvestigationNotePage(listBody([note({ content: "a".repeat(4001) })]))).toBe(false);
    expect(isInvestigationNotePage(listBody([note({ content: "🙂".repeat(4001) })]))).toBe(false);
  });

  it("refuses empty, whitespace-only and control-bearing content", async () => {
    for (const content of ["", " ", "\t\n ", "null\u0000byte", "\u007fdelete", "\u0085nel", 1]) {
      expect(isInvestigationNotePage(listBody([note({ content })]))).toBe(false);
    }
  });
});

describe("createInvestigationNote", () => {
  it("sends POST, expects 201 and rebuilds the body from validated values", async () => {
    mockFetchOnce(async () => jsonResponse(created(), { status: 201 }));

    const result = await createInvestigationNote(signedIn(), CASE_ID, VALID_CREATE);

    const request = sentRequest();
    expect(request.method).toBe("POST");
    expect(request.url).toBe(`${BASE}/api/v1/cases/${CASE_ID}/notes`);
    expect(new URL(request.url).search).toBe("");
    expect(JSON.parse(await request.clone().text())).toEqual({
      content: "조사 메모 원문",
      expectedVersion: 6,
    });
    expect(result.data.noteId).toBe(NOTE_ID);
    expect(result.data.concurrencyVersion).toBe(7);
  });

  it("refuses a 200 for a note creation", async () => {
    for (const status of [200, 202, 204]) {
      mockFetchOnce(async () =>
        status === 204 ? new Response(null, { status }) : jsonResponse(created(), { status }),
      );
      await expect(
        createInvestigationNote(signedIn(), CASE_ID, VALID_CREATE),
      ).rejects.toBeInstanceOf(InvalidResponseError);
      vi.unstubAllGlobals();
    }
  });

  it("refuses a request that proposes an author or carries an unknown field", async () => {
    for (const request of [
      { ...VALID_CREATE, authorRef: USER_REF },
      { ...VALID_CREATE, authorType: "USER" },
      { ...VALID_CREATE, actorId: USER_REF },
      { ...VALID_CREATE, correctionOfNoteId: NOTE_ID },
      { ...VALID_CREATE, unknown: 1 },
      { content: "조사 메모 원문" },
      { expectedVersion: 6 },
      {},
      null,
      "note",
      [],
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(created(), { status: 201 }));
      await expect(
        createInvestigationNote(client, CASE_ID, request as InvestigationNoteCreateRequest),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("refuses malformed content and a malformed expectedVersion before sending", async () => {
    for (const request of [
      { ...VALID_CREATE, content: "" },
      { ...VALID_CREATE, content: "   " },
      { ...VALID_CREATE, content: "a".repeat(4001) },
      { ...VALID_CREATE, content: "null\u0000byte" },
      { ...VALID_CREATE, content: 1 },
      { ...VALID_CREATE, content: null },
      { ...VALID_CREATE, expectedVersion: -1 },
      { ...VALID_CREATE, expectedVersion: 1.5 },
      { ...VALID_CREATE, expectedVersion: "6" },
      { ...VALID_CREATE, expectedVersion: Number.MAX_SAFE_INTEGER + 1 },
      { ...VALID_CREATE, expectedVersion: null },
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(created(), { status: 201 }));
      await expect(
        createInvestigationNote(client, CASE_ID, request as InvestigationNoteCreateRequest),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      vi.unstubAllGlobals();
    }
  });

  it("refuses a created response with a missing, unknown or malformed field", async () => {
    expect(isInvestigationNoteCreated(created())).toBe(true);

    const complete = created();
    for (const key of Object.keys(complete)) {
      const missing = { ...complete };
      delete missing[key];
      expect(isInvestigationNoteCreated(missing)).toBe(false);
    }
    expect(isInvestigationNoteCreated({ ...complete, actorId: USER_REF })).toBe(false);

    for (const override of [
      { noteId: NOTE_ID.toUpperCase() },
      { caseId: "not-a-uuid" },
      { createdAt: "2026-09-02T00:00:00" },
      { concurrencyVersion: -1 },
      { concurrencyVersion: Number.MAX_SAFE_INTEGER + 1 },
      { traceId: "short" },
      { authorType: "SYSTEM" },
    ]) {
      expect(isInvestigationNoteCreated(created(override))).toBe(false);
    }
  });

  it("does not replay a failed creation", async () => {
    for (const status of [400, 401, 403, 404, 409, 422, 500, 503]) {
      mockFetchOnce(async () => jsonResponse({ code: "X", message: "leaked" }, { status }));
      await expect(
        createInvestigationNote(signedIn(), CASE_ID, VALID_CREATE),
      ).rejects.toBeInstanceOf(Error);
      expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
      vi.unstubAllGlobals();
    }

    mockFetchRejectOnce(new TypeError("connection refused"));
    await expect(createInvestigationNote(signedIn(), CASE_ID, VALID_CREATE)).rejects.toBeInstanceOf(
      NetworkError,
    );
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
  });

  it("keeps a 409 NOTE_NOT_ALLOWED body out of the error", async () => {
    mockFetchOnce(async () =>
      jsonResponse(
        { code: "NOTE_NOT_ALLOWED", message: "leaked", traceId: "leaked_body_trace_01" },
        { status: 409 },
      ),
    );
    const error = await createInvestigationNote(signedIn(), CASE_ID, VALID_CREATE).catch(
      (thrown: unknown) => thrown,
    );
    expect(error).toBeInstanceOf(HttpError);
    expect((error as HttpError).status).toBe(409);
    expect(JSON.stringify(error)).not.toContain("leaked");
  });

  it("invalidates on 401 and leaves the session alone on 403", async () => {
    const unauthorized = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 401 }));
    await expect(
      createInvestigationNote(unauthorized, CASE_ID, VALID_CREATE),
    ).rejects.toBeInstanceOf(UnauthorizedError);
    expect(unauthorized.calls.invalidateIfCurrent).toBe(1);
    vi.unstubAllGlobals();

    const forbidden = signedIn();
    mockFetchOnce(async () => jsonResponse({}, { status: 403 }));
    await expect(createInvestigationNote(forbidden, CASE_ID, VALID_CREATE)).rejects.toBeInstanceOf(
      ForbiddenError,
    );
    expect(forbidden.calls.invalidateIfCurrent).toBe(0);
  });

  it("refuses a created response whose header trace id disagrees with its body", async () => {
    mockFetchOnce(async () =>
      jsonResponse(created(), { status: 201, headers: { "X-Trace-Id": "trace_demo_other_01" } }),
    );
    await expect(
      createInvestigationNote(signedIn(), CASE_ID, VALID_CREATE),
    ).rejects.toBeInstanceOf(InvalidResponseError);
  });

  it("refuses a non-canonical case id before any credential lookup", async () => {
    for (const id of [CASE_ID.toUpperCase(), `${CASE_ID}/notes`, "../actuator", ""]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(created(), { status: 201 }));
      await expect(createInvestigationNote(client, id, VALID_CREATE)).rejects.toBeInstanceOf(
        RequestNotAllowedError,
      );
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });
});

/**
 * Whitespace here has to mean what Java means by it, because Backend decides
 * `422 INVALID_CONTENT` with
 * `Character.isWhitespace(cp) || Character.isSpaceChar(cp)`. JavaScript's own
 * `\s` is a different set, and the difference is not academic: it matches
 * U+FEFF, which Java does not, so relying on it would refuse a note Backend
 * accepts.
 */
describe("investigation note content — Java whitespace parity", () => {
  it("refuses a note made only of characters Java counts as whitespace", async () => {
    for (const codePoint of [
      0x20, // SPACE
      0xa0, // NO-BREAK SPACE, which Character.isWhitespace alone would miss
      0x2007, // FIGURE SPACE, likewise
      0x202f, // NARROW NO-BREAK SPACE, likewise
      0x1680, // OGHAM SPACE MARK
      0x2000, // EN QUAD
      0x200a, // HAIR SPACE
      0x2028, // LINE SEPARATOR
      0x2029, // PARAGRAPH SEPARATOR
      0x205f, // MEDIUM MATHEMATICAL SPACE
      0x3000, // IDEOGRAPHIC SPACE
    ]) {
      const content = String.fromCodePoint(codePoint).repeat(3);
      expect(isInvestigationNotePage(listBody([note({ content })]))).toBe(false);
    }
  });

  it("accepts a note of only U+FEFF, which Java classifies as a format character", async () => {
    const bom = "\ufeff";
    // The set JavaScript would have used disagrees, which is the whole point.
    expect(/\s/u.test(bom)).toBe(true);
    expect(isInvestigationNotePage(listBody([note({ content: bom })]))).toBe(true);
    expect(isInvestigationNotePage(listBody([note({ content: bom + bom })]))).toBe(true);
  });

  it("accepts content that is whitespace around something real, unchanged", async () => {
    for (const content of [
      "\u00a0a\u00a0",
      "\u3000메모\u3000",
      "  padded  ",
      "line one\r\nline two",
    ]) {
      mockFetchOnce(async () => jsonResponse(listBody([note({ content })])));
      const result = await fetchInvestigationNoteList(signedIn(), CASE_ID);
      expect(result.data.items[0].content).toBe(content);
      vi.unstubAllGlobals();
    }
  });

  it("refuses a whitespace-only creation before any credential lookup", async () => {
    for (const content of [
      "\u0020",
      "\u00a0\u00a0",
      "\u3000",
      "\u2028",
      "\t\n ",
    ]) {
      const client = signedIn();
      mockFetchOnce(async () => jsonResponse(created(), { status: 201 }));
      await expect(
        createInvestigationNote(client, CASE_ID, { content, expectedVersion: 6 }),
      ).rejects.toBeInstanceOf(RequestNotAllowedError);
      expect(client.calls.authorizeRequest).toBe(0);
      expect(vi.mocked(fetch)).not.toHaveBeenCalled();
      vi.unstubAllGlobals();
    }
  });

  it("sends a U+FEFF-only note, because Backend accepts one", async () => {
    const content = "\ufeff";
    mockFetchOnce(async () => jsonResponse(created({ content }), { status: 201 }));

    await createInvestigationNote(signedIn(), CASE_ID, { content, expectedVersion: 6 });

    expect(JSON.parse(await sentRequest().clone().text())).toEqual({
      content,
      expectedVersion: 6,
    });
  });

  it("still counts length in code points, whitespace included", async () => {
    const padded = "\u00a0".repeat(3999) + "a";
    expect(isInvestigationNotePage(listBody([note({ content: padded })]))).toBe(true);
    const tooLong = "\u00a0".repeat(4000) + "a";
    expect(isInvestigationNotePage(listBody([note({ content: tooLong })]))).toBe(false);
  });
});
