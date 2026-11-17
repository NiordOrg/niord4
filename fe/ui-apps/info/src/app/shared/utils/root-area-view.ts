import type { OlMapView } from '@niord/map/ol-map';

import type { RootAreaSpec } from '../../niord-info.config';

/** The framing a root area carries: its country spec's centre and zoom. */
interface Framed {
  readonly latitude?: number;
  readonly longitude?: number;
  readonly zoomLevel?: number;
}

/**
 * A deployment with no root area has no home waters to show, so its maps start on the whole
 * world until the messages frame them.
 */
export const WORLD_VIEW: OlMapView = { center: [0, 30], zoom: 2 };

/** Where a map points for a root area, or nothing when its framing is incomplete. */
export function rootAreaView(area: Framed | undefined): OlMapView | undefined {
  if (
    typeof area?.latitude !== 'number' ||
    typeof area.longitude !== 'number' ||
    typeof area.zoomLevel !== 'number'
  ) {
    return undefined;
  }
  return { center: [area.longitude, area.latitude], zoom: area.zoomLevel };
}

/**
 * Where a map of this deployment points before it has drawn anything: the root area named by
 * `areaId`, otherwise the country's first, otherwise the world.
 *
 * It is known the moment the page starts — the root areas are the build's — so the first tiles a
 * map fetches are the country's own, never another country's that the fit then replaces.
 */
export function initialMapView(specs: readonly RootAreaSpec[], areaId?: string): OlMapView {
  const named = areaId ? specs.find((spec) => spec.areaId === areaId) : undefined;
  return rootAreaView(named ?? specs[0]) ?? WORLD_VIEW;
}
