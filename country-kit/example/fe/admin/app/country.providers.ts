import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { PROMULGATION_EDITORS, PROMULGATION_RENDERERS, provideSlot } from '@niord/admin/api';
import { BulletinEdit } from './bulletin/bulletin-edit';
import { BulletinView } from './bulletin/bulletin-view';

/**
 * The example country's hooks and slots: a renderer and an editor for its own promulgation
 * service, the harbour bulletin, beside the base product's; and a note at the end of the front
 * page's welcome panel, fetched only when the front page shows it.
 */
export function provideCountry(): EnvironmentProviders {
  return makeEnvironmentProviders([
    {
      provide: PROMULGATION_RENDERERS,
      useValue: { serviceId: 'xx-bulletin', component: BulletinView },
      multi: true,
    },
    {
      provide: PROMULGATION_EDITORS,
      useValue: { serviceId: 'xx-bulletin', component: BulletinEdit },
      multi: true,
    },
    provideSlot('home.welcome', () =>
      import('./welcome-note/welcome-note').then((m) => m.WelcomeNote),
    ),
  ]);
}
