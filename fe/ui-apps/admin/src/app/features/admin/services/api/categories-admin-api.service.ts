import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { Message } from '../../../messages/models/message.model';
import type { TemplateParam } from './param-types-admin-api.service';

/** The two category flavours (the backend `CategoryType` enum). */
export type CategoryType = 'CATEGORY' | 'TEMPLATE';

export interface AdminCategoryDesc {
  lang: string;
  name?: string;
}

/** A category's domain membership (TEMPLATE only) — a thin `{domainId, active}` ref on the wire. */
export interface CategoryDomainRef {
  domainId: string;
  active?: boolean;
}

/**
 * The writable admin category model (the backend `SystemCategoryVo`) — the CATEGORY fields plus the
 * TEMPLATE authoring block. `id` is undefined for a new category; `parent` is a thin `{ id }` ref on
 * the wire. Mirrors `AdminArea` minus geometry / message-sorting, adds `type` + `atonFilter` + the
 * template fields.
 */
export interface AdminCategory {
  id?: number;
  mrn?: string;
  active: boolean;
  parent?: AdminCategory;
  children?: AdminCategory[];
  descs: AdminCategoryDesc[];
  type?: CategoryType;
  editorFields?: string[];
  atonFilter?: string;
  siblingSortOrder?: number;
  // ── TEMPLATE-only ────────────────────────────────────────────────────────────
  domains?: CategoryDomainRef[];
  stdTemplateFields?: string[];
  templateParams?: TemplateParam[];
  scriptResourcePaths?: string[];
  messageId?: string;
}

/**
 * Categories CRUD + tree management (the admin catalog — distinct from the read-side messages
 * `CategoriesApiService` which owns search / category-roots). Endpoint contract:
 * - select fetches a single record (NO parent/children — categories have no sorting inheritance);
 * - `move-category` re-parents (null `parentId` = root); `change-sort-order` reorders siblings;
 *   `recompute-tree-sort-order` re-indexes the whole tree; all are `@RolesAllowed(SYSADMIN)`;
 * - `templates/execute` runs a TEMPLATE against an example message (the "Test…" preview);
 * - export is the pruned `GET /categories/export`; import posts to `upload-categories`.
 */
@Injectable({ providedIn: 'root' })
export class CategoriesAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** A single category detail record — no parent, no children. */
  getCategory(id: number): Observable<AdminCategory> {
    return this.http.get<AdminCategory>(`${this.apiBaseUrl}/categories/category/${id}`);
  }

  create(category: AdminCategory): Observable<AdminCategory> {
    return this.http.post<AdminCategory>(`${this.apiBaseUrl}/categories/category/`, category);
  }

  update(category: AdminCategory): Observable<AdminCategory> {
    return this.http.put<AdminCategory>(
      `${this.apiBaseUrl}/categories/category/${category.id}`,
      category,
    );
  }

  delete(id: number): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/categories/category/${id}`);
  }

  /** Re-parent a category; `parentId === null` moves it to the tree root. */
  moveCategory(categoryId: number, parentId: number | null): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/categories/move-category`, { categoryId, parentId });
  }

  /** Reorder a category among its siblings (toolbar Up/Down). */
  changeSortOrder(categoryId: number, moveUp: boolean): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/categories/change-sort-order`, { categoryId, moveUp });
  }

  /** Re-index the whole category tree's sort order (cog "Re-index categories"). */
  recomputeTreeSortOrder(): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/categories/recompute-tree-sort-order`, {});
  }

  /**
   * Execute a TEMPLATE category against an example message (the "Test…" preview). The
   * `messageId` is URL-encoded in the body and the whole edited category travels along.
   */
  executeTemplate(category: AdminCategory, messageId: string): Observable<Message> {
    return this.http.put<Message>(`${this.apiBaseUrl}/templates/execute`, {
      messageId: encodeURIComponent(messageId),
      category,
    });
  }

  /** The pruned export (ids stripped, domains reduced) — not `/categories/all`. */
  exportUrl(): string {
    return `${this.apiBaseUrl}/categories/export`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/categories/upload-categories`;
  }
}
