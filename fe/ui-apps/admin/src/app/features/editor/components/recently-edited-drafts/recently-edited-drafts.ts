import { Component, computed, inject } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { Router } from '@angular/router';
import { TranslocoPipe } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { Card } from '../../../../shared/components/card/card';
import { Icon } from '../../../../shared/icons/icon';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { MessageIdBadge } from '../../../messages/components/message-id-badge/message-id-badge';
import type { Message } from '../../../messages/models/message.model';
import { MessageDetailsDialogService } from '../../../messages/services/message-details-dialog.service';
import { MessageEditShortcutService } from '../../../messages/services/message-edit-shortcut.service';

/**
 * The current user's recently-edited drafts, a quick-access list inside the editor. This is the
 * ONLY editor fetch that refetches on language change — the editable working copy
 * never does. Hidden entirely when there are no drafts.
 */
@Component({
  selector: 'app-recently-edited-drafts',
  imports: [TranslocoPipe, Card, MessageIdBadge, Icon],
  template: `
    @if (drafts().length) {
      <app-card [header]="'editor.recently_edited' | transloco" [padded]="false">
        <table class="niord-table">
          <tbody>
            @for (draft of drafts(); track draft.id) {
              <tr class="row-interactive">
                <td class="w-px whitespace-nowrap">
                  <app-message-id-badge [message]="draft" />
                </td>
                <td class="max-w-0">
                  <button
                    type="button"
                    class="block w-full truncate text-left hover:underline"
                    (click)="openDetails(draft.id)"
                  >
                    {{ titleOf(draft) || ('messages.common.untitled' | transloco) }}
                  </button>
                </td>
                <td class="w-px whitespace-nowrap">
                  <button
                    type="button"
                    class="icon-button text-xs"
                    [attr.aria-label]="'editor.drafts.edit' | transloco"
                    (click)="edit(draft.id)"
                  >
                    <app-icon name="lucidePencil" />
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </app-card>
    }
  `,
  host: { class: 'contents' },
})
export class RecentlyEditedDrafts {
  private readonly language = inject(LanguageService);
  private readonly router = inject(Router);
  private readonly detailsDialog = inject(MessageDetailsDialogService);
  // Load-bearing injection, not decorative: this root service installs the document key
  // listeners in its constructor, so without a consumer here the shortcut would stay dead
  // for anyone who lands on the editor without passing through the message list first.
  private readonly editShortcut = inject(MessageEditShortcutService);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  // A failed fetch leaves the resource in its error state (value() throws); read via hasValue()
  // so an outage degrades to an empty list.
  private readonly resource = httpResource<readonly Message[]>(
    () => ({
      url: `${this.apiBaseUrl}/messages/recently-edited-drafts`,
      params: { lang: this.language.selectedLanguage(), maxMessageNo: '20' },
    }),
    { defaultValue: [] },
  );

  protected readonly drafts = computed<readonly Message[]>(() =>
    this.resource.hasValue() ? this.resource.value() : [],
  );

  protected titleOf(draft: Message): string {
    // descs[0] is deliberate, not a missing lang-lookup: the fetch sends `lang`, and the backend's
    // DataFilter returns ONLY that language's desc (first-desc fallback when absent) — the payload
    // never carries more than one desc, and a language switch refetches.
    return draft.descs?.[0]?.title ?? '';
  }

  /** Navigate by UUID (the badge renders shortId/placeholder, but navigation always uses `id`). */
  protected edit(id: string): void {
    void this.router.navigate(['/editor/edit', id]);
  }

  /** Open the details dialog seeded with the whole drafts list so prev/next navigation works. */
  protected openDetails(id: string): void {
    // Hold-E turns the row into a direct edit, matching how the message list behaves. The
    // per-message permission check the list performs has nothing to check here: this endpoint
    // returns only the current user's own drafts in the active domain, and its payload is
    // trimmed to the title — so editability is a property of the list, not of the row.
    if (this.editShortcut.editKeyHeld()) {
      this.edit(id);
      return;
    }
    this.detailsDialog.open(
      id,
      this.drafts().map((draft) => draft.id),
    );
  }
}
