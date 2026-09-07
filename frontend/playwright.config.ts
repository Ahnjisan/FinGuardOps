import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, realpathSync, rmSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, delimiter, dirname, join, resolve } from "node:path";
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
// this directory in its finally block; this hook covers direct execution.
process.once("exit", () => {
  rmSync(ownedOutputDirectory, { recursive: true, force: true });
});

/**
 * The isolated Chromium this suite must run against.
 *
 * Required, not optional. Falling back to a browser launched on this machine
 * would mean falling back to whatever that machine happens to trust, which is
 * exactly the coupling the runner exists to remove: the browser is a pinned
 * Playwright Linux image with a per-run NSS database holding only the
 * `localhost` leaf, and there is no second way to run these tests.
 */
const browserWsEndpoint = env.FINGUARDOPS_E2E_BROWSER_WS;
if (!browserWsEndpoint || !/^ws:\/\/127\.0\.0\.1:[0-9]{1,5}\/$/.test(browserWsEndpoint)) {
  throw new Error(
    "FINGUARDOPS_E2E_BROWSER_WS must name the isolated browser server on loopback. " +
      "Run frontend/scripts/run-keycloak-e2e.ps1 rather than Playwright directly.",
  );
}

const frontendRoot = import.meta.dirname;

/**
 * The Vite this checkout installs. Pinned here as well as in `package.json`
 * because the web server below is started from an installed file rather than
 * from a package name, and a file is only the right file if the package around
 * it is the expected version.
 */
const EXPECTED_VITE_VERSION = "8.2.2";

function readInstalledVersion(packageDirectory: string): string | undefined {
  const manifest = join(packageDirectory, "package.json");
  if (!existsSync(manifest)) {
    return undefined;
  }
  const parsed: unknown = JSON.parse(readFileSync(manifest, "utf8"));
  if (typeof parsed !== "object" || parsed === null) {
    return undefined;
  }
  const version = (parsed as { version?: unknown }).version;
  return typeof version === "string" ? version : undefined;
}

const viteDirectory = join(frontendRoot, "node_modules", "vite");
if (readInstalledVersion(viteDirectory) !== EXPECTED_VITE_VERSION) {
  throw new Error(
    `vite ${EXPECTED_VITE_VERSION} is not installed under frontend/node_modules. Run npm ci in frontend.`,
  );
}
if (!existsSync(join(viteDirectory, "bin", "vite.js"))) {
  throw new Error("The installed vite carries no CLI entry point. Run npm ci in frontend.");
}

/**
 * The `node` a shell would find, if it is this very interpreter.
 *
 * The web server below is spawned through a shell, which is the one place in
 * this suite where a program is named rather than handed over as a path. A name
 * is resolved by whatever is first on `PATH`, so the name is only safe once it
 * has been shown to resolve to the interpreter already running this
 * configuration. If it does not, the run stops instead of quietly starting the
 * application under a different Node than the one this repository pins.
 */
function resolveNodeOnPath(): string | undefined {
  const searchPath = env.PATH ?? env.Path ?? "";
  const extensions =
    process.platform === "win32"
      ? (env.PATHEXT ?? ".COM;.EXE;.BAT;.CMD").split(";").filter((extension) => extension !== "")
      : [""];
  for (const directory of searchPath.split(delimiter)) {
    if (directory === "") {
      continue;
    }
    for (const extension of extensions) {
      const candidate = join(directory, `node${extension}`);
      try {
        if (statSync(candidate).isFile()) {
          return candidate;
        }
      } catch {
        // Not this directory. The loop owns the answer.
      }
    }
  }
  return undefined;
}

function isSamePath(left: string, right: string): boolean {
  return process.platform === "win32"
    ? left.toLowerCase() === right.toLowerCase()
    : left === right;
}

const nodeOnPath = resolveNodeOnPath();
if (nodeOnPath === undefined || !isSamePath(realpathSync(nodeOnPath), realpathSync(process.execPath))) {
  throw new Error(
    "The first `node` on PATH is not the interpreter running this configuration. " +
      "Run frontend/scripts/run-keycloak-e2e.ps1 from a shell whose Node is the installed one.",
  );
}

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
        // Every page in this suite is driven in the isolated container.
        connectOptions: { wsEndpoint: browserWsEndpoint },
      },
    },
  ],
  webServer: {
    // The installed Vite entry point, run by Node. Not `npm run dev`: an npm
    // script adds a script lookup, lifecycle hooks and, for anything it fails
    // to resolve, a registry, none of which belong in a run whose contract is
    // that it fetches nothing.
    //
    // Every element of this command line is a bare ASCII token. The path is
    // relative to `cwd` below, so the checkout's own absolute location - which
    // on Windows routinely contains spaces and non-ASCII characters - never
    // enters a string a shell will parse, and there is nothing here to quote,
    // escape or get wrong.
    command: "node node_modules/vite/bin/vite.js --host 127.0.0.1 --port 5173 --strictPort",
    cwd: frontendRoot,
    // Bound to the IPv4 loopback explicitly. `localhost` can resolve to `::1`
    // first on Windows, and the container reaches this host through its IPv4
    // gateway, so a v6-only bind would answer nothing at all. The browser still
    // addresses the application as `http://localhost:5173`, which is what the
    // OIDC redirect allowlist names.
    url: "http://127.0.0.1:5173",
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
