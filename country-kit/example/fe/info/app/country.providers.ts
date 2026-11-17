import { type EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { provideSlot } from '@niord/info/api';

/**
 * The example country's slots on the public site: a notice above the footer of every page,
 * fetched after the page itself.
 */
export function provideCountry(): EnvironmentProviders {
  return makeEnvironmentProviders([
    provideSlot('layout.beforeFooter', () =>
      import('./notice/site-notice.component').then((m) => m.SiteNoticeComponent),
    ),
  ]);
}
