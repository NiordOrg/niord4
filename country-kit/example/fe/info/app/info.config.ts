import type { InfoConfig } from '@niord/info/api';
import { FooterComponent } from './footer/footer.component';

/**
 * The example country's public site: no area filter, the clock in UTC, and a footer of its own,
 * whose words, logo and legal document come from the country's branding.
 */
export const COUNTRY_INFO: InfoConfig = {
  timeZone: 'UTC',
  executionMode: 'production',
  wmsLayer: false,
  rootAreas: [],
  footerComponent: FooterComponent,
};
