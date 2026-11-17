import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import {
  emptyFeatureCollection,
  type MessageArea,
  type MessageGeometryFeatureCollection,
} from '../../models/message.model';
import type { AreaRef } from '../../models/messages-filter-params.model';

@Injectable({ providedIn: 'root' })
export class AreasApiService {
  private readonly http = inject(HttpClient);
  private readonly lang = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  search(
    query: string,
    options?: {
      domainId?: string;
      language?: string;
      limit?: number;
      includeMessageSorting?: boolean;
      includeGeometry?: boolean;
    },
  ): Observable<AreaRef[]> {
    if (!query || query.length === 0) {
      return of([]);
    }
    // Always send `messageSorting` and `geometry` (defaulting to 'false').
    // Omitting them changes the backend response shape — including sort
    // metadata and full geometry — which the messages filter never wants.
    let params = this.lang
      .langParams(options?.language)
      .set('name', query)
      .set('messageSorting', options?.includeMessageSorting ? 'true' : 'false')
      .set('geometry', options?.includeGeometry ? 'true' : 'false')
      .set('limit', String(options?.limit ?? 10));
    if (options?.domainId) {
      params = params.set('domain', options.domainId);
    }
    return this.http.get<AreaRef[]>(`${this.apiBaseUrl}/areas/search`, { params });
  }

  getByIds(
    ids: readonly number[],
    options?: { language?: string; includeGeometry?: boolean; allLanguages?: boolean },
  ): Observable<AreaRef[]> {
    // `allLanguages` omits the `lang` param so the backend's DataFilter has no language filter
    // and returns descs in EVERY language. Needed when importing an area's geometry into the
    // editor — both `name:en` and `name:da` must land on the feature. The lang-scoped
    // search/getByIds path returns only the active language's desc.
    if (options?.allLanguages) {
      if (ids.length === 0) {
        return of([]);
      }
      const csv = ids.map((id) => encodeURIComponent(String(id))).join(',');
      const params = new HttpParams().set('geometry', options.includeGeometry ? 'true' : 'false');
      return this.http.get<AreaRef[]>(`${this.apiBaseUrl}/areas/search/${csv}`, { params });
    }
    return this.lang.getByIdsCsv<AreaRef>('/areas/search', ids, {
      language: options?.language,
      extraParams: { geometry: options?.includeGeometry ? 'true' : 'false' },
    });
  }

  /**
   * Areas whose geometry intersects the message geometry. POSTs the
   * combined message `FeatureCollection` — the caller excludes `affected`-restriction features —
   * and returns the full area objects the editor stores verbatim as `message.areas`. `maxLevel`
   * defaults to 2 and `lang` to the active UI language; `domain=true` scopes
   * the match to the session domain: `POST /rest/areas/intersecting-areas?maxLevel=2&lang=<l>&domain=true`.
   */
  intersectingAreas(
    featureCollection: MessageGeometryFeatureCollection,
    options?: { maxLevel?: number; language?: string },
  ): Observable<MessageArea[]> {
    const params = this.lang
      .langParams(options?.language)
      .set('maxLevel', String(options?.maxLevel ?? 2))
      .set('domain', 'true');
    return this.http.post<MessageArea[]>(
      `${this.apiBaseUrl}/areas/intersecting-areas`,
      featureCollection,
      { params },
    );
  }

  /**
   * Returns the root areas with nested `children` arrays — used by the tree
   * browser dialog. Backed by `GET /rest/areas/area-roots?lang=<l>`.
   */
  getRoots(options?: { language?: string }): Observable<readonly AreaRef[]> {
    const params = this.lang.langParams(options?.language);
    return this.http.get<readonly AreaRef[]>(`${this.apiBaseUrl}/areas/area-roots`, { params });
  }

  /**
   * Adapts an area (fetched with geometry + all languages) into a single-feature
   * collection for the geometry editor, flattening `descs[]` into `name:<lang>`
   * feature properties. Returns an empty collection when the area carries no
   * geometry. Pure DTO→UI-model mapping — no HTTP.
   */
  areaToFeatureCollection(area: AreaRef): MessageGeometryFeatureCollection {
    if (!area.geometry) return emptyFeatureCollection();
    const properties: Record<string, unknown> = { areaId: area.id };
    for (const desc of area.descs ?? []) {
      if (desc.lang && desc.name) properties[`name:${desc.lang}`] = desc.name;
    }
    return {
      type: 'FeatureCollection',
      features: [{ type: 'Feature', id: crypto.randomUUID(), properties, geometry: area.geometry }],
    };
  }
}
