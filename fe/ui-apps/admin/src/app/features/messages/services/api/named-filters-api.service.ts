import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { NamedFilter, NamedFilterCreatePayload } from '../../models/named-filter.model';

@Injectable({ providedIn: 'root' })
export class NamedFiltersApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  add(payload: NamedFilterCreatePayload): Observable<NamedFilter> {
    return this.http.post<NamedFilter>(`${this.apiBaseUrl}/filters/filter/`, payload);
  }

  remove(id: number): Observable<void> {
    return this.http.delete<void>(`${this.apiBaseUrl}/filters/filter/${id}`);
  }
}
