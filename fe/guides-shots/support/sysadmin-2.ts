import { existsSync, readdirSync, rmSync } from 'node:fs';

import { expect, test, type APIRequestContext } from '@playwright/test';

import { authHeaders, psql } from './backend';
import { BACKEND_URL } from './env';
import { joinPath } from './paths';

// The two functions of Node's fs module that only this file uses, beside node-fs.d.ts's slice (the
// folder's tsconfig sets `types: []`, and @types/node is not a dependency of the workspace).
declare module 'node:fs' {
  export function readdirSync(path: string): string[];
  export function rmSync(
    path: string,
    options: { readonly recursive: true; readonly force: true },
  ): void;
}

// The slice of Node's `process` this file reads (as env.ts declares it).
declare const process: { readonly env: Record<string, string | undefined> };

/*
 * Helpers of the sysadmin manual's second half (sysadmin-manual-2.shots.ts): the example rows a
 * picture needs where the copy has none, each found before it is made and named so a reader of the
 * data knows where it came from, the release file the import preview is shown with, and the batch
 * runs of the Batch Jobs pictures (scripts through Execute JavaScript that read or wait only).
 */

/** The Niord Integration example: inactive, so the server never pulls from it. */
export const GUIDE_INTEGRATION = {
  url: 'https://niord.example.org',
  sourceSeriesId: 'guide-example-nw',
  targetSeriesId: 'dma-nw',
} as const;

interface Integration {
  readonly id?: number;
  readonly url: string;
}

/**
 * The id of the Niord Integration example, made once through the backend the page calls (a
 * sysadmin's POST) when the copy has none. It is inactive: the scheduler and Execute pass it by.
 */
export async function guideIntegrationId(request: APIRequestContext): Promise<number> {
  const headers = await authHeaders('sysadmin');
  const listed = await request.get(`${BACKEND_URL}/rest/niord-integrations/all`, { headers });
  if (!listed.ok()) throw new Error(`The Niord integrations answered ${listed.status()}`);
  const found = ((await listed.json()) as Integration[]).find(
    (integration) => integration.url === GUIDE_INTEGRATION.url,
  );
  if (found?.id !== undefined) return found.id;
  const made = await request.post(`${BACKEND_URL}/rest/niord-integrations/niord-integration`, {
    headers,
    data: {
      url: GUIDE_INTEGRATION.url,
      active: false,
      assignNewUids: true,
      createBaseData: false,
      messageSeriesMappings: [
        {
          sourceSeriesId: GUIDE_INTEGRATION.sourceSeriesId,
          targetSeriesId: GUIDE_INTEGRATION.targetSeriesId,
        },
      ],
    },
  });
  if (!made.ok()) throw new Error(`Making the integration example answered ${made.status()}`);
  const integration = (await made.json()) as Integration;
  if (integration.id === undefined) throw new Error('The integration example came back without id');
  return integration.id;
}

/** The release file the Script Resources import preview is shown with, from the repository. */
export function releaseScriptResources(): string {
  return joinPath(
    test.info().project.testDir,
    '..',
    '..',
    'be',
    'niord',
    'bootstrap',
    'releases',
    '2026-11-02-accumulated-period',
    'script-resources.json',
  );
}

/**
 * A time of a backend answer, given by `to` from its milliseconds, in the form it came in: the
 * backend writes a time as epoch milliseconds or as an ISO text (Jackson 3). Anything else is kept.
 */
export function retimed(value: unknown, to: (millis: number) => number): unknown {
  if (typeof value === 'number') return to(value);
  if (typeof value === 'string' && Number.isFinite(Date.parse(value))) {
    return new Date(to(Date.parse(value))).toISOString();
  }
  return value;
}

/** The one batch job the Batch Jobs pictures run: Execute JavaScript's. */
export const SCRIPT_JOB = 'script-executor';

/**
 * The script of the Execute JavaScript picture, which only reads: the Batch Jobs page and its log
 * dialog show a run of it.
 */
export const SERIES_IDS_SCRIPT =
  '// Prints the ids of the message series to the server log\n' +
  "var ids = em.createQuery('SELECT s.seriesId FROM MessageSeries s').getResultList();\n" +
  "print('Message series: ' + ids);";

/**
 * A script that only waits, so that its run can be photographed while it runs, and once stopped.
 * It writes nothing; the batch engine sees a stop only when the script has ended.
 */
export const WAIT_SCRIPT = '// Waits 40 seconds and writes nothing\njava.lang.Thread.sleep(40000);';

