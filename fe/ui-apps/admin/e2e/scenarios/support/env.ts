import type { APIRequestContext } from '@playwright/test';

import { expect, test } from '../../auth/fixtures';

/*
 * The scenarios' switch, their variables and the guards that run before any of them writes.
 *
 * The scenarios write data: messages, a mailing list, a firing schedule, a publication series. So
 * they run only with E2E_SCENARIOS=1, only against the sample deployment, and only with one
 * worker, because each of them restarts the backend the others use. Without the switch every
 * scenario test skips and says why, so a plain `npm run e2e` against somebody's development data
 * stays as harmless as it is without them.
 */

// The slice of Node's globals the file needs: the e2e tsconfig loads no Node types.
declare const process: { readonly env: Record<string, string | undefined> };

const env = (name: string): string | undefined => {
  const value = process.env[name]?.trim();
  return value ? value : undefined;
};

export const SCENARIOS_ON = env('E2E_SCENARIOS') === '1';

export const SKIP_REASON =
  'E2E_SCENARIOS is not set: the duty-officer scenarios write data and run only against the ' +
  'sample deployment (scenarios/README.md)';

const KEYCLOAK_URL = env('KEYCLOAK_URL');

if (SCENARIOS_ON && !KEYCLOAK_URL) {
  throw new Error(
    'The scenarios need KEYCLOAK_URL, the Keycloak the sample deployment signs in with ' +
      '(the local stack: http://localhost:8080/auth). See e2e/auth/README.md.',
  );
}

const restartTimeoutSeconds = Number(env('E2E_SCENARIOS_RESTART_TIMEOUT') ?? '600');

/** What the runner gave the scenarios. Every value but the switch is optional. */
export const scenarioEnv = {
  /** The public site's dev server; without it the public-site page reads skip by name. */
  infoUrl: env('E2E_INFO_URL')?.replace(/\/+$/, ''),
  /** MailHog's web address; without it the mail reads skip by name. */
  mailhogUrl: env('E2E_MAILHOG_URL')?.replace(/\/+$/, ''),
  /** The shell command that restarts the backend (README.md); without it the restart skips. */
  restartCommand: env('E2E_SCENARIOS_RESTART'),
  /**
   * The backend's own address, such as http://127.0.0.1:8888. With it, the readiness after a
   * restart is polled there, not through the admin dev server's proxy. Optional.
   */
  backendUrl: env('E2E_SCENARIOS_BACKEND_URL')?.replace(/\/+$/, ''),
  restartTimeoutMs:
    (Number.isFinite(restartTimeoutSeconds) && restartTimeoutSeconds > 0
      ? restartTimeoutSeconds
      : 600) * 1000,
  keycloakOrigin: KEYCLOAK_URL ? new URL(KEYCLOAK_URL).origin : '',
};

/**
 * Records in the report what a scenario left out or met: a skipped restart or read, a known fault.
 * The annotation goes into the HTML and JSON reports; the line on the output is what the list
 * reporter, the suite's default, shows.
 */
export function annotate(
  type: 'skip' | 'known-fault' | 'restart' | 'tidy',
  description: string,
): void {
  test.info().annotations.push({ type, description });
  console.log(`[scenario] ${type}: ${description}`);
}

/** The domains of the sample deployment the scenarios work in. */
export const SAMPLE_DOMAINS = ['niord-nw', 'niord-nm', 'niord-fa'] as const;

/** The title prefix every message of the sample carries. */
const SAMPLE_TITLE = /^SAMPLE —/;

/** Ten minutes per test: a test waits for mails sent ten a minute, and one restarts the backend. */
const SCENARIO_TEST_TIMEOUT_MS = 10 * 60_000;

/**
 * One scenario: a serial describe whose tests skip without the switch, and whose first test is
 * preceded by the guards. The steps of the scenario share state through the describe's variables.
 */
export function describeScenario(title: string, body: () => void): void {
  test.describe(title, () => {
    test.describe.configure({ mode: 'serial', timeout: SCENARIO_TEST_TIMEOUT_MS });
    // A control that never comes, or a dev server that died, fails in half a minute, not at the
    // ten-minute test timeout.
    test.use({ actionTimeout: 30_000, navigationTimeout: 60_000 });
    test.skip(!SCENARIOS_ON, SKIP_REASON);

    test.beforeAll(async ({ playwright }, testInfo) => {
      // Fails before anything is written: each scenario restarts the backend the others use.
      expect(
        testInfo.config.workers,
        'run the scenarios with --workers=1: each restarts the backend the others use',
      ).toBe(1);

      const baseURL = String(testInfo.project.use.baseURL ?? '');
      const request = await playwright.request.newContext({ baseURL });
      try {
        await expectSampleDeployment(request, baseURL);
      } finally {
        await request.dispose();
      }
    });

    body();
  });
}

async function expectSampleDeployment(request: APIRequestContext, baseURL: string): Promise<void> {
  const notSample = `the backend at ${baseURL} does not hold the sample deployment; the scenarios write data and run only there`;

  const settings = await request.get('/rest/settings/web');
  expect(settings.ok(), `${notSample} (no web settings: is the dev server's backend up?)`).toBe(
    true,
  );
  const mode = String(((await settings.json()) as Record<string, unknown>)['mode'] ?? '');
  expect(mode.toLowerCase(), `the backend at ${baseURL} runs in production mode`).not.toBe(
    'production',
  );

  const messages = await request.get('/rest/public/v1/messages', { params: { lang: 'en' } });
  expect(messages.ok(), notSample).toBe(true);
  const titles = ((await messages.json()) as { descs?: { title?: string }[] }[]).flatMap(
    (message) => (message.descs ?? []).map((desc) => desc.title ?? ''),
  );
  expect(
    titles.some((title) => SAMPLE_TITLE.test(title)),
    notSample,
  ).toBe(true);

  const domains = await request.get('/rest/domains/all', { params: { lang: 'en' } });
  expect(domains.ok(), notSample).toBe(true);
  const ids = ((await domains.json()) as { domainId: string }[]).map((domain) => domain.domainId);
  for (const domain of SAMPLE_DOMAINS) expect(ids, notSample).toContain(domain);
}
