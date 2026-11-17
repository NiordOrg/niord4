import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { Domain } from '../../../../core/domain/models/domain.model';

/**
 * Admin print-report shape. Distinct from the messages-feature read model
 * (`shared/models/print/message-report.model`), which carries only
 * `reportId`/`name`/`properties`/`params`. The admin form owns the full record.
 */
export interface SystemMessageReport {
  readonly reportId: string;
  readonly name: string;
  readonly sortOrder?: number;
  readonly templatePath: string;
  /** Empty/absent = all domains. */
  readonly domains?: readonly Domain[];
  readonly properties?: Record<string, unknown>;
  readonly params?: Record<string, unknown>;
}

interface SystemMessageReportVo {
  reportId: string;
  name?: string | null;
  sortOrder?: number | null;
  templatePath?: string | null;
  domains?: Domain[] | null;
  properties?: Record<string, unknown> | null;
  params?: Record<string, unknown> | null;
}

const fromVo = (vo: SystemMessageReportVo): SystemMessageReport => ({
  reportId: vo.reportId,
  name: vo.name ?? '',
  sortOrder: vo.sortOrder ?? undefined,
  templatePath: vo.templatePath ?? '',
  domains: vo.domains ?? [],
  properties: vo.properties ?? {},
  params: vo.params ?? {},
});

/**
 * Print-report CRUD + ticketed export. Wraps the existing `/rest/message-reports/*`
 * endpoints (no backend change). The read-side `MessageReportsApiService`
 * (features/messages) is left untouched.
 */
@Injectable({ providedIn: 'root' })
export class MessageReportsAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /**
   * The whole report inventory — a sysadmin-only read.
   *
   * The endpoint answers every other role with a 403, so this belongs to the
   * report-administration screen and nowhere else. A picker that merely needs
   * the reports the current desk may print with wants the editor-tier list on
   * `MessageReportsApiService` instead.
   */
  getAll(): Observable<SystemMessageReport[]> {
    return this.http
      .get<SystemMessageReportVo[]>(`${this.apiBaseUrl}/message-reports/all`)
      .pipe(map((vos) => vos.map(fromVo)));
  }

  create(report: SystemMessageReport): Observable<unknown> {
    return this.http.post(`${this.apiBaseUrl}/message-reports/report/`, report);
  }

  update(report: SystemMessageReport): Observable<unknown> {
    return this.http.put(
      `${this.apiBaseUrl}/message-reports/report/${encodeURIComponent(report.reportId)}`,
      report,
    );
  }

  delete(reportId: string): Observable<unknown> {
    return this.http.delete(
      `${this.apiBaseUrl}/message-reports/report/${encodeURIComponent(reportId)}`,
    );
  }

  /** Bare export URL — `TicketedDownloadService.openTicketed(.., 'sysadmin')` appends `?ticket=`. */
  exportUrl(): string {
    return `${this.apiBaseUrl}/message-reports/all`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/message-reports/upload-reports`;
  }
}
