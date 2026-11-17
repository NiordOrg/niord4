import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Geometry } from 'geojson';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/** The two non-blank area types (the backend `AreaType` enum). */
export type AreaType = 'COUNTRY' | 'FIRING_AREA';
/** Geometric message-sorting strategies (blank = manual "area order"). */
export type MessageSortingType = 'NS' | 'SN' | 'EW' | 'WE' | 'CW' | 'CCW';

export interface AdminAreaDesc {
  lang: string;
  name?: string;
}

/**
 * The writable admin area model (the backend `SystemAreaVo`) — richer than the read-only `AreaRef` used
 * across messages: it carries the admin-only `type`, `messageSorting` strategy (+ CW/CCW origin),
 * `editorFields`, `siblingSortOrder`, `children` and `geometry`. `id` is undefined for a new area.
 */
export interface AdminArea {
  id?: number;
  mrn?: string;
  active: boolean;
  /** On the wire the parent is a thin `{ id }` ref; `getArea?parent=true` returns the full chain. */
  parent?: AdminArea;
  children?: AdminArea[];
  descs: AdminAreaDesc[];
  type?: AreaType;
  messageSorting?: MessageSortingType;
  originLatitude?: number;
  originLongitude?: number;
  originAngle?: number;
  editorFields?: string[];
  siblingSortOrder?: number;
  geometry?: Geometry;
}

/**
 * Areas CRUD + tree management (the admin catalog — distinct from the read-side messages
 * `AreasApiService` which owns search / area-roots / intersecting). Endpoint contract:
 * - select fetches the ancestor chain (`?parent=true`) which drives the message-sorting inheritance;
 * - `move-area` re-parents (null `parentId` = root); `change-sort-order` reorders siblings;
 *   `recompute-tree-sort-order` re-indexes the whole tree; all are `@RolesAllowed(ADMIN)`;
 * - export is the bare PermitAll `GET /areas/all`; import posts to `upload-areas`;
 * - firing-area generation lives on `/firing-schedules` but is triggered from the Areas page.
 */
@Injectable({ providedIn: 'root' })
export class AreasAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** The full area incl. its ancestor chain (`parent=true`) — drives the message-sorting inheritance. */
  getArea(id: number, options?: { language?: string }): Observable<AdminArea> {
    let params = new HttpParams().set('parent', 'true');
    if (options?.language) {
      params = params.set('lang', options.language);
    }
    return this.http.get<AdminArea>(`${this.apiBaseUrl}/areas/area/${id}`, { params });
  }

  create(area: AdminArea): Observable<AdminArea> {
    return this.http.post<AdminArea>(`${this.apiBaseUrl}/areas/area/`, area);
  }

  update(area: AdminArea): Observable<AdminArea> {
    return this.http.put<AdminArea>(`${this.apiBaseUrl}/areas/area/${area.id}`, area);
  }

  delete(id: number): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/areas/area/${id}`);
  }

  /** Re-parent an area; `parentId === null` moves it to the tree root. */
  moveArea(areaId: number, parentId: number | null): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/areas/move-area`, { areaId, parentId });
  }

  /** Reorder an area among its siblings (toolbar Up/Down). */
  changeSortOrder(areaId: number, moveUp: boolean): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/areas/change-sort-order`, { areaId, moveUp });
  }

  /** Re-index the whole area tree's sort order (cog "Re-index areas"). */
  recomputeTreeSortOrder(): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/areas/recompute-tree-sort-order`, {});
  }

  /** Generate firing-area template messages (backend 403s if the series is not in the current domain). */
  generateFiringAreaMessages(seriesId: string, tagId?: string): Observable<unknown> {
    return this.http.post(`${this.apiBaseUrl}/firing-schedules/generate-firing-area-messages`, {
      seriesId,
      tagId,
    });
  }

  exportUrl(): string {
    return `${this.apiBaseUrl}/areas/all`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/areas/upload-areas`;
  }
}
