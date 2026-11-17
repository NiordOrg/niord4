import type { Page } from '@playwright/test';

import { settle } from './stabilise';

/** A map's view: its centre in degrees (WGS 84) and its zoom level. */
export interface MapView {
  readonly lon: number;
  readonly lat: number;
  readonly zoom: number;
}

/**
 * Moves every map on the page to a fixed view, as a reader would by panning and zooming, and waits
 * until the page is still again (a messages map searches the extent it moved to). A map that fits
 * itself to all its messages shows another picture whenever a message is published; a fixed view
 * shows the same waters on every run and is chosen so its markers stand apart. The maps reach
 * OpenLayers through Angular's development-mode debugging API, as the stabiliser does, and must
 * be in Web Mercator, the projection of both applications' maps.
 */
export async function showMapAt(page: Page, view: MapView): Promise<void> {
  // A map still loading (after a layer was switched, say) fits itself to what it loaded when the
  // load ends, which would undo a view set before.
  await settle(page);
  const moved = await page.evaluate(({ lon, lat, zoom }) => {
    interface OlView {
      getProjection(): { getCode(): string };
      setCenter(center: number[]): void;
      setZoom(zoom: number): void;
    }
    const debug = (window as unknown as { ng?: { getComponent?: (e: Element) => unknown } }).ng;
    const radius = 6378137;
    const x = (lon * Math.PI * radius) / 180;
    const y = radius * Math.log(Math.tan(Math.PI / 4 + (lat * Math.PI) / 360));
    let count = 0;
    for (const host of Array.from(document.querySelectorAll('app-ol-map'))) {
      const map = (debug?.getComponent?.(host) as { map?: { getView(): OlView } } | null)?.map;
      if (!map) continue;
      const olView = map.getView();
      const code = olView.getProjection().getCode();
      if (code !== 'EPSG:3857') throw new Error(`The map is in ${code}, not Web Mercator.`);
      olView.setCenter([x, y]);
      olView.setZoom(zoom);
      count++;
    }
    return count;
  }, view);
  if (moved === 0) throw new Error('No map on the page could be moved (no Angular debug API?).');
  await settle(page);
}
