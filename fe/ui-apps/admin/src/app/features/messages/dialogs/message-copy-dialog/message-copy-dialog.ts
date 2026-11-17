import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../shared/components/dialog/dialog';
import { MessageCopyDialogService } from './message-copy-dialog.service';

/**
 * Copy-message confirmation. The "Include reference to copied message" checkbox is offered ONLY for
 * NM messages; confirm resolves the copy route with the
 * REFERENCE back-reference when checked. Checkbox state is dialog-local and reset each time the
 * dialog opens. Cancel / Escape / backdrop resolve the open as a dismissal (no navigation).
 */
@Component({
  selector: 'app-message-copy-dialog',
  imports: [FormsModule, TranslocoPipe, Dialog],
  template: `
    <app-dialog
      [visible]="dialog.visible()"
      (visibleChange)="dialog.onVisibleChange($event)"
      (opened)="includeReference.set(false)"
      [modal]="true"
      [header]="'editor.status.copyDialog.title' | transloco"
      [style]="{ width: '28rem' }"
    >
      @if (dialog.mainType() === 'NM') {
        <label for="copy-include-reference" class="flex items-center gap-2">
          <input
            type="checkbox"
            id="copy-include-reference"
            class="checkbox"
            [ngModel]="includeReference()"
            (ngModelChange)="includeReference.set($event)"
          />
          <span class="text-sm">
            {{ 'msg.copy.include_reference' | transloco }}
          </span>
        </label>
      }

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="dialog.dismiss()">
          {{ 'editor.status.copyDialog.cancel' | transloco }}
        </button>
        <button type="button" class="btn-primary" (click)="dialog.confirm(includeReference())">
          {{ 'editor.status.copyDialog.confirm' | transloco }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class MessageCopyDialog {
  protected readonly dialog = inject(MessageCopyDialogService);
  protected readonly includeReference = signal(false);
}
