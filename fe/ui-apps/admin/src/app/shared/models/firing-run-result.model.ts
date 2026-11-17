/**
 * The answer of `PUT /firing-schedules/update-firing-exercises`: what the firing-exercise run could
 * not do. Each count is a unit the run caught, logged and left for the hourly run to try again: a
 * schedule, an area's create or replace, a cancellation. All three 0 means the run did everything.
 *
 * Read by the editor's Firing Area Schedule page and by the sysadmin Firing Schedules page, which
 * both start the run.
 */
export interface FiringRunResult {
  readonly failedSchedules: number;
  readonly failedAreas: number;
  readonly failedCancellations: number;
}

/**
 * The number of changes the run could not make. A missing body or count reads as 0, so an answer
 * without the counts is taken as a clean run, as before the run reported them.
 */
export function firingRunFailures(result: FiringRunResult | null | undefined): number {
  return (
    (result?.failedSchedules ?? 0) + (result?.failedAreas ?? 0) + (result?.failedCancellations ?? 0)
  );
}
