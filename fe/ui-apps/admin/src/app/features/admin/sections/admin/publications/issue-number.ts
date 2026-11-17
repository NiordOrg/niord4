import type {
  NumberingScheme,
  SeriesCadence,
} from '../../../services/api/publication-series-api.service';

/**
 * What an issue is called by its numbers, and which of them mean anything.
 *
 * Every issue carries a week, a week-year and, where the scheme has one, an
 * edition — all of them derived from the same cut-off, and all of them present
 * whether or not the publication is numbered by any of them. An annual edition
 * closes in some ISO week too, so it has a week number; that number names
 * nothing about the publication and must never reach a screen.
 *
 * So the SERIES decides which number to show, and the issue only supplies it. A
 * caller that branches on `week != null` instead is asking the issue a question
 * the issue cannot answer, and it gets the answer wrong in both directions: it
 * prints a meaningless week beside an annual, and it falls through to a name for
 * a weekly issue whose numbers simply had not been filled in yet.
 *
 * Pure on purpose: no injector, no signals. It is the part worth pinning with a
 * spec, and it can only be pinned while it is a function of its inputs.
 */

/** As much of a series as naming an issue needs. */
export interface SeriesNumbering {
  readonly cadence?: SeriesCadence;
  readonly numberingScheme?: NumberingScheme;
}

/** As much of an issue as naming it needs. Real rows and synthesized ones both. */
export interface IssueNumbers {
  readonly week?: number;
  /** The week a multi-week issue runs TO. Absent on an ordinary single-week one. */
  readonly weekTo?: number;
  readonly year?: number;
  readonly edition?: string;
}

/** Which of an issue's numbers this series is numbered by. */
type NumberKind = 'WEEK' | 'YEAR' | 'NONE';

/**
 * The scheme wins over the cadence, and the cadence only fills a gap.
 *
 * The scheme is the answer to exactly this question and the cadence is the
 * answer to a different one — how often the publication comes out. They agree
 * for every series that has both, and where they do not, the scheme is what an
 * admin chose deliberately: `NONE` on a weekly series means "this series does
 * not number its issues", and reading the cadence anyway would overrule it.
 *
 * A series with no scheme is the imported estate, whose cadence is all there is.
 */
function numberKindOf(series: SeriesNumbering | undefined): NumberKind {
  switch (series?.numberingScheme) {
    case 'ISO_WEEK_YEAR':
      return 'WEEK';
    case 'YEAR_EDITION':
      return 'YEAR';
    case undefined:
      break;
    default:
      // MONTH_YEAR, EDITION_SEQUENCE and NONE are numbered by something no row
      // carries as a number. They are named, not numbered.
      return 'NONE';
  }
  switch (series?.cadence) {
    case 'WEEKLY':
      return 'WEEK';
    case 'YEARLY':
      return 'YEAR';
    default:
      return 'NONE';
  }
}

/**
 * The short number an issue is known by, or `undefined` where it has none.
 *
 * `undefined` rather than a fallback of its own: what to show instead is the
 * caller's question — a strip cell falls back to the period's name, and a table
 * that already prints the name would rather print nothing twice.
 */
export function issueNumberText(
  issue: IssueNumbers | undefined,
  series: SeriesNumbering | undefined,
): string | undefined {
  if (!issue) {
    return undefined;
  }
  switch (numberKindOf(series)) {
    case 'WEEK':
      return weekText(issue);
    case 'YEAR':
      return yearText(issue);
    case 'NONE':
      return undefined;
  }
}

/**
 * "27", or "52+1" for an issue that closed two periods at once.
 *
 * The double week is the holiday issue, and the `+` form is what the publication
 * has always been called — the pair is one issue covering both weeks, not a
 * range from one to the other, which is why it is not written with a dash.
 */
function weekText(issue: IssueNumbers): string | undefined {
  if (issue.week == null) {
    return undefined;
  }
  return issue.weekTo == null ? String(issue.week) : `${issue.week}+${issue.weekTo}`;
}

/**
 * "2026", or "2026 (2)" where the year holds more than one edition.
 *
 * The edition is left off the ordinary case because it says nothing there: a
 * publication that comes out once a year is fully named by the year, and "(1)"
 * beside it invites the reader to look for a second edition that does not exist.
 */
function yearText(issue: IssueNumbers): string | undefined {
  if (issue.year == null) {
    return undefined;
  }
  const edition = laterEdition(issue.edition);
  return edition === undefined ? String(issue.year) : `${issue.year} (${edition})`;
}

/**
 * The edition, where it is not the year's first one.
 *
 * An edition somebody wrote as words cannot be compared to the first, so it is
 * shown: it was typed to distinguish this issue from another, and hiding it
 * would leave two cells in the same year reading identically.
 *
 * Exported because the strip is not the only place that asks. The issue header
 * marks an edition that is not the first on EVERY series, whatever numbers that
 * series is named by — a weekly whose week 41 came out twice is the one row in
 * the archive where the number is the whole point — and "the first edition shows
 * nothing" has to be one rule in one place or the two surfaces will disagree.
 */
export function laterEdition(edition: string | undefined): string | undefined {
  const text = edition?.trim();
  if (!text) {
    return undefined;
  }
  const numeric = Number(text);
  return Number.isFinite(numeric) && numeric <= 1 ? undefined : text;
}
