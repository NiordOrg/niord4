import type { Domain } from '../../../core/domain/models/domain.model';
import {
  KNOWN_MAIN_TYPES,
  KNOWN_MESSAGE_STATUSES,
  KNOWN_MESSAGE_TYPES,
  PUBLISHED_MESSAGE_STATUSES,
  type MainType,
  type MessageStatus,
  type MessageType,
} from '../models/message.model';
import {
  COMMENTS_VALUES,
  DATE_TYPES,
  emptyEnabledMap,
  emptySliceValues,
  FILTER_NAMES,
  KNOWN_COMMENTS_VALUES,
  KNOWN_DATE_TYPES,
  KNOWN_SORT_BY_VALUES,
  KNOWN_SORT_ORDERS,
  KNOWN_USER_TYPES,
  REFERENCE_LEVELS_DEFAULT,
  REFERENCE_LEVELS_MAX,
  REFERENCE_LEVELS_MIN,
  type CommentsValue,
  type DateType,
  type MapExtent,
  type MessagesFilterParams,
  type SortBy,
  type SortOrder,
  type UserType,
} from '../models/messages-filter-params.model';

export interface SerializerContext {
  readonly defaultSortBy: SortBy;
  readonly defaultSortOrder: SortOrder;
  readonly mapExtent: MapExtent | undefined;
  readonly isMap: boolean;
  readonly searchPublicMessages: boolean;
  readonly isAuthenticated: boolean;
}

export interface DeserializerContext {
  readonly isAuthenticated: boolean;
  resolveDomain(domainId: string): Domain | undefined;
}

export interface ParseResult {
  readonly params: MessagesFilterParams;
  readonly pendingDomainId?: string;
  /**
   * Map view parameters lifted out of the URL (zoom / center / extent). The facade
   * writes these back to `MapExtentService` so the map view recenters and the search
   * picks up the bbox on the next request-params recompute.
   */
  readonly mapExtent?: MapExtent;
}

export function defaultParams(
  defaults: { sortBy: SortBy; sortOrder: SortOrder } = {
    sortBy: 'AREA',
    sortOrder: 'ASC',
  },
): MessagesFilterParams {
  return {
    enabled: emptyEnabledMap(),
    ...emptySliceValues(),
    sortBy: defaults.sortBy,
    sortOrder: defaults.sortOrder,
  };
}

