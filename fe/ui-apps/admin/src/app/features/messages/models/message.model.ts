import type { GeometryFeatureCollection } from '../../../shared/models/geometry.model';

export type MessageGeometryFeatureCollection = GeometryFeatureCollection;

export { emptyFeatureCollection } from '../../../shared/models/geometry.model';

export {
  KNOWN_MAIN_TYPES,
  MAIN_TYPES,
  type MainType,
} from '../../../shared/models/main-type.model';
import type { MainType } from '../../../shared/models/main-type.model';

export const MESSAGE_TYPES = [
  'PRELIMINARY_NOTICE',
  'TEMPORARY_NOTICE',
  'PERMANENT_NOTICE',
  'MISCELLANEOUS_NOTICE',
  'LOCAL_WARNING',
  'COASTAL_WARNING',
  'SUBAREA_WARNING',
  'NAVAREA_WARNING',
] as const;

export type MessageType = (typeof MESSAGE_TYPES)[number];

export const KNOWN_MESSAGE_TYPES: ReadonlySet<MessageType> = new Set(MESSAGE_TYPES);

/**
 * The message sub-types per main type, in UI order. The order is load-bearing:
 * it is both the checkbox/segment render order and the save-payload serialization order for
 * every type-restriction surface (editor type field, template execution, message-series and
 * promulgation-type admin) — keep all consumers on this single constant.
 */
export const MESSAGE_TYPES_BY_MAIN_TYPE: Readonly<Record<MainType, readonly MessageType[]>> = {
  NW: ['LOCAL_WARNING', 'COASTAL_WARNING', 'SUBAREA_WARNING', 'NAVAREA_WARNING'],
  NM: ['TEMPORARY_NOTICE', 'PRELIMINARY_NOTICE', 'PERMANENT_NOTICE', 'MISCELLANEOUS_NOTICE'],
};

export {
  KNOWN_MESSAGE_STATUSES,
  MESSAGE_STATUSES,
  type MessageStatus,
} from '../../../shared/models/message-status.model';
import type { MessageStatus } from '../../../shared/models/message-status.model';

/**
 * What a message is (the backend's `MessageKind`): an ordinary editorial `WARNING`, a
 * `CANCELLATION` warning Niord publishes by itself when a warning is cancelled, or a `BULLETIN`
 * (reserved; never made). Only a `WARNING` can be edited; the others take status changes only.
 */
export const MESSAGE_KINDS = ['WARNING', 'CANCELLATION', 'BULLETIN'] as const;

export type MessageKind = (typeof MESSAGE_KINDS)[number];

export const PUBLISHED_MESSAGE_STATUSES: ReadonlySet<MessageStatus> = new Set([
  'PUBLISHED',
  'EXPIRED',
  'CANCELLED',
]);

export interface DateInterval {
  readonly allDay?: boolean;
  readonly fromDate?: string;
  readonly toDate?: string;
}

/**
 * Synthetic area id used to group messages that have no area under a single
 * "General" heading. Produced by the message-list facade and consumed by the
 * area-heading / message-areas renderers — defined once here so producer and
 * consumers cannot drift.
 */
export const NO_AREA_SENTINEL_ID = -999999;

export interface MessageArea {
  readonly id: number;
  readonly active?: boolean;
  readonly parent?: MessageArea;
  readonly descs?: MessageAreaDescription[];
}

export interface MessageAreaDescription {
  readonly lang?: string;
  readonly name?: string;
}

export interface MessageChart {
  readonly chartNumber: string;
  readonly internationalNumber?: number;
}

export interface MessageDescription {
  readonly lang?: string;
  readonly title?: string;
  readonly subject?: string;
  readonly description?: string;
  readonly details?: string;
  readonly otherCategories?: string;
  readonly internalInfo?: string;
  readonly publication?: string;
  readonly internalPublication?: string;
  readonly source?: string;
  readonly vicinity?: string;
}

export interface MessagePart {
  readonly type?: string;
  // Server field name is `indexNo` (MessagePartVo) — using `index` would be silently dropped on
  // save, so part re-ordering would never persist.
  readonly indexNo?: number;
  readonly eventDates?: DateInterval[];
  readonly descs?: MessageDescription[];
  readonly geometry?: MessageGeometryFeatureCollection;
  readonly showTime?: boolean;
  readonly showCoordinates?: boolean;
  // Hides the DETAILS-part subject line in the rendered message (MessagePartVo.hideSubject).
  readonly hideSubject?: boolean;
}

export interface MessageReference {
  readonly messageId: string;
  readonly type?: ReferenceType;
  readonly descs?: MessageReferenceDescription[];
}

export interface MessageReferenceDescription {
  readonly lang?: string;
  readonly description?: string;
}

export const REFERENCE_TYPES = [
  'REFERENCE',
  'REPETITION',
  'REPETITION_NEW_TIME',
  'CANCELLATION',
  'UPDATE',
] as const;

export type ReferenceType = (typeof REFERENCE_TYPES)[number];

// '' = "don't display inline" (the backend round-trips the empty string, not an absent field).
export const ATTACHMENT_DISPLAYS = ['', 'ABOVE', 'BELOW', 'SEPARATE_PAGE'] as const;

