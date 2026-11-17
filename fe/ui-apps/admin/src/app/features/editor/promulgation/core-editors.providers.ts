import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';

import { AudioEdit } from './audio-edit/audio-edit';
import { NavtexEdit } from './navtex-edit/navtex-edit';
import { PROMULGATION_EDITORS } from './promulgation-editor.token';
import { SafetynetEdit } from './safetynet-edit/safetynet-edit';
import { TwitterEdit } from './twitter-edit/twitter-edit';

/**
 * Register the base product's channel editors: NAVTEX, Twitter, SafetyNET and Audio. A country adds
 * the editor of a service of its own through its provideCountry(). Mirrors
 * `provideCorePromulgationRenderers`.
 */
export function provideCorePromulgationEditors(): EnvironmentProviders {
  return makeEnvironmentProviders([
    {
      provide: PROMULGATION_EDITORS,
      useValue: { serviceId: 'navtex', component: NavtexEdit },
      multi: true,
    },
    {
      provide: PROMULGATION_EDITORS,
      useValue: { serviceId: 'twitter', component: TwitterEdit },
      multi: true,
    },
    {
      provide: PROMULGATION_EDITORS,
      useValue: { serviceId: 'safetynet', component: SafetynetEdit },
      multi: true,
    },
    {
      provide: PROMULGATION_EDITORS,
      useValue: { serviceId: 'audio', component: AudioEdit },
      multi: true,
    },
  ]);
}
