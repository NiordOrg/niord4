import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';

import { AuthFacade } from '../../../../../core/auth/facades/auth.facade';
import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { Menu } from '../../../../../shared/components/menu/menu';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import { Icon } from '../../../../../shared/icons/icon';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { MailingListsApiService } from '../../../services/api/mailing-lists-api.service';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { MailingListEditor, type MailingListEditorModel } from './mailing-list-editor';
import { MailingListRecipientsDrawer } from './mailing-list-recipients-drawer';
import { MailingListsFacade } from './mailing-lists.facade';
import type { MailingList } from './mailing-list.model';

/**
 * Mailing Lists section: client-filtered, client-paged list with a create/edit/copy drawer,
 * confirm-delete, and JSON import/export. Mutations (create/edit/delete/import) are sysadmin-only;
 * plain admins see the list and manage recipients. The list shows the current-language desc name +
 * description (the endpoint lang-resolves server-side); editing fetches the all-language details.
 */
@Component({
  selector: 'app-mailing-lists-page',
  imports: [
    AdminSectionPage,
    Icon,
    Menu,
    Pager,
    Confirm,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    JsonImportDialog,
    MailingListEditor,
    MailingListRecipientsDrawer,
    TranslocoPipe,
  ],
  providers: [MailingListsFacade, ConfirmService],
  templateUrl: './mailing-lists-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class MailingListsPage {
  protected readonly facade = inject(MailingListsFacade);
  protected readonly api = inject(MailingListsApiService);
  private readonly auth = inject(AuthFacade);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  /** Mutations (create/edit/copy/delete/import/export) are sysadmin-only. */
  protected readonly canManage = this.auth.hasRole('sysadmin');

  protected readonly search = signal('');

  /** Filter on the ID, current-language name, OR description, case-insensitive; crash-safe. */
  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const lists = this.facade.mailingLists();
    if (!term) {
      return lists;
    }
    return lists.filter((list) => {
      if (list.mailingListId.toLowerCase().includes(term)) {
        return true;
      }
      const desc = list.descs[0];
      return (
        desc !== undefined &&
        (desc.name.toLowerCase().includes(term) || desc.description.toLowerCase().includes(term))
      );
    });
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);

  protected readonly editorModel = signal<MailingListEditorModel | undefined>(undefined);
  /** The list whose recipients drawer is open (`undefined` = closed). Open to plain admins. */
  protected readonly recipientsModel = signal<MailingList | undefined>(undefined);

  private readonly exportLabel = translateSignal('admin.mailingLists.menu.export');
  private readonly importLabel = translateSignal('admin.mailingLists.menu.import');

  protected readonly cogItems = computed<MenuItem[]>(() => [
    {
      label: this.exportLabel(),
      command: () => this.downloads.openTicketed(this.api.exportUrl(), 'sysadmin'),
    },
    { label: this.importLabel(), command: () => this.importDialog().open() },
  ]);

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  protected addMailingList(): void {
    this.editorModel.set({
      mode: 'add',
      mailingList: { mailingListId: '', active: true, descs: [], triggers: [] },
    });
  }

  protected editMailingList(list: MailingList): void {
    this.api.getDetails(list.mailingListId).subscribe({
      next: (details) => this.editorModel.set({ mode: 'edit', mailingList: details }),
      error: () => this.notifications.error('admin.mailingLists.toast.loadError'),
    });
  }

  /** Copy = load the full details, then open in add mode with the id cleared (server POSTs create). */
  protected copyMailingList(list: MailingList): void {
    this.api.getDetails(list.mailingListId).subscribe({
      next: (details) =>
        this.editorModel.set({ mode: 'add', mailingList: { ...details, mailingListId: '' } }),
      error: () => this.notifications.error('admin.mailingLists.toast.loadError'),
    });
  }

  protected deleteMailingList(list: MailingList): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.mailingLists.confirmDelete.header'),
      message: this.transloco.translate('admin.mailingLists.confirmDelete.message', {
        id: list.mailingListId,
      }),
      accept: () => {
        this.api.remove(list.mailingListId).subscribe({
          next: () => {
            this.notifications.success('admin.mailingLists.toast.deleted');
            this.facade.refresh();
          },
          error: () => this.notifications.error('admin.mailingLists.toast.deleteError'),
        });
      },
    });
  }

  protected manageRecipients(list: MailingList): void {
    this.recipientsModel.set(list);
  }

  protected onRecipientsSaved(): void {
    // A recipient save changes the count — refresh the list (the drawer stays open).
    this.facade.refresh();
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.facade.refresh();
  }

  protected onImportClosed(): void {
    this.facade.refresh();
  }
}
