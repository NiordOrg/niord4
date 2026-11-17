import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
} from '@angular/core';
import { provideRouter, TitleStrategy, withInMemoryScrolling } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import type { NiordInfoConfig } from './niord-info.config';
import { provideNiordInfo } from './provide-niord-info';
import { createNiordRoutes } from './routes/create-niord-routes';
import { COUNTRY_INFO } from './country-specific/default/info.config';
import { COUNTRY_LANGUAGES } from './country-specific/default/language.config';
import { provideCountry } from './country-specific/default/country.providers';
import { WebSettingsService } from './core/services/web-settings.service';
import { AppConfigService } from './core/services/app-config.service';
import { MAP_TILE_SOURCES, type MapTileSources } from '@niord/map/tile-sources';
import { provideInfoIcons } from './shared/icons/info-icons';
import { InfoTitleStrategy } from './core/services/info-title.strategy';

/**
 * The one file that reads the country files — `info.config`, `language.config`, `country.routes`
 * and `country.providers` — so the country seam is a single composition. A country build swaps
 * the `country-specific/default/` files for its own through `fileReplacements`; nothing else in
 * the application names a country folder.
 */
export const niordConfig: NiordInfoConfig = {
  ...COUNTRY_INFO,
  languages: [...COUNTRY_LANGUAGES.siteLanguages],
  defaultLanguage: COUNTRY_LANGUAGES.defaultLanguage,
  languageOptions: COUNTRY_LANGUAGES.languages,
  brandingLanguages: COUNTRY_LANGUAGES.brandingLanguages,
};

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    // A page the reader moves on to starts at its top, not at the offset the page before was
    // scrolled to; Back returns to where they were.
    // The country's own pages come last, lazily, inside the site's layout.
    provideRouter(
      createNiordRoutes(() => import('./country-specific/default/country.routes')),
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled' }),
    ),
    provideHttpClient(),
    provideNiordInfo(niordConfig),
    provideInfoIcons(),
    // The country's behaviour hooks and slot registrations; the base product's add nothing.
    provideCountry(),
    // The tab is named by the route, in the language the reader is in.
    { provide: TitleStrategy, useClass: InfoTitleStrategy },
    // Runs before the first component, so LanguageService and the language switcher are
    // built on the resolved list. The country defaults are handed in here because the
    // settings service itself must stay free of the country seam.
    provideAppInitializer(() => inject(WebSettingsService).load(COUNTRY_LANGUAGES)),
    // The base map's tile address, as that initializer resolved it: a getter, because the
    // providers are built before the settings arrive and a map reads it when it builds its layer.
    {
      provide: MAP_TILE_SOURCES,
      useFactory: (): MapTileSources => {
        const appConfig = inject(AppConfigService);
        return {
          get osmUrl() {
            return appConfig.osmUrl();
          },
        };
      },
    },
  ],
};
