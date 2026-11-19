import { Component, computed, inject, viewChild } from '@angular/core';
import { translateSignal, TranslocoPipe } from '@jsverse/transloco';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { Icon } from '../../../shared/icons/icon';
import { Menu } from '../../../shared/components/menu/menu';
import { MenuItemDef } from '../../../shared/components/menu/menu-item-def.directive';
import { MenuTrigger } from '../../../shared/components/menu-trigger/menu-trigger';
import type { MenuItem } from '../../../shared/models/menu-item.model';
import { TagPicker } from '../components/tag-picker/tag-picker';
import { MessageComparisonDialogService } from '../dialogs/message-comparison-dialog/message-comparison-dialog.service';
import { MailingListReportsDialog } from '../dialogs/mailing-list-reports-dialog/mailing-list-reports-dialog';
import { MessageImportDialog } from '../dialogs/message-import-dialog/message-import-dialog';
import { MyMailsDialog } from '../dialogs/my-mails-dialog/my-mails-dialog';
import { MessageStatusUpdateDialog } from '../dialogs/message-status-update-dialog/message-status-update-dialog';
import { MessageTagsDialogService } from '../dialogs/message-tags-dialog/message-tags-dialog.service';
import { ConfirmService } from '../../../shared/services/confirm.service';
import { MessageAreaSortLauncher } from '../services/message-area-sort.launcher';
import { MessageExportService } from '../services/message-export.service';
import { MessageListFacade } from '../services/message-list.facade';
import { MessageMailService } from '../services/message-mail.service';
import { MessagePrintService } from '../services/message-print.service';
import { MessageSelectionService } from '../services/message-selection.service';
import { MessagesFilterFacade } from '../services/messages-filter.facade';
import type { SortBy } from '../models/messages-filter-params.model';
import type { MessageStatus } from '../../../shared/models/message-status.model';

interface SortMenuItem extends MenuItem {
  readonly extra: { readonly kind: 'sort'; readonly field: SortBy };
}

@Component({
  selector: 'app-messages-action-menu',
  imports: [
    Icon,
    Menu,
    MenuItemDef,
    MenuTrigger,
    TagPicker,
    MessageImportDialog,
    MessageStatusUpdateDialog,
    MailingListReportsDialog,
    MyMailsDialog,
    TranslocoPipe,
  ],
  template: `
    <app-menu-trigger icon="lucideSettings" [label]="'menu.action' | transloco" [menu]="menuRef" />
    <app-menu #menuRef [model]="items()" [ariaLabel]="'menu.action' | transloco">
      <ng-template appMenuItemDef let-item>
        @if (isSortItem(item)) {
          <app-icon name="lucideArrowDownWideNarrow" [attr.aria-label]="t.sortByAriaPrefix()" />
          <span class="flex-1">{{ item.label }}</span>
          @if (activeSortField() === item.extra.field) {
            <app-icon
              [name]="activeDirectionIconClass()"
              [attr.aria-label]="activeDirectionAriaLabel()"
            />
          }
        } @else {
          @if (item.icon) {
            <app-icon [name]="item.icon" />
          }
          <span class="flex-1">{{ item.label }}</span>
        }
      </ng-template>
    </app-menu>
    <app-tag-picker mode="add" />
    <app-message-import-dialog #importDialog />
    <app-message-status-update-dialog #statusDialog />
    <app-mailing-list-reports-dialog #reportsDialog />
    <app-my-mails-dialog #myMailsDialog />
  `,
})
export class MessagesActionMenu {
  private readonly auth = inject(AuthFacade);
  private readonly selection = inject(MessageSelectionService);
  private readonly filter = inject(MessagesFilterFacade);
  private readonly list = inject(MessageListFacade);
  private readonly printService = inject(MessagePrintService);
  private readonly exportService = inject(MessageExportService);
  private readonly mailService = inject(MessageMailService);
  private readonly tagsManager = inject(MessageTagsDialogService);
  private readonly comparison = inject(MessageComparisonDialogService);
  private readonly areaSortLauncher = inject(MessageAreaSortLauncher);
  private readonly confirm = inject(ConfirmService);

  /**
   * The Action button that opened the menu — the picker anchors here because
   * the clicked menu item is removed from the DOM when the menu closes.
   */
  private readonly menuTrigger = viewChild.required(MenuTrigger);
  private readonly tagPicker = viewChild.required(TagPicker);
  private readonly importDialog = viewChild.required(MessageImportDialog);
  private readonly reportsDialog = viewChild.required(MailingListReportsDialog);
  private readonly myMailsDialog = viewChild.required(MyMailsDialog);
  private readonly statusDialog = viewChild.required(MessageStatusUpdateDialog);

