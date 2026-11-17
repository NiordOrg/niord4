import Feature, { type FeatureLike } from 'ol/Feature';
import OlMapImpl from 'ol/Map';
import LineString from 'ol/geom/LineString';
import Point from 'ol/geom/Point';
import Polygon from 'ol/geom/Polygon';
import type BaseLayer from 'ol/layer/Base';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  featuresAtPixel,
  HIT_TOLERANCE_PX,
  hitTolerance,
  pointerTypeOf,
  TOUCH_HIT_TOLERANCE_PX,
} from './hit-detection';

/** A square surface centred on the origin, `half` map units from centre to edge. */
function square(half: number): Feature {
  return new Feature(
    new Polygon([
      [
        [-half, -half],
        [half, -half],
        [half, half],
        [-half, half],
        [-half, -half],
      ],
    ]),
  );
}

function layerWith(...features: Feature[]): VectorLayer {
  return new VectorLayer({ source: new VectorSource({ features }) });
}

interface HitOptions {
  hitTolerance?: number;
  layerFilter?: (layer: BaseLayer) => boolean;
}

describe('featuresAtPixel', () => {
  let map: OlMapImpl;
  /** What the painted-pixel test reports; jsdom paints nothing, so the test decides. */
  let painted: { feature: FeatureLike; layer: VectorLayer }[];
  let lastOptions: HitOptions | undefined;

  beforeEach(() => {
    map = new OlMapImpl({});
    painted = [];
    lastOptions = undefined;
    vi.spyOn(map, 'forEachFeatureAtPixel').mockImplementation(((
      _pixel: unknown,
      callback: (feature: FeatureLike, layer: VectorLayer) => unknown,
      options?: HitOptions,
    ) => {
      lastOptions = options;
      for (const hit of painted) {
        if (options?.layerFilter && !options.layerFilter(hit.layer)) continue;
        callback(hit.feature, hit.layer);
      }
    }) as never);
    // The pointer sits on the origin, which every square below contains.
    vi.spyOn(map, 'getCoordinateFromPixel').mockReturnValue([0, 0]);
  });

  afterEach(() => map.dispose());

  it('finds a surface the pointer is inside, although nothing is painted there', () => {
    const area = square(100);
    const layer = layerWith(area);

    expect(featuresAtPixel(map, [50, 50], [layer])).toEqual([area]);
  });

  it('names the innermost of nested surfaces first, after the marks painted under the pointer', () => {
    const outer = square(1000);
    const inner = square(10);
    const middle = square(100);
    const symbol = new Feature(new Point([0, 0]));
    const layer = layerWith(outer, inner, middle, symbol);
    painted = [{ feature: symbol, layer }];

    expect(featuresAtPixel(map, [50, 50], [layer])).toEqual([symbol, inner, middle, outer]);
  });

  it('reports a surface once when its outline is painted under the pointer too', () => {
    const area = square(100);
    const layer = layerWith(area);
    painted = [{ feature: area, layer }];

    expect(featuresAtPixel(map, [50, 50], [layer])).toEqual([area]);
  });

  it('leaves points and lines to the painted test: containment is for surfaces', () => {
    const point = new Feature(new Point([0, 0]));
    const line = new Feature(
      new LineString([
        [-10, 0],
        [10, 0],
      ]),
    );
    const layer = layerWith(point, line);

    expect(featuresAtPixel(map, [50, 50], [layer])).toEqual([]);
  });

  it('searches only the layers it is given, and none that is hidden', () => {
    const mine = layerWith(square(100));
    const other = layerWith(square(100));
    const hidden = layerWith(square(100));
    hidden.setVisible(false);
    painted = [{ feature: other.getSource()!.getFeatures()[0], layer: other }];

    const found = featuresAtPixel(map, [50, 50], [mine, hidden, undefined]);

    expect(found).toEqual(mine.getSource()!.getFeatures());
    expect(lastOptions?.layerFilter?.(other)).toBe(false);
    expect(lastOptions?.layerFilter?.(hidden)).toBe(false);
    expect(lastOptions?.layerFilter?.(mine)).toBe(true);
  });

  it('returns only the painted marks while the map has drawn no frame to locate the pixel in', () => {
    vi.mocked(map.getCoordinateFromPixel).mockReturnValue(null as never);
    const symbol = new Feature(new Point([0, 0]));
    const layer = layerWith(square(100), symbol);
    painted = [{ feature: symbol, layer }];

    expect(featuresAtPixel(map, [50, 50], [layer])).toEqual([symbol]);
  });

  it('gives a finger a wider tolerance than a mouse', () => {
    const layer = layerWith();
    layer.getSource()!.addFeature(square(1));

    featuresAtPixel(map, [50, 50], [layer]);
    expect(lastOptions?.hitTolerance).toBe(HIT_TOLERANCE_PX);

    featuresAtPixel(map, [50, 50], [layer], 'touch');
    expect(lastOptions?.hitTolerance).toBe(TOUCH_HIT_TOLERANCE_PX);
    expect(TOUCH_HIT_TOLERANCE_PX).toBeGreaterThan(HIT_TOLERANCE_PX);
  });
});

describe('hitTolerance', () => {
  it('reads an unknown pointer, a mouse and a pen as precise', () => {
    expect(hitTolerance()).toBe(HIT_TOLERANCE_PX);
    expect(hitTolerance('mouse')).toBe(HIT_TOLERANCE_PX);
    expect(hitTolerance('pen')).toBe(HIT_TOLERANCE_PX);
    expect(hitTolerance('touch')).toBe(TOUCH_HIT_TOLERANCE_PX);
  });
});

describe('pointerTypeOf', () => {
  it('reads the pointer kind off the browser event behind a map event', () => {
    expect(pointerTypeOf({ originalEvent: { pointerType: 'touch' } })).toBe('touch');
    expect(pointerTypeOf({ originalEvent: new MouseEvent('click') })).toBeUndefined();
    expect(pointerTypeOf({})).toBeUndefined();
  });
});
