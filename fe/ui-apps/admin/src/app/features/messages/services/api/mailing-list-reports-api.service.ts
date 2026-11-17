import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/** A public mailing-list report (a SCHEDULED trigger flagged `publicReport`). */
export interface MailingListReport {
  readonly id: string;
  readonly name: string;
  readonly scheduleType?: string;
  readonly scheduledExecutionTime?: string;
  readonly scheduledExecutionTimeZone?: string;
}

/**
 * The public mailing-list-report read surface — the slice the messages-area reports dialog
 * consumes. It lives in the messages feature (which owns the dialog) so the feature dependency
 * stays one-way; the admin Mailing Lists section does NOT consume these endpoints.
 */
@Injectable({ providedIn: 'root' })
export class MailingListReportsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getReports(language: string): Observable<MailingListReport[]> {
    const params = new HttpParams().set('lang', language);
    return this.http.get<MailingListReport[]>(`${this.apiBaseUrl}/mailing-lists/reports`, {
      params,
    });
  }

  /** Execute a report inline — returns the rendered report HTML as text. */
  executeReport(reportId: string, language: string): Observable<string> {
    const params = new HttpParams().set('lang', language);
    return this.http.get(
      `${this.apiBaseUrl}/mailing-lists/execute-report/${encodeURIComponent(reportId)}.html`,
      { params, responseType: 'text' },
    );
  }

  /** The report URL for opening in a new window (the caller appends the `user`-role ticket). */
  executeReportUrl(reportId: string, language: string): string {
    return `${this.apiBaseUrl}/mailing-lists/execute-report/${encodeURIComponent(reportId)}.html?lang=${encodeURIComponent(language)}`;
  }
}
