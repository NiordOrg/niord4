/**
 * What the site renders. Every field is `readonly`: one fetched message is shared by the three
 * views, the print sheet, the dialog and the map layers, so a component that wrote to it would
 * change what the others draw.
 *
 * The closed sets below are the values the site has words for. A field the backend may widen —
 * a message type, a status, a reference kind — stays a plain `string` on the model, because a
 * union with `| string` in it narrows nothing and documents less than the array does.
 */

/** The two families of message the site publishes. The order is the filter bar's order. */
export const MAIN_TYPES = ['NW', 'NM'] as const;
export type MainType = (typeof MAIN_TYPES)[number];

/**
 * The statuses the id badge names in a chip of its own, each with a `STATUS_<value>` key. A
 * published message is the ordinary case and wears no chip, so it is not one of them.
 */
export const MESSAGE_STATUSES = ['EXPIRED', 'CANCELLED'] as const;
export type MessageStatus = (typeof MESSAGE_STATUSES)[number];

/** Message types, each with a `TYPE_<value>` label key. */
export const MESSAGE_TYPES = [
  'PERMANENT_NOTICE',
  'TEMPORARY_NOTICE',
  'PRELIMINARY_NOTICE',
  'MISCELLANEOUS_NOTICE',
  'COASTAL_WARNING',
  'SUBAREA_WARNING',
  'NAVAREA_WARNING',
  'LOCAL_WARNING',
] as const;
export type MessageType = (typeof MESSAGE_TYPES)[number];

/** The two types whose badge carries a marker: a temporary or a preliminary notice. */
export const PROVISIONAL_TYPE_SUFFIXES = {
  TEMPORARY_NOTICE: '(T)',
  PRELIMINARY_NOTICE: '(P)',
} as const satisfies Readonly<Partial<Record<MessageType, string>>>;

/** Reference kinds that carry a `REF_<value>` label. The backend names others, drawn without one. */
export const MESSAGE_REFERENCE_TYPES = [
  'REPETITION',
  'REPETITION_NEW_TIME',
  'CANCELLATION',
  'UPDATE',
] as const;
export type MessageReferenceType = (typeof MESSAGE_REFERENCE_TYPES)[number];

const REFERENCE_LABEL_KEYS = {
  REPETITION: 'REF_REPETITION',
  REPETITION_NEW_TIME: 'REF_REPETITION_NEW_TIME',
  CANCELLATION: 'REF_CANCELLATION',
  UPDATE: 'REF_UPDATE',
} as const satisfies Readonly<Record<MessageReferenceType, string>>;

/**
 * The label key for a reference kind, or nothing when the backend named a kind the site has no
 * word for — which is drawn as the bare message id, as it always has been.
 */
export function referenceLabelKey(type: string | undefined): string | undefined {
  return type && Object.hasOwn(REFERENCE_LABEL_KEYS, type)
    ? REFERENCE_LABEL_KEYS[type as MessageReferenceType]
    : undefined;
}

/** Where an attachment is drawn relative to the message text. */
export const ATTACHMENT_DISPLAYS = ['ABOVE', 'BELOW'] as const;
export type AttachmentDisplay = (typeof ATTACHMENT_DISPLAYS)[number];

/** Part kinds, each with a `PART_TYPE_<value>` heading key. */
export const MESSAGE_PART_TYPES = [
  'DETAILS',
  'TIME',
  'POSITIONS',
  'NOTE',
  'PROHIBITION',
  'SIGNALS',
] as const;
export type MessagePartType = (typeof MESSAGE_PART_TYPES)[number];

/** One language's words for whatever carries it. */
export interface Desc {
  readonly lang: string;
  readonly title?: string;
  readonly description?: string;
  readonly subject?: string;
  readonly details?: string;
  readonly source?: string;
  readonly publication?: string;
  readonly caption?: string;
  readonly name?: string;
}

/** A node of the area hierarchy a message is filed under. */
export interface Area {
  /** The backend's own key, a number. An MRN is how a build-time configuration names an area. */
  readonly id: number;
  readonly mrn?: string;
  readonly parent?: Area;
  readonly descs?: readonly Desc[];
  /** The map framing a root area carries, merged in from the country configuration. */
  readonly latitude?: number;
  readonly longitude?: number;
  readonly zoomLevel?: number;
}

export interface Chart {
  readonly chartNumber: string;
  readonly internationalNumber?: number;
}

export interface Reference {
  readonly messageId: string;
  /** A raw wire value; `referenceLabelKey` knows the ones that have a label. */
  readonly type?: string;
  readonly descs?: readonly Desc[];
}

export interface Attachment {
  readonly path: string;
  readonly fileName: string;
  readonly type?: string;
  /** A raw wire value; `ATTACHMENT_DISPLAYS` are the two the card places by. */
  readonly display?: string;
  readonly width?: string;
  readonly height?: string;
  readonly descs?: readonly Desc[];
}

/** The GeoJSON a message part draws its geometry from. */
export interface GeoJsonFeature {
  readonly type: string;
  readonly geometry?: GeoJsonGeometry;
  readonly properties?: Readonly<Record<string, unknown>>;
}

export interface GeoJsonGeometry {
  readonly type: string;
  readonly coordinates?: unknown;
  readonly features?: readonly GeoJsonFeature[];
  readonly geometries?: readonly GeoJsonGeometry[];
}

export interface MessagePart {
  /** A raw wire value; `MESSAGE_PART_TYPES` are the ones with a heading of their own. */
  readonly type?: string;
  readonly geometry?: GeoJsonGeometry;
  readonly descs?: readonly Desc[];
  readonly hideSubject?: boolean;
}

/**
 * The series a message was numbered in. Nothing renders it; it is what the printed sheet is
 * narrowed to, so that the backend renders the series this site serves and no others.
 */
export interface MessageSeries {
  readonly seriesId?: string;
}

export interface Message {
  readonly id: string;
  readonly shortId?: string;
  readonly messageSeries?: MessageSeries;
  readonly mainType: MainType;
  /** A raw wire value; `MESSAGE_TYPES` are the ones with a label. */
  readonly type?: string;
  /** A raw wire value; `MESSAGE_STATUSES` are the two the badge names. */
  readonly status?: string;
  readonly areas?: readonly Area[];
  readonly charts?: readonly Chart[];
  readonly references?: readonly Reference[];
  readonly attachments?: readonly Attachment[];
  readonly parts?: readonly MessagePart[];
  readonly descs?: readonly Desc[];
  /** Epoch milliseconds, resolved at the service boundary from what the API declares a string. */
  readonly publishDateFrom?: number;
  readonly originalInformation?: boolean;
}
