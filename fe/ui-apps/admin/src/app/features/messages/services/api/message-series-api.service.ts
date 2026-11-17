import { HttpClient, type HttpResourceRequest } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import type { MessageSeries } from '../../models/message.model';

@Injectable({ providedIn: 'root' })
export class MessageSeriesApiService {
  private readonly http = inject(HttpClient);
  private readonly lang = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  searchMessageSeries(
    query: string,
    options?: { domainId?: string; language?: string; limit?: number },
  ): Observable<MessageSeries[]> {
    let params = this.lang
      .langParams(options?.language)
      .set('name', query)
      .set('limit', String(options?.limit ?? 10));
    if (options?.domainId) {
      params = params.set('domain', options.domainId);
    }
    return this.http.get<MessageSeries[]>(`${this.apiBaseUrl}/message-series/search`, { params });
  }

  getMessageSeriesByIds(
    ids: string[],
    options?: { language?: string; limit?: number },
  ): Observable<MessageSeries[]> {
    return this.lang.getByIdsCsv<MessageSeries>('/message-series/search', ids, {
      language: options?.language,
      limit: options?.limit ?? 20,
    });
  }

  /**
   * The complete, cross-domain list of message series (backs the full-list
   * target-series picker). Carries `?messageNumbers=true` and intentionally NO
   * `?lang=` — the picker labels the raw `seriesId`, so there is nothing to
   * localize.
   */
  getAll(): Observable<MessageSeries[]> {
    const { url, params } = this.allRequest();
    return this.http.get<MessageSeries[]>(url, { params });
  }

  /**
   * The same list load as `getAll`, as a request descriptor for reactive
   * `httpResource` consumers (the sysadmin series catalog) — endpoint and
   * params stay defined once.
   */
  allRequest(): HttpResourceRequest {
    return {
      url: `${this.apiBaseUrl}/message-series/all`,
      params: { messageNumbers: 'true' },
    };
  }
}
