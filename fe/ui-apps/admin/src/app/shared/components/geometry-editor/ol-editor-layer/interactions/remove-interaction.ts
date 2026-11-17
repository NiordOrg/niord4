import { Feature as OlFeature, type MapBrowserEvent } from 'ol';
import type { FeatureLike } from 'ol/Feature';
import { Pointer as PointerInteraction } from 'ol/interaction';
import type VectorSource from 'ol/source/Vector';

export interface RemoveInteractionOptions {
  readonly source: VectorSource;
  readonly onRemove: (feature: OlFeature) => void;
}

/**
 * Click-to-delete interaction. On pointer-down it removes the top feature under the cursor
 * that belongs to `source` and notifies via `onRemove`.
 *
 * OpenLayers ships no built-in click-to-delete, so this is a bespoke `PointerInteraction`
 * subclass. (Whole-feature dragging, by contrast, uses OL's built-in `Translate`.)
 */
export class RemoveInteraction extends PointerInteraction {
  private readonly source: VectorSource;
  private readonly onRemove: (feature: OlFeature) => void;

  constructor(options: RemoveInteractionOptions) {
    super();
    this.source = options.source;
    this.onRemove = options.onRemove;
  }

  override handleDownEvent(event: MapBrowserEvent): boolean {
    let removed: OlFeature | undefined;
    event.map.forEachFeatureAtPixel(event.pixel, (candidate: FeatureLike) => {
      if (removed || !(candidate instanceof OlFeature) || !this.source.hasFeature(candidate)) {
        return;
      }
      this.source.removeFeature(candidate);
      removed = candidate;
    });
    if (removed) {
      this.onRemove(removed);
    }
    // Never start a drag sequence — deletion happens on down.
    return false;
  }
}
