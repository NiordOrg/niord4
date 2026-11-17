import type { Feature as OlFeature } from 'ol';
import type { TransformFunction } from 'ol/proj';

const DATA_PROJECTION = 'EPSG:4326';
const FEATURE_PROJECTION = 'EPSG:3857';

/** Swap each flat coordinate pair (X,Y → Y,X) in place, preserving any higher dimensions. */
const swapXY: TransformFunction = (input, output, dimension) => {
  const out = output ?? input;
  const stride = dimension ?? 2;
  for (let i = 0; i < input.length; i += stride) {
    const x = input[i];
    const y = input[i + 1];
    out[i] = y;
    out[i + 1] = x;
    for (let d = 2; d < stride; d++) {
      out[i + d] = input[i + d];
    }
  }
  return out;
};

/**
 * marineregions.org GeoJSON exports deliver coordinates in Y,X (lat,lon) order instead of the
 * GeoJSON-standard X,Y (lon,lat). OL's reader mis-reads the pair as lon/lat and projects it into
 * the map view, so the feature lands transposed. When a feature carries the `mrgid` property those
 * exports set, undo the projection (back to 4326), swap each pair into the correct order, then
 * re-project into the map view. Also surfaces the export's `country` label when there is no `name`.
 *
 * A no-op for every other dropped file (GeoJSON without `mrgid`, KML, WKT).
 */
export function fixMarineRegionsImport(feature: OlFeature): void {
  if (!feature.get('mrgid')) return;

  const geometry = feature.getGeometry();
  if (geometry) {
    geometry.transform(FEATURE_PROJECTION, DATA_PROJECTION);
    geometry.applyTransform(swapXY);
    geometry.transform(DATA_PROJECTION, FEATURE_PROJECTION);
  }

  // MarineRegions labels features with `country` when they carry no `name`; surface it so the
  // feature still gets an on-map label.
  if (feature.get('name') == null && typeof feature.get('country') === 'string') {
    feature.set('name', feature.get('country'));
  }
}
