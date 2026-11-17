import { Component, DestroyRef, effect, inject, input, output } from '@angular/core';
import { Feature as OlFeature, type Map as OlMap } from 'ol';
import Collection from 'ol/Collection';
import type { EventsKey } from 'ol/events';
import { platformModifierKeyOnly, shiftKeyOnly, singleClick } from 'ol/events/condition';
import type { Extent } from 'ol/extent';
import { GeoJSON, KML } from 'ol/format';
import {
  DragAndDrop,
  DragBox,
  Draw,
  type Interaction,
  Modify,
  Select,
  Translate,
} from 'ol/interaction';
import { createBox } from 'ol/interaction/Draw';
import type { Options as DragAndDropOptions } from 'ol/interaction/DragAndDrop';
import type { FeatureLike } from 'ol/Feature';
import VectorLayer from 'ol/layer/Vector';
import { unByKey } from 'ol/Observable';
import VectorSource from 'ol/source/Vector';
import type Style from 'ol/style/Style';

import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { MapShellService } from '@niord/map/map-shell.service';
import { GeometryConversionService } from '../../../services/geometry-conversion.service';
import { readCoordNames } from '../geometry-tree/coord-names';
import { geometryToTree, positionCoordinatesByIndex } from '../geometry-tree/geometry-tree-model';
import {
  editorBufferStyle,
  editorFeatureStyle,
  editorNameLabelStyle,
} from '../util/editor-map-styles';
import { isBufferFeature } from '../util/buffer-feature';
import { hasPositionOffTheMap, shiftOntoGlobe } from '../util/longitude-turns';
import { applyImportedFeatureNames } from './import-feature-names';
import { fixMarineRegionsImport } from './import-marineregions';
import { RemoveInteraction } from './interactions/remove-interaction';
import { WktImportFormat } from './wkt-import-format';

const FIT_PADDING: [number, number, number, number] = [30, 30, 30, 30];
/**
 * Animation duration (ms) for a manual fit ("Zoom to extent"), matching the shared map's
 * wheel-zoom easing (`MouseWheelZoom` duration in `OlMap`) so the glide feels consistent. The
 * auto-fit on import passes 0 (instant) — there is nothing to ease from when geometry first lands.
 */
const FIT_DURATION_MS = 250;

/** The active drawing/editing mode. `measure` is currently inert here. */
export type EditorDrawMode =
  | 'point'
  | 'polyline'
  | 'polygon'
  | 'box'
  | 'select'
  | 'remove'
  | 'measure'
  | 'none';

/**
 * The editable geometry vector layer + the OpenLayers interaction suite. It owns the live
 * editing `VectorSource`:
 *
 * - `[features]` is the **seed** — re-applied only when the host re-imports (by
 *   reference); edits never feed back into it, so there is no draw→emit→reseed loop
 *   (the single most important design point).
 * - `[drawMode]` toggles which interaction bundle is active.
 * - `(featuresChanged)` emits the current source features after any add/modify/translate/
 *   delete/drop; `(selectionChanged)` mirrors the OL Select collection (the geometry-tree hook).
 *
 * Registers via `MapShellService` (same content-projection pattern as the read-only
 * map layers); interactions are added straight to the shell map.
 */
@Component({
  selector: 'app-ol-editor-layer',
  host: { class: 'contents' },
  template: '',
})
export class OlEditorLayer {
  private readonly shell = inject(MapShellService);
  private readonly destroyRef = inject(DestroyRef);
  private readonly geo = inject(GeometryConversionService);
  private readonly language = inject(LanguageService);

  /** The seed feature set — applied to the source only when this reference changes. */
  readonly features = input<readonly OlFeature[]>([]);
  readonly drawMode = input<EditorDrawMode>('select');
  /** Fit the view to the features the first time they appear (not on every edit). */
  readonly fitExtent = input<boolean>(true);
  readonly maxZoom = input<number>(12);

  readonly featuresChanged = output<readonly OlFeature[]>();
  readonly selectionChanged = output<readonly string[]>();

  private readonly source = new VectorSource({ wrapX: false });
  private layer: VectorLayer | undefined;

