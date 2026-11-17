import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { TranslocoService } from '@jsverse/transloco';
import { catchError, firstValueFrom, of, timeout } from 'rxjs';
import type { LanguageConfig } from '../config/language-config';
import { AppConfigService } from './app-config.service';

/** The anonymous settings endpoint. Same origin: the container proxies it, the dev server too. */
const WEB_SETTINGS_URL = '/rest/settings/web';

export interface ResolvedLanguages {
  readonly languages: readonly string[];
  readonly defaultLanguage: string;
}

/** A backend json setting arrives as an array, or as a JSON string when it was typed by hand. */
function codes(value: unknown): readonly string[] | undefined {
  if (typeof value === 'string') {
    try {
      return codes(JSON.parse(value));
    } catch {
      return undefined;
    }
  }
  if (!Array.isArray(value) || value.length === 0) return undefined;
  if (
    !value.every((code): code is string => typeof code === 'string' && /^[a-z]{2,3}$/.test(code))
  ) {
    return undefined;
  }
  return [...new Set(value)];
}

/**
 * The site's language list and default, from the backend where it answers and from the country
 * file where it does not. A language is offered only when the setting asks for it AND the build
 * ships a bundle for it (the country file's `languages`), so a setting can never switch the site
 * to a language it has no texts for.
 */
export function resolveInfoLanguages(
  defaults: LanguageConfig,
  settings: Record<string, unknown> = {},
): ResolvedLanguages {
  const shipped = defaults.languages.map((option) => option.value);
  const requested = codes(settings['infoSiteLanguages']) ?? defaults.siteLanguages;
  let languages = requested.filter((code) => shipped.includes(code));
  if (languages.length !== requested.length) {
    console.warn('Some configured site languages have no shipped translation bundle.', requested);
  }
  if (languages.length === 0) {
    languages = defaults.siteLanguages.filter((code) => shipped.includes(code));
  }
  if (languages.length === 0) languages = [shipped[0]];

  const requestedDefault =
    typeof settings['infoDefaultLanguage'] === 'string' && settings['infoDefaultLanguage'].trim()
      ? settings['infoDefaultLanguage'].trim()
      : defaults.defaultLanguage;
  const defaultLanguage = languages.includes(requestedDefault) ? requestedDefault : languages[0];

  return { languages, defaultLanguage };
}

/**
 * The deployment ribbon. The backend emits `mode` as free lower-case text; every deployable
 * declares 'production', and so does the backend's own fallback, while a local or test run
 * overrides it. Anything but 'production' is a non-production deployment and is shown,
 * uppercased, in the navigation bar. An unreachable backend shows nothing — a public site
 * must not display a DEV badge because a request failed.
 */
export function resolveExecutionMode(settings: Record<string, unknown>, fallback: string): string {
  const mode = typeof settings['mode'] === 'string' ? settings['mode'].trim() : '';
  return mode || fallback;
}

/**
 * The deployment's base-map tile address, from the `osmUrl` setting, or `undefined` for the map
 * kit's default. Only an `https:` template carrying `{z}`, `{x}` and `{y}` is taken: the site's
 * policy allows images over https alone — a template without a scheme asks for http images
 * wherever the site is opened over http — and a template OpenLayers cannot fill loads nothing.
 */
export function resolveTileUrl(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const url = value.trim();
  if (!/^https:\/\/\S+$/.test(url)) return undefined;
  return ['{z}', '{x}', '{y}'].every((placeholder) => url.includes(placeholder)) ? url : undefined;
}

@Injectable({ providedIn: 'root' })
export class WebSettingsService {
  private readonly http = inject(HttpClient);
  private readonly transloco = inject(TranslocoService);
  private readonly appConfig = inject(AppConfigService);

  /**
   * Runs from provideAppInitializer, before the first component. Never blocks or fails boot:
   * on any error or a 5 s stall the site runs on its country file alone. The defaults are
   * passed in rather than imported, so this service never names a country folder.
   *
   * It must not inject LanguageService — that service reads the language list in its field
   * initializer and would capture the pre-settings value.
   */
  async load(defaults: LanguageConfig): Promise<void> {
    const settings: Record<string, unknown> = await firstValueFrom(
      this.http.get<Record<string, unknown>>(WEB_SETTINGS_URL).pipe(
        timeout(5000),
        catchError(() => of({})),
      ),
      // A torn-down request completes EMPTY without erroring, which firstValueFrom would
      // otherwise turn into an EmptyError rejection — boot must survive that too.
      { defaultValue: {} },
    );

    const resolved = resolveInfoLanguages(defaults, settings);
    this.appConfig.applyLanguages(resolved.languages, resolved.defaultLanguage);
    this.appConfig.applyExecutionMode(
      resolveExecutionMode(settings, this.appConfig.executionMode()),
    );
    this.appConfig.applyOsmUrl(resolveTileUrl(settings['osmUrl']));

    this.transloco.setAvailableLangs([...resolved.languages]);
    this.transloco.setDefaultLang(resolved.defaultLanguage);
  }
}
