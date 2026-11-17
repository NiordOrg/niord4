import { Component, DestroyRef, effect, inject, input } from '@angular/core';
import { Feature } from 'ol';
import { Point } from 'ol/geom';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import { Style } from 'ol/style';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { MapShellService } from '@niord/map/map-shell.service';
import { coordinateIndexStyle, coordinateNameStyle } from '@niord/map/map-styles';
import type { Message } from '../../models/message.model';
import type { MessageGeoJsonFeature } from '../../services/message-geometry.service';
import { MessageGeometryService } from '../../services/message-geometry.service';

@Component({
  selector: 'app-ol-message-labels-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlMessageLabelsLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly geometry = inject(MessageGeometryService);
  private readonly language = inject(LanguageService);

  readonly message = input<Message | undefined>(undefined);
  readonly featureCollection = input<
    { readonly features: readonly MessageGeoJsonFeature[] } | undefined
  >(undefined);
  readonly visible = input<boolean>(true);
  readonly name = input<string>('MessageLabels');
  readonly showInLayerSwitcher = input<boolean>(false);

  private readonly source = new VectorSource({ wrapX: false });
  private layer: VectorLayer | undefined;

  constructor() {
    effect(() => {
      this.ensureLayer();
      if (!this.layer) return;
      this.layer.setVisible(this.visible());
      this.layer.set('name', this.name());
      this.layer.set('showInLayerSwitcher', this.showInLayerSwitcher());
    });

    effect(() => {
      this.rebuildLabels();
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
      zIndex: 100,
      style: (feature) => (feature.get('labelStyle') as Style | Style[]) ?? new Style({}),
    });
    this.shell.registerLayer(this.layer);
  }

  private rebuildLabels(): void {
    this.source.clear();
    const features = this.collectFeatures();
    // Track a running 1-based index across all features so labels read
    // 1, 2, 3 ... regardless of how many sub-features the geometry has —
    // the index is shared across the whole feature loop, not reset per feature.
    let displayIndex = 1;
    const lang = this.language.selectedLanguage();
    for (const gjFeature of features) {
      const coords = this.geometry.serializeReadableCoordinates(gjFeature, lang);
      for (const coord of coords) {
        const olGeom = new Point(this.toOlCoordinate(coord.lonLat));
        const feature = new Feature(olGeom);
        const styles: Style[] = [coordinateIndexStyle(displayIndex)];
        if (coord.name) {
          styles.push(coordinateNameStyle(coord.name));
        }
        feature.set('labelStyle', styles);
        this.source.addFeature(feature);
        displayIndex++;
      }
    }
  }

  private collectFeatures(): readonly MessageGeoJsonFeature[] {
    const explicit = this.featureCollection();
    if (explicit?.features?.length) {
      return explicit.features;
    }
    const message = this.message();
    if (!message) return [];
    return this.geometry.getFeatures(message);
  }

  private toOlCoordinate(lonLat: readonly [number, number]): [number, number] {
    // Use the shared MapProjectionService via MessageGeometryService — read geometry
    // returns a projected ol.geom. For per-coord conversion we hand-roll via the
    // service to keep the import surface narrow.
    const olFeature = this.geometry.gjToOlFeature({
      type: 'Feature',
      properties: {},
      geometry: { type: 'Point', coordinates: [lonLat[0], lonLat[1]] },
    });
    const point = olFeature.getGeometry() as Point;
    const [x, y] = point.getCoordinates();
    return [x, y];
  }
}
