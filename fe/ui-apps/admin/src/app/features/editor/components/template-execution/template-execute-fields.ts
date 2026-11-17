import {
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  untracked,
} from '@angular/core';
import { CdkDrag, type CdkDragDrop, CdkDragHandle, CdkDropList } from '@angular/cdk/drag-drop';
import { TranslocoPipe } from '@jsverse/transloco';

import { DomainFacade } from '../../../../core/domain/facades/domain.facade';
import type { DomainMessageSeriesRef } from '../../../../core/domain/models/domain.model';
import { NotificationService } from '../../../../core/notifications/notification.service';
import { Card } from '../../../../shared/components/card/card';
import { DateTimeField } from '../../../../shared/forms/date-picker/date-time-field';
import {
  LocalizedInputs,
  type LocalizedValueInput,
} from '../../../../shared/forms/localized-inputs/localized-inputs';
import { Select } from '../../../../shared/forms/select/select';
import { Icon } from '../../../../shared/icons/icon';
import { AreasField } from '../../../messages/components/areas-field/areas-field';
import { GeometryPreviewField } from '../geometry-preview-field/geometry-preview-field';
import { SectionPanel } from '../section-panel/section-panel';
import { type Marking, TemplateMarkingsField } from './template-markings-field';
import { TemplateVesselField, type VesselData } from './template-vessel-field';
import type { GeometryFeatureCollection } from '../../../../shared/models/geometry.model';
import {
  MessageGeometryService,
  type MessageGeoJsonFeature,
} from '../../../messages/services/message-geometry.service';
import {
  MESSAGE_TYPES_BY_MAIN_TYPE,
  type BasePromulgation,
  type DateInterval,
  type MainType,
  type Message,
  type MessageArea,
  type MessagePart,
  type MessageSeries,
  type MessageType,
} from '../../../messages/models/message.model';
import type { AreaRef } from '../../../messages/models/messages-filter-params.model';
import type {
  ParamType,
  SystemCategory,
  TemplateParamValues,
} from '../../../messages/models/template.model';
import type { ParamChange } from './template-param-field';
import { TemplateParamField } from './template-param-field';
import { forceAllDayBounds } from '../../util/event-date-bounds.util';

const NW_TYPES: readonly MessageType[] = MESSAGE_TYPES_BY_MAIN_TYPE.NW;
const NM_TYPES: readonly MessageType[] = MESSAGE_TYPES_BY_MAIN_TYPE.NM;

const emptyFc = (): GeometryFeatureCollection => ({ type: 'FeatureCollection', features: [] });

/**
 * The execute-tab FORM: renders EXACTLY the standard fields (`stdTemplateFields`) + custom
 * parameters (`templateParams`) each selected template declares — nothing more, nothing less.
 * Edits a working `[(message)]` copy + a per-template
 * `[(templateData)]` map; the dialog sends both to `PUT /templates/execute`.
 *
 * Field gating is OR-across the templates for the general group and
 * per-template for the rest. A `positionList` parameter renders ONCE PER POSITION (from the part's
 * geometry) and so is hidden until a position exists; a normal parameter always renders.
 */
@Component({
  selector: 'app-template-execute-fields',
  imports: [
    TranslocoPipe,
    CdkDropList,
    CdkDrag,
    CdkDragHandle,
    Icon,
    Select,
    DateTimeField,
    AreasField,
    LocalizedInputs,
    GeometryPreviewField,
    TemplateParamField,
    TemplateVesselField,
    TemplateMarkingsField,
    Card,
    SectionPanel,
  ],
  templateUrl: './template-execute-fields.html',
  host: { class: 'block' },
})
export class TemplateExecuteFields {
  private readonly domain = inject(DomainFacade);
  private readonly geometry = inject(MessageGeometryService);
  private readonly notify = inject(NotificationService);

  readonly templates = input.required<readonly SystemCategory[]>();
  readonly paramTypes = input<ReadonlyMap<string, ParamType>>(new Map());
  readonly lang = input('');

  /** The working message copy the std fields edit (two-way with the dialog). */
  readonly message = model.required<Message>();
  /** Per-template value records (params + posDecimals + derived positions), aligned with `templates`. */
  readonly templateData = model.required<TemplateParamValues[]>();

  /** Emitted on a genuine user edit (never on the automatic scaffold / position derivation). */
  readonly userEdited = output<void>();
  /** Reorder request from the per-template card drag/move controls (the host owns the arrays). */
  readonly reorder = output<{ from: number; to: number }>();
  /** The message type changed — the host re-derives promulgations via adjust. */
  readonly typeChanged = output<void>();
  /** A part's geometry was edited — the host recomputes the areas from it. */
  readonly geometryChanged = output<void>();
  /** The manual "compute from locations" action — an unconditional recompute. */
  readonly computeAreas = output<void>();

