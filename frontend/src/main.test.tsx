import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, screen, waitFor } from "@testing-library/react";

function setUpRootElement(): void {
  document.body.innerHTML = '<div id="root"></div>';
}

beforeEach(() => {
  setUpRootElement();
});

afterEach(() => {
  vi.unstubAllEnvs();
  vi.resetModules();
  vi.restoreAllMocks();
  document.body.innerHTML = "";
});

describe("application entry (bootstrap)", () => {
  it("fails fast on import when VITE_API_BASE_URL is missing, before any render", async () => {
    vi.stubEnv("VITE_API_BASE_URL", "");

    await expect(import("./main")).rejects.toThrow();

    expect(document.getElementById("root")?.childElementCount).toBe(0);
  });

  it("fails fast on import when VITE_API_BASE_URL is invalid, before any render", async () => {
    vi.stubEnv("VITE_API_BASE_URL", "not-a-valid-url");

    await expect(import("./main")).rejects.toThrow();

    expect(document.getElementById("root")?.childElementCount).toBe(0);
  });

  it("validates configuration before checking for the root element", async () => {
    document.body.innerHTML = "";
    vi.stubEnv("VITE_API_BASE_URL", "not-a-valid-url");

    const error = await import("./main").catch((caught: unknown) => caught);

    expect((error as Error).name).toBe("EnvConfigError");
  });

  it("renders the application when configuration is valid", async () => {
    vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");

    await act(async () => {
      await import("./main");
    });

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    });
  });

  it("runs environment validation exactly once on a valid start", async () => {
    vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");
    const envModule = await import("./config/env");
    const getEnvSpy = vi.spyOn(envModule, "getEnv");

    await act(async () => {
      await import("./main");
    });

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: /finguardops frontend/i })).toBeInTheDocument();
    });
    expect(getEnvSpy).toHaveBeenCalledTimes(1);
  });

  it("does not include the raw invalid input in the thrown error", async () => {
    const secretLikeValue = "not-a-valid-url-secret-token-xyz";
    vi.stubEnv("VITE_API_BASE_URL", secretLikeValue);

    const error = await import("./main").catch((caught: unknown) => caught);

    expect((error as Error).message).not.toContain(secretLikeValue);
  });

  it("throws the standard 'root element not found' error only when configuration is already valid", async () => {
    document.body.innerHTML = "";
    vi.stubEnv("VITE_API_BASE_URL", "http://localhost:8080");

    const error = await import("./main").catch((caught: unknown) => caught);

    expect((error as Error).message).toBe("Root element not found.");
  });
});
