import {
  afterNextRender,
  Component,
  computed,
  effect,
  type ElementRef,
  inject,
  Injector,
  input,
  output,
  signal,
  untracked,
  viewChild,
} from '@angular/core';
import type { Feature as OlFeature } from 'ol';
import type { Coordinate } from 'ol/coordinate';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../icons/icon';
import { MapProjectionService } from '@niord/map/map-projection.service';
import { formatLatLon, parseLatLon } from '../../../utils/coordinate-format';
import type { GeometryEditType } from '../geometry-editor';
import { isDeletableNode, type TreeNode } from './geometry-tree-model';
import { geometryTypeLabel } from './geometry-type-labels';

export interface CoordinateEdit {
  readonly coordIndex: number;
  readonly lat: number;
  readonly lon: number;
}

/** Folders spanning at most this many positions auto-expand; larger ones start collapsed. */
const AUTO_EXPAND_LIMIT = 50;
const NAMEABLE_PARENTS: readonly TreeNode['type'][] = ['Point', 'LineString', 'Exterior'];

/**
 * One node of the recursive geometry tree (self-recursive over its children). Folder
 * nodes (Point, LineString, Polygon rings, Multi-geometries, GeometryCollection) show a
 * type row with an expand toggle; `Position` nodes show the coordinate in DDM and edit
 * inline on click. Hovering a row reveals add (+) / delete (−) affordances.
 *
 * Inline editing is lenient parse-on-commit: Enter parses the text, and an INVALID value
 * flags only THIS position (red) and keeps it in edit mode — a bad vertex never marks the
 * whole feature. Escape cancels; blur commits a valid value or reverts.
 *
 * In `message` edit-type, positions under a Point/LineString/Exterior are expandable to
 * per-language name fields (`name:<coordIndex>:<lang>` on the feature).
 */