  // Interaction handles, built once when the map first appears.
  private select: Select | undefined;
  /** The modifiable subset of the selection — excludes locked affected-area buffers. */
  private readonly modifiableFeatures = new Collection<OlFeature>();
  /** drawMode → the interactions activated for it. */
  private toggleControls: Record<string, Interaction[]> = {};
  /** Every interaction added to the map, for teardown. */
  private readonly ownedInteractions: Interaction[] = [];
  /** Every OL event listener registered in `ensureInteractions`, unbound on destroy. */
  private readonly listenerKeys: EventsKey[] = [];
  private interactionsBuilt = false;

  // Re-entrancy / idempotency guards.
  private seeding = false;
  /** While true, a batched `addFeatures` suppresses the per-feature emit (emits once at the end). */
  private batching = false;
  private lastSeeded: readonly OlFeature[] | undefined;
  private lastMode: EditorDrawMode | undefined;
  private pendingFit = false;

  // The languages an imported `name` is copied into, and the language whose name
  // label is currently drawn on the map (kept in sync via an effect below).
  private readonly modelLanguages = this.language.modelLanguages.map((lang) => lang.value);
  private activeLang = this.language.selectedLanguage();

  constructor() {
    effect(() => {
      const map = this.shell.map();
      this.ensureLayer();
      if (map) {
        this.ensureInteractions(map);
      }
      this.syncFeatures(this.features());
      this.syncDrawMode(this.drawMode());
    });

    // Redraw name labels in the active language when it changes.
    effect(() => {
      this.activeLang = this.language.selectedLanguage();
      this.layer?.changed();
    });

    this.destroyRef.onDestroy(() => {
      const map = this.shell.map();
      for (const key of this.listenerKeys) {
        unByKey(key);
      }
      this.listenerKeys.length = 0;
      for (const interaction of this.ownedInteractions) {
        map?.removeInteraction(interaction);
        interaction.dispose();
      }
      this.ownedInteractions.length = 0;
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
      style: (feature) => this.styleFor(feature),
    });
    this.shell.registerLayer(this.layer);
  }

  /**
   * Magenta editing style, plus the feature's name label (active language) at its centre,
   * plus any per-coordinate names (`name:<idx>:<lang>`) drawn at their vertices.
   */
  private styleFor(feature: FeatureLike): Style | Style[] {
    const base = isBufferFeature(feature as OlFeature)
      ? editorBufferStyle()
      : editorFeatureStyle(feature);
    const geometry = (feature as OlFeature).getGeometry();
    if (!geometry) return base;

    const styles: Style[] = [base];
    const name = feature.get(`name:${this.activeLang}`);
    if (typeof name === 'string' && name.length > 0) {
      styles.push(editorNameLabelStyle(name, this.geo.getGeometryCenter(geometry)));
    }
    this.appendCoordNameStyles(feature as OlFeature, styles);
    return styles.length === 1 ? base : styles;
  }

  /** Appends a label at each named vertex (`name:<idx>:<active lang>`), if any. */
  private appendCoordNameStyles(feature: OlFeature, styles: Style[]): void {
    const names = readCoordNames(feature).filter((name) => name.lang === this.activeLang);
    if (names.length === 0) return;
    const geometry = feature.getGeometry();
    if (!geometry) return;

    const coordByIndex = positionCoordinatesByIndex(geometryToTree(geometry));
    for (const name of names) {
      const coordinate = coordByIndex.get(name.coordIndex);
      if (coordinate && typeof name.value === 'string' && name.value.length > 0) {
        styles.push(editorNameLabelStyle(name.value, coordinate));
      }
    }
  }

