import type { AreaRef } from '../../messages/models/messages-filter-params.model';

/**
 * One firing time-interval for an area — the wire shape of `FiringPeriodVo`. `fromDate`/`toDate`
 * are ISO datetimes (same shape as the messages `DateInterval`, so the shared interval formatter
 * consumes a `FiringPeriod` directly).
 *
 * A period is not always within one day: a period "until midnight" ends at 00:00 of the next day,
 * and one made through the API may cross midnight, so it is listed on both days. The editor keeps
 * a stored period's `id` and `legacyId` and sends them back: the backend updates a period with an
 * id, adds one without, and deletes a period of the day that the body lacks only when its id is in
 * `loadedPeriodIds`; it copies `legacyId` from the body, so a period sent without it loses it.
 */
export interface FiringPeriod {
  readonly id?: number;
  readonly legacyId?: string;
  readonly areaId?: number;
  readonly fromDate?: string;
  readonly toDate?: string;
}

/**
 * A firing area together with its firing periods for the queried date — the wire
 * shape of `FiringAreaPeriodsVo`. The view-model grouping (`areaHeading`) and the
 * inline-editor working copy live in component/facade signals, NOT on this model.
 */
export interface FiringAreaPeriods {
  readonly area: AreaRef;
  readonly firingPeriods: readonly FiringPeriod[];
  /**
   * In a save only: the ids of the area's periods of the day that the editor loaded before its
   * changes. The backend deletes a period of the day that `firingPeriods` leaves out only when its
   * id is here, so a period another tab added in the meantime is kept. The editor always sends it
   * (an empty list for a day it opened without periods); a body without it replaces the day.
   */
  readonly loadedPeriodIds?: readonly number[];
}
