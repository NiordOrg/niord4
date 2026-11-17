import { Component, computed, inject, Injector, Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { AppConfigService } from '../../../core/services/app-config.service';
import { currentYearInZone } from '../../utils/current-year';
import { openInfoDialog } from '../dialog/open-dialog';
import { loadLegalDialog } from '../legal-dialog/load-legal-dialog';

/** The legal documents the footer offers, each under the branding's label for it. */
const LEGAL_DOCUMENTS = [
  { documentId: 'disclaimer', labelKey: 'FOOTER_DISCLAIMER' },
  { documentId: 'cookies', labelKey: 'FOOTER_COOKIES' },
] as const;

/**
 * The footer of a deployment that brings no footer of its own: the product's copyright line,
 * and the logo, the accessibility statement and the legal documents each when the branding
 * labels it. The words are all the branding's: a deployment that labels nothing gets the
 * copyright line alone, and the key is the one a country's branding file words with the
 * authority's name.
 */
@Component({
  selector: 'app-default-footer',
  imports: [TranslocoPipe],
  template: `
    <footer class="app-shell-gutter bg-chrome py-2 text-center text-chrome-muted">
      <span [innerHTML]="'FOOTER_COPYRIGHT' | transloco: { year }"></span>
      @if (logoSrc(); as src) {
        <img
          class="ml-4 inline-block h-5.5 w-auto align-middle"
          [src]="src"
          [height]="22"
          [alt]="logoAlt()"
        />
      }
      @if (accessibilityLabel() && accessibilityStatement; as statement) {
        <a
          class="link-button is-quiet ml-4 text-chrome-muted hover:text-chrome-fg"
          [href]="statement"
        >
          {{ accessibilityLabel() }}
        </a>
      }
      @for (document of legalDocuments; track document.documentId) {
        @if (document.label(); as label) {
          <button
            type="button"
            class="link-button is-quiet ml-4 text-chrome-muted hover:text-chrome-fg"
            (click)="openLegal(document.documentId)"
          >
            {{ label }}
          </button>
        }
      }
    </footer>
  `,
})
export class DefaultFooterComponent {
  private readonly injector = inject(Injector);
  private readonly appConfig = inject(AppConfigService);
  /**
   * The bundle of the language on screen, branding merged in, from the moment it has arrived.
   * Read as a whole rather than key by key: a label left unset is the usual case, not a missing
   * translation to report.
   */
  private readonly translation = toSignal(inject(TranslocoService).selectTranslation());

  /** The deployment's own calendar year — a copyright label, never a stored instant. */
  readonly year = currentYearInZone(this.appConfig.timeZone());

  /** The address of the deployment's accessibility statement, when its build configures one. */
  protected readonly accessibilityStatement = this.appConfig.accessibilityStatement;

  protected readonly logoSrc = this.branded('FOOTER_LOGO');
  private readonly logoAltText = this.branded('FOOTER_LOGO_ALT');
  /** With no text to describe it, the logo is decoration beside the copyright line. */
  protected readonly logoAlt = computed(() => this.logoAltText() ?? '');
  protected readonly accessibilityLabel = this.branded('FOOTER_ACCESSIBILITY');
  protected readonly legalDocuments = LEGAL_DOCUMENTS.map(({ documentId, labelKey }) => ({
    documentId,
    label: this.branded(labelKey),
  }));

  openLegal(documentId: string): void {
    void openInfoDialog(this.injector, loadLegalDialog, { documentId });
  }

  /** A branding value, once the branding has arrived and only when it sets one. */
  private branded(key: string): Signal<string | undefined> {
    return computed(() => {
      const text: unknown = this.translation()?.[key];
      return typeof text === 'string' && text ? text : undefined;
    });
  }
}
