import { Component, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { Menu } from '../../../../../shared/components/menu/menu';
import { Subject, debounceTime } from 'rxjs';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { Icon } from '../../../../../shared/icons/icon';
import { Pager } from '../../../../../shared/components/pager/pager';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import {
  Contact,
  ContactSearchResult,
  ContactsApiService,
} from '../../../services/api/contacts-api.service';
import { ADMIN_PAGE_SIZE, ADMIN_TEXT_FILTER_DEBOUNCE_MS } from '../../../utils/kit-constants';
import { clampPageToTotal } from '../../../utils/server-page-clamp';
import { ContactEditor, ContactEditorModel } from './contact-editor';
import { ImportEmailsDialog } from './import-emails-dialog';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';

/**
 * The Contacts tab: server-paged contact CRUD (the search page is 0-based on
 * the wire — the API adapter owns the conversion), paste-import of emails
 * (dedup/validation is server-side), and the ticketed CSV export (the export
 * endpoint is PermitAll with a programmatic ADMIN check — the admin-role
 * ticket is mandatory).
 */
@Component({
  selector: 'app-contacts-tab',
  imports: [
    LanguageFlag,
    Icon,
    Menu,
    Pager,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    ContactEditor,
    ImportEmailsDialog,
    TranslocoPipe,
  ],
  templateUrl: './contacts-tab.html',
  host: { class: 'flex min-w-0 flex-col' },
})
export class ContactsTab {
  private readonly languageSettings = inject(LanguageService);
  private readonly api = inject(ContactsApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  protected readonly filter = signal('');
  /** 1-based UI page (the adapter converts to the 0-based wire page). */
  protected readonly page = signal(1);
  protected readonly result = signal<ContactSearchResult>({ data: [], size: 0, total: 0 });
  protected readonly loading = signal(true);
  protected readonly error = signal(false);
  protected readonly editorModel = signal<ContactEditorModel | undefined>(undefined);
  protected readonly importOpen = signal(false);

  protected readonly pageSize = ADMIN_PAGE_SIZE;
  protected readonly first = computed(() => (this.page() - 1) * ADMIN_PAGE_SIZE);

  private lastCommittedFilter = '';
  private readonly filterInput$ = new Subject<string>();
  /**
   * Bumped by every search (the clamp's re-search too); an answer or a failure
   * whose id is no longer the latest is dropped, so a slow older search cannot
   * land last and show rows, or an error, that do not match the filter and page
   * on screen.
   */
  private searchRequestId = 0;

  private readonly exportLabel = translateSignal('admin.users.contacts.menu.export');
  private readonly importLabel = translateSignal('admin.users.contacts.menu.import');

  protected readonly cogItems = computed<MenuItem[]>(() => [
    {
      label: this.exportLabel(),
      command: () => this.downloads.openTicketed(this.api.exportUrl(), 'admin'),
    },
    { label: this.importLabel(), command: () => this.importOpen.set(true) },
  ]);

  constructor() {
    this.filterInput$
      .pipe(debounceTime(ADMIN_TEXT_FILTER_DEBOUNCE_MS), takeUntilDestroyed())
      .subscribe((value) => this.commitFilter(value));
    this.search();
  }

  protected onFilterInput(value: string): void {
    this.filter.set(value);
    this.filterInput$.next(value);
  }

  protected onFilterBlur(): void {
    this.commitFilter(this.filter());
  }

  protected clearFilter(): void {
    this.filter.set('');
    this.commitFilter('');
  }

  /** Every committed filter change resets to page 1. */
  private commitFilter(value: string): void {
    if (value === this.lastCommittedFilter) return;
    this.lastCommittedFilter = value;
    this.page.set(1);
    this.search();
  }

  protected onPageChange(firstRow: number): void {
    this.page.set(Math.floor(firstRow / ADMIN_PAGE_SIZE) + 1);
    this.search();
  }

  protected search(): void {
    const requestId = ++this.searchRequestId;
    this.loading.set(true);
    this.error.set(false);
    this.api.search(this.lastCommittedFilter, this.page(), ADMIN_PAGE_SIZE).subscribe({
      next: (result) => {
        if (requestId !== this.searchRequestId) return;
        this.loading.set(false);
        this.result.set(result);
        // A shrunken result set can strand the page past the end (filter change
        // or last-row delete) — clamp and re-search at the last valid page.
        const clamped = clampPageToTotal(this.page() - 1, ADMIN_PAGE_SIZE, result.total);
        if (clamped !== null && clamped + 1 !== this.page()) {
          this.page.set(clamped + 1);
          this.search();
        }
      },
      error: () => {
        if (requestId !== this.searchRequestId) return;
        // Keep the prior results visible and surface the failure.
        this.loading.set(false);
        this.error.set(true);
      },
    });
  }

  protected flagPathFor(lang: string): string | undefined {
    return this.languageSettings.modelLanguages.find((language) => language.value === lang)
      ?.flagPath;
  }

  protected languageLabelFor(lang: string): string {
    return (
      this.languageSettings.modelLanguages.find((language) => language.value === lang)?.label ??
      lang
    );
  }

  protected addContact(): void {
    this.editorModel.set({ mode: 'add', contact: { email: '' } });
  }

  protected editContact(contact: Contact): void {
    this.editorModel.set({ mode: 'edit', contact });
  }

  protected deleteContact(contact: Contact): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.users.contacts.confirmDelete.header'),
      message: this.transloco.translate('admin.users.contacts.confirmDelete.message', {
        email: contact.email,
      }),
      accept: () => {
        if (contact.id === undefined) return;
        this.api.delete(contact.id).subscribe({
          next: () => {
            this.notifications.success('admin.users.contacts.toast.deleted');
            this.search();
          },
          error: () => this.notifications.error('admin.users.contacts.toast.deleteError'),
        });
      },
    });
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.search();
  }

  /** POST the raw paste; surface the backend's plain-text result; refetch. */
  protected onImport(emails: string): void {
    this.api.importEmails(emails).subscribe({
      next: (result) => {
        this.notifications.success('admin.users.contacts.toast.imported', { result });
        this.search();
      },
      error: () => this.notifications.error('admin.users.contacts.toast.importError'),
    });
  }
}
