import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';

import { NavtexTransmittersPlugin } from './navtex-transmitters-plugin';
import { PROMULGATION_ADMIN_PLUGINS } from './promulgation-admin-plugin.token';
import { SafetyNetAreasPlugin } from './safetynet-areas-plugin';
import { TwitterSettingsPlugin } from './twitter-settings-plugin';

/**
 * Register the core per-service promulgation admin plugins (every build) —
 * the navtex, safetynet and twitter drawer sections. The token is not a
 * country seam: core registers every admin plugin and the country API does
 * not export the token. Provide it at the app environment injector only (see
 * the token doc); a serviceId with no entry renders no plugin section. Mirrors
 * `provideCorePromulgationEditors`.
 */
export function provideCorePromulgationAdminPlugins(): EnvironmentProviders {
  return makeEnvironmentProviders([
    {
      provide: PROMULGATION_ADMIN_PLUGINS,
      useValue: { serviceId: 'navtex', component: NavtexTransmittersPlugin },
      multi: true,
    },
    {
      provide: PROMULGATION_ADMIN_PLUGINS,
      useValue: { serviceId: 'safetynet', component: SafetyNetAreasPlugin },
      multi: true,
    },
    {
      provide: PROMULGATION_ADMIN_PLUGINS,
      useValue: { serviceId: 'twitter', component: TwitterSettingsPlugin },
      multi: true,
    },
  ]);
}