  private ensureInteractions(map: OlMap): void {
    if (this.interactionsBuilt || !this.layer) return;
    this.interactionsBuilt = true;
    const layer = this.layer;

    const drawPoint = new Draw({ source: this.source, type: 'Point' });
    const drawLine = new Draw({ source: this.source, type: 'LineString' });
    const drawPolygon = new Draw({ source: this.source, type: 'Polygon' });
    // A box is drawn with OL's `createBox()` (a 2-click rectangle on a Circle-type draw).
    const drawBox = new Draw({
      source: this.source,
      type: 'Circle',
      geometryFunction: createBox(),
    });

    // `style: null` stops OL's Select from stamping its default edit style onto each selected
    // feature (`feature.setStyle(...)`). That override hid the layer's name label while selected
    // and — because `Feature.clone()` copies the individual style — baked the edit style into
    // undo/redo snapshots, so restored features rendered permanently "selected". With it null,
    // the layer's own style function is the sole renderer and reacts to the `selected` property.
    const select = new Select({ layers: [layer], style: null });
    this.select = select;
    this.listenerKeys.push(
      select.getFeatures().on('add', (event) => {
        event.element.set('selected', true);
        // Affected-area buffers are derived/locked — keep them OUT of the Modify collection so their
        // vertices can't be edited on the map.
        if (!isBufferFeature(event.element)) this.modifiableFeatures.push(event.element);
        this.emitSelection();
      }),
      select.getFeatures().on('remove', (event) => {
        event.element.set('selected', false);
        this.modifiableFeatures.remove(event.element);
        this.emitSelection();
      }),
    );

    const modify = new Modify({
      features: this.modifiableFeatures,
      // Shift+single-click deletes the vertex under the cursor.
      deleteCondition: (event) => shiftKeyOnly(event) && singleClick(event),
    });
    this.listenerKeys.push(
      modify.on('modifyend', (event) => {
        this.shiftFeaturesOntoGlobe(event.features.getArray());
        this.emitFeatures();
      }),
    );

    // OL's built-in Translate drags a whole feature on this layer; Modify takes precedence near a
    // vertex, Translate moves the body. Locked affected-area buffers are excluded (derived geometry
    // — reshape via the parent points).
    const translate = new Translate({
      layers: [layer],
      filter: (feature) => !isBufferFeature(feature as OlFeature),
    });
    this.listenerKeys.push(
      translate.on('translateend', (event) => {
        // OL fires translateend even on a plain click-to-select (a zero-distance drag). Emit only
        // when the feature actually moved, so selecting a feature is not recorded as an edit — and
        // therefore not an undo/redo history step.
        const { coordinate, startCoordinate } = event;
        if (coordinate[0] === startCoordinate[0] && coordinate[1] === startCoordinate[1]) return;
        // A feature dragged past the antimeridian onto a world copy comes back by whole turns.
        this.shiftFeaturesOntoGlobe(event.features.getArray());
        this.emitFeatures();
      }),
    );

    const remove = new RemoveInteraction({
      source: this.source,
      onRemove: (feature) => {
        // Keep the Select collection consistent with the source — OL's Select does not
        // listen for source removals, so a deleted feature would otherwise linger in the
        // selection. The 'remove' listener then re-emits the (now correct) selection.
        select.getFeatures().remove(feature);
        this.emitFeatures();
      },
    });

    // Ctrl/Cmd-drag a box to add the intersecting features to the selection (any mode).
    const dragBox = new DragBox({ condition: platformModifierKeyOnly });
    this.listenerKeys.push(
      dragBox.on('boxstart', () => select.getFeatures().clear()),
      dragBox.on('boxend', () => {
        const extent = dragBox.getGeometry().getExtent();
        this.source.forEachFeatureIntersectingExtent(extent, (feature) =>
          select.getFeatures().push(feature),
        );
      }),
    );

    // Drop a GeoJSON / KML / WKT file onto the map to import its features (any mode).
    // OL reads in the map view projection and adds straight to our source; the
    // `addfeature` handler then assigns ids and emits.
    const dragAndDrop = new DragAndDrop({
      // OL's heterogeneous-format-class typing rejects a mixed array; the classes are
      // all FeatureFormat subclasses, so the cast is sound. WktImportFormat forces
      // EPSG:4326 on the projection-less WKT so dropped WKT lands in view.
      formatConstructors: [
        GeoJSON,
        KML,
        WktImportFormat,
      ] as unknown as DragAndDropOptions['formatConstructors'],
    });
    // APPEND dropped features. Passing `source` to DragAndDrop would
    // CLEAR the source first, wiping any in-progress geometry; instead each dropped
    // feature is appended and flows through the source 'addfeature' handler (id + emit),
    // exactly like a drawn one. (OL reads in the map view projection.)
    this.listenerKeys.push(
      dragAndDrop.on('addfeatures', (event) => {
        for (const feature of event.features ?? []) {
          if (feature instanceof OlFeature) {
            // OL's KML reader assigns each feature its own style from the inline <Style>,
            // which would override the layer style (so KML features render in their source
            // colour with no name label). Clear it so the editor style (magenta + name
            // label) applies uniformly.
            feature.setStyle(undefined);
            // marineregions.org exports carry transposed (Y,X) coordinates flagged by `mrgid` —
            // correct them (+ their `country` label) before anything reads the geometry/name.
            fixMarineRegionsImport(feature);
            // Surface the imported `name` (GeoJSON properties.name / KML <name>) as an
            // on-map label, copied into the per-language keys.
            applyImportedFeatureNames(feature, this.modelLanguages);
            // A latitude beyond 90 has no place in the map's projection (it reads as no
            // number): such a feature is no position on the globe, so it is not taken.
            const geometry = feature.getGeometry();
            if (geometry && hasPositionOffTheMap(geometry)) continue;
            this.source.addFeature(feature);
          }
        }
      }),
    );

    // New features (draw / drop) flow through the source; assign a transient editor id,
    // bring a feature drawn on a world copy (or dropped with a longitude beyond 180) back
    // onto the globe by whole turns, and emit — unless we are programmatically seeding.
    this.listenerKeys.push(
      this.source.on('addfeature', (event) => {
        if (this.seeding) return;
        const feature = event.feature;
        if (feature && feature.getId() === undefined) {
          feature.setId(crypto.randomUUID());
        }
        if (feature) this.shiftFeaturesOntoGlobe([feature]);
        // A batched add assigns ids here but defers the emit to a single call at the end.
        if (this.batching) return;
        this.emitFeatures();
      }),
    );

    this.toggleControls = {
      point: [drawPoint],
      polyline: [drawLine],
      polygon: [drawPolygon],
      box: [drawBox],
      select: [select, translate, modify],
      remove: [remove],
    };

    // DragBox + DragAndDrop are always active (gated by their own key/file conditions);
    // the toggle controls start inactive and are switched on by syncDrawMode.
    const alwaysOn: Interaction[] = [dragBox, dragAndDrop];
    const toggled = Object.values(this.toggleControls).flat();
    for (const interaction of toggled) {
      interaction.setActive(false);
    }
    for (const interaction of [...toggled, ...alwaysOn]) {
      map.addInteraction(interaction);
      this.ownedInteractions.push(interaction);
    }
  }

