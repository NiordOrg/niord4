import {
  afterRenderEffect,
  Component,
  computed,
  effect,
  ElementRef,
  inject,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import {
  CdkDrag,
  type CdkDragDrop,
  CdkDragHandle,
  CdkDropList,
  moveItemInArray,
} from '@angular/cdk/drag-drop';
import type { Feature as OlFeature } from 'ol';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { Menu } from '../menu/menu';
import { MenuTriggerFor } from '../menu/menu-trigger.directive';
import { Card } from '../card/card';
import { Icon } from '../../icons/icon';
import type { MenuItem } from '../../models/menu-item.model';
import { APP_CONFIG } from '../../../core/config/app-config.token';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../core/main-shell/services/language.service';
import { HelpAnchor } from '../../../core/main-shell/help/help-anchor';
import { BaseMapLayers } from '../ol-map/base-map-layers';
import { computeInitialMapView } from '../ol-map/map-view-defaults';
import { MapShellService } from '@niord/map/map-shell.service';
import { type OlMapView, OlMap } from '@niord/map/ol-map';
import { OlCoordinateReadout } from '../ol-map/ol-coordinate-readout';
import { OlLayerSwitcher } from '@niord/map/ol-layer-switcher';
import { DiscardConfirmService } from '../../services/discard-confirm.service';
import { HistoryStore } from '../../utils/history-store';
import type { GeometryFeatureCollection } from '../../models/geometry.model';
import { GeometryConversionService } from '../../services/geometry-conversion.service';
import { type EditorDrawMode, OlEditorLayer } from './ol-editor-layer/ol-editor-layer';
import { OlMeasureLayer } from './ol-measure-layer/ol-measure-layer';
import { GeometryTree } from './geometry-tree/geometry-tree';
import { GEOMETRY_TYPE_ICONS } from './geometry-tree/geometry-type-icons';
import { GeometryImportDialog } from './geometry-import-dialog/geometry-import-dialog';
import { EditAsTextDialog } from './edit-as-text-dialog/edit-as-text-dialog';
import {
  canAddBuffer,
  computeBufferGeometry,
  createBufferFeature,
  isBufferFeature,
} from './util/buffer-feature';
import type { BufferType } from './util/ol-buffer';
import {
  canConvert,
  canMerge,
  canSplit,
  type ConvertType,
  convertFeature,
  mergeFeatures,
  splitFeature,
} from './util/geometry-ops';
import { cloneEditorFeatures } from './util/geometry-history';

export type GeometryEditType = 'features' | 'feature' | 'message';

/**
 * The standalone message geometry editor — a complex VALUE editor (NOT a form):
 * `[geometry]` (a GeoJSON FeatureCollection in EPSG:4326) in, `(geometryChange)` out
 * on **commit** (OK), never live. Escape / Cancel discards the working changes.
 *
 * The working state is a single signal of OL features (the source of truth) that
 * the import, the draw/edit interactions, the DMS coordinate tree, and the
 * buffer/ops/import actions all build on; edits round-trip on commit only.
 *
 * Self-contained: it owns its working state and does not depend on the app's
 * reactive-forms layer.
 */
@Component({
  selector: 'app-geometry-editor',
  imports: [
    TranslocoPipe,
    CdkDropList,
    CdkDrag,
    CdkDragHandle,
    Card,
    HelpAnchor,
    Menu,
    MenuTriggerFor,
    Icon,
    OlMap,
    BaseMapLayers,
    OlLayerSwitcher,
    OlEditorLayer,
    OlMeasureLayer,
    OlCoordinateReadout,
    GeometryTree,
    GeometryImportDialog,
    EditAsTextDialog,
  ],
  // Own the MapShellService so the layer switcher — lifted out of the OlMap content into the
  // collapsible left overlay (a sibling of OlMap) — shares the same shell + layer registrations.
  providers: [MapShellService],
  template: `
    <div
      class="flex flex-wrap items-center gap-2 border-b border-surface-200 bg-surface-0 px-3 py-2 dark:border-surface-700 dark:bg-surface-900"
    >
      <span class="text-sm font-medium">{{ 'geometry.title' | transloco }}</span>
      <app-help-anchor [section]="helpSection()" />
      <span class="mx-1 h-5 w-px bg-surface-200 dark:bg-surface-700"></span>
      @for (mode of drawModes(); track mode) {
        <button
          type="button"
          [class]="drawMode() === mode ? 'btn-secondary text-sm' : 'btn-tertiary text-sm'"
          (click)="drawMode.set(mode)"
        >
          @if (modeGlyph(mode); as glyph) {
            <app-icon [name]="glyph" />
          }
          <span>{{ 'geometry.mode.' + mode | transloco }}</span>
        </button>
      }
      <span class="mx-1 h-5 w-px bg-surface-200 dark:bg-surface-700"></span>
      <button
        type="button"
        class="icon-button text-xs"
        [disabled]="!canUndo()"
        [attr.aria-label]="'geometry.undo' | transloco"
        [title]="'geometry.undo' | transloco"
        (click)="undo()"
      >
        <app-icon name="lucideUndo2" />
      </button>
      <button
        type="button"
        class="icon-button text-xs -scale-x-100"
        [disabled]="!canRedo()"
        [attr.aria-label]="'geometry.redo' | transloco"
        [title]="'geometry.redo' | transloco"
        (click)="redo()"
      >
        <app-icon name="lucideUndo2" />
      </button>
      <span class="mx-1 h-5 w-px bg-surface-200 dark:bg-surface-700"></span>
      <button
        type="button"
        class="icon-button"
        [disabled]="opsItems().length === 0"
        [attr.aria-label]="'geometry.ops.title' | transloco"
        [appMenuTriggerFor]="opsMenu"
        (opened)="opsMenuOpen.set(true)"
        (closed)="opsMenuOpen.set(false)"
      >
        <app-icon name="lucideSettings" />
      </button>
      <app-menu #opsMenu [model]="opsItems()" [ariaLabel]="'geometry.ops.title' | transloco" />
      <!-- ml-auto keeps the actions right-aligned whether the bar is one row or
           wraps to two on a narrow screen (where they drop to their own row). -->
      <div class="ml-auto flex items-center gap-2">
        <button type="button" class="btn-tertiary" (click)="cancel()">
          <app-icon name="lucideX" />
          <span>{{ 'geometry.cancel' | transloco }}</span>
        </button>
        <button type="button" class="btn-primary" (click)="commit()">
          <app-icon name="lucideCheck" />
          <span>{{ 'geometry.ok' | transloco }}</span>
        </button>
      </div>
    </div>
    <div class="relative flex min-h-0 flex-1">
      <app-ol-map class="min-h-0 flex-1" [view]="initialView()">
        <app-base-map-layers />
        <app-ol-editor-layer
          [features]="seed()"
          [fitExtent]="fitOnSeed()"
          [drawMode]="drawMode()"
          (featuresChanged)="onFeaturesChanged($event)"
          (selectionChanged)="selectedIds.set($event)"
        />
        <app-ol-measure-layer [active]="drawMode() === 'measure'" />
        <app-ol-coordinate-readout />
      </app-ol-map>

      <!--
        Collapsible left overlay (like the messages map view): the geometry list panel + the
        layer switcher float over the map. pointer-events-none on the column so the map stays
        draggable in the gaps; each panel re-enables pointer events. The switcher sits at the
        bottom (mt-auto) and shares the map's MapShellService via this component's provider.
      -->
      <div
        class="pointer-events-none absolute top-2 bottom-2 left-2 z-10 flex w-80 max-w-[calc(100%-1rem)] flex-col gap-2"
      >
        <app-card
          [header]="'geometry.title' | transloco"
          [fill]="true"
          [padded]="false"
          sectionClass="pointer-events-auto shadow"
        >
          <button
            cardActions
            type="button"
            class="icon-button text-xs"
            (click)="toggleSidebar()"
            [attr.aria-label]="
              (sidebarCollapsed() ? 'geometry.expand' : 'geometry.collapse') | transloco
            "
            [attr.title]="
              (sidebarCollapsed() ? 'geometry.expand' : 'geometry.collapse') | transloco
            "
          >
            <app-icon [name]="sidebarCollapsed() ? 'lucideChevronDown' : 'lucideChevronUp'" />
          </button>
          @if (!sidebarCollapsed()) {
            <div
              cdkDropList
              class="flex flex-col gap-2 p-2"
              (cdkDropListDropped)="onReorderFeatures($event)"
            >
              @for (feature of features(); track featureId(feature)) {
                <div
                  cdkDrag
                  class="flex items-start gap-1"
                  [attr.data-feature-id]="featureId(feature)"
                >
                  <button
                    type="button"
                    cdkDragHandle
                    class="icon-button mt-1 shrink-0 cursor-grab text-fg-subtle"
                    [attr.aria-label]="'geometry.card.reorder' | transloco"
                    [attr.title]="'geometry.card.reorder' | transloco"
                  >
                    <app-icon name="lucideGripVertical" />
                  </button>
                  <app-geometry-tree
                    class="min-w-0 flex-1"
                    [feature]="feature"
                    [editType]="editType()"
                    [locked]="isBuffer(feature)"
                    [selected]="selectedIds().includes(featureId(feature))"
                    [singleSelected]="isSoleSelection(feature)"
                    [language]="language()"
                    [languages]="modelLanguages"
                    [revision]="revision()"
                    (selectRequested)="onTreeSelect(feature)"
                    (selectionToggled)="onSelectionToggle(feature)"
                    (geometryEdited)="onTreeEdited()"
                    (zoomRequested)="onZoomToFeature(feature)"
                    (deleteRequested)="onDeleteFeature(feature)"
                    (propertyChanged)="onPropertyChanged()"
                  />
                </div>
              } @empty {
                <p class="px-1 py-2 text-xs text-fg-muted">
                  {{ 'geometry.noFeatures' | transloco }}
                </p>
              }
            </div>
          }
        </app-card>
        <app-ol-layer-switcher
          class="pointer-events-auto mt-auto shrink-0"
          [title]="'messages.map.layerSwitcher.title' | transloco"
        />
      </div>
    </div>

    <app-geometry-import-dialog
      [visible]="importDialogVisible()"
      (visibleChange)="importDialogVisible.set($event)"
      (imported)="onImported($event)"
    />
    <app-edit-as-text-dialog
      [visible]="editTextDialogVisible()"
      (visibleChange)="editTextDialogVisible.set($event)"
      [featureCollection]="currentFeatureCollection()"
      (saved)="onEditedAsText($event)"
    />
  `,
  host: {
    class: 'flex h-full flex-col',
    '(document:keydown.escape)': 'onEscape($event)',
    '(window:beforeunload)': 'onBeforeUnload($event)',
    // Undo: Ctrl+Z (Win/Linux), Cmd+Z (Mac).
    '(document:keydown.control.z)': 'onUndoKey($event)',
    '(document:keydown.meta.z)': 'onUndoKey($event)',
    // Redo: Ctrl+Shift+Z / Ctrl+Y (Win/Linux), Cmd+Shift+Z (Mac).
    '(document:keydown.control.shift.z)': 'onRedoKey($event)',
    '(document:keydown.control.y)': 'onRedoKey($event)',
    '(document:keydown.meta.shift.z)': 'onRedoKey($event)',
  },
})
export class GeometryEditor {
  private readonly geo = inject(GeometryConversionService);
  private readonly languageService = inject(LanguageService);
  private readonly transloco = inject(TranslocoService);
  private readonly discardConfirm = inject(DiscardConfirmService);
  private readonly config = inject(APP_CONFIG);
  private readonly domain = inject(DomainFacade);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** Active language ('en'/'da') — drives the DDM decimal separator + name:<lang> labels. */
  protected readonly language = this.languageService.selectedLanguage;
  /** All model languages — for the tree's per-coordinate name rows. */
  protected readonly modelLanguages = this.languageService.modelLanguages.map((l) => l.value);

  /** The editable layer — used to drive the map selection from a tree click (one-way command). */
  private readonly editorLayer = viewChild(OlEditorLayer);

  /**
   * Initial map view when no incoming geometry is fitted: the session domain's configured
   * center/zoom (falling back to the deployment default), matching the homepage and messages
   * map. When the editor opens WITH geometry, the editor layer fits to that geometry's extent,
   * overriding this default.
   */
  protected readonly initialView = computed<OlMapView | undefined>(() =>
    computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );

  readonly geometry = input<GeometryFeatureCollection | undefined>(undefined);
  /** `message` unlocks names/radius/restriction/import/buffer; `feature` merges on commit. */
  readonly editType = input<GeometryEditType>('features');

  /**
   * Force the cog "Import…" item on regardless of `editType`. `message` always
   * gets Import; consumers without per-feature properties (e.g. charts, which
   * use `features` to suppress the property panel) opt back into Import here.
   */
  readonly showImport = input<boolean>(false);

  /** CSV of enabled draw modes. */
  readonly drawControls = input<string>('point,polyline,polygon,select,remove,measure');

  /**
   * Help-anchor section for the toolbar `?` link. Defaults to the position-editor
   * manual because the geometry-editing UI is identical across hosts (Editor,
   * Admin charts, the dev sandbox, edit-as-text). A host can override it to point
   * at a different `HELP_URLS` section; an unknown key resolves to no URL and the
   * icon hides.
   */
  readonly helpSection = input<string>('position-editor');

  readonly geometryChange = output<GeometryFeatureCollection>();
  readonly cancelled = output<void>();

  /** The cog "Actions" menu trigger — read so Escape can close just the menu, not the editor. */
  private readonly opsMenuTrigger = viewChild.required(MenuTriggerFor);
  /** Mirrors the ops menu's open state (via the trigger's opened/closed). */
  protected readonly opsMenuOpen = signal(false);

  /**
   * Serialized baseline (the committed/imported geometry) that {@link dirty} compares
   * against. Refreshed at every baseline point: the fresh-input import, commit, and the
   * cancel re-import.
   */
  private readonly baselineJson = signal('');

  /**
   * True when the working geometry DIFFERS from the baseline — gates Cancel/Escape
   * (confirm before discard) and blocks a page reload, mirroring the drawer. A state
   * comparison, not a latch: undoing every edit, or deleting a just-placed geometry,
   * brings the working set back to the baseline and clears this. Reads `revision()` so
   * in-place tree/property coordinate edits (which mutate features without a new
   * `features()` reference) are seen too.
   */
  protected readonly dirty = computed(() => {
    this.revision();
    return this.serializeFeatures() !== this.baselineJson();
  });

  /** The working geometry serialized to GeoJSON text — the unit of the {@link dirty} comparison. */
  private serializeFeatures(): string {
    return JSON.stringify(this.geo.olFeaturesToCollection(this.features()));
  }

  /**
   * `seed` is the layer's import seed (changes only on import/cancel); `features` is the
   * live working model, updated from the layer's edits. Keeping them separate is what
   * makes the layer one-way-driven without a draw→emit→reseed loop.
   */
  protected readonly seed = signal<readonly OlFeature[]>([]);
  protected readonly features = signal<readonly OlFeature[]>([]);
  /** Whether the next seed should fit the camera — false on a no-edit Cancel re-import. */
  protected readonly fitOnSeed = signal(true);

  /** Selected feature ids, mirrored from the layer's Select interaction (single source). */
  protected readonly selectedIds = signal<readonly string[]>([]);
  /** Bumped on any geometry edit (map or tree) so the per-feature trees re-derive. */
  protected readonly revision = signal(0);

  /** Undo/redo history of editor snapshots (deep feature clones); reusable signal-backed store. */
  private readonly history = new HistoryStore<OlFeature[]>();
  protected readonly canUndo = this.history.canUndo;
  protected readonly canRedo = this.history.canRedo;
  /** Coalesces an edit's synchronous multi-emit (e.g. merge = remove + add) into one snapshot. */
  private historyCaptureQueued = false;
  /** Suppresses capture while a programmatic undo/redo restore is being applied. */
  private restoringHistory = false;

  protected readonly drawMode = signal<EditorDrawMode>('select');
  /** Icon glyph ids shown on the toolbar draw-mode buttons (paired with the mode label). */
  protected readonly modeIcons: Record<EditorDrawMode, string> = {
    point: GEOMETRY_TYPE_ICONS['Point'],
    polyline: GEOMETRY_TYPE_ICONS['LineString'],
    polygon: GEOMETRY_TYPE_ICONS['Polygon'],
    box: 'lucideMaximize',
    select: 'lucideMove',
    remove: 'lucideEraser',
    measure: 'lucideMoveHorizontal',
    none: '',
  };
  /** The left geometry panel floats over the map as a collapsible overlay (default expanded). */
  protected readonly sidebarCollapsed = signal(false);
  /** The enabled draw-mode buttons, parsed from the `drawControls` CSV. */
  protected readonly drawModes = computed(() =>
    this.drawControls()
      .split(',')
      .map((mode) => mode.trim())
      .filter((mode): mode is EditorDrawMode => mode in this.modeIcons),
  );

  /** Bare glyph id for a draw mode's toolbar icon (`''` renders no icon). */
  protected modeGlyph(mode: EditorDrawMode): string {
    return this.modeIcons[mode];
  }

  /** Features matching the current selection — the buffer-add source + property-panel target. */
  protected readonly selectedFeatures = computed(() => {
    const ids = new Set(this.selectedIds());
    return this.features().filter((feature) => ids.has(this.featureId(feature)));
  });
  private readonly convertTypes: readonly ConvertType[] = ['LineString', 'Polygon', 'MultiPoint'];

  /** Import + edit-as-text dialog visibility (both opened from the cog "Actions" menu). */
  protected readonly importDialogVisible = signal(false);
  protected readonly editTextDialogVisible = signal(false);
  /** The working geometry as GeoJSON — fed to the edit-as-text dialog to pre-format on open. */
  protected readonly currentFeatureCollection = computed(() =>
    this.geo.olFeaturesToCollection(this.features()),
  );

  /**
   * The cog "Actions" menu. Order: the global actions (Clear all, Zoom to extent, Edit
   * as text, Import) come first, then a divider, then the selection-gated geometry ops
   * (Merge ≥ 2 / Split ≥ 1 non-point / Convert one feature to a valid target type). Clear all
   * and Zoom to extent gray out on an empty editor (nothing to clear / fit). (Touches `language`
   * so the menu labels re-translate on a language switch.)
   */
  protected readonly opsItems = computed<MenuItem[]>(() => {
    this.language();
    const selected = this.selectedFeatures();
    const hasFeatures = this.features().length > 0;

    const items: MenuItem[] = [
      {
        label: this.transloco.translate('geometry.ops.clearAll'),
        icon: 'lucideTrash2',
        disabled: !hasFeatures,
        command: () => this.onClearAll(),
      },
      {
        label: this.transloco.translate('geometry.ops.zoomToExtent'),
        icon: 'lucideZoomIn',
        disabled: !hasFeatures,
        command: () => this.onZoomToExtent(),
      },
    ];
    // Edit-as-text on any non-`feature` editType; Import on `message` (listed after edit-as-text).
    if (this.editType() !== 'feature') {
      items.push({
        label: this.transloco.translate('geometry.editAsText.title'),
        icon: 'lucidePencil',
        command: () => this.editTextDialogVisible.set(true),
      });
    }
    if (this.editType() === 'message' || this.showImport()) {
      items.push({
        label: this.transloco.translate('geometry.import.title'),
        icon: 'lucideDownload',
        command: () => this.importDialogVisible.set(true),
      });
    }

    // Selection-gated geometry ops, below a divider. The affected-area actions come first
    // (affected-area before merge/split). "Add affected radius" offsets ANY
    // selected geometry (point → circle, line → corridor, polygon → expanded); path/area build a
    // LineString / Polygon from the selected points. Affected areas are a MESSAGE concept —
    // the chart/area extent editors never offer them (gated on editType 'message').
    const ops: MenuItem[] = [];
    if (this.editType() === 'message') {
      if (canAddBuffer('radius', selected)) {
        ops.push({
          label: this.transloco.translate('geometry.buffer.addRadius'),
          icon: 'lucideCircle',
          command: () => this.onAddBuffer('radius'),
        });
      }
      if (canAddBuffer('path', selected)) {
        ops.push({
          label: this.transloco.translate('geometry.buffer.addPath'),
          icon: 'lucideChartLine',
          command: () => this.onAddBuffer('path'),
        });
      }
      if (canAddBuffer('area', selected)) {
        ops.push({
          label: this.transloco.translate('geometry.buffer.addArea'),
          icon: 'lucideStar',
          command: () => this.onAddBuffer('area'),
        });
      }
    }
    if (canMerge(selected)) {
      ops.push({
        label: this.transloco.translate('geometry.ops.merge'),
        icon: 'lucideLink',
        command: () => this.onMerge(),
      });
    }
    if (canSplit(selected)) {
      ops.push({
        label: this.transloco.translate('geometry.ops.split'),
        icon: 'lucideShare2',
        command: () => this.onSplit(),
      });
    }
    if (selected.length === 1) {
      const targets = this.convertTypes.filter((type) => canConvert(selected[0], type));
      if (targets.length > 0) {
        ops.push({
          label: this.transloco.translate('geometry.ops.convert'),
          icon: 'lucideRefreshCw',
          items: targets.map((type) => ({
            label: this.transloco.translate(`geometry.ops.to.${type}`),
            icon: GEOMETRY_TYPE_ICONS[type],
            command: () => this.onConvert(type),
          })),
        });
      }
    }

    if (ops.length > 0) {
      items.push({ separator: true }, ...ops);
    }
    return items;
  });

  constructor() {
    // Import the input geometry into the working model. Commit-on-OK semantics:
    // the input is the STARTING point (re-imported on a fresh input), not a live
    // two-way binding — edits live only in `features` until commit/cancel.
    effect(() => {
      const fc = this.geometry();
      untracked(() => {
        this.importGeometry(fc);
        // A fresh input is a new starting point — discard any prior undo/redo history
        // and re-baseline (the editor matches its input again).
        this.history.reset(cloneEditorFeatures(this.features()));
        this.baselineJson.set(this.serializeFeatures());
        // Open ready to DRAW — adding a position is the common opening move, so the editor
        // opens in draw mode on every geometry, deliberately, not only on empty ones. Still
        // gated on the host actually offering `point`: the chart editor's toolbar is
        // box/polygon/select/remove, so an unconditional 'point' would leave it in a mode with no
        // matching button while map clicks silently dropped points into a coverage extent.
        this.drawMode.set(this.drawModes().includes('point') ? 'point' : 'select');
      });
    });

    // Keep the selected feature's card visible: when a single feature becomes selected (map click
    // or tree select), scroll its card into the scrollable list panel.
    afterRenderEffect(() => {
      const ids = this.selectedIds();
      if (ids.length !== 1) return;
      const cards = this.host.nativeElement.querySelectorAll<HTMLElement>('[data-feature-id]');
      for (const card of cards) {
        if (card.dataset['featureId'] === ids[0]) {
          card.scrollIntoView?.({ block: 'nearest' });
          break;
        }
      }
    });
  }

  protected featureId(feature: OlFeature): string {
    return String(feature.getId());
  }

  /** Affected-area buffers are derived from their parents — locked (read-only) in the tree. */
  protected isBuffer(feature: OlFeature): boolean {
    return isBufferFeature(feature);
  }

  /** True when `feature` is the ONLY selected feature — gates its inline Feature fields. */
  protected isSoleSelection(feature: OlFeature): boolean {
    const ids = this.selectedIds();
    return ids.length === 1 && ids[0] === this.featureId(feature);
  }

  /** Collapse/expand the floating geometry-list overlay. */
  protected toggleSidebar(): void {
    this.sidebarCollapsed.update((collapsed) => !collapsed);
  }

  /** Map edit (draw/modify/translate/delete/drop) — refresh the working set + re-derive trees. */
  protected onFeaturesChanged(features: readonly OlFeature[]): void {
    this.features.set(features);
    this.afterModelEdited();
  }

  /** A tree's coordinate edit already mutated the feature geometry (map re-renders); re-derive. */
  protected onTreeEdited(): void {
    this.afterModelEdited();
  }

  /** Adds an affected-area buffer (radius offset of any geometry, or a path/area from points). */
  protected onAddBuffer(type: BufferType): void {
    const buffer = createBufferFeature(this.selectedFeatures(), type, this.features());
    if (buffer) this.editorLayer()?.addFeatures([buffer]);
  }

  /**
   * Geometry ops — swap the affected features in the layer (remove originals + add results);
   * the resulting `featuresChanged` emits flow through `onFeaturesChanged`, which re-derives the
   * trees and recomputes buffers. New features are unselected.
   */
  protected onMerge(): void {
    const layer = this.editorLayer();
    const selected = this.selectedFeatures();
    const merged = mergeFeatures(selected);
    if (!layer || !merged) return;
    layer.removeFeatures(selected.map((feature) => this.featureId(feature)));
    layer.addFeatures([merged]);
  }

  protected onSplit(): void {
    const layer = this.editorLayer();
    if (!layer) return;
    for (const feature of this.selectedFeatures()) {
      const parts = splitFeature(feature);
      if (!parts) continue; // a Point is left untouched
      layer.removeFeatures([this.featureId(feature)]);
      layer.addFeatures(parts);
    }
  }

  protected onConvert(toType: ConvertType): void {
    const layer = this.editorLayer();
    const selected = this.selectedFeatures();
    if (!layer || selected.length !== 1) return;
    const converted = convertFeature(selected[0], toType);
    if (!converted) return;
    layer.removeFeatures([this.featureId(selected[0])]);
    layer.addFeatures([converted]);
  }

  /**
   * Clear all — removes every feature from the editor layer; the resulting `featuresChanged`
   * (empty) and `selectionChanged` (empty) emits flow back through `onFeaturesChanged`, so the
   * working set, trees, and selection all reset. Cancel restores the original input; OK commits
   * the empty geometry.
   */
  protected onClearAll(): void {
    this.editorLayer()?.removeFeatures(this.features().map((feature) => this.featureId(feature)));
  }

  /** Zoom to extent — fits the map view to the current features. */
  protected onZoomToExtent(): void {
    this.editorLayer()?.zoomToExtent();
  }

  /**
   * Queues a single history snapshot for the current edit. Coalesced via a microtask so an edit
   * that emits several times synchronously (e.g. merge = remove + add, or a buffer cascade-delete)
   * records ONE step of the settled state, not the broken intermediates. Suppressed while a
   * restore is in flight.
   */
  private scheduleHistoryCapture(): void {
    if (this.restoringHistory || this.historyCaptureQueued) return;
    this.historyCaptureQueued = true;
    queueMicrotask(() => {
      this.historyCaptureQueued = false;
      if (this.restoringHistory) return;
      this.history.push(cloneEditorFeatures(this.features()));
    });
  }

  /** Step back one edit (Ctrl+Z / the ↶ button). */
  protected undo(): void {
    const snapshot = this.history.undo();
    if (snapshot) this.applyHistorySnapshot(snapshot);
  }

  /** Step forward one edit (Ctrl+Shift+Z / Ctrl+Y / the ↷ button). */
  protected redo(): void {
    const snapshot = this.history.redo();
    if (snapshot) this.applyHistorySnapshot(snapshot);
  }

  /**
   * Restores a snapshot into the live editor — fresh feature instances so the stored snapshot
   * stays immutable for the opposite step — WITHOUT refitting the view (the camera stays put) or
   * recording a new history step. Buffer geometries are part of the snapshot, so no recompute.
   * Selection is preserved across the step: any selected feature that still exists (ids are
   * carried through the clone) is re-selected, so the property panel stays open and updates in
   * place; `selectFeatures` ignores ids that the step removed.
   */
  private applyHistorySnapshot(snapshot: readonly OlFeature[]): void {
    const layer = this.editorLayer();
    if (!layer) return;
    const previousSelection = this.selectedIds();
    this.restoringHistory = true;
    try {
      const live = cloneEditorFeatures(snapshot);
      layer.setFeatures(live);
      this.features.set(live);
      layer.selectFeatures(previousSelection);
      this.revision.update((revision) => revision + 1);
    } finally {
      this.restoringHistory = false;
    }
  }

  /** Ctrl+Z → undo, unless a text field is focused (defer to native text undo) or a dialog is open. */
  protected onUndoKey(event: Event): void {
    if (this.shouldDeferHistoryKey()) return;
    event.preventDefault();
    this.undo();
  }

  /** Ctrl+Shift+Z / Ctrl+Y → redo, with the same text-field / dialog deferral as undo. */
  protected onRedoKey(event: Event): void {
    if (this.shouldDeferHistoryKey()) return;
    event.preventDefault();
    this.redo();
  }

  /** History shortcuts defer to native text undo while a field is focused, and pause for a dialog. */
  private shouldDeferHistoryKey(): boolean {
    if (this.importDialogVisible() || this.editTextDialogVisible()) return true;
    const active = document.activeElement as HTMLElement | null;
    if (!active) return false;
    return active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable;
  }

  /** A property-panel edit (name/restriction/radius) — recompute dependent buffers + refresh. */
  protected onPropertyChanged(): void {
    this.afterModelEdited();
  }

  /** Shared post-edit refresh: recompute dependent buffers, bump the revision, schedule a history capture. */
  private afterModelEdited(): void {
    this.recomputeBuffers();
    this.revision.update((revision) => revision + 1);
    this.scheduleHistoryCapture();
    // `dirty` is derived from (features + revision) vs the baseline — no flag to set.
  }

  /**
   * Recomputes affected-area buffers from their parents. `computeBufferGeometry` returns a
   * geometry whenever ≥1 point parent survives (a bare point when the radius is 0 or there are
   * too few parents for the declared type) and `undefined` ONLY when every parent is gone —
   * which is the sole trigger that drops the buffer, so a transient edit never destroys it.
   */
  private recomputeBuffers(): void {
    const all = this.features();
    const stale: string[] = [];
    for (const feature of all) {
      if (!isBufferFeature(feature)) continue;
      const geometry = computeBufferGeometry(feature, all);
      if (geometry) {
        feature.setGeometry(geometry);
      } else {
        stale.push(this.featureId(feature));
      }
    }
    if (stale.length > 0) this.editorLayer()?.removeFeatures(stale);
  }

  /** Clicking a feature's tree selects it on the map (the layer re-emits selectionChanged). */
  protected onTreeSelect(feature: OlFeature): void {
    this.editorLayer()?.selectFeatures([this.featureId(feature)]);
  }

  /** A card's checkbox — toggle this feature in/out of the multi-selection (additive). */
  protected onSelectionToggle(feature: OlFeature): void {
    this.editorLayer()?.toggleSelection(this.featureId(feature));
  }

  /** A geometry card's zoom icon — fit the map to that feature's extent. */
  protected onZoomToFeature(feature: OlFeature): void {
    this.editorLayer()?.zoomToFeature(this.featureId(feature));
  }

  /** A geometry card's trash icon — delete the whole feature (buffers cascade via recompute). */
  protected onDeleteFeature(feature: OlFeature): void {
    this.editorLayer()?.removeFeatures([this.featureId(feature)]);
  }

  /**
   * Drag-reorder of the feature cards (the grip handle). Reorders the working model and syncs
   * the editor layer's source to the same order, so the new order both survives a later map edit
   * (which re-emits the source order) and is what commit round-trips — the whole-message
   * coordinate numbering follows the card order. Routed through the shared post-edit refresh so it
   * recomputes buffers, re-derives the trees, marks dirty, and records one undo step.
   */
  protected onReorderFeatures(event: CdkDragDrop<unknown>): void {
    if (event.previousIndex === event.currentIndex) return;
    const reordered = [...this.features()];
    moveItemInArray(reordered, event.previousIndex, event.currentIndex);
    this.features.set(reordered);
    this.editorLayer()?.reorderFeatures(reordered.map((feature) => this.featureId(feature)));
    this.afterModelEdited();
  }

  /** Round-trip the working features back to GeoJSON and emit (commit). */
  protected commit(): void {
    // OK with no actual edit must not manufacture a change on the host — it
    // would flag the positions field dirty for a geometry nobody touched. When
    // nothing changed, close like Cancel instead of emitting.
    if (!this.dirty()) {
      this.cancelled.emit();
      return;
    }
    this.geometryChange.emit(this.geo.olFeaturesToCollection(this.features()));
    // The committed set is now the baseline — no pending edits to guard.
    this.baselineJson.set(this.serializeFeatures());
  }

  /**
   * Document-level Escape: cancel the editor — UNLESS a descendant overlay owns this Escape.
   * An open import / edit-as-text dialog keeps its work (explicit guard). The cog "Actions"
   * menu preventDefaults Escape (CDK menu) and the event still bubbles here: when the menu was
   * open (defaultPrevented, or still flagged open) close just the menu and leave the editor.
   */
  protected onEscape(event: Event): void {
    if (this.importDialogVisible() || this.editTextDialogVisible()) return;
    if (event.defaultPrevented || this.opsMenuOpen()) {
      this.opsMenuTrigger().close();
      return;
    }
    this.cancel();
  }

  /**
   * Discard working changes (re-import the input) and signal cancel. Bound to the Cancel button
   * and the Escape key. Confirms first when there are unsaved edits, through the app
   * discard-confirm dialog (same surface as the drawer), so a stray Cancel/Escape can't silently
   * drop the user's work.
   */
  protected async cancel(): Promise<void> {
    if (this.dirty() && !(await this.discardConfirm.confirmDiscard())) {
      return;
    }
    // Re-import the input WITHOUT refitting the camera: a no-edit Cancel should leave the view where
    // the user left it rather than recenter on the input geometry's extent.
    this.importGeometry(this.geometry(), false);
    this.history.reset(cloneEditorFeatures(this.features()));
    this.baselineJson.set(this.serializeFeatures());
    this.cancelled.emit();
  }

  /** Block a page reload/close while the editor holds unsaved edits (mirrors the drawer). */
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.dirty()) {
      event.preventDefault();
    }
  }

  /** Import (From Area/Text/UTM) — APPENDS the parsed features to the working set. */
  protected onImported(fc: GeometryFeatureCollection): void {
    const features = this.toOlFeatures(fc);
    if (features.length > 0) this.editorLayer()?.addFeatures(features);
  }

  /** Edit-as-text — REPLACES the whole geometry with the edited set. */
  protected onEditedAsText(fc: GeometryFeatureCollection): void {
    this.importGeometry(fc);
    // A whole-geometry replace is one undoable step (the pre-edit set is the prior baseline).
    // `dirty` derives from the new `features()` vs the baseline — no flag to set.
    this.scheduleHistoryCapture();
  }

  /** Converts a GeoJSON FeatureCollection to OL features, assigning a transient editor id. */
  private toOlFeatures(fc: GeometryFeatureCollection | undefined): OlFeature[] {
    return (fc?.features ?? []).map((gjFeature) => {
      const olFeature = this.geo.gjToOlFeature(gjFeature);
      // Every working feature gets a transient editor id (used by selection + the
      // tree + buffer parentFeatureIds). NOT a server id.
      if (olFeature.getId() === undefined) {
        olFeature.setId(crypto.randomUUID());
      }
      return olFeature;
    });
  }

  private importGeometry(fc: GeometryFeatureCollection | undefined, fit = true): void {
    const features = this.toOlFeatures(fc);
    // Seed the layer (new reference → re-applied to the source) and reset the working
    // model to the same starting set, so commit is correct before any edit.
    this.fitOnSeed.set(fit);
    this.seed.set(features);
    this.features.set(features);
  }
}
