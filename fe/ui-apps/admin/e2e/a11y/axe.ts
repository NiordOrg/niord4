import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';

import type { Page } from '@playwright/test';

import { expect, test } from '../auth/fixtures';

/*
 * The administration app scanned with axe-core, in the browser the test drives, and compared with
 * the checked-in list of violations already known (`known-violations.json`).
 *
 * A scan runs the rules axe-core tags for WCAG 2.0 and 2.1 at levels A and AA, which is what
 * EN 301 549 asks of web content. The map's canvas is left out: a map for navigational use is
 * exempt as long as what it shows is also given as text; the controls around it are scanned.
 *
 * Every failing node is keyed by its rule and its selector, as axe-core writes it. A node on the
 * list annotates the report with `known-violation` and the record it is filed under; a node not on
 * the list fails the test, naming the rule, its impact, its help page and the selector; an entry of
 * the list that no longer matches any node fails it too, so the list shrinks with each fix. In an
 * entry's selector a `*` stands for any run of characters but a space, for a fault repeated on
 * every row of a list (`tr:nth-child(*) > …`). The list holds the sample deployment's pages, so the
 * specs that scan run only there (`expectSampleDeployment`).
 *
 * axe-core's source is handed to the page through the DevTools protocol (`page.evaluate`), which
 * the page's Content-Security-Policy does not hold. The full result of each scan is attached to the
 * test as JSON, reduced to rule, impact, help and selectors: no node's markup leaves the page.
 */

/** The tags of the WCAG 2.0 and 2.1 A and AA rules. */
export const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'];

/** Left out of every scan: the map's canvas (see above). */
const ALWAYS_EXCLUDED = ['app-ol-map canvas'];

export interface ScanOptions {
  /** Scan only this element and what it holds, such as an open dialog or an overlay panel. */
  readonly include?: string;
}

/** One failing rule, as the report keeps it. */
export interface Violation {
  readonly rule: string;
  readonly impact: string | null;
  readonly help: string;
  readonly helpUrl: string;
  readonly targets: readonly string[];
}

/** One entry of `known-violations.json`. */
interface KnownViolation {
  readonly rule: string;
  /** The node's selector; `*` stands for any run of characters but a space. */
  readonly target: string;
  /** The record the fault is filed under: a backlog entry's title, or `pending: <title>`. */
  readonly record: string;
}

type KnownList = Readonly<Record<string, readonly KnownViolation[]>>;

let axeSource: string | undefined;
let knownList: KnownList | undefined;

function resolver() {
  // The suite's own folder: from there `axe-core` resolves to the workspace's node_modules.
  return createRequire(`${test.info().config.rootDir}/`);
}

function axeScript(): string {
  axeSource ??= readFileSync(resolver().resolve('axe-core/axe.min.js'), 'utf8');
  return axeSource;
}

function known(): KnownList {
  knownList ??= JSON.parse(
    readFileSync(`${test.info().config.rootDir}/a11y/known-violations.json`, 'utf8'),
  ) as KnownList;
  return knownList;
}

/** True when the entry's selector, with its `*` wildcards, is the node's selector. */
function matches(entry: KnownViolation, rule: string, target: string): boolean {
  if (entry.rule !== rule) return false;
  if (!entry.target.includes('*')) return entry.target === target;
  const pattern = entry.target
    .split('*')
    .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
    .join('[^ ]*');
  return new RegExp(`^${pattern}$`).test(target);
}

/**
 * Waits until the page has settled: no request in flight, nothing marked busy or loading, two
 * frames drawn. Never a fixed sleep. A page that polls never reaches network idle; the wait then
 * gives up after ten seconds and the busy and loading marks decide.
 */
export async function settle(page: Page): Promise<void> {
  await page.waitForLoadState('networkidle', { timeout: 10_000 }).catch(() => undefined);
  await page
    .waitForFunction(
      () => !document.querySelector('[aria-busy="true"], [role="progressbar"]'),
      null,
      { timeout: 10_000 },
    )
    .catch(() => undefined);
  await page.evaluate(
    () =>
      new Promise<void>((resolve) =>
        requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
      ),
  );
}

/** Runs axe-core over the page as it stands and returns the failing rules, reduced. */
export async function axeViolations(page: Page, options: ScanOptions = {}): Promise<Violation[]> {
  if (!(await page.evaluate(() => typeof (window as { axe?: unknown }).axe === 'object'))) {
    await page.evaluate(axeScript());
  }
  return page.evaluate(
    async ({ tags, include, exclude }) => {
      interface AxeNode {
        readonly target: unknown[];
      }
      interface AxeResult {
        readonly id: string;
        readonly impact?: string | null;
        readonly help: string;
        readonly helpUrl: string;
        readonly nodes: AxeNode[];
      }
      const axe = (
        window as unknown as {
          axe: {
            run(context: unknown, options: unknown): Promise<{ violations: AxeResult[] }>;
          };
        }
      ).axe;
      const context = include ? { include: [[include]], exclude } : { exclude };
      const result = await axe.run(context, {
        runOnly: { type: 'tag', values: tags },
        resultTypes: ['violations'],
      });
      // Reduced in the page already: no node's markup ever leaves it.
      return result.violations.map((violation) => ({
        rule: violation.id,
        impact: violation.impact ?? null,
        help: violation.help,
        helpUrl: violation.helpUrl,
        targets: violation.nodes.map((node) =>
          node.target
            .flat(Infinity as 1)
            .map(String)
            .join(' >>> '),
        ),
      }));
    },
    {
      tags: WCAG_TAGS,
      include: options.include,
      exclude: ALWAYS_EXCLUDED.map((selector) => [selector]),
    },
  );
}

/**
 * Scans the page, attaches the result to the test under `label`, and holds it to the known list's
 * entries for `label`: an unlisted node or a listed one that no longer shows fails the test.
 */
export async function scanPage(
  page: Page,
  label: string,
  options: ScanOptions = {},
): Promise<void> {
  await settle(page);
  const violations = await axeViolations(page, options);
  await test.info().attach(`axe: ${label}`, {
    body: JSON.stringify({ label, include: options.include ?? null, violations }, null, 2),
    contentType: 'application/json',
  });

  const listed = known()[label] ?? [];
  const isListed = (rule: string, target: string): KnownViolation | undefined =>
    listed.find((entry) => matches(entry, rule, target));

  const unlisted: string[] = [];
  const seen = new Set<KnownViolation>();
  for (const violation of violations) {
    for (const target of violation.targets) {
      const entry = isListed(violation.rule, target);
      if (entry) {
        seen.add(entry);
        test.info().annotations.push({
          type: 'known-violation',
          description: `${label}: ${violation.rule} at ${target} (${entry.record})`,
        });
      } else {
        unlisted.push(
          `${violation.rule} (${violation.impact ?? 'no impact'}): ${violation.help}\n` +
            `    at ${target}\n    ${violation.helpUrl}`,
        );
      }
    }
  }
  const gone = listed
    .filter((entry) => !seen.has(entry))
    .map((entry) => `${entry.rule} at ${entry.target} (${entry.record})`);

  expect.soft(unlisted, `${label}: accessibility violations not on the known list`).toEqual([]);
  expect
    .soft(
      gone,
      `${label}: known violations that no longer show; remove them from known-violations.json (and close the record)`,
    )
    .toEqual([]);
}
