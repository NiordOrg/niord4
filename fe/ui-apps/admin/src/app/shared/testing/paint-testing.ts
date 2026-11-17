import type { ComponentFixture } from '@angular/core/testing';

import { nextPaint } from '../utils/next-paint';

/**
 * Waits until the views of a fixture that were built with `paintedOnce` have been painted once, and
 * renders the fixture again — for the spec of a list view, whose rows are built in the task after
 * the view's first paint. Call it after the fixture's first `detectChanges()`.
 */
export async function afterFirstPaint(fixture: ComponentFixture<unknown>): Promise<void> {
  // The view's own wait was registered first, so it ends first.
  await nextPaint();
  fixture.detectChanges();
}
