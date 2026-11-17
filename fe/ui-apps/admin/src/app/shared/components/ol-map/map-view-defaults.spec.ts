import { describe, expect, it } from 'vitest';

import { computeInitialMapView } from './map-view-defaults';

describe('computeInitialMapView', () => {
  it('domain values win over config defaults per axis', () => {
    const view = computeInitialMapView(
      { center: [10, 55], zoom: 8 },
      { mapDefaultLongitude: 11, mapDefaultLatitude: 56, mapDefaultZoomLevel: 6 },
    );
    expect(view).toEqual({ center: [10, 55], zoom: 8 });
  });

  it('a domain with only a center still inherits the config zoom (per-axis fallthrough)', () => {
    const view = computeInitialMapView(
      { center: [10, 55], zoom: undefined },
      { mapDefaultLongitude: 11, mapDefaultLatitude: 56, mapDefaultZoomLevel: 6 },
    );
    expect(view).toEqual({ center: [10, 55], zoom: 6 });
  });

  it('assembles the config center as [lon, lat] from the two DB-named scalars', () => {
    const view = computeInitialMapView(
      { center: undefined, zoom: undefined },
      { mapDefaultLongitude: 11, mapDefaultLatitude: 56, mapDefaultZoomLevel: 6 },
    );
    expect(view).toEqual({ center: [11, 56], zoom: 6 });
  });

  it('ignores a half coordinate — one scalar alone never forms a center', () => {
    const latOnly = computeInitialMapView(
      { center: undefined, zoom: undefined },
      { mapDefaultLatitude: 56, mapDefaultZoomLevel: 6 },
    );
    expect(latOnly).toEqual({ center: undefined, zoom: 6 });

    const lonOnly = computeInitialMapView(
      { center: undefined, zoom: undefined },
      { mapDefaultLongitude: 11 },
    );
    expect(lonOnly).toBeUndefined();
  });

  it('returns undefined when neither tier supplies anything (OL default view)', () => {
    expect(
      computeInitialMapView({ center: undefined, zoom: undefined }, undefined),
    ).toBeUndefined();
    expect(computeInitialMapView({ center: undefined, zoom: undefined }, {})).toBeUndefined();
  });
});
