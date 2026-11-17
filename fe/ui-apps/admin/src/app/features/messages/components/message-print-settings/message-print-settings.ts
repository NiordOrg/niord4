import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  model,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { AuthFacade } from '../../../../core/auth/facades/auth.facade';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Select } from '../../../../shared/forms/select/select';
import { Icon } from '../../../../shared/icons/icon';
import type { MessageReport } from '../../../../shared/models/print/message-report.model';
import {
  DEFAULT_PRINT_SETTINGS,
  PAPER_SIZES,
  type PageOrientation,
  type PaperSize,
  type PrintSettings,
  type ReportParams,
} from '../../../../shared/models/print/print-settings.model';
import { MessageReportsApiService } from '../../services/api/message-reports-api.service';
import { LanguageService } from '../../../../core/main-shell/services/language.service';

/**
 * The one list report the backend prints for a caller it does not know: `FmReportService.PUBLIC_REPORT_ID`
 * (`MessageReportRestService.listReport` refuses any other report to such a caller with 403).
 */
export const GUEST_LIST_REPORT_ID = 'public';

interface SelectOption<T> {
  readonly value: T;
  readonly label: string;
  readonly icon?: string;
  readonly iconClass?: string;
}

@Component({
  selector: 'app-message-print-settings',
  templateUrl: './message-print-settings.html',
  imports: [FormsModule, Icon, Select, TranslocoPipe],
})
export class MessagePrintSettings {
  protected readonly language = inject(LanguageService);
  private readonly api = inject(MessageReportsApiService);
  private readonly transloco = inject(TranslocoService);
  private readonly notifications = inject(NotificationService);
  private readonly auth = inject(AuthFacade);
  private readonly destroyRef = inject(DestroyRef);

  /**
   * Reactive active-language tag. Computed labels that call
   * `transloco.translate(...)` (a one-shot, non-reactive method) read this
   * signal so they re-compute when the language changes.
   */
  private readonly activeLang = toSignal(this.transloco.langChanges$, {
    initialValue: this.transloco.getActiveLang(),
  });

  readonly list = input.required<boolean>();
  readonly report = input<MessageReport | undefined>(undefined);
  readonly expandParams = input<number | undefined>(undefined);

  readonly printSettings = model<PrintSettings>({ ...DEFAULT_PRINT_SETTINGS });
  readonly reportParams = model<ReportParams>({});

  protected readonly reports = signal<readonly MessageReport[]>([]);
  protected readonly loading = signal(false);

  /** Currently-active report (the hardcoded one OR the picked one from the dropdown). */
  protected readonly activeReport = computed<MessageReport | undefined>(() => {
    const hardcoded = this.report();
    if (hardcoded) return hardcoded;
    const id = this.printSettings().report;
    if (!id) return undefined;
    return this.reports().find((r) => r.reportId === id);
  });

  /** Show the map-thumbnails checkbox unless the active report explicitly sets `mapThumbnails`. */
  protected readonly showMapThumbnails = computed<boolean>(() => {
    const active = this.activeReport();
    if (!active) return true;
    return active.properties.mapThumbnails === undefined;
  });

  protected readonly orientationOptions = computed<SelectOption<PageOrientation>[]>(() => {
    this.activeLang();
    // There's no dedicated portrait/landscape glyph, so we use the generic
    // `file` document outline (which reads as a tall page) for portrait and
    // rotate it 90° via a Tailwind transform for landscape.
    return [
      {
        value: 'portrait',
        label: this.transloco.translate('messages.print.settings.orientation.portrait'),
        icon: 'lucideFile',
      },
      {
        value: 'landscape',
        label: this.transloco.translate('messages.print.settings.orientation.landscape'),
        icon: 'lucideFile',
        iconClass: 'rotate-90',
      },
    ];
  });

  protected readonly paperSizeOptions = computed<SelectOption<PaperSize>[]>(() => {
    this.activeLang();
    return PAPER_SIZES.map((value) => ({
      value,
      label: this.transloco.translate(`messages.print.settings.paperSize.${value}`),
    }));
  });

  protected readonly reportOptions = computed(() =>
    this.reports().map((r) => ({ value: r.reportId, label: r.name })),
  );

