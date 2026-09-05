import { randomUUID } from "node:crypto";
import { mkdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, dirname, join, resolve } from "node:path";
import process, { env } from "node:process";
import { defineConfig, devices } from "@playwright/test";

const ownedOutputDirectory =
  env.FINGUARDOPS_E2E_OUTPUT_DIR ??
  join(tmpdir(), `finguardops-keycloak-playwright-${randomUUID()}`);

if (
  dirname(resolve(ownedOutputDirectory)) !== resolve(tmpdir()) ||
  !basename(ownedOutputDirectory).startsWith("finguardops-")
) {
  throw new Error("The Playwright output directory is not an owned temporary path.");
}
mkdirSync(ownedOutputDirectory, { recursive: true });

// No report, trace, screenshot or video is retained. The runner also removes
// this directory in its finally block; this hook covers direct npm execution.
process.once("exit", () => {
  rmSync(ownedOutputDirectory, { recursive: true, force: true });
});

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  timeout: 60_000,
  workers: 1,
  retries: 0,
  reporter: [["line"]],
  outputDir: ownedOutputDirectory,
  preserveOutput: "never",
  use: {
    baseURL: "http://localhost:5173",
    ignoreHTTPSErrors: false,
    trace: "off",
    screenshot: "off",
    video: "off",
  },
  projects: [
    {
      name: "chromium",
      use: {
        ...devices["Desktop Chrome"],
        browserName: "chromium",
      },
    },
  ],
  webServer: {
    command: "npm run dev -- --host localhost --port 5173 --strictPort",
    url: "http://localhost:5173",
    reuseExistingServer: false,
    timeout: 60_000,
    stdout: "ignore",
    stderr: "pipe",
    env: {
      ...env,
      VITE_API_BASE_URL: "http://localhost:8080",
      VITE_OIDC_AUTHORITY: "https://localhost:8443/realms/finguardops-local",
      VITE_OIDC_CLIENT_ID: "finguardops-frontend",
    },
  },
});
