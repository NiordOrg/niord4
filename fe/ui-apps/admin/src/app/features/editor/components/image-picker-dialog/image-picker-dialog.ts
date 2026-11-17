import { Component, computed, input, model, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';
import { encodeRepoPath } from '@niord/html/repo-path';

import { Dialog } from '../../../../shared/components/dialog/dialog';
import { MessageAttachment as MessageAttachmentViewer } from '../../../messages/components/message-attachment/message-attachment';
import type { Message, MessageAttachment } from '../../../messages/models/message.model';

/**
 * The inline-image picker: a grid of the message's OWN attachments that the Description editors'
 * image command opens. Picking one emits the repository file URL
 * `` `/rest/repo/file/<attachment.path>` `` — the path encoded segment by segment by
 * `encodeRepoPath`, which leaves the encoded form the server sends as it is, with an apostrophe
 * encoded as well — and the host inserts an `<img>` at the cursor. Cancel emits nothing.
 *
 * Deliberately lists ONLY IMAGE attachments — the only shape that renders as an inline `<img>`;
 * offering a non-image attachment (e.g. a PDF) here would insert a broken image.
 */
@Component({
  selector: 'app-image-picker-dialog',
  imports: [TranslocoPipe, Dialog, MessageAttachmentViewer],
  template: `
    <app-dialog
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      [modal]="true"
      [header]="'editor.attachments.picker.header' | transloco"
      [style]="{ width: '40rem' }"
    >
      @if (attachments().length > 0) {
        <div class="grid grid-cols-3 gap-3 sm:grid-cols-4">
          @for (att of attachments(); track $index) {
            <div class="flex items-center justify-center rounded border border-surface-200 p-2">
              <app-message-attachment [attachment]="att" [size]="80" (clicked)="onPick(att)" />
            </div>
          }
        </div>
      } @else {
        <p class="py-4 text-sm text-fg-subtle">
          {{ 'editor.attachments.picker.empty' | transloco }}
        </p>
      }

      <ng-template #footer>
        <button type="button" class="btn-tertiary" (click)="visible.set(false)">
          {{ 'term.cancel' | transloco }}
        </button>
      </ng-template>
    </app-dialog>
  `,
  host: { class: 'contents' },
})
export class ImagePickerDialog {
  /** Two-way visibility. */
  readonly visible = model(false);
  /** The message whose attachments populate the grid (facade-owned, read-only). */
  readonly message = input<Message | undefined>(undefined);
  /** The chosen attachment's repository file URL, emitted on pick. */
  readonly picked = output<string>();

  protected readonly attachments = computed<readonly MessageAttachment[]>(() =>
    (this.message()?.attachments ?? []).filter((att) => att.type?.startsWith('image/')),
  );

  protected onPick(attachment: MessageAttachment): void {
    if (attachment.path) {
      // The link is stored in the message's HTML, and the backend reads it back up to the first
      // quote (RepoPaths.FILE_LINK). encodeURIComponent leaves an apostrophe raw, so it is
      // encoded here too, or `it's.png` would be read back as `it` (REPO4).
      const path = encodeRepoPath(attachment.path).replaceAll("'", '%27');
      this.picked.emit(`/rest/repo/file/${path}`);
    }
    this.visible.set(false);
  }
}