  protected readonly t = {
    print: translateSignal('menu.action.print'),
    printSelection: translateSignal('menu.action.print.selection'),
    mailingListReport: translateSignal('menu.action.report'),
    mail: translateSignal('menu.action.mail'),
    myMails: translateSignal('menu.action.my_mails'),
    tagAdd: translateSignal('menu.action.tag.add_selection'),
    tagRemove: translateSignal('menu.action.tag.remove_selection'),
    tagManage: translateSignal('messages.shell.action.tagManage'),
    selectAll: translateSignal('menu.action.select.all'),
    selectNone: translateSignal('menu.action.select.none'),
    compare: translateSignal('menu.action.message.compare'),
    export: translateSignal('menu.action.export'),
    exportSelection: translateSignal('menu.action.export_selection'),
    import: translateSignal('menu.action.import'),
    updateStatus: translateSignal('menu.action.update_status'),
    sortArea: translateSignal('menu.action.sort_area'),
    sortByArea: translateSignal('messages.shell.action.sortBy.area'),
    sortById: translateSignal('messages.shell.action.sortBy.id'),
    sortByDate: translateSignal('messages.shell.action.sortBy.date'),
    sortByPublishDate: translateSignal('messages.shell.action.sortBy.publishDate'),
    sortByFollowUpDate: translateSignal('messages.shell.action.sortBy.followUpDate'),
    sortByAriaPrefix: translateSignal('messages.shell.action.sortBy.ariaPrefix'),
    sortByAriaAscending: translateSignal('messages.shell.action.sortBy.ariaAscending'),
    sortByAriaDescending: translateSignal('messages.shell.action.sortBy.ariaDescending'),
    indexIncomplete: translateSignal('messages.list.indexIncomplete'),
  } as const;

  protected readonly activeSortField = computed<SortBy>(() => this.filter.sortBy());

  protected readonly activeDirectionIconClass = computed(() =>
    this.filter.sortOrder() === 'ASC' ? 'lucideArrowUp' : 'lucideArrowDown',
  );

  protected readonly activeDirectionAriaLabel = computed(() =>
    this.filter.sortOrder() === 'ASC'
      ? this.t.sortByAriaAscending()
      : this.t.sortByAriaDescending(),
  );

  readonly items = computed<MenuItem[]>(() => {
    const isLoggedIn = this.auth.isAuthenticated();
    // The backend's `user` role in the session domain (a client role there or on the base
    // client). The selection's print and export, the reports, the tag actions and the tag
    // manager all reach endpoints that ask for it; a signed-in account without it would be
    // offered each of them only to be refused with 403.
    const hasDomainRole = this.auth.hasDomainUserRole();
    const isEditor = this.auth.hasRole('editor')();
    const isAdmin = this.auth.hasRole('admin')() || this.auth.hasRole('sysadmin')();
    const sel = this.selection.selectedCount();

    const items: MenuItem[] = [];
    items.push(this.actionItem(this.t.print(), 'lucidePrinter', () => this.runPrintList()));
    if (hasDomainRole && sel > 0) {
      items.push(
        this.actionItem(this.t.printSelection(), 'lucidePrinter', () => this.runPrintSelection()),
      );
    }
    if (hasDomainRole) {
      items.push(
        this.actionItem(this.t.mailingListReport(), 'lucideFile', () =>
          this.reportsDialog().open(),
        ),
      );
    }

    // Sending the mail is the editor's (`POST /rest/message-mail/send` asks for the editor role),
    // and the selection travels in a temporary tag, which asks for the domain role as well.
    if (isEditor && hasDomainRole && sel > 0) {
      items.push({ separator: true });
      items.push(
        this.actionItem(this.t.mail(), 'lucideMail', () =>
          this.mailService
            .mailSelection(this.selection.selectedIds(), this.list.indexIncomplete())
            .subscribe(),
        ),
      );
    }
    // The mails the user sent (from the mail dialog or a comment) and what became of them; the
    // read asks for the user role in the domain.
    if (hasDomainRole) {
      items.push(
        this.actionItem(this.t.myMails(), 'lucideMail', () => this.myMailsDialog().open()),
      );
    }

    if (hasDomainRole && sel > 0) {
      items.push({ separator: true });
      items.push(this.actionItem(this.t.tagAdd(), 'lucideBookmark', () => this.openTagPicker()));
      // Remove opens the same membership picker narrowed to the tags the
      // selection is already in.
      items.push(
        this.actionItem(this.t.tagRemove(), 'lucideBookmark', () =>
          this.openTagPicker({ membershipOnly: true }),
        ),
      );
    }

    // Tag management lives here (no standalone Tags toolbar button) — open to
    // a user with a role in the session domain, selection-independent.
    if (hasDomainRole) {
      items.push(
        this.actionItem(this.t.tagManage(), 'lucideTags', () =>
          this.tagsManager.open({ includeLocked: true }),
        ),
      );
    }

    items.push({ separator: true });
    items.push(this.actionItem(this.t.selectAll(), 'lucideStar', () => this.onSelectAll()));
    if (sel > 0) {
      items.push(this.actionItem(this.t.selectNone(), 'lucideStar', () => this.onClearSelection()));
    }

    if (sel === 2) {
      const [firstId, secondId] = this.selection.selectedIds();
      items.push({ separator: true });
      items.push(
        this.actionItem(this.t.compare(), 'lucideArrowRightLeft', () =>
          this.comparison.open(firstId, secondId),
        ),
      );
    }

    if (isLoggedIn) {
      items.push({ separator: true });
      items.push(this.actionItem(this.t.export(), 'lucideUpload', () => this.runExport()));
      // The selection is exported through a temporary tag, which asks for the domain role.
      if (hasDomainRole && sel > 0) {
        items.push(
          this.actionItem(this.t.exportSelection(), 'lucideUpload', () =>
            this.runExportSelection(),
          ),
        );
      }
    }
    if (isAdmin) {
      items.push(
        this.actionItem(this.t.import(), 'lucideDownload', () => this.importDialog().open()),
      );
    }

    if (sel > 0 && isEditor) {
      items.push({ separator: true });
      items.push(
        this.actionItem(this.t.updateStatus(), 'lucidePencil', () =>
          this.statusDialog().open(this.selection.selectedMessages()),
        ),
      );
    }

    items.push({ separator: true });
    if (isEditor) {
      items.push(
        this.actionItem(this.t.sortArea(), 'lucideArrowUpDown', () => this.sortAreaMessages()),
      );
    }
    items.push(this.sortItem(this.t.sortByArea(), 'AREA'));
    items.push(this.sortItem(this.t.sortById(), 'ID'));
    items.push(this.sortItem(this.t.sortByDate(), 'DATE'));
    items.push(this.sortItem(this.t.sortByPublishDate(), 'PUBLISH_DATE'));
    items.push(this.sortItem(this.t.sortByFollowUpDate(), 'FOLLOW_UP_DATE'));

    return items;
  });

