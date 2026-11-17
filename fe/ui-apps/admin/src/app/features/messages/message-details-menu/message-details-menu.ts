import { Component, computed, inject, input, signal, viewChild } from '@angular/core';
import type { FocusOrigin } from '@angular/cdk/a11y';
import { httpResource } from '@angular/common/http';
import { Router } from '@angular/router';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { APP_CONFIG } from '../../../core/config/app-config.token';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import { TAG_SELECTION_STORE } from '../../../core/domain/services/tag-selection-store.token';
import { Menu } from '../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../shared/components/menu-trigger/menu-trigger';
import type { MenuItem } from '../../../shared/models/menu-item.model';
import type { Message } from '../models/message.model';
import type { MessageTag } from '../../../shared/models/message-tag.model';
import { TagPicker } from '../components/tag-picker/tag-picker';
import { MessageCopyDialogService } from '../dialogs/message-copy-dialog/message-copy-dialog.service';
import { MessagePrintService } from '../services/message-print.service';
import { MessageTagActionsService } from '../services/message-tag-actions.service';

type MenuSize = 'xs' | 'sm' | 'md';

@Component({
  selector: 'app-message-details-menu',
  imports: [Menu, MenuTrigger, TagPicker, TranslocoPipe],
  templateUrl: './message-details-menu.html',
})
export class MessageDetailsMenu {
  private readonly auth = inject(AuthFacade);
  private readonly domain = inject(DomainFacade);
  private readonly transloco = inject(TranslocoService);
  private readonly tagActions = inject(MessageTagActionsService);
  private readonly lastTagStore = inject(TAG_SELECTION_STORE);
  private readonly printService = inject(MessagePrintService);
  private readonly copyDialog = inject(MessageCopyDialogService);
  private readonly router = inject(Router);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  readonly messageId = input.required<string>();
  readonly message = input.required<Message>();
  readonly size = input<MenuSize>('sm');

  /**
   * The cog button that opened the menu — the picker anchors here because the
   * clicked menu item is removed from the DOM when the menu closes.
   */
  private readonly menuTrigger = viewChild.required(MenuTrigger);
  private readonly tagPicker = viewChild.required(TagPicker);

  /**
   * 0 = never opened (suppresses the initial fetch so a long list doesn't
   * issue N tag requests on mount). Each click bumps the counter, which
   * recomputes the httpResource request and forces a refetch — so the menu
   * always reflects mutations from other surfaces (bulk add, last-tag, etc.).
   */
  private readonly openTick = signal(0);

  private readonly seriesId = computed(() => this.message().messageSeries?.seriesId);

  // Opening a message in the editor is available to any logged-in user whose active domain owns the
  // series: a plain user lands on a read-only working copy but can still add comments. Content
  // mutation stays gated inside the editor (`editableNow`); only Copy — which mints a new message —
  // keeps the editor-role requirement (see `canCopy`).
  protected readonly canEdit = computed(() => {
    if (!this.auth.hasRole('user')()) return false;
    const series = this.seriesId();
    if (!series) return false;
    return this.domain.canEditMessageSeries(series)();
  });
  // Copy creates a brand-new message, so it needs the editor role on top of the shared edit-entry
  // preconditions (logged in + a domain-editable series).
  protected readonly canCopy = computed(() => this.auth.hasRole('editor')() && this.canEdit());
  // Tagging asks for the backend's `user` role in the session domain (a client role there or on
  // the base client); signed in alone, the tag endpoints answer 403.
  protected readonly canTag = computed(() => this.auth.hasDomainUserRole());
  protected readonly canPrint = (): boolean => true;

  protected readonly buttonSize = computed<'small' | 'large' | undefined>(() =>
    this.size() === 'md' ? undefined : 'small',
  );

  protected readonly t = {
    edit: translateSignal('menu.action.edit'),
    copy: translateSignal('menu.action.copy'),
    print: translateSignal('messages.detailsMenu.print'),
    addToTag: translateSignal('menu.action.tag.add'),
    removeFrom: translateSignal('menu.action.tag.remove_named'),
    loading: translateSignal('common.loading'),
    buttonLabel: translateSignal('messages.detailsMenu.buttonLabel'),
  } as const;

  protected readonly tagsResource = httpResource<readonly MessageTag[]>(
    () => {
      // 0 = never opened; suppress the fetch until the menu first opens. Each open bumps the tick,
      // which recomputes this request into a fresh object and forces a refetch — so the "Remove
      // from…" rows always reflect tag mutations made on other surfaces (bulk add, last-tag, etc.).
      if (this.openTick() === 0) return undefined;
      return {
        url: `${this.apiBaseUrl}/tags/message/${encodeURIComponent(this.messageId())}`,
        params: { locked: 'false' },
      };
    },
    { defaultValue: [] },
  );

  private readonly lastTag = this.lastTagStore.getLastSelection();

