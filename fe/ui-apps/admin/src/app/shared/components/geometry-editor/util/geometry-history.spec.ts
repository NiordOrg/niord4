import { Feature } from 'ol';
import { Point } from 'ol/geom';
import { Style } from 'ol/style';
import { describe, expect, it } from 'vitest';

import { cloneEditorFeatures } from './geometry-history';

describe('cloneEditorFeatures', () => {
  it('preserves the feature id', () => {
    const feature = new Feature(new Point([1, 2]));
    feature.setId('abc');

    const [clone] = cloneEditorFeatures([feature]);

    expect(clone.getId()).toBe('abc');
  });

  it('drops the transient selected flag', () => {
    const feature = new Feature(new Point([1, 2]));
    feature.set('selected', true);

    const [clone] = cloneEditorFeatures([feature]);

    expect(clone.get('selected')).toBeUndefined();
  });

  it('clears any per-feature style so the layer style renders the clone (regression: stuck-selected)', () => {
    const feature = new Feature(new Point([1, 2]));
    // OL's Select interaction stamps an individual style onto a selected feature, and
    // Feature.clone() copies it — which made restored snapshots render permanently selected.
    feature.setStyle(new Style({}));

    const [clone] = cloneEditorFeatures([feature]);

    expect(clone.getStyle()).toBeFalsy();
  });

  it('deep-clones the geometry so later edits to the original do not affect the snapshot', () => {
    const point = new Point([1, 2]);
    const feature = new Feature(point);

    const [clone] = cloneEditorFeatures([feature]);
    point.setCoordinates([99, 99]); // mutate the original after snapshotting

    expect((clone.getGeometry() as Point).getCoordinates()).toEqual([1, 2]);
  });

  it('carries per-feature names and buffer parentage through (full property copy)', () => {
    const feature = new Feature(new Point([1, 2]));
    feature.set('name:en', 'Buoy');
    feature.set('parentFeatureIds', 'p1,p2');

    const [clone] = cloneEditorFeatures([feature]);

    expect(clone.get('name:en')).toBe('Buoy');
    expect(clone.get('parentFeatureIds')).toBe('p1,p2');
  });
});
