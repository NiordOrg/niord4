import { DestroyRef, inject, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { ActivatedRouteSnapshot, RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';

import { APP_CONFIG } from '../config/app-config.token';

const SEPARATOR = ' - ';

@Injectable({ providedIn: 'root' })
export class I18nTitleStrategy extends TitleStrategy {
  private readonly transloco = inject(TranslocoService);
  private readonly title = inject(Title);
  private readonly appName = inject(APP_CONFIG).appName;
  private lastTitleKeys: readonly string[] = [];
  private readonly destroyRef = inject(DestroyRef);
  private observingTranslations = false;

  private observeTranslations(): void {
    if (this.observingTranslations) return;
    this.observingTranslations = true;
    // Start after navigation, once runtime language settings and the router are initialized.
    // Loading dictionaries while Router constructs this strategy re-enters its domain interceptor.
    // Re-resolve the stored keys whenever the active language's translation is (re)loaded or
    // replaced. `translate()` in `applyTitle` is synchronous — on a cold load the first
    // NavigationEnd can beat the async translation load (base bundle + dictionary overrides are
    // one forkJoined fetch), which would bake the RAW key into the tab title with nothing ever
    // fixing it. `selectTranslation()` emits AFTER a load completes, after a language switch
    // finishes loading, and after the runtime Dictionary refresh replaces the translation — so
    // this one subscription heals all three cases (and supersedes re-applying on the language
    // signal alone, which fired before the new language had loaded).
    this.transloco
      .selectTranslation()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.applyTitle(this.lastTitleKeys));
  }

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.lastTitleKeys = collectTitleKeys(snapshot.root);
    this.observeTranslations();
    this.applyTitle(this.lastTitleKeys);
  }

  private applyTitle(keys: readonly string[]): void {
    const segments = keys
      .map((key) => this.transloco.translate(key))
      .filter((segment): segment is string => !!segment);
    const composed = [this.appName, ...segments].join(SEPARATOR);
    this.title.setTitle(composed);
  }
}

function collectTitleKeys(route: ActivatedRouteSnapshot): string[] {
  const keys: string[] = [];
  let current: ActivatedRouteSnapshot | null = route;
  while (current) {
    const titleKey = current.routeConfig?.title;
    if (typeof titleKey === 'string') {
      keys.push(titleKey);
    }
    current = current.firstChild;
  }
  return keys;
}