  constructor() {
    // Scaffold the copy to match the selected templates: a DETAILS
    // part + geometry + eventDates per template, and refresh the derived position rows. Tracks the
    // working copy TOO: the host may replace it wholesale after the initial scaffold (the create
    // flow's async server-blank swap), which would otherwise discard the just-scaffolded parts.
    // Loop-safe because `scaffold` is idempotent — it returns the SAME message reference when every
    // part is already scaffolded, so the effect settles after at most one rewrite.
    effect(() => {
      const templates = this.templates();
      this.message();
      untracked(() => this.scaffold(templates.length));
    });
  }

  // ── Domain-derived: message series + valid types ───────────────────────────
  protected readonly mainType = computed<MainType | undefined>(() => this.message().mainType);
  protected readonly messageSeries = computed<readonly DomainMessageSeriesRef[]>(() =>
    (this.domain.sessionDomain()?.messageSeries ?? []).filter(
      (series) => series.mainType === this.mainType(),
    ),
  );
  /** A type is offered iff some domain series for the main type permits it. */
  protected readonly validTypes = computed<ReadonlySet<MessageType>>(() => {
    const valid = new Set<MessageType>();
    for (const series of this.messageSeries()) {
      const types = series.types ?? [];
      for (const type of this.mainType() === 'NM' ? NM_TYPES : NW_TYPES) {
        if (types.length === 0 || types.includes(type)) valid.add(type);
      }
    }
    return valid;
  });
  protected readonly typeOptions = computed<readonly MessageType[]>(() =>
    (this.mainType() === 'NM' ? NM_TYPES : NW_TYPES).filter((type) => this.validTypes().has(type)),
  );

  // ── std-field gating ───────────────────────────────────────────────────────
  /** OR-across the selected templates (no `template`), else that template's set. */
  protected showStdField(field: string, template?: SystemCategory): boolean {
    const templates = template ? [template] : this.templates();
    return templates.some((t) => (t.stdTemplateFields ?? []).includes(field));
  }

  protected nameOf(cat: SystemCategory): string {
    const descs = cat.descs;
    return descs?.find((d) => d.lang === this.lang())?.name ?? descs?.[0]?.name ?? '';
  }

  // ── Message-field edits (on the working copy) ──────────────────────────────
  protected patch(patch: Partial<Message>): void {
    this.userEdited.emit();
    this.message.update((message) => ({ ...message, ...patch }));
  }

  protected setType(type: MessageType): void {
    // Auto-select the sole series permitting the type.
    const permitting = this.messageSeries().filter(
      (series) => (series.types ?? []).length === 0 || (series.types ?? []).includes(type),
    );
    this.patch({
      type,
      ...(permitting.length === 1 ? { messageSeries: permitting[0] as MessageSeries } : {}),
    });
    // The host re-derives promulgations for the new type via adjust.
    this.typeChanged.emit();
  }

  /** A per-template card was drag-reordered — forward to the host, which owns the aligned arrays. */
  protected onDrop(event: CdkDragDrop<unknown>): void {
    if (event.previousIndex !== event.currentIndex) {
      this.reorder.emit({ from: event.previousIndex, to: event.currentIndex });
    }
  }

  /** Move a template card up/down (the buttons that mirror the message-part card controls). */
  protected move(index: number, delta: number): void {
    const to = index + delta;
    if (to >= 0 && to < this.templates().length) {
      this.reorder.emit({ from: index, to });
    }
  }

  protected setSeries(series: DomainMessageSeriesRef | undefined): void {
    this.patch({ messageSeries: series as MessageSeries | undefined });
  }

  protected togglePromulgation(index: number): void {
    this.userEdited.emit();
    this.message.update((message) => {
      const promulgations = [...(message.promulgations ?? [])];
      const current = promulgations[index];
      promulgations[index] = { ...current, promulgate: !current.promulgate } as BasePromulgation;
      return { ...message, promulgations };
    });
  }

  protected isPromulgationLocked(promulgation: BasePromulgation): boolean {
    return promulgation.type?.requirement === 'MANDATORY' && promulgation.promulgate;
  }

  protected readonly areaRefs = computed<readonly AreaRef[]>(() => this.message().areas ?? []);
  protected readonly domainId = computed<string | null>(
    () => this.domain.sessionDomain()?.domainId ?? null,
  );
  protected onAreasChange(areas: readonly AreaRef[]): void {
    this.patch({ areas: areas.map(toMessageArea) });
  }

