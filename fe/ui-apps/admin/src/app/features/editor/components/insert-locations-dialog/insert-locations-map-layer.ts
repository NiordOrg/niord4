import { Component, DestroyRef, effect, inject, input, output } from '@angular/core';
import { Feature } from 'ol';
import type { EventsKey } from 'ol/events';
import { unByKey } from 'ol/Observable';
import type { Pixel } from 'ol/pixel';
import VectorLayer from 'ol/layer/Vector';
import VectorSource from 'ol/source/Vector';

import { featuresAtPixel, pointerTypeOf } from '@niord/map/hit-detection';
import { MapShellService } from '@niord/map/map-shell.service';
import type { GeometryFeature } from '../../../../shared/models/geometry.model';
import type { MainType } from '../../../messages/models/message.model';
import { styleForMessageFeature } from '@niord/map/map-styles';
import { MessageGeometryService } from '../../../messages/services/message-geometry.service';

const FIT_PADDING: [number, number, number, number] = [24, 24, 24, 24];
/** The feature's index into the dialog's (buffer-filtered) `features` list — links a map click to its checkbox. */
const IDX = 'idx';

/**
 * The Insert-Locations dialog's interactive feature-picker layer. Renders the invoking part's
 * features on the shared map, highlights the ones currently selected (the `isSelected` halo), and
 * emits the index of a clicked feature so a map click toggles the same selection as its checkbox.
 *
 * The map is an interactive feature-picker: a click toggles selection directly, not merely a
 * read-only preview of the checkbox selection. Registers via {@link MapShellService} like the
 * other map layers.
 */
@Component({
  selector: 'app-insert-locations-map-layer',
  host: { class: 'contents' },
  template: '',
})
export class InsertLocationsMapLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly geometry = inject(MessageGeometryService);

  readonly features = input<readonly GeometryFeature[]>([]);
  readonly selectedIndices = input<ReadonlySet<number>>(new Set());
  /** Colours the portrayal (NW/NM); the selection halo is what matters for picking. */
  readonly mainType = input<MainType | undefined>(undefined);
  /** The index (into `features`) of a clicked feature — the dialog toggles that checkbox. */
  readonly toggled = output<number>();

  private readonly source = new VectorSource({ wrapX: false });
  private layer: VectorLayer | undefined;
  private clickKey: EventsKey | undefined;

  constructor() {
    // Rebuild the vector features (and re-fit) when the part's features change or the map mounts.
    effect(() => {
      this.rebuild();
    });

    // Re-render on selection change — the style function reads the current selection each pass.
    effect(() => {
      this.selectedIndices();
      this.layer?.changed();
    });

    // Attach the map click handler once the map exists.
    effect(() => {
      const map = this.shell.map();
      if (!map || this.clickKey) return;
      this.clickKey = map.on('singleclick', (event) =>
        this.handleClick(event.pixel, pointerTypeOf(event)),
      );
    });

    this.destroyRef.onDestroy(() => {
      if (this.clickKey) unByKey(this.clickKey);
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
      style: (feature, resolution) =>
        styleForMessageFeature(
          feature as Feature,
          { showOutline: false, isSelected: this.selectedIndices().has(feature.get(IDX)) },
          resolution,
        ),
    });
    this.shell.registerLayer(this.layer);
  }

  private rebuild(): void {
    this.ensureLayer();
    this.source.clear();
    const mainType = this.mainType();
    this.features().forEach((gjFeature, index) => {
      const olFeature = this.geometry.gjToOlFeature(gjFeature);
      olFeature.set(IDX, index);
      if (mainType) {
        olFeature.set('mainType', mainType);
      }
      this.source.addFeature(olFeature);
    });
    this.fitView();
  }

  /**
   * The kit's hit detection picks the feature: the mark painted under the pointer, or else the
   * innermost area the pointer is inside — the portrayal leaves a surface unfilled, so a click
   * inside one picks it although nothing is painted there.
   */
  private handleClick(pixel: Pixel, pointerType?: string): void {
    const map = this.shell.map();
    if (!map) return;
    const [hit] = featuresAtPixel(map, pixel, [this.layer], pointerType);
    const index = hit?.get(IDX);
    if (typeof index === 'number') {
      this.toggled.emit(index);
    }
  }

  private fitView(): void {
    const map = this.shell.map();
    if (!map) return;
    const extent = this.source.getExtent();
    if (!extent || extent.some((value) => !Number.isFinite(value))) return;
    map.getView().fit(extent, { padding: FIT_PADDING, maxZoom: 12 });
  }
}
