import { StrictMode } from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { HealthPage } from "./HealthPage";
import { renderWithRouter } from "../test/renderWithRouter";
import { jsonResponse, mockFetchOnce, mockFetchRejectOnce } from "../test/mockFetch";

interface Deferred {
  resolve: (value: unknown) => void;
  reject: (reason?: unknown) => void;
}

/** Each fetch() call gets its own deferred promise, settled independently by the test. */
function stubQueuedFetch(): Deferred[] {
  const deferredList: Deferred[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn().mockImplementation(() => {
      let resolve!: (value: unknown) => void;
      let reject!: (reason?: unknown) => void;
      const promise = new Promise((res, rej) => {
        resolve = res;
        reject = rej;
      });
      deferredList.push({ resolve, reject });
      return promise;
    }),
  );
  return deferredList;
}

beforeEach(() => {
  vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
});

function renderHealthPage() {
  return renderWithRouter([{ path: "/", element: <HealthPage /> }]);
}

describe("HealthPage", () => {
  it("shows a loading state before the health check resolves", async () => {
    const deferred = stubQueuedFetch();

    renderHealthPage();

    expect(screen.getByRole("status")).toHaveTextContent(/checking backend health/i);

    // Settle the request before the test ends so the module-level in-flight
    // registry clears and does not leak into later tests in this file.
    deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/healthy/i);
    });
  });

  it("shows a success state once the health check resolves", async () => {
    mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

    renderHealthPage();

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/healthy/i);
    });
  });

  it("shows a fixed safe error message when the health check fails, without leaking internals", async () => {
    mockFetchRejectOnce(new TypeError("Failed to fetch"));

    renderHealthPage();

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/unable to reach the backend/i);
    });
    expect(screen.getByRole("status").textContent).not.toContain("TypeError");
    expect(screen.getByRole("status").textContent).not.toContain("localhost:8080");
  });

  it("does not automatically retry after a failure", async () => {
    mockFetchRejectOnce(new TypeError("Failed to fetch"));

    renderHealthPage();

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/unable to reach the backend/i);
    });

    expect(fetch).toHaveBeenCalledTimes(1);
  });

  it("only retries when the user explicitly clicks retry", async () => {
    const user = userEvent.setup();
    mockFetchRejectOnce(new TypeError("Failed to fetch"));

    renderHealthPage();

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/unable to reach the backend/i);
    });

    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValueOnce(jsonResponse({ status: "UP", service: "backend" })),
    );

    await user.click(screen.getByRole("button", { name: /retry/i }));

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/healthy/i);
    });
  });

  it("adds exactly one more fetch per explicit retry click, never more", async () => {
    const user = userEvent.setup();
    mockFetchRejectOnce(new TypeError("Failed to fetch"));

    renderHealthPage();

    await waitFor(() => {
      expect(screen.getByRole("status")).toHaveTextContent(/unable to reach the backend/i);
    });
    expect(fetch).toHaveBeenCalledTimes(1);

    mockFetchRejectOnce(new TypeError("Failed to fetch"));
    await user.click(screen.getByRole("button", { name: /retry/i }));

    await waitFor(() => {
      // mockFetchRejectOnce stubs a fresh fetch mock, so this call count is
      // relative to that new mock — it must be exactly 1 (one retry, one call).
      expect(fetch).toHaveBeenCalledTimes(1);
    });
  });

  describe("StrictMode initial mount", () => {
    it("fetches exactly once on the first logical mount, even with StrictMode's setup->cleanup->setup", async () => {
      mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));

      render(
        <StrictMode>
          <HealthPage />
        </StrictMode>,
      );

      await waitFor(() => {
        expect(screen.getByRole("status")).toHaveTextContent(/healthy/i);
      });
      expect(fetch).toHaveBeenCalledTimes(1);
    });
  });

  describe("unmount and remount lifecycle", () => {
    it("does not update state after unmount when the in-flight request resolves late", async () => {
      const consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => undefined);
      const deferred = stubQueuedFetch();

      const { unmount } = render(<HealthPage />);
      unmount();

      deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();

      const stateUpdateWarnings = consoleErrorSpy.mock.calls.filter((call) =>
        String(call[0]).includes("update a component"),
      );
      expect(stateUpdateWarnings).toEqual([]);
    });

    it("does not update state after unmount when the in-flight request rejects late", async () => {
      const consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => undefined);
      const deferred = stubQueuedFetch();

      const { unmount } = render(<HealthPage />);
      unmount();

      deferred[0].reject(new TypeError("Failed to fetch"));
      await Promise.resolve();
      await Promise.resolve();
      await Promise.resolve();

      const stateUpdateWarnings = consoleErrorSpy.mock.calls.filter((call) =>
        String(call[0]).includes("update a component"),
      );
      expect(stateUpdateWarnings).toEqual([]);
    });

    it("starts a new fetch on a genuine remount after the previous instance's request already settled", async () => {
      mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));
      const first = render(<HealthPage />);
      await waitFor(() => {
        expect(screen.getByRole("status")).toHaveTextContent(/healthy/i);
      });
      first.unmount();

      mockFetchOnce(async () => jsonResponse({ status: "UP", service: "backend" }));
      render(<HealthPage />);

      await waitFor(() => {
        // mockFetchOnce stubs a fresh fetch mock, so this call count is
        // relative to that new mock — it must be exactly 1 (the fresh fetch
        // fired by the genuine remount, proving no permanent success cache).
        expect(fetch).toHaveBeenCalledTimes(1);
      });
      await waitFor(() => {
        expect(screen.getByRole("status")).toHaveTextContent(/healthy/i);
      });
    });

    it("shares one in-flight request across an unmount+remount race and only updates the surviving instance", async () => {
      const deferred = stubQueuedFetch();

      const first = render(<HealthPage />);
      first.unmount();
      // The request from the first (now unmounted) instance is still
      // in flight, so the remount below must reuse it rather than firing
      // a second network call.
      render(<HealthPage />);

      expect(fetch).toHaveBeenCalledTimes(1);

      deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));

      await waitFor(() => {
        expect(screen.getByRole("status")).toHaveTextContent(/healthy/i);
      });
      expect(fetch).toHaveBeenCalledTimes(1);
    });
  });

  describe("retry concurrency guards", () => {
    it("does not start an additional fetch from a second retry click while the first retry is still loading", async () => {
      const user = userEvent.setup();
      mockFetchRejectOnce(new TypeError("Failed to fetch"));

      renderHealthPage();
      await waitFor(() => {
        expect(screen.getByRole("status")).toHaveTextContent(/unable to reach the backend/i);
      });
      expect(fetch).toHaveBeenCalledTimes(1);

      const deferred = stubQueuedFetch();
      await user.click(screen.getByRole("button", { name: /retry/i }));
      expect(fetch).toHaveBeenCalledTimes(1);
      expect(screen.getByRole("status")).toHaveTextContent(/checking backend health/i);

      // The retry button is not rendered while loading, so there is no
      // element to click again — this proves the UI itself blocks the
      // duplicate action, in addition to the hook-level guard.
      expect(screen.queryByRole("button", { name: /retry/i })).not.toBeInTheDocument();

      deferred[0].resolve(jsonResponse({ status: "UP", service: "backend" }));
      await waitFor(() => {
        expect(screen.getByRole("status")).toHaveTextContent(/healthy/i);
      });
      expect(fetch).toHaveBeenCalledTimes(1);
    });
  });
});