  private syncFeatures(features: readonly OlFeature[]): void {
    if (features !== this.lastSeeded) {
      this.lastSeeded = features;
      this.seeding = true;
      try {
        this.source.clear();
        if (features.length > 0) {
          this.source.addFeatures([...features]);
        }
      } finally {
        this.seeding = false;
      }
      // A fresh import discards any prior selection and requests a one-off refit.
      this.select?.getFeatures().clear();
      this.pendingFit = features.length > 0 && this.fitExtent();
    }
    // Fit once per distinct import, deferred until the map exists (the seed can arrive
    // before the map is ready). Edits never change the seed reference, so they never refit.
    if (this.pendingFit && this.shell.map()) {
      this.fitView();
    }
  }

  private syncDrawMode(mode: EditorDrawMode): void {
    if (!this.interactionsBuilt || mode === this.lastMode) return;
    this.lastMode = mode;
    for (const [key, interactions] of Object.entries(this.toggleControls)) {
      const active = key === mode;
      for (const interaction of interactions) {
        interaction.setActive(active);
      }
    }
    // Clear the selection on every draw-control switch.
    this.select?.getFeatures().clear();
  }

  /**
   * Sets the map selection to exactly `ids` (the tree→map command). The Select add/remove
   * listeners re-emit `selectionChanged`, so selection stays single-sourced in this layer —
   * there is no selection input to feed back, hence no loop.
   */
  selectFeatures(ids: readonly string[]): void {
    const collection = this.select?.getFeatures();
    if (!collection) return;
    const wanted = new Set(ids);
    for (const feature of [...collection.getArray()]) {
      if (!wanted.has(String(feature.getId()))) {
        collection.remove(feature);
      }
    }
    const present = new Set(collection.getArray().map((feature) => String(feature.getId())));
    for (const feature of this.source.getFeatures()) {
      const id = String(feature.getId());
      if (wanted.has(id) && !present.has(id)) {
        collection.push(feature);
      }
    }
  }

  /** Toggles one feature in/out of the selection (a tree card's checkbox — additive multi-select). */
  toggleSelection(id: string): void {
    const collection = this.select?.getFeatures();
    const feature = this.source.getFeatureById(id);
    if (!collection || !feature) return;
    if (collection.getArray().includes(feature)) {
      collection.remove(feature);
    } else {
      collection.push(feature);
    }
  }

