import { Component, DestroyRef, effect, inject, input } from '@angular/core';
import Feature from 'ol/Feature';
import Point from 'ol/geom/Point';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import type Style from 'ol/style/Style';

import { MapProjectionService } from '@niord/map/map-projection.service';
import { MapShellService } from '@niord/map/map-shell.service';
import { coordinateIndexStyle, coordinateNameStyle } from '@niord/map/map-styles';
import { Message } from '../../../../core/models/message.model';
import { LanguageService } from '../../../../core/services/language.service';
import { MapService } from '../../../../core/services/map.service';
import { featuresForMessage } from '../../../../core/models/message.util';

/** Above the raster layers, beside the features the labels name. */
const LABEL_LAYER_Z_INDEX = 100;

/**
 * A geometry's name stands on its own at the middle of the geometry, with no numbered circle
 * under it, so it sits closer to the position than a coordinate's name does.
 */
const GEOMETRY_NAME_OFFSET_Y = 5;

/**
 * The names and the numbered coordinates of one message, for the map that shows that message
 * alone. The numbering runs across the whole message, so the reader can follow the positions
 * in the text against the chart.
 */
@Component({
  selector: 'app-message-labels-layer',
  host: { class: 'contents' },
  template: '',
})
export class MessageLabelsLayer {
  private readonly shell = inject(MapShellService);
  private readonly maps = inject(MapService);
  private readonly projection = inject(MapProjectionService);
  private readonly language = inject(LanguageService).language;
  private readonly destroyRef = inject(DestroyRef);

  readonly message = input<Message | undefined>(undefined);
  readonly visible = input(true);
  readonly name = input('Labels');
  readonly showInLayerSwitcher = input(true);

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

    effect(() => this.rebuild());

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
    this.layer = new VectorLayer({ source: this.source, zIndex: LABEL_LAYER_Z_INDEX });
    this.shell.registerLayer(this.layer);
  }

  private rebuild(): void {
    this.source.clear();
    const message = this.message();
    const features = featuresForMessage(message);
    if (features.length === 0) return;

    const lang = this.language();
    let coordIndex = 1;

    for (const gjFeature of features) {
      const olFeature = this.maps.gjToOlFeature(gjFeature);
      const styles: Style[] = [];
      const props = gjFeature.properties;
      const name = props ? (props[`name:${lang}`] as string | undefined) : undefined;

      if (name) styles.push(this.geometryNameStyle(name, olFeature));

      const coords: { lon: number; lat: number; index: number; name?: string }[] = [];
      this.maps.serializeReadableCoordinates(gjFeature, coords);

      for (const coord of coords) {
        const point = this.projection.fromLonLat([coord.lon, coord.lat]);
        styles.push(this.indexStyleAt(coordIndex, point));
        if (coord.name) styles.push(this.nameStyleAt(coord.name, point));
        coordIndex++;
      }

      if (styles.length > 0) {
        olFeature.setStyle(styles);
        this.source.addFeature(olFeature);
      }
    }
  }

  /** The name a geometry carries, drawn at the middle of that geometry. */
  private geometryNameStyle(name: string, olFeature: Feature): Style {
    const style = coordinateNameStyle(name, GEOMETRY_NAME_OFFSET_Y);
    style.setGeometry(() => {
      const geometry = olFeature.getGeometry();
      const center = geometry ? this.maps.getGeometryCenter(geometry) : undefined;
      return center ? new Point(center) : undefined;
    });
    return style;
  }

  /** The numbered circle on one coordinate. */
  private indexStyleAt(index: number, point: number[]): Style {
    const style = coordinateIndexStyle(index);
    style.setGeometry(new Point(point));
    return style;
  }

  /** The name that coordinate carries, under its circle. */
  private nameStyleAt(name: string, point: number[]): Style {
    const style = coordinateNameStyle(name);
    style.setGeometry(new Point(point));
    return style;
  }
}
