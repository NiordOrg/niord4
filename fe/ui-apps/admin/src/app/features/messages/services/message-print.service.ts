import { Injectable, inject } from '@angular/core';
import { EMPTY, Observable, catchError, map, of, switchMap } from 'rxjs';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import type { PrintRequest } from '../dialogs/message-print-dialog/message-print-dialog.service';
import { MessagePrintDialogService } from '../dialogs/message-print-dialog/message-print-dialog.service';
import type { MessageReport } from '../../../shared/models/print/message-report.model';
import { MessageReportsApiService } from './api/message-reports-api.service';
import { TagsApiService } from './api/tags-api.service';

/**
 * Window-location boundary. Tests override this via `TestBed.overrideProvider`
 * to assert URL construction without actually navigating away.
 */
export interface BrowserNavigator {
  navigate(url: string): void;
}

const DEFAULT_BROWSER_NAVIGATOR: BrowserNavigator = {
  navigate(url) {
    if (typeof window !== 'undefined') {
      window.location.href = url;
    }
  },
};

export interface PrintListOptions {
  /** Visible to user in the dialog header. */
  readonly totalMessageNo: number;
  /** Current filter as a query string (no leading `?`). May be empty. */
  readonly filterParams: string;
}

/**
 * Orchestrator for the three print flows. Each method:
 *   1. Opens the print dialog with the appropriate count + list mode.
 *   2. On PrintRequest, builds the URL (with optional temp-tag for selection).
 *   3. Navigates the browser to the URL — the backend serves the PDF, which
 *      the browser handles as a download. window.location stays put.
 *
 * The service takes total + filter as caller-supplied parameters rather than
 * injecting MessageListFacade + MessagesFilterFacade — keeps the dependency
 * graph shallow and avoids forcing every transitive test to stub those facades.
 */
@Injectable({ providedIn: 'root' })
export class MessagePrintService {
  private readonly dialog = inject(MessagePrintDialogService);
  private readonly tagsApi = inject(TagsApiService);
  private readonly reportsApi = inject(MessageReportsApiService);
  private readonly language = inject(LanguageService);
  private readonly notifications = inject(NotificationService);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** Overridable for tests; see message-print.service.spec.ts. */
  protected readonly navigator: BrowserNavigator = DEFAULT_BROWSER_NAVIGATOR;

  printList(options: PrintListOptions): Observable<void> {
    return this.dialog.open({ totalMessageNo: options.totalMessageNo, list: true }).pipe(
      map((request) => {
        if (!request) return;
        const url = this.buildListUrl(request, undefined, options.filterParams);
        this.navigator.navigate(url);
      }),
    );
  }

  printSelection(messageIds: readonly string[], filterParams: string): Observable<void> {
    return this.dialog.open({ totalMessageNo: messageIds.length, list: true }).pipe(
      switchMap((request) => {
        if (!request) return of<void>(undefined);
        return this.tagsApi.createTempMessageTag(messageIds).pipe(
          map((tempTag) => {
            const url = this.buildListUrl(request, tempTag.tagId, filterParams);
            this.navigator.navigate(url);
          }),
          catchError(() => {
            this.notifications.error('messages.print.errorTempTag');
            return of<void>(undefined);
          }),
        );
      }),
    );
  }

  printMessage(messageId: string): Observable<void> {
    return this.dialog.open({ totalMessageNo: 1, list: false }).pipe(
      map((request) => {
        if (!request) return;
        const url = this.buildSingleMessageUrl(messageId, request);
        this.navigator.navigate(url);
      }),
    );
  }

  /**
   * Test-print for the admin print-report editor: open the dialog with the in-edit
   * (possibly unsaved) report hardcoded, then print the messages carrying `tagId`.
   * Reuses the list URL builder, passing the user-selected tag as the `tag` param.
   * The tag is optional — without one the current domain's list is printed.
   *
   * Only `/reports` expands a stored report's `${year}` and `${week}`, so the in-edit report's
   * parameters are first expanded by the backend in the current domain's zone; the dialog opens with
   * the answer's parameters and names the report, claiming no message count. When the expansion
   * fails the dialog does not open, since a test print would show the raw placeholders.
   */
  printReportTest(report: MessageReport, tagId?: string): Observable<void> {
    return this.reportsApi.expandReportParams(report).pipe(
      catchError(() => {
        this.notifications.error('messages.print.errorExpandParams');
        return EMPTY;
      }),
      switchMap((expanded) =>
        this.dialog.open({
          totalMessageNo: 0,
          list: true,
          report: { ...report, params: expanded.params ?? report.params },
        }),
      ),
      map((request) => {
        if (!request) return;
        const url = this.buildListUrl(request, tagId || undefined, '');
        this.navigator.navigate(url);
      }),
    );
  }

  /**
   * Build the URL for the list/selection print endpoint. When `tempTagId` is
   * set, the selection-print path appends `&tag=<id>`; filter params are
   * appended in both cases (the backend uses tag when present and ignores filter).
   */
  private buildListUrl(
    request: PrintRequest,
    tempTagId: string | undefined,
    filterParams: string,
  ): string {
    const params = this.buildPrintParams(request);
    if (tempTagId) {
      params.set('tag', tempTagId);
    }
    const filterSuffix = filterParams.length > 0 ? `&${filterParams}` : '';
    return `${this.apiBaseUrl}/message-reports/report.pdf?${params.toString()}${filterSuffix}`;
  }

  /**
   * Single-message print does NOT append filter params (the endpoint takes
   * the message id in the path, filter is irrelevant).
   */
  private buildSingleMessageUrl(messageId: string, request: PrintRequest): string {
    const params = this.buildPrintParams(request);
    return `${this.apiBaseUrl}/message-reports/message/${encodeURIComponent(messageId)}.pdf?${params.toString()}`;
  }

  private buildPrintParams(request: PrintRequest): URLSearchParams {
    const params = new URLSearchParams();
    const s = request.settings;
    if (s.report) params.set('report', s.report);
    params.set('pageSize', s.pageSize);
    params.set('pageOrientation', s.pageOrientation);
    params.set('mapThumbnails', String(s.mapThumbnails));
    // Report-param URL keys are prefixed with `param:`.
    for (const [key, value] of Object.entries(request.reportParams)) {
      if (value === undefined || value === null || value === '') continue;
      params.set(`param:${key}`, String(value));
    }
    // The auth ticket is a single-use short-lived token. It rides in the URL
    // (the backend contract) so the browser-driven PDF download works
    // without sending the bearer token. URL-embedded tokens are visible in
    // browser history and access logs — acceptable here because each ticket
    // is consumed on first use; documented for security review.
    params.set('ticket', request.ticket);
    params.set('lang', request.settings.language ?? this.language.selectedLanguage());
    return params;
  }
}
