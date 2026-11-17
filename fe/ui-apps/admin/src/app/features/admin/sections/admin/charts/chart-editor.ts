import {
  Component,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { disabled, form, FormField, min, required, validate } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';
import type { Geometry } from 'geojson';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { Drawer } from '../../../../../shared/components/drawer/drawer';
import {
  LengthCounter,
  lengthError,
} from '../../../../../shared/forms/length-counter/length-counter';
import { EditAsTextDialog } from '../../../../../shared/components/geometry-editor/edit-as-text-dialog/edit-as-text-dialog';
import { GeometryEditor } from '../../../../../shared/components/geometry-editor/geometry-editor';
import { OlGeometryPreviewLayer } from '../../../../../shared/components/geometry-editor/ol-geometry-preview-layer/ol-geometry-preview-layer';
import { computeInitialMapView } from '../../../../../shared/components/ol-map/map-view-defaults';
import { MapShellService } from '@niord/map/map-shell.service';
import { type OlMapView, OlMap } from '@niord/map/ol-map';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import { Icon } from '../../../../../shared/icons/icon';
import {
  emptyFeatureCollection,
  type GeometryFeatureCollection,
} from '../../../../../shared/models/geometry.model';
import { Chart, ChartsAdminApiService } from '../../../services/api/charts-admin-api.service';

export interface ChartEditorModel {
  readonly mode: 'add' | 'edit';
  readonly chart: Chart;
}

/** The width of the chart's text columns (number, name, datum): varchar(255). */
const CHART_TEXT_MAX = 255;

interface ChartFormModel {
  active: boolean;
  chartNumber: string;
  internationalNumber: number | null;
  name: string;
  scale: number | null;
  horizontalDatum: string;
  geometry: Geometry | null;
}

/**
 * Create/edit form for a chart, hosted in the shared drawer. `chartNumber` is
 * the immutable string PK — required on add, disabled on edit (the PUT path id).
 *
 * The chart's coverage extent is shown as a read-only preview map at the bottom
 * of the drawer, with "Edit" (the shared geometry editor in a near-full-window
 * modal) and "Edit as text" buttons overlaid inside it. A chart stores a single
 * bare `Geometry` (no per-feature properties — so the editor runs in `features`
 * mode, no name/restriction panel): this component expands a stored
 * `GeometryCollection` into one feature per member when feeding the
 * preview/modals, and collapses the edited features back to a bare geometry on
 * commit — one feature stays bare, several become a `GeometryCollection`, none
 * clears the extent. Commits land in a hidden `geometry` form
 * control so the Save gate and the drawer's dirty-close guard see extent edits.
 */
@Component({
  selector: 'app-chart-editor',
  imports: [
    Drawer,
    FormField,
    Icon,
    Dialog,
    GeometryEditor,
    EditAsTextDialog,
    LengthCounter,
    OlMap,
    OlTileLayer,
    OlGeometryPreviewLayer,
    TranslocoPipe,
  ],
  // Own a MapShellService so the preview map is isolated from the charts-page
  // overview map's shell (the geometry editor + edit-as-text dialog each shadow
  // this with their own provider, so the modals stay isolated too).
  providers: [MapShellService],
  templateUrl: './chart-editor.html',
  host: { class: 'contents' },
})
export class ChartEditor {
  private readonly api = inject(ChartsAdminApiService);
  private readonly notifications = inject(NotificationService);
  private readonly config = inject(APP_CONFIG);
  private readonly domain = inject(DomainFacade);

  /** `undefined` = closed; the page owns visibility. */
  readonly model = input.required<ChartEditorModel | undefined>();
  readonly saved = output<void>();
  readonly closed = output<void>();

  protected readonly saving = signal(false);

  private readonly formModel = signal<ChartFormModel>({
    active: true,
    chartNumber: '',
    internationalNumber: null,
    name: '',
    scale: null,
    horizontalDatum: '',
    geometry: null,
  });
  protected readonly form = form(this.formModel, (path) => {
    required(path.chartNumber);
    // chartNumber is the immutable string PK (the PUT path id) — locked on edit.
    disabled(path.chartNumber, { when: () => this.model()?.mode === 'edit' });
    // A scale is 1:n with n at least 1; an empty scale stays allowed (the rule
    // skips null). [formField] reflects it onto the input as min="1", so the browser
    // marks a lower value the user typed.
    min(path.scale, 1);
    // ERR7. Each text goes into a varchar(255) column; a longer one keeps Save
    // back and the counter under it says by how much, where the server would
    // refuse it.
    validate(path.chartNumber, ({ value }) => lengthError(value(), CHART_TEXT_MAX));
    validate(path.name, ({ value }) => lengthError(value(), CHART_TEXT_MAX));
    validate(path.horizontalDatum, ({ value }) => lengthError(value(), CHART_TEXT_MAX));
  });

  protected readonly textMax = CHART_TEXT_MAX;

  /**
   * Net change against the opened values. Signal Forms' `dirty` tracks user
   * interaction and never clears on revert, so it can't gate Save on "differs
   * from what was loaded" — a serialized baseline comparison does.
   */
  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () => JSON.stringify(this.formModel()) !== this.baselineJson(),
  );

  protected readonly saveDisabled = computed(
    () => !this.changed() || this.form().invalid() || this.saving(),
  );

  protected readonly headerKey = computed(() =>
    this.model()?.mode === 'edit'
      ? 'admin.charts.editor.editTitle'
      : 'admin.charts.editor.addTitle',
  );

  /** The geometry drives the preview map + seeds the modals. */
  protected readonly geometryValue = computed(() => this.formModel().geometry);
  protected readonly hasExtent = computed(() => this.geometryValue() !== null);

  /**
   * The working extent as a FeatureCollection — a stored `GeometryCollection`
   * expands into one feature per member so each shows and round-trips separately
   * (empty when no extent).
   */
  protected readonly currentFc = computed<GeometryFeatureCollection>(() => {
    const geometry = this.geometryValue();
    if (!geometry) {
      return emptyFeatureCollection();
    }
    const geometries = geometry.type === 'GeometryCollection' ? geometry.geometries : [geometry];
    return {
      type: 'FeatureCollection',
      features: geometries.map((g) => ({ type: 'Feature', geometry: g, properties: {} })),
    };
  });

  /** Preview map default view (used when there's no extent to fit): the session domain's center/zoom. */
  protected readonly initialView = computed<OlMapView | undefined>(() =>
    computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );

  protected readonly geometryEditorOpen = signal(false);
  protected readonly editAsTextOpen = signal(false);

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        // untracked: rebuild only when the drawer (re)opens.
        untracked(() => this.rebuildForm(model));
      } else {
        this.geometryEditorOpen.set(false);
        this.editAsTextOpen.set(false);
      }
    });
  }

  private rebuildForm(model: ChartEditorModel): void {
    const chart = model.chart;
    const next: ChartFormModel = {
      active: chart.active,
      chartNumber: chart.chartNumber ?? '',
      internationalNumber: chart.internationalNumber ?? null,
      name: chart.name ?? '',
      scale: chart.scale ?? null,
      horizontalDatum: chart.horizontalDatum ?? '',
      geometry: chart.geometry ?? null,
    };
    this.formModel.set(next);
    this.baselineJson.set(JSON.stringify(next));
    this.form().reset();
    this.geometryEditorOpen.set(false);
    this.editAsTextOpen.set(false);
    this.saving.set(false);
  }

  protected openGeometryEditor(): void {
    this.geometryEditorOpen.set(true);
  }

  protected openEditAsText(): void {
    this.editAsTextOpen.set(true);
  }

  /**
   * Collapse on commit: drop the per-feature wrappers (charts carry no
   * properties) to a single bare geometry — one feature stays bare, several
   * become a `GeometryCollection`, none clears the extent.
   */
  protected onGeometryCommit(fc: GeometryFeatureCollection): void {
    const geometries = fc.features
      .map((feature) => feature.geometry)
      .filter((g): g is Geometry => g != null);
    const geometry: Geometry | null =
      geometries.length === 0
        ? null
        : geometries.length === 1
          ? geometries[0]
          : { type: 'GeometryCollection', geometries };
    this.form.geometry().value.set(geometry);
    this.geometryEditorOpen.set(false);
  }

  protected onGeometryCancelled(): void {
    this.geometryEditorOpen.set(false);
  }

  protected save(): void {
    const model = this.model();
    if (!model || this.saveDisabled()) {
      return;
    }
    const value = this.formModel();
    const chart: Chart = {
      chartNumber: value.chartNumber,
      internationalNumber: value.internationalNumber ?? undefined,
      name: value.name || undefined,
      scale: value.scale ?? undefined,
      horizontalDatum: value.horizontalDatum || undefined,
      active: value.active,
      geometry: value.geometry ?? undefined,
    };
    const request = model.mode === 'add' ? this.api.create(chart) : this.api.update(chart);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.charts.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.charts.toast.saveError');
      },
    });
  }
}
