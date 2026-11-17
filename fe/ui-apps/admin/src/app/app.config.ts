import {
  ApplicationConfig,
  inject,
  isDevMode,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { Location } from '@angular/common';
import {
  provideRouter,
  Router,
  TitleStrategy,
  withNavigationErrorHandler,
  withRouterConfig,
} from '@angular/router';
import { provideServiceWorker } from '@angular/service-worker';
import Keycloak from 'keycloak-js';

import { routes } from './app.routes';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { authTokenInterceptor } from './core/interceptors/auth-token.interceptor';
import { niordDomainInterceptor } from './core/interceptors/niord-domain.interceptor';
import { APP_CONFIG } from './core/config/app-config.token';
import {
  createRuntimeConfig,
  mapTileSources,
  webSettingsInitializer,
} from './core/config/web-settings';
import { MAP_TILE_SOURCES } from '@niord/map/tile-sources';
import { environment } from '../environments/environment';
import { TranslocoHttpLoader } from './transloco-loader';
import { provideTransloco, TranslocoService } from '@jsverse/transloco';
import { catchError, firstValueFrom, of, timeout } from 'rxjs';
import { LANGUAGES } from './core/main-shell/config/config.token';
import { LanguageService } from './core/main-shell/services/language.service';
import { AuthService } from './core/auth/services/auth.service';
import { KEYCLOAK } from './core/auth/services/keycloak.token';
import { createTestKeycloak, readTestAuthBundle } from './core/auth/services/test-keycloak';
import { DomainService } from './core/domain/services/domain.service';
import { I18nTitleStrategy } from './core/main-shell/title.strategy';
import { reportMissingChunkOnNavigationError } from './core/version/build-version.service';
import { provideCorePromulgationRenderers } from './features/messages/promulgation/core-renderers.providers';
import { provideCountry } from './country-specific/default/country.providers';
import { COUNTRY_MENU_ENTRIES } from './country-specific/default/country.menu';
import { COUNTRY_MENU } from './core/main-shell/country-menu';
import { provideCorePromulgationEditors } from './features/editor/promulgation/core-editors.providers';
import { provideCorePromulgationAdminPlugins } from './features/admin/sections/sysadmin/promulgation/core-admin-plugins.providers';
import { MessagesAreaGeometrySource } from './features/messages/services/area-geometry-source';
import { LastMessagesViewService } from './features/messages/services/last-messages-view.service';
import { AREA_GEOMETRY_SOURCE } from './shared/components/geometry-editor/area-geometry-source.token';
import { ConfirmService } from './shared/services/confirm.service';
import { provideNiordIcons } from './shared/icons/niord-icons';

/**
 * Cap on the boot-phase translation load. Deliberately below `AuthService`'s own init timeout so
 * the i18n leg can never overtake auth as the slowest initializer and extend the splash.
 */
const I18N_PRELOAD_TIMEOUT_MS = 3000;

/** Hosts that must never get a service worker, however the bundle was built. */
const LOCAL_HOSTNAMES = new Set(['localhost', '127.0.0.1', '[::1]']);

export const appConfig: ApplicationConfig = {
  providers: [
    // The app is intentionally zoneless — makes the v21+ default explicit as an intent marker.
    provideZonelessChangeDetection(),
    provideBrowserGlobalErrorListeners(),
    // App-wide Lucide icon registry (@ng-icons) — backs <app-icon> / <ng-icon>.
    provideNiordIcons(),
    // Relies on Angular 22's default paramsInheritanceStrategy 'always': child routes inherit
    // params/queryParams/data from ancestors even with a non-empty path (used to dedupe the
    // help-section route data across the messages children). Do NOT re-pin 'emptyOnly'.
    // A lazily-loaded route that can no longer be fetched is a deploy that happened under an open
    // tab; the handler turns it into the shell's update banner instead of a click that does nothing.
    // 'computed': when a guard cancels the browser's Back or Forward (No at an unsaved-changes
    // prompt), the router moves the history back to the entry the user was on. The default
    // 'replace' writes the current address over the entry the browser moved to, so the page the
    // user came from is lost and a second Back or Forward does nothing. Every guarded route,
    // a country's own included, gets this behaviour.
    provideRouter(
      routes,
      withNavigationErrorHandler(reportMissingChunkOnNavigationError),
      withRouterConfig({ canceledNavigationResolution: 'computed' }),
    ),
    // A CLONE of the env config, so the web-settings initializer below can overlay
    // backend values onto it without mutating the imported environment module.
    { provide: APP_CONFIG, useFactory: () => createRuntimeConfig(environment) },
    // The deployment's tile addresses for every map's base layers, read from that clone once
    // the overlay has written into it (getters, so a map built after boot sees the result).
    { provide: MAP_TILE_SOURCES, useFactory: () => mapTileSources(inject(APP_CONFIG)) },

    // The geometry editor's import dialog "From Area" tab — backed by the
    // messages area API. Provided app-wide so every editor host (charts, the
    // future message-editor integration, the dev sandbox) gets the From-Area
    // tab; the dialog hides it when the token is absent.
    { provide: AREA_GEOMETRY_SOURCE, useExisting: MessagesAreaGeometrySource },
    // Root ConfirmService for root-provided consumers (e.g. NamedFiltersFacade);
    // the root <app-confirm> (mounted in messages-filter-dropdown) renders it.
    // Components that re-provide their own ConfirmService mount their own <app-confirm>.
    ConfirmService,
    {
      provide: KEYCLOAK,
      useFactory: () => {
        const config = inject(APP_CONFIG);
        // Test-only: when an e2e fixture has seeded a token bundle on window, return a
        // pre-authenticated stub instead of the real Keycloak client. Gated on
        // `!production` so prod builds cannot be subverted by setting the window key.
        if (!config.production) {
          const bundle = readTestAuthBundle();
          if (bundle) return createTestKeycloak(bundle);
        }
        const { url, realm, clientId } = config.keycloak;
        return new Keycloak({ url, realm, clientId });
      },
    },
    // Services build absolute backend URLs themselves (apiBaseUrl from AppConfig);
    // both interceptors recognize them via `isNiordBackendRequest`. authTokenInterceptor
    // first so the Authorization header is on the request when niordDomainInterceptor
    // augments it. Order between the two doesn't otherwise matter — both are idempotent
    // header-attach interceptors with no URL mutation.
    provideHttpClient(withInterceptors([authTokenInterceptor, niordDomainInterceptor])),
    provideTransloco({
      config: {
        availableLangs: [
          ...new Set([
            ...LANGUAGES.availableLanguages.map((language) => language.value),
            ...LANGUAGES.uiLanguages,
          ]),
        ],
        defaultLang: LANGUAGES.defaultLanguage,
        reRenderOnLangChange: true,
        prodMode: !isDevMode(),
      },
      loader: TranslocoHttpLoader,
    }),
    // Registered first so its work starts first. Angular starts every initializer in the same
    // tick and awaits them together, so nothing below waits for auth to finish: every request the
    // other initializers make targets an endpoint that answers anonymously.
    provideAppInitializer(() => inject(AuthService).init()),
    // Language settings must resolve before translation loads and localized domain requests.
    // Auth remains independent; all of these endpoints also support anonymous startup.
    provideAppInitializer(() => {
      const transloco = inject(TranslocoService);
      const language = inject(LanguageService);
      const domainService = inject(DomainService);
      const router = inject(Router);
      const initialPath = inject(Location).path();
      const deepLinkDomainId =
        router.parseUrl(initialPath).queryParamMap.get('domain') ?? undefined;
      return webSettingsInitializer().then(() => {
        language.configure();
        return Promise.all([
          firstValueFrom(
            transloco.load(language.selectedLanguage()).pipe(
              timeout(I18N_PRELOAD_TIMEOUT_MS),
              catchError(() => of(undefined)),
            ),
            { defaultValue: undefined },
          ),
          domainService.bootstrap(deepLinkDomainId),
        ]);
      });
    }),
    // Eagerly create the last-messages-view tracker at bootstrap so its router subscription is
    // live before the navigation that opens the editor — otherwise the editor's Back would miss
    // the messages URL the user came from.
    provideAppInitializer(() => void inject(LastMessagesViewService)),
    { provide: TitleStrategy, useClass: I18nTitleStrategy },
    // Core registers the navtex/twitter/safetynet/audio promulgation renderers and their editors.
    provideCorePromulgationRenderers(),
    provideCorePromulgationEditors(),
    // The country's behaviour hooks and component slots come from country.providers.ts, which each
    // country build swaps in with fileReplacements: the default adds nothing, and a country adds
    // the renderer and editor of a service of its own. Provided at THIS root injector level so its
    // multi providers merge with core's instead of shadowing them.
    provideCountry(),
    // The country's own menu entries, from the country.menu.ts swapped in the same way.
    { provide: COUNTRY_MENU, useValue: COUNTRY_MENU_ENTRIES },
    // The sysadmin promulgation-type drawer's per-service plugin sections: core registers the
    // navtex/safetynet/twitter admin plugins, and a serviceId with no registered plugin renders
    // no admin section. This is not a country seam — the token is not in the country API, so
    // a country's own service has no admin section. Provided at THIS root injector level only:
    // multi-providers don't merge across injector levels, so a lower-level provider would
    // shadow these registrations.
    provideCorePromulgationAdminPlugins(),
    // Repeat-visit caching. LAST in the array so the render-blocking auth/settings/domain
    // initializers still run first, and registration waits for the app to go stable.
    //
    // The gate is deliberately belt-and-braces. `isDevMode()` alone is not enough: the `test`
    // configuration builds optimized, so `ng serve -c test` would make isDevMode() false and
    // register a REAL worker against http://localhost:4200 — which then caches a dev shell and
    // outlives the session. Registration is also scoped to the configuration of angular.json that
    // sets serviceWorker (production), which a country workspace's generated configurations
    // inherit; the hostname check covers any other.
    //
    // Never gate on `environment.production`: environment.test.ts sets it false, and a deployed
    // test build ships that file, so the worker would be off on the live site.
    provideServiceWorker('ngsw-worker.js', {
      enabled: !isDevMode() && !LOCAL_HOSTNAMES.has(location.hostname),
      registrationStrategy: 'registerWhenStable:30000',
      updateViaCache: 'none',
    }),
  ],
};
