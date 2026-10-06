import process from "node:process";
import type {
  FullResult,
  Reporter,
  Suite,
  TestCase,
  TestError,
  TestResult,
  TestStep,
} from "@playwright/test/reporter";

/**
 * A fixed-field failure record for the Keycloak browser E2E run.
 *
 * The line reporter's output carries test titles, error messages, stacks,
 * rendered text and request addresses, so the runner throws all of it away.
 * What this reporter writes instead is a closed set of records that name *where*
 * a failure happened and *what kind* of failure it was, and nothing else:
 *
 *   FINGUARDOPS_E2E_PW_V1 <nonce> TEST line=<n|none> n=<n> status=<s> kind=<k> at=<n|none> stage=<fixed|none>
 *   FINGUARDOPS_E2E_PW_V1 <nonce> GLOBAL kind=<WEBSERVER|OTHER>
 *   FINGUARDOPS_E2E_PW_V1 <nonce> SUMMARY status=<s> passed=<n> failed=<n> skipped=<n>
 *   FINGUARDOPS_E2E_PW_V1 <nonce> OVERFLOW
 *
 * `line` is the spec line that declares the test and `n` its ordinal among
 * tests declared on that line (the tampering matrices declare several). `at`
 * is the spec line of the failing assertion. `stage` is the first failed fixed
 * step, or the last entered fixed step if no step error was reported. Line numbers are positions in a
 * checked-in source file; no title, message, stack, URL, DOM text, credential
 * or token is ever written, and an error's text is read only to choose `kind`.
 *
 * The nonce is a per-run value the runner hands to this process alone. The
 * reporter removes it from the environment as soon as it is constructed, which
 * is before the web server or any worker is started, so ordinary test or web
 * server output cannot reproduce a record the runner would accept.
 *
 * Nothing here can change the run's verdict: `onEnd` returns no status, and
 * every hook swallows its own failure. A missing or malformed nonce disables
 * the reporter entirely.
 */
export const SAFE_FAILURE_MARKER_PREFIX = "FINGUARDOPS_E2E_PW_V1";

