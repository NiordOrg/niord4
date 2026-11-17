import { getWidth } from 'ol/extent';
import { GeometryCollection, type Geometry, SimpleGeometry } from 'ol/geom';
import { get as getProjection, type ProjectionLike } from 'ol/proj';

/** The editor's map projection (as `import-marineregions.ts` and the conversion service assume). */
const MAP_PROJECTION = 'EPSG:3857';

/** The x extent of one world in `projection`: its left edge and its width. */
function worldOf(projection: ProjectionLike): { minX: number; width: number } {
  const extent = getProjection(projection)?.getExtent();
  if (!extent) {
    throw new Error(`No extent for the map projection ${String(projection)}`);
  }
  return { minX: extent[0], width: getWidth(extent) };
}

/**
 * How many whole world widths (turns of 360 degrees) the middle of the geometry's longitude
 * extent lies east (positive) or west (negative) of the globe's [-180, 180). A geometry drawn on a
 * wrapped world copy, or read from a file with a longitude beyond 180, has a count other than 0.
 * A geometry without a position (empty, or with a coordinate that is not a number) counts 0.
 */
export function wholeTurnsOffGlobe(
  geometry: Geometry,
  projection: ProjectionLike = MAP_PROJECTION,
): number {
  const [minX, , maxX] = geometry.getExtent();
  const middle = (minX + maxX) / 2;
  if (!Number.isFinite(middle)) return 0;
  const world = worldOf(projection);
  return Math.floor((middle - world.minX) / world.width);
}

/**
 * Moves the geometry in place by whole world widths so that the middle of its longitude extent
 * lies in [-180, 180), as the backend does on save (`JtsConverter.toJtsOnGlobe`). The whole
 * geometry moves by the same turns, never each point on its own, so a geometry that crosses the
 * antimeridian keeps its shape. Returns whether it moved; a geometry on the globe is not touched.
 */
export function shiftOntoGlobe(
  geometry: Geometry,
  projection: ProjectionLike = MAP_PROJECTION,
): boolean {
  const turns = wholeTurnsOffGlobe(geometry, projection);
  if (turns === 0) return false;
  geometry.translate(-turns * worldOf(projection).width, 0);
  return true;
}

/**
 * Whether the geometry holds a coordinate that is not a finite number. In the map's projection
 * that is what a latitude beyond 90 in a dropped file becomes (the Mercator projection has no
 * place for it), so such a geometry is no position on the globe and is refused by the editor.
 */
export function hasPositionOffTheMap(geometry: Geometry): boolean {
  if (geometry instanceof GeometryCollection) {
    return geometry.getGeometriesArray().some((member) => hasPositionOffTheMap(member));
  }
  if (geometry instanceof SimpleGeometry) {
    return geometry.getFlatCoordinates().some((value) => !Number.isFinite(value));
  }
  return false;
}
