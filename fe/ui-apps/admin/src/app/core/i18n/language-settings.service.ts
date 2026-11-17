import { computed, inject, Injectable, InjectionToken, signal } from '@angular/core';
import type { LanguageConfig } from '../config/app-config.interface';
import { LANGUAGES } from '../main-shell/config/config.token';
import type { LanguageOption } from '../../shared/models/main.models';

/**
 * The build's language configuration (`LANGUAGES`), as a token so a spec can give the
 * service another one.
 */
export const LANGUAGE_CONFIG = new InjectionToken<LanguageConfig>('LANGUAGE_CONFIG', {
  providedIn: 'root',
  factory: () => LANGUAGES,
});

export interface ResolvedLanguages {
  readonly uiLanguages: readonly LanguageOption[];
  readonly modelLanguages: readonly LanguageOption[];
  readonly defaultLanguage: string;
}

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

export function resolveLanguages(
  defaults: LanguageConfig,
  settings: Record<string, unknown> = {},
): ResolvedLanguages {
  const metadata = defaults.availableLanguages;
  const bundles = defaults.uiLanguages;
  const modelCodes =
    codes(settings['modelLanguages']) ?? defaults.modelLanguages ?? metadata.map((l) => l.value);
  const requestedUi =
    codes(settings['siteLanguages']) ?? defaults.siteLanguages ?? metadata.map((l) => l.value);
  let uiCodes = requestedUi.filter((code) => bundles.includes(code));
  if (uiCodes.length !== requestedUi.length) {
    console.warn('Some configured UI languages have no shipped translation bundle.', requestedUi);
  }
  if (uiCodes.length === 0) {
    uiCodes = (defaults.siteLanguages ?? metadata.map((l) => l.value)).filter((code) =>
      bundles.includes(code),
    );
  }
  if (uiCodes.length === 0) uiCodes = [bundles[0]];
  const requestedDefault =
    typeof settings['defaultLanguage'] === 'string'
      ? settings['defaultLanguage']
      : defaults.defaultLanguage;
  const defaultLanguage = modelCodes.includes(requestedDefault) ? requestedDefault : modelCodes[0];
  // A language the configuration does not name has no flag in this build: it is shown as its code.
  const option = (code: string): LanguageOption =>
    metadata.find((entry) => entry.value === code) ?? {
      value: code,
      label: code.toUpperCase(),
      locale: code,
    };
  return {
    uiLanguages: uiCodes.map(option),
    modelLanguages: modelCodes.map(option),
    defaultLanguage,
  };
}

@Injectable({ providedIn: 'root' })
export class LanguageSettingsService {
  private readonly defaults = inject(LANGUAGE_CONFIG);
  private readonly configuration = signal(resolveLanguages(this.defaults));
  readonly uiLanguages = computed(() => this.configuration().uiLanguages);
  readonly modelLanguages = computed(() => this.configuration().modelLanguages);
  readonly defaultLanguage = computed(() => this.configuration().defaultLanguage);
  /** The languages whose UI bundle this build ships, whatever the backend settings enable. */
  readonly bundleLanguages: readonly string[] = this.defaults.uiLanguages;
  /** The languages this build ships branding for. */
  readonly brandingLanguages: readonly string[] = this.defaults.brandingLanguages ?? [];

  configure(settings: Record<string, unknown>): void {
    this.configuration.set(resolveLanguages(this.defaults, settings));
  }
}
