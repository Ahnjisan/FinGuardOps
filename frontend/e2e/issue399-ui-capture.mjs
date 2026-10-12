import { createRequire } from 'node:module';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { spawn, execFileSync } from 'node:child_process';

const root = process.cwd();
const run = process.argv[2];
const sha = process.argv[3];
if (!/^[0-9a-f]{32}$/.test(run) || !/^[0-9a-f]{40}$/.test(sha)) throw Error('identity invalid');
const dir = join(root, 'backend', 'build', `visual-capture-${run}`);
const fixture = JSON.parse(readFileSync(join(dir, 'fixtures.json'), 'utf8'));
if (fixture.runId !== run || fixture.commitSha !== sha) throw Error('fixture identity invalid');
const scn = fixture.entries.find(item => item.scenario === 'SCN003');
if (!scn) throw Error('SCN003 unavailable');
const tx = scn.body.transactionId;
const caseId = scn.response.caseId;
const containerName = 'finguardops-keycloak-browser-e2e-ai-service-1';
const record = JSON.parse(execFileSync('docker', ['inspect', containerName, '--format', '{{json .}}'],
  { encoding: 'utf8', windowsHide: true }));
if (record.Config.Labels['com.docker.compose.project'] !== 'finguardops-keycloak-browser-e2e' ||
    record.Config.Labels['com.docker.compose.service'] !== 'ai-service' ||
    !record.Config.Image.includes(`e2e-${sha.slice(0, 12)}-${run}`) || !record.State.Running) {
  throw Error('relay ownership invalid');
}
const containerId = record.Id;
const require = createRequire(join(root, 'frontend', 'package.json'));
const { chromium } = require('playwright-core');
const PY = `import json,sys,urllib.request,urllib.error
p=json.load(sys.stdin)
assert p['method']=='GET' and p['path'].startswith('/api/v1/')
h={k:v for k,v in p['headers'].items() if k.lower() in ('authorization','content-type')}
r=urllib.request.Request('http://backend:8080'+p['path'],headers=h,method='GET')
try: q=urllib.request.urlopen(r,timeout=30)
except urllib.error.HTTPError as e: q=e
with q: print(json.dumps({'status':q.status,'body':q.read().decode('utf-8')}))`;
function relay(path, headers) {
  return new Promise((resolve, reject) => {
    const child = spawn('docker', ['exec', '-i', containerId, 'python', '-c', PY],
      { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'] });
    const chunks = [];
    child.stdout.on('data', chunk => chunks.push(chunk));
    child.stderr.resume();
    child.on('error', () => reject(Error('relay process failed')));
    child.on('close', code => {
      if (code !== 0) { reject(Error('relay exit invalid')); return; }
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); }
      catch { reject(Error('relay response invalid')); }
    });
    child.stdin.end(JSON.stringify({ method: 'GET', path, headers }));
  });
}
function check(value, code) { if (!value) throw Error(code); }
function waitForGet(page, path) {
  return page.waitForResponse(response => new URL(response.url()).pathname === path &&
    response.request().method() === 'GET' && response.status() === 200, { timeout: 60_000 });
}
async function installRoute(page) {
  await page.route('http://localhost:8080/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'OPTIONS') {
      await route.fulfill({ status: 204, headers: {
        'access-control-allow-origin': 'http://localhost:5173',
        'access-control-allow-methods': 'GET, OPTIONS',
        'access-control-allow-headers': 'authorization, content-type',
      } });
      return;
    }
    if (request.method() !== 'GET' || !/^\/api\/v1\/(transactions|cases|fds-dashboard|ai-report-usage)(\/|$)/.test(url.pathname)) {
      await route.abort(); return;
    }
    const actual = await relay(url.pathname + url.search, request.headers());
    await route.fulfill({ status: actual.status, body: actual.body, headers: {
      'content-type': 'application/json', 'access-control-allow-origin': 'http://localhost:5173',
    } });
  });
}
async function login(page, name) {
  await page.getByRole('button', { name: '로그인' }).click();
  await page.locator('#username').waitFor({ state: 'visible', timeout: 45000 });
  const password = readFileSync(join(root, 'infra', 'keycloak', '.local', 'secrets', 'user-password'), 'ascii').trim();
  await page.locator('#username').fill(name);
  await page.locator('#password').fill(password);
  await page.locator('#kc-login').click();
  await page.getByLabel('인증 상태').getByText('님으로 로그인했습니다.', { exact: false }).waitFor({ timeout: 45000 });
}
async function focusByKeyboard(page, locator, code) {
  await page.keyboard.press('Tab');
  for (let i = 0; i < 35 && !(await locator.evaluate(el => el === document.activeElement)); i++) {
    await page.keyboard.press('Tab');
  }
  check(await locator.evaluate(el => el === document.activeElement && el.matches(':focus-visible')), code);
}
async function iconLink(page, name, href) {
  const link = page.getByRole('link', { name, exact: true });
  await link.waitFor({ state: 'visible' });
  check(await link.getAttribute('href') === href, `${name} destination invalid`);
  const box = await link.boundingBox();
  check(box && box.width >= 44 && box.height >= 44, `${name} target too small`);
  check(await link.locator('svg[aria-hidden="true"]').count() === 1, `${name} icon inaccessible`);
  return link;
}
async function capture(page, file, mask = []) {
  await page.screenshot({ path: join(dir, file), fullPage: true, mask,
    maskColor: '#26313a' });
}
const output = [];
let stage = 'connect';
let browser;
try {
  browser = await chromium.connect('ws://127.0.0.1:14250/');
  const analyst = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await analyst.newPage();
  await installRoute(page);
  stage = 'analyst-login';
  await page.goto('http://localhost:5173/cases/' + caseId);
  await login(page, 'local-fds-analyst');
  const caseHeading = page.getByRole('heading', { name: `사건 ${caseId}`, level: 2 });
  await caseHeading.waitFor({ state: 'visible' });
  const caseBack = await iconLink(page, '사건 목록으로', '/cases');
  stage = 'case-icon';
  await capture(page, 'issue399-case-icon-normal.png',
    [page.locator('section[aria-labelledby="case-summary-heading"] dd.facts__ref').last()]);
  await focusByKeyboard(page, caseBack, 'case icon focus invalid');
  await page.locator('.detail__navigation').screenshot({ path: join(dir, 'issue399-case-icon-focus.png') });
  await caseBack.hover();
  await page.locator('.detail__navigation').screenshot({ path: join(dir, 'issue399-case-icon-hover.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  const narrowCase = await caseBack.boundingBox();
  check(narrowCase && narrowCase.x >= 0 && narrowCase.x + narrowCase.width <= 390,
    'narrow case icon clipped');
  await capture(page, 'issue399-case-narrow.png',
    [page.locator('section[aria-labelledby="case-summary-heading"] dd.facts__ref').last()]);
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.getByRole('link', { name: `거래 ${tx} 상세 보기` }).click();
  await page.getByRole('heading', { name: `거래 ${tx}`, level: 2 }).waitFor({ state: 'visible' });
  const listBack = await iconLink(page, '거래 목록으로', '/transactions');
  const fromCase = await iconLink(page, '사건으로 돌아가기', `/cases/${caseId}`);
  const nav = page.locator('.detail__navigation');
  const a = await listBack.boundingBox(), b = await fromCase.boundingBox();
  check(b.x >= a.x + a.width + 4, 'desktop icon spacing invalid');
  stage = 'transaction-icons';
  await nav.screenshot({ path: join(dir, 'issue399-transaction-icons-normal.png') });
  await focusByKeyboard(page, listBack, 'transaction icon focus invalid');
  await nav.screenshot({ path: join(dir, 'issue399-transaction-icons-focus.png') });
  await fromCase.hover();
  await nav.screenshot({ path: join(dir, 'issue399-transaction-icons-hover.png') });
  await page.setViewportSize({ width: 390, height: 844 });
  const narrowA = await listBack.boundingBox(), narrowB = await fromCase.boundingBox();
  check(narrowA && narrowB && narrowA.x >= 0 && narrowB.x + narrowB.width <= 390 &&
    (narrowB.x >= narrowA.x + narrowA.width + 4 || narrowB.y >= narrowA.y + narrowA.height + 4),
    'narrow icons clipped or overlapping');
  await capture(page, 'issue399-transaction-narrow.png',
    [page.locator('section[aria-labelledby="transaction-parties-heading"] dd')]);
  await fromCase.click();
  check(new URL(page.url()).pathname === `/cases/${caseId}`, 'case return route invalid');
  check(await page.getByRole('navigation', { name: '주요 탐색' })
    .getByRole('link', { name: 'AI 운영' }).count() === 0, 'analyst role boundary invalid');
  await page.getByText('AI 조사 보조 리포트 보기').click();
  const reportRefresh = page.getByRole('button', { name: '리포트 새로고침' });
  await reportRefresh.waitFor({ state: 'visible' });
  check(await reportRefresh.locator('svg[aria-hidden="true"]').count() === 1,
    'AI report refresh icon invalid');
  await reportRefresh.click();
  await page.getByRole('heading', { name: '저장된 리포트' }).waitFor({ state: 'visible' });
  await page.setViewportSize({ width: 1440, height: 900 });
  stage = 'fds-controls';
  const initialFds = [
    waitForGet(page, '/api/v1/fds-dashboard/transactions'),
    waitForGet(page, '/api/v1/fds-dashboard/cases'),
  ];
  await page.getByRole('link', { name: 'FDS 집계', exact: true }).click();
  await Promise.all(initialFds);
  await page.getByRole('heading', { name: 'FDS 기간별 집계' }).waitFor({ state: 'visible' });
  const fdsNav = page.getByRole('navigation', { name: '주요 탐색' }).getByRole('link', { name: 'FDS 집계' });
  check(await fdsNav.locator('svg[aria-hidden="true"]').count() === 1, 'FDS sidebar icon invalid');
  await focusByKeyboard(page, fdsNav, 'FDS sidebar focus invalid');
  await page.locator('.rail__nav').screenshot({ path: join(dir, 'issue399-fds-sidebar-focus.png') });
  await fdsNav.hover();
  await page.locator('.rail__nav').screenshot({ path: join(dir, 'issue399-fds-sidebar-hover.png') });
  const search = page.getByRole('button', { name: '조회', exact: true });
  const refresh = page.getByRole('button', { name: '새로고침', exact: true });
  check(await search.locator('svg[aria-hidden="true"]').count() === 1 &&
    await refresh.locator('svg[aria-hidden="true"]').count() === 1, 'FDS action icons invalid');
  await page.getByLabel('시작일 (KST)').fill(fixture.date);
  const next = new Date(`${fixture.date}T00:00:00Z`); next.setUTCDate(next.getUTCDate() + 1);
  await page.getByLabel('종료일 (미포함, KST)').fill(next.toISOString().slice(0, 10));
  await Promise.all([
    waitForGet(page, '/api/v1/fds-dashboard/transactions'),
    waitForGet(page, '/api/v1/fds-dashboard/cases'),
    search.click(),
  ]);
  await page.getByText(`채택 완료 ${fixture.dashboard.classified}`, { exact: true }).waitFor({ state: 'visible' });
  await Promise.all([
    waitForGet(page, '/api/v1/fds-dashboard/transactions'),
    waitForGet(page, '/api/v1/fds-dashboard/cases'),
    refresh.click(),
  ]);
  await page.getByText(`채택 완료 ${fixture.dashboard.classified}`, { exact: true }).waitFor({ state: 'visible' });
  await capture(page, 'issue399-fds-controls.png');
  await page.setViewportSize({ width: 390, height: 844 });
  await capture(page, 'issue399-fds-narrow.png');
  output.push('analyst transaction/case controls and FDS query/refresh verified');
  await analyst.close();
  stage = 'admin-login';
  const admin = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const adminPage = await admin.newPage();
  await installRoute(adminPage);
  const initialAi = [
    waitForGet(adminPage, '/api/v1/ai-report-usage'),
    waitForGet(adminPage, '/api/v1/ai-report-usage/summary'),
  ];
  await adminPage.goto('http://localhost:5173/ai-operations');
  await login(adminPage, 'local-platform-admin');
  await Promise.all(initialAi);
  await adminPage.getByRole('heading', { name: 'AI 요청 사용량' }).waitFor({ state: 'visible' });
  const aiNav = adminPage.getByRole('navigation', { name: '주요 탐색' }).getByRole('link', { name: 'AI 운영' });
  check(await aiNav.locator('svg[aria-hidden="true"]').count() === 1, 'AI sidebar icon invalid');
  await focusByKeyboard(adminPage, aiNav, 'AI sidebar focus invalid');
  await adminPage.locator('.rail__nav').screenshot({ path: join(dir, 'issue399-ai-sidebar-focus.png') });
  await aiNav.hover();
  await adminPage.locator('.rail__nav').screenshot({ path: join(dir, 'issue399-ai-sidebar-hover.png') });
  check(await adminPage.getByRole('navigation', { name: '주요 탐색' }).getByRole('link', { name: '거래' }).count() === 0,
    'admin role boundary invalid');
  const aiSearch = adminPage.getByRole('button', { name: '조회', exact: true });
  check(await aiSearch.locator('svg[aria-hidden="true"]').count() === 1, 'AI search icon invalid');
  const aiFrom = adminPage.getByLabel('시작 (UTC)');
  const previousFrom = await aiFrom.inputValue();
  check(Number.isFinite(Date.parse(previousFrom)), 'AI initial UTC filter invalid');
  const earlierFrom = new Date(Date.parse(previousFrom) - 60 * 60 * 1000).toISOString();
  await aiFrom.fill(earlierFrom);
  await Promise.all([
    waitForGet(adminPage, '/api/v1/ai-report-usage'),
    waitForGet(adminPage, '/api/v1/ai-report-usage/summary'),
    aiSearch.click(),
  ]);
  await adminPage.locator('.ai-operations__summary').waitFor({ state: 'visible' });
  await adminPage.locator('.ai-operations tbody a').first().waitFor({ state: 'visible' });
  stage = 'ai-controls';
  await capture(adminPage, 'issue399-ai-operations.png');
  await adminPage.setViewportSize({ width: 390, height: 844 });
  await capture(adminPage, 'issue399-ai-operations-narrow.png');
  output.push('admin AI sidebar and query verified');
  await admin.close();
  writeFileSync(join(dir, 'issue399-ui-result.json'), JSON.stringify({ runId: run, sha,
    status: 'passed', checks: output, images: [
      'issue399-case-icon-normal.png', 'issue399-case-icon-focus.png', 'issue399-case-icon-hover.png',
      'issue399-case-narrow.png',
      'issue399-transaction-icons-normal.png', 'issue399-transaction-icons-focus.png',
      'issue399-transaction-icons-hover.png', 'issue399-transaction-narrow.png',
      'issue399-fds-controls.png', 'issue399-fds-narrow.png',
      'issue399-fds-sidebar-focus.png', 'issue399-fds-sidebar-hover.png',
      'issue399-ai-operations.png', 'issue399-ai-operations-narrow.png',
      'issue399-ai-sidebar-focus.png', 'issue399-ai-sidebar-hover.png',
    ] }, null, 2), 'utf8');
  process.stdout.write('ISSUE399_UI_PASSED\n');
} catch (error) {
  writeFileSync(join(dir, 'issue399-ui-result.json'), JSON.stringify({ runId: run, sha,
    status: 'failed', stage, code: error instanceof Error ? error.message : 'unknown' }, null, 2), 'utf8');
  process.stderr.write(`ISSUE399_UI_FAILED stage=${stage}\n`);
  process.exitCode = 1;
} finally {
  if (browser) await browser.close();
}