export type AttachmentDisplay = (typeof ATTACHMENT_DISPLAYS)[number];

export interface MessageAttachmentDescription {
  readonly lang?: string;
  readonly caption?: string;
}

export interface MessageAttachment {
  readonly path?: string;
  readonly fileName?: string;
  readonly fileSize?: number;
  readonly type?: string;
  // CSS-unit strings ('50%', '200px') — a numeric type would silently drop the unit suffix.
  readonly width?: string;
  readonly height?: string;
  readonly display?: AttachmentDisplay;
  readonly descs?: MessageAttachmentDescription[];
}

export interface MessageSeries {
  readonly seriesId: string;
  readonly mainType?: MainType;
  readonly types?: string[];
  readonly name?: string;
  // Numbering config (present once re-hydrated from the domain series). Drives whether the
  // short-id / message-number is hand-editable — editable only when numberSequenceType is MANUAL.
  readonly numberSequenceType?: string;
  readonly shortFormat?: string;
}

export type PromulgationRequirement = 'MANDATORY' | 'OPTIONAL' | 'DEFAULT';

export interface PromulgationType {
  readonly typeId: string;
  readonly serviceId: string;
  readonly name: string;
  readonly requirement?: PromulgationRequirement;
  readonly language?: string;
  readonly priority?: number;
  // Present on the `promulgation-type/{typeId}` response (not on the entry embedded in a message):
  // the message types this promulgation type maps to. When exactly one, enabling the channel while
  // `message.type` is unset auto-deduces the type. Optional — embedded entries never carry it.
  readonly messageTypes?: string[];
}

export interface BasePromulgation {
  readonly id?: number;
  readonly type: PromulgationType;
  readonly promulgate: boolean;
  // Jackson polymorphic type discriminator — the promulgation subclass FQN the backend stamps in
  // (e.g. `org.niord.core.promulgation.vo.NavtexMessagePromulgationVo`). Carried opaquely so the
  // message round-trips back on save/adjust; UI code discriminates on `type.serviceId`, never this.
  readonly ['@class']?: string;
}

export interface CategoryDescription {
  readonly lang?: string;
  readonly name?: string;
}

export interface CategoryRef {
  readonly id: number;
  readonly mrn?: string;
  readonly active?: boolean;
  /** CATEGORY | TEMPLATE — present on the category-roots feed (drives the admin tree's template badge). */
  readonly type?: 'CATEGORY' | 'TEMPLATE';
  readonly descs?: readonly CategoryDescription[];
  readonly parent?: CategoryRef;
  /** Populated by /api/categories/category-roots (the tree-browser dialog). */
  readonly children?: readonly CategoryRef[];
}

export interface Message {
  readonly id: string;
  readonly shortId?: string;
  readonly mainType?: MainType;
  readonly type?: MessageType;
  readonly status?: MessageStatus;
  readonly messageSeries?: MessageSeries;
  readonly areas?: MessageArea[];
  readonly charts?: MessageChart[];
  // Category membership (MessageVo.categories). Additive/optional like the other read-only-safe
  // fields; the editor's category picker owns it (uses the shared CategoryRef shape).
  readonly categories?: readonly CategoryRef[];
  readonly descs?: MessageDescription[];
  readonly parts?: MessagePart[];
  readonly references?: MessageReference[];
  readonly attachments?: MessageAttachment[];
  readonly originalInformation?: boolean;
  // Manual message number (MessageVo.number) — hand-editable only for a MANUAL series with a
  // shortFormat; otherwise server-derived. Additive/optional so the read-only surfaces ignore it.
  readonly number?: number;
  // Charts panel scalar (MessageVo.horizontalDatum), e.g. "WGS-84".
  readonly horizontalDatum?: string;
  // Layout panel flag (MessageVo.separatePage) — start this message on a new PDF-report page.
  readonly separatePage?: boolean;
  readonly publishDateFrom?: string;
  readonly publishDateTo?: string;
  readonly followUpDate?: string;
  readonly created?: string;
  readonly updated?: string;
  readonly promulgations?: BasePromulgation[];

  // Editor-only fields. Additive + optional so the read-only message surfaces
  // (list/details/map) are unaffected. Populated by the `editable-message` endpoint.
  readonly editRepoPath?: string;
  readonly revision?: number;
  readonly thumbnailPath?: string;
  // Field-visibility map (fieldId -> shown). Backend-derived from the message's
  // area/category/series + the sysadmin `editorFieldsBase` setting; consume-only.
  readonly editorFields?: Record<string, boolean>;
  readonly autoTitle?: boolean;
  readonly unackComments?: number;
  // The message's kind. A list row does not carry it; a message without one is a WARNING.
  readonly kind?: MessageKind;
  // Cancelling this message makes Niord publish a cancellation warning for it at once.
  readonly autoCancellationWarning?: boolean;
}

/** A message Niord made by itself (not a WARNING): it takes status changes but no edits. */
export function isSystemMessage(message: Message | undefined): boolean {
  return message?.kind !== undefined && message.kind !== 'WARNING';
}

/** A cancellation warning, which Niord published when the warning it references was cancelled. */
export function isCancellationWarning(message: Message | undefined): boolean {
  return message?.kind === 'CANCELLATION';
}
