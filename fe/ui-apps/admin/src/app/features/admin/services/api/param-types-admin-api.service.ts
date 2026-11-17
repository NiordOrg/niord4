import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { DictionaryEntry } from './dictionaries-api.service';

export type ParamTypeKind = 'STANDARD' | 'LIST' | 'COMPOSITE';

/** Per-language name of a nested COMPOSITE parameter. */
export interface TemplateParamDesc {
  readonly lang: string;
  readonly name: string;
}

/** A nested parameter inside a COMPOSITE param type (or a TEMPLATE category). */
export interface TemplateParam {
  readonly paramId: string;
  /** The referenced param-type's NAME (not the numeric id). */
  readonly type: string;
  readonly mandatory: boolean;
  readonly positionList: boolean;
  readonly descs: readonly TemplateParamDesc[];
}

export interface ParamType {
  readonly id?: number;
  readonly type: ParamTypeKind;
  readonly name?: string;
  /** LIST kind — an ordered subset of a dictionary's entries. */
  readonly values?: readonly DictionaryEntry[];
  /** COMPOSITE kind — an ordered set of nested parameters. */
  readonly templateParams?: readonly TemplateParam[];
}

interface ParamTypeVo {
  id?: number | null;
  type: ParamTypeKind;
  name?: string | null;
  values?: DictionaryEntry[] | null;
  templateParams?: TemplateParam[] | null;
}

const fromVo = (vo: ParamTypeVo): ParamType => ({
  id: vo.id ?? undefined,
  type: vo.type,
  name: vo.name ?? undefined,
  values: vo.values ?? undefined,
  templateParams: vo.templateParams ?? undefined,
});

/**
 * Parameter-types CRUD + public JSON export. Wraps `/rest/templates/*` (no backend
 * change). The list is fetched all-language (no `?lang=`) and ordered client-side
 * (Dictionaries precedent); `getDetails` carries the full `values`/`templateParams`.
 */
@Injectable({ providedIn: 'root' })
export class ParamTypesAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getAll(): Observable<ParamType[]> {
    return this.http
      .get<ParamTypeVo[]>(`${this.apiBaseUrl}/templates/parameter-types`)
      .pipe(map((vos) => vos.map(fromVo)));
  }

  getDetails(id: number): Observable<ParamType> {
    return this.http
      .get<ParamTypeVo>(`${this.apiBaseUrl}/templates/parameter-type/${id}`)
      .pipe(map(fromVo));
  }

  create(paramType: ParamType): Observable<unknown> {
    return this.http.post(`${this.apiBaseUrl}/templates/parameter-type/`, paramType);
  }

  update(paramType: ParamType): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/templates/parameter-type/${paramType.id}`, paramType);
  }

  delete(id: number): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/templates/parameter-type/${id}`);
  }

  /** Public JSON export (no ticket) — opened via `TicketedDownloadService.openPublic`. */
  exportUrl(): string {
    return `${this.apiBaseUrl}/templates/parameter-types/export`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/templates/upload-param-types`;
  }
}
