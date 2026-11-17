import type { InfoConfig } from '../../niord-info.config';

/**
 * The DEFAULT deployment: no country. `rootAreas` is empty, which means "no area filter" —
 * the message views then query the whole data set and the filter bar shows no area row
 * (`features/messages/messages.component.ts`). The map centres on the messages it finds.
 * No `footerComponent`, so the product's own minimal footer renders; a country names its own.
 */
export const COUNTRY_INFO: InfoConfig = {
  timeZone: 'UTC',
  executionMode: 'production',
  wmsLayer: false,
  rootAreas: [],
};
