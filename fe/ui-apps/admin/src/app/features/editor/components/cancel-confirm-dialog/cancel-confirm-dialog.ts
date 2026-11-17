import { Component, input, model, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../shared/components/dialog/dialog';

/**
 * Cancel confirmation. The "Create cancellation message" checkbox is offered ONLY for NM messages;
 * `confirmed` carries whether to draft a cancellation message afterwards. Checkbox state is
 * dialog-local and reset each time the dialog opens.
 */
@Component({
  selector: 'app-cancel-confirm-dialog',
  imports: [FormsModule, TranslocoPipe, Dialog],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="createCancellation.set(false)"
      [modal]="true"
      [header]="'editor.status.cancelDialog.header' | transloco"
      [style]="{ width: '32rem' }"
    >
      <p class="text-sm">{{ 'editor.status.cancelDialog.prose' | transloco }}</p>

      @if (mainType() === 'NM') {
        <label for="create-cancellation" class="mt-4 flex items-center gap-2">
          <input
            type="checkbox"
            id="create-cancellation"
            class="checkbox"
            [ngModel]="createCancellation()"
            (ngModelChange)="createCancellation.set($event)"
          />
          <span class="text-sm">{{ 'editor.status.cancelDialog.createLabel' | transloco }}</span>
        </label>
      }

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'term.cancel' | transloco }}
        </button>
        <button type="button" class="btn-danger" (click)="confirm()">
          {{ 'editor.status.cancel_msg' | transloco }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class CancelConfirmDialog {
  readonly visible = model(false);
  readonly mainType = input<string>('');
  readonly confirmed = output<boolean>();

  protected readonly createCancellation = signal(false);

  protected confirm(): void {
    this.confirmed.emit(this.createCancellation());
    this.visible.set(false);
  }
}
