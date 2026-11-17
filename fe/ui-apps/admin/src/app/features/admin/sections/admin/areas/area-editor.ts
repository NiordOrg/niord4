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
import { form, FormField, validate } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';
import type { Geometry } from 'geojson';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import { EditorFieldsGrid } from '../../../components/editor-fields-grid/editor-fields-grid';
import { LocalizedTextInput } from '../../../components/localized-text-input/localized-text-input';
import { EDITOR_FIELD_CANDIDATES } from '../../../utils/editor-field-candidates';
import { DomainFacade } from '../../../../../core/domain/facades/domain.facade';
import { LanguageService } from '../../../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../../../core/notifications/notification.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { EditAsTextDialog } from '../../../../../shared/components/geometry-editor/edit-as-text-dialog/edit-as-text-dialog';
import { GeometryEditor } from '../../../../../shared/components/geometry-editor/geometry-editor';
import { OlGeometryPreviewLayer } from '../../../../../shared/components/geometry-editor/ol-geometry-preview-layer/ol-geometry-preview-layer';
import { computeInitialMapView } from '../../../../../shared/components/ol-map/map-view-defaults';
import { MapShellService } from '@niord/map/map-shell.service';
import { type OlMapView, OlMap } from '@niord/map/ol-map';
import { OlTileLayer } from '@niord/map/layers/ol-tile-layer';
import {
  LengthCounter,
  lengthErrorAny,
} from '../../../../../shared/forms/length-counter/length-counter';
import { Icon } from '../../../../../shared/icons/icon';
import {
  emptyFeatureCollection,
  type GeometryFeatureCollection,
} from '../../../../../shared/models/geometry.model';
import {
  type AdminArea,
  type AreaType,
  AreasAdminApiService,
  type MessageSortingType,
} from '../../../services/api/areas-admin-api.service';

export interface AreaEditorModel {
  readonly mode: 'add' | 'edit';
  readonly area: AdminArea;
}

const AREA_TYPES: readonly AreaType[] = ['COUNTRY', 'FIRING_AREA'];
const SORTING_TYPES: readonly MessageSortingType[] = ['NS', 'SN', 'EW', 'WE', 'CW', 'CCW'];

/** The width of an area name's column: varchar(255). */
const NAME_MAX = 255;

interface AreaFormModel {
  active: boolean;
  names: Record<string, string>;
  mrn: string;
  type: '' | AreaType;
  messageSorting: '' | MessageSortingType;
  originLatitude: number | null;
  originLongitude: number | null;
  originAngle: number | null;
  geometry: Geometry | null;
}

const EMPTY: AreaFormModel = {
  active: true,
  names: {},
  mrn: '',
  type: '',
  messageSorting: '',
  originLatitude: null,
  originLongitude: null,
  originAngle: null,
  geometry: null,
};

/**
 * The inline area editor (the right pane of the master-detail Areas page). Signal Forms over an area
 * working copy: active, per-language names (first required), MRN, area type, additional editor
 * fields, message-sorting strategy (hidden when an ANCESTOR already defines one — the child inherits)
 * with a CW/CCW origin, and the extent geometry (the Charts wrap/unwrap pattern — a bare
 * `Geometry` ⇄ a single-feature collection; edit-as-text enabled in `features` mode). Save normalises
 * blanks to unset (a blank type / messageSorting is omitted) and POSTs (add) / PUTs (edit).
 */
