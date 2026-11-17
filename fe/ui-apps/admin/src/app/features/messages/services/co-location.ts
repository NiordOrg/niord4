import type { Feature as OlFeature } from 'ol';
import { Point } from 'ol/geom';

const COLOCATION_COUNT_KEY = 'coLocationCount';
const COLOCATION_PRIMARY_KEY = 'coLocationPrimary';

/**
 * S-124 co-location handling: when several warnings share the same point, one symbol
 * is drawn and the count of co-located warnings is shown as a small badge. This groups
 * point features by their (metre-rounded) coordinate and stamps `coLocationCount` on
 * every member plus `coLocationPrimary` on the first — which `map-styles` reads to draw
 * the badge once. Non-point features are ignored.
 */
export function stampCoLocation(features: readonly OlFeature[]): void {
  const groups = new Map<string, OlFeature[]>();
  for (const feature of features) {
    const geom = feature.getGeometry();
    if (!(geom instanceof Point)) continue;
    const [x, y] = geom.getCoordinates();
    const key = `${Math.round(x)}:${Math.round(y)}`;
    const group = groups.get(key);
    if (group) group.push(feature);
    else groups.set(key, [feature]);
  }
  for (const group of groups.values()) {
    if (group.length < 2) continue;
    group.forEach((feature, index) => {
      feature.set(COLOCATION_COUNT_KEY, group.length);
      feature.set(COLOCATION_PRIMARY_KEY, index === 0);
    });
  }
}
