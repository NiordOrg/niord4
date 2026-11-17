import { test as base, expect } from '@playwright/test';

import { watchPage } from './guard';
import { installStabiliser, trackRequests } from './stabilise';
import { cacheTiles } from './tiles';

/**
 * The rig's `test`: every page is watched by the guard and counted by the network tracker from
 * the moment it exists, carries the stabiliser style in every document it loads, and takes its
 * map tiles from the rig's tile cache. Signing in
 * is a call of its own (loginAs), because a picture of the public site, of Keycloak or of MailHog
 * needs none.
 */
export const test = base.extend<{ shotSetup: void }>({
  shotSetup: [
    async ({ page }, use) => {
      watchPage(page);
      trackRequests(page);
      await installStabiliser(page);
      await cacheTiles(page);
      await use();
    },
    { auto: true },
  ],
});

export { expect };