/**
 * The data file names the pictures' runs are started with (Execute JavaScript sends its own name,
 * javascript.js): they show what the runs are, and the spec finds its runs again by them.
 */
export const BATCH_FILES = {
  seriesIds: 'guide-example-series-ids.js',
  wait: 'guide-example-wait.js',
} as const;

/** One execution of a batch run, as the Batch Jobs page reads it. */
export interface BatchExecution {
  readonly executionId: number;
  readonly batchStatus?: string;
}

/** One run (a job instance) of a batch job, newest execution first. */
export interface BatchRun {
  readonly instanceId: number;
  readonly jobNo?: number;
  readonly fileName?: string;
  readonly executions: readonly BatchExecution[];
}

/** The runs of the script job, as the Batch Jobs page lists them (newest first). */
async function scriptRuns(request: APIRequestContext): Promise<readonly BatchRun[]> {
  const response = await request.get(`${BACKEND_URL}/rest/batch/${SCRIPT_JOB}/instances`, {
    params: { page: '0', pageSize: '100' },
    headers: await authHeaders('sysadmin'),
  });
  if (!response.ok()) throw new Error(`The batch runs answered ${response.status()}`);
  return ((await response.json()) as { data?: BatchRun[] }).data ?? [];
}

/** The newest run started with the data file, if any. */
async function newestRun(
  request: APIRequestContext,
  fileName: string,
): Promise<BatchRun | undefined> {
  return (await scriptRuns(request)).find((run) => run.fileName === fileName);
}

/** The status of a run's newest execution. */
function statusOf(run: BatchRun | undefined): string | undefined {
  return run?.executions[0]?.batchStatus;
}

/** The log file of a script run that records the script and how long it ran. */
export const PROCESSOR_LOG = 'BatchScriptExecutorProcessorLog.txt';

/** How many executions of its script a run's processor log records (0 when it has none yet). */
export async function loggedExecutions(request: APIRequestContext, run: BatchRun): Promise<number> {
  const response = await request.get(
    `${BACKEND_URL}/rest/batch/instance/${run.instanceId}/logs/${PROCESSOR_LOG}`,
    { headers: await authHeaders('sysadmin') },
  );
  if (!response.ok()) return 0;
  return ((await response.text()).match(/^INFO: Executed script in /gm) ?? []).length;
}

/**
 * The guides home's execution folders of the script job: <home>/batch-jobs/<job>/execution/
 * <year>/<month>/<job number>, named as BatchData.computeBatchJobFolderPath names them. The guides
 * home is start-rig.sh's ($HOME/.niord-local/niord_guides); null when it cannot be found.
 */
function scriptExecutionRoot(): string | null {
  const userHome = process.env['USERPROFILE'] ?? process.env['HOME'];
  if (!userHome) return null;
  const root = joinPath(
    userHome,
    '.niord-local',
    'niord_guides',
    'batch-jobs',
    SCRIPT_JOB,
    'execution',
  );
  return existsSync(root) ? root : null;
}

/**
 * Removes the script job's execution folders that no BatchData row of this copy holds: they are an
 * earlier copy's (prepare.sql empties BatchData and the job numbers start again), and a new run
 * with the same job number would append its log to the old one, so that its log dialog showed two
 * runs. make-guides-db.sh removes the whole batch-jobs folder of the guides home; this keeps the
 * pictures right on a copy made before it did. Only the guides home, only folders no row holds.
 */
function removeEarlierCopiesFolders(): void {
  const root = scriptExecutionRoot();
  if (root === null) return;
  const held = new Set(
    psql(
      `SELECT extract(year FROM created)::int || '/' || extract(month FROM created)::int || '/' ` +
        `|| "jobNo" FROM "BatchData" WHERE "jobName" = '${SCRIPT_JOB}'`,
      'the execution folders of the script job this copy holds',
    )
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line.length > 0),
  );
  for (const year of readdirSync(root)) {
    for (const month of readdirSync(joinPath(root, year))) {
      for (const jobNo of readdirSync(joinPath(root, year, month))) {
        if (!held.has(`${year}/${month}/${jobNo}`)) {
          rmSync(joinPath(root, year, month, jobNo), { recursive: true, force: true });
        }
      }
    }
  }
}

/**
 * Starts a script as the script job, the call Execute JavaScript's Schedule makes (as the
 * sysadmin), under the given data file name, and returns its run once the page can list it.
 */