@Component({
  selector: 'app-geometry-tree-node',
  imports: [TranslocoPipe, Icon],
  template: `
    @if (isFolder()) {
      @let label = typeLabel();
      @let typeText = label.key ? (label.key | transloco) : label.text;
      <div
        class="group flex items-center gap-1 rounded py-0.5 text-sm focus:ring-1 focus:ring-accent focus:outline-none"
        data-tree-row
        tabindex="-1"
      >
        @if (node().children.length > 0) {
          <button
            type="button"
            class="flex h-4 w-4 items-center justify-center text-fg-muted"
            data-tree-toggle
            [attr.aria-expanded]="expanded()"
            [attr.aria-label]="
              (expanded() ? 'geometry.tree.collapse' : 'geometry.tree.expand')
                | transloco: { type: typeText }
            "
            (click)="toggleExpanded()"
          >
            <app-icon [name]="expanded() ? 'lucideChevronDown' : 'lucideChevronRight'" />
          </button>
        } @else {
          <span class="w-4"></span>
        }
        <span class="font-medium">{{ typeText }}</span>
        <span class="flex-1"></span>
        @if (deletable() && !locked()) {
          <button
            type="button"
            class="px-1 text-xs text-fg-muted opacity-0 hover:text-danger group-hover:opacity-100 focus:opacity-100 [@media(hover:none)]:opacity-100"
            [attr.aria-label]="'geometry.tree.delete' | transloco: { type: typeText }"
            (click)="deleteNode.emit(node().key)"
          >
            <app-icon name="lucideMinus" />
          </button>
        }
      </div>
      @if (expanded()) {
        <div class="border-l border-surface-200 pl-3 dark:border-surface-700">
          @for (child of node().children; track child.key) {
            <app-geometry-tree-node
              [node]="child"
              [editType]="editType()"
              [locked]="locked()"
              [language]="language()"
              [languages]="languages()"
              [feature]="feature()"
              [canAdd]="node().type !== 'Point'"
              [deletable]="childDeletable(child)"
              [nameable]="childNameable(child)"
              [autoEditCoordIndex]="autoEditCoordIndex()"
              (coordinateEdit)="coordinateEdit.emit($event)"
              (addVertex)="addVertex.emit($event)"
              (deleteNode)="deleteNode.emit($event)"
              (namesChanged)="namesChanged.emit()"
              (autoEditConsumed)="autoEditConsumed.emit()"
            />
          }
        </div>
      }
    } @else {
      <div
        class="group relative flex items-center gap-1 rounded py-0.5 pl-1 focus:ring-1 focus:ring-accent focus:outline-none"
        data-tree-row
        tabindex="-1"
      >
        @if (nameable() && !locked()) {
          <button
            type="button"
            class="flex h-4 w-4 items-center justify-center text-fg-muted"
            [attr.aria-expanded]="expanded()"
            [attr.aria-label]="
              (expanded() ? 'geometry.tree.hideNames' : 'geometry.tree.editNames') | transloco
            "
            (click)="toggleExpanded()"
          >
            <app-icon
              [name]="expanded() ? 'lucideChevronDown' : 'lucideChevronRight'"
              class="text-[0.6rem]"
            />
          </button>
        } @else {
          <span class="w-4"></span>
        }
        @if (editing()) {
          <input
            #posInput
            type="text"
            class="w-full min-w-0 rounded border bg-surface-0 px-1 py-0.5 font-mono text-xs dark:bg-surface-900"
            [class.border-surface-300]="!editError()"
            [class.border-danger]="editError()"
            [class.text-danger]="editError()"
            [value]="editText()"
            [attr.aria-invalid]="editError()"
            (keydown.enter)="commit(posInput.value); $event.stopPropagation()"
            (keydown.escape)="cancel(); $event.stopPropagation()"
            (blur)="onBlur(posInput.value)"
          />
        } @else if (locked()) {
          <span
            class="min-w-0 truncate px-1 py-0.5 text-left font-mono text-xs"
            [title]="title()"
            >{{ title() }}</span
          >
        } @else {
          <button
            type="button"
            class="min-w-0 truncate rounded px-1 py-0.5 text-left font-mono text-xs hover:bg-surface-100 dark:hover:bg-surface-800"
            data-tree-edit
            [title]="title()"
            (click)="startEdit()"
          >
            {{ title() }}
          </button>
        }
        <span class="flex-1"></span>
        <!--
          Pinned to the row's right edge over a surface backing:
          a too-wide coordinate is truncated (not displaced), so the minus stays reachable, and the
          edit input is width-constrained so focusing a just-added vertex never horizontal-scrolls
          the overflow-hidden card. Hidden while editing so the +/- never overlap the open input.
        -->
        @if (!editing()) {
          <span
            class="absolute inset-y-0 right-0 flex items-center gap-0.5 bg-surface-0 pl-1 opacity-0 group-hover:opacity-100 focus-within:opacity-100 [@media(hover:none)]:opacity-100 dark:bg-surface-900"
          >
            @if (canAdd() && !locked()) {
              <button
                type="button"
                class="px-1 text-xs text-fg-muted hover:text-primary-500"
                [attr.aria-label]="'geometry.tree.addPosition' | transloco"
                (click)="addVertex.emit(node().key)"
              >
                <app-icon name="lucidePlus" />
              </button>
            }
            @if (deletable() && !locked()) {
              <button
                type="button"
                class="px-1 text-xs text-fg-muted hover:text-danger"
                [attr.aria-label]="'geometry.tree.deletePosition' | transloco"
                (click)="deleteNode.emit(node().key)"
              >
                <app-icon name="lucideMinus" />
              </button>
            }
          </span>
        }
      </div>
      @if (nameable() && !locked() && expanded()) {
        <div class="flex flex-col gap-0.5 py-0.5 pl-8">
          @for (lang of languages(); track lang) {
            <label class="flex items-center gap-1 text-xs">
              <span class="w-6 uppercase text-fg-muted">{{ lang }}</span>
              <input
                #langInput
                type="text"
                class="w-44 rounded border border-surface-300 bg-surface-0 px-1 py-0.5 text-xs dark:border-surface-700 dark:bg-surface-900"
                [value]="nameFor(lang)"
                [attr.aria-label]="'geometry.tree.positionName' | transloco: { lang: lang }"
                (change)="setName(lang, langInput.value)"
              />
            </label>
          }
        </div>
      }
    }
  `,
  host: { class: 'block' },
})
export class GeometryTreeNode {
  private readonly projection = inject(MapProjectionService);
  private readonly injector = inject(Injector);
  private readonly positionInput = viewChild<ElementRef<HTMLInputElement>>('posInput');

  readonly node = input.required<TreeNode>();
  readonly editType = input<GeometryEditType>('features');
  /** Read-only mode (affected-area buffers): coordinates are shown but add/delete/edit are hidden. */
  readonly locked = input<boolean>(false);
  readonly language = input<string>('en');
  /** All model languages (for the per-coordinate name rows). */
  readonly languages = input<readonly string[]>([]);
  /** The feature being edited — for reading/writing `name:<coordIndex>:<lang>`. */
  readonly feature = input<OlFeature | undefined>(undefined);
  /** Whether a `+` (add position after) is offered (parent is a line/ring, not a Point). */
  readonly canAdd = input<boolean>(false);
  /** Whether a `−` (delete) is offered (min-vertex / sibling rules, computed by the parent). */
  readonly deletable = input<boolean>(false);
  /** Whether this position carries per-language names (message edit-type, named parent). */
  readonly nameable = input<boolean>(false);
  /** Breadth-first index of a just-inserted position to auto-open for editing. */
  readonly autoEditCoordIndex = input<number | undefined>(undefined);

  readonly coordinateEdit = output<CoordinateEdit>();
  readonly addVertex = output<string>();
  readonly deleteNode = output<string>();
  readonly namesChanged = output<void>();
  readonly autoEditConsumed = output<void>();

