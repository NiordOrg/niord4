import { DestroyRef, inject, Injectable } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';
import { TranslocoService } from '@jsverse/transloco';

/** The site's own name, and what separates it from the view the reader is on. */
const SITE_TITLE = 'SITE_TITLE';
const SEPARATOR = ' - ';

/**
 * The browser tab: the site's name, and the view the reader is on when the route names one.
 * The key a route carries is one of the main menu's own — the words the reader clicked.
 *
 * `selectTranslation()` rather than a one-shot translate: on a cold load the first navigation
 * beats the bundle, which would bake the raw key into the tab with nothing ever fixing it, and
 * the same subscription re-applies the title after a language switch.
 */
@Injectable({ providedIn: 'root' })
export class InfoTitleStrategy extends TitleStrategy {
  private readonly transloco = inject(TranslocoService);
  private readonly title = inject(Title);
  private readonly destroyRef = inject(DestroyRef);

  private titleKey: string | undefined;
  private observing = false;

  override updateTitle(snapshot: RouterStateSnapshot): void {
    this.titleKey = this.buildTitle(snapshot);
    this.observe();
    this.apply();
  }

  private observe(): void {
    if (this.observing) return;
    this.observing = true;
    this.transloco
      .selectTranslation()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(() => this.apply());
  }

  /**
   * Until the bundle has landed the tab keeps the title the page was served with rather than
   * reading "SITE_TITLE - MENU_DETAILS"; the subscription applies the real one when the words
   * arrive. Whether they have is asked of the loaded bundle, not of `translate()`, which reports a
   * key it cannot translate as missing.
   */
  private apply(): void {
    const bundle = this.transloco.getTranslation(this.transloco.getActiveLang());
    if (!(SITE_TITLE in bundle)) return;
    const site = this.transloco.translate<string>(SITE_TITLE);
    const view = this.titleKey ? this.transloco.translate<string>(this.titleKey) : '';
    this.title.setTitle(view && view !== this.titleKey ? site + SEPARATOR + view : site);
  }
}
