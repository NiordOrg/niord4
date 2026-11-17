import { Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../../shared/icons/icon';
import {
  RowLinkAnchorDirective,
  RowLinkDirective,
} from '../../../../../shared/directives/row-link.directive';
import type {
  IssueTimeline,
  IssueTimelineRow,
} from '../../../services/api/publication-series-api.service';
import { issueNumberText, type SeriesNumbering } from './issue-number';
import { computedIssueStatusClass, computedIssueStatusKey } from './publication-status';

/**
 * The stretch of periods that produced nothing, named by its ends.
 *
 * "2019–2025" rather than "seven periods": the reader of a card is deciding
 * whether the silence matters, and which years are missing answers that where a
 * count does not. The strip beside it already carries the count.
 *
 * `undefined` unless the missing periods form ONE unbroken run. A span written
 * across a period that did produce an issue claims something untrue about it,
 * and the strip is the place where a scattered pattern can actually be read —
 * so the card says nothing rather than say it wrongly.
 *
 * Pure, and exported, because this is the part worth pinning with a spec: it can
 * only be pinned while it is a function of its inputs.
 */
export function gapSpanText(
  timeline: IssueTimeline | undefined,
  numbering: SeriesNumbering | undefined,
): string | undefined {
  // Gaps nobody looked for are not gaps. Every imported series is DRAFT, which
  // closes the gate, so this is the common case rather than an unusual one.
  const rows = timeline?.gapDetection?.enabled === true ? (timeline.rows ?? []) : [];
  const newest = rows.findIndex((row) => row.computedStatus === 'MISSING');
  if (newest === -1) {
    return undefined;
  }
  let oldest = newest;
  while (oldest + 1 < rows.length && rows[oldest + 1].computedStatus === 'MISSING') {
    oldest++;
  }
  if (rows.slice(oldest + 1).some((row) => row.computedStatus === 'MISSING')) {
    return undefined;
  }
  // Newest first, the order the server sends -- so the span reads from the far
  // end of the run back to the most recent period that produced nothing.
  const from = periodText(rows[oldest], numbering);
  const to = periodText(rows[newest], numbering);
  if (from === undefined || to === undefined) {
    return undefined;
  }
  return from === to ? from : `${from}–${to}`;
}

/** What one period is called: the number the series is numbered by, else its name. */
function periodText(
  row: IssueTimelineRow,
  numbering: SeriesNumbering | undefined,
): string | undefined {
  return issueNumberText(row, numbering) ?? row.label;
}

/**
 * One series' recent periods, as a strip of cells.
 *
 * A cell per PERIOD, not per issue: the cells that carry the most information
 * are the ones with no issue behind them, and a strip built from issues alone
 * cannot draw them. Newest first, the order the server sends and the order the
 * question is asked in — "what came out last, and did anything not come out".
 *
 * A real cell opens its issue. A missing period opens the series, which is where
 * the period can be created; an upcoming one opens nothing, because a period
 * still being worked toward is not a problem and offering an action on it
 * invites a duplicate of an issue that is about to exist.
 */
@Component({
  selector: 'app-issue-timeline',
  imports: [Icon, TranslocoPipe, RowLinkDirective, RowLinkAnchorDirective],
  templateUrl: './issue-timeline.html',
  host: { class: 'block' },
})
export class IssueTimelineStrip {
  /** The series' strip. Absent while it is in flight, or if it was not asked for. */
  readonly timeline = input<IssueTimeline | undefined>(undefined);

  /**
   * The series the cells belong to — the first segment of the address a cell
   * opens, which is a sibling route of the screen the strip is drawn on.
   *
   * Required: a cell that cannot say where it goes is not a link, and every
   * caller has the series in hand already.
   */
  readonly seriesId = input.required<string>();

  /**
   * The series the strip belongs to, which is what decides how a cell reads.
   *
   * Passed in rather than read off the rows: the strip's rows carry a week
   * whatever the publication is numbered by — an annual edition closes in some
   * ISO week too — so a cell that looked no further would print a week number
   * beside a yearly publication that has never been called by one.
   */
  readonly numbering = input<SeriesNumbering | undefined>(undefined);

  /** A real cell was chosen: open that issue. */
  readonly openIssue = output<string>();

  /** A missing period was chosen: open the series, where it can be created. */
  readonly openSeries = output<void>();

  protected readonly rows = computed<readonly IssueTimelineRow[]>(
    () => this.timeline()?.rows ?? [],
  );

  /**
   * How many of the periods on show have no issue.
   *
   * Counted off the cells rather than taken from a separate number, so the
   * warning and the cells it warns about can never disagree — and reported as
   * nothing at all when gaps were not looked for, because a strip nobody
   * examined must not read as a strip with nothing wrong with it.
   */
  protected readonly gapCount = computed(() =>
    this.timeline()?.gapDetection?.enabled === true
      ? this.rows().filter((row) => row.computedStatus === 'MISSING').length
      : 0,
  );

  /**
   * What to write in a cell: as short as the period allows.
   *
   * The number the SERIES is numbered by — a week where it has weeks, a year
   * where it has editions of a year — and the row's own name for a series
   * numbered by neither, whose cells stand for issues rather than for periods.
   * The full name is on the cell's label either way.
   */
  protected cellText(row: IssueTimelineRow): string {
    return issueNumberText(row, this.numbering()) ?? row.label ?? '—';
  }

  /** The whole sentence a cell is read out as, and its tooltip. */
  protected cellLabel(row: IssueTimelineRow): string {
    return row.label ?? this.cellText(row);
  }

  /**
   * Whether the cell can say how many messages went out in the period.
   *
   * A publication that resolves nothing carries no count, and a synthesized
   * period has nothing to count — neither of which is a count of zero, which
   * would claim the period resolved and came back empty.
   */
  protected hasCount(row: IssueTimelineRow): boolean {
    return row.memberCount != null;
  }

  protected statusKey(row: IssueTimelineRow): string {
    return computedIssueStatusKey(row.computedStatus);
  }

  protected statusClass(row: IssueTimelineRow): string {
    return computedIssueStatusClass(row.computedStatus);
  }
}
