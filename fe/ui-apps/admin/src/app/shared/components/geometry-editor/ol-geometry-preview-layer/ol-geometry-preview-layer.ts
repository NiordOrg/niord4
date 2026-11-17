import { Component, DestroyRef, effect, inject, input } from '@angular/core';
import type { Feature as OlFeature } from 'ol';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';

import { MapShellService } from '@niord/map/map-shell.service';
import type { GeometryFeatureCollection } from '../../../models/geometry.model';
import { GeometryConversionService } from '../../../services/geometry-conversion.service';
// Deliberately the KEYS module, not `buffer-feature`: this layer only needs to recognise a
// buffer feature, and importing the full module would pull @turf/buffer → JSTS (~301 kB raw)
// into every route that renders geometry without editing it.
import { isBufferFeature } from '../util/buffer-feature-keys';
import { editorBufferStyle, editorFeatureStyle } from '../util/editor-map-styles';

/**
 * A read-only vector layer that renders a GeoJSON FeatureCollection on a shell map — used by
 * the "Edit as text" dialog's live preview. It re-renders +
 * re-fits whenever the input collection changes (e.g. on each re-parse as the user types). No
 * interactions: this is purely a viewer, distinct from the editable `OlEditorLayer`.
 */
@Component({
  selector: 'app-ol-geometry-preview-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlGeometryPreviewLayer {
  private readonly shell = inject(MapShellService);
  private readonly geo = inject(GeometryConversionService);
  private readonly destroyRef = inject(DestroyRef);

  readonly featureCollection = input<GeometryFeatureCollection | undefined>(undefined);
  readonly fitExtent = input<boolean>(true);
  readonly maxZoom = input<number>(14);

  private readonly source = new VectorSource({ wrapX: false });
  private layer: VectorLayer | undefined;

  constructor() {
    effect(() => {
      const map = this.shell.map();
      const fc = this.featureCollection();
      this.ensureLayer();
      this.source.clear();
      const features = (fc?.features ?? []).map((feature) => this.geo.gjToOlFeature(feature));
      if (features.length > 0) this.source.addFeatures(features);
      if (map && this.fitExtent() && features.length > 0) {
        const extent = this.source.getExtent();
        if (extent && extent.every((value) => Number.isFinite(value))) {
          map.getView().fit(extent, { padding: [20, 20, 20, 20], maxZoom: this.maxZoom() });
        }
      }
    });

    this.destroyRef.onDestroy(() => {
      if (this.layer) {
        this.shell.unregisterLayer(this.layer);
        this.layer.dispose();
      }
      this.source.clear();
      this.source.dispose();
    });
  }

  private ensureLayer(): void {
    if (this.layer) return;
    this.layer = new VectorLayer({
      source: this.source,
      zIndex: 60,
      style: (feature) =>
        isBufferFeature(feature as OlFeature) ? editorBufferStyle() : editorFeatureStyle(feature),
    });
    this.shell.registerLayer(this.layer);
  }
}
