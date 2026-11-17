import { Component, computed, inject, input, output, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { EditAsTextDialog } from '../../../../shared/components/geometry-editor/edit-as-text-dialog/edit-as-text-dialog';
import { GeometryEditor } from '../../../../shared/components/geometry-editor/geometry-editor';
import { OlGeometryPreviewLayer } from '../../../../shared/components/geometry-editor/ol-geometry-preview-layer/ol-geometry-preview-layer';
import { BaseMapLayers } from '../../../../shared/components/ol-map/base-map-layers';
import { computeInitialMapView } from '../../../../shared/components/ol-map/map-view-defaults';
import { MapShellService } from '@niord/map/map-shell.service';
import { OlLayerSwitcher } from '@niord/map/ol-layer-switcher';
import { type OlMapView, OlMap } from '@niord/map/ol-map';
import { Icon } from '../../../../shared/icons/icon';
import type { GeometryFeatureCollection } from '../../../../shared/models/geometry.model';
import { formatLatLon } from '../../../../shared/utils/coordinate-format';
import { geometryCollectionsEqual } from '../../../../shared/utils/geometry-equals';
import {
  MessageGeometryService,
  type MessageGeoJsonFeature,
} from '../../../messages/services/message-geometry.service';

/** One serialized coordinate in the read-only viewer: its running index, formatted position, optional name. */
interface CoordinateRow {
  readonly index: number;
  readonly text: string;
  readonly name?: string;
}

/** One feature's coordinates for the viewer, with the optional feature-name header. */
interface CoordinateGroup {
  readonly name?: string;
  readonly rows: readonly CoordinateRow[];
}

/**
 * A self-contained geometry field: a read-only mini-map preview + the mono coordinate table, with
 * an "Edit" (the shared {@link GeometryEditor} in a near-full-window modal, lazily loaded) and an
 * "Edit as text" ({@link EditAsTextDialog}) affordance — the SAME positions surface a message-part
 * card presents in the normal editor. Value-in / value-out: the host binds `[geometry]` and commits
 * `(geometryChange)` however it likes (into a facade part, or a local working copy), so the map
 * looks and behaves identically wherever it is used while the OUTPUT stays the host's to place.
 *
 * Owns its own {@link MapShellService} so the preview map is isolated from any ancestor shell and
 * from sibling instances (the geometry editor + edit-as-text dialog each shadow it again for their
 * own modal maps).
 */
@Component({
  selector: 'app-geometry-preview-field',
  imports: [
    TranslocoPipe,
    Icon,
    OlMap,
    BaseMapLayers,
    OlLayerSwitcher,
    OlGeometryPreviewLayer,
    Dialog,
    GeometryEditor,
    EditAsTextDialog,
  ],
  providers: [MapShellService],
  templateUrl: './geometry-preview-field.html',
  styleUrl: './geometry-preview-field.css',
  host: { class: 'block' },
})
export class GeometryPreviewField {
  private readonly language = inject(LanguageService);
  private readonly geometry = inject(MessageGeometryService);
  private readonly domain = inject(DomainFacade);
  private readonly config = inject(APP_CONFIG);

  /** The feature collection to preview and edit. */
  readonly featureCollection = input<GeometryFeatureCollection | undefined>(undefined);
  /** Gates the Edit / Edit-as-text affordances (a read-only host shows the preview only). */
  readonly editable = input<boolean>(true);
  /** Tailwind height class for the preview map box (default matches the part card). */
  readonly heightClass = input<string>('h-40');

  /** The edited geometry, emitted on commit (OK) from either surface. */
  readonly geometryChange = output<GeometryFeatureCollection>();

  /** Fallback map view before the preview layer fits to the geometry. */
  protected readonly initialView = computed<OlMapView | undefined>(() =>
    computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );

  protected readonly modalOpen = signal(false);
  protected readonly asTextOpen = signal(false);

  /**
   * The geometry serialized to per-feature coordinate lists for the read-only viewer, each in the
   * active language and formatted `DD° MM.mmm'N - DDD° MM.mmm'E`. Rows number from a feature's
   * MESSAGE-WIDE `startCoordIndex` when stamped, else a local running counter.
   */
  protected readonly coordinateGroups = computed<readonly CoordinateGroup[]>(() => {
    const lang = this.language.selectedLanguage();
    const features = this.featureCollection()?.features ?? [];
    const groups: CoordinateGroup[] = [];
    let running = 0;
    for (const feature of features as MessageGeoJsonFeature[]) {
      const coords = this.geometry.serializeReadableCoordinates(feature, lang);
      if (coords.length === 0) {
        continue;
      }
      const stamped = Number(
        (feature.properties as Record<string, unknown> | undefined)?.['startCoordIndex'],
      );
      const start = Number.isFinite(stamped) && stamped > 0 ? stamped : running + 1;
      const rows = coords.map((coord, i) => ({
        index: start + i,
        text: formatLatLon(coord.lonLat[1], coord.lonLat[0], { decimals: 3, locale: lang }),
        name: coord.name,
      }));
      running = start + coords.length - 1;
      groups.push({ name: this.featureName(feature, lang), rows });
    }
    return groups;
  });

  protected readonly coordinateLineCount = computed<number>(() =>
    this.coordinateGroups().reduce((sum, group) => sum + group.rows.length, 0),
  );

  private featureName(feature: MessageGeoJsonFeature, lang: string): string | undefined {
    const props = (feature.properties ?? {}) as Record<string, unknown>;
    const byLang = props[`name:${lang}`];
    if (typeof byLang === 'string' && byLang.length > 0) {
      return byLang;
    }
    const plain = props['name'];
    return typeof plain === 'string' && plain.length > 0 ? plain : undefined;
  }

  protected openEditor(): void {
    this.modalOpen.set(true);
  }

  protected openAsText(): void {
    this.asTextOpen.set(true);
  }

  /** OK from either geometry surface — emit the new geometry and close the modal. */
  protected onCommit(geometry: GeometryFeatureCollection): void {
    // Belt-and-suspenders with each surface's own no-change guard: don't
    // propagate a change the host would flag as dirty when the committed
    // geometry matches the current one. The editor round-trips coordinates
    // through EPSG:3857, so compare with a positional tolerance, not by value.
    if (!geometryCollectionsEqual(geometry, this.featureCollection())) {
      this.geometryChange.emit(geometry);
    }
    this.modalOpen.set(false);
  }

  /** Escape/Cancel from the geometry editor — close without emitting. */
  protected onCancel(): void {
    this.modalOpen.set(false);
  }
}