  /** Adds programmatic features (e.g. a computed buffer or an import) to the live source. */
  addFeatures(features: readonly OlFeature[]): void {
    if (features.length === 0) return;
    // Add all at once + emit ONCE: the source fires 'addfeature' per feature, so a naive loop
    // would trigger N onFeaturesChanged/recompute passes for a multi-feature import.
    this.batching = true;
    try {
      this.source.addFeatures([...features]);
    } finally {
      this.batching = false;
    }
    this.emitFeatures();
  }

  /** Removes features by id from the live source + the selection (e.g. cascade-deleted buffers). */
  removeFeatures(ids: readonly string[]): void {
    const wanted = new Set(ids);
    let removed = false;
    for (const feature of [...this.source.getFeatures()]) {
      if (wanted.has(String(feature.getId()))) {
        this.select?.getFeatures().remove(feature);
        this.source.removeFeature(feature);
        removed = true;
      }
    }
    if (removed) this.emitFeatures();
  }

  /** Fits the map view to the current features' extent (the manual "Zoom to extent" command). */
  zoomToExtent(): void {
    this.fitView(FIT_DURATION_MS);
  }

  /** Fits the map view to a single feature's extent (a geometry card's "Zoom to geometry"). */
  zoomToFeature(id: string): void {
    this.fitToExtent(this.source.getFeatureById(id)?.getGeometry()?.getExtent(), FIT_DURATION_MS);
  }

  /**
   * Replaces the live source with exactly `features`, clearing the selection, WITHOUT refitting
   * the view or emitting `featuresChanged` — the host already holds this set (used by undo/redo
   * restore). Feature ids are taken as-is (no reassignment), so parent/selection references hold.
   */
  setFeatures(features: readonly OlFeature[]): void {
    this.seeding = true;
    try {
      this.source.clear();
      this.select?.getFeatures().clear();
      if (features.length > 0) {
        this.source.addFeatures([...features]);
      }
    } finally {
      this.seeding = false;
    }
  }

  /**
   * Reorders the live source to match `orderedIds` — the card drag-reorder. The SAME feature
   * instances are re-added (so ids, the map selection, and buffer parent references all hold),
   * WITHOUT refitting the view or emitting `featuresChanged` (the host already set its working
   * order). Ids not present are ignored; any source feature absent from `orderedIds` keeps its
   * relative tail position. Runs under the seeding guard so the re-add fires no per-feature emit.
   */
  reorderFeatures(orderedIds: readonly string[]): void {
    const current = this.source.getFeatures();
    if (current.length === 0) return;
    const byId = new Map(current.map((feature) => [String(feature.getId()), feature]));
    const ordered: OlFeature[] = [];
    for (const id of orderedIds) {
      const feature = byId.get(id);
      if (feature) {
        ordered.push(feature);
        byId.delete(id);
      }
    }
    for (const feature of current) {
      if (byId.has(String(feature.getId()))) ordered.push(feature);
    }
    this.seeding = true;
    try {
      this.source.clear();
      this.source.addFeatures(ordered);
    } finally {
      this.seeding = false;
    }
  }

  /**
   * Moves each feature whose longitudes lie off the globe back by whole turns of 360 degrees,
   * the whole geometry at once (so one across the antimeridian keeps its shape), as the backend
   * does on save. A locked affected-area buffer follows its parents on their next recompute.
   */
  private shiftFeaturesOntoGlobe(features: readonly OlFeature[]): void {
    for (const feature of features) {
      if (isBufferFeature(feature)) continue;
      const geometry = feature.getGeometry();
      if (geometry) shiftOntoGlobe(geometry);
    }
  }

  private emitFeatures(): void {
    this.featuresChanged.emit([...this.source.getFeatures()]);
  }

  private emitSelection(): void {
    const ids = (this.select?.getFeatures().getArray() ?? [])
      .map((feature) => feature.getId())
      .filter((id): id is string | number => id !== undefined)
      .map(String);
    this.selectionChanged.emit(ids);
  }

  private fitView(durationMs = 0): void {
    if (this.fitToExtent(this.source.getExtent(), durationMs)) {
      this.pendingFit = false;
    }
  }

  /** Fits the view to `extent` when it is defined and finite. Returns whether a fit actually ran. */
  private fitToExtent(extent: Extent | null | undefined, durationMs: number): boolean {
    const map = this.shell.map();
    if (!map || !extent || !extent.every((value) => Number.isFinite(value))) return false;
    map.getView().fit(extent, {
      padding: FIT_PADDING,
      maxZoom: this.maxZoom(),
      duration: durationMs,
    });
    return true;
  }
}