  // ── vicinity (per-language, under the areas picker) ────
  protected readonly vicinities = computed<Record<string, string>>(() => {
    const record: Record<string, string> = {};
    for (const desc of this.message().descs ?? []) {
      if (desc.lang) {
        record[desc.lang] = desc.vicinity ?? '';
      }
    }
    return record;
  });

  protected onVicinityInput({ lang, value }: LocalizedValueInput): void {
    this.setVicinity(lang, value);
  }

  /** Blank vicinity input adopts another language's non-blank value on focus (copy-on-focus). */
  protected onVicinityFocus(lang: string): void {
    const vicinities = this.vicinities();
    if (vicinities[lang]) {
      return;
    }
    const donor = Object.entries(vicinities).find(([key, text]) => key !== lang && !!text);
    if (donor) {
      this.setVicinity(lang, donor[1]);
    }
  }

  private setVicinity(lang: string, vicinity: string): void {
    this.userEdited.emit();
    this.message.update((message) => {
      const descs = [...(message.descs ?? [])];
      const index = descs.findIndex((desc) => desc.lang === lang);
      if (index >= 0) {
        descs[index] = { ...descs[index], vicinity };
      } else {
        descs.push({ lang, vicinity });
      }
      return { ...message, descs };
    });
  }

  // ── dates ──────────────────────────────────────────────────────────────────
  protected asDate(iso: string | undefined): Date | null {
    return iso ? new Date(iso) : null;
  }
  protected setPublishFrom(date: Date | null): void {
    this.patch({ publishDateFrom: date ? date.toISOString() : undefined });
  }
  protected setPublishTo(date: Date | null): void {
    this.patch({ publishDateTo: date ? date.toISOString() : undefined });
  }

  // ── event dates (per template part) ────────────────────────────────────────
  protected eventDates(index: number): readonly DateInterval[] {
    return this.message().parts?.[index]?.eventDates ?? [];
  }
  protected addEventDate(index: number): void {
    this.updatePart(index, (part) => ({
      ...part,
      eventDates: [
        ...(part.eventDates ?? []),
        { allDay: this.mainType() === 'NM', fromDate: undefined, toDate: undefined },
      ],
    }));
  }
  protected removeEventDate(index: number, di: number): void {
    this.updatePart(index, (part) => ({
      ...part,
      eventDates: (part.eventDates ?? []).filter((_, i) => i !== di),
    }));
  }
  /**
   * Patch an event-date interval, snapping an all-day one to the whole-day bounds of the
   * domain's zone — the same rule the editor applies, so a row created here and the same row
   * edited afterwards cover the same instants.
   *
   * With no domain — and so no zone — the endpoints are left as they are and the user is told:
   * a day snapped to the editing browser's midnight would be the wrong day for the domain.
   */
  protected setEventDate(index: number, di: number, patch: Partial<DateInterval>): void {
    const zone = this.domain.sessionDomain()?.timeZone;
    const current = this.message().parts?.[index]?.eventDates?.[di];
    if (current && (patch.allDay ?? current.allDay) && !zone) {
      this.notify.error('common.dateTime.noTimeZone');
    }
    this.updatePart(index, (part) => ({
      ...part,
      eventDates: (part.eventDates ?? []).map((interval, i) => {
        if (i !== di) {
          return interval;
        }
        const merged: DateInterval = { ...interval, ...patch };
        return merged.allDay && zone ? forceAllDayBounds(merged, zone) : merged;
      }),
    }));
  }

  // ── positions (geometry per template part) ─────────────────────────────────
  protected geometryOf(index: number): GeometryFeatureCollection {
    return (this.message().parts?.[index]?.geometry as GeometryFeatureCollection) ?? emptyFc();
  }
  protected onGeometryChange(index: number, fc: GeometryFeatureCollection): void {
    this.updatePart(index, (part) => ({ ...part, geometry: fc }));
    this.updatePositions();
    // The host owns the HTTP round-trip (same shape as `typeChanged` → adjustEditableMessage):
    // keeping it out of an effect means nothing here is a reactive consumer, so the interceptor
    // chain can't leak signal reads into a tracking context.
    this.geometryChanged.emit();
  }

  /** Whether any part carries geometry — gates the manual "compute from locations" action. */
  protected readonly hasGeometry = computed<boolean>(() =>
    (this.message().parts ?? []).some(
      (part) =>
        ((part.geometry as GeometryFeatureCollection | undefined)?.features?.length ?? 0) > 0,
    ),
  );

