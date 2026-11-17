import { endOfDayInZone, startOfDayInZone } from '../../../shared/utils/zoned-date';
import type { DateInterval } from '../../messages/models/message.model';

/**
 * Force an interval's endpoints to the whole-day bounds of the day each end falls on in
 * `timeZone` — from → 00:00:00.000, to → 23:59:59.999. A day is a day in the domain's
 * zone, not in whichever zone the editing browser happens to sit.
 *
 * Shared by every surface that can mark an event date all-day, so a row created while a
 * template is executed spans exactly the same instants as the same row edited afterwards.
 */
export function forceAllDayBounds(interval: DateInterval, timeZone: string): DateInterval {
  return {
    ...interval,
    fromDate: dayEdgeIso(interval.fromDate, timeZone, 'start'),
    toDate: dayEdgeIso(interval.toDate, timeZone, 'end'),
  };
}

/** The first / last instant of the `timeZone` day `iso` falls on. Undefined stays undefined. */
function dayEdgeIso(
  iso: string | undefined,
  timeZone: string,
  edge: 'start' | 'end',
): string | undefined {
  if (!iso) {
    return iso;
  }
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return iso;
  }
  const bound =
    edge === 'start' ? startOfDayInZone(date, timeZone) : endOfDayInZone(date, timeZone);
  return bound.toISOString();
}
