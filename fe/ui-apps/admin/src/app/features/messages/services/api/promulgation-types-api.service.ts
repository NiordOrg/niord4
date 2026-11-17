import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { PromulgationTypeRef } from '../../models/messages-filter-params.model';

// Backed by two backend endpoints:
//   search:  GET /rest/promulgations/search-public-promulgation-type?type=<q>
//   by-ids:  GET /rest/promulgations/public-promulgation-type/<csv-ids>
// The search call is skipped entirely when the query is empty, to avoid
// loading the full set on focus.
@Injectable({ providedIn: 'root' })
export class PromulgationTypesApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  search(query: string): Observable<PromulgationTypeRef[]> {
    if (!query) {
      return of([]);
    }
    return this.http.get<PromulgationTypeRef[]>(
      `${this.apiBaseUrl}/promulgations/search-public-promulgation-type`,
      { params: new HttpParams().set('type', query) },
    );
  }

  getByIds(ids: readonly string[]): Observable<PromulgationTypeRef[]> {
    if (ids.length === 0) {
      return of([]);
    }
    return this.http.get<PromulgationTypeRef[]>(
      `${this.apiBaseUrl}/promulgations/public-promulgation-type/${ids.join(',')}`,
    );
  }
}
