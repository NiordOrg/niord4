import {
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
  viewChildren,
} from '@angular/core';
import { httpResource } from '@angular/common/http';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterLinkActive } from '@angular/router';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';
import { filter, map } from 'rxjs';

import { Menu } from '../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../shared/components/menu-trigger/menu-trigger';
import { Icon } from '../../../../shared/icons/icon';
import type { MenuItem } from '../../../../shared/models/menu-item.model';
import type { MessageTag } from '../../../../shared/models/message-tag.model';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { TAG_SELECTION_STORE } from '../../../../core/domain/services/tag-selection-store.token';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { TagPicker } from '../../../messages/components/tag-picker/tag-picker';
import { MessageComparisonDialogService } from '../../../messages/dialogs/message-comparison-dialog/message-comparison-dialog.service';
import { MessagePrintService } from '../../../messages/services/message-print.service';
import { MessageTagActionsService } from '../../../messages/services/message-tag-actions.service';
import { EditorCommentsStore } from '../../services/editor-comments.store';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';

interface TabDescriptor {
  readonly id: 'edit' | 'status' | 'comments' | 'history';
  readonly labelKey: string;
  readonly icon: string;
}

const TABS: readonly TabDescriptor[] = [
  { id: 'edit', labelKey: 'view_mode.edit', icon: 'lucidePencil' },
  { id: 'status', labelKey: 'view_mode.status', icon: 'lucideFlag' },
  { id: 'comments', labelKey: 'view_mode.comments', icon: 'lucideMessageSquare' },
  { id: 'history', labelKey: 'view_mode.history', icon: 'lucideHistory' },
];

/**
 * Editor chrome: the view-mode tab strip + the Actions dropdown, mirroring the messages tab menu
 * (full-bleed bar, centered icon tabs, mobile burger). The Edit tab is always shown; Status /
 * Comments / History tabs (and Print / tag actions) are gated on `message.created` (the "saved at
 * least once" flag — NOT the id, since a copy/template has an id but no `created`).
 */