  /**
   * Whether the Report select is shown. A hardcoded report (a test print, whose dialog header names
   * it) is not chosen from the reports loaded earlier: the in-edit report may be unsaved or of
   * another domain, and the select would show it empty.
   */
  protected readonly offersReports = computed(
    () => this.report() === undefined && this.reportOptions().length > 0,
  );

  /** Keys of the current reportParams record, for template iteration. */
  protected readonly reportParamKeys = computed(() => Object.keys(this.reportParams()));

  constructor() {
    // Auto-load reports when no report is hardcoded. Re-fires when `list`,
    // `expandParams`, or the active language change — the backend localizes
    // `report.name`, so a language switch needs a fresh fetch to update
    // labels in the dropdown — and when the user signs in or out, which
    // changes the reports offered (see `loadReports`).
    effect(() => {
      const hardcoded = this.report();
      if (hardcoded) {
        this.applyHardcodedReport(hardcoded);
        return;
      }
      const listMode = this.list();
      const expand = this.expandParams();
      const guest = !this.auth.isAuthenticated();
      this.activeLang();
      untracked(() => {
        this.loadReports(listMode, expand, guest);
      });
    });

    // On report change in the dropdown, reset reportParams to the new report's
    // defaults. Intentional: manual user edits to the previous report's params
    // are discarded when the report changes.
    effect(() => {
      const active = this.activeReport();
      if (!active) return;
      untracked(() => {
        this.reportParams.set({ ...active.params });
      });
    });
  }

  protected updatePrintSettings<K extends keyof PrintSettings>(
    key: K,
    value: PrintSettings[K],
  ): void {
    this.printSettings.update((s) => ({ ...s, [key]: value }));
  }

  protected updateReportParam(key: string, value: string): void {
    this.reportParams.update((p) => ({ ...p, [key]: value }));
  }

  private applyHardcodedReport(report: MessageReport): void {
    untracked(() => {
      this.printSettings.update((s) => ({ ...s, report: report.reportId }));
      this.reportParams.set({ ...report.params });
    });
  }

  /**
   * Monotonically-increasing request id. Each `loadReports` call bumps it and
   * ignores its own response if the id has been bumped since (e.g. by a
   * rapid language switch). Cheaper than wrapping the inner subscribe in a
   * full `switchMap` pipeline and matches the existing imperative shape.
   * `takeUntilDestroyed` covers component teardown.
   */
  private loadRequestId = 0;

  /**
   * Loads the reports the dialog offers. A signed-in user gets every report the endpoint lists, and
   * a report the caller set is kept; the first report is picked only when none is set.
   *
   * A guest is a caller the backend does not know, and it prints such a caller a list with the
   * public report only, whatever `/reports` lists (and one message with the standard report only).
   * Print sets the window's address to the PDF, so a refusal replaces the application with the
   * backend's error. For a list the guest is therefore offered the public report alone (none
   * when the deployment has no report of that id, and the dialog then lets nothing be printed);
   * and a report set earlier that is not offered now — the public list report when the same dialog
   * next prints one message — gives way to the first one offered.
   */
  private loadReports(forList: boolean, expandParams: number | undefined, guest: boolean): void {
    const requestId = ++this.loadRequestId;
    this.loading.set(true);
    this.api
      .listReports({ forList, expandParams })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (listed) => {
          if (requestId !== this.loadRequestId) return;
          this.loading.set(false);
          const reports =
            guest && forList ? listed.filter((r) => r.reportId === GUEST_LIST_REPORT_ID) : listed;
          this.reports.set(reports);
          const current = this.printSettings().report;
          const keep = guest
            ? reports.some((r) => r.reportId === current)
            : current !== undefined && current !== '';
          if (!keep) {
            const report = reports[0]?.reportId;
            if (guest || report !== undefined) {
              this.printSettings.update((s) => ({ ...s, report }));
            }
          }
        },
        error: () => {
          if (requestId !== this.loadRequestId) return;
          this.loading.set(false);
          this.notifications.error('messages.print.settings.errorLoadingReports');
        },
      });
  }
}
