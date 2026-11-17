import { DestroyRef, inject, Injectable, Injector } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Translation, TranslocoLoader, TranslocoService } from '@jsverse/transloco';
import { HttpClient } from '@angular/common/http';
import {
  catchError,
  defer,
  forkJoin,
  map,
  Observable,
  of,
  retry,
  switchMap,
  tap,
  timeout,
  timer,
} from 'rxjs';

import { BrandingService } from './core/i18n/branding.service';
import { DictionaryOverridesService } from './core/i18n/dictionary-overrides.service';
import { mergeDictionaryOverrides } from './core/i18n/merge-dictionary-overrides';
import { LanguageSettingsService } from './core/i18n/language-settings.service';
import { translationValues } from './core/i18n/translation-values';

/** Cap on the dictionary-override fetch so a slow or broken backend can't stall the first paint. */
const OVERRIDE_TIMEOUT_MS = 5000;

/** How often a bundle that did not arrive is asked for again before the app goes on without it. */
const BUNDLE_RETRIES = 2;
/** The pause before the first of those, doubled before the next. */
const BUNDLE_RETRY_DELAY_MS = 1000;
/** The pause before a bundle that stayed away is asked for once more, in the background. */
const HEAL_DELAY_MS = 30_000;
/** How often that is tried, each after twice the pause of the one before. */
const HEAL_RETRIES = 3;

@Injectable({ providedIn: 'root' })
export class TranslocoHttpLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);
  private readonly overrides = inject(DictionaryOverridesService);
  private readonly languages = inject(LanguageSettingsService);
  private readonly branding = inject(BrandingService);
  // Transloco is what calls this loader, so it cannot be injected up front; it is taken when a
  // late bundle has something to hand it.
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);

  getTranslation(lang: string): Observable<Translation> {
    // Three layers, merged key by key BEFORE Transloco stores the bundle, so nothing flashes:
    // the language's own bundle, the country's branding over it, and the sysadmin Dictionary
    // overrides on top. A language with no UI bundle of its own (a content language) takes the
    // deployment default's bundle, else the first UI language's. Every bundle a build ships is
    // staged complete, its gaps filled from English, so no English bundle lies underneath and
    // there is no runtime fallback language.
    // The loader never fails: Transloco's fallback strategy, which a failure reaches, throws
    // when no fallback language is configured. A bundle that does not arrive is asked for again
    // twice, then the load completes with the layers above it alone and the failure is logged.
    // Transloco keeps that for the whole session, so the bundle is then asked for again in the
    // background a few times, and when it arrives the three layers replace it on screen.
    // Branding and overrides are best-effort: a missing file, a fetch failure or a
    // timeout leaves the layers under them, so the app never breaks or hangs on i18n.
    // `mergeDictionaryOverrides` additionally refuses any override that would drop a compiled
    // `{{token}}`, so a dictionary entry with different or absent param names can't blank a value.
    const baseLanguage = this.baseLanguage(lang);
    return defer(() => {
      let bundleMissing = false;
      const bundle$ = this.bundle(baseLanguage).pipe(
        retry({
          count: BUNDLE_RETRIES,
          delay: (_error, attempt) => timer(BUNDLE_RETRY_DELAY_MS * 2 ** (attempt - 1)),
        }),
        catchError((error: unknown) => {
          console.error(`Failed to load the i18n bundle "${baseLanguage}"`, error);
          bundleMissing = true;
          return of<Translation>({});
        }),
      );
      return forkJoin({ base: bundle$, layers: this.layersAbove(lang, baseLanguage) }).pipe(
        map(({ base, layers }) => this.merge(base, layers)),
        // The bundle is asked for again only once Transloco holds this load: one that arrived
        // before it would be overwritten by it when the load completes.
        tap({
          complete: () => {
            if (bundleMissing) this.healLater(lang, baseLanguage);
          },
        }),
      );
    });
  }

  /** The bundle that serves `lang`: its own, else the default's, else the first UI language's. */
  private baseLanguage(lang: string): string {
    const bundles = this.languages.bundleLanguages;
    if (bundles.includes(lang)) return lang;
    const fallback = this.languages.defaultLanguage();
    return bundles.includes(fallback) ? fallback : this.languages.uiLanguages()[0].value;
  }

  private bundle(language: string): Observable<Translation> {
    return this.http.get<Translation>(`assets/i18n/${language}.json`);
  }

  /** The branding and the Dictionary overrides; neither ever fails. */
  private layersAbove(lang: string, baseLanguage: string) {
    return forkJoin({
      branding: this.branding.load(lang, baseLanguage),
      overrides: this.overrides.fetch(lang, this.languages.defaultLanguage()).pipe(
        timeout(OVERRIDE_TIMEOUT_MS),
        catchError(() => of<Record<string, string>>({})),
      ),
    });
  }

  private merge(
    base: Translation,
    {
      branding,
      overrides,
    }: { branding: Record<string, string>; overrides: Record<string, string> },
  ): Translation {
    return mergeDictionaryOverrides({ ...translationValues(base), ...branding }, overrides);
  }

  /**
   * Asks for a bundle that stayed away until it arrives or the attempts run out; the layers
   * above it are asked for again once it is in, since what kept the bundle away may have kept
   * them away too.
   */
  private healLater(lang: string, baseLanguage: string): void {
    timer(HEAL_DELAY_MS)
      .pipe(
        switchMap(() =>
          this.bundle(baseLanguage).pipe(
            retry({
              count: HEAL_RETRIES,
              delay: (_error, attempt) => timer(HEAL_DELAY_MS * 2 ** attempt),
            }),
          ),
        ),
        switchMap((base) =>
          this.layersAbove(lang, baseLanguage).pipe(map((layers) => this.merge(base, layers))),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        // Replacing, not merging: what is on screen now lacks the bundle's every key.
        next: (translation) =>
          this.injector.get(TranslocoService).setTranslation(translation, lang, { merge: false }),
        error: () => undefined,
      });
  }
}