  /** Live action — invokes `command` on click. */
  private actionItem(label: string, icon: string, command: () => void): MenuItem {
    return { label, icon, command };
  }

  private openTagPicker(options?: { membershipOnly: boolean }): void {
    this.tagPicker().open(this.menuTrigger().triggerElement, {
      messageIds: this.selection.selectedIds(),
      membershipOnly: options?.membershipOnly,
    });
  }

  /**
   * Interactive area-sort (no pre-scoped area): seed the dialog's status from the active list filter
   * — PUBLISHED unless the filter excludes it, then DRAFT / VERIFIED if present.
   */
  private sortAreaMessages(): void {
    const statuses = this.filter.effectiveStatusFilter();
    let seed: MessageStatus = 'PUBLISHED';
    if (statuses.length > 0 && !statuses.includes('PUBLISHED')) {
      if (statuses.includes('DRAFT')) {
        seed = 'DRAFT';
      } else if (statuses.includes('VERIFIED')) {
        seed = 'VERIFIED';
      }
    }
    this.areaSortLauncher.sortInteractive(seed);
  }

  private runPrintList(): void {
    this.printService
      .printList({ totalMessageNo: this.list.total(), filterParams: this.filter.requestParams() })
      .subscribe();
  }

  private runPrintSelection(): void {
    this.printService
      .printSelection(this.selection.selectedIds(), this.filter.requestParams())
      .subscribe();
  }

  private runExport(): void {
    this.afterIndexWarning(this.t.export(), () =>
      this.exportService.export({ filterParams: this.filter.requestParams() }).subscribe(),
    );
  }

  private runExportSelection(): void {
    this.afterIndexWarning(this.t.exportSelection(), () =>
      this.exportService.exportSelection(this.selection.selectedIds()).subscribe(),
    );
  }

  /**
   * Runs the export at once; or, while the list on screen was answered from a search index still
   * being rebuilt after a start, once the person has read the list's warning and confirmed it,
   * since messages may be missing from what is exported.
   */
  private afterIndexWarning(acceptLabel: string, run: () => void): void {
    if (!this.list.indexIncomplete()) {
      run();
      return;
    }
    this.confirm.confirmNeutral({ message: this.t.indexIncomplete(), acceptLabel, accept: run });
  }

  protected isSortItem(item: MenuItem | SortMenuItem): item is SortMenuItem {
    return (item as SortMenuItem).extra?.kind === 'sort';
  }

  private sortItem(label: string, field: SortBy): SortMenuItem {
    return {
      label,
      command: () => this.toggleSort(field),
      extra: { kind: 'sort', field },
    };
  }

  private toggleSort(field: SortBy): void {
    this.filter.toggleSort(field);
  }

  private onSelectAll(): void {
    this.selection.selectLoaded(this.list.messages());
  }

  private onClearSelection(): void {
    this.selection.clear();
  }
}