/** Deliberately repeated in `playwright.config.ts`; neither file imports the other. */
const REPORTER_NONCE_ENVIRONMENT = "FINGUARDOPS_E2E_REPORTER_NONCE";
const NONCE_PATTERN = /^[0-9a-f]{32}$/;
const SPEC_FILE = "keycloak-user-login.spec.ts";
const ADMIN_TEST_TITLE = "a PLATFORM_ADMIN reviews the stored AI request usage without case authority";
const ASSERTION_HELPER = "requireCondition";
const MAX_TEST_RECORDS = 32;
const MAX_GLOBAL_RECORDS = 4;
const MAX_LINE = 99_999;
const MAX_ORDINAL = 999;
const MAX_COUNT = 9_999;
const FLUSH_WAIT_MILLISECONDS = 1_000;
// ESC [ ... final byte: enough to read an error's first line for `kind` only.
// eslint-disable-next-line no-control-regex
const ANSI_SEQUENCE = /\u001b\[[0-9;]*[A-Za-z]/g;
const STACK_FRAME = /^\s*at (?:(.*?) \()?(.+?):(\d+):(\d+)\)?$/;
const ADMIN_STAGES = [
  "RELAY_INIT", "GUARD", "LOGIN", "USAGE_API", "USAGE_UI",
  "DETAIL_API", "DETAIL_UI", "CLEANUP",
] as const;
type AdminStage = typeof ADMIN_STAGES[number];
const ADMIN_STAGE_SET: ReadonlySet<string> = new Set(ADMIN_STAGES);

type FailureStatus = "failed" | "timedOut" | "interrupted";
type FailureKind = "REQUIRE_CONDITION" | "EXPECT" | "TIMEOUT" | "INTERRUPTED" | "OTHER";

export interface SafeFailureReporterOptions {
  readonly nonce?: unknown;
}

function fileName(path: string): string {
  const segments = path.split(/[\\/]/);
  return segments[segments.length - 1] ?? "";
}

function isSpecFile(path: string | undefined): boolean {
  return path !== undefined && fileName(path) === SPEC_FILE;
}

function boundedLine(line: number | undefined): string {
  return line !== undefined && Number.isSafeInteger(line) && line >= 1 && line <= MAX_LINE
    ? String(line)
    : "none";
}

function boundedCount(count: number): string {
  return String(Math.min(Math.max(0, Math.trunc(count)), MAX_COUNT));
}

/**
 * The spec line of the assertion that failed: the first spec frame that is not
 * the shared assertion helper, so a `requireCondition` failure names its caller
 * rather than the helper's own `throw`.
 */
function failingSpecLine(error: TestError | undefined): string {
  if (error === undefined) {
    return "none";
  }
  for (const frame of (error.stack ?? "").split("\n")) {
    const parsed = STACK_FRAME.exec(frame);
    if (parsed === null || !isSpecFile(parsed[2])) {
      continue;
    }
    const functionName = parsed[1] ?? "";
    if (functionName === ASSERTION_HELPER || functionName.endsWith(`.${ASSERTION_HELPER}`)) {
      continue;
    }
    return boundedLine(Number(parsed[3]));
  }
  return isSpecFile(error.location?.file) ? boundedLine(error.location?.line) : "none";
}

function failureKind(status: FailureStatus, error: TestError | undefined): FailureKind {
  if (status === "timedOut") {
    return "TIMEOUT";
  }
  if (status === "interrupted") {
    return "INTERRUPTED";
  }
  if (error === undefined) {
    return "OTHER";
  }
  const stack = error.stack ?? "";
  if (
    stack
      .split("\n")
      .some((frame) => {
        const parsed = STACK_FRAME.exec(frame);
        const functionName = parsed?.[1] ?? "";
        return (
          parsed !== null &&
          isSpecFile(parsed[2]) &&
          (functionName === ASSERTION_HELPER || functionName.endsWith(`.${ASSERTION_HELPER}`))
        );
      })
  ) {
    return "REQUIRE_CONDITION";
  }
  const firstLine = (error.message ?? "").replace(ANSI_SEQUENCE, "").split("\n")[0] ?? "";
  return /\bexpect\(/.test(firstLine) ? "EXPECT" : "OTHER";
}

export default class SafeFailureReporter implements Reporter {
  private readonly nonce: string | null;
  private readonly ordinals = new Map<string, number>();
  private readonly stages = new Map<string, { last: AdminStage; failed?: AdminStage }>();
  private testRecords = 0;
  private globalRecords = 0;
  private overflowed = false;
  private passed = 0;
  private failed = 0;
  private skipped = 0;

  constructor(options: SafeFailureReporterOptions = {}) {
    this.nonce =
      typeof options.nonce === "string" && NONCE_PATTERN.test(options.nonce) ? options.nonce : null;
    try {
      delete process.env[REPORTER_NONCE_ENVIRONMENT];
    } catch {
      // The nonce only ever authenticates this reporter's own records.
    }
  }

  printsToStdio(): boolean {
    return false;
  }

  onBegin(_config: unknown, suite: Suite): void {
    try {
      const perLine = new Map<string, number>();
      for (const test of suite.allTests()) {
        const key = `${test.location.file}:${String(test.location.line)}`;
        const ordinal = (perLine.get(key) ?? 0) + 1;
        perLine.set(key, ordinal);
        this.ordinals.set(test.id, ordinal);
      }
    } catch {
      // Ordinals are a convenience; a failure leaves them at `1`.
    }
  }

  onStepBegin(test: TestCase, _result: TestResult, step: TestStep): void {
    try {
      if (isSpecFile(test.location.file) && test.title === ADMIN_TEST_TITLE && ADMIN_STAGE_SET.has(step.title)) {
        const previous = this.stages.get(test.id);
        this.stages.set(test.id, { last: step.title as AdminStage, failed: previous?.failed });
      }
    } catch {
      // A diagnostic never replaces the test's verdict.
    }
  }

  onStepEnd(test: TestCase, _result: TestResult, step: TestStep): void {
    try {
      const current = this.stages.get(test.id);
      if (current !== undefined && current.failed === undefined &&
          ADMIN_STAGE_SET.has(step.title) && step.error !== undefined) {
        current.failed = step.title as AdminStage;
      }
    } catch {
      // A diagnostic never replaces the test's verdict.
    }
  }

  onTestEnd(test: TestCase, result: TestResult): void {
    try {
      if (result.status === "passed") {
        this.passed += 1;
        return;
      }
      if (result.status === "skipped") {
        this.skipped += 1;
        return;
      }
      this.failed += 1;
      if (this.testRecords >= MAX_TEST_RECORDS) {
        this.overflow();
        return;
      }
      this.testRecords += 1;
      const status: FailureStatus = result.status;
      const error = result.errors[0];
      const line = isSpecFile(test.location.file) ? boundedLine(test.location.line) : "none";
      const ordinal = Math.min(this.ordinals.get(test.id) ?? 1, MAX_ORDINAL);
      const stage = this.stages.get(test.id);
      this.write(
        `TEST line=${line} n=${String(ordinal)} status=${status} ` +
          `kind=${failureKind(status, error)} at=${failingSpecLine(error)} stage=${stage?.failed ?? stage?.last ?? "none"}`,
      );
    } catch {
      // A diagnostic never replaces the failure it describes.
    } finally {
      this.stages.delete(test.id);
    }
  }

  onError(error: TestError): void {
    try {
      if (this.globalRecords >= MAX_GLOBAL_RECORDS) {
        this.overflow();
        return;
      }
      this.globalRecords += 1;
      const kind = (error.message ?? "").includes("config.webServer") ? "WEBSERVER" : "OTHER";
      this.write(`GLOBAL kind=${kind}`);
    } catch {
      // A diagnostic never replaces the failure it describes.
    }
  }

  async onEnd(result: FullResult): Promise<void> {
    try {
      const record =
        `SUMMARY status=${result.status} passed=${boundedCount(this.passed)} ` +
        `failed=${boundedCount(this.failed)} skipped=${boundedCount(this.skipped)}`;
      // Waits for the record to leave this process, but never for long: a
      // stream that will not report a flush must not hold the run open.
      await new Promise<void>((resolve) => {
        setTimeout(resolve, FLUSH_WAIT_MILLISECONDS).unref();
        if (!this.write(record, resolve)) {
          resolve();
        }
      });
    } catch {
      // The verdict is the runner's exit code, never this record.
    }
  }

  private overflow(): void {
    if (!this.overflowed) {
      this.overflowed = true;
      this.write("OVERFLOW");
    }
  }

  /** Starts on a fresh line, because the line reporter leaves its cursor mid-line. */
  private write(record: string, flushed?: () => void): boolean {
    if (this.nonce === null) {
      return false;
    }
    try {
      process.stdout.write(`\n${SAFE_FAILURE_MARKER_PREFIX} ${this.nonce} ${record}\n`, () => {
        flushed?.();
      });
      return true;
    } catch {
      return false;
    }
  }
}
