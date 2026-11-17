/**
 * Maps an OpenLayers geometry type to the icon shown for it — shared by the
 * geometry tree's feature-card icon and the editor toolbar / convert menu, so a
 * given geometry reads with the same glyph everywhere.
 *
 * `lucideStar` stands in for Polygon (the most distinct "many-edged" shape vs the
 * square); `lucideEllipsis` (a row of dots) stands in for MultiPoint.
 */
export const GEOMETRY_TYPE_ICONS: Record<string, string> = {
  Point: 'lucideMapPin',
  MultiPoint: 'lucideEllipsis',
  LineString: 'lucideChartLine',
  MultiLineString: 'lucideChartLine',
  Polygon: 'lucideStar',
  MultiPolygon: 'lucideStar',
  GeometryCollection: 'lucideColumns3',
  Circle: 'lucideCircle',
};

/** The icon for a geometry type, falling back to the point marker for unknown types. */
export function iconForGeometryType(type: string | undefined): string {
  return (type && GEOMETRY_TYPE_ICONS[type]) || 'lucideMapPin';
}
