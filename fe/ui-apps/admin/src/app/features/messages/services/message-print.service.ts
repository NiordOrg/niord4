import { HttpClient, HttpContext } from '@angular/common/http';
import { DOCUMENT, Injectable, inject } from '@angular/core';
import { EMPTY, Observable, catchError, map, of, switchMap } from 'rxjs';

import { APP_CONFIG } from '../../../core/config/app-config.token';
import { TICKETED_REQUEST } from '../../../core/interceptors/ticketed-request';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import type { PrintRequest } from '../dialogs/message-print-dialog/message-print-dialog.service';
import { MessagePrintDialogService } from '../dialogs/message-print-dialog/message-print-dialog.service';
import type { MessageReport } from '../../../shared/models/print/message-report.model';
import { MessageReportsApiService } from './api/message-reports-api.service';
import { TagsApiService } from './api/tags-api.service';

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
 *   3. Fetches the PDF from the URL and hands it to the browser as a download;
 *      when the backend cannot make it, a toast says so and the page stays as
 *      it was (see `download`).
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
  private readonly http = inject(HttpClient);
  private readonly document = inject(DOCUMENT);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  printList(options: PrintListOptions): Observable<void> {
    return this.dialog.open({ totalMessageNo: options.totalMessageNo, list: true }).pipe(
      switchMap((request) => {
        if (!request) return of<void>(undefined);
        const url = this.buildListUrl(request, undefined, options.filterParams);
        return this.download(url, LIST_FILE_NAME);
      }),
    );
  }

  printSelection(messageIds: readonly string[], filterParams: string): Observable<void> {
    return this.dialog.open({ totalMessageNo: messageIds.length, list: true }).pipe(
      switchMap((request) => {
        if (!request) return of<void>(undefined);
        return this.tagsApi.createTempMessageTag(messageIds).pipe(
          catchError(() => {
            this.notifications.error('messages.print.errorTempTag');
            return EMPTY;
          }),
          switchMap((tempTag) =>
            this.download(this.buildListUrl(request, tempTag.tagId, filterParams), LIST_FILE_NAME),
          ),
        );
      }),
    );
  }

  printMessage(messageId: string): Observable<void> {
    return this.dialog.open({ totalMessageNo: 1, list: false }).pipe(
      switchMap((request) => {
        if (!request) return of<void>(undefined);
        const url = this.buildSingleMessageUrl(messageId, request);
        return this.download(url, `message-${messageId}.pdf`);
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
      switchMap((request) => {
        if (!request) return of<void>(undefined);
        const url = this.buildListUrl(request, tagId || undefined, '');
        return this.download(url, LIST_FILE_NAME);
      }),
    );
  }

  /**
   * Fetch the PDF and hand it to the browser as a download, or say that it could not be made.
   *
   * Fetched rather than navigated to: a navigation shows whatever the backend answers, so a print
   * it cannot make (a message no longer there, an error while rendering, a backend that does not
   * answer) replaced the application with the backend's error document. The request is the one
   * the navigation made — the address with its one-time ticket, and neither the bearer nor the
   * domain header (`TICKETED_REQUEST`) — so the backend answers it exactly as it answered the
   * navigation; the browser keeps no copy of the document.
   *
   * The document is named as the backend names it, or by `fallbackName` when the backend's name
   * cannot be read.
   */
  private download(url: string, fallbackName: string): Observable<void> {
    return this.http
      .get(url, {
        responseType: 'blob',
        observe: 'response',
        cache: 'no-store',
        context: new HttpContext().set(TICKETED_REQUEST, true),
      })
      .pipe(
        map((response) => {
          if (!response.body) throw new Error('No document');
          this.saveFile(
            response.body,
            fileNameOf(response.headers.get('Content-Disposition'), fallbackName),
          );
        }),
        catchError(() => {
          this.notifications.error('messages.print.errorDocument');
          return of<void>(undefined);
        }),
      );
  }

  /** Hand a fetched document to the browser as a download; overridable for tests. */
  protected saveFile(file: Blob, name: string): void {
    const objectUrl = URL.createObjectURL(file);
    const link = this.document.createElement('a');
    link.href = objectUrl;
    link.download = name;
    link.click();
    // Released once the click has been dispatched; the browser has taken its own reference by
    // then, and holding it would keep the document in memory for the life of the page.
    URL.revokeObjectURL(objectUrl);
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

/** The name the backend gives a printed list when the request names none. */
const LIST_FILE_NAME = 'messages.pdf';

/**
 * The file name of a Content-Disposition `filename="…"`, which the backend writes URI-encoded, or
 * the fallback when the response carries none the page can read.
 */
function fileNameOf(contentDisposition: string | null, fallback: string): string {
  const quoted = /filename="([^"]+)"/.exec(contentDisposition ?? '')?.[1];
  if (!quoted) return fallback;
  try {
    return decodeURIComponent(quoted);
  } catch {
    return quoted;
  }
}
