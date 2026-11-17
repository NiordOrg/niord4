import { Component, inject, model, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { MessageDetailsRenderer } from '../../../../messages/components/message-details-renderer/message-details-renderer';
import type { Message } from '../../../../messages/models/message.model';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';

/**
 * Shows the result of executing a TEMPLATE category against an example message (the "Test…" preview):
 * the executed message rendered per-language with a flag switcher (promulgation shown, no details
 * menu). Opened imperatively by the category editor.
 */
@Component({
  selector: 'app-category-template-result-dialog',
  imports: [LanguageFlag, Dialog, MessageDetailsRenderer, TranslocoPipe],
  templateUrl: './category-template-result-dialog.html',
})
export class CategoryTemplateResultDialog {
  private readonly language = inject(LanguageService);

  readonly visible = model(false);
  protected readonly languages = this.language.modelLanguages;
  protected readonly message = signal<Message | null>(null);
  protected readonly previewLang = signal<string>('');

  protected flagFor(lang: string): string | undefined {
    return this.languages.find((l) => l.value === lang)?.flagPath;
  }

  open(message: Message): void {
    this.message.set(message);
    this.previewLang.set(this.language.selectedLanguage());
    this.visible.set(true);
  }

  protected setLang(lang: string): void {
    this.previewLang.set(lang);
  }
}
