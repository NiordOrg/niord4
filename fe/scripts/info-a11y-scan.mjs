#!/usr/bin/env node
/**
 * The public site scanned for accessibility faults with axe-core, in Chromium, against a site that
 * runs — the record of an audit, not a gate.
 *
 *   npm run a11y:info -- --base http://localhost:4400 --out <dir>          # run from fe/
 *   node scripts/info-a11y-scan.mjs --base <url> --out <dir> [--message <id>] [--route <name>]...
 *
 * --base is a public site that answers its own /rest calls: the local stack's
 * `./start-local.sh --info` on 4400 + slot, or `npm run start:info -- --configuration production`
 * against a running backend, which is the build a reader gets; a country runs the same against its
 * own `npm run start:info`, for its tokens and its languages. --out is a folder the two reports are
 * written to. --message names a message the one-message route opens; without it the first message
 * the list read returns is taken. --route, given once per state, narrows the run to the states
 * named, each by its whole name (a run prints them, and they are listed in STATES below). Git Bash
 * rewrites an argument that starts with a slash into a Windows path, so there a --route needs
 * MSYS_NO_PATHCONV=1 in front of the command, and --out then a Windows path (C:/…).
 *
 * Each state — a route, a dialog or a tab opened on one, a publication browsed, the list empty and
 * the list failed — is opened at 1280 and at 320 px wide, in each language the menu's switcher
 * offers (the page's own language when it offers none), and scanned once the page has settled,
 * with the rules axe-core tags for WCAG 2.0 and 2.1 at levels A and AA and its best-practice rules
 * beside them. A best-practice rule is kept apart: the missing level-one heading and the landmark
 * rules are tagged so, and are no WCAG failure by themselves. Both `violations` (a rule that fails)
 * and `incomplete` (a rule axe could not decide, contrast over an image most often: a person decides
 * it) are kept.
 *
 * axe-core's source is handed to the page through the DevTools protocol, which the page's
 * Content-Security-Policy does not hold, so the scan runs as well against the container as against
 * the dev server.
 *
 * The reports are `a11y-scan.json` (every result, with its state, language and width) and
 * `a11y-scan.md` (a table per state: rule, WCAG criteria, impact, nodes and the first node's
 * selector). Neither holds any page content: no node's markup, no failure summary, no text, and
 * every quoted value in a selector is cut out, because the data a site shows may be a restore of a
 * real one. Neither is meant to be committed; an audit record copies the verdicts.
 *
 * Exit codes: 0 when no rule fails, 1 when one does (each is in the reports), 2 for a usage or
 * setup error, 3 when a state could not be opened (the run goes on, and the reports name it under
 * "Could not be opened"; a rule that failed elsewhere is in them as well).
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

/** The tags of the WCAG 2.0 and 2.1 A and AA rules: what EN 301 549 asks of web content. */
export const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

/** Run beside them, and reported apart. */
export const BEST_PRACTICE_TAG = 'best-practice';

/** The two widths each state is scanned at: a desktop and a phone, where reflow is judged. */
export const WIDTHS = [
  { width: 1280, height: 800 },
  { width: 320, height: 640 },
];

/**
 * The WCAG success criteria a rule's tags name: `wcag143` is 1.4.3 and `wcag1410` is 1.4.10. A
 * level tag (`wcag2a`, `wcag21aa`, `wcag22aa` …) names no criterion.
 */
export function criteriaOf(tags) {
  const criteria = [];
  for (const tag of tags ?? []) {
    const match = /^wcag(\d)(\d)(\d{1,2})$/.exec(tag);
    if (match) criteria.push(`${match[1]}.${match[2]}.${match[3]}`);
  }
  return criteria;
}

/** A WCAG rule when one of its tags is a WCAG 2.0 or 2.1 A or AA level, best practice otherwise. */
export function kindOf(tags) {
  return (tags ?? []).some((tag) => WCAG_TAGS.includes(tag)) ? 'wcag' : 'best-practice';
}

