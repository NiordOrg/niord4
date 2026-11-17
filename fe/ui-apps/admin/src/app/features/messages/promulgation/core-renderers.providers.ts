import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';

import { AudioView } from './audio-view/audio-view';
import { NavtexView } from './navtex-view/navtex-view';
import { PROMULGATION_RENDERERS } from './promulgation-renderer.token';
import { SafetynetView } from './safetynet-view/safetynet-view';
import { TwitterView } from './twitter-view/twitter-view';

export function provideCorePromulgationRenderers(): EnvironmentProviders {
  return makeEnvironmentProviders([
    {
      provide: PROMULGATION_RENDERERS,
      useValue: { serviceId: 'navtex', component: NavtexView },
      multi: true,
    },
    {
      provide: PROMULGATION_RENDERERS,
      useValue: { serviceId: 'twitter', component: TwitterView },
      multi: true,
    },
    {
      provide: PROMULGATION_RENDERERS,
      useValue: { serviceId: 'safetynet', component: SafetynetView },
      multi: true,
    },
    {
      provide: PROMULGATION_RENDERERS,
      useValue: { serviceId: 'audio', component: AudioView },
      multi: true,
    },
  ]);
}
