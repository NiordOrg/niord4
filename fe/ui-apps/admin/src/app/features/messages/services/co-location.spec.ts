import { Feature } from 'ol';
import { LineString, Point } from 'ol/geom';
import { describe, expect, it } from 'vitest';

import { stampCoLocation } from './co-location';

function pointAt(x: number, y: number): Feature {
  return new Feature({ geometry: new Point([x, y]) });
}

describe('stampCoLocation', () => {
  it('stamps count + primary on features sharing a point', () => {
    const a = pointAt(10, 20);
    const b = pointAt(10, 20);
    const c = pointAt(10, 20);
    stampCoLocation([a, b, c]);

    for (const f of [a, b, c]) expect(f.get('coLocationCount')).toBe(3);
    expect(a.get('coLocationPrimary')).toBe(true);
    expect(b.get('coLocationPrimary')).toBe(false);
    expect(c.get('coLocationPrimary')).toBe(false);
  });

  it('leaves a lone point unstamped', () => {
    const a = pointAt(0, 0);
    const b = pointAt(500, 500);
    stampCoLocation([a, b]);
    expect(a.get('coLocationCount')).toBeUndefined();
    expect(b.get('coLocationCount')).toBeUndefined();
  });

  it('groups by metre-rounded coordinate (tolerates float noise)', () => {
    const a = pointAt(10.2, 20.1);
    const b = pointAt(10.4, 19.8);
    stampCoLocation([a, b]);
    expect(a.get('coLocationCount')).toBe(2);
  });

  it('ignores non-point geometries', () => {
    const line = new Feature({
      geometry: new LineString([
        [0, 0],
        [1, 1],
      ]),
    });
    const p = pointAt(0, 0);
    stampCoLocation([line, p]);
    expect(line.get('coLocationCount')).toBeUndefined();
    expect(p.get('coLocationCount')).toBeUndefined();
  });
});
