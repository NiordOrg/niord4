import { Feature } from 'ol';
import { LineString, Point } from 'ol/geom';
import { fromLonLat, toLonLat } from 'ol/proj';
import { describe, expect, it } from 'vitest';

import { fixMarineRegionsImport } from './import-marineregions';

/** A feature whose geometry OL read into the map projection (EPSG:3857) from the given lon/lat. */
function pointAt(lon: number, lat: number, props: Record<string, unknown> = {}): Feature {
  const feature = new Feature(new Point(fromLonLat([lon, lat])));
  for (const [key, value] of Object.entries(props)) {
    feature.set(key, value);
  }
  return feature;
}

function lonLatOf(feature: Feature): [number, number] {
  return toLonLat((feature.getGeometry() as Point).getCoordinates()) as [number, number];
}

describe('fixMarineRegionsImport', () => {
  it('swaps the transposed (Y,X) coordinates of an mrgid feature back to X,Y', () => {
    // marineregions.org delivered lat=55,lon=10 as [55,10]; OL mis-read it as lon=55,lat=10.
    const feature = pointAt(55, 10, { mrgid: 12345 });
    fixMarineRegionsImport(feature);
    const [lon, lat] = lonLatOf(feature);
    expect(lon).toBeCloseTo(10, 6);
    expect(lat).toBeCloseTo(55, 6);
  });

  it('swaps every coordinate of a multi-vertex geometry', () => {
    const feature = new Feature(new LineString([fromLonLat([55, 10]), fromLonLat([60, 20])]));
    feature.set('mrgid', 1);
    fixMarineRegionsImport(feature);
    const coords = (feature.getGeometry() as LineString)
      .getCoordinates()
      .map((c) => toLonLat(c).map((n) => Math.round(n)));
    expect(coords).toEqual([
      [10, 55],
      [20, 60],
    ]);
  });

  it('is a no-op for a feature without mrgid (KML / plain GeoJSON / WKT)', () => {
    const feature = pointAt(10, 55, { country: 'Anchorland' });
    fixMarineRegionsImport(feature);
    const [lon, lat] = lonLatOf(feature);
    expect(lon).toBeCloseTo(10, 6);
    expect(lat).toBeCloseTo(55, 6);
    expect(feature.get('name')).toBeUndefined(); // no country→name fallback without mrgid
  });

  it('falls back to the `country` label when an mrgid feature has no name', () => {
    const feature = pointAt(55, 10, { mrgid: 1, country: 'Anchorland' });
    fixMarineRegionsImport(feature);
    expect(feature.get('name')).toBe('Anchorland');
  });

  it('keeps an explicit `name` over the `country` fallback', () => {
    const feature = pointAt(55, 10, { mrgid: 1, country: 'Anchorland', name: 'Kattegat' });
    fixMarineRegionsImport(feature);
    expect(feature.get('name')).toBe('Kattegat');
  });
});
