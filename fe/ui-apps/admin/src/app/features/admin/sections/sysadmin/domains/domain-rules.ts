import type { MessageSeries } from '../../../../messages/models/message.model';
import type {
  AreaRef,
  CategoryRef,
} from '../../../../messages/models/messages-filter-params.model';
import type { AdminDomain } from '../../../models/admin-domain.model';

/** The flat working model behind the domain editor form. */
export interface DomainFormValue {
  readonly domainId: string;
  readonly active: boolean;
  readonly sortOrder: number | null;
  readonly name: string;
  readonly timeZone: string;
  readonly lat: number | null;
  readonly lon: number | null;
  readonly zoomLevel: number | null;
  readonly messageSortOrder: string;
  readonly color: string;
  readonly areas: readonly AreaRef[];
  readonly categories: readonly CategoryRef[];
  readonly messageSeries: readonly MessageSeries[];
  readonly publish: boolean;
  readonly atons: boolean;
  readonly templates: boolean;
}

/** The map-center zoom bounds. */
export const ZOOM_MIN = 3;
export const ZOOM_MAX = 15;

/**
 * The six fixed message-sort-order values (they travel on the wire verbatim),
 * labelled via i18n keys.
 */
export const MESSAGE_SORT_ORDER_OPTIONS: readonly { value: string; labelKey: string }[] = [
  { value: 'AREA ASC', labelKey: 'admin.domains.sortOption.areaAsc' },
  { value: 'AREA DESC', labelKey: 'admin.domains.sortOption.areaDesc' },
  { value: 'ID ASC', labelKey: 'admin.domains.sortOption.idAsc' },
  { value: 'ID DESC', labelKey: 'admin.domains.sortOption.idDesc' },
  { value: 'DATE ASC', labelKey: 'admin.domains.sortOption.dateAsc' },
  { value: 'DATE DESC', labelKey: 'admin.domains.sortOption.dateDesc' },
];

/**
 * Timezone options: the canonical `Intl` list, with a persisted-but-missing
 * zone (an alias or deprecated id) PREPENDED so it still displays and survives
 * an untouched save. A blank persisted value adds nothing — blank renders
 * empty and is not offerable.
 */
export function timeZoneOptions(persisted: string | undefined): readonly string[] {
  const zones = Intl.supportedValuesOf('timeZone');
  return persisted && !zones.includes(persisted) ? [persisted, ...zones] : zones;
}

/**
 * The save payload: scalars as the form holds them (blank optionals omitted —
 * the backend nulls blanks anyway), the pickers' rich objects reduced to the
 * minimal refs the backend re-resolves (`{id}` for areas/categories,
 * `{seriesId}` for series — stale desc payloads must never round-trip). The
 * backend-derived read-only fields (`inKeycloak`, `firingSchedule`) are never
 * sent, even though they ride along on the opened entity.
 */
export function buildDomainPayload(value: DomainFormValue): AdminDomain {
  return {
    domainId: value.domainId,
    active: value.active,
    sortOrder: value.sortOrder ?? undefined,
    name: blankToUndefined(value.name),
    timeZone: blankToUndefined(value.timeZone),
    lat: value.lat ?? undefined,
    lon: value.lon ?? undefined,
    zoomLevel: value.zoomLevel ?? undefined,
    messageSortOrder: blankToUndefined(value.messageSortOrder),
    color: blankToUndefined(value.color),
    areas: value.areas.map((area) => ({ id: area.id })),
    categories: value.categories.map((category) => ({ id: category.id })),
    messageSeries: value.messageSeries.map((series) => ({ seriesId: series.seriesId })),
    publish: value.publish,
    atons: value.atons,
    templates: value.templates,
  };
}

function blankToUndefined(value: string): string | undefined {
  return value === '' ? undefined : value;
}