@Component({
  selector: 'app-editor-action-menu',
  imports: [RouterLink, RouterLinkActive, TranslocoPipe, Icon, Menu, MenuTrigger, TagPicker],
  template: `
    <!-- Full-bleed bar: breaks the border/background out of the editor's centered column so the
         bottom border runs edge-to-edge like the main header; the inner wrapper re-centers the
         nav at content width. z-30 lifts the transform-created stacking context above later
         sticky/overlay siblings. -->
    <div
      class="relative z-30 left-1/2 w-screen -translate-x-1/2 border-b border-surface-200 bg-surface-0"
    >
      <div
        class="relative mx-auto w-full max-w-screen-2xl"
        [attr.aria-label]="'editor.tab.ariaLabel' | transloco"
      >
        <!-- Mobile burger bar (<md). Hidden at md+ where the desktop grid takes over. -->
        <div class="flex items-center gap-2 px-4 py-1 md:hidden">
          <button
            type="button"
            class="icon-button text-xs"
            (click)="toggleBurger()"
            [attr.aria-expanded]="burgerOpen()"
            [attr.aria-label]="'editor.tab.ariaLabel' | transloco"
          >
            <app-icon name="lucideMenu" />
          </button>
          @if (currentTab(); as tab) {
            <span class="inline-flex items-center gap-2 text-sm font-medium text-fg-title">
              <app-icon [name]="tab.icon" />
              <span>{{ tab.labelKey | transloco }}</span>
            </span>
          }
        </div>

        <!-- Mobile burger panel: overlays page content instead of pushing the layout. -->
        @if (burgerOpen()) {
          <div
            class="absolute top-full right-0 left-0 z-20 flex flex-col gap-1 border-b border-surface-200 bg-surface-0 p-2 shadow-lg md:hidden"
          >
            @if (created()) {
              @for (tab of tabs; track tab.id) {
                <a
                  [routerLink]="['/editor', tab.id, id()]"
                  routerLinkActive="bg-surface-100 text-primary-700 font-semibold"
                  class="inline-flex items-center gap-2 rounded px-3 py-2 text-sm font-medium text-fg-title hover:bg-surface-50"
                  (click)="closeBurger()"
                >
                  <app-icon [name]="tab.icon" />
                  <span class="flex-1">{{ tab.labelKey | transloco }}</span>
                  @if (tab.id === 'comments' && commentCount() > 0) {
                    <span
                      class="badge-accent"
                      [attr.aria-label]="'editor.tab.commentCount' | transloco"
                      >{{ commentCount() }}</span
                    >
                  }
                </a>
              }
            } @else {
              <span
                class="inline-flex items-center gap-2 rounded px-3 py-2 text-sm font-semibold text-fg"
              >
                <app-icon name="lucidePencil" />
                <span>{{ 'view_mode.edit' | transloco }}</span>
              </span>
            }
            @if (items().length > 0) {
              <div class="mt-1 flex flex-wrap gap-1 border-t border-surface-200 pt-1">
                <app-menu-trigger
                  icon="lucideSettings"
                  [label]="'editor.action.menu' | transloco"
                  [menu]="mobileMenu"
                  (opened)="onOpened()"
                />
                <app-menu
                  #mobileMenu
                  [model]="items()"
                  [ariaLabel]="'editor.action.menu' | transloco"
                />
              </div>
            }
          </div>
        }

        <!-- Desktop layout (md+). 3-column grid keeps tabs centred regardless of
             right-column width. Hidden below md where the burger takes over. -->
        <nav class="hidden grid-cols-[1fr_auto_1fr] items-center gap-2 px-4 py-2 md:grid">
          <span></span>

          <div class="flex flex-wrap items-center justify-center gap-1">
            @if (created()) {
              @for (tab of tabs; track tab.id) {
                <a
                  [routerLink]="['/editor', tab.id, id()]"
                  routerLinkActive="toolbar-tab-active text-fg font-semibold"
                  class="relative inline-flex items-center gap-2 px-4 py-2 text-sm font-medium text-fg-muted hover:text-fg"
                >
                  <app-icon [name]="tab.icon" />
                  <span>{{ tab.labelKey | transloco }}</span>
                  @if (tab.id === 'comments' && commentCount() > 0) {
                    <span
                      class="badge-accent"
                      [attr.aria-label]="'editor.tab.commentCount' | transloco"
                      >{{ commentCount() }}</span
                    >
                  }
                </a>
              }
            } @else {
              <span
                class="toolbar-tab-active relative inline-flex items-center gap-2 px-4 py-2 text-sm font-semibold text-fg"
              >
                <app-icon name="lucidePencil" />
                <span>{{ 'view_mode.edit' | transloco }}</span>
              </span>
            }
          </div>

          <div class="flex items-center justify-end gap-1">
            <!-- Hidden entirely when no action applies (e.g. the Start screen). -->
            @if (items().length > 0) {
              <app-menu-trigger
                icon="lucideSettings"
                [label]="'editor.action.menu' | transloco"
                [menu]="desktopMenu"
                align="end"
                (opened)="onOpened()"
              />
              <app-menu
                #desktopMenu
                [model]="items()"
                [ariaLabel]="'editor.action.menu' | transloco"
              />
            }
          </div>
        </nav>
      </div>
    </div>

    <!-- No tags-manager dialog host is mounted under the editor routes,
         so the picker's Manage/Create links stay hidden here. -->
    <app-tag-picker mode="add" [showManage]="false" />
  `,
  host: {
    class: 'contents',
    '(document:click)': 'onDocumentClick($event)',
    '(document:keydown.escape)': 'onEscape()',
  },
})
export class EditorActionMenu {
  private readonly router = inject(Router);
  private readonly transloco = inject(TranslocoService);
  private readonly language = inject(LanguageService);
  private readonly printService = inject(MessagePrintService);
  private readonly tagActions = inject(MessageTagActionsService);
  private readonly comparison = inject(MessageComparisonDialogService);
  private readonly lastTagStore = inject(TAG_SELECTION_STORE);
  protected readonly facade = inject(EditorFacade);
  private readonly commentsStore = inject(EditorCommentsStore);
  private readonly fields = inject(EditorFieldsService);
  private readonly hostRef = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  protected readonly tabs = TABS;
  protected readonly burgerOpen = signal(false);

