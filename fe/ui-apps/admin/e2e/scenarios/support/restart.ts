import { execSync } from 'node:child_process';

import type { APIRequestContext } from '@playwright/test';

import { annotate, scenarioEnv } from './env';

/*
 * The backend's restart, once per scenario: wave after wave of hand testing found faults that
 * showed only after one. The runner gives the command in E2E_SCENARIOS_RESTART. Its contract: it
 * stops the backend the dev servers proxy to, returns once the old process has stopped, and leaves
 * a new one starting in the background with its output in a log (never on the command's own
 * output, or the call would not return). The suite then waits until the new one is ready.
 */

const POLL_MS = 3000;

function pause(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function answers(
  request: APIRequestContext,
  path: string,
  ready: (body: string) => boolean,
): Promise<boolean> {
  try {
    const response = await request.get(path, { timeout: 10_000 });
    return response.status() === 200 && ready(await response.text());
  } catch {
    return false;
  }
}

/**
 * Waits until the backend is ready, then until the admin dev server's proxy serves its settings.
 *
 * With E2E_SCENARIOS_BACKEND_URL the readiness is polled at the backend itself. Without it, it is
 * polled through the dev server's proxy, which logs an error for every poll the backend refuses.
 * The last step always goes through the proxy, so a dev server that died while the backend was
 * down is named, not met later as a page that never loads.
 */
export async function waitForBackend(request: APIRequestContext, timeoutMs: number): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  const readiness = '/rest/actuator/health/readiness';
  const steps: [string, string, (body: string) => boolean][] = [
    [
      `${scenarioEnv.backendUrl ?? ''}${readiness}`,
      'the backend did not report ready',
      (body) => /"status"\s*:\s*"UP"/.test(body),
    ],
    [
      '/rest/settings/web',
      scenarioEnv.backendUrl
        ? 'the backend is ready, but the admin dev server does not answer through its proxy: ' +
          'is the dev server still running? Start it again'
        : 'the backend did not serve its settings',
      () => true,
    ],
  ];
  for (const [path, failure, ready] of steps) {
    // Once the backend itself is ready, the dev server has a minute to answer through its proxy.
    const stepDeadline =
      scenarioEnv.backendUrl && path === '/rest/settings/web'
        ? Math.min(deadline, Date.now() + 60_000)
        : deadline;
    while (!(await answers(request, path, ready))) {
      if (Date.now() > stepDeadline) {
        throw new Error(
          `${failure} (${path}) in time after the restart: ` +
            'read the log the restart command writes, and check the admin dev server',
        );
      }
      await pause(POLL_MS);
    }
  }
}

/**
 * Restarts the backend with the runner's command and waits until it is ready. Without the command
 * the step is skipped and the report says so; what the scenario reads after it still runs.
 * Returns whether the backend was restarted.
 */
export async function restartBackend(request: APIRequestContext): Promise<boolean> {
  const command = scenarioEnv.restartCommand;
  if (!command) {
    annotate('skip', 'restart skipped: E2E_SCENARIOS_RESTART is not set');
    return false;
  }
  const started = Date.now();
  execSync(command, {
    stdio: 'inherit',
    timeout: scenarioEnv.restartTimeoutMs,
    windowsHide: true,
  });
  await waitForBackend(request, scenarioEnv.restartTimeoutMs - (Date.now() - started));
  annotate(
    'restart',
    `backend restarted and ready in ${Math.round((Date.now() - started) / 1000)} s`,
  );
  return true;
}
