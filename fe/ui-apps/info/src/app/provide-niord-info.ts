import { isDevMode, makeEnvironmentProviders } from '@angular/core';
import { provideTransloco } from '@jsverse/transloco';
import { NiordInfoConfig, NIORD_INFO_CONFIG } from './niord-info.config';
import { NiordTranslocoLoader } from './i18n/niord-transloco-loader';

export function provideNiordInfo(config: NiordInfoConfig) {
  return makeEnvironmentProviders([
    { provide: NIORD_INFO_CONFIG, useValue: config },
    provideTransloco({
      config: {
        defaultLang: config.defaultLanguage,
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
        // No fallback language: the build stages every bundle complete, filling a key a pack
        // lacks from English, so the active language's bundle alone holds every text. An empty
        // value is one the catalog allows, and is rendered empty rather than reported missing.
        missingHandler: { allowEmpty: true },
      },
      loader: NiordTranslocoLoader,
    }),
  ]);
}
