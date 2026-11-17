import { Component, DestroyRef, effect, inject, input } from '@angular/core';
import { Feature } from 'ol';
import { Point } from 'ol/geom';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';

import { MapShellService } from '@niord/map/map-shell.service';
import type { Message } from '../../models/message.model';
import { styleForMessageFeature } from '@niord/map/map-styles';
import { stampCoLocation } from '../../services/co-location';
import type { MessageGeoJsonFeature } from '../../services/message-geometry.service';
import { MessageGeometryService } from '../../services/message-geometry.service';

const FIT_PADDING: [number, number, number, number] = [20, 20, 20, 20];

@Component({
  selector: 'app-ol-message-details-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlMessageDetailsLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly geometry = inject(MessageGeometryService);

  readonly message = input<Message | undefined>(undefined);
  readonly featureCollection = input<
    { readonly features: readonly MessageGeoJsonFeature[] } | undefined
  >(undefined);
  readonly showOutline = input<boolean>(false);
  readonly fitExtent = input<boolean>(true);
  readonly maxZoom = input<number>(12);
  readonly visible = input<boolean>(true);
  readonly name = input<string>('MessageDetails');
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
      this.rebuildFeatures();
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
      zIndex: 50,
      style: (feature, resolution) =>
        styleForMessageFeature(
          feature as Feature,
          { showOutline: this.showOutline(), isSelected: false },
          resolution,
        ),
    });
    this.shell.registerLayer(this.layer);
  }

  private rebuildFeatures(): void {
    this.source.clear();
    const features = this.collectFeatures();
    if (features.length === 0) return;
    const showOutline = this.showOutline();
    const mainType = this.message()?.mainType;
    for (const gjFeature of features) {
      const olFeature = this.geometry.gjToOlFeature(gjFeature);
      if (mainType) {
        olFeature.set('mainType', mainType);
      }
      const props = gjFeature.properties ?? {};
      if (props['parentFeatureIds'] !== undefined) {
        olFeature.set('parentFeatureIds', props['parentFeatureIds']);
      }
      if (showOutline) {
        const geom = olFeature.getGeometry();
        if (geom) {
          olFeature.setGeometry(new Point(this.geometry.getGeometryCenter(geom)));
        }
      }
      this.source.addFeature(olFeature);
    }
    stampCoLocation(this.source.getFeatures());
    if (this.fitExtent()) {
      this.fitView();
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

  private fitView(): void {
    const map = this.shell.map();
    if (!map) return;
    const extent = this.source.getExtent();
    if (!extent || extent.some((v) => !Number.isFinite(v))) return;
    map.getView().fit(extent, { padding: FIT_PADDING, maxZoom: this.maxZoom() });
  }
}