  /** Returns the last-selected tag iff it's stored AND the current message is NOT already in it. */
  protected readonly addToLastTagCandidate = computed<MessageTag | undefined>(() => {
    const last = this.lastTag();
    if (!last) return undefined;
    const tagsInMessage = this.tagsResource.hasValue() ? this.tagsResource.value() : [];
    if (tagsInMessage.some((t) => t.tagId === last.tagId)) return undefined;
    return last;
  });

  /**
   * The "Remove from…" submenu children — one per tag the message is in. Kept as
   * its OWN computed so the child array reference is stable while other menu inputs
   * change (a fresh array on every `items()` recompute churned the open CDK submenu).
   */
  private readonly removeFromTagItems = computed<MenuItem[]>(() =>
    (this.tagsResource.hasValue() ? this.tagsResource.value() : []).map((tag) => ({
      label: tag.name,
      icon: 'lucideTag',
      command: () =>
        this.tagActions.removeMessagesFromTag([this.messageId()], tag).subscribe({
          next: (result) => {
            if (result) this.tagsResource.reload();
          },
        }),
    })),
  );

  readonly items = computed<MenuItem[]>(() => {
    const items: MenuItem[] = [];

    if (this.canEdit()) {
      items.push({
        label: this.t.edit(),
        icon: 'lucidePencil',
        command: () => void this.router.navigate(['/editor/edit', this.messageId()]),
      });
      if (this.canCopy()) {
        items.push({
          label: this.t.copy(),
          icon: 'lucideCopy',
          command: () => this.openCopyDialog(),
        });
      }
      items.push({ separator: true });
    }

    if (this.canPrint()) {
      items.push({
        label: this.t.print(),
        icon: 'lucidePrinter',
        command: () => this.printService.printMessage(this.messageId()).subscribe(),
      });
    }

    if (this.canTag()) {
      items.push({ separator: true });
      items.push({
        label: this.t.addToTag(),
        icon: 'lucideTag',
        command: () => this.openTagPicker(),
      });

      const lastTagForAdd = this.addToLastTagCandidate();
      if (lastTagForAdd) {
        items.push({
          label: this.transloco.translate('menu.action.tag.add_named', {
            name: lastTagForAdd.name,
          }),
          icon: 'lucideTag',
          command: () =>
            this.tagActions.addMessagesToLastSelectedTag([this.messageId()]).subscribe({
              next: (tag) => {
                if (tag) this.tagsResource.reload();
              },
            }),
        });
      }

      // "Remove from…" submenu — one child per (unlocked) tag the message is
      // in. Nesting the tag list keeps the top-level menu height stable: it can
      // only shrink (by the add-to-last-tag row), never grow with the tag count.
      // While the membership list is loading, a disabled placeholder stands in;
      // once it resolves, the parent disappears entirely if the message is in no
      // tags.
      const removeItems = this.removeFromTagItems();
      if (this.tagsResource.isLoading()) {
        items.push({
          label: this.t.removeFrom(),
          icon: 'lucideTag',
          items: [
            { label: this.t.loading(), icon: 'lucideLoaderCircle', spin: true, disabled: true },
          ],
        });
      } else if (removeItems.length > 0) {
        items.push({
          label: this.t.removeFrom(),
          icon: 'lucideTag',
          items: removeItems,
        });
      }
    }

    return items;
  });

  /**
   * Route the copy through the shared copy dialog (NM messages may add a back-reference to the
   * original). Navigation happens on confirm with the dialog's resolved copy route; cancel is a
   * no-op. The dialog host is mounted by the surrounding messages route.
   */
  private openCopyDialog(): void {
    this.copyDialog.open(this.messageId(), this.message().mainType).subscribe((commands) => {
      if (commands) {
        void this.router.navigate(commands);
      }
    });
  }

  /**
   * Full membership editing (add AND remove, multi-select) lives in the picker;
   * the menu only offers quick add + a per-tag "Remove from…" submenu shortcut.
   */
  private openTagPicker(): void {
    this.tagPicker().open(this.menuTrigger().triggerElement, {
      messageIds: [this.messageId()],
      displayId: this.message().shortId,
    });
  }

  /**
   * Opens the menu as a use of its cog button would. `MessageRowMenu` calls it right after it has
   * put this menu in place of its own stand-in button, which the user has just used.
   */
  open(item: 'first' | 'last', origin: FocusOrigin): void {
    this.menuTrigger().openFromButton(item, origin);
  }

  /**
   * Fired from the trigger's `(opened)`. Refetches this message's tags so the
   * "Remove from…" submenu is fresh — the menu opens instantly with its static
   * items + the placeholder submenu; the tag children swap in when the fetch
   * resolves. (CDK owns the open/close mechanics.)
   */
  protected onOpened(): void {
    this.openTick.update((n) => n + 1);
  }
}
