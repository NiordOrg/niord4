// Load-time budget check for the publications screens.
//
// Drives the running dev server through Playwright, records every /rest/ call
// each screen makes, and compares the count and the summed request time against
// a per-screen budget. The four app-bootstrap calls every full page load makes
// (dictionaries, web settings, the session domains) are listed but not counted:
// they belong to the shell, not to the screen.
//
// Usage (from the repo root, dev server on APP_URL, backend reachable from it):
//   NIORD_USER=<user> NIORD_PASS=<password> node scripts/perf/publications-requests.mjs
// Options: APP_URL (default http://localhost:4200), REPORT_ONLY=1 (never fail),
//          OUT=path for the raw JSON (default scripts/perf/last-run.json).
// Exit code 1 when any screen is over budget, so it can gate a deploy check.
import { chromium } from 'playwright';
import fs from 'node:fs';

const APP = process.env.APP_URL ?? 'http://localhost:4200';
const OUT = process.env.OUT ?? 'scripts/perf/last-run.json';
const REPORT_ONLY = process.env.REPORT_ONLY === '1';
const user = process.env.NIORD_USER,
  pass = process.env.NIORD_PASS;
if (!user || !pass) {
  console.error('NIORD_USER / NIORD_PASS required');
  process.exit(2);
}

/** The budget: at most this many screen calls, together under this many ms (cold). */
const BUDGET_MS = 500;
const BOOTSTRAP = [
  /\/dictionaries\/dictionary\//,
  /\/settings\/web/,
  // The shell's session-domain read only. The publications editors ask for the
  // same catalogue without the Keycloak state, and are told apart by the
  // admin-list `inactive=true` the shell's call does not carry.
  /\/domains\/all\?(?!.*inactive=true).*keycloakState=false/,
];

// The fixtures below are the test estate's; they hold from the 2026-09-02 restore.
const OPEN_WEEKLY = '4332e75f-b54c-4bf8-95e5-567f83be7108';
const PUBLISHED_WEEKLY = '703f5150-2233-4782-8468-7fddd254779b';
const OPEN_PT = '24cfb418-2165-4865-a6c6-9b86cabf36d2';

/**
 * name, how to reach it, the request budget, and calls excluded from the time
 * budget by ruling (the unpaged series archive is deferred: see PERF-PLAN §0).
 */
const SCREENS = [
  { name: 'dashboard (NM)', domain: 'NM', go: '/admin/publications', maxRequests: 3 },
  {
    name: 'series page weekly-ntm',
    domain: 'NM',
    go: '/admin/publications/weekly-ntm',
    maxRequests: 3,
    excludeFromTime: [/\/publication-issues\/series\//],
  },
  {
    name: 'open issue weekly',
    domain: 'NM',
    go: `/admin/publications/weekly-ntm?issue=${OPEN_WEEKLY}`,
    maxRequests: 2,
  },
  {
    name: 'published issue weekly',
    domain: 'NM',
    go: `/admin/publications/weekly-ntm?issue=${PUBLISHED_WEEKLY}`,
    maxRequests: 2,
  },
  {
    name: 'open issue P&T',
    domain: 'NM',
    go: `/admin/publications/weekly-ntm-p-t?issue=${OPEN_PT}`,
    maxRequests: 2,
  },
  {
    name: 'settings drawer (click, after series page)',
    domain: 'NM',
    go: '/admin/publications/weekly-ntm',
    maxRequests: 4,
    then: async (page) => {
      await page
        .locator('main button')
        .filter({ hasText: /Settings|Indstillinger/ })
        .first()
        .click();
    },
  },
  { name: 'categories page', domain: 'NM', go: '/admin/publication-categories', maxRequests: 2 },
  { name: 'dashboard (NM Annex)', domain: 'NM Annex', go: '/admin/publications', maxRequests: 3 },
  {
    name: 'one-off detail',
    domain: 'NM Annex',
    go: '/admin/publications/one-off/navigation-through-danish-waters',
    // The publication, then its issue and its history side by side, plus the
    // category catalogue.
    maxRequests: 4,
  },
];

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1440, height: 900 } });
const page = await ctx.newPage();
page.setDefaultTimeout(8000);

