import { Component, computed, inject, input, signal } from '@angular/core';
import { CdkDragHandle } from '@angular/cdk/drag-drop';
import { TranslocoPipe } from '@jsverse/transloco';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../core/main-shell/services/language.service';
import { Dialog } from '../../../../shared/components/dialog/dialog';
import { EditAsTextDialog } from '../../../../shared/components/geometry-editor/edit-as-text-dialog/edit-as-text-dialog';
import { GeometryEditor } from '../../../../shared/components/geometry-editor/geometry-editor';
import { OlGeometryPreviewLayer } from '../../../../shared/components/geometry-editor/ol-geometry-preview-layer/ol-geometry-preview-layer';
import { computeInitialMapView } from '../../../../shared/components/ol-map/map-view-defaults';
import { MapShellService } from '@niord/map/map-shell.service';
import { type OlMapView, OlMap } from '@niord/map/ol-map';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import { Icon } from '../../../../shared/icons/icon';
import type { GeometryFeatureCollection } from '../../../../shared/models/geometry.model';
import { formatLatLon } from '../../../../shared/utils/coordinate-format';
import type { MessagePart } from '../../../messages/models/message.model';
import {
  MessageGeometryService,
  type MessageGeoJsonFeature,
} from '../../../messages/services/message-geometry.service';
import { EditorFacade } from '../../services/editor-facade';
import { EditorFieldsService } from '../../services/editor-fields.service';
import { EditorFieldRow } from '../editor-field-row/editor-field-row';
import { DescriptionField } from '../fields/description-field/description-field';
import { EventDatesField } from '../fields/event-dates-field/event-dates-field';
import { SubjectField } from '../fields/subject-field/subject-field';
import { PartTypeSwitcher } from './part-type-switcher';

/** One serialized coordinate in the read-only positions viewer: its running index, formatted position, optional name. */
interface CoordinateRow {
  readonly index: number;
  readonly text: string;
  readonly name?: string;
}

/** One geometry feature's coordinates for the positions viewer, with the optional feature-name header. */
interface CoordinateGroup {
  readonly name?: string;
  readonly rows: readonly CoordinateRow[];
}

/**
 * One message-part card: a sunken header strip (a `cdkDragHandle` grip for drag-reorder, "Part N"
 * title, the inline type switcher, and the right-aligned move-up/move-down/remove actions) over the
 * part-indexed field rows gated by the visibility predicates — event dates, positions, subject
 * (DETAILS only), and the always-on description row. Adding a part lives in the Parts panel head, not
 * on the card. The grip stays decorative to assistive tech (keyboard reorder is the move buttons);
 * the `cdkDrag` wrapper + `cdkDropList` that the handle drives live in the parent edit form.
 *
 * Inputs are {@link partIndex}, the live {@link part}, and {@link first} / {@link last}
 * (move-button disable). The toolbar reuses the paired facade+fields part mutators
 * (`removePart`/`movePart` + the `EditorFieldsService` lock-step splice) so per-part expand/edited
 * state follows its part. The **description** row is delegated to `<app-description-field>`
 * (per-language rich-text body). The **positions** row collapses to the first serialized coordinate
 * line (a `(more…)` suffix when there are more); expanded, it shows a read-only mini-map + the mono
 * coordinate table with an "Edit" (the shared `<app-geometry-editor>` in a near-full-window modal,
 * lazily loaded so `@turf` / the editor chunk stay out of the initial bundle) and an "Edit as text"
 * (the shared `<app-edit-as-text-dialog>`) affordance. Both commit through the facade
 * (`setPartGeometry` → dirty + message-wide reindex); Escape/Cancel discards.
 */
@Component({
  selector: 'app-part-card',
  imports: [
    TranslocoPipe,
    CdkDragHandle,
    Icon,
    EditorFieldRow,
    DescriptionField,
    EventDatesField,
    SubjectField,
    PartTypeSwitcher,
    OlMap,
    OlTileLayer,
    OlGeometryPreviewLayer,
    Dialog,
    GeometryEditor,
    EditAsTextDialog,
  ],
  // Own a MapShellService so the positions preview map is isolated from any ancestor shell (and
  // so each part card's preview is its own). The geometry editor + edit-as-text dialog each shadow
  // this with their own provider, keeping the modal maps isolated too.
  providers: [MapShellService],
  templateUrl: './part-card.html',
  styleUrl: './part-card.css',
  host: { class: 'contents' },
})
export class PartCard {
  /** The index of the part this card renders. */
  readonly partIndex = input.required<number>();
  /** The live message part (drives the type switcher, predicates, and viewers). */
  readonly part = input.required<MessagePart>();
  /** Whether this is the first part (disables Move-up). */
  readonly first = input(false);
  /** Whether this is the last part (disables Move-down). */
  readonly last = input(false);

  protected readonly facade = inject(EditorFacade);
  protected readonly fields = inject(EditorFieldsService);
  private readonly language = inject(LanguageService);
  private readonly geometry = inject(MessageGeometryService);
  private readonly domain = inject(DomainFacade);
  private readonly config = inject(APP_CONFIG);

