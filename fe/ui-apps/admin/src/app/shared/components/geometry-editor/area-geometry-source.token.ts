import { InjectionToken } from '@angular/core';
import type { Observable } from 'rxjs';

import type { GeometryFeatureCollection } from '../../models/geometry.model';
import type { HierarchicalEntity } from '../entity-tree/hierarchical-entity.model';

/**
 * Supplies the geometry import dialog's "From Area" tab with an area catalog.
 * The dialog is a shared component and must not depend on any feature's area
 * API — consumers that have one provide this token (the messages-backed
 * implementation lives in `features/messages`); when it is absent the
 * From-Area tab is hidden and the dialog opens on the From-Text tab.
 */
export interface AreaGeometrySource {
  /** Root areas (with nested children) for the From-Area tree picker. */
  getRoots(): Observable<readonly HierarchicalEntity[]>;

  /** Resolves the picked area to the FeatureCollection to import. */
  getGeometry(area: HierarchicalEntity): Observable<GeometryFeatureCollection>;
}

export const AREA_GEOMETRY_SOURCE = new InjectionToken<AreaGeometrySource>('AREA_GEOMETRY_SOURCE');
