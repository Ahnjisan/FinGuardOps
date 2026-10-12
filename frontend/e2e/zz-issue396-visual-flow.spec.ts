/** Issue #396 capture: only real Keycloak USER pages are saved. */
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";
import { env } from "node:process";
import { runInNewContext } from "node:vm";
import { expect, test } from "@playwright/test";
import * as ts from "typescript";
import { isAdoptedDetectionResponse } from "../src/api/adoptedDetectionApi";
import * as validation from "../src/api/responseValidation";
import { isCaseDashboard, isTransactionDashboard } from "../src/api/fdsDashboardApi";

const ROOT = resolve(import.meta.dirname, "../..");
const SHA = env.FINGUARDOPS_E2E_REVISION ?? "";
const RUN = env.FINGUARDOPS_E2E_RUN_ID ?? "";
const DIR = resolve(ROOT, "backend", "build", `visual-capture-${RUN}`);
const SCRIPT = resolve(ROOT, "infra", "local-jwt-fixture", "verify_issue396_visual.py");
const APP = "http://localhost:5173";
const TOKEN = "https://localhost:8443/realms/finguardops-local/protocol/openid-connect/token";
const UUID = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
const readAllowed = new RegExp(`^/api/v1/(?:transactions(?:/${UUID}(?:/adopted-detection-result)?)?|cases(?:/${UUID}(?:/(?:audit-logs|notes|transactions|ai-reports/current))?)?|fds-dashboard/(?:transactions|cases))$`);
const caseStatusWrite = new RegExp(`^/api/v1/cases/${UUID}/status$`);
const reportWrite = new RegExp(`^/api/v1/cases/${UUID}/ai-reports$`);
const RULES: Record<string, string> = {
  SCN001: "RECENT_DEVICE_REGISTRATION_HIGH_AMOUNT",
  SCN002: "RECENT_SECURITY_CHANGE_HIGH_AMOUNT",
  SCN003: "EXTERNAL_SUSPICIOUS_RECIPIENT_ACCOUNT",
  SCN004: "DISTRIBUTED_OUTBOUND_WINDOW",
  SCN005: "MANY_CUSTOMER_INBOUND_WINDOW",
  SCN006: "VERIFIED_INBOUND_ATM_DRAIN",
  SCN007: "VERIFIED_LOAN_NEW_RECIPIENT_DRAIN",
  SCN008: "VERIFIED_OPEN_BANKING_CONCENTRATION_RESEND",
};

function diagnose(result: Record<string, unknown>): Record<string, unknown> {
  const source = readFileSync(resolve(ROOT, "frontend/src/api/adoptedDetectionApi.ts"), "utf8") +
    "\nexport const __probe = { isAdopted, isRule, isScoreGroup, isScenario, isAggregation, isFundFlow, isOpenBanking };";
  const compiled = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022,
  } }).outputText;
  const exports: Record<string, unknown> = {};
  runInNewContext(compiled, { exports, require: (name: string) => {
    if (name === "./responseValidation") return validation;
    if (name === "./authorizedClient") return { sendAuthorizedBackendRequest: () => undefined };
    throw new Error("diagnostic import invalid");
  } });
  const checks = exports.__probe as Record<string, (value: unknown) => boolean>;
  const adopted = result.adoptedResult as Record<string, unknown>;
  const groups = Array.isArray(adopted.scoreGroupSummary) ? adopted.scoreGroupSummary : [];
  const rules = Array.isArray(adopted.ruleEvidence) ? adopted.ruleEvidence : [];
  return {
    validator: isAdoptedDetectionResponse(result), adopted: checks.isAdopted(adopted),
    rule: rules.map(checks.isRule), scoreGroup: groups.map(checks.isScoreGroup),
    scenario: checks.isScenario(adopted.scn001002Evidence),
    aggregation: checks.isAggregation(adopted.aggregationEvidence),
    fundFlow: checks.isFundFlow(adopted.fundFlowEvidence),
    openBanking: checks.isOpenBanking(adopted.openBankingEvidence),
    mlFeatureVersionMatch: adopted.mlFeatureVersion === "fraud-feature-v5",
    mlEvidenceLengthOne: Array.isArray(adopted.mlEvidence) && adopted.mlEvidence.length === 1,
    mlEvidenceContributionMatch: Array.isArray(adopted.mlEvidence) &&
      (adopted.mlEvidence[0] as Record<string, unknown>)?.scoreContribution === adopted.mlRawContribution,
    modelDigestShape: typeof adopted.modelSha256 === "string" && /^[0-9a-f]{64}$/.test(adopted.modelSha256),
  };
}

