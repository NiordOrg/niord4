import { Component, computed, effect, inject, signal } from '@angular/core';
import { form, FormField, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { HelpAnchor } from '../../../../core/main-shell/help/help-anchor';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { Icon } from '../../../../shared/icons/icon';
import { EmailAddressField } from '../../../../shared/forms/email-address-field/email-address-field';
import { MessageMailDialogService } from './message-mail-dialog.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Select } from '../../../../shared/forms/select/select';

interface MailFormModel {
  language: string;
  emailAddresses: readonly string[];
  mailSubject: string;
  mailMessage: string;
}

@Component({
  selector: 'app-message-mail-dialog',
  templateUrl: './message-mail-dialog.html',
  imports: [Dialog, EmailAddressField, FormField, HelpAnchor, Icon, Select, TranslocoPipe],
})
export class MessageMailDialog {
  protected readonly language = inject(LanguageService);
  protected readonly dialog = inject(MessageMailDialogService);

  private readonly formModel = signal<MailFormModel>({
    language: this.language.selectedLanguage(),
    emailAddresses: [],
    mailSubject: '',
    mailMessage: '',
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.mailSubject);
  });

  /**
   * Net change against the opened (empty) values. Signal Forms' `dirty` tracks
   * interaction and never clears on revert, so a serialized baseline comparison
   * gates Send instead.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  protected readonly sendDisabled = computed(
    () =>
      this.dialog.sending() ||
      !this.changed() ||
      this.form().invalid() ||
      this.formModel().emailAddresses.length === 0,
  );

  protected readonly totalMessageNo = computed(() => this.dialog.totalMessageNo());
  protected readonly indexIncomplete = computed(() => this.dialog.indexIncomplete());

  constructor() {
    // Reset the form every time the dialog opens — leftover state from the
    // previous open would leak into the next selection. A failed send keeps
    // the dialog open, so what was typed stays until the send succeeds.
    effect(() => {
      if (this.dialog.visible()) {
        const next: MailFormModel = {
          language: this.language.selectedLanguage(),
          emailAddresses: [],
          mailSubject: '',
          mailMessage: '',
        };
        this.formModel.set(next);
        this.baselineJson.set(JSON.stringify(next));
        this.form().reset();
      }
    });
  }

  /** Runs the send; the dialog closes only when it succeeds (see MessageMailDialogService). */
  protected onSend(): void {
    if (this.sendDisabled()) return;
    const { language, emailAddresses, mailSubject, mailMessage } = this.formModel();
    this.dialog.send({ language, emailAddresses, mailSubject, mailMessage });
  }
}
