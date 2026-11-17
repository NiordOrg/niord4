import { inject, Injectable } from '@angular/core';
import { catchError, map, of, type Observable } from 'rxjs';

import type { HierarchicalEntity } from '../../../shared/components/entity-tree/hierarchical-entity.model';
import type { AreaGeometrySource } from '../../../shared/components/geometry-editor/area-geometry-source.token';
import type { GeometryFeatureCollection } from '../../../shared/models/geometry.model';
import type { AreaRef } from '../models/messages-filter-params.model';
import { AreasApiService } from './api/areas-api.service';

/**
 * The messages-backed `AreaGeometrySource` for the geometry import dialog's
 * "From Area" tab. Consumers that host the geometry editor provide it:
 * `{ provide: AREA_GEOMETRY_SOURCE, useClass: MessagesAreaGeometrySource }`.
 */
@Injectable({ providedIn: 'root' })
export class MessagesAreaGeometrySource implements AreaGeometrySource {
  private readonly areasApi = inject(AreasApiService);

  getRoots(): Observable<readonly HierarchicalEntity[]> {
    return this.areasApi.getRoots();
  }

  /**
   * Re-fetches the picked area with geometry + ALL languages, so both `name:en`
   * and `name:da` land on the feature — the lang-scoped tree carries only the
   * active language's desc (and no geometry). Falls back to the tree's own
   * (geometry-less) ref on fetch failure, yielding an empty collection.
   */
  getGeometry(area: HierarchicalEntity): Observable<GeometryFeatureCollection> {
    // Safe widening: the picked entity always originates from this source's own
    // getRoots(), which returns AreaRefs.
    const ref = area as AreaRef;
    return this.areasApi.getByIds([ref.id], { includeGeometry: true, allLanguages: true }).pipe(
      catchError(() => of([ref] as readonly AreaRef[])),
      map((areas) => this.areasApi.areaToFeatureCollection(areas[0] ?? ref)),
    );
  }
}