export function toQuery(params: MessagesFilterParams, ctx: SerializerContext): string {
  const out: [string, string][] = [];

  // Map (route-derived; from the MAP_EXTENT_TOKEN). Emitted only in map view —
  // otherwise stale extent would pollute non-map URLs.
  const map = ctx.isMap ? ctx.mapExtent : undefined;
  if (map?.zoom !== undefined) {
    out.push(['zoom', String(map.zoom)]);
  }
  if (map?.center && map.center.length === 2) {
    out.push(['lon', String(map.center[0])]);
    out.push(['lat', String(map.center[1])]);
  }
  if (map?.extent && map.extent.length === 4) {
    out.push(['minLon', String(map.extent[0])]);
    out.push(['minLat', String(map.extent[1])]);
    out.push(['maxLon', String(map.extent[2])]);
    out.push(['maxLat', String(map.extent[3])]);
  }

  // Domain
  if (params.enabled.domain && params.domain.domain) {
    out.push(['domain', params.domain.domain.domainId]);
  }

  // Text
  if (params.enabled.text && params.text.query.length > 0) {
    out.push(['query', params.text.query]);
  }

  // Type
  if (params.enabled.type) {
    if (params.type.mainType) {
      out.push(['mainType', params.type.mainType]);
    }
    for (const type of params.type.types) {
      out.push(['type', type]);
    }
    // Warnings are the backend's default kind, so only the other kind is written; Notices to
    // Mariners have no cancellation warnings.
    if (params.type.cancellationWarnings && params.type.mainType !== 'NM') {
      out.push(['kind', 'CANCELLATION']);
    }
  }

  // Status — apply public-mask
  if (params.enabled.status) {
    for (const status of params.status.statuses) {
      if (ctx.searchPublicMessages && !PUBLISHED_MESSAGE_STATUSES.has(status)) {
        continue;
      }
      out.push(['status', status]);
    }
  }

  // Tag — opaque IDs (URL contract unchanged: one `tag=<tagId>` per item)
  if (params.enabled.tag) {
    for (const tag of params.tag.tags) {
      out.push(['tag', tag.tagId]);
    }
  }

  // Publication
  if (params.enabled.publication) {
    for (const publication of params.publication.publications) {
      out.push(['publication', publication.publicationId]);
    }
  }

  // PromulgationType
  if (params.enabled.promulgationType) {
    for (const type of params.promulgationType.promulgationTypes) {
      out.push(['promulgationType', type.typeId]);
    }
  }

  // User — outbound gate: requires authenticated, enabled, non-empty username
  if (
    ctx.isAuthenticated &&
    params.enabled.user &&
    params.user.username &&
    params.user.username.length > 0
  ) {
    out.push(['username', params.user.username]);
    if (params.user.userType) {
      out.push(['userType', params.user.userType]);
    }
  }

  // Comments — apply mask: not authenticated → strip OWN/OWN_UNACK to omitted
  if (params.enabled.comments && params.comments.value.length > 0) {
    const masked = effectiveCommentsForOutbound(params.comments.value, ctx.isAuthenticated);
    if (masked.length > 0) {
      out.push(['comments', masked]);
    }
  }

  // Reference
  if (params.enabled.reference && params.reference.messageId) {
    out.push(['messageId', params.reference.messageId]);
    out.push(['referenceLevels', String(params.reference.referenceLevels)]);
  }

  // AtoN — opaque UIDs
  if (params.enabled.aton) {
    for (const uid of params.aton.atonUids) {
      out.push(['aton', uid]);
    }
  }

  // Chart — uses chartNumber
  if (params.enabled.chart) {
    for (const chart of params.chart.charts) {
      out.push(['chart', chart.chartNumber]);
    }
  }

  // MessageSeries — uses seriesId
  if (params.enabled.messageSeries) {
    for (const series of params.messageSeries.messageSeries) {
      if (series.seriesId) {
        out.push(['messageSeries', series.seriesId]);
      }
    }
  }

  // Area
  if (params.enabled.area) {
    for (const area of params.area.areas) {
      out.push(['area', String(area.id)]);
    }
  }

  // Category
  if (params.enabled.category) {
    for (const category of params.category.categories) {
      out.push(['category', String(category.id)]);
    }
  }

  // Date — dateType only emitted with at least one date
  if (params.enabled.date) {
    if (params.date.fromDate !== undefined) {
      out.push(['fromDate', String(params.date.fromDate)]);
    }
    if (params.date.toDate !== undefined) {
      out.push(['toDate', String(params.date.toDate)]);
    }
    if (params.date.fromDate !== undefined || params.date.toDate !== undefined) {
      out.push(['dateType', params.date.dateType]);
    }
  }

  // Sort — emit only when ≠ defaults
  if (params.sortBy !== ctx.defaultSortBy) {
    out.push(['sortBy', params.sortBy]);
  }
  if (params.sortOrder !== ctx.defaultSortOrder) {
    out.push(['sortOrder', params.sortOrder]);
  }

  // Deterministic alphabetic key order; preserve user-input order within repeated keys.
  out.sort(([a], [b]) => a.localeCompare(b));

  return out
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&');
}

function effectiveCommentsForOutbound(
  value: CommentsValue,
  isAuthenticated: boolean,
): CommentsValue {
  if (isAuthenticated) {
    return value;
  }
  if (value === 'OWN' || value === 'OWN_UNACK') {
    return '';
  }
  return value;
}

function parseSearchParams(input: string | URLSearchParams): URLSearchParams {
  return typeof input === 'string' ? new URLSearchParams(input) : input;
}

function parseAll(params: URLSearchParams, key: string): string[] {
  return params.getAll(key);
}

function parseInteger(raw: string | null): number | undefined {
  if (raw === null) {
    return undefined;
  }
  const value = Number.parseInt(raw, 10);
  return Number.isFinite(value) ? value : undefined;
}

function parseFloatOrUndefined(raw: string | null): number | undefined {
  if (raw === null) {
    return undefined;
  }
  const value = Number.parseFloat(raw);
  return Number.isFinite(value) ? value : undefined;
}

