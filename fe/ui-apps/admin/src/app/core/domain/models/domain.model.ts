import type { MainType } from '../../../shared/models/main-type.model';

export const DOMAIN_SORT_FIELDS = ['AREA', 'ID', 'DATE'] as const;
export type DomainMessageSortField = (typeof DOMAIN_SORT_FIELDS)[number];

export const DOMAIN_SORT_ORDERS = ['ASC', 'DESC'] as const;
export type DomainSortOrder = (typeof DOMAIN_SORT_ORDERS)[number];

export interface DomainMessageSeriesRef {
  readonly seriesId: string;
  readonly mainType?: MainType;
  readonly types?: readonly string[];
  readonly name?: string;
  // The domain VO carries the series' numbering config; the editor uses it to decide whether
  // the short-id / message-number is hand-editable (only when numberSequenceType === 'MANUAL').
  readonly numberSequenceType?: string;
  readonly shortFormat?: string;
}

export interface DomainAreaRef {
  readonly id: number;
}

export interface DomainCategoryRef {
  readonly id: number;
}

export interface Domain {
  readonly domainId: string;
  readonly name?: string;
  readonly active?: boolean;
  readonly sortOrder?: number;
  readonly timeZone?: string;
  readonly messageSortOrder?: string;
  readonly color?: string;
  readonly lat?: number;
  readonly lon?: number;
  readonly zoomLevel?: number;
  readonly messageSeries?: readonly DomainMessageSeriesRef[];
  readonly areas?: readonly DomainAreaRef[];
  readonly categories?: readonly DomainCategoryRef[];
  readonly publish?: boolean;
  readonly atons?: boolean;
  readonly templates?: boolean;
  readonly firingSchedule?: boolean;
}

export interface DomainCapabilities {
  readonly atons: boolean;
  readonly templates: boolean;
  readonly publish: boolean;
  readonly firingSchedule: boolean;
}

export interface DomainMapDefaults {
  readonly center?: readonly [number, number];
  readonly zoom?: number;
}

export const DEFAULT_SORT_BY: DomainMessageSortField = 'AREA';
export const DEFAULT_SORT_ORDER: DomainSortOrder = 'ASC';

export function parseMessageSortOrder(raw: string | undefined): {
  readonly sortBy: DomainMessageSortField;
  readonly sortOrder: DomainSortOrder;
} {
  if (!raw) {
    return { sortBy: DEFAULT_SORT_BY, sortOrder: DEFAULT_SORT_ORDER };
  }
  const upper = raw.toUpperCase();
  const sortBy = DOMAIN_SORT_FIELDS.find((field) => upper.startsWith(field)) ?? DEFAULT_SORT_BY;
  const sortOrder = DOMAIN_SORT_ORDERS.find((order) => upper.endsWith(order)) ?? DEFAULT_SORT_ORDER;
  return { sortBy, sortOrder };
}
