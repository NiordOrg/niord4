import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable, of } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import type { CategoryRef } from '../../models/messages-filter-params.model';
import type { SystemCategory } from '../../models/template.model';

@Injectable({ providedIn: 'root' })
export class CategoriesApiService {
  private readonly http = inject(HttpClient);
  private readonly lang = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  search(
    query: string,
    options?: { domainId?: string; language?: string; limit?: number },
  ): Observable<CategoryRef[]> {
    if (!query || query.length === 0) {
      return of([]);
    }
    let params = this.lang
      .langParams(options?.language)
      .set('name', query)
      .set('limit', String(options?.limit ?? 10));
    if (options?.domainId) {
      params = params.set('domain', options.domainId);
    }
    return this.http.get<CategoryRef[]>(`${this.apiBaseUrl}/categories/search`, { params });
  }

  getByIds(ids: readonly number[], options?: { language?: string }): Observable<CategoryRef[]> {
    return this.lang.getByIdsCsv<CategoryRef>('/categories/search', ids, options);
  }

  /**
   * TEMPLATE categories (executable message templates) available in a domain — powers the
   * editor Start screen's "from a template" pickers. Backed by
   * `GET /rest/categories/search?type=TEMPLATE&domain=<domainId>&lang=<l>`. Templates are NOT
   * partitioned by main type on the wire (the endpoint has no `mainType` filter), so both the
   * NW and NM pickers draw from the same domain template set — the mainType is applied only when
   * the created draft is seeded, not to this search.
   */
  searchTemplates(options?: {
    domainId?: string;
    language?: string;
    limit?: number;
    /** Restrict to descendants of a parent category (the select-tab ancestor filter). */
    ancestorId?: number;
    /** Free-text name filter (the select-tab search box). */
    name?: string;
  }): Observable<SystemCategory[]> {
    let params = this.lang
      .langParams(options?.language)
      .set('type', 'TEMPLATE')
      .set('limit', String(options?.limit ?? 100));
    if (options?.domainId) {
      params = params.set('domain', options.domainId);
    }
    if (options?.ancestorId !== undefined) {
      params = params.set('ancestorId', String(options.ancestorId));
    }
    if (options?.name) {
      params = params.set('name', options.name);
    }
    return this.http.get<SystemCategory[]>(`${this.apiBaseUrl}/categories/search`, { params });
  }

  /**
   * Re-hydrate thin category refs to full {@link SystemCategory} detail (`type`,
   * `scriptResourcePaths`, `stdTemplateFields`, `templateParams`) — the execute form needs the
   * template metadata that the message's `categories` refs don't carry.
   */
  getTemplateDetails(
    ids: readonly number[],
    options?: { language?: string },
  ): Observable<SystemCategory[]> {
    return this.lang.getByIdsCsv<SystemCategory>('/categories/search', ids, options);
  }

  /**
   * Returns the root categories with nested `children` arrays — used by the
   * tree browser dialog. Backed by `GET /rest/categories/category-roots?lang=<l>`.
   */
  getRoots(options?: { language?: string }): Observable<readonly CategoryRef[]> {
    const params = this.lang.langParams(options?.language);
    return this.http.get<readonly CategoryRef[]>(`${this.apiBaseUrl}/categories/category-roots`, {
      params,
    });
  }
}