@Component({
  selector: 'app-area-editor',
  imports: [
    FormField,
    LocalizedTextInput,
    EditorFieldsGrid,
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
  providers: [MapShellService],
  templateUrl: './area-editor.html',
  host: { class: 'flex min-h-0 flex-1 flex-col' },
})
export class AreaEditor {
  private readonly api = inject(AreasAdminApiService);
  private readonly notifications = inject(NotificationService);
  private readonly config = inject(APP_CONFIG);
  private readonly domain = inject(DomainFacade);
  private readonly language = inject(LanguageService);

  /** `undefined` = nothing selected (empty pane); the page owns which area is being edited. */
  readonly model = input.required<AreaEditorModel | undefined>();
  readonly saved = output<void>();
  readonly cancelled = output<void>();
  /** Mirrors the unsaved-changes state so the page can gate Move up/down (only while pristine). */
  readonly dirtyChange = output<boolean>();

  protected readonly saving = signal(false);
  protected readonly editorFieldCandidates = EDITOR_FIELD_CANDIDATES;
  protected readonly areaTypes = AREA_TYPES;
  protected readonly sortingTypes = SORTING_TYPES;

  /** Configured model languages (N-language) for the per-language name inputs. */
  protected readonly languages = this.language.modelLanguages;

  private readonly formModel = signal<AreaFormModel>(EMPTY);
  protected readonly form = form(this.formModel, (path) => {
    // ERR7. A name longer than its column keeps Save back; the counter under
    // the field says by how much.
    validate(path.names, ({ value }) => lengthErrorAny(value(), NAME_MAX));
  });

  protected readonly nameMax = NAME_MAX;

  /** The per-language names (dynamic keys aren't addressable as Signal-Forms fields — managed here). */
  protected readonly names = computed(() => this.formModel().names);

  /** The chosen additional editor fields (kept outside the Signal-Forms model — a set toggle). */
  protected readonly editorFields = signal<readonly string[]>([]);

  private readonly baselineJson = signal('');
  protected readonly changed = computed(
    () =>
      JSON.stringify(this.formModel()) + JSON.stringify(this.editorFields()) !==
      this.baselineJson(),
  );

  /** The configured default language's name is mandatory. */
  protected readonly firstNameFilled = computed<boolean>(() => {
    const first = this.language.defaultLanguage;
    return !!first && (this.formModel().names[first] ?? '').trim().length > 0;
  });

  protected readonly saveDisabled = computed(
    () => !this.changed() || !this.firstNameFilled() || this.form().invalid() || this.saving(),
  );

  /**
   * Whether THIS area may define its own message-sorting: false when any ANCESTOR already defines one
   * (the child inherits). The input area is fetched with `?parent=true`, so its ancestor chain is
   * present.
   */
  protected readonly defineMessageSorting = computed<boolean>(() => {
    let parent = this.model()?.area.parent;
    while (parent) {
      if (parent.messageSorting) return false;
      parent = parent.parent;
    }
    return true;
  });

  protected readonly showOrigin = computed<boolean>(() => {
    const sorting = this.formModel().messageSorting;
    return sorting === 'CW' || sorting === 'CCW';
  });

  /** Read-only parent name shown in add-mode only (reparent is drag-and-drop only). */
  protected readonly parentName = computed<string>(() => {
    const parent = this.model()?.area.parent;
    return parent?.descs?.[0]?.name ?? '';
  });

  // ── geometry (Charts wrap/unwrap pattern) ────────────────────────────────────
  protected readonly geometryValue = computed(() => this.formModel().geometry);
  protected readonly hasExtent = computed(() => this.geometryValue() !== null);
  protected readonly currentFc = computed<GeometryFeatureCollection>(() => {
    const geometry = this.geometryValue();
    if (!geometry) return emptyFeatureCollection();
    const geometries = geometry.type === 'GeometryCollection' ? geometry.geometries : [geometry];
    return {
      type: 'FeatureCollection',
      features: geometries.map((g) => ({ type: 'Feature', geometry: g, properties: {} })),
    };
  });
  protected readonly initialView = computed<OlMapView | undefined>(() =>
    computeInitialMapView(this.domain.mapDefaults(), this.config.map),
  );
  protected readonly geometryEditorOpen = signal(false);
  protected readonly editAsTextOpen = signal(false);

  constructor() {
    effect(() => {
      const model = this.model();
      if (model) {
        untracked(() => this.rebuildForm(model));
      } else {
        this.geometryEditorOpen.set(false);
        this.editAsTextOpen.set(false);
      }
    });
    // Dirty is only meaningful while an area is open: with no model the baseline is empty, so
    // `changed()` reads true against the EMPTY form and would spuriously arm the discard guard.
    effect(() => this.dirtyChange.emit(this.model() !== undefined && this.changed()));
  }

  private rebuildForm(model: AreaEditorModel): void {
    const area = model.area;
    const names: Record<string, string> = {};
    for (const option of this.languages) {
      names[option.value] = area.descs?.find((d) => d.lang === option.value)?.name ?? '';
    }
    const next: AreaFormModel = {
      active: area.active ?? true,
      names,
      mrn: area.mrn ?? '',
      type: area.type ?? '',
      messageSorting: area.messageSorting ?? '',
      originLatitude: area.originLatitude ?? null,
      originLongitude: area.originLongitude ?? null,
      originAngle: area.originAngle ?? null,
      geometry: area.geometry ?? null,
    };
    this.formModel.set(next);
    this.editorFields.set(area.editorFields ?? []);
    this.baselineJson.set(JSON.stringify(next) + JSON.stringify(area.editorFields ?? []));
    this.form().reset();
    this.geometryEditorOpen.set(false);
    this.editAsTextOpen.set(false);
    this.saving.set(false);
  }

  protected setName(lang: string, value: string): void {
    this.formModel.update((model) => ({ ...model, names: { ...model.names, [lang]: value } }));
  }

  protected openGeometryEditor(): void {
    this.geometryEditorOpen.set(true);
  }

  protected openEditAsText(): void {
    this.editAsTextOpen.set(true);
  }

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
    const descs = this.languages
      .map((option) => ({
        lang: option.value,
        name: value.names[option.value]?.trim() || undefined,
      }))
      .filter((desc) => desc.name);
    const sorting = value.messageSorting || undefined;
    // Origin only travels for the CW/CCW (radial) strategies — the fields are shown only then.
    const isRadial = sorting === 'CW' || sorting === 'CCW';
    // No `siblingSortOrder`: the backend keeps the stored place among the siblings when the body
    // carries none. The order read when the editor opened goes stale after a Move up/down made while
    // the editor stays open (the page refreshes only the tree), and sending it back would undo the move.
    const area: AdminArea = {
      id: model.area.id,
      parent: model.area.parent ? { active: true, descs: [], id: model.area.parent.id } : undefined,
      active: value.active,
      mrn: value.mrn.trim() || undefined,
      descs,
      type: value.type || undefined,
      messageSorting: sorting,
      originLatitude: isRadial ? (value.originLatitude ?? undefined) : undefined,
      originLongitude: isRadial ? (value.originLongitude ?? undefined) : undefined,
      originAngle: isRadial ? (value.originAngle ?? undefined) : undefined,
      editorFields: this.editorFields().length > 0 ? [...this.editorFields()] : undefined,
      geometry: value.geometry ?? undefined,
    };
    const request = model.mode === 'add' ? this.api.create(area) : this.api.update(area);
    this.saving.set(true);
    request.subscribe({
      next: () => {
        this.notifications.success('admin.areas.toast.saved');
        this.saved.emit();
      },
      error: () => {
        this.saving.set(false);
        this.notifications.error('admin.areas.toast.saveError');
      },
    });
  }
}