function fixture(phase: "generate" | "validate" | "relay" | "verify-report" |
  "ai-invariant-snapshot" | "dashboard-status", input?: object): Promise<unknown> {
  return new Promise((resolveDone, reject) => {
    const child = spawn("python", ["-B", SCRIPT, phase], {
      cwd: ROOT, env, stdio: ["pipe", "pipe", "pipe"], windowsHide: true,
    });
    const stdout: Buffer[] = [];
    child.stdout.on("data", (chunk: Buffer) => stdout.push(chunk));
    child.stderr.resume(); // No Secret, token, or raw failure body reaches the test log.
    child.on("error", () => reject(new Error(`visual ${phase} process start failed`)));
    child.on("close", (code) => {
      if (code === 0) {
        if (phase === "relay" || phase === "dashboard-status" || phase === "ai-invariant-snapshot") {
          try { resolveDone(JSON.parse(Buffer.concat(stdout).toString("utf8"))); }
          catch { reject(new Error("visual relay output invalid")); }
        } else resolveDone(undefined);
      }
      else reject(new Error(`visual ${phase} failed with exit ${String(code)}`));
    });
    child.stdin.end(input === undefined ? "" : JSON.stringify(input));
  });
}

interface Entry {
  scenario: string;
  body: { transactionId: string };
  response: { caseId: string; adoptedDetectionResultId: string };
  stored: { risk: string; score: number; status: string };
}
interface Evidence {
  runId: string;
  commitSha: string;
  date: string;
  entries: Entry[];
  dashboard: { total: number; classified: number; newCases: number };
  scenarios: Record<string, number>;
  scenarioIds: Record<string, string[]>;
  scn002Exclusions: Array<{ transactionId: string; failedPredicates: string[] }>;
}