  /**
   * The Actions triggers (desktop + optional burger instance) — the tag picker anchors to the
   * visible one because the clicked menu item is removed from the DOM when the menu closes.
   */
  private readonly menuTriggers = viewChildren(MenuTrigger);
  private readonly tagPicker = viewChild.required(TagPicker);

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  protected readonly created = computed<boolean>(() => !!this.facade.message()?.created);
  protected readonly id = computed<string>(() => this.facade.message()?.id ?? '');
  /**
   * TOTAL comments, not the unacknowledged count the message VO carries: the backend has no total,
   * so it comes from the thread the route-provided store fetches on editor entry.
   */
  protected readonly commentCount = this.commentsStore.count;
  private readonly onEditRoute = computed<boolean>(() => this.url().includes('/editor/edit'));

  /**
   * 0 = never opened; suppress the tag-membership fetch until the Actions menu first opens. Each
   * open bumps the tick, forcing a refetch so the "Remove from…" list + the add-to-last-tag guard
   * reflect tag mutations made elsewhere. Only saved (`created`) messages carry server-side tags.
   */
  private readonly openTick = signal(0);

  private readonly tagsResource = httpResource<readonly MessageTag[]>(
    () => {
      if (this.openTick() === 0 || !this.created()) return undefined;
      return {
        url: `${this.apiBaseUrl}/tags/message/${encodeURIComponent(this.id())}`,
        params: { locked: 'false' },
      };
    },
    { defaultValue: [] },
  );

  /** The last-selected tag iff it's stored AND the message is NOT already in it. */
  private readonly addToLastTagCandidate = computed<MessageTag | undefined>(() => {
    const last = this.lastTagStore.getLastSelection()();
    if (!last) return undefined;
    const inTags = this.tagsResource.hasValue() ? this.tagsResource.value() : [];
    if (inTags.some((tag) => tag.tagId === last.tagId)) return undefined;
    return last;
  });

  /** "Remove from…" submenu children — one per (unlocked) tag the message is in. */
  private readonly removeFromTagItems = computed<MenuItem[]>(() =>
    (this.tagsResource.hasValue() ? this.tagsResource.value() : []).map((tag) => ({
      label: tag.name,
      icon: 'lucideTag',
      command: () =>
        this.tagActions.removeMessagesFromTag([this.id()], tag).subscribe({
          next: (result) => {
            if (result) this.tagsResource.reload();
          },
        }),
    })),
  );

  /** The active tab for the mobile burger-bar label (copy/template routes read as Edit). */
  protected readonly currentTab = computed<TabDescriptor>(() => {
    const url = this.url();
    return this.tabs.find((tab) => url.startsWith(`/editor/${tab.id}`)) ?? this.tabs[0];
  });

  protected toggleBurger(): void {
    this.burgerOpen.update((open) => !open);
  }

  protected closeBurger(): void {
    this.burgerOpen.set(false);
  }

  protected onDocumentClick(event: MouseEvent): void {
    if (!this.burgerOpen()) return;
    const target = event.target as Node | null;
    if (target && this.hostRef.nativeElement.contains(target)) return;
    this.closeBurger();
  }

  protected onEscape(): void {
    if (this.burgerOpen()) this.closeBurger();
  }

  /** Fired from an Actions trigger's `(opened)`: refetch this message's tags so the membership
   * guard + "Remove from…" submenu are fresh (mutations from other surfaces reflect here). */
  protected onOpened(): void {
    this.openTick.update((n) => n + 1);
  }

