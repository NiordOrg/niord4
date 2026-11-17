import { TestBed } from '@angular/core/testing';

/**
 * Drive the signal graph until resource-backed state has settled.
 *
 * Twice, because a component created on the first pass issues its own reads on the second, and
 * resources resolve on a MICROTASK — a synchronous `flush()` followed by `detectChanges()` would
 * assert the pre-answer state. `fixture.whenStable()` waits for pending resources, so it never
 * completes before the flush; use it only afterwards.
 *
 * The public site's own copy: the administration app's specs spell the same four lines inline,
 * and nothing under `ui-apps/info/` may import from `ui-apps/admin/`.
 */
export async function settle(): Promise<void> {
  TestBed.tick();
  await Promise.resolve();
  TestBed.tick();
  await Promise.resolve();
}
