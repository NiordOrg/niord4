import type { Geometry } from 'geojson';

import type { Domain } from '../../../core/domain/models/domain.model';
import type { MessageTag } from '../../../shared/models/message-tag.model';
import type {
  CategoryDescription,
  CategoryRef,
  MainType,
  MessageStatus,
  MessageType,
} from './message.model';
import type { MessageSeries } from './message.model';

// `CategoryRef`/`CategoryDescription` now live on `message.model.ts` (categories became a native
// `Message` field); re-exported here for the many filter/editor consumers that import them from
// this module.
export type { CategoryDescription, CategoryRef };

export type CommentsValue = '' | 'ANY' | 'OWN' | 'ANY_UNACK' | 'OWN_UNACK';

export const COMMENTS_VALUES = ['', 'ANY', 'OWN', 'ANY_UNACK', 'OWN_UNACK'] as const;
export const KNOWN_COMMENTS_VALUES: ReadonlySet<CommentsValue> = new Set(COMMENTS_VALUES);

export type UserType = 'CREATED_BY' | 'UPDATED_BY' | 'LAST_UPDATED_BY';

export const USER_TYPES = ['CREATED_BY', 'UPDATED_BY', 'LAST_UPDATED_BY'] as const;
export const KNOWN_USER_TYPES: ReadonlySet<UserType> = new Set(USER_TYPES);

export type DateType =
  | 'PUBLISH_DATE'
  | 'PUBLISH_FROM_DATE'
  | 'EVENT_DATE'
  | 'CREATED_DATE'
  | 'UPDATED_DATE';

export const DATE_TYPES = [
  'PUBLISH_DATE',
  'PUBLISH_FROM_DATE',
  'EVENT_DATE',
  'CREATED_DATE',
  'UPDATED_DATE',
] as const;
export const KNOWN_DATE_TYPES: ReadonlySet<DateType> = new Set(DATE_TYPES);

export type SortBy = 'AREA' | 'ID' | 'DATE' | 'PUBLISH_DATE' | 'FOLLOW_UP_DATE';

export const SORT_BY_VALUES = ['AREA', 'ID', 'DATE', 'PUBLISH_DATE', 'FOLLOW_UP_DATE'] as const;
export const KNOWN_SORT_BY_VALUES: ReadonlySet<SortBy> = new Set(SORT_BY_VALUES);

export type SortOrder = 'ASC' | 'DESC';

export const SORT_ORDERS = ['ASC', 'DESC'] as const;
export const KNOWN_SORT_ORDERS: ReadonlySet<SortOrder> = new Set(SORT_ORDERS);

export interface MapExtent {
  readonly zoom?: number;
  readonly center?: readonly [number, number];
  readonly extent?: readonly [number, number, number, number];
}

export interface ChartRef {
  readonly chartNumber: string;
  readonly internationalNumber?: number;
  readonly horizontalDatum?: string;
  readonly name?: string;
  readonly fullChartNumber?: string;
  /** Populated by /api/charts/search?geometry=true (consumed by the map charts overlay). */
  readonly geometry?: Geometry;
}

export interface AreaDescription {
  readonly lang?: string;
  readonly name?: string;
}

export interface AreaRef {
  readonly id: number;
  readonly mrn?: string;
  readonly active?: boolean;
  readonly descs?: readonly AreaDescription[];
  readonly parent?: AreaRef;
  /** Populated by /api/areas/area-roots (the tree-browser dialog). */
  readonly children?: readonly AreaRef[];
  /** Populated by /api/areas/search?geometry=true (consumed by the map areas overlay). */
  readonly geometry?: Geometry;
}

export interface PublicationDescription {
  readonly lang?: string;
  readonly title?: string;
  // The message-publication template for this language (e.g. `Publication no. ${parameters}`). Its
  // presence of a `${parameters}` placeholder drives the Insert-Publications dialog's parameter
  // field. Carried by both picker payloads but only relevant to that dialog.
  readonly messagePublicationFormat?: string;
  // A pre-set link for the publication desc (used by LINK-type publications).
  readonly link?: string;
}

/**
 * The three states a publication issue can be in.
 *
 * `OPEN` is being filled, `PUBLISHED` is out, `RETIRED` was taken down again. A
 * retired publication is still hydrated — a message citing one has to keep
 * rendering what it cited — but it is never offered for a new citation.
 */
export type PublicationStatus = 'OPEN' | 'PUBLISHED' | 'RETIRED';

/** What the picker offers by default: what is out, plus what is being worked on. */
export const PUBLICATION_PICKER_STATUSES: readonly PublicationStatus[] = ['PUBLISHED', 'OPEN'];

/** What a citation may point at: only a publication that has actually been released. */
export const PUBLICATION_CITATION_STATUSES: readonly PublicationStatus[] = ['PUBLISHED'];

export interface PublicationRef {
  readonly publicationId: string;
  readonly type?: string;
  readonly mainType?: string;
  /**
   * Populated for a publication issue; absent for a publication that only exists in the
   * legacy half of the catalogue, whose payload structurally cannot carry a status.
   */
  readonly status?: string;
  /** The series this issue belongs to — the picker's grouping key. */
  readonly publicationSeriesId?: string;
  /**
   * What that series is CALLED, per language.
   *
   * The id beside it is a database key — `weekly-ntm` — and naming a publication
   * by one asks the reader to know the catalogue by heart. Carried for every
   * language rather than resolved server-side, because the pickers send no
   * `lang`: a row named only in Danish must still say so to an English reader
   * instead of collapsing back to the key.
   *
   * Absent on a legacy-half row, whose payload has no slot for it.
   */
  readonly seriesNames?: Readonly<Record<string, string>>;
  /** Which citation channel the publication belongs to. */
  readonly messagePublication?: 'EXTERNAL' | 'INTERNAL';
  /** Epoch ms: when the issue became the publicly current one. */
  readonly publicFrom?: number;
  /**
   * Whether the publication is produced separately per language.
   *
   * Read by the per-message link override, which is offered only for a publication
   * sharing one link across every language. Absent on a legacy-half row, whose payload
   * does not carry the flag.
   */
  readonly languageSpecific?: boolean;
  readonly descs?: readonly PublicationDescription[];
}

