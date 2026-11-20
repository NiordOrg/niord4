import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of, switchMap } from 'rxjs';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { AuthTicketService } from '../../../core/auth/services/auth-ticket.service';
import { APP_CONFIG } from '../../../core/config/app-config.token';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import { TagsApiService } from './api/tags-api.service';

/**
 * Window-location boundary — tests override the protected `navigator` via a
 * subclass to assert URL construction without navigating. Mirrors
 * `MessagePrintService`'s seam.
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

/**
 * The most messages one archive holds: the backend's export takes the first this many of the
 * search (`maxSize` in `MessageExportRestService.generateZipArchiveForSearch`), in the order the
 * search sorts them. The two numbers are kept equal.
 */
export const MESSAGE_EXPORT_LIMIT = 1000;

export interface ExportOptions {
  /** Current filter as a query string (no leading `?`). May be empty. Ignored when `tempTagId` set. */
  readonly filterParams: string;
  /** When set, export the temp-tag's messages (selection path) instead of the filter. */
  readonly tempTagId?: string;
  /**
   * How many messages the search (or the selection) holds, when known. Above
   * {@link MESSAGE_EXPORT_LIMIT} the person is told that the archive holds only the first ones.
   */
  readonly total?: number;
}

/**
 * ZIP-export orchestrator: fetch a
 * one-time auth ticket, build the export query (filter OR tag) + lang + ticket,
 * then navigate the browser to `export.zip` — same-tab (the ZIP is an attachment,
 * so the page stays put). The export URL params live ONLY on the download URL,
 * never in the Angular router.
 *
 * The archive holds at most {@link MESSAGE_EXPORT_LIMIT} messages. When the search holds more, a
 * notice says so with the download and stays until it is closed, so an archive cut short is never
 * taken for a whole one.
 *
 * The ticket asks for the `user` role whenever the user holds it in the session domain, as the
 * print dialog's does: a ticket with no role names the user but grants none, and the export would
 * then leave out every message that is not public. A signed-in user with no role in the session
 * domain asks with no role, since the backend refuses a role the caller does not hold.
 */
@Injectable({ providedIn: 'root' })
export class MessageExportService {
  private readonly auth = inject(AuthFacade);
  private readonly ticket = inject(AuthTicketService);
  private readonly tagsApi = inject(TagsApiService);
  private readonly language = inject(LanguageService);
  private readonly notifications = inject(NotificationService);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** Overridable for tests; see message-export.service.spec.ts. */
  protected readonly navigator: BrowserNavigator = DEFAULT_BROWSER_NAVIGATOR;

  /** Export the current search (no params). */
  export(options: ExportOptions): Observable<void> {
    return this.ticket.fetch(this.auth.hasDomainUserRole() ? 'user' : undefined).pipe(
      map((ticket) => {
        this.navigator.navigate(this.buildExportUrl(ticket, options));
        if (options.total !== undefined && options.total > MESSAGE_EXPORT_LIMIT) {
          this.notifications.notify({
            severity: 'warn',
            summaryKey: 'messages.export.truncated',
            summaryParams: { limit: MESSAGE_EXPORT_LIMIT, total: options.total },
            life: 0,
          });
        }
      }),
    );
  }

  /**
   * Export the current selection: create a server-side
   * temp tag wrapping the ids, then export with `tag=<tagId>` instead of the filter.
   */
  exportSelection(selectedIds: readonly string[]): Observable<void> {
    return this.tagsApi.createTempMessageTag(selectedIds).pipe(
      switchMap((tempTag) =>
        this.export({ filterParams: '', tempTagId: tempTag.tagId, total: selectedIds.length }),
      ),
      catchError(() => {
        this.notifications.error('messages.export.errorTempTag');
        return of<void>(undefined);
      }),
    );
  }

  private buildExportUrl(ticket: string, options: ExportOptions): string {
    const params = new URLSearchParams();
    if (options.tempTagId) {
      params.set('tag', options.tempTagId);
    }
    params.set('lang', this.language.selectedLanguage());
    params.set('ticket', ticket);
    // Selection path uses the tag; search path appends the caller's filter string.
    const filterSuffix =
      !options.tempTagId && options.filterParams.length > 0 ? `&${options.filterParams}` : '';
    return `${this.apiBaseUrl}/message-io/export.zip?${params.toString()}${filterSuffix}`;
  }
}
