import { Component, DestroyRef, effect, inject, input, output } from '@angular/core';
import type Feature from 'ol/Feature';
import type OlMapImpl from 'ol/Map';
import type MapBrowserEvent from 'ol/MapBrowserEvent';
import { unByKey } from 'ol/Observable';
import type { EventsKey } from 'ol/events';
import { createEmpty, extend, isEmpty } from 'ol/extent';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';

import { featuresAtPixel, pointerTypeOf } from '@niord/map/hit-detection';
import { MapProjectionService } from '@niord/map/map-projection.service';
import { MapShellService } from '@niord/map/map-shell.service';
import { styleForMessageFeature } from '@niord/map/map-styles';
import type { OlMapView } from '@niord/map/ol-map';
import { Message } from '../../../core/models/message.model';
import { featuresForMessage } from '../../../core/models/message.util';
import { MapService } from '../../../core/services/map.service';

/** The message a feature was drawn for, carried on the feature so a pixel resolves back to it. */
const FEATURE_MESSAGE = 'message';
/** Which of the two symbol sets the portrayal draws the feature with. */
const FEATURE_MAIN_TYPE = 'mainType';

/** The margin the fit leaves around the drawn messages. */
const FIT_PADDING: [number, number, number, number] = [5, 5, 5, 5];

/**
 * Both layers sit above every raster layer: a chart or a WMS overlay registering later must not
 * paint over the features a reader came for.
 */
const FEATURE_LAYER_Z_INDEX = 100;

/**
 * The messages on one of the public site's maps: one layer for warnings, one for notices, and
 * the fit that frames them all. The site's chart, the map in a message's dialog and the teaser
 * all draw with it, so a message looks the same on each.
 *
 * Every mark is the kit's S-124 portrayal, which marks each line and each surface with its tag
 * at every zoom level, so a message is visible however far out the reader is and nothing is
 * added to mark it.
 */
@Component({
  selector: 'app-message-features-layer',
  host: { class: 'contents' },
  template: '',
})
export class MessageFeaturesLayer {
  private readonly shell = inject(MapShellService);
  private readonly maps = inject(MapService);
  private readonly projection = inject(MapProjectionService);
  private readonly destroyRef = inject(DestroyRef);

  readonly messages = input.required<readonly Message[]>();
  readonly fitExtent = input(false);
  readonly maxZoom = input(10);
  /** Where to point the map when nothing drawn has a position — the country's root area. */
  readonly fallbackView = input<OlMapView | undefined>(undefined);
  /**
   * Whether a reader can act on the messages: a click names the message under it. A map that is
   * one link as a whole — the teaser — draws the messages and does not.
   */
  readonly interactive = input(true);

  readonly featureClicked = output<string>();

  private readonly nwSource = new VectorSource({ wrapX: false });
  private readonly nmSource = new VectorSource({ wrapX: false });
  private nwLayer: VectorLayer | undefined;
  private nmLayer: VectorLayer | undefined;

  constructor() {
    // The layers register on the first effect run, which is also when the kit's own layers
    // register, so the map ends up with them in template order.
    effect(() => {
      this.ensureLayers();
      const map = this.shell.map();
      if (map) this.rebuild(map);
    });

    // One click listener, re-armed if the map is ever replaced.
    effect((onCleanup) => {
      const map = this.shell.map();
      if (!map || !this.interactive()) return;
      const key: EventsKey = map.on('click', (event) => this.onClick(event));
      onCleanup(() => unByKey(key));
    });

    this.destroyRef.onDestroy(() => {
      for (const layer of [this.nwLayer, this.nmLayer]) {
        if (layer) {
          this.shell.unregisterLayer(layer);
          layer.dispose();
        }
      }
      this.nwSource.clear();
      this.nwSource.dispose();
      this.nmSource.clear();
      this.nmSource.dispose();
    });
  }

  /**
   * The messages under a pixel, each once, in the order the kit resolves the features: the marks
   * painted there first, then the surfaces the pixel lies inside, smallest first. What the
   * tooltip names and what a click opens.
   */
  messagesAtPixel(pixel: [number, number], pointerType?: string): readonly Message[] {
    const map = this.shell.map();
    if (!map) return [];
    const seen = new Set<string>();
    const messages: Message[] = [];
    for (const feature of featuresAtPixel(map, pixel, [this.nwLayer, this.nmLayer], pointerType)) {
      const message = feature.get(FEATURE_MESSAGE) as Message | undefined;
      if (message && !seen.has(message.id)) {
        seen.add(message.id);
        messages.push(message);
      }
    }
    return messages;
  }

  private ensureLayers(): void {
    if (this.nwLayer) return;
    this.nwLayer = this.buildLayer(this.nwSource);
    this.nmLayer = this.buildLayer(this.nmSource);
    this.shell.registerLayer(this.nwLayer);
    this.shell.registerLayer(this.nmLayer);
  }

  /**
   * Warnings and notices keep a layer each so a pixel resolves back to one of them, but the marks
   * are the kit's: the portrayal reads the main type and the affected-area flag off the feature
   * itself. Nothing on these maps is selected or hovered — the tooltip is what a reader gets — so
   * the context carries neither.
   */
  private buildLayer(source: VectorSource): VectorLayer {
    return new VectorLayer({
      source,
      zIndex: FEATURE_LAYER_Z_INDEX,
      style: (feature, resolution) =>
        styleForMessageFeature(
          feature as Feature,
          { showOutline: false, isSelected: false },
          resolution,
        ),
    });
  }

  private rebuild(map: OlMapImpl): void {
    const messages = this.messages();
    this.nwSource.clear();
    this.nmSource.clear();

    // A message with no position has no feature to draw; the chart lists those beside itself.
    for (const message of messages) {
      for (const gjFeature of featuresForMessage(message)) {
        const olFeature = this.maps.gjToOlFeature(gjFeature);
        olFeature.set(FEATURE_MESSAGE, message);
        olFeature.set(FEATURE_MAIN_TYPE, message.mainType);
        this.sourceFor(message).addFeature(olFeature);
      }
    }

    if (this.fitExtent()) this.fit(map);
  }

  private sourceFor(message: Message): VectorSource {
    return message.mainType === 'NW' ? this.nwSource : this.nmSource;
  }

  private fit(map: OlMapImpl): void {
    const extent = createEmpty();
    for (const source of [this.nwSource, this.nmSource]) {
      // An empty source reports an infinite extent rather than nothing at all.
      const sourceExtent = source.getExtent();
      if (sourceExtent && Number.isFinite(sourceExtent[0])) extend(extent, sourceExtent);
    }

    if (!isEmpty(extent)) {
      map.getView().fit(extent, {
        padding: FIT_PADDING,
        size: map.getSize(),
        maxZoom: this.maxZoom(),
      });
      return;
    }

    const fallback = this.fallbackView();
    if (fallback?.center && fallback.zoom !== undefined) {
      map.getView().setCenter(this.projection.fromLonLat(fallback.center));
      map.getView().setZoom(fallback.zoom);
    }
  }

  private onClick(event: MapBrowserEvent): void {
    const messages = this.messagesAtPixel([event.pixel[0], event.pixel[1]], pointerTypeOf(event));
    if (messages.length > 0) this.featureClicked.emit(messages[0].id);
  }
}
