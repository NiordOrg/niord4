import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import type { Translation } from '@jsverse/transloco';
import { catchError, map, Observable, of, tap, timeout } from 'rxjs';

import { LanguageSettingsService } from './language-settings.service';
import { translationValues } from './translation-values';

/** Cap on the branding fetch, so a slow file cannot stall the first paint. */
const BRANDING_TIMEOUT_MS = 5000;

/**
 * The country's branding: `assets/branding/<lang>.json`, the authority's wording, links and logo,
 * merged over the UI bundle by the translation loader. The base product ships none, so its build
 * requests no branding file; a country names its branded languages in its language
 * configuration (`brandingLanguages`).
 *
 * The service also records which keys the branding set, per loaded language, because what the
 * footer shows is decided by this layer alone, not by the merged translation: the backend's web
 * dictionary has keys of the same names, which the dictionary merge adds to every build — the
 * base product's (`footer.copyright` and others in `defaults/base/texts/en/web.properties` and
 * bootstrap/texts.json) and, on a country's deployment, the rows from its own
 * `web_<lang>.properties`.
 */
@Injectable({ providedIn: 'root' })
export class BrandingService {
  private readonly http = inject(HttpClient);
  private readonly settings = inject(LanguageSettingsService);

  private readonly brandedKeys = signal<Readonly<Record<string, ReadonlySet<string>>>>({});

  /**
   * The branding text for the translation of `lang`, whose UI bundle is `bundleLanguage`.
   * Best-effort: a language without branding, a missing file or a timeout give no text.
   */
  load(lang: string, bundleLanguage: string): Observable<Record<string, string>> {
    if (!this.settings.brandingLanguages.includes(bundleLanguage)) {
      return of({});
    }
    return this.http.get<Translation>(`assets/branding/${bundleLanguage}.json`).pipe(
      timeout(BRANDING_TIMEOUT_MS),
      map((branding) => translationValues(branding)),
      catchError(() => of<Record<string, string>>({})),
      tap((values) => this.record(lang, values)),
    );
  }

  /** Whether the branding loaded for `lang` sets `key`. */
  isBranded(lang: string, key: string): boolean {
    return this.brandedKeys()[lang]?.has(key) ?? false;
  }

  private record(lang: string, values: Record<string, string>): void {
    this.brandedKeys.update((all) => ({ ...all, [lang]: new Set(Object.keys(values)) }));
  }
}
