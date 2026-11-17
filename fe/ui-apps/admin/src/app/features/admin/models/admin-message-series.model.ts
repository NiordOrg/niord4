import type { MainType } from '../../../shared/models/main-type.model';

/**
 * How a message series assigns sequence numbers:
 * - `YEARLY` — the number sequence resets to 1 every year
 * - `CONTINUOUS` — the number sequence never resets
 * - `MANUAL` — the user specifies the short ID by hand
 * - `NONE` — no short ID is assigned
 */
export const NUMBER_SEQUENCE_TYPES = ['YEARLY', 'CONTINUOUS', 'MANUAL', 'NONE'] as const;

export type NumberSequenceType = (typeof NUMBER_SEQUENCE_TYPES)[number];

/**
 * The writable admin view of a message series (the backend's
 * `SystemMessageSeriesVo`) — distinct from the messages read-side
 * `MessageSeries` picker shape. Nothing on the entity is localized.
 */
export interface AdminMessageSeries {
  /** Free-form string primary key — immutable once created. */
  readonly seriesId: string;
  readonly mainType: MainType;
  /**
   * Restricts the series to a subset of the mainType's message types. Empty
   * (or absent) means UNRESTRICTED — all types permitted; the empty array is
   * sent as-is, never treated as a restriction.
   */
  readonly types?: readonly string[];
  readonly numberSequenceType: NumberSequenceType;
  /** Short-ID token format — absent when the sequence type is `NONE`. */
  readonly shortFormat?: string;
  /** NAVTEX preamble token format — NW series only. */
  readonly navtexFormat?: string;
  /**
   * The server-computed next number for the current year — present on the
   * list payload (`?messageNumbers=true`) for auto-numbered series. Read-only:
   * it is never edited through the series save; corrections go through the
   * dedicated next-number endpoints.
   */
  readonly nextMessageNumber?: number;
  /** Additional editor fields force-enabled for messages of the series. */
  readonly editorFields?: readonly string[];
  /** Excludes the series' messages from the free-text search index. */
  readonly excludeFromMessageIndex?: boolean;
}
