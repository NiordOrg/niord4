import { Component, computed, ElementRef, inject, input, output, signal } from '@angular/core';
import type { Feature as OlFeature } from 'ol';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { Icon } from '../../../icons/icon';
import { MapProjectionService } from '@niord/map/map-projection.service';
import { FeaturePropertyPanel } from '../feature-property-panel/feature-property-panel';
import type { GeometryEditType } from '../geometry-editor';
import { applyCoordNameUpdates } from './coord-names';
import { type CoordinateEdit, GeometryTreeNode } from './geometry-tree-node';
import { iconForGeometryType } from './geometry-type-icons';
import {
  computeInsertCoordinate,
  findNodeByKey,
  geometryToTree,
  insertPositionAfter,
  removeNodeByKey,
  setCoordinateAt,
  treeToGeometry,
} from './geometry-tree-model';

/**
 * The per-feature geometry tree. Renders one OL feature's
 * geometry as a recursive, inline-editable tree and writes coordinate edits, vertex
 * add/delete, and per-coordinate names straight back onto the feature, so the map
 * re-renders from the same object.
 *
 * The tree is DERIVED from the feature geometry; since OL geometries are mutated in place
 * (the feature reference doesn't change), the host bumps `[revision]` after any external
 * edit (a map modify/drag) to force a re-derive. Add/delete keep the per-coordinate name
 * keys (`name:<idx>:<lang>`) aligned via the offset bookkeeping.
 */
@Component({
  selector: 'app-geometry-tree',
  imports: [GeometryTreeNode, FeaturePropertyPanel, TranslocoPipe, Icon],
  template: `
    <div
      class="overflow-hidden rounded border"
      [class.border-surface-200]="!selected()"
      [class.border-primary-400]="selected()"
      [class.dark:border-surface-700]="!selected()"
    >
      <div
        class="flex items-center"
        [class.bg-surface-100]="!selected()"
        [class.bg-primary-50]="selected()"
        [class.dark:bg-surface-800]="!selected()"
      >
        <input
          type="checkbox"
          class="ml-2 shrink-0 accent-primary-500"
          [checked]="selected()"
          [attr.aria-label]="'geometry.card.select' | transloco"
          [attr.title]="'geometry.card.select' | transloco"
          (change)="selectionToggled.emit()"
        />
        <button
          type="button"
          class="flex min-w-0 flex-1 items-center gap-2 px-2 py-1 text-left text-sm"
          [attr.aria-pressed]="selected()"
          (click)="selectRequested.emit()"
        >
          <app-icon [name]="featureIconClass()" class="text-fg-muted" />
          <span class="truncate font-medium">{{ label() }}</span>
        </button>
        <button
          type="button"
          class="icon-button hover:text-primary-500"
          [attr.aria-label]="'geometry.card.zoom' | transloco"
          [attr.title]="'geometry.card.zoom' | transloco"
          (click)="zoomRequested.emit()"
        >
          <app-icon name="lucideZoomIn" />
        </button>
        <button
          type="button"
          class="icon-button mr-1 hover:text-danger"
          [attr.aria-label]="'geometry.card.delete' | transloco"
          [attr.title]="'geometry.card.delete' | transloco"
          (click)="deleteRequested.emit()"
        >
          <app-icon name="lucideTrash2" />
        </button>
      </div>
      @if (singleSelected() && editType() === 'message') {
        <app-feature-property-panel
          [feature]="feature()"
          [languages]="languages()"
          [editType]="editType()"
          [revision]="revision()"
          (changed)="propertyChanged.emit()"
        />
      }
      @if (tree(); as root) {
        <!-- tabindex -1: a delegation container for the rows' keyboard events —
             focus lives on the rows themselves, never the wrapper. -->
        <div class="px-2 py-1" tabindex="-1" (keydown)="onTreeKeydown($event)">
          <app-geometry-tree-node
            [node]="root"
            [editType]="editType()"
            [locked]="locked()"
            [language]="language()"
            [languages]="languages()"
            [feature]="feature()"
            [autoEditCoordIndex]="pendingEditIndex()"
            (coordinateEdit)="onEdit($event)"
            (addVertex)="onAddVertex($event)"
            (deleteNode)="onDeleteNode($event)"
            (namesChanged)="geometryEdited.emit()"
            (autoEditConsumed)="pendingEditIndex.set(undefined)"
          />
        </div>
      }
    </div>
  `,
  host: { class: 'block' },
})
export class GeometryTree {
  private readonly projection = inject(MapProjectionService);
  private readonly transloco = inject(TranslocoService);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly feature = input.required<OlFeature>();
  readonly editType = input<GeometryEditType>('features');
  readonly selected = input<boolean>(false);
  /** True only when this is the SOLE selected card — gates the inline Feature fields (single focus). */
  readonly singleSelected = input<boolean>(false);
  /** Locked features (affected-area buffers): a lock icon and the coordinate tree shown read-only. */
  readonly locked = input<boolean>(false);
  readonly language = input<string>('en');
  /** All model languages — for the per-coordinate name rows (message edit-type). */
  readonly languages = input<readonly string[]>([]);
  /** Bumped by the host after an external edit (map modify/drag) so the tree re-derives. */
  readonly revision = input<number>(0);

  readonly geometryEdited = output<void>();
  readonly selectRequested = output<void>();
  /** Toggle this card in/out of the multi-selection (the header checkbox). */
  readonly selectionToggled = output<void>();
  /** Zoom the map to this feature's extent (the card's zoom icon). */
  readonly zoomRequested = output<void>();
  /** Delete this whole feature (the card's trash icon). */
  readonly deleteRequested = output<void>();
  /** A Feature-fields edit (name/restriction/radius) bubbled up from the inline property panel. */
  readonly propertyChanged = output<void>();