  // ── bespoke std fields: work_vessel + markings (per template) ────────────────
  protected vessel(index: number): VesselData | undefined {
    return this.templateData()[index]?.['vessel'] as VesselData | undefined;
  }
  protected setVessel(index: number, vessel: VesselData): void {
    this.updateData(index, { vessel });
  }
  protected markings(index: number): readonly Marking[] {
    return (this.templateData()[index]?.['markings'] as Marking[]) ?? [];
  }
  protected markingType(index: number): string {
    return (this.templateData()[index]?.['markingType'] as string) ?? 'unmarked';
  }
  protected setMarkings(index: number, markings: readonly Marking[]): void {
    this.updateData(index, { markings });
  }
  protected setMarkingType(index: number, markingType: string): void {
    this.updateData(index, { markingType });
  }

  // ── parameters ─────────────────────────────────────────────────────────────
  /** The derived position rows for a positionList parameter (empty → the param is hidden). */
  protected positions(index: number): readonly Record<string, unknown>[] {
    return (this.templateData()[index]?.positions as Record<string, unknown>[]) ?? [];
  }
  protected onParamChange(index: number, change: ParamChange): void {
    this.updateData(index, { [change.paramId]: change.value });
  }
  protected onPositionParamChange(index: number, posIndex: number, change: ParamChange): void {
    this.userEdited.emit();
    this.templateData.update((list) =>
      list.map((record, i) => {
        if (i !== index) return record;
        const positions = [...((record.positions as Record<string, unknown>[]) ?? [])];
        positions[posIndex] = { ...positions[posIndex], [change.paramId]: change.value };
        return { ...record, positions };
      }),
    );
  }

  // ── internals ──────────────────────────────────────────────────────────────
  private updatePart(index: number, fn: (part: MessagePart) => MessagePart): void {
    this.userEdited.emit();
    this.message.update((message) => {
      const parts = [...(message.parts ?? [])];
      parts[index] = fn(parts[index] ?? { type: 'DETAILS' });
      return { ...message, parts };
    });
  }

  private updateData(index: number, patch: Record<string, unknown>): void {
    this.userEdited.emit();
    this.templateData.update((list) =>
      list.map((record, i) => (i === index ? { ...record, ...patch } : record)),
    );
  }

  /** Ensure a DETAILS part + geometry + eventDates per template, and refresh derived positions. */
  private scaffold(count: number): void {
    // Idempotent: rewrite (and notify) ONLY when a part is missing one of the scaffolded fields —
    // returning the unchanged reference keeps the tracking effect above from re-running forever,
    // and skipping `updatePositions` avoids churning the per-template value records on no-ops.
    let changed = false;
    this.message.update((message) => {
      const parts = [...(message.parts ?? [])];
      for (let i = 0; i < count; i++) {
        const part = parts[i];
        if (
          !part ||
          part.type === undefined ||
          part.geometry === undefined ||
          part.eventDates === undefined
        ) {
          changed = true;
          parts[i] = {
            ...part,
            type: part?.type ?? 'DETAILS',
            geometry: part?.geometry ?? emptyFc(),
            eventDates: part?.eventDates ?? [],
          };
        }
      }
      return changed ? { ...message, parts } : message;
    });
    if (changed) {
      this.updatePositions();
    }
  }

  /**
   * Rebuild each template's `positions` from its part geometry:
   * one entry per non-buffer, non-affected feature — the rows a `positionList` parameter repeats over.
   */
  private updatePositions(): void {
    const parts = this.message().parts ?? [];
    this.templateData.update((list) =>
      list.map((record, index) => {
        const features = (parts[index]?.geometry?.features ?? []).filter((feature) => {
          const props = feature.properties ?? {};
          return props['parentFeatureIds'] === undefined && props['restriction'] !== 'affected';
        });
        const existing = (record.positions as Record<string, unknown>[]) ?? [];
        const positions = features.map((feature, i) => ({
          ...(existing[i] ?? {}),
          // The backend's FreeMarker position-list templates iterate `geometry` as a SEQUENCE of
          // serialized features (`<#list geomParam.geometry as feature>` → `feature.coordinates`),
          // so raw GeoJSON (a Polygon hash) throws server-side and fails the whole execute preview.
          // Hence a one-feature list whose coordinates are `{coordinates:[lon,lat]}` point
          // objects (interior rings + the ring-closing vertex already dropped by
          // serializeReadableCoordinates).
          geometry: [
            {
              coordinates: this.geometry
                .serializeReadableCoordinates(feature as MessageGeoJsonFeature)
                .map((coord) => ({ coordinates: [coord.lonLat[0], coord.lonLat[1]] })),
            },
          ],
        }));
        return { ...record, positions };
      }),
    );
  }
}

/** Prune a picker `AreaRef` to the `MessageArea` shape the message carries. */
function toMessageArea(area: AreaRef): MessageArea {
  return {
    id: area.id,
    active: area.active,
    parent: area.parent ? toMessageArea(area.parent) : undefined,
    descs: area.descs?.map((desc) => ({ lang: desc.lang, name: desc.name })),
  };
}