  constructor() {
    // Auto-open the editor for a freshly-inserted vertex (the host sets autoEditCoordIndex
    // to the new index). untracked so the editing writes don't re-trigger this effect.
    effect(() => {
      const target = this.autoEditCoordIndex();
      const node = this.node();
      if (target === undefined || node.type !== 'Position' || node.coordIndex !== target) return;
      untracked(() => {
        if (!this.editing()) {
          this.startEdit();
          this.autoEditConsumed.emit();
        }
      });
    });

    // Tree node instances are reused by their positional @for key, so an add/delete
    // elsewhere can rebind an open editor to a DIFFERENT vertex. If the bound coordinate
    // changes underneath an open edit, drop it (rather than committing stale text to the
    // wrong vertex).
    effect(() => {
      const coordinate = this.node().coordinate;
      untracked(() => {
        const startedAt = this.editStartedAt;
        if (
          this.editing() &&
          startedAt &&
          (!coordinate || coordinate[0] !== startedAt[0] || coordinate[1] !== startedAt[1])
        ) {
          this.cancel();
        }
      });
    });
  }

  /** User override of the default expand state; defaults below auto-expand small nodes. */
  private readonly userExpanded = signal<boolean | undefined>(undefined);
  protected readonly editing = signal(false);
  protected readonly editError = signal(false);
  protected readonly editText = signal('');
  /** The coordinate the open edit started on — used to drop the edit if the bound vertex shifts. */
  private editStartedAt: Coordinate | undefined;

  protected readonly isFolder = computed(() => this.node().type !== 'Position');

  /** A folder row's type in words ("Line", "Outer ring"), for its label and its buttons' names. */
  protected readonly typeLabel = computed(() => geometryTypeLabel(this.node().type));

  /**
   * Expanded by default only for small folders, and for nameable positions only once they
   * carry a name — a huge geometry starts collapsed so it neither floods the DOM nor grows
   * the panel. The user override wins once toggled.
   */
  protected readonly expanded = computed(() => {
    const override = this.userExpanded();
    if (override !== undefined) return override;
    if (this.node().type === 'Position') {
      return this.nameable() ? this.hasAnyName() : false;
    }
    return this.node().coordCount <= AUTO_EXPAND_LIMIT;
  });

  /** The Position coordinate in DDM (active locale decimal separator), or the type label. */
  protected readonly title = computed(() => {
    const node = this.node();
    if (node.type !== 'Position' || !node.coordinate) return node.type;
    const [lon, lat] = this.projection.toLonLat(node.coordinate);
    return formatLatLon(lat, lon, { locale: this.language() });
  });

  protected toggleExpanded(): void {
    this.userExpanded.set(!this.expanded());
  }

  protected childDeletable(child: TreeNode): boolean {
    return isDeletableNode(child, this.node().type, this.node().children.length);
  }

  protected childNameable(child: TreeNode): boolean {
    return (
      child.type === 'Position' &&
      this.editType() === 'message' &&
      NAMEABLE_PARENTS.includes(this.node().type)
    );
  }

  protected nameFor(lang: string): string {
    const value = this.feature()?.get(`name:${this.node().coordIndex}:${lang}`);
    return typeof value === 'string' ? value : '';
  }

  protected setName(lang: string, value: string): void {
    const feature = this.feature();
    if (!feature) return;
    const key = `name:${this.node().coordIndex}:${lang}`;
    const trimmed = value.trim();
    if (trimmed.length > 0) {
      feature.set(key, trimmed);
    } else {
      feature.unset(key);
    }
    this.namesChanged.emit();
  }

  private hasAnyName(): boolean {
    return this.languages().some((lang) => this.nameFor(lang).length > 0);
  }

  protected startEdit(): void {
    this.editText.set(this.title());
    this.editError.set(false);
    this.editStartedAt = this.node().coordinate;
    this.editing.set(true);
    // Focus + select the freshly-rendered field so the user can overtype immediately
    // (the click landed on the now-removed trigger button).
    afterNextRender(
      () => {
        const input = this.positionInput()?.nativeElement;
        input?.focus();
        input?.select();
      },
      { injector: this.injector },
    );
  }

  protected commit(value: string): void {
    const parsed = parseLatLon(value);
    if (!parsed) {
      // Lenient parse-on-commit: flag only this position and keep editing it.
      this.editError.set(true);
      return;
    }
    this.coordinateEdit.emit({
      coordIndex: this.node().coordIndex,
      lat: parsed.lat,
      lon: parsed.lon,
    });
    this.editing.set(false);
    this.editError.set(false);
  }

  protected cancel(): void {
    this.editing.set(false);
    this.editError.set(false);
  }

  /** Leaving the field commits a valid value but reverts an invalid one (no focus trap). */
  protected onBlur(value: string): void {
    if (!this.editing()) return;
    if (parseLatLon(value)) {
      this.commit(value);
    } else {
      this.cancel();
    }
  }
}
