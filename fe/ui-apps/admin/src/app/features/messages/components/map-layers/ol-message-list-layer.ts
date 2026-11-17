import { Component, DestroyRef, computed, effect, inject, input, output } from '@angular/core';
import { Feature, Map as OlMap } from 'ol';
import { unByKey } from 'ol/Observable';
import type { EventsKey } from 'ol/events';
import type { MapBrowserEvent } from 'ol';
import { createEmpty, extend, isEmpty } from 'ol/extent';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';
import { Point } from 'ol/geom';
import type { Pixel } from 'ol/pixel';

import { featuresAtPixel, pointerTypeOf } from '@niord/map/hit-detection';
import { MapShellService } from '@niord/map/map-shell.service';
import type { Message } from '../../models/message.model';
import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { styleForMessageFeature } from '@niord/map/map-styles';
import { stampCoLocation } from '../../services/co-location';
import { MessageGeometryService } from '../../services/message-geometry.service';

const FEATURE_MESSAGE_ID = 'messageId';
const FEATURE_MAIN_TYPE = 'mainType';
const FEATURE_PARENT_FEATURE_IDS = 'parentFeatureIds';

@Component({
  selector: 'app-ol-message-list-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlMessageListLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly geometry = inject(MessageGeometryService);
  private readonly appConfig = inject(APP_CONFIG);

  readonly messages = input.required<readonly Message[]>();
  readonly selection = input<ReadonlySet<string>>(new Set<string>());
  readonly hovered = input<string | undefined>(undefined);
  readonly showOutline = input<boolean>(false);
  readonly showNoPos = input<boolean>(false);
  readonly excludeAffectedAreas = input<boolean>(false);
  readonly visible = input<boolean>(true);
  readonly name = input<string>('Messages');
  readonly showInLayerSwitcher = input<boolean>(true);

  readonly featureClicked = output<string>();
  readonly noPosMessages = output<readonly Message[]>();

  private readonly source = new VectorSource({ wrapX: false });
  private layer: VectorLayer | undefined;
  private clickKey: EventsKey | undefined;

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

    // Re-render when selection or hover changes so the halo styles update.
    effect(() => {
      this.selection();
      this.hovered();
      this.layer?.changed();
    });

    // Wire the click handler when the map becomes available.
    effect(() => {
      const map = this.shell.map();
      if (!map) return;
      this.unsubscribeClick();
      this.clickKey = map.on('click', (event) => this.handleClick(map, event));
    });

    this.destroyRef.onDestroy(() => {
      this.unsubscribeClick();
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
      // Top-tier overlay — kept above raster layers (WMS in particular) even
      // when those register later in the layer collection.
      zIndex: 100,
      style: (feature, resolution) => {
        const id = (feature.get(FEATURE_MESSAGE_ID) as string) ?? '';
        return styleForMessageFeature(
          feature as Feature,
          {
            showOutline: this.showOutline(),
            isSelected: this.selection().has(id),
            isHovered: this.hovered() === id,
            highlightEnabled: this.appConfig.map?.highlightEnabled,
          },
          resolution,
        );
      },
    });
    this.shell.registerLayer(this.layer);
  }

  private rebuildFeatures(): void {
    const messages = this.messages();
    const opts = { excludeAffectedAreas: this.excludeAffectedAreas() };
    const showOutline = this.showOutline();
    this.source.clear();
    const noPos: Message[] = [];

    for (const message of messages) {
      const features = this.geometry.getFeatures(message, opts);
      if (features.length === 0) {
        noPos.push(message);
        continue;
      }
      for (const gjFeature of features) {
        const olFeature = this.geometry.gjToOlFeature(gjFeature);
        olFeature.set(FEATURE_MESSAGE_ID, message.id);
        if (message.mainType) {
          olFeature.set(FEATURE_MAIN_TYPE, message.mainType);
        }
        const props = gjFeature.properties ?? {};
        if (props['parentFeatureIds'] !== undefined) {
          olFeature.set(FEATURE_PARENT_FEATURE_IDS, props['parentFeatureIds']);
        }
        if (showOutline) {
          const geom = olFeature.getGeometry();
          if (geom) {
            const center = this.geometry.getGeometryCenter(geom);
            olFeature.setGeometry(new Point(center));
          }
        }
        this.source.addFeature(olFeature);
      }
    }

    stampCoLocation(this.source.getFeatures());

    if (this.showNoPos()) {
      this.noPosMessages.emit(noPos);
    }
  }

  private handleClick(map: OlMap, event: MapBrowserEvent): void {
    const [resolved] = this.messageIdsAt(map, event.pixel, pointerTypeOf(event));
    if (resolved) {
      this.featureClicked.emit(resolved);
    }
  }

  /**
   * Animates the map view to fit the bounding extent of the given message's
   * features. No-op if the message has no geometry (so the result-list zoom
   * button can be shown unconditionally without needing a per-row enabled check).
   */
  fitToMessage(messageId: string): void {
    const map = this.shell.map();
    if (!map) return;
    const extent = createEmpty();
    for (const feature of this.source.getFeatures()) {
      if (feature.get(FEATURE_MESSAGE_ID) !== messageId) continue;
      const geom = feature.getGeometry();
      if (geom) extend(extent, geom.getExtent());
    }
    if (isEmpty(extent)) return;
    map.getView().fit(extent, {
      padding: [40, 40, 40, 40],
      maxZoom: 14,
      duration: 300,
    });
  }

  /**
   * Resolves the messages under a pixel, filtered to this layer's features.
   * Used by tooltip wiring — supersedes the previous pattern of copying a
   * `resolveMessagesAtPixel` helper into every map-hosting component.
   */
  messagesAtPixel(pixel: [number, number]): readonly Message[] {
    const ids = this.messageIdsAtPixel(pixel);
    if (ids.length === 0) return [];
    const byId = this.messagesByIdSignal();
    const out: Message[] = [];
    for (const id of ids) {
      const msg = byId.get(id);
      if (msg) out.push(msg);
    }
    return out;
  }

  /**
   * Built once per messages() change — avoids the O(n) Map rebuild on every
   * pointermove that the previous callsites (one per map view) ran inline.
   */
  private readonly messagesByIdSignal = computed<Map<string, Message>>(() => {
    return new Map(this.messages().map((m) => [m.id, m]));
  });

  /**
   * Returns the message IDs of features under the given pixel, filtered to
   * this layer's source, each once. Used by the parent to resolve tooltip
   * content without reaching into this component's private state.
   */
  messageIdsAtPixel(pixel: [number, number]): readonly string[] {
    const map = this.shell.map();
    return map ? this.messageIdsAt(map, pixel) : [];
  }

  /**
   * The kit's hit detection: the marks painted under the pointer first, then the unfilled
   * surfaces it lies inside, smallest first — so a click inside an area selects that area's
   * message although the S-124 portrayal paints nothing there.
   */
  private messageIdsAt(map: OlMap, pixel: Pixel, pointerType?: string): string[] {
    const seen = new Set<string>();
    for (const feature of featuresAtPixel(map, pixel, [this.layer], pointerType)) {
      const id = feature.get(FEATURE_MESSAGE_ID) as string | undefined;
      if (id) seen.add(id);
    }
    return Array.from(seen);
  }

  private unsubscribeClick(): void {
    if (!this.clickKey) return;
    unByKey(this.clickKey);
    this.clickKey = undefined;
  }
}
