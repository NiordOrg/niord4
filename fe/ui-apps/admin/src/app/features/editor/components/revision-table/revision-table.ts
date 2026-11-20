import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe } from '@jsverse/transloco';

import type { MessageHistory } from '../../../messages/models/message-history.model';
import { LocaleDatePipe } from '../../../../shared/pipes/locale-date.pipe';

/**
 * Revision list with a ≤2-select cap: an unselected row's checkbox disappears once two are chosen,
 * and toggling re-emits so the page can recompute the diff + attachments. Shares the `.niord-table`
 * chrome with the other list tables; an empty history deliberately shows ONLY the header, with NO
 * "no revisions" empty row.
 *
 * A row is the entry itself, never its version number: a message carried over from an older system
 * can hold two entries with the same number, and each is selected on its own.
 */
@Component({
  selector: 'app-revision-table',
  imports: [FormsModule, LocaleDatePipe, TranslocoPipe],
  template: `
    <table class="niord-table">
      <thead>
        <tr>
          <th class="w-10"></th>
          <th>{{ 'editor.history.col.version' | transloco }}</th>
          <th>{{ 'editor.history.col.created' | transloco }}</th>
          <th>{{ 'editor.history.col.user' | transloco }}</th>
          <th>{{ 'editor.history.col.status' | transloco }}</th>
        </tr>
      </thead>
      <tbody>
        @for (rev of revisions(); track $index) {
          <tr>
            <td>
              @if (isSelected(rev) || selected().length < 2) {
                <input
                  type="checkbox"
                  class="checkbox"
                  [ngModel]="isSelected(rev)"
                  (ngModelChange)="toggleRevision.emit(rev)"
                  [attr.aria-label]="'editor.history.select' | transloco"
                />
              }
            </td>
            <td class="tabular-nums">{{ rev.version }}</td>
            <td>{{ rev.created | localeDate: 'dateTime' }}</td>
            <td>{{ rev.user ?? ('editor.history.systemUser' | transloco) }}</td>
            <td>{{ 'msg.status.' + rev.status | transloco }}</td>
          </tr>
        }
      </tbody>
    </table>
  `,
  host: { class: 'contents' },
})
export class RevisionTable {
  readonly revisions = input<readonly MessageHistory[]>([]);
  readonly selected = input<readonly MessageHistory[]>([]);
  readonly toggleRevision = output<MessageHistory>();

  protected isSelected(rev: MessageHistory): boolean {
    return this.selected().includes(rev);
  }
}
