import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { MessageReport } from '../../../../shared/models/print/message-report.model';

export interface ListReportsOptions {
  /** true → /reports (list/batch print); false → /detail-reports (single message). */
  readonly forList: boolean;
  readonly expandParams?: number;
}

@Injectable({ providedIn: 'root' })
export class MessageReportsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  listReports(options: ListReportsOptions): Observable<readonly MessageReport[]> {
    const endpoint = options.forList ? 'reports' : 'detail-reports';
    let params = new HttpParams();
    if (typeof options.expandParams === 'number') {
      params = params.set('expandParams', String(options.expandParams));
    }
    return this.http.get<readonly MessageReport[]>(
      `${this.apiBaseUrl}/message-reports/${endpoint}`,
      { params },
    );
  }

  /**
   * The report as it is sent, with `${year}`, `${week}` and their two-digit forms in its parameters
   * expanded for today in the current domain's zone, as `/reports` expands a stored report's. The
   * report need not be saved, and nothing is stored (SYSADMIN only).
   */
  expandReportParams(report: MessageReport): Observable<MessageReport> {
    return this.http.post<MessageReport>(
      `${this.apiBaseUrl}/message-reports/expand-params`,
      report,
    );
  }
}
