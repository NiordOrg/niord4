import { HttpClient } from '@angular/common/http';
import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  model,
  signal,
  untracked,
} from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import type { Subscription } from 'rxjs';

import { LanguageSettingsService } from '../../i18n/language-settings.service';
import { Dialog } from '../../../shared/components/dialog/dialog';
import { ErrorBanner } from '../../../shared/components/error-banner/error-banner';
import { LanguageService } from '../services/language.service';

/** The legal documents a country's branding may carry, in the order the footer offers them. */
export const LEGAL_DOCUMENTS = ['disclaimer', 'cookies'] as const;
export type LegalDocument = (typeof LEGAL_DOCUMENTS)[number];

/**
 * One legal document of the country's branding: `assets/legal/<document>.<lang>.html`. The country
 * owns the text and the languages it has; this dialog owns only the chrome, so another document is
 * a file and a branded label, not a code change.
 *
 * The HTML is bound with [innerHTML], so Angular sanitises it: the documents use <h3>, <p> and
 * <dl>/<dt>/<dd>, which pass unchanged. The files arrive over the network, so nothing may mark
 * them trusted on the way in.
 *
 * A document missing in the reader's language is shown in the deployment's default language, then
 * in English, then in any other language the build has branding for, and says so with its `lang`.
 * Only the build's branding languages are asked for: no other language has documents.
 */
@Component({
  selector: 'app-legal-document-dialog',
  imports: [Dialog, ErrorBanner, TranslocoPipe],
  templateUrl: './legal-document-dialog.html',
  host: { class: 'contents' },
})
export class LegalDocumentDialog {
  private readonly http = inject(HttpClient);
  private readonly language = inject(LanguageService);
  private readonly settings = inject(LanguageSettingsService);

  readonly document = input.required<LegalDocument>();
  readonly visible = model(false);

  protected readonly content = signal('');
  /** The language of the document that was found, which is not always the one asked for. */
  protected readonly contentLang = signal<string | undefined>(undefined);
  /** No branding language had the document, or none of the reads arrived. */
  protected readonly failed = signal(false);
  /** Set on the body only when the document is not in the reader's language. */
  protected readonly bodyLang = computed(() => {
    const lang = this.contentLang();
    return lang && lang !== this.language.selectedLanguage() ? lang : null;
  });

  private reading: Subscription | undefined;

  constructor() {
    effect(() => {
      if (!this.visible()) return;
      const id = this.document();
      untracked(() => this.open(id));
    });
    inject(DestroyRef).onDestroy(() => this.reading?.unsubscribe());
  }

  private open(id: LegalDocument): void {
    const branded = this.settings.brandingLanguages;
    const candidates = [
      this.language.selectedLanguage(),
      this.language.defaultLanguage,
      'en',
      ...branded,
    ];
    this.content.set('');
    this.contentLang.set(undefined);
    this.failed.set(false);
    this.read(
      id,
      [...new Set(candidates)].filter((lang) => branded.includes(lang)),
    );
  }

  private read(id: LegalDocument, languages: readonly string[]): void {
    const [lang, ...rest] = languages;
    if (!lang) {
      this.failed.set(true);
      return;
    }
    this.reading?.unsubscribe();
    this.reading = this.http
      .get(`assets/legal/${id}.${lang}.html`, { responseType: 'text' })
      .subscribe({
        next: (html) => {
          // A missing asset is answered with index.html by the SPA fallback of the dev server
          // and of the deployment, so a 200 is not proof that the file exists.
          if (html.includes('<app-root')) {
            this.read(id, rest);
            return;
          }
          this.content.set(html);
          this.contentLang.set(lang);
        },
        error: () => this.read(id, rest),
      });
  }
}
