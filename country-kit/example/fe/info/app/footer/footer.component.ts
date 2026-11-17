import { Component, computed, inject, Injector } from '@angular/core';
import { TranslocoPipe, translateSignal } from '@jsverse/transloco';
import {
  AppConfigService,
  currentYearInZone,
  loadLegalDialog,
  openInfoDialog,
} from '@niord/info/api';

const FOOTER_LOGO = 'FOOTER_LOGO';

/**
 * The example country's footer: the copyright line and the country's motto on the wider side,
 * the logo and the disclaimer on the narrower one. Its two-column split, three fifths to two, is a
 * utility no base product template uses, so the built stylesheet carries it only because the
 * country's templates are in the utility scan.
 */
@Component({
  selector: 'app-footer',
  imports: [TranslocoPipe],
  template: `
    <footer
      class="app-shell-gutter grid gap-2 bg-chrome py-2.5 text-xs text-chrome-muted sm:grid-cols-[3fr_2fr]"
    >
      <div>
        <p>{{ 'FOOTER_COPYRIGHT' | transloco: { year } }}</p>
        <p class="italic">{{ 'country.motto' | transloco }}</p>
      </div>
      <div class="flex items-center gap-3 sm:justify-end">
        @if (logoSrc(); as src) {
          <img
            class="inline-block h-5.5 w-auto"
            [src]="src"
            [height]="22"
            [alt]="'FOOTER_LOGO_ALT' | transloco"
          />
        }
        <button
          type="button"
          class="link-button is-quiet text-xs text-chrome-muted hover:text-chrome-fg"
          (click)="openDisclaimer()"
        >
          {{ 'FOOTER_DISCLAIMER' | transloco }}
        </button>
      </div>
    </footer>
  `,
})
export class FooterComponent {
  private readonly injector = inject(Injector);
  private readonly appConfig = inject(AppConfigService);

  /** The deployment's own calendar year, for the copyright line. */
  protected readonly year = currentYearInZone(this.appConfig.timeZone());

  private readonly logo = translateSignal<string>(FOOTER_LOGO);

  /** The logo the branding names, once the branding has arrived; until then none is requested. */
  readonly logoSrc = computed(() => {
    const src = this.logo();
    return src && src !== FOOTER_LOGO ? src : undefined;
  });

  openDisclaimer(): void {
    void openInfoDialog(this.injector, loadLegalDialog, { documentId: 'disclaimer' });
  }
}
