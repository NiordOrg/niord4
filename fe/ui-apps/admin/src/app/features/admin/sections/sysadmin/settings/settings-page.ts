import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { Icon } from '../../../../../shared/icons/icon';
import { Menu } from '../../../../../shared/components/menu/menu';
import { Pager } from '../../../../../shared/components/pager/pager';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { JsonImportDialog } from '../../../components/json-import-dialog/json-import-dialog';
import { Setting, SettingsApiService } from '../../../services/api/settings-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { SettingEditor, SettingEditorModel } from './setting-editor';
import { SettingsFacade } from './settings.facade';

/**
 * Settings section: a client-filtered, client-paged master list over the
 * editable backend system settings, with a type-dispatched edit drawer. The
 * list shows key/description/value (Password masked as `********`); editing
 * opens the shared drawer (`SettingEditor`) on a DEEP COPY of the row so the
 * list is never mutated pre-save. On a successful save the page refetches and
 * the drawer closes (no success toast); every drawer close path refetches the
 * list (including Cancel). Cog: ticketed export (sysadmin) + JSON import.
 */
@Component({
  selector: 'app-settings-page',
  imports: [
    AdminSectionPage,
    Pager,
    Icon,
    Menu,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    JsonImportDialog,
    SettingEditor,
    TranslocoPipe,
  ],
  providers: [SettingsFacade],
  templateUrl: './settings-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class SettingsPage {
  protected readonly facade = inject(SettingsFacade);
  protected readonly api = inject(SettingsApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);

  private readonly importDialog = viewChild.required(JsonImportDialog);

  protected readonly search = signal('');

  /**
   * Filter on the setting KEY or its stringified VALUE, case-insensitive — the
   * filter deliberately spans both key and value (mirrors dictionaries). A
   * Password setting's secret is EXCLUDED from matching: its value is shown
   * masked, so searching it would leak the secret — only the masked display
   * ('********') is searchable for those rows, which never matches a real term.
   */
  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const settings = this.facade.settings();
    if (!term) {
      return settings;
    }
    return settings.filter(
      (setting) =>
        setting.key.toLowerCase().includes(term) ||
        this.searchableValue(setting).toLowerCase().includes(term),
    );
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);
  protected readonly pageRows = computed(() => [...this.pager.paged()]);

  protected readonly editorModel = signal<SettingEditorModel | undefined>(undefined);

  private readonly exportLabel = translateSignal('admin.settings.menu.export');
  private readonly importLabel = translateSignal('admin.settings.menu.import');

  protected readonly cogItems = computed<MenuItem[]>(() => [
    { label: this.exportLabel(), command: () => this.exportSettings() },
    { label: this.importLabel(), command: () => this.importDialog().open() },
  ]);

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  /** The masked-or-cropped value shown in the list cell. */
  protected listValue(setting: Setting): string {
    if (setting.type === 'Password') {
      return '********';
    }
    return this.displayString(setting.value);
  }

  /**
   * Open the edit drawer on a DEEP COPY of the row (the list row is never
   * mutated pre-save). The drawer rebuilds its value control on (re)open.
   */
  protected editSetting(setting: Setting): void {
    this.editorModel.set({ setting: structuredClone(setting) });
  }

  protected onEditorSaved(): void {
    // Success: refetch and close (no success toast).
    this.editorModel.set(undefined);
    this.facade.refresh();
  }

  protected onEditorClosed(): void {
    // Every close path (Cancel/Esc/✕/mask) refetches the list.
    this.editorModel.set(undefined);
    this.facade.refresh();
  }

  protected onImportClosed(): void {
    // Deliberately refetch on every close path so import results are reflected.
    this.facade.refresh();
  }

  private async exportSettings(): Promise<void> {
    try {
      await this.downloads.openTicketed(this.api.exportUrl(), 'sysadmin');
    } catch {
      // Surface a failed export ticket rather than swallowing it.
      this.notifications.error('admin.settings.toast.exportError');
    }
  }

  /**
   * Value text used by the list filter. Reuses the masked cell value, so Password
   * rows match against the MASK, never the secret — a search can't leak a credential.
   */
  private searchableValue(setting: Setting): string {
    return this.listValue(setting);
  }

  /** Best-effort one-line string for a list value cell (objects → compact JSON). */
  private displayString(value: unknown): string {
    if (value === undefined || value === null) {
      return '';
    }
    if (typeof value === 'object') {
      try {
        return JSON.stringify(value);
      } catch {
        return String(value);
      }
    }
    return String(value);
  }
}
