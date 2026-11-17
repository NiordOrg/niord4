import { Feature, type MapBrowserEvent } from 'ol';
import { Point } from 'ol/geom';
import type { FeatureLike } from 'ol/Feature';
import VectorSource from 'ol/source/Vector';
import { describe, expect, it, vi } from 'vitest';

import { RemoveInteraction } from './remove-interaction';

/** A minimal MapBrowserEvent whose map yields `hits` from forEachFeatureAtPixel. */
function downEvent(hits: readonly FeatureLike[]): MapBrowserEvent {
  return {
    pixel: [0, 0],
    map: {
      forEachFeatureAtPixel: (_pixel: unknown, callback: (f: FeatureLike) => unknown) => {
        for (const hit of hits) {
          const stop = callback(hit);
          if (stop) return stop;
        }
        return undefined;
      },
    },
  } as unknown as MapBrowserEvent;
}

describe('RemoveInteraction', () => {
  it('removes the hit feature belonging to the source and notifies onRemove', () => {
    const feature = new Feature(new Point([0, 0]));
    feature.setId('f1');
    const source = new VectorSource();
    source.addFeature(feature);
    const onRemove = vi.fn();
    const interaction = new RemoveInteraction({ source, onRemove });

    const startsDrag = interaction.handleDownEvent(downEvent([feature]));

    expect(startsDrag).toBe(false);
    expect(source.hasFeature(feature)).toBe(false);
    expect(onRemove).toHaveBeenCalledWith(feature);
  });

  it('ignores a feature that does not belong to the source', () => {
    const stray = new Feature(new Point([0, 0]));
    const source = new VectorSource();
    const onRemove = vi.fn();
    const interaction = new RemoveInteraction({ source, onRemove });

    interaction.handleDownEvent(downEvent([stray]));

    expect(onRemove).not.toHaveBeenCalled();
  });

  it('removes only the first (topmost) matching feature', () => {
    const top = new Feature(new Point([0, 0]));
    top.setId('top');
    const below = new Feature(new Point([0, 0]));
    below.setId('below');
    const source = new VectorSource();
    source.addFeatures([top, below]);
    const onRemove = vi.fn();
    const interaction = new RemoveInteraction({ source, onRemove });

    interaction.handleDownEvent(downEvent([top, below]));

    expect(source.hasFeature(top)).toBe(false);
    expect(source.hasFeature(below)).toBe(true);
    expect(onRemove).toHaveBeenCalledTimes(1);
  });
});