async function startScript(
  request: APIRequestContext,
  fileName: string,
  javaScript: string,
): Promise<BatchRun> {
  removeEarlierCopiesFolders();
  const before = new Set((await scriptRuns(request)).map((run) => run.instanceId));
  const response = await request.post(`${BACKEND_URL}/rest/batch/execute-javascript`, {
    headers: await authHeaders('sysadmin'),
    data: { scriptName: fileName, javaScript },
  });
  if (!response.ok()) throw new Error(`Execute JavaScript answered ${response.status()}`);
  let started: BatchRun | undefined;
  await expect
    .poll(
      async () => {
        started = (await scriptRuns(request)).find(
          (run) => run.fileName === fileName && !before.has(run.instanceId),
        );
        return statusOf(started);
      },
      { timeout: 30_000, message: `the run of ${fileName}` },
    )
    .toMatch(/^(STARTING|STARTED|COMPLETED|FAILED)$/);
  if (!started) throw new Error(`The run of ${fileName} was not listed`);
  return started;
}

/** Waits until the newest execution of the run is in one of the statuses; returns the run. */
async function runIn(
  request: APIRequestContext,
  run: BatchRun,
  statuses: RegExp,
  timeout: number,
): Promise<BatchRun> {
  let found: BatchRun | undefined;
  await expect
    .poll(
      async () => {
        found = (await scriptRuns(request)).find(
          (candidate) => candidate.instanceId === run.instanceId,
        );
        return statusOf(found) ?? '';
      },
      { timeout, message: `the status of job no. ${run.jobNo ?? '?'}` },
    )
    .toMatch(statuses);
  if (!found) throw new Error(`Job no. ${run.jobNo ?? '?'} is no longer listed`);
  return found;
}

/**
 * The completed run of the reading script that the Batch Jobs page and its log dialog show: found
 * again by its data file name, or started once (it reads the message series ids, writes nothing).
 * Its log must record its one execution only: a run whose folder an earlier copy's run of the same
 * job number had left holds both, and is passed by for a new run.
 */
export async function completedSeriesIdsRun(request: APIRequestContext): Promise<BatchRun> {
  const found = await newestRun(request, BATCH_FILES.seriesIds);
  if (found && statusOf(found) === 'COMPLETED' && (await loggedExecutions(request, found)) === 1) {
    return found;
  }
  const started = await startScript(request, BATCH_FILES.seriesIds, SERIES_IDS_SCRIPT);
  const run = await runIn(request, started, /^COMPLETED$/, 60_000);
  const logged = await loggedExecutions(request, run);
  if (logged !== 1) {
    throw new Error(
      `The log of job no. ${run.jobNo ?? '?'} records ${logged} executions of its script, not ` +
        'one: its folder in the guides home was not a new one. Empty the batch-jobs folder of the ' +
        'guides home (make-guides-db.sh does) and take the pictures on a new copy.',
    );
  }
  return run;
}

/**
 * A run of the waiting script that is running now: the newest one when it still runs, else a new
 * one. A run that is running can only be had by starting one, so every picture of it adds a run.
 */
export async function runningWaitRun(request: APIRequestContext): Promise<BatchRun> {
  const found = await newestRun(request, BATCH_FILES.wait);
  const run =
    found && statusOf(found) === 'STARTED'
      ? found
      : await startScript(request, BATCH_FILES.wait, WAIT_SCRIPT);
  return runIn(request, run, /^STARTED$/, 30_000);
}

/** Asks the batch engine to stop a run's newest execution, the call the page's stop link makes. */
export async function stopRun(request: APIRequestContext, run: BatchRun): Promise<void> {
  const execution = run.executions[0];
  if (!execution) throw new Error(`Job no. ${run.jobNo ?? '?'} has no execution`);
  const response = await request.put(
    `${BACKEND_URL}/rest/batch/execution/${execution.executionId}/stop`,
    { headers: await authHeaders('sysadmin') },
  );
  if (!response.ok())
    throw new Error(`Stopping job no. ${run.jobNo ?? '?'} answered ${response.status()}`);
}

/**
 * A stopped run of the waiting script: the newest one, stopped first when it still runs (or a new
 * one started and stopped when there is none), once the engine has stopped it, which it does when
 * the script ends.
 */
export async function stoppedWaitRun(request: APIRequestContext): Promise<BatchRun> {
  let run = await newestRun(request, BATCH_FILES.wait);
  if (!run || /^(COMPLETED|FAILED|ABANDONED)$/.test(statusOf(run) ?? '')) {
    run = await runningWaitRun(request);
  }
  if (/^(STARTING|STARTED)$/.test(statusOf(run) ?? '')) {
    run = await runIn(request, run, /^STARTED$/, 30_000);
    await stopRun(request, run);
  }
  return runIn(request, run, /^STOPPED$/, 90_000);
}