/** A node's selector with every quoted value cut out, so no attribute value of the page reaches a report. */
export function safeSelector(target) {
  const parts = Array.isArray(target) ? target.flat(Infinity) : [target];
  return parts
    .map((part) => String(part).replace(/(["'])(?:\\.|(?!\1).)*\1/g, '"…"'))
    .join(' >>> ');
}

/**
 * One axe result reduced to what a report may hold: the rule, its tags, its impact, the help line
 * axe writes for the rule itself, and each node's selector. Its markup, its failure summary and
 * the checks' messages and data carry the page's text and are left behind.
 */
export function reduceResult(result) {
  return {
    rule: result.id,
    impact: result.impact ?? null,
    tags: [...(result.tags ?? [])],
    help: result.help ?? '',
    helpUrl: result.helpUrl ?? '',
    nodes: (result.nodes ?? []).map((node) => ({
      target: safeSelector(node.target),
      impact: node.impact ?? null,
    })),
  };
}

/**
 * The scans grouped by state, then by outcome (`violation` or `incomplete`) and kind (`wcag` or
 * `best-practice`), then by rule: per rule its criteria, the worst impact, the nodes summed over
 * every language and width, the first node's selector and where it was seen (`en@320`).
 *
 * Each scan is `{ state, lang, width, violations, incomplete }`, the results already reduced.
 */
export function groupScans(scans) {
  const rank = { minor: 1, moderate: 2, serious: 3, critical: 4 };
  const states = new Map();
  for (const scan of scans) {
    if (!states.has(scan.state)) states.set(scan.state, new Map());
    const groups = states.get(scan.state);
    for (const outcome of ['violation', 'incomplete']) {
      const results = outcome === 'violation' ? scan.violations : scan.incomplete;
      for (const result of results ?? []) {
        const key = `${outcome}|${kindOf(result.tags)}`;
        if (!groups.has(key)) groups.set(key, new Map());
        const rules = groups.get(key);
        const seen = `${scan.lang}@${scan.width}`;
        const rule = rules.get(result.rule);
        if (!rule) {
          rules.set(result.rule, {
            rule: result.rule,
            criteria: criteriaOf(result.tags),
            impact: result.impact,
            help: result.help,
            nodes: result.nodes.length,
            firstTarget: result.nodes[0]?.target ?? '',
            seen: [seen],
          });
        } else {
          rule.nodes += result.nodes.length;
          if ((rank[result.impact] ?? 0) > (rank[rule.impact] ?? 0)) rule.impact = result.impact;
          if (!rule.firstTarget) rule.firstTarget = result.nodes[0]?.target ?? '';
          if (!rule.seen.includes(seen)) rule.seen.push(seen);
        }
      }
    }
  }
  return states;
}

/** A table cell: one line, its pipes escaped. */
function cell(value) {
  return String(value ?? '')
    .replace(/\r?\n/g, ' ')
    .replace(/\|/g, '\\|');
}

const SECTIONS = [
  ['violation|wcag', 'WCAG 2.1 A and AA: violations'],
  ['incomplete|wcag', 'WCAG 2.1 A and AA: needs a human check'],
  ['violation|best-practice', 'Best practice: violations'],
  ['incomplete|best-practice', 'Best practice: needs a human check'],
];

/**
 * The Markdown report: a heading per state and, under it, one table per outcome and kind with a
 * row per rule. `meta` carries the run's facts for the header (base address, versions, date).
 */
export function renderMarkdown(states, meta = {}) {
  const lines = ['# Accessibility scan of the public site', ''];
  for (const [label, value] of Object.entries(meta)) lines.push(`- ${label}: ${cell(value)}`);
  if (Object.keys(meta).length) lines.push('');
  for (const [state, groups] of states) {
    lines.push(`## ${state}`, '');
    let any = false;
    for (const [key, title] of SECTIONS) {
      const rules = groups.get(key);
      if (!rules || rules.size === 0) continue;
      any = true;
      lines.push(`### ${title}`, '');
      lines.push('| Rule | WCAG | Impact | Nodes | Seen at | First node |');
      lines.push('|---|---|---|---|---|---|');
      for (const rule of [...rules.values()].sort((a, b) => a.rule.localeCompare(b.rule))) {
        lines.push(
          `| ${cell(rule.rule)} | ${cell(rule.criteria.join(', ') || '—')} | ${cell(
            rule.impact ?? '—',
          )} | ${rule.nodes} | ${cell(rule.seen.join(', '))} | \`${cell(rule.firstTarget)}\` |`,
        );
      }
      lines.push('');
    }
    if (!any) lines.push('No rule failed and none was left undecided.', '');
  }
  return lines.join('\n');
}

/** True when a rule of either kind failed in any scan: the run's exit code is then 1. */
export function anyViolation(scans) {
  return scans.some((scan) => (scan.violations ?? []).length > 0);
}

// ---------------------------------------------------------------------------------------------
// The run
// ---------------------------------------------------------------------------------------------

/**
 * The states scanned, in order. `path` is where the state opens, `prepare` what is done once the
 * page has settled, and `name` the label the reports carry — the one-message route's own id is
 * data and stays out of it.
 */
const STATES = [
  { name: '/messages/details', path: () => '/messages/details' },
  {
    name: '/messages/details/message/<id>',
    path: (ctx) => `/messages/details/message/${encodeURIComponent(ctx.messageId)}`,
    needsMessage: true,
  },
  { name: '/messages/table', path: () => '/messages/table' },
  {
    name: '/messages/table, a message dialog open',
    path: () => '/messages/table',
    prepare: openTableDialog,
  },
  { name: '/messages/map', path: () => '/messages/map' },
  { name: '/messages/print', path: () => '/messages/print' },
  { name: '/publications', path: () => '/publications' },
  {
    name: '/publications, the second tab open',
    path: () => '/publications',
    prepare: openSecondTab,
  },
  {
    // Opened from the Downloads page, as a reader opens it: the publication's id is data.
    name: '/messages/details, a publication browsed',
    path: () => '/publications',
    prepare: openBrowsedPublication,
  },
  { name: '/teaser', path: () => '/teaser' },
  {
    name: '/messages/details, the first footer document open',
    path: () => '/messages/details',
    prepare: openFooterDialog,
  },
  {
    name: '/messages/details, the list empty',
    path: () => '/messages/details',
    answer: { status: 200, contentType: 'application/json', body: '[]' },
  },
  {
    name: '/messages/details, the list failed',
    path: () => '/messages/details',
    answer: { status: 503, contentType: 'application/json', body: '{}' },
  },
];

function fail(message) {
  console.error(`info-a11y-scan: ${message}`);
  process.exit(2);
}

/** No element of the page is still busy, and two frames have been drawn since. */
async function settle(page) {
  await page.waitForLoadState('networkidle', { timeout: 15000 }).catch(() => {});
  await page
    .waitForFunction(() => !document.querySelector('[aria-busy="true"]'), null, { timeout: 15000 })
    .catch(() => {});
  await page.evaluate(
    () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
  );
}

/** Opens the first message of the table view in its dialog; the reason when the list has none. */
async function openTableDialog(page) {
  const row = page.locator('app-message-table-view li button').first();
  if ((await row.count()) === 0) return 'the table lists no message';
  await row.click();
  await page.locator('[role="dialog"]').first().waitFor({ timeout: 15000 });
  await settle(page);
  return null;
}

/** Selects the page's second tab; the reason when it has none. */
async function openSecondTab(page) {
  const tab = page.locator('[role="tab"]').nth(1);
  if ((await tab.count()) === 0) return 'the page has no second tab';
  await tab.click();
  await page.waitForFunction(
    () => document.querySelectorAll('[role="tab"]')[1]?.getAttribute('aria-selected') === 'true',
  );
  await settle(page);
  return null;
}

/**
 * Follows the Downloads page's first Browse link to the messages of that publication, whose filter
 * bar names it; the reason when the page offers none. The list renders each link twice, one copy
 * hidden at each width, so the visible one is taken.
 */
async function openBrowsedPublication(page) {
  const link = page.locator('app-publication-list a[href*="publicationId="]:visible').first();
  if ((await link.count()) === 0) return 'the Downloads page offers no Browse link';
  await link.click();
  await page.waitForURL(/[?&]publicationId=/, { timeout: 15000 });
  await settle(page);
  return null;
}

/** Opens the footer's first legal document; the reason when the build's footer has none. */
async function openFooterDialog(page) {
  const button = page.locator('footer button').first();
  if ((await button.count()) === 0) return 'the footer offers no document';
  await button.click();
  await page.locator('[role="dialog"]').first().waitFor({ timeout: 15000 });
  await settle(page);
  return null;
}

/** The languages the menu's switcher offers, by their `lang`; none on a page without the menu. */
function switcherLanguages(page) {
  return page
    .locator('app-main-menu nav button[lang]')
    .evaluateAll((buttons) => buttons.map((button) => button.getAttribute('lang')));
}

/** Presses the switcher's button for `lang` and waits for the page to be in it. */
async function switchTo(page, lang) {
  await page.locator(`app-main-menu nav button[lang="${lang}"]`).evaluate((b) => b.click());
  await page.waitForFunction((wanted) => document.documentElement.lang === wanted, lang, {
    timeout: 15000,
  });
  await settle(page);
}

/** Scans the page as it stands and returns the reduced violations and undecided results. */
async function scan(page, axeSource) {
  if (!(await page.evaluate(() => typeof window.axe === 'object'))) {
    await page.evaluate(axeSource);
  }
  const raw = await page.evaluate(
    async (tags) => {
      const result = await window.axe.run(document, {
        runOnly: { type: 'tag', values: tags },
        resultTypes: ['violations', 'incomplete'],
      });
      // Reduced in the page already: no node's markup or summary ever leaves it.
      const reduce = (r) => ({
        id: r.id,
        impact: r.impact,
        tags: r.tags,
        help: r.help,
        helpUrl: r.helpUrl,
        nodes: r.nodes.map((n) => ({ target: n.target, impact: n.impact })),
      });
      return {
        violations: result.violations.map(reduce),
        incomplete: result.incomplete.map(reduce),
      };
    },
    [...WCAG_TAGS, BEST_PRACTICE_TAG],
  );
  return {
    violations: raw.violations.map(reduceResult),
    incomplete: raw.incomplete.map(reduceResult),
  };
}

/** The list read every message view makes, whose answer the empty and the failed states replace. */
function isListRead(url) {
  return url.pathname === '/rest/public/v1/messages';
}

/** The first message id the public list returns, read the way the site reads it. */
async function firstMessageId(page, base) {
  await page.goto(`${base}/messages/details`);
  return page.evaluate(async () => {
    const response = await fetch('/rest/public/v1/messages');
    if (!response.ok) return undefined;
    const list = await response.json();
    return Array.isArray(list) && list.length > 0 ? list[0].id : undefined;
  });
}

// Run as a script rather than imported by its test, which asks only the pure functions above.
const invokedDirectly =
  process.argv[1] !== undefined &&
  fs.realpathSync(path.resolve(process.argv[1])) ===
    fs.realpathSync(fileURLToPath(import.meta.url));

if (invokedDirectly) {
  const USAGE =
    'usage: info-a11y-scan.mjs --base <url> --out <dir> [--message <id>] [--route <name>]...';
  let base;
  let out;
  let messageId;
  const only = [];
  const argv = process.argv.slice(2);
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--base' && argv[i + 1]) base = argv[++i].replace(/\/+$/, '');
    else if (argv[i] === '--out' && argv[i + 1]) out = path.resolve(argv[++i]);
    else if (argv[i] === '--message' && argv[i + 1]) messageId = argv[++i];
    else if (argv[i] === '--route' && argv[i + 1]) only.push(argv[++i]);
    else fail(`unknown or incomplete option ${argv[i]}\n${USAGE}`);
  }
  if (!base || !/^https?:\/\//.test(base)) fail(`--base must name the site's address\n${USAGE}`);
  if (!out) fail(`--out must name a folder for the reports\n${USAGE}`);

  const require = createRequire(import.meta.url);
  let axeSource;
  let axeVersion;
  try {
    axeSource = fs.readFileSync(require.resolve('axe-core/axe.min.js'), 'utf8');
    axeVersion = JSON.parse(
      fs.readFileSync(require.resolve('axe-core/package.json'), 'utf8'),
    ).version;
  } catch (error) {
    fail(`axe-core is not installed (npm ci): ${error.message}`);
  }

  const unknown = only.filter((name) => !STATES.some((s) => s.name === name));
  if (unknown.length > 0) {
    fail(
      `no state is named ${unknown.join(' or ')}; the states are:\n  ${STATES.map((s) => s.name).join('\n  ')}`,
    );
  }
  const states = only.length > 0 ? STATES.filter((s) => only.includes(s.name)) : STATES;

  const { chromium } = await import('@playwright/test');
  let browser;
  try {
    browser = await chromium.launch();
  } catch (error) {
    fail(`Chromium did not start (npx playwright install chromium): ${error.message}`);
  }
  const chromiumVersion = browser.version();

  const scans = [];
  const skipped = [];
  const errored = [];
  try {
    for (const size of WIDTHS) {
      const context = await browser.newContext({ viewport: size });
      const page = await context.newPage();
      let lookupFailed = false;
      if (states.some((s) => s.needsMessage) && !messageId) {
        try {
          messageId = await firstMessageId(page, base);
        } catch (error) {
          // The list could not be read at all (a site that does not answer, most often): the state
          // that needs a message is named as not opened, and the other states still run, each
          // into its own catch below, so the reports are written. No id is known yet to cut out.
          lookupFailed = true;
          const reason = String(error?.message ?? error).split('\n')[0];
          for (const state of states.filter((s) => s.needsMessage)) {
            errored.push(`${state.name} at ${size.width} px: ${reason}`);
          }
        }
      }
      for (const state of states) {
        if (state.needsMessage && !messageId) {
          if (!lookupFailed) skipped.push(`${state.name}: the list returned no message`);
          continue;
        }
        if (state.answer) await page.route(isListRead, (route) => route.fulfill(state.answer));
        try {
          await page.goto(`${base}${state.path({ messageId })}`);
          await settle(page);
          const languages = await switcherLanguages(page);
          const langs = languages.length
            ? languages
            : [await page.evaluate(() => document.documentElement.lang)];
          for (const lang of langs) {
            if (languages.length) {
              // A language switch closes no dialog, but opens the state afresh in that language.
              if (state.prepare) await page.goto(`${base}${state.path({ messageId })}`);
              await settle(page);
              await switchTo(page, lang);
            }
            if (state.prepare) {
              const reason = await state.prepare(page);
              if (reason) {
                skipped.push(`${state.name} at ${size.width} px: ${reason}`);
                break;
              }
            }
            const result = await scan(page, axeSource);
            scans.push({ state: state.name, lang, width: size.width, ...result });
            console.log(
              `${result.violations.length ? 'FAIL' : 'ok  '}  ${state.name}, ${lang}, ${size.width} px: ` +
                `${result.violations.length} failing, ${result.incomplete.length} undecided`,
            );
          }
        } catch (error) {
          // A state that could not be opened (a page that did not load, a dialog that never
          // came, a language switch that timed out) is named and the run goes on, so the reports
          // of the other states are still written. Its first line only, the message id cut out.
          let reason = String(error?.message ?? error).split('\n')[0];
          if (messageId) reason = reason.split(messageId).join('<id>');
          errored.push(`${state.name} at ${size.width} px: ${reason}`);
        } finally {
          if (state.answer) await page.unroute(isListRead).catch(() => {});
        }
      }
      await context.close();
    }
  } finally {
    await browser.close();
  }

  const meta = {
    Scanned: new Date().toISOString().slice(0, 10),
    Site: base,
    'axe-core': axeVersion,
    Chromium: chromiumVersion,
    Rules: `${WCAG_TAGS.join(', ')}, ${BEST_PRACTICE_TAG}`,
    Widths: WIDTHS.map((s) => `${s.width} × ${s.height}`).join(', '),
  };
  if (skipped.length) meta['Not scanned'] = skipped.join('; ');
  if (errored.length) meta['Could not be opened'] = errored.join('; ');
  fs.mkdirSync(out, { recursive: true });
  fs.writeFileSync(
    path.join(out, 'a11y-scan.json'),
    `${JSON.stringify({ meta, scans }, null, 2)}\n`,
  );
  fs.writeFileSync(path.join(out, 'a11y-scan.md'), renderMarkdown(groupScans(scans), meta));
  for (const reason of skipped) console.log(`skip  ${reason}`);
  for (const reason of errored) console.error(`ERROR ${reason}`);
  console.log(`reports in ${out}`);
  process.exit(errored.length ? 3 : anyViolation(scans) ? 1 : 0);
}
