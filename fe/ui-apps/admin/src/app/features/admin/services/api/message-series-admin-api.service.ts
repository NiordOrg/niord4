import { HttpClient, type HttpResourceRequest } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { AdminMessageSeries } from '../../models/admin-message-series.model';

/**
 * Message-series CRUD + next-number maintenance (the sysadmin catalog —
 * distinct from the messages read-side `MessageSeriesApiService`, which also
 * owns the list load the admin page reuses). Endpoint contract, fixed by the
 * backend:
 * - create POSTs to the trailing-slash collection path `/message-series/series/`;
 * - the PUT/DELETE path id is the URL-encoded `seriesId`; the PUT path id is
 *   derived from the body's, so the two match by construction (the backend
 *   400s a mismatch — the id is immutable);
 * - the next-number endpoints exchange a bare JSON integer (null on GET when
 *   no number is assigned for the year yet).
 */
@Injectable({ providedIn: 'root' })
export class MessageSeriesAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  create(series: AdminMessageSeries): Observable<unknown> {
    return this.http.post(`${this.apiBaseUrl}/message-series/series/`, series);
  }

  update(series: AdminMessageSeries): Observable<unknown> {
    return this.http.put(this.seriesUrl(series.seriesId), series);
  }

  delete(seriesId: string): Observable<unknown> {
    return this.http.delete(this.seriesUrl(seriesId));
  }

  /** The stored next sequence number of the series for the given year. */
  getNextNumber(seriesId: string, year: number): Observable<number | null> {
    return this.http.get<number | null>(this.nextNumberRequest(seriesId, year).url);
  }

  /**
   * The same stored-number load as `getNextNumber`, as a request descriptor for
   * reactive `httpResource` consumers (the next-number popover's year-keyed
   * fetch) — the endpoint stays defined once.
   */
  nextNumberRequest(seriesId: string, year: number): HttpResourceRequest {
    return { url: `${this.seriesUrl(seriesId)}/number/${year}` };
  }

  setNextNumber(seriesId: string, year: number, nextNumber: number): Observable<unknown> {
    return this.http.put(`${this.seriesUrl(seriesId)}/number/${year}`, nextNumber);
  }

  /** Computes the next number from the series' existing messages of the year. */
  computeNextNumber(seriesId: string, year: number): Observable<number | null> {
    return this.http.get<number | null>(`${this.seriesUrl(seriesId)}/compute-number/${year}`);
  }

  private seriesUrl(seriesId: string): string {
    return `${this.apiBaseUrl}/message-series/series/${encodeURIComponent(seriesId)}`;
  }
}