function parseMapExtent(params: URLSearchParams): MapExtent | undefined {
  const zoom = parseInteger(params.get('zoom'));
  const lon = parseFloatOrUndefined(params.get('lon'));
  const lat = parseFloatOrUndefined(params.get('lat'));
  const minLon = parseFloatOrUndefined(params.get('minLon'));
  const minLat = parseFloatOrUndefined(params.get('minLat'));
  const maxLon = parseFloatOrUndefined(params.get('maxLon'));
  const maxLat = parseFloatOrUndefined(params.get('maxLat'));
  const center: readonly [number, number] | undefined =
    lon !== undefined && lat !== undefined ? [lon, lat] : undefined;
  const extent: readonly [number, number, number, number] | undefined =
    minLon !== undefined && minLat !== undefined && maxLon !== undefined && maxLat !== undefined
      ? [minLon, minLat, maxLon, maxLat]
      : undefined;
  if (zoom === undefined && center === undefined && extent === undefined) {
    return undefined;
  }
  return { zoom, center, extent };
}

export function fromQuery(
  query: string | URLSearchParams,
  defaults: MessagesFilterParams,
  ctx: DeserializerContext,
): ParseResult {
  const params = parseSearchParams(query);
  const enabled = { ...defaults.enabled };
  const slices = emptySliceValues();
  let sortBy: SortBy = defaults.sortBy;
  let sortOrder: SortOrder = defaults.sortOrder;
  let pendingDomainId: string | undefined;

  // Domain
  const domainId = params.get('domain');
  if (domainId) {
    const resolved = ctx.resolveDomain(domainId);
    if (resolved) {
      slices.domain = { domain: resolved };
      enabled.domain = true;
    } else {
      pendingDomainId = domainId;
    }
  }

  // Text
  const query_ = params.get('query');
  if (query_ !== null) {
    slices.text = { query: query_ };
    enabled.text = true;
  }

  // Type — mainType + types
  const mainType = params.get('mainType');
  const typeValues = parseAll(params, 'type').filter((v): v is MessageType =>
    KNOWN_MESSAGE_TYPES.has(v as MessageType),
  );
  const isMainTypeKnown = mainType !== null && KNOWN_MAIN_TYPES.has(mainType as MainType);
  // Any other kind is ignored, like an unknown status; Notices to Mariners have no cancellation
  // warnings, so the kind is dropped with `mainType=NM`.
  const cancellationWarnings = params.get('kind') === 'CANCELLATION' && mainType !== 'NM';
  if (isMainTypeKnown || typeValues.length > 0 || cancellationWarnings) {
    slices.type = {
      mainType: isMainTypeKnown ? (mainType as MainType) : '',
      types: Array.from(new Set<MessageType>(typeValues)),
      cancellationWarnings,
    };
    enabled.type = true;
  }

  // Status
  const statusValues = parseAll(params, 'status').filter((v): v is MessageStatus =>
    KNOWN_MESSAGE_STATUSES.has(v as MessageStatus),
  );
  if (statusValues.length > 0) {
    slices.status = { statuses: Array.from(new Set<MessageStatus>(statusValues)) };
    enabled.status = true;
  }

  // Tag — opaque IDs in URL hydrate to placeholder MessageTag objects branded
  // with `_placeholder: true`. The field component (MessageTagsField) resolves
  // them via getTagsByIds() and overwrites the slice with fully-hydrated
  // objects. The placeholder fields (type/messageCount/locked) are never
  // user-visible: the chip only renders `name`/`tagId`, and the dialog reads
  // from its own API call. The brand lets callers detect un-hydrated stubs
  // without sniffing the values.
  const tagValues = parseAll(params, 'tag');
  if (tagValues.length > 0) {
    slices.tag = {
      tags: tagValues.map((tagId) => ({
        tagId,
        name: tagId,
        type: 'PRIVATE',
        messageCount: 0,
        locked: false,
        _placeholder: true,
      })),
    };
    enabled.tag = true;
  }

  // Publication — opaque IDs in 2b; entity hydration happens in the field component
  const publicationValues = parseAll(params, 'publication');
  if (publicationValues.length > 0) {
    slices.publication = {
      publications: publicationValues.map((id) => ({ publicationId: id })),
    };
    enabled.publication = true;
  }

  // PromulgationType
  const promulgationValues = parseAll(params, 'promulgationType');
  if (promulgationValues.length > 0) {
    slices.promulgationType = {
      promulgationTypes: promulgationValues.map((id) => ({ typeId: id })),
    };
    enabled.promulgationType = true;
  }

  // User — inbound gate: discard params entirely when not authenticated
  if (ctx.isAuthenticated) {
    const username = params.get('username');
    if (username && username.length > 0) {
      const userType = params.get('userType');
      slices.user = {
        username,
        userType:
          userType && KNOWN_USER_TYPES.has(userType as UserType)
            ? (userType as UserType)
            : undefined,
      };
      enabled.user = true;
    }
  }

  // Comments — accept OWN/OWN_UNACK even when not authenticated; mask happens at outbound
  const commentsRaw = params.get('comments');
  if (commentsRaw !== null && KNOWN_COMMENTS_VALUES.has(commentsRaw as CommentsValue)) {
    slices.comments = { value: commentsRaw as CommentsValue };
    enabled.comments = commentsRaw.length > 0;
  }

  // Reference
  const messageId = params.get('messageId');
  if (messageId) {
    const rawLevels = parseInteger(params.get('referenceLevels'));
    const clamped =
      rawLevels === undefined
        ? REFERENCE_LEVELS_DEFAULT
        : Math.min(Math.max(rawLevels, REFERENCE_LEVELS_MIN), REFERENCE_LEVELS_MAX);
    slices.reference = {
      messageId,
      referenceLevels: clamped,
    };
    enabled.reference = true;
  }

  // AtoN — opaque
  const atonValues = parseAll(params, 'aton');
  if (atonValues.length > 0) {
    slices.aton = { atonUids: atonValues };
    enabled.aton = true;
  }

  // Chart — chartNumber-keyed; opaque entity until hydrated
  const chartValues = parseAll(params, 'chart');
  if (chartValues.length > 0) {
    slices.chart = {
      charts: chartValues.map((chartNumber) => ({ chartNumber })),
    };
    enabled.chart = true;
  }

  // MessageSeries — seriesId-keyed; opaque entity until hydrated
  const seriesValues = parseAll(params, 'messageSeries');
  if (seriesValues.length > 0) {
    slices.messageSeries = {
      messageSeries: seriesValues.map((seriesId) => ({ seriesId })),
    };
    enabled.messageSeries = true;
  }

  // Area — numeric IDs
  const areaValues = parseAll(params, 'area')
    .map((raw) => parseInteger(raw))
    .filter((id): id is number => id !== undefined);
  if (areaValues.length > 0) {
    slices.area = {
      areas: areaValues.map((id) => ({ id })),
    };
    enabled.area = true;
  }

  // Category — numeric IDs
  const categoryValues = parseAll(params, 'category')
    .map((raw) => parseInteger(raw))
    .filter((id): id is number => id !== undefined);
  if (categoryValues.length > 0) {
    slices.category = {
      categories: categoryValues.map((id) => ({ id })),
    };
    enabled.category = true;
  }

  // Date
  const fromDate = parseInteger(params.get('fromDate'));
  const toDate = parseInteger(params.get('toDate'));
  const dateTypeRaw = params.get('dateType');
  const dateType =
    dateTypeRaw && KNOWN_DATE_TYPES.has(dateTypeRaw as DateType)
      ? (dateTypeRaw as DateType)
      : 'PUBLISH_DATE';
  if (fromDate !== undefined || toDate !== undefined) {
    slices.date = { dateType, fromDate, toDate };
    enabled.date = true;
  }

  // Sort
  const sortByRaw = params.get('sortBy');
  if (sortByRaw && KNOWN_SORT_BY_VALUES.has(sortByRaw as SortBy)) {
    sortBy = sortByRaw as SortBy;
  }
  const sortOrderRaw = params.get('sortOrder');
  if (sortOrderRaw && KNOWN_SORT_ORDERS.has(sortOrderRaw as SortOrder)) {
    sortOrder = sortOrderRaw as SortOrder;
  }

  return {
    params: {
      enabled,
      ...slices,
      sortBy,
      sortOrder,
    },
    pendingDomainId,
    mapExtent: parseMapExtent(params),
  };
}

export { COMMENTS_VALUES, DATE_TYPES, FILTER_NAMES };