test.skip(env.FINGUARDOPS_ISSUE396_VISUAL !== "1", "Dedicated Issue #396 visual run only");
test("SCN-001~008 real Keycloak visual capture", async ({ page }) => {
  test.setTimeout(1_500_000);
  if (!/^[0-9a-f]{32}$/.test(RUN)) throw new Error("visual owner run ID invalid");
  mkdirSync(DIR, { recursive: true });
  const mark = (stage: string) => writeFileSync(resolve(DIR, "progress.json"),
    JSON.stringify({ runId: RUN, stage }) + "\n", "utf8");
  mark("fixture-generate-start");
  await fixture("generate");
  mark("fixture-generated");
  const evidence = JSON.parse(readFileSync(resolve(DIR, "fixtures.json"), "utf8")) as Evidence;
  if (evidence.commitSha !== SHA || evidence.runId !== RUN || evidence.entries.length !== 8) {
    throw new Error("visual fixture identity invalid");
  }
  for (const code of Object.keys(RULES)) {
    const ids = evidence.scenarioIds[code];
    const target = evidence.entries.find(entry => entry.scenario === code)?.body.transactionId;
    if (!Array.isArray(ids) || new Set(ids).size !== ids.length ||
        ids.length !== evidence.scenarios[code] || !target || !ids.includes(target)) {
      throw new Error(`${code} contract DB identity set invalid`);
    }
  }

  // The isolated Chromium reaches real Vite and stock Keycloak. Only the
  // production Backend API is relayed from its loopback to this host's loopback.
  const invalidResponses = new Set<string>();
  let lastTransactionDashboard: Record<string, unknown> | null = null;
  let lastCaseDashboard: Record<string, unknown> | null = null;
  await page.route("http://localhost:8080/**", async (route) => {
    const original = route.request();
    const url = new URL(original.url());
    if (original.method() === "OPTIONS") {
      await route.fulfill({ status: 204, headers: {
        "access-control-allow-origin": APP,
        "access-control-allow-methods": "GET, POST, PATCH, OPTIONS",
        "access-control-allow-headers": "authorization, content-type, idempotency-key",
      } });
      return;
    }
    const permitted = (original.method() === "GET" && readAllowed.test(url.pathname)) ||
      (original.method() === "PATCH" && caseStatusWrite.test(url.pathname)) ||
      (original.method() === "POST" && reportWrite.test(url.pathname));
    if (!permitted) {
      appendFileSync(resolve(DIR, "api-calls.jsonl"), JSON.stringify({
        method: original.method(), path: url.pathname.replace(new RegExp(UUID, "g"), "{id}"), status: "blocked" }) + "\n");
      await route.abort(); return;
    }
    const actual = await fixture("relay", { method: original.method(),
      path: `${url.pathname}${url.search}`, headers: original.headers(),
      body: original.postData() }) as { status: number; body: string };
    if (url.pathname.endsWith("/adopted-detection-result") && actual.status === 200) {
      const result = JSON.parse(actual.body) as Record<string, unknown>;
      if (!isAdoptedDetectionResponse(result) && typeof result.transactionId === "string") {
        invalidResponses.add(result.transactionId);
        const scenario = evidence.entries.find(entry => entry.body.transactionId === result.transactionId)?.scenario;
        const window = (result.adoptedResult as Record<string, unknown> | null)?.aggregationEvidence as
          Record<string, unknown> | undefined;
        const fundFlow = (result.adoptedResult as Record<string, unknown> | null)?.fundFlowEvidence as
          Record<string, unknown> | undefined;
        const openBanking = (result.adoptedResult as Record<string, unknown> | null)?.openBankingEvidence as
          Record<string, unknown> | undefined;
        const checks = diagnose(result);
        const firstFailedValidator = checks.aggregation === false ? "aggregationEvidence" :
          checks.fundFlow === false ? "fundFlowEvidence" :
            checks.openBanking === false ? "openBankingEvidence" : "adoptedResult.other";
        const amount = firstFailedValidator === "aggregationEvidence" ?
          (window?.outbound as Record<string, unknown> | undefined)?.totalAmount :
          firstFailedValidator === "fundFlowEvidence" ?
            ((fundFlow?.atm as Record<string, unknown> | undefined)?.status === "AVAILABLE" ?
              (fundFlow?.atm as Record<string, unknown>).sourceAmount :
              (fundFlow?.loan as Record<string, unknown> | undefined)?.sourceAmount) :
            openBanking?.concentrationAmount;
        appendFileSync(resolve(DIR, "validator-diagnostic.jsonl"), JSON.stringify({
          scenario: scenario ?? "UNLINKED", checks,
          firstFailedValidator, amountCandidate: firstFailedValidator === "adoptedResult.other" ?
            null : firstFailedValidator + ".amount",
          expected: "integral KRW string; optional 1-4 zero fractional digits",
          actualType: typeof amount,
          actualFractionDigits: typeof amount === "string" && amount.includes(".") ?
            amount.length - amount.indexOf(".") - 1 : 0,
          actualIntegral: typeof amount === "string" && /^\d+(?:\.0+)?$/.test(amount),
        }) + "\n");
      }
    }
    if (url.pathname.startsWith("/api/v1/fds-dashboard/") && actual.status === 200) {
      const body = JSON.parse(actual.body) as Record<string, unknown>;
      if (url.pathname.endsWith("/transactions")) lastTransactionDashboard = body;
      if (url.pathname.endsWith("/cases")) lastCaseDashboard = body;
      appendFileSync(resolve(DIR, "dashboard-shapes.jsonl"), JSON.stringify({
        path: url.pathname, valid: url.pathname.endsWith("/transactions") ?
          isTransactionDashboard(body) : isCaseDashboard(body),
        keys: Object.keys(body).sort(),
        from: body.from, to: body.to,
        dates: Array.isArray(body.days) ? body.days.map((day: { date: string }) => day.date) : [],
      }) + "\n");
    }
    appendFileSync(resolve(DIR, "api-calls.jsonl"), JSON.stringify({
      method: original.method(), path: url.pathname.replace(new RegExp(UUID, "g"), "{id}"),
      status: actual.status }) + "\n");
    await route.fulfill({ status: actual.status, body: actual.body,
      headers: { "content-type": "application/json", "access-control-allow-origin": APP } });
  });

  await page.goto(APP + "/");
  const tokenResponse = page.waitForResponse(response => response.url() === TOKEN &&
    response.request().method() === "POST", { timeout: 45_000 });
  await page.getByRole("button", { name: "로그인" }).click();
  await expect(page.locator("#username")).toBeVisible({ timeout: 45_000 });
  await page.locator("#username").fill("local-fds-analyst");
  const password = readFileSync(resolve(ROOT, "infra/keycloak/.local/secrets/user-password"), "ascii").trim();
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  const tokens = await (await tokenResponse).json() as { access_token: string };
  if (typeof tokens.access_token !== "string" || tokens.access_token.split(".").length !== 3) {
    throw new Error("Keycloak USER token missing");
  }
  await expect(page.getByLabel("인증 상태")).toContainText("님으로 로그인했습니다.");
  mark("keycloak-user-logged-in");
  await fixture("validate", { analystToken: tokens.access_token });
  mark("api-db-validated");

  const openCase = async (caseId: string) => {
    await page.getByRole("link", { name: "사건", exact: true }).click();
    await page.getByRole("link", { name: `사건 ${caseId} 상세 보기` }).click();
  };
  const index: string[] = ["# Issue #396 최종 코드 실제 화면 캡처", "",
    `- 실행 SHA: \`${SHA}\``, `- 실행 ID: \`${RUN}\``,
    `- KST 날짜: \`${evidence.date}\``,
    "- 인증: stock Keycloak의 실제 `local-fds-analyst` USER / 격리 Chromium",
    "- 고객·계좌·기기 참조값은 합성값이며 거래 화면에서 가려 저장했습니다.",
    "- External Risk와 Ollama는 로컬 모의 Provider입니다. 실제 Qwen 증거가 아닙니다.", "",
    "| 시나리오 | 화면 | 거래 ID | 탐지 ID | 사건 ID | 이미지 |",
    "| --- | --- | --- | --- | --- | --- |"];
  for (const entry of evidence.entries) {
    const code = entry.scenario;
    const tx = entry.body.transactionId;
    const det = entry.response.adoptedDetectionResultId;
    const caseId = entry.response.caseId;
    if (!RULES[code] || !tx || !det || !caseId) throw new Error("visual entry invalid");
    mark(`${code}-opening-case`);
    await openCase(caseId);
    await expect(page.getByRole("heading", { name: `사건 ${caseId}`, level: 2 })).toBeVisible();
    await expect(page.getByRole("heading", { name: "감사 이력", level: 3 })).toBeVisible();
    await expect(page.locator("article.audit__entry").first()).toBeVisible();
    await expect(page.getByRole("link", { name: `거래 ${tx} 상세 보기` })).toBeVisible();
    const caseImage = `${code}-case.png`;
    await page.screenshot({ path: resolve(DIR, caseImage), fullPage: true,
      mask: [page.locator('section[aria-labelledby="case-summary-heading"] dd.facts__ref').last()],
      maskColor: "#26313a" });
    mark(`${code}-case-captured`);
    index.push(`| ${code} | 연결 사건·Audit | \`${tx}\` | \`${det}\` | \`${caseId}\` | [PNG](${caseImage}) |`);

    mark(`${code}-opening-transaction`);
    await page.getByRole("link", { name: `거래 ${tx} 상세 보기` }).click();
    await expect(page.getByRole("heading", { name: `거래 ${tx}`, level: 2 })).toBeVisible();
    mark(`${code}-transaction-heading-visible`);
    const adopted = page.locator(".adopted-detection");
    if (invalidResponses.has(tx)) {
      await expect(adopted).toContainText("응답을 안전하게 표시할 수 없습니다.");
      const failureImage = `${code}-transaction-error.png`;
      await page.screenshot({ path: resolve(DIR, failureImage), fullPage: true,
        mask: [page.locator('section[aria-labelledby="transaction-parties-heading"] dd')],
        maskColor: "#26313a" });
      index.push(`| ${code} | 거래 상세 오류: 탐지 근거 미표시 | \`${tx}\` | \`${det}\` | \`${caseId}\` | [PNG](${failureImage}) |`);
      mark(`${code}-transaction-error-captured`);
      throw new Error(`${code} adopted response rejected`);
    }
    try {
      await expect(adopted).toContainText(det, { timeout: 30_000 });
    } catch {
      writeFileSync(resolve(DIR, "screen-diagnostic.json"), JSON.stringify({
        scenario: code, check: "adopted detection ID visible", result: "failed",
      }) + "\n", "utf8");
      const failureImage = `${code}-transaction-error.png`;
      await page.screenshot({ path: resolve(DIR, failureImage),
        fullPage: true, mask: [page.locator('section[aria-labelledby="transaction-parties-heading"] dd')],
        maskColor: "#26313a" });
      index.push(`| ${code} | 거래 상세 오류: 탐지 근거 미표시 | \`${tx}\` | \`${det}\` | \`${caseId}\` | [PNG](${failureImage}) |`);
      mark(`${code}-transaction-error-captured`);
      throw new Error(`${code} adopted evidence not visible`);
    }
    mark(`${code}-detection-visible`);
    await expect(adopted).toContainText(entry.stored.risk);
    mark(`${code}-risk-visible`);
    await expect(adopted).toContainText(RULES[code]);
    mark(`${code}-rule-visible`);
    await expect(adopted).toContainText("ML 기여도");
    mark(`${code}-ml-visible`);
    const txImage = `${code}-transaction.png`;
    await page.screenshot({ path: resolve(DIR, txImage), fullPage: true,
      mask: [page.locator('section[aria-labelledby="transaction-parties-heading"] dd')],
      maskColor: "#26313a" });
    mark(`${code}-transaction-captured`);
    index.push(`| ${code} | 거래 상세·위험등급·Rule/ML | \`${tx}\` | \`${det}\` | \`${caseId}\` | [PNG](${txImage}) |`);
    if (code === "SCN003") {
      await page.setViewportSize({ width: 390, height: 844 });
      const listLink = page.getByRole("link", { name: "거래 목록으로" });
      const caseLink = page.getByRole("link", { name: "사건으로 돌아가기" });
      await expect(listLink).toBeVisible();
      await expect(caseLink).toBeVisible();
      const first = await listLink.boundingBox();
      const second = await caseLink.boundingBox();
      if (!first || !second || !(
        second.x >= first.x + first.width + 4 ||
        second.y >= first.y + first.height + 4
      )) {
        throw new Error("mobile return controls overlap or lack spacing");
      }
      await listLink.focus();
      await expect(listLink).toBeFocused();
      const navImage = "SCN003-navigation-mobile.png";
      await page.locator(".detail__navigation").screenshot({ path: resolve(DIR, navImage) });
      index.push(`| SCN003 | 작은 화면 보조 버튼·포커스 | \`${tx}\` | \`${det}\` | \`${caseId}\` | [PNG](${navImage}) |`);
      await page.setViewportSize({ width: 1440, height: 900 });
    }
  }

  const scn3 = evidence.entries.find(entry => entry.scenario === "SCN003")!;
  await openCase(scn3.response.caseId);
  await expect(page.getByRole("heading", { name: `사건 ${scn3.response.caseId}`, level: 2 })).toBeVisible();
  await page.getByRole("textbox", { name: "담당자 UUID", exact: true }).fill(randomUUID());
  await page.getByRole("button", { name: "검토 시작", exact: true }).click();
  await expect(page.locator('section[aria-labelledby="case-summary-heading"]')).toContainText("검토 중");
  const invariantInput = { analystToken: tokens.access_token,
    caseId: scn3.response.caseId, transactionId: scn3.body.transactionId,
    detectionResultId: scn3.response.adoptedDetectionResultId };
  const beforeAi = await fixture("ai-invariant-snapshot", invariantInput);
  await page.getByText("AI 조사 보조 리포트 보기").click();
  const createReport = page.getByRole("button", { name: "리포트 생성 요청" });
  try {
    await expect(createReport).toBeEnabled({ timeout: 15_000 });
    await createReport.click();
    for (let attempt = 0; attempt < 60; attempt += 1) {
      if (await page.getByRole("heading", { name: "저장된 리포트" }).isVisible()) break;
      await page.getByRole("button", { name: "리포트 새로고침" }).click();
      await page.waitForTimeout(2_000);
    }
    await expect(page.getByRole("heading", { name: "저장된 리포트" })).toBeVisible();
    const currentResponse = await fixture("relay", { method: "GET",
      path: `/api/v1/cases/${scn3.response.caseId}/ai-reports/current`,
      headers: { Authorization: `Bearer ${tokens.access_token}` } }) as { status: number; body: string };
    if (currentResponse.status !== 200) throw new Error("AI report current status invalid");
    const current = JSON.parse(currentResponse.body) as {
      latestRequest: { aiRequestId: string; executionId: string; detectionResultVersion: number; reportStatus: string };
      currentReport: { reportId: string; executionId: string; reportSource: string; reportStatus: string };
    };
    if (!current.latestRequest || !current.currentReport ||
        current.latestRequest.executionId !== current.currentReport.executionId ||
        current.currentReport.reportSource !== "TEMPLATE_FALLBACK") {
      throw new Error("AI report identities or mock source invalid");
    }
    await fixture("verify-report", { caseId: scn3.response.caseId,
      aiRequestId: current.latestRequest.aiRequestId,
      executionId: current.currentReport.executionId, reportId: current.currentReport.reportId,
      version: current.latestRequest.detectionResultVersion,
      status: current.currentReport.reportStatus, source: current.currentReport.reportSource });
    const afterAi = await fixture("ai-invariant-snapshot", invariantInput);
    if (JSON.stringify(afterAi) !== JSON.stringify(beforeAi)) {
      throw new Error("AI report changed case, transaction, or adopted detection state");
    }
    const reportImage = "SCN003-ai-report.png";
    await page.screenshot({ path: resolve(DIR, reportImage), fullPage: true });
    mark("ai-report-captured");
    index.push(`| SCN003 | 별도 요청 뒤 저장 AI 리포트(로컬 Ollama mock) | \`${scn3.body.transactionId}\` | \`${scn3.response.adoptedDetectionResultId}\` | \`${scn3.response.caseId}\` | [PNG](${reportImage}) |`);
    index.push(`| SCN003 | AI 요청·execution·report ID 연결: \`${current.latestRequest.aiRequestId}\` / \`${current.currentReport.executionId}\` / \`${current.currentReport.reportId}\` | - | - | \`${scn3.response.caseId}\` | [PNG](${reportImage}) |`);
    index.push(`| SCN003 | AI 전후 사건·거래·채택 탐지 API/DB 불변 대조 | \`${scn3.body.transactionId}\` | \`${scn3.response.adoptedDetectionResultId}\` | \`${scn3.response.caseId}\` | [PNG](${reportImage}) |`);
  } catch {
    const reportImage = "SCN003-ai-report-unavailable.png";
    await page.screenshot({ path: resolve(DIR, reportImage), fullPage: true,
      mask: [page.locator('section[aria-labelledby="case-summary-heading"] dd.facts__ref').last()] });
    mark("ai-report-unavailable-captured");
    index.push(`| SCN003 | AI 리포트 요청 UI 비활성, 저장 리포트 미검증 | \`${scn3.body.transactionId}\` | \`${scn3.response.adoptedDetectionResultId}\` | \`${scn3.response.caseId}\` | [PNG](${reportImage}) |`);
    throw new Error("SCN003 AI report not visible");
  }

  const dbStatuses = await fixture("dashboard-status", { date: evidence.date }) as Record<string, number>;
  await page.getByRole("link", { name: "FDS 집계", exact: true }).click();
  await page.setViewportSize({ width: 1920, height: 1080 });
  await expect(page.getByRole("heading", { name: "FDS 기간별 집계" })).toBeVisible();
  await page.getByLabel("시작일 (KST)").fill(evidence.date);
  const tomorrow = new Date(`${evidence.date}T00:00:00Z`);
  tomorrow.setUTCDate(tomorrow.getUTCDate() + 1);
  await page.getByLabel("종료일 (미포함, KST)").fill(tomorrow.toISOString().slice(0, 10));
  await page.getByRole("button", { name: "조회", exact: true }).click();
  const dayRow = page.getByRole("table", { name: "일별 거래·위험등급 건수" }).getByRole("row", { name: new RegExp(evidence.date) });
  const scenarioRow = page.getByRole("table", { name: "일별 시나리오 적중 거래 건수" }).getByRole("row", { name: new RegExp(evidence.date) });
  let dashboardVerified = true;
  try {
    await expect(dayRow.locator("td").nth(0)).toHaveText(String(evidence.dashboard.total), { timeout: 15_000 });
    await expect(dayRow.locator("td").nth(1)).toHaveText(String(evidence.dashboard.total - evidence.dashboard.classified));
    await expect(page.getByText(`채택 완료 ${evidence.dashboard.classified}`, { exact: true })).toBeVisible();
    await expect(scenarioRow).toBeVisible();
    for (const code of Object.keys(RULES)) {
      const column = Object.keys(RULES).indexOf(code) + 1;
      await expect(scenarioRow.locator("td").nth(column - 1)).toHaveText(String(evidence.scenarios[code]));
    }
    await expect(page.getByRole("table", { name: "일별 신규 사건 추세" }).getByRole("row", { name: new RegExp(evidence.date) })).toContainText(String(evidence.dashboard.newCases));
    const transactionApi = lastTransactionDashboard as { days?: Array<{ date: string; total: number;
      classified: number; unclassified: number; scenarios: Record<string, number> }> } | null;
    const caseApi = lastCaseDashboard as { days?: Array<{ date: string; newCases: number }>;
      currentStatuses?: Record<string, number> } | null;
    const apiDay = transactionApi?.days?.find(day => day.date === evidence.date);
    const caseDay = caseApi?.days?.find(day => day.date === evidence.date);
    if (!apiDay || apiDay.total !== evidence.dashboard.total ||
        apiDay.classified !== evidence.dashboard.classified ||
        apiDay.unclassified !== evidence.dashboard.total - evidence.dashboard.classified ||
        Object.keys(RULES).some(code => apiDay.scenarios[code] !== evidence.scenarioIds[code].length) ||
        !caseDay || caseDay.newCases !== evidence.dashboard.newCases || !caseApi?.currentStatuses ||
        Object.entries(dbStatuses).some(([status, count]) => caseApi.currentStatuses?.[status] !== count) ||
        Object.entries(caseApi.currentStatuses).some(([status, count]) => count !== (dbStatuses[status] ?? 0))) {
      throw new Error("dashboard API and contract DB identity counts differ");
    }
    for (const status of ["OPEN", "IN_REVIEW", "ADDITIONAL_INFORMATION_REQUIRED", "CLOSED"]) {
      await expect(page.getByText(`${status} ${dbStatuses[status] ?? 0}`, { exact: true })).toBeVisible();
    }
  } catch {
    dashboardVerified = false;
    writeFileSync(resolve(DIR, "dashboard-diagnostic.json"), JSON.stringify({
      dayTable: (await page.getByRole("table", { name: "일별 거래·위험등급 건수" }).allTextContents()).map(value => value.slice(0, 500)),
      scenarioTable: (await page.getByRole("table", { name: "일별 시나리오 적중 거래 건수" }).allTextContents()).map(value => value.slice(0, 500)),
      alerts: await page.getByRole("alert").allTextContents(),
    }) + "\n", "utf8");
  }
  await page.screenshot({ path: resolve(DIR, "dashboard-full.png"), fullPage: true });
  if (await page.getByRole("table", { name: "일별 시나리오 적중 거래 건수" }).isVisible()) {
    const lastHeader = page.getByRole("table", { name: "일별 시나리오 적중 거래 건수" })
      .getByRole("columnheader", { name: "SCN008" });
    await lastHeader.scrollIntoViewIfNeeded();
    await expect(lastHeader).toBeInViewport();
    await expect(scenarioRow.locator("td").last()).toBeInViewport();
    await page.getByRole("table", { name: "일별 시나리오 적중 거래 건수" }).screenshot({ path: resolve(DIR, "dashboard-scenarios.png") });
  }
  mark("dashboard-captured");
  index.push(`| 전체 | FDS 대시보드 ${dashboardVerified ? "DB 수치 대조 완료" : "화면 수치 대조 실패"} | - | - | - | [PNG](dashboard-full.png) |`);
  if (dashboardVerified) index.push("| 전체 | SCN-001~008 일별 고유 거래 집계 | - | - | - | [PNG](dashboard-scenarios.png) |");
  index.push("", "## SCN-002 계약 집계", "",
    `- 계약 조건의 고유 거래 ID 집합: ${evidence.scenarioIds.SCN002.map(id => `\`${id}\``).join(", ")}`,
    `- 같은 Rule 코드만 가진 추가 거래: ${evidence.scn002Exclusions.map(item =>
      `\`${item.transactionId}\` (${item.failedPredicates.join(", ")})`).join("; ") || "없음"}`);
  writeFileSync(resolve(DIR, "index.md"), index.join("\n") + "\n", "utf8");
  if (!dashboardVerified) throw new Error("dashboard API/DB/screen counts differ");
});