export interface PromulgationTypeRef {
  readonly typeId: string;
  readonly name?: string;
  readonly priority?: number;
  readonly active?: boolean;
}

export interface DomainSliceValue {
  readonly domain: Domain | undefined;
}

export interface TextSliceValue {
  readonly query: string;
}

export interface TypeSliceValue {
  readonly mainType: '' | MainType;
  readonly types: readonly MessageType[];
  /**
   * List the cancellation warnings instead of the warnings. A search lists one kind at a time, and
   * only Navigational Warnings have cancellation warnings.
   */
  readonly cancellationWarnings: boolean;
}

export interface StatusSliceValue {
  readonly statuses: readonly MessageStatus[];
}

export interface TagSliceValue {
  readonly tags: readonly MessageTag[];
}

export interface PublicationSliceValue {
  readonly publications: readonly PublicationRef[];
}

export interface PromulgationTypeSliceValue {
  readonly promulgationTypes: readonly PromulgationTypeRef[];
}

export interface UserSliceValue {
  readonly username: string | undefined;
  readonly userType: UserType | undefined;
}

export interface CommentsSliceValue {
  readonly value: CommentsValue;
}

export interface ReferenceSliceValue {
  readonly messageId: string | undefined;
  readonly referenceLevels: number;
}

export interface AtonSliceValue {
  readonly atonUids: readonly string[];
}

export interface ChartSliceValue {
  readonly charts: readonly ChartRef[];
}

export interface MessageSeriesSliceValue {
  readonly messageSeries: readonly MessageSeries[];
}

export interface AreaSliceValue {
  readonly areas: readonly AreaRef[];
}

export interface CategorySliceValue {
  readonly categories: readonly CategoryRef[];
}

export interface DateSliceValue {
  readonly dateType: DateType;
  readonly fromDate: number | undefined;
  readonly toDate: number | undefined;
}

export const FILTER_NAMES = [
  'domain',
  'messageSeries',
  'text',
  'type',
  'status',
  'tag',
  'publication',
  'promulgationType',
  'user',
  'comments',
  'reference',
  'aton',
  'chart',
  'area',
  'category',
  'date',
] as const;

export type FilterName = (typeof FILTER_NAMES)[number];

export interface SliceValueMap {
  readonly domain: DomainSliceValue;
  readonly text: TextSliceValue;
  readonly type: TypeSliceValue;
  readonly status: StatusSliceValue;
  readonly tag: TagSliceValue;
  readonly publication: PublicationSliceValue;
  readonly promulgationType: PromulgationTypeSliceValue;
  readonly user: UserSliceValue;
  readonly comments: CommentsSliceValue;
  readonly reference: ReferenceSliceValue;
  readonly aton: AtonSliceValue;
  readonly chart: ChartSliceValue;
  readonly messageSeries: MessageSeriesSliceValue;
  readonly area: AreaSliceValue;
  readonly category: CategorySliceValue;
  readonly date: DateSliceValue;
}

export type SliceValueOf<K extends FilterName> = SliceValueMap[K];

export interface MessagesFilterParams {
  readonly enabled: Readonly<Record<FilterName, boolean>>;
  readonly domain: DomainSliceValue;
  readonly text: TextSliceValue;
  readonly type: TypeSliceValue;
  readonly status: StatusSliceValue;
  readonly tag: TagSliceValue;
  readonly publication: PublicationSliceValue;
  readonly promulgationType: PromulgationTypeSliceValue;
  readonly user: UserSliceValue;
  readonly comments: CommentsSliceValue;
  readonly reference: ReferenceSliceValue;
  readonly aton: AtonSliceValue;
  readonly chart: ChartSliceValue;
  readonly messageSeries: MessageSeriesSliceValue;
  readonly area: AreaSliceValue;
  readonly category: CategorySliceValue;
  readonly date: DateSliceValue;
  readonly sortBy: SortBy;
  readonly sortOrder: SortOrder;
}

export const REFERENCE_LEVELS_DEFAULT = 1;
export const REFERENCE_LEVELS_MIN = 0;
export const REFERENCE_LEVELS_MAX = 5;

export function emptyEnabledMap(): Record<FilterName, boolean> {
  return {
    domain: false,
    messageSeries: false,
    text: false,
    type: false,
    status: false,
    tag: false,
    publication: false,
    promulgationType: false,
    user: false,
    comments: false,
    reference: false,
    aton: false,
    chart: false,
    area: false,
    category: false,
    date: false,
  };
}

export function emptySliceValues(): {
  [K in FilterName]: SliceValueMap[K];
} {
  return {
    domain: { domain: undefined },
    messageSeries: { messageSeries: [] },
    text: { query: '' },
    type: { mainType: '', types: [], cancellationWarnings: false },
    status: { statuses: [] },
    tag: { tags: [] },
    publication: { publications: [] },
    promulgationType: { promulgationTypes: [] },
    user: { username: undefined, userType: undefined },
    comments: { value: '' },
    reference: { messageId: undefined, referenceLevels: REFERENCE_LEVELS_DEFAULT },
    aton: { atonUids: [] },
    chart: { charts: [] },
    area: { areas: [] },
    category: { categories: [] },
    date: { dateType: 'PUBLISH_DATE', fromDate: undefined, toDate: undefined },
  };
}
