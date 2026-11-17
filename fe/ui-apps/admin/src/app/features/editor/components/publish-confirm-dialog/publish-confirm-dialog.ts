import { Component, input, model, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../shared/components/dialog/dialog';
import { MessageIdBadge } from '../../../messages/components/message-id-badge/message-id-badge';
import type { Message } from '../../../messages/models/message.model';

/**
 * Publish confirmation. The cascade-cancel checkbox is offered ONLY when referenced PUBLISHED
 * messages exist; `confirmed` carries whether to also cancel them. Checkbox state is dialog-local
 * and reset each time the dialog opens.
 */
@Component({
  selector: 'app-publish-confirm-dialog',
  imports: [FormsModule, TranslocoPipe, Dialog, MessageIdBadge],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      (opened)="cancelReferenced.set(false)"
      [modal]="true"
      [header]="'editor.status.publishDialog.header' | transloco"
      [style]="{ width: '32rem' }"
    >
      <p class="text-sm">{{ 'editor.status.publishDialog.prose' | transloco }}</p>

      @if (referencedMessages().length) {
        <div class="mt-4 flex flex-col gap-2">
          <label for="cascade-cancel" class="flex items-center gap-2">
            <input
              type="checkbox"
              id="cascade-cancel"
              class="checkbox"
              [ngModel]="cancelReferenced()"
              (ngModelChange)="cancelReferenced.set($event)"
            />
            <span class="text-sm">{{
              'editor.status.publishDialog.cascadeLabel' | transloco
            }}</span>
          </label>
          <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm">
            @for (ref of referencedMessages(); track ref.id) {
              <app-message-id-badge [message]="ref" />
            }
          </div>
        </div>
      }

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'term.cancel' | transloco }}
        </button>
        <button type="button" class="btn-primary" (click)="confirm()">
          {{ 'editor.status.publish_msg' | transloco }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class PublishConfirmDialog {
  readonly visible = model(false);
  readonly referencedMessages = input<readonly Message[]>([]);
  readonly confirmed = output<boolean>();

  protected readonly cancelReferenced = signal(false);

  protected confirm(): void {
    this.confirmed.emit(this.cancelReferenced());
    this.visible.set(false);
  }
}
