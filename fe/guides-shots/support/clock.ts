import type { Page } from '@playwright/test';

import { NOW } from './env';

/**
 * Starts the page's clock at a fixed instant, for a page that shows "now" (today's date in a field,
 * a time since), so a picture taken again shows the same day. Called before the first navigation.
 *
 * The clock keeps running from that instant. A clock that stood still (Playwright's setFixedTime)
 * would also hold OpenLayers' view animations and tile fade-in at their first frame, and a map
 * would never finish drawing. The backend keeps the real time: what it selects as "now" (the
 * messages in force) follows the day of the run.
 */
export async function fixClock(page: Page, at: string = NOW): Promise<void> {
  await page.clock.install({ time: new Date(at) });
  await page.clock.resume();
}
