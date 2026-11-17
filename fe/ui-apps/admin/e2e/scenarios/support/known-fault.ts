import { annotate } from './env';

/*
 * A known product fault inside a scenario. The check asserts the RIGHT behaviour. While the fault
 * shows, the check fails, the step passes and the report carries a `known-fault` annotation; once
 * the fault is fixed, the check passes and the test fails, so the mark leaves with the fix.
 *
 * `code` is the record's code in be/docs/potential-errors.md, the entry's title in
 * fe/docs/backlog.md, or `pending: <short title>` for a fault found by a scenario that has no
 * record yet. A fault that leaves nothing after it to test is a `test.fixme` instead.
 */
export async function knownFault(
  code: string,
  title: string,
  check: () => Promise<void> | void,
): Promise<void> {
  let failure: unknown;
  try {
    await check();
  } catch (error) {
    failure = error;
  }
  if (failure === undefined) {
    throw new Error(
      `known fault ${code} (${title}) no longer shows: remove the mark (and close the record)`,
    );
  }
  const reason = String(failure instanceof Error ? failure.message : failure)
    .split('\n')
    .find((line) => line.trim() !== '');
  annotate('known-fault', `${code}: ${title}${reason ? ` (${reason.trim()})` : ''}`);
}