  /** Index of a just-inserted vertex to auto-open for editing. */
  protected readonly pendingEditIndex = signal<number | undefined>(undefined);

  /**
   * Feature-card icon id: a lock for locked (affected-area) features, otherwise the glyph for
   * the feature's geometry type — matching the toolbar / convert-menu icons.
   */
  protected readonly featureIconClass = computed(() =>
    this.locked() ? 'lucideLock' : iconForGeometryType(this.feature().getGeometry()?.getType()),
  );

  protected readonly tree = computed(() => {
    this.revision();
    const geometry = this.feature().getGeometry();
    return geometry ? geometryToTree(geometry) : undefined;
  });

  /**
   * A feature's display label — its active-language name, else (for a locked affected-area
   * buffer) "Affected area", else the geometry type.
   */
  protected readonly label = computed(() => {
    this.revision();
    const feature = this.feature();
    const name = feature.get(`name:${this.language()}`);
    if (typeof name === 'string' && name.length > 0) return name;
    if (this.locked()) return this.transloco.translate('geometry.affectedArea');
    return feature.getGeometry()?.getType() ?? 'Feature';
  });

  protected onEdit(edit: CoordinateEdit): void {
    const feature = this.feature();
    const geometry = feature.getGeometry();
    if (!geometry) return;
    const moved = setCoordinateAt(
      geometryToTree(geometry),
      edit.coordIndex,
      this.projection.fromLonLat([edit.lon, edit.lat]),
    );
    feature.setGeometry(treeToGeometry(moved));
    this.geometryEdited.emit();
  }

  protected onAddVertex(afterKey: string): void {
    const feature = this.feature();
    const geometry = feature.getGeometry();
    if (!geometry) return;
    const tree = geometryToTree(geometry);
    const afterNode = findNodeByKey(tree, afterKey);
    const coordinate = computeInsertCoordinate(tree, afterKey);
    if (!afterNode || !coordinate) return;

    feature.setGeometry(treeToGeometry(insertPositionAfter(tree, afterKey, coordinate)));
    const newIndex = afterNode.coordIndex + 1;
    // Every per-coordinate name at/after the new slot shifts up one.
    applyCoordNameUpdates(feature, [{ type: 'offset', fromIndex: newIndex, offset: 1 }]);
    this.pendingEditIndex.set(newIndex);
    this.geometryEdited.emit();
  }

  /**
   * Keyboard navigation over the rendered rows: ArrowUp/Down move focus between
   * visible rows, ArrowRight expands a
   * folder (or steps into its first child), ArrowLeft collapses (or steps to
   * the parent row), and F2/Enter starts inline coordinate editing. Operates on
   * the DOM rows (`[data-tree-row]`) so it composes with the recursive node
   * component without extra plumbing; typing inside an open edit input is
   * never hijacked.
   */
  protected onTreeKeydown(event: KeyboardEvent): void {
    const target = event.target as HTMLElement | null;
    if (!target || target instanceof HTMLInputElement) {
      return;
    }
    const row = target.closest<HTMLElement>('[data-tree-row]');
    if (!row) {
      return;
    }

    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowUp': {
        const rows = [...this.host.nativeElement.querySelectorAll<HTMLElement>('[data-tree-row]')];
        const next = rows[rows.indexOf(row) + (event.key === 'ArrowDown' ? 1 : -1)];
        next?.focus();
        event.preventDefault();
        break;
      }
      case 'ArrowRight': {
        const toggle = row.querySelector<HTMLElement>('[data-tree-toggle]');
        if (toggle && toggle.getAttribute('aria-expanded') !== 'true') {
          toggle.click();
        } else if (toggle) {
          // Already expanded — step into the first child row.
          const nodeEl = row.closest('app-geometry-tree-node');
          nodeEl?.querySelectorAll<HTMLElement>('[data-tree-row]')[1]?.focus();
        }
        event.preventDefault();
        break;
      }
      case 'ArrowLeft': {
        const toggle = row.querySelector<HTMLElement>('[data-tree-toggle]');
        if (toggle && toggle.getAttribute('aria-expanded') === 'true') {
          toggle.click();
        } else {
          // Collapsed or leaf — step out to the parent folder's row.
          const parentNode = row
            .closest('app-geometry-tree-node')
            ?.parentElement?.closest('app-geometry-tree-node');
          parentNode?.querySelector<HTMLElement>('[data-tree-row]')?.focus();
        }
        event.preventDefault();
        break;
      }
      case 'F2':
      case 'Enter': {
        const action =
          row.querySelector<HTMLElement>('[data-tree-edit]') ??
          row.querySelector<HTMLElement>('[data-tree-toggle]');
        action?.click();
        event.preventDefault();
        break;
      }
    }
  }

  protected onDeleteNode(key: string): void {
    const feature = this.feature();
    const geometry = feature.getGeometry();
    if (!geometry) return;
    const tree = geometryToTree(geometry);
    const node = findNodeByKey(tree, key);
    if (!node) return;

    feature.setGeometry(treeToGeometry(removeNodeByKey(tree, key)));
    const from = node.coordIndex;
    const to = from + node.coordCount;
    // Drop the deleted span's names, then shift the tail down by the removed count.
    applyCoordNameUpdates(feature, [
      { type: 'remove', fromIndex: from, toIndex: to },
      { type: 'offset', fromIndex: to, offset: -node.coordCount },
    ]);
    this.geometryEdited.emit();
  }
}