  protected readonly items = computed<MenuItem[]>(() => {
    // Read the active language so these memoized labels re-translate on a language switch —
    // transloco.translate() is a plain (untracked) call, so without this the menu would keep the
    // previous language until another dependency happens to change.
    this.language.selectedLanguage();
    const id = this.id();
    const created = this.created();
    const menuItems: MenuItem[] = [];

    if (created) {
      menuItems.push({
        label: this.transloco.translate('editor.action.print'),
        icon: 'lucidePrinter',
        command: () => this.printService.printMessage(id).subscribe(),
      });
      menuItems.push({
        label: this.transloco.translate('editor.action.compare'),
        icon: 'lucideArrowRightLeft',
        command: () => this.comparison.open(undefined, id),
      });
    }

    if (created) {
      if (menuItems.length > 0) {
        menuItems.push({ separator: true });
      }
      menuItems.push({
        label: this.transloco.translate('editor.action.addToTag'),
        icon: 'lucideTag',
        command: () => this.openTagPicker(id),
      });

      const lastTagForAdd = this.addToLastTagCandidate();
      if (lastTagForAdd) {
        menuItems.push({
          label: this.transloco.translate('editor.action.addToLastTag', {
            name: lastTagForAdd.name,
          }),
          icon: 'lucideTag',
          command: () =>
            this.tagActions.addMessagesToLastSelectedTag([id]).subscribe({
              next: (tag) => {
                if (tag) this.tagsResource.reload();
              },
            }),
        });
      }

      // "Remove from…" submenu — one child per (unlocked) tag the message is in. A disabled
      // placeholder stands in while the membership list loads; the parent disappears entirely
      // once it resolves to no tags. A failed read says so, with a retry, rather than reading as
      // no tags.
      const removeItems = this.removeFromTagItems();
      if (this.tagsResource.isLoading()) {
        menuItems.push({
          label: this.transloco.translate('menu.action.tag.remove_named'),
          icon: 'lucideTag',
          items: [
            {
              label: this.transloco.translate('common.loading'),
              icon: 'lucideLoaderCircle',
              spin: true,
              disabled: true,
            },
          ],
        });
      } else if (this.tagsResource.error()) {
        menuItems.push({
          label: this.transloco.translate('menu.action.tag.remove_named'),
          icon: 'lucideTag',
          items: [
            {
              label: this.transloco.translate('common.fetchError'),
              icon: 'lucideCircleX',
              disabled: true,
            },
            {
              label: this.transloco.translate('common.retry'),
              icon: 'lucideRefreshCw',
              command: () => this.tagsResource.reload(),
            },
          ],
        });
      } else if (removeItems.length > 0) {
        menuItems.push({
          label: this.transloco.translate('menu.action.tag.remove_named'),
          icon: 'lucideTag',
          items: removeItems,
        });
      }
    }

    if (this.facade.isEditor() && this.onEditRoute()) {
      // No leading separator when nothing precedes (a fresh blank/template message has no
      // print/compare/tag items yet).
      if (menuItems.length > 0) {
        menuItems.push({ separator: true });
      }
      menuItems.push({
        label: this.transloco.translate('menu.action.fields.expand'),
        icon: 'lucideChevronsDown',
        command: () => this.fields.expandAll(),
      });
      menuItems.push({
        label: this.transloco.translate('menu.action.fields.collapse'),
        icon: 'lucideChevronsUp',
        command: () => this.fields.collapseAll(),
      });
    }

    return menuItems;
  });

  private openTagPicker(id: string): void {
    const triggers = this.menuTriggers();
    const anchor =
      triggers.find((trigger) => trigger.triggerElement.offsetParent !== null) ?? triggers[0];
    if (!anchor) {
      return;
    }
    this.tagPicker().open(anchor.triggerElement, {
      messageIds: [id],
      displayId: this.facade.message()?.shortId,
    });
  }
}
