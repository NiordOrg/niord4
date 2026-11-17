import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { TranslocoPipe } from '@jsverse/transloco';
import { AppConfigService } from '../../../core/services/app-config.service';
import { LanguageService } from '../../../core/services/language.service';
import { DialogFrame } from '../dialog/dialog-frame';
import { ContentLang } from '../../directives/content-lang';
import { Icon } from '../../icons/icon';

/** What the opener hands the dialog: which legal document of the country to render. */
export interface LegalDialogData {
  readonly documentId: string;
}

/**
 * Renders one legal document of the running country: `assets/legal/<document>.<lang>.html`
 * from the country's asset overlay. The country owns the text and the languages it has; this
 * component owns nothing but the chrome, so a third language or a third document is a file,
 * not a code change.
 *
 * The HTML is bound with [innerHTML], so Angular sanitises it. The documents use <p>, <h3>,
 * <dl>/<dt>/<dd>, <a href target rel> and `class` — every one of them is in Angular's element
 * and attribute allow-list, so nothing is stripped (asserted by the spec). These files arrive
 * over the network, so nothing may ever mark them trusted on the way in.
 *
 * A document the country has not written in the reader's language is shown in the site's
 * default language — the language the country writes its documents in first — and failing that
 * in the next language the site offers, and it says so with its `lang`. English is not a fallback of its own: on a Danish site
 * that also offers Kalaallisut, a Kalaallisut reader gets the Danish document.
 */
@Component({
  selector: 'app-legal-dialog',
  imports: [TranslocoPipe, DialogFrame, ContentLang, Icon],
  template: `
    <app-dialog-frame [title]="titleKey() | transloco">
      <!-- A live region from the start, so a document that could not be read says so when that
           is known, rather than leaving a title over an empty body. -->
      <div role="alert">
        @if (failed()) {
          <p class="message message-error">
            <app-icon name="lucideTriangleAlert" class="message-icon" />
            {{ 'DOCUMENT_LOAD_FAILED' | transloco }}
          </p>
        }
      </div>
      <div class="legal-document" [appContentLang]="contentLang()" [innerHTML]="content()"></div>
      <footer class="dialog-foot justify-center">
        <button type="button" class="btn-primary" (click)="dialogRef.close()">
          {{ 'BTN_CLOSE' | transloco }}
        </button>
      </footer>
    </app-dialog-frame>
  `,
})
export class LegalDialogComponent implements OnInit {
  readonly dialogRef = inject<DialogRef<void>>(DialogRef);
  private readonly data = inject<LegalDialogData>(DIALOG_DATA);
  private readonly http = inject(HttpClient);
  private readonly language = inject(LanguageService).language;
  private readonly appConfig = inject(AppConfigService);
  private readonly destroyRef = inject(DestroyRef);

  readonly documentId = this.data.documentId;

  readonly content = signal('');
  /** The language of the document that was found, which is not always the one asked for. */
  readonly contentLang = signal<string | undefined>(undefined);
  /** No language the site offers had the document, or none of the reads arrived. */
  readonly failed = signal(false);

  titleKey(): string {
    return `LEGAL_TITLE_${this.documentId.toUpperCase()}`;
  }

  ngOnInit(): void {
    // The reader's language, then the site's default, then whatever else the site offers.
    const candidates = [
      this.language(),
      this.appConfig.defaultLanguage(),
      ...this.appConfig.languages(),
    ];
    this.load([...new Set(candidates)]);
  }

  private load(languages: string[]): void {
    const [lang, ...rest] = languages;
    if (!lang) {
      this.content.set('');
      this.failed.set(true);
      return;
    }
    this.http
      .get(`assets/legal/${this.documentId}.${lang}.html`, { responseType: 'text' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (html) => {
          // A missing asset is answered with index.html by the SPA fallback of both the dev
          // server and the container, so a 200 is not proof that the file exists.
          if (html.includes('<app-root')) {
            this.load(rest);
            return;
          }
          this.content.set(html);
          this.contentLang.set(lang);
        },
        error: () => this.load(rest),
      });
  }
}
