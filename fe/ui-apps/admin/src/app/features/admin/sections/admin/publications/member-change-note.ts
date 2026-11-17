import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../../shared/icons/icon';
import type { IssueMember } from '../../../services/api/publication-series-api.service';
import { MEMBER_CHANGE_WHY_KEY, memberChangeNotes } from './member-change';

/**
 * Small marks beside a member's status saying what changed since the issue went out.
 *
 * Renders NOTHING for a row that still agrees with its message, and nothing at
 * all on a live list — an open issue resolves its members now, so there is
 * nothing for them to have changed from.
 *
 * An icon, not a line of text. On an issue of any age most rows have moved on,
 * because notices expire, so a sentence on each of them would be the norm and
 * a table full of warnings warns about nothing. The tooltip carries the whole
 * of it: what changed, and that the message is still in this issue because it
 * was published when the issue was decided at its cut-off. The glyph is
 * decorative, so the wrapper carries that text as its accessible name too.
 */
@Component({
  selector: 'app-member-change-note',
  imports: [Icon, TranslocoPipe],
  template: `
    @let why = whyKey | transloco;
    @for (note of notes(); track note.field) {
      @let sentence =
        note.valueKey
          ? (note.key | transloco: { value: note.valueKey | transloco })
          : (note.key | transloco);
      <span
        class="ml-1 inline-flex align-middle text-warning"
        role="img"
        data-testid="member-change-note"
        [attr.aria-label]="sentence + ' ' + why"
        [attr.title]="sentence + ' ' + why"
      >
        <app-icon [name]="note.icon" class="text-xs" />
      </span>
    }
  `,
  host: { class: 'contents' },
})
export class MemberChangeNote {
  /** The member row. A live row carries no change and renders nothing. */
  readonly member = input<IssueMember | undefined>(undefined);

  protected readonly whyKey = MEMBER_CHANGE_WHY_KEY;

  protected readonly notes = computed(() => memberChangeNotes(this.member()));
}