let current = null;
const log = {};
const started = new Map();
page.on('request', (r) => {
  if (r.url().includes('/rest/') && r.method() !== 'OPTIONS') started.set(r, Date.now());
});
page.on('response', async (res) => {
  const r = res.request();
  if (!started.has(r) || !current) return;
  let bytes = 0;
  try {
    bytes = (await res.body()).length;
  } catch {
    /* opaque */
  }
  (log[current] ||= []).push({
    method: r.method(),
    path: r.url().replace(/^https?:\/\/[^/]+/, ''),
    status: res.status(),
    ms: Date.now() - started.get(r),
    bytes,
  });
});

const settle = async () => {
  await page.waitForTimeout(3500);
  await page.waitForLoadState('networkidle').catch(() => {});
  await page.waitForTimeout(500);
};

// login
await page.goto(`${APP}/admin/publications`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(6000);
if ((await page.locator('input[name="username"]').count()) > 0) {
  await page.fill('input[name="username"]', user);
  await page.fill('input[name="password"]', pass);
  await page.locator('#kc-login, input[type="submit"], button[type="submit"]').first().click();
  await page.waitForTimeout(8000);
}
for (let i = 0; i < 18; i++) {
  if ((await page.locator('main').count()) > 0) break;
  await page.waitForTimeout(5000);
}
await page.waitForTimeout(3000);

async function ensureDomain(name) {
  const trigger = page
    .locator('header button')
    .filter({ hasText: /^(NW|NM|NM Annex|NM Almanac|Firing Areas)$/ })
    .first();
  if ((await trigger.textContent())?.trim() === name) return;
  await trigger.click();
  await page
    .getByRole('menuitem', { name, exact: true })
    .or(page.locator('[cdkmenuitem]', { hasText: name }))
    .first()
    .click();
  await page.waitForTimeout(3000);
}

const rows = [];
let failed = false;
for (const s of SCREENS) {
  await ensureDomain(s.domain);
  await page.goto(`${APP}${s.go}`);
  await settle();
  current = s.name;
  log[current] = [];
  if (s.then) {
    // A click scenario is measured from the click; the page it clicks on has already settled.
    await s.then(page);
  } else {
    await page.goto(`${APP}${s.go}`);
  }
  await settle();
  current = null;
  const all = log[s.name];
  const screen = all.filter((x) => !BOOTSTRAP.some((re) => re.test(x.path)));
  const timed = screen.filter((x) => !(s.excludeFromTime ?? []).some((re) => re.test(x.path)));
  const ms = timed.reduce((a, x) => a + x.ms, 0);
  const over = screen.length > s.maxRequests || ms > BUDGET_MS;
  failed ||= over;
  rows.push({
    screen: s.name,
    requests: screen.length,
    maxRequests: s.maxRequests,
    summedMs: ms,
    budgetMs: BUDGET_MS,
    bootstrap: all.length - screen.length,
    over,
  });
  console.log(
    `${over ? 'OVER ' : 'ok   '} ${s.name.padEnd(44)} ${String(screen.length).padStart(2)}/${s.maxRequests} req  ${String(ms).padStart(5)} ms  (+${all.length - screen.length} bootstrap)`,
  );
  for (const x of screen)
    console.log(
      `         ${String(x.ms).padStart(5)} ms ${String(x.bytes).padStart(7)} B  ${x.method} ${x.path.replace('/rest/', '').slice(0, 100)}`,
    );
}
fs.mkdirSync(OUT.replace(/[\\/][^\\/]*$/, ''), { recursive: true });
fs.writeFileSync(
  OUT,
  JSON.stringify(
    { at: new Date().toISOString(), app: APP, budgetMs: BUDGET_MS, rows, requests: log },
    null,
    1,
  ),
);
await browser.close();
console.log(failed ? '\nBUDGET EXCEEDED on at least one screen' : '\nall screens within budget');
process.exit(failed && !REPORT_ONLY ? 1 : 0);
