import type { Geometry } from 'ol/geom';
import { describe, expect, it } from 'vitest';

import { WktImportFormat } from './wkt-import-format';

const FEATURE_PROJECTION = 'EPSG:3857';

function readInto3857(wkt: string) {
  return new WktImportFormat().readFeatures(wkt, { featureProjection: FEATURE_PROJECTION });
}

describe('WktImportFormat', () => {
  it('reads a single WKT geometry and projects degrees (4326) into the map projection (3857)', () => {
    const features = readInto3857('POINT (10 56)');
    expect(features).toHaveLength(1);
    const [x, y] = (features[0].getGeometry() as Geometry).getExtent();
    // 10°E / 56°N in Web Mercator metres — far from the untransformed (10, 56) that the
    // bug produced. Confirms the dataProjection=4326 fix is applied.
    expect(x).toBeGreaterThan(1_000_000);
    expect(y).toBeGreaterThan(7_000_000);
  });

  it('reads one-geometry-per-line WKT files as separate features', () => {
    const features = readInto3857('POINT (10 56)\nPOINT (11 57)\n');
    expect(features).toHaveLength(2);
  });

  it('still reads a normal single geometry that contains no newlines', () => {
    const features = readInto3857('POLYGON ((10 55, 12 55, 12 57, 10 57, 10 55))');
    expect(features).toHaveLength(1);
    expect(features[0].getGeometry()?.getType()).toBe('Polygon');
  });
});
