import type Feature from 'ol/Feature';
import type { FeatureLike } from 'ol/Feature';
import type OlMap from 'ol/Map';
import type Geometry from 'ol/geom/Geometry';
import type MultiPolygon from 'ol/geom/MultiPolygon';
import type Polygon from 'ol/geom/Polygon';
import type VectorLayer from 'ol/layer/Vector';
import type { Pixel } from 'ol/pixel';

/**
 * How far from a painted pixel a mouse pointer still lands on a message mark, in CSS pixels.
 *
 * The S-124 portrayal draws thin marks: a two-pixel dashed pen, and hollow symbols whose only
 * opaque pixels are a ring, a box and two letters. OpenLayers hit-tests a feature by the pixels
 * it paints, so without a tolerance a pointer has to land on a dash or a letter stroke exactly.
 */
export const HIT_TOLERANCE_PX = 4;

/** The same for a finger, which covers far more of the screen than the pixel it reports. */
export const TOUCH_HIT_TOLERANCE_PX = 10;

/** The tolerance for the kind of pointer an event came from; unknown reads as a mouse. */
export function hitTolerance(pointerType?: string): number {
  return pointerType === 'touch' ? TOUCH_HIT_TOLERANCE_PX : HIT_TOLERANCE_PX;
}

/** The pointer kind behind an OpenLayers map event, when the browser event carries one. */
export function pointerTypeOf(event: { readonly originalEvent?: unknown }): string | undefined {
  const original = event.originalEvent as { readonly pointerType?: unknown } | undefined;
  return typeof original?.pointerType === 'string' ? original.pointerType : undefined;
}

/**
 * The features of `layers` under a pointer, each once: what a click opens and what a hover
 * names.
 *
 * Two tests, because the portrayal leaves a surface unfilled. First the marks painted within the
 * hit tolerance — a symbol, a tag, a line, an outline, an affected area's translucent fill — in
 * the order OpenLayers reports them, the one drawn on top first. Then every surface that contains
 * the position although nothing is painted there: a reader who points inside an area means that
 * area, and the outline alone is too thin a target to find it by. Those follow the painted hits,
 * smallest first, so a pointer inside nested areas names the innermost before the ones around it.
 *
 * A layer that is hidden is not searched, and a map that has not drawn a frame yet has no pixel
 * to resolve.
 */
export function featuresAtPixel(
  map: OlMap,
  pixel: Pixel,
  layers: readonly (VectorLayer | undefined)[],
  pointerType?: string,
): FeatureLike[] {
  const searched = layers.filter(
    (layer): layer is VectorLayer => layer !== undefined && layer.getVisible(),
  );
  if (searched.length === 0) return [];

  const found: FeatureLike[] = [];
  const seen = new Set<FeatureLike>();
  const layerSet = new Set<unknown>(searched);
  map.forEachFeatureAtPixel(
    pixel,
    (feature) => {
      if (!seen.has(feature)) {
        seen.add(feature);
        found.push(feature);
      }
    },
    { hitTolerance: hitTolerance(pointerType), layerFilter: (layer) => layerSet.has(layer) },
  );

  const coordinate = map.getCoordinateFromPixel(pixel);
  if (!coordinate) return found;

  const inside: { readonly feature: Feature; readonly area: number }[] = [];
  for (const layer of searched) {
    const candidates: readonly Feature[] =
      layer.getSource()?.getFeaturesAtCoordinate(coordinate) ?? [];
    for (const feature of candidates) {
      const area = surfaceArea(feature.getGeometry());
      if (area === undefined || seen.has(feature)) continue;
      seen.add(feature);
      inside.push({ feature, area });
    }
  }
  inside.sort((a, b) => a.area - b.area);
  return [...found, ...inside.map((entry) => entry.feature)];
}

/** A surface's area, or nothing for a point or a line, which only a painted hit can find. */
function surfaceArea(geometry: Geometry | undefined): number | undefined {
  switch (geometry?.getType()) {
    case 'Polygon':
      return (geometry as Polygon).getArea();
    case 'MultiPolygon':
      return (geometry as MultiPolygon).getArea();
    default:
      return undefined;
  }
}
