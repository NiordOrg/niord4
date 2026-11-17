import type { OlMapView } from '@niord/map/ol-map';
import type { DomainMapDefaults } from '../../../core/domain/models/domain.model';
import type { MapConfig } from '../../../core/config/app-config.interface';

/**
 * Two-tier cascade for the initial map center + zoom: session-domain values
 * win per axis, then deployment-config defaults fill any gaps. Each axis falls
 * through independently — a domain with only lat/lon still inherits the config
 * zoom. Returns `undefined` when neither tier supplies anything, letting OL fall
 * back to its own default.
 */
export function computeInitialMapView(
  domain: DomainMapDefaults,
  mapConfig: MapConfig | undefined,
): OlMapView | undefined {
  // Config stores the DB-named lat/lon scalars; OL wants a [lon, lat] center.
  // Both scalars must be present to form a center — half a coordinate is noise.
  const configCenter =
    mapConfig?.mapDefaultLongitude !== undefined && mapConfig.mapDefaultLatitude !== undefined
      ? ([mapConfig.mapDefaultLongitude, mapConfig.mapDefaultLatitude] as const)
      : undefined;
  const center = domain.center ?? configCenter;
  const zoom = domain.zoom ?? mapConfig?.mapDefaultZoomLevel;
  if (center === undefined && zoom === undefined) return undefined;
  return { center, zoom };
}
