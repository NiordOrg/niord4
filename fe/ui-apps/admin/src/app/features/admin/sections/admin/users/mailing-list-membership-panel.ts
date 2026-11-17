import { Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import type { MailingList } from '../../../services/api/mailing-lists-api.service';

export interface MembershipToggle {
  readonly list: MailingList;
  /** The DESIRED membership state. */
  readonly recipient: boolean;
}

interface MembershipRow {
  readonly list: MailingList;
  readonly selected: boolean;
}

/**
 * Mailing-list membership editor shared by the user and contact editors: every
 * mailing list with a status dot + ONE toggle button per row.
 *
 * Rows derive ONLY when BOTH sources have resolved — the all-lists catalog and
 * the member-id lookup — so a slow membership fetch can never paint every row
 * unselected before its ids arrive. The panel renders state and emits
 * intents; the hosting editor owns the API calls.
 */
@Component({
  selector: 'app-mailing-list-membership-panel',
  imports: [ProgressBar, TranslocoPipe],
  template: `
    <app-progress-bar [visible]="memberIds() === undefined" />

    @if (rows(); as resolved) {
      @if (resolved.length === 0) {
        <p class="text-sm text-fg-muted">{{ 'admin.users.membership.empty' | transloco }}</p>
      } @else {
        <table class="niord-table">
          <thead>
            <tr>
              <th class="w-20">{{ 'admin.users.membership.column.member' | transloco }}</th>
              <th>{{ 'admin.users.membership.column.name' | transloco }}</th>
              <th>{{ 'admin.users.membership.column.description' | transloco }}</th>
              <th class="w-28">
                <span class="sr-only">{{
                  'admin.users.membership.column.actions' | transloco
                }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            @for (row of resolved; track row.list.mailingListId) {
              <tr>
                <td>
                  <span
                    class="status-dot"
                    [class.is-active]="row.selected"
                    [class.is-inactive]="!row.selected"
                    [attr.aria-label]="
                      (row.selected
                        ? 'admin.users.membership.member'
                        : 'admin.users.membership.notMember'
                      ) | transloco
                    "
                    role="img"
                  ></span>
                </td>
                <td>{{ row.list.name }}</td>
                <td class="text-fg-muted">{{ row.list.description }}</td>
                <td class="text-right whitespace-nowrap">
                  <button
                    type="button"
                    class="btn-secondary"
                    (click)="toggled.emit({ list: row.list, recipient: !row.selected })"
                  >
                    {{
                      (row.selected
                        ? 'admin.users.membership.leave'
                        : 'admin.users.membership.join'
                      ) | transloco
                    }}
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
    }
  `,
  host: { class: 'contents' },
})
export class MailingListMembershipPanel {
  /** The all-lists catalog (lang-resolved names/descriptions). */
  readonly lists = input.required<readonly MailingList[]>();
  /** The member list-ids — `undefined` while the lookup is still in flight. */
  readonly memberIds = input.required<ReadonlySet<string> | undefined>();

  readonly toggled = output<MembershipToggle>();

  /** Defined only once BOTH sources resolved (the two-source race guard). */
  protected readonly rows = computed<MembershipRow[] | undefined>(() => {
    const ids = this.memberIds();
    if (ids === undefined) return undefined;
    return this.lists().map((list) => ({ list, selected: ids.has(list.mailingListId) }));
  });
}
