import type { Feature as OlFeature } from 'ol';

/**
 * Deep-clones the editor's working features for an undo/redo snapshot. The geometry is copied
 * (OL `clone()` deep-copies coordinates) so a later restore is unaffected by in-place edits;
 * the feature id is carried over (`clone()` drops it) so buffer parent references and selection
 * stay valid across a restore; the transient `selected` flag is dropped so a restored feature
 * never renders pre-selected; and any per-feature style is cleared so the snapshot always renders
 * through the layer's style function (never a stale individual style baked in by `clone()`).
 */
export function cloneEditorFeatures(features: readonly OlFeature[]): OlFeature[] {
  return features.map((feature) => {
    const clone = feature.clone();
    const id = feature.getId();
    if (id !== undefined) {
      clone.setId(id);
    }
    clone.unset('selected', true);
    clone.setStyle(undefined);
    return clone;
  });
}
