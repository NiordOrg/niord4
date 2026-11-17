import { Feature } from 'ol';
import { Point } from 'ol/geom';
import { describe, expect, it } from 'vitest';

import { applyCoordNameUpdates } from './coord-names';

function featureWithNames(names: Record<string, string>): Feature {
  const feature = new Feature(new Point([0, 0]));
  for (const [key, value] of Object.entries(names)) {
    feature.set(key, value);
  }
  return feature;
}

describe('coord-names applyCoordNameUpdates', () => {
  it('offsets names at/after an insertion point by +1', () => {
    const feature = featureWithNames({ 'name:0:en': 'A', 'name:1:en': 'B', 'name:2:en': 'C' });
    // Insert a vertex after index 0 → every name at index ≥ 1 shifts up.
    applyCoordNameUpdates(feature, [{ type: 'offset', fromIndex: 1, offset: 1 }]);
    expect(feature.get('name:0:en')).toBe('A');
    expect(feature.get('name:1:en')).toBeUndefined();
    expect(feature.get('name:2:en')).toBe('B');
    expect(feature.get('name:3:en')).toBe('C');
  });

  it('removes the deleted index and shifts the tail down (delete)', () => {
    const feature = featureWithNames({ 'name:0:en': 'A', 'name:1:en': 'B', 'name:2:en': 'C' });
    // Delete vertex at index 1: remove [1,2), then offset [2,∞) by -1.
    applyCoordNameUpdates(feature, [
      { type: 'remove', fromIndex: 1, toIndex: 2 },
      { type: 'offset', fromIndex: 2, offset: -1 },
    ]);
    expect(feature.get('name:0:en')).toBe('A');
    expect(feature.get('name:1:en')).toBe('C');
    expect(feature.get('name:2:en')).toBeUndefined();
  });

  it('shifts all languages of a coordinate together', () => {
    const feature = featureWithNames({ 'name:1:en': 'B', 'name:1:da': 'Bda' });
    applyCoordNameUpdates(feature, [{ type: 'offset', fromIndex: 1, offset: 1 }]);
    expect(feature.get('name:2:en')).toBe('B');
    expect(feature.get('name:2:da')).toBe('Bda');
    expect(feature.get('name:1:en')).toBeUndefined();
  });

  it('leaves names before the change point untouched', () => {
    const feature = featureWithNames({ 'name:0:en': 'keep' });
    applyCoordNameUpdates(feature, [{ type: 'offset', fromIndex: 5, offset: 1 }]);
    expect(feature.get('name:0:en')).toBe('keep');
  });
});
