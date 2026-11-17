import { Component, model, output, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { Icon } from '../../../../../shared/icons/icon';

/**
 * The import-emails dialog — a PASTE-of-emails textarea, not a file upload
 * (the JSON-import drop-zone dialog deliberately does not fit here). Import
 * emits the raw text only when non-empty (an empty textarea fires no request);
 * the hosting tab owns the POST + the result surfacing.
 */
@Component({
  selector: 'app-import-emails-dialog',
  imports: [Dialog, Icon, TranslocoPipe],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="emails.set('')"
      [modal]="true"
      [header]="'admin.users.contacts.import.title' | transloco"
      [style]="{ width: '90vw', maxWidth: '36rem' }"
    >
      <p class="mb-2 text-sm text-fg-muted">
        {{ 'admin.users.contacts.import.help' | transloco }}
      </p>
      <textarea
        #emailsArea
        rows="8"
        class="w-full rounded border border-surface-300 bg-surface-0 p-2 font-mono text-xs dark:border-surface-700 dark:bg-surface-900"
        [value]="emails()"
        (input)="emails.set(emailsArea.value)"
        [attr.aria-label]="'admin.users.contacts.import.title' | transloco"
      ></textarea>

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'admin.users.editor.cancel' | transloco }}
        </button>
        <button
          type="button"
          class="btn-primary"
          [disabled]="emails().trim().length === 0"
          (click)="confirm()"
        >
          <app-icon name="lucideDownload" />
          <span>{{ 'admin.users.contacts.import.import' | transloco }}</span>
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class ImportEmailsDialog {
  readonly visible = model<boolean>(false);
  /** The raw pasted text — the backend splits/validates/dedupes server-side. */
  readonly imported = output<string>();

  protected readonly emails = signal('');

  protected confirm(): void {
    const text = this.emails().trim();
    if (text.length === 0) return;
    this.imported.emit(text);
    this.visible.set(false);
  }
}