  /** Fallback view for the preview map before the preview layer fits to the part's geometry. */
  protected readonly initialView = computed<OlMapView | undefined>(() =>
    computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );

  /**
   * Whether the full-window geometry editor modal is open for this part. A local signal, distinct
   * from the row's expand state: expanding the positions row reveals the mini-map + coordinate table
   * inline; the "Edit" affordance there opens this modal. Both are editor-only (the Edit buttons are
   * `editableNow`-gated and the deferred editor chunk only loads once this flips), so a read-only
   * viewer never triggers either.
   */
  protected readonly positionsModalOpen = signal(false);

  /** Whether the shared "Edit as text" dialog is open for this part's geometry. */
  protected readonly positionsAsTextOpen = signal(false);

  /**
   * Whether the positions row is expanded into its editor. Gates the (eagerly-projected) mini-map +
   * coordinate table with an inner `@if` so the OL map only mounts while the row is open — content
   * projected into the field row is created with the card, not with the row's own `@if`.
   */
  protected readonly positionsExpanded = computed<boolean>(() =>
    this.fields.isExpanded('positions', this.partIndex()),
  );

  /**
   * The part's geometry serialized to per-feature coordinate lists for the read-only positions
   * viewer (mini-map + editable mount added separately): each feature's readable coordinates in the
   * current language, formatted `DD° MM.mmm'N - DDD° MM.mmm'E`.
   * Rows are numbered from the feature's MESSAGE-WIDE `startCoordIndex` (stamped by the facade's
   * reindex on load and on every geometry commit), so coordinates in
   * a later part continue the numbering rather than restarting at 1; a local counter is only the
   * fallback for unstamped data.
   */
  protected readonly coordinateGroups = computed<readonly CoordinateGroup[]>(() => {
    const lang = this.language.selectedLanguage();
    const features = this.part().geometry?.features ?? [];
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

  /** Total serialized coordinate lines across all features — gates the empty state + the `(more…)` suffix. */
  protected readonly coordinateLineCount = computed<number>(() =>
    this.coordinateGroups().reduce((sum, group) => sum + group.rows.length, 0),
  );

  /** The collapsed positions summary: the first serialized coordinate line (`1) 55° … - 012° …`). */
  protected readonly firstCoordinateLine = computed<string>(() => {
    const first = this.coordinateGroups()[0]?.rows[0];
    return first ? `${first.index}) ${first.text}` : '';
  });

  /** Whether more than one coordinate line exists — drives the collapsed `(more…)` suffix. */
  protected readonly hasMoreCoordinates = computed<boolean>(() => this.coordinateLineCount() > 1);

  private featureName(feature: MessageGeoJsonFeature, lang: string): string | undefined {
    const props = (feature.properties ?? {}) as Record<string, unknown>;
    const byLang = props[`name:${lang}`];
    if (typeof byLang === 'string' && byLang.length > 0) {
      return byLang;
    }
    const plain = props['name'];
    return typeof plain === 'string' && plain.length > 0 ? plain : undefined;
  }

  // Part mutations pair the facade op with the field-state splice so per-part expand/edited state
  // follows its part. The facade commits immutably, so a changed `parts` reference is the signal
  // that the op actually happened (boundary moves + non-editable states no-op).
  protected onMoveUp(): void {
    const before = this.facade.message()?.parts;
    const index = this.partIndex();
    this.facade.movePart(index, 'up');
    if (this.facade.message()?.parts !== before) {
      this.fields.partMoved(index, index - 1);
    }
  }

  protected onMoveDown(): void {
    const before = this.facade.message()?.parts;
    const index = this.partIndex();
    this.facade.movePart(index, 'down');
    if (this.facade.message()?.parts !== before) {
      this.fields.partMoved(index, index + 1);
    }
  }

  protected onRemove(): void {
    const before = this.facade.message()?.parts;
    const index = this.partIndex();
    this.facade.removePart(index);
    if (this.facade.message()?.parts !== before) {
      this.fields.partRemoved(index);
    }
  }

  // Positions editor: the "Edit" affordance opens the full-window geometry editor; "Edit as text"
  // opens the shared plain-text dialog. Both commit the edited geometry through the facade (dirty +
  // message-wide reindex, no adjust); Escape/Cancel discards.

  /** Open the full-window geometry editor for this part ("Edit" affordance). */
  protected openPositionsEditor(): void {
    this.positionsModalOpen.set(true);
  }

  /** Open the shared "Edit as text" dialog for this part's geometry. */
  protected openPositionsAsText(): void {
    this.positionsAsTextOpen.set(true);
  }

  /** OK from either geometry surface — commit the new geometry and close the modal. */
  protected onPositionsCommit(geometry: GeometryFeatureCollection): void {
    this.facade.setPartGeometry(this.partIndex(), geometry);
    this.positionsModalOpen.set(false);
  }

  /** Escape/Cancel from the geometry editor — close without writing. */
  protected onPositionsCancel(): void {
    this.positionsModalOpen.set(false);
  }
}
