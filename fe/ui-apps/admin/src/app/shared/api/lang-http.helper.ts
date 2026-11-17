import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';

import { APP_CONFIG } from '../../core/config/app-config.token';
import { LanguageService } from '../../core/main-shell/services/language.service';

@Injectable({ providedIn: 'root' })
export class LangHttpHelper {
  private readonly http = inject(HttpClient);
  private readonly languageService = inject(LanguageService);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  resolveLanguage(language?: string): string {
    return language ?? this.languageService.selectedLanguage();
  }

  langParams(language?: string): HttpParams {
    return new HttpParams().set('lang', this.resolveLanguage(language));
  }

  /**
   * GET `<apiBaseUrl><path>/<encoded-csv-of-ids>?lang=…`; empty `ids` short-circuits to `of([])`.
   * `path` is the backend-relative path (e.g. `/areas/search`) — the helper prepends
   * `apiBaseUrl` so callers don't have to.
   */
  getByIdsCsv<T>(
    path: string,
    ids: readonly (string | number)[],
    opts?: { language?: string; limit?: number; extraParams?: Readonly<Record<string, string>> },
  ): Observable<T[]> {
    if (ids.length === 0) {
      return of([]);
    }
    let params = this.langParams(opts?.language);
    if (opts?.limit !== undefined) {
      params = params.set('limit', String(opts.limit));
    }
    if (opts?.extraParams) {
      for (const [key, value] of Object.entries(opts.extraParams)) {
        params = params.set(key, value);
      }
    }
    // Encode each id segment (not the whole CSV) so reserved characters in chart
    // numbers / series ids are escaped while the comma separators stay literal. These
    // ids arrive already decoded (URLSearchParams.getAll in the filter serializer), so
    // encoding is the correct direction and cannot double-encode — unlike repo paths,
    // which the backend pre-encodes and MessageUrlBuilderService deliberately passes
    // through. Numeric area/category ids are unaffected (encoding is a no-op).
    const csv = ids.map((id) => encodeURIComponent(String(id))).join(',');
    return this.http.get<T[]>(`${this.apiBaseUrl}${path}/${csv}`, { params });
  }
}
