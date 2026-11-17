import { DestroyRef, inject, Injectable, Injector } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Translation, TranslocoLoader, TranslocoService } from '@jsverse/transloco';
import {
  Observable,
  catchError,
  defer,
  forkJoin,
  map,
  of,
  retry,
  switchMap,
  tap,
  timer,
} from 'rxjs';
import { AppConfigService } from '../core/services/app-config.service';

/** How often a bundle that did not arrive is asked for again before the page goes on without it. */
const BUNDLE_RETRIES = 2;
/** The pause before the first of those, doubled before each one after it. */
const BUNDLE_RETRY_DELAY_MS = 1000;
/** The pause before a bundle that stayed away is asked for once more, in the background. */
const HEAL_DELAY_MS = 30_000;
/** How often that is tried, each after twice the pause of the one before. */
const HEAL_RETRIES = 3;

/**
 * Two files per language: the product's own texts, then the country's branding overlay
 * (authority name, footer links, legal dialog titles, logo alt text). Branding wins, so a
 * deployment retunes those without touching the bundle. The branding file is asked for only in
 * a language the build ships branding for (its language configuration's `brandingLanguages`):
 * the default build ships none and asks for none. A missing branding file is not an error.
 *
 * A bundle that does not arrive is. Transloco caches what the loader returns for the whole
 * visit, so an empty answer would leave every label showing its key name until a reload — and
 * Transloco's own retry cannot be relied on instead: an error it gives up on reaches every
 * `translateSignal` as a thrown read, and with no fallback language configured its fallback
 * strategy throws outright. So the loader never fails. It asks again twice, then shows the words
 * of another language the site offers — the default first — rather than key names, and asks for
 * the missing bundle again in the background a few times; when it arrives it replaces the
 * stand-in on screen. The stand-in is about availability, not a fallback language: every bundle
 * the build ships is complete.
 */
@Injectable({ providedIn: 'root' })
export class NiordTranslocoLoader implements TranslocoLoader {
  private readonly http = inject(HttpClient);
  private readonly appConfig = inject(AppConfigService);
  // Transloco is what calls this loader, so it cannot be injected up front; it is taken when a
  // late bundle has something to hand it.
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);

  getTranslation(lang: string): Observable<Translation> {
    return defer(() => {
      let standingIn = false;
      const bundle$ = this.bundle(lang).pipe(
        retry({
          count: BUNDLE_RETRIES,
          delay: (_error, attempt) => timer(BUNDLE_RETRY_DELAY_MS * 2 ** (attempt - 1)),
        }),
        catchError(() => {
          standingIn = true;
          return this.standIn(lang);
        }),
      );
      return forkJoin([bundle$, this.branding(lang)]).pipe(
        map(([translations, branding]) => ({ ...translations, ...branding })),
        // The missing bundle is asked for again only once Transloco holds the stand-in: one that
        // arrived before it would be overwritten by it when the load completes.
        tap({
          complete: () => {
            if (standingIn) this.healLater(lang);
          },
        }),
      );
    });
  }

  private bundle(lang: string): Observable<Translation> {
    return this.http.get<Translation>(`./assets/i18n/${lang}.json`);
  }

  private branding(lang: string): Observable<Translation> {
    if (!this.appConfig.shipsBranding(lang)) return of({});
    return this.http
      .get<Translation>(`./assets/branding/${lang}.json`)
      .pipe(catchError(() => of({})));
  }

  /**
   * The words of the first other site language whose bundle answers, the default first; each is
   * asked for once. Key names only when none of them does.
   */
  private standIn(lang: string): Observable<Translation> {
    const others = [
      ...new Set([this.appConfig.defaultLanguage(), ...this.appConfig.languages()]),
    ].filter((other) => other !== lang);
    return others.reduceRight<Observable<Translation>>(
      (next, other) =>
        this.bundle(other).pipe(
          map((translations) => {
            console.warn(`The ${lang} texts did not load; showing the ${other} texts meanwhile.`);
            return translations;
          }),
          catchError(() => next),
        ),
      of({}),
    );
  }

  /** Asks for a bundle that stayed away until it arrives or the attempts run out. */
  private healLater(lang: string): void {
    timer(HEAL_DELAY_MS)
      .pipe(
        switchMap(() =>
          forkJoin([this.bundle(lang), this.branding(lang)]).pipe(
            retry({
              count: HEAL_RETRIES,
              delay: (_error, attempt) => timer(HEAL_DELAY_MS * 2 ** attempt),
            }),
          ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        // Replacing, not merging: what is on screen now is another language's words.
        next: ([translations, branding]) =>
          this.injector
            .get(TranslocoService)
            .setTranslation({ ...translations, ...branding }, lang, { merge: false }),
        error: () => undefined,
      });
  }
}
