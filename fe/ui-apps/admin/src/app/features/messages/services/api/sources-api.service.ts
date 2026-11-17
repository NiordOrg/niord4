import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';

export interface SourceDesc {
  readonly lang: string;
  readonly name: string;
  readonly abbreviation: string;
}

export interface Source {
  readonly id?: number;
  readonly active: boolean;
  readonly descs: readonly SourceDesc[];
}

interface SourceDescVo {
  lang: string;
  name?: string | null;
  abbreviation?: string | null;
}

interface SourceVo {
  id?: number | null;
  active?: boolean | null;
  descs?: SourceDescVo[] | null;
}

const fromVo = (vo: SourceVo): Source => ({
  id: vo.id ?? undefined,
  active: vo.active ?? false,
  descs: (vo.descs ?? []).map((desc) => ({
    lang: desc.lang,
    name: desc.name ?? '',
    abbreviation: desc.abbreviation ?? '',
  })),
});

/**
 * Sources CRUD. Endpoint contract:
 * - the LIST fetch carries `?lang=` (backend filters descs to one language
 *   and sorts by that language's name);
 * - the DETAILS fetch must NOT carry `lang` (it returns all-language descs);
 * - the export URL is the bare PermitAll list endpoint, no lang, no ticket;
 * - the PUT path id must equal the body id or the backend 400s.
 *
 * `/sources/all` returns every source (active AND inactive) and each carries the
 * `active` flag, so callers that need active-only sources (e.g. the editor's
 * Add-Source picker) filter client-side on `Source.active`.
 *
 * Lives in the messages feature — the sanctioned cross-feature hub — so both the
 * admin Sources CRUD screens and the editor's Add-Source dialog can share it.
 */
@Injectable({ providedIn: 'root' })
export class SourcesApiService {
  private readonly http = inject(HttpClient);
  private readonly langHelper = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getAll(language?: string): Observable<Source[]> {
    return this.http
      .get<SourceVo[]>(`${this.apiBaseUrl}/sources/all`, {
        params: this.langHelper.langParams(language),
      })
      .pipe(map((sources) => sources.map(fromVo)));
  }

  getSource(id: number): Observable<Source> {
    return this.http.get<SourceVo>(`${this.apiBaseUrl}/sources/source/${id}`).pipe(map(fromVo));
  }

  create(source: Source): Observable<Source> {
    return this.http.post<SourceVo>(`${this.apiBaseUrl}/sources/source/`, source).pipe(map(fromVo));
  }

  update(source: Source): Observable<Source> {
    return this.http
      .put<SourceVo>(`${this.apiBaseUrl}/sources/source/${source.id}`, source)
      .pipe(map(fromVo));
  }

  delete(id: number): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/sources/source/${id}`);
  }

  /** Public all-language JSON export (PermitAll — open directly, never ticketed). */
  exportUrl(): string {
    return `${this.apiBaseUrl}/sources/all`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/sources/upload-sources`;
  }
}
