import { HttpErrorResponse } from '@angular/common/http';
import { Component, DestroyRef, computed, inject, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { TranslocoPipe, TranslocoService, translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../../../shared/models/menu-item.model';
import { FormsModule } from '@angular/forms';
import { Subscription, catchError, of, timeout } from 'rxjs';
import { Icon } from '../../../../../shared/icons/icon';
import { Menu } from '../../../../../shared/components/menu/menu';
import { Pager } from '../../../../../shared/components/pager/pager';
import { Select } from '../../../../../shared/forms/select/select';

import { TicketedDownloadService } from '../../../../../core/auth/services/ticketed-download.service';
import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { TranslationRefreshService } from '../../../../../core/i18n/translation-refresh.service';
import { Confirm } from '../../../../../shared/components/confirm/confirm';
import { EmptyState } from '../../../../../shared/components/empty-state/empty-state';
import { ErrorBanner } from '../../../../../shared/components/error-banner/error-banner';
import { MenuTrigger } from '../../../../../shared/components/menu-trigger/menu-trigger';
import { ProgressBar } from '../../../../../shared/components/progress-bar/progress-bar';
import { ConfirmService } from '../../../../../shared/services/confirm.service';
import {
  DictionariesApiService,
  DictionaryEntry,
  DictionaryKeyReader,
  DictionaryKeyUsers,
} from '../../../services/api/dictionaries-api.service';
import { AdminSectionPage } from '../../../shell/admin-section-page';
import { createClientPager } from '../../../utils/client-pager';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { orderDescs } from '../../../utils/localized-descs';
import { DictionariesFacade } from './dictionaries.facade';
import { DictionaryEntryEditor, DictionaryEntryEditorModel } from './dictionary-entry-editor';
import { LanguageFlag } from '../../../../../shared/components/language-flag/language-flag';
import { DictionaryImportDialog } from './dictionary-import-dialog';
import { problemDetail } from '../../../../../shared/api/problem-detail';

/** Script resources the delete confirm names one by one; the rest are counted. */
const MAX_LISTED_TEMPLATES = 10;

/** How long the delete waits for the key-users check before it confirms without it. */
const KEY_USERS_TIMEOUT_MS = 5000;

/** What each backend reader of a key does without it. */
const READER_TEXT: Record<DictionaryKeyReader, string> = {
  MESSAGE_CANCELLATION: 'admin.dictionaries.confirmDelete.reader.messageCancellation',
  FIRING_EXERCISES: 'admin.dictionaries.confirmDelete.reader.firingExercises',
  S124_AGENCY: 'admin.dictionaries.confirmDelete.reader.s124Agency',
  COMMENT_MAIL: 'admin.dictionaries.confirmDelete.reader.commentMail',
  MAILING_LIST_MAIL: 'admin.dictionaries.confirmDelete.reader.mailingListMail',
  AUDIO_TEXT: 'admin.dictionaries.confirmDelete.reader.audioText',
  DATE_INTERVAL_DIRECTIVE: 'admin.dictionaries.confirmDelete.reader.dateIntervalDirective',
  FROM_TO_DATE_DIRECTIVE: 'admin.dictionaries.confirmDelete.reader.fromToDateDirective',
  LAT_LON_DIRECTIVE: 'admin.dictionaries.confirmDelete.reader.latLonDirective',
  OTHER: 'admin.dictionaries.confirmDelete.reader.other',
};

/**
 * Dictionaries section: per-dictionary entry catalog with extended columns,
 * entry drawer (create/edit/copy), and JSON export and import (previewed before anything is
 * written).
 * The entries payload is all-language; per-language lines render in
 * configured-language-list order (never reordered by the UI language).
 */
@Component({
  selector: 'app-dictionaries-page',
  imports: [
    LanguageFlag,
    AdminSectionPage,
    Pager,
    Select,
    FormsModule,
    Icon,
    Menu,
    Confirm,
    MenuTrigger,
    ProgressBar,
    EmptyState,
    ErrorBanner,
    DictionaryEntryEditor,
    DictionaryImportDialog,
    TranslocoPipe,
  ],
  providers: [DictionariesFacade, ConfirmService],
  templateUrl: './dictionaries-page.html',
  host: { class: 'flex min-w-0 flex-1 flex-col' },
})
export class DictionariesPage {
  private readonly languageSettings = inject(LanguageService);
  private readonly config = inject(APP_CONFIG);
  protected readonly facade = inject(DictionariesFacade);
  protected readonly api = inject(DictionariesApiService);
  private readonly downloads = inject(TicketedDownloadService);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly transloco = inject(TranslocoService);
  private readonly translationRefresh = inject(TranslationRefreshService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly importDialog = viewChild.required(DictionaryImportDialog);

  /** The key-users check of the last Delete click, while it runs. */
  private pendingCheck?: Subscription;

  protected readonly search = signal('');

  /**
   * Extended-columns toggle: user-controllable, but re-guessed on every reload
   * — extended shows when ANY desc has a DEFINED long value (an empty string
   * counts as defined).
   */
  protected readonly extendedOverride = signal<boolean | undefined>(undefined);
  protected readonly guessedExtended = computed(() =>
    this.facade.entries().some((entry) => entry.descs.some((desc) => desc.longValue !== undefined)),
  );
  protected readonly showExtended = computed(
    () => this.extendedOverride() ?? this.guessedExtended(),
  );

  /**
   * Filter on key OR any language's value (and long value while extended
   * columns are shown), case-insensitive — deliberately spanning key and
   * values, not the key alone.
   */
  protected readonly filtered = computed(() => {
    const term = this.search().trim().toLowerCase();
    const entries = this.facade.entries();
    if (!term) {
      return entries;
    }
    const extended = this.showExtended();
    return entries.filter(
      (entry) =>
        entry.key.toLowerCase().includes(term) ||
        entry.descs.some(
          (desc) =>
            desc.value.toLowerCase().includes(term) ||
            (extended && (desc.longValue ?? '').toLowerCase().includes(term)),
        ),
    );
  });

  protected readonly pager = createClientPager(this.filtered, ADMIN_PAGE_SIZE);
  protected readonly pageRows = computed(() => [...this.pager.paged()]);

  protected readonly editorModel = signal<DictionaryEntryEditorModel | undefined>(undefined);

  /** Configured-list desc ordering for the table lines — never follows the UI language. */
  protected orderedDescs(entry: DictionaryEntry) {
    return orderDescs(
      entry.descs,
      this.languageSettings.modelLanguages.map((language) => language.value),
    );
  }

  /** Flag image for a per-line language indicator — N-language-safe. */
  protected flagPathFor(lang: string): string | undefined {
    return this.languageSettings.modelLanguages.find((language) => language.value === lang)
      ?.flagPath;
  }

  /** Human language label ("English"/"Dansk") for the flag's accessible name. */
  protected langLabelFor(lang: string): string {
    return (
      this.languageSettings.modelLanguages.find((language) => language.value === lang)?.label ??
      lang.toUpperCase()
    );
  }

  protected readonly names = computed(() => [...this.facade.names()]);

  private readonly exportLabel = translateSignal('admin.dictionaries.menu.export');
  private readonly importLabel = translateSignal('admin.dictionaries.menu.import');

  protected readonly cogItems = computed<MenuItem[]>(() => [
    {
      label: this.exportLabel(),
      command: () => this.downloads.openPublic(this.api.exportUrl(this.facade.currentName())),
    },
    { label: this.importLabel(), command: () => this.importDialog().open() },
  ]);

  protected onSearch(value: string): void {
    this.search.set(value);
    this.pager.reset();
  }

  protected onDictionaryChange(name: string): void {
    this.facade.selectDictionary(name);
    this.extendedOverride.set(undefined); // re-guess on the new dataset
    this.search.set('');
    this.pager.reset();
  }

  /**
   * The extended guess re-runs on EVERY entries reload, so a manual
   * Show-Extended toggle is deliberately overwritten by each mutation or import.
   */
  private refreshAndReguess(): void {
    this.extendedOverride.set(undefined);
    this.facade.refresh();
    // Push the change into the running app's live translations so a Dictionary edit (create,
    // edit, delete, import) shows without a full page reload. Best-effort: a failure just
    // means it appears on the next reload — the entry itself is already saved.
    this.translationRefresh.refreshActiveLanguage().subscribe({ error: () => undefined });
  }

  protected addEntry(): void {
    this.editorModel.set({
      mode: 'add',
      dictionaryName: this.facade.currentName(),
      entry: { key: '', descs: [] },
      showExtended: this.showExtended(),
    });
  }

  protected editEntry(entry: DictionaryEntry): void {
    this.editorModel.set({
      mode: 'edit',
      dictionaryName: this.facade.currentName(),
      entry,
      showExtended: this.showExtended(),
    });
  }

  /** Copy = create mode pre-filled with the source entry, id stripped. */
  protected copyEntry(entry: DictionaryEntry): void {
    this.editorModel.set({
      mode: 'add',
      dictionaryName: this.facade.currentName(),
      entry: { ...entry, id: undefined },
      showExtended: this.showExtended(),
    });
  }

  /**
   * Asks the backend who uses the key before the confirm opens, so the confirm can name them.
   * A failed check (an older backend answers 404) or one that has not answered within
   * {@link KEY_USERS_TIMEOUT_MS} still opens the confirm, saying so: a delete never depends on
   * the check.
   */
  protected deleteEntry(entry: DictionaryEntry): void {
    // Only the last click's answer opens a confirm: a late answer for an earlier row is dropped.
    this.pendingCheck?.unsubscribe();
    this.pendingCheck = this.api
      .getKeyUsers(this.facade.currentName(), entry.key)
      .pipe(
        timeout(KEY_USERS_TIMEOUT_MS),
        catchError(() => of(undefined)),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((users) => {
        this.pendingCheck = undefined;
        this.confirmDelete(entry, users);
      });
  }

  private confirmDelete(entry: DictionaryEntry, users: DictionaryKeyUsers | undefined): void {
    this.confirm.confirmDestructive({
      header: this.transloco.translate('admin.dictionaries.confirmDelete.header'),
      message: this.transloco.translate('admin.dictionaries.confirmDelete.message', {
        key: entry.key,
      }),
      details: this.keyUserLines(users),
      accept: () => {
        this.api.deleteEntry(this.facade.currentName(), entry.key).subscribe({
          next: () => {
            this.notifications.success('admin.dictionaries.toast.deleted');
            this.refreshAndReguess();
          },
          error: (error: unknown) => {
            // A 409 names the parameter types that still list the entry: say which.
            const detail =
              error instanceof HttpErrorResponse && error.status === 409
                ? problemDetail(error)
                : undefined;
            if (detail === undefined) {
              this.notifications.error('admin.dictionaries.toast.deleteError');
            } else {
              this.notifications.error('admin.dictionaries.toast.deleteRefused', { detail });
            }
          },
        });
      },
    });
  }

  /**
   * The confirm's lines: the parameter types whose list holds the key (the backend refuses the
   * delete while any does), the script resources that name the key (the first ten, then a count),
   * what each part of Niord that reads the key does without it, and always that a deleted key
   * stays deleted.
   *
   * Where the backend reports S-124 off (`s124Enabled` false in the web settings), the S-124
   * agency reader is left out before the "used by nothing" test: only SECOM's exchange sets read
   * it, and SECOM cannot run with S-124 off, so a key only it reads is unused there. The
   * cancellation warning's reader stays, because the warning is issued for every country whatever
   * the switch says (decision 2 of `be/docs/s124-cancellation-plan.md`, which the switch leaves
   * unchanged).
   */
  private keyUserLines(users: DictionaryKeyUsers | undefined): string[] {
    const t = (key: string, params?: Record<string, unknown>) =>
      this.transloco.translate(key, params);
    const lines: string[] = [];
    const readers =
      this.config.s124Enabled === false
        ? (users?.readers ?? []).filter((reader) => reader !== 'S124_AGENCY')
        : (users?.readers ?? []);
    if (!users) {
      lines.push(t('admin.dictionaries.confirmDelete.usedBy.unknown'));
    } else if (
      users.templates.length === 0 &&
      readers.length === 0 &&
      users.paramTypes.length === 0
    ) {
      lines.push(t('admin.dictionaries.confirmDelete.usedBy.none'));
    } else {
      if (users.paramTypes.length > 0) {
        lines.push(
          t('admin.dictionaries.confirmDelete.paramTypes', { names: users.paramTypes.join(', ') }),
        );
      }
      for (const path of users.templates.slice(0, MAX_LISTED_TEMPLATES)) {
        lines.push(
          users.prefix
            ? t('admin.dictionaries.confirmDelete.usedBy.templateByPrefix', {
                path,
                prefix: users.prefix,
              })
            : t('admin.dictionaries.confirmDelete.usedBy.template', { path }),
        );
      }
      if (users.templates.length > MAX_LISTED_TEMPLATES) {
        lines.push(
          t('admin.dictionaries.confirmDelete.usedBy.more', {
            count: users.templates.length - MAX_LISTED_TEMPLATES,
          }),
        );
      }
      for (const reader of readers) {
        lines.push(t(READER_TEXT[reader]));
      }
    }
    lines.push(t('admin.dictionaries.confirmDelete.stays'));
    return lines;
  }

  protected onEditorSaved(): void {
    this.editorModel.set(undefined);
    this.refreshAndReguess();
  }

  protected onImportClosed(): void {
    // Deliberately refetch on every close path so import results are reflected.
    this.refreshAndReguess();
  }
}
