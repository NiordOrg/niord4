import type { MainType } from '../../../../../shared/models/main-type.model';
import { getZonedDateParts } from '../../../../../shared/utils/zoned-date';
import { MESSAGE_TYPES_BY_MAIN_TYPE } from '../../../../messages/models/message.model';
import type {
  AdminMessageSeries,
  NumberSequenceType,
} from '../../../models/admin-message-series.model';

/**
 * The year a `YEARLY` sequence is numbering right now, as `timeZone` reads it.
 *
 * The turn of the year is a fact about a clock: for the hours either side of midnight on
 * 31 December, two zones disagree about which year a number belongs to, and the one that
 * decides is the domain the numbers are issued in — not the one the administrator happens
 * to be sitting in.
 *
 * With no session domain there is no such clock. The year is only a column label and the
 * seed of an editable input here — never a value this screen stores on its own — so it
 * degrades to the administrator's own year rather than blanking a whole column; the
 * sequence itself is still keyed by whatever year is then sent.
 */
export function currentYearIn(timeZone: string | undefined): number {
  if (timeZone) {
    return getZonedDateParts(new Date(), timeZone).year;
  }
  // eslint-disable-next-line no-restricted-syntax -- no session domain means no domain clock to read; this year is a label and an editable seed, never a stored value (see above)
  return new Date().getFullYear();
}

/**
 * Pure numbering-rule gates shared by the series list and editor. Both are
 * truthiness checks, so a series without a sequence type (defensive — the wire
 * always carries one) counts as neither auto-numbered nor short-format-defining.
 */
type SeriesNumbering = Partial<Pick<AdminMessageSeries, 'numberSequenceType'>>;

/**
 * True when the series assigns sequence numbers automatically
 * (`YEARLY` / `CONTINUOUS`) — gates the next-number list column and the
 * editor's next-number row.
 */
export function numberAssigned(series: SeriesNumbering): boolean {
  const sequenceType = series.numberSequenceType;
  return sequenceType !== undefined && sequenceType !== 'MANUAL' && sequenceType !== 'NONE';
}

/**
 * True when the series defines a short-ID format (every sequence type except
 * `NONE`) — gates the editor's Short Format group and the save payload's
 * `shortFormat`.
 */
export function definesShortFormat(series: SeriesNumbering): boolean {
  const sequenceType = series.numberSequenceType;
  return sequenceType !== undefined && sequenceType !== 'NONE';
}

/** The restrict-type candidates per main type — the shared ordered map (= payload order). */
export const RESTRICT_TYPES = MESSAGE_TYPES_BY_MAIN_TYPE;

/** The editor's flat working values (restrict-type checks live beside, not inside, the form). */
export interface SeriesFormValue {
  readonly seriesId: string;
  readonly mainType: MainType;
  readonly numberSequenceType: NumberSequenceType;
  readonly shortFormat: string;
  readonly navtexFormat: string;
  readonly excludeFromMessageIndex: boolean;
}

/**
 * Normalizes the editor's working state into the save payload (the wire shape
 * drives message numbering — keep it bit-for-bit):
 * - `types` is rebuilt from the CURRENT main type's checks only; checks kept
 *   for the other main type are silently dropped. No checks ⇒ `[]`, which
 *   means UNRESTRICTED and is sent as-is.
 * - `shortFormat` is stripped when the series defines no short format (NONE);
 *   the working-model text is untouched — only the payload drops it.
 * - `navtexFormat` survives every sequence type (incl. NONE) and a non-NW main
 *   type; an empty string is omitted, matching the add-mode payload.
 * - `editorFields` is omitted when empty (the shipped area/category rule).
 */
export function buildSeriesPayload(
  value: SeriesFormValue,
  checks: Readonly<Record<MainType, Readonly<Record<string, boolean>>>>,
  editorFields: readonly string[],
): AdminMessageSeries {
  return {
    seriesId: value.seriesId,
    mainType: value.mainType,
    types: RESTRICT_TYPES[value.mainType].filter((type) => checks[value.mainType][type]),
    numberSequenceType: value.numberSequenceType,
    ...(definesShortFormat(value) ? { shortFormat: value.shortFormat } : {}),
    ...(value.navtexFormat !== '' ? { navtexFormat: value.navtexFormat } : {}),
    ...(editorFields.length > 0 ? { editorFields: [...editorFields] } : {}),
    excludeFromMessageIndex: value.excludeFromMessageIndex,
  };
}
