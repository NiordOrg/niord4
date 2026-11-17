import {
  CUTOFF_SOURCES,
  type IntervalBoundSource,
  type PublicationIssue,
} from '../../../services/api/publication-series-api.service';

/**
 * Where an issue's dates came from, and when two of them are worth showing apart.
 *
 * A date on this screen is not automatically a record of something that happened.
 * Only a STAMPED bound was observed at a release; the other three were computed
 * from the cadence, read back out of the archive, or typed. A reader deciding
 * whether to trust a period needs to see which — and a reconstructed cut-off read
 * as evidence of when an issue actually closed is exactly the mistake the import
 * makes easy.
 *
 * Pure on purpose: no injector, no HTTP, so the vocabulary can be walked against
 * the translation bundles by a spec.
 */

export const BOUND_SOURCES: readonly IntervalBoundSource[] = [
  'STAMPED',
  'NOMINAL',
  'RECOVERED',
  'MANUAL',
];

/**
 * Every value a marker can be handed, from either vocabulary.
 *
 * The cut-off carries a vocabulary of its own, so a marker beside that date can
 * be handed a value the interval bounds never use. Both lists are here because a
 * value missing from this one renders as "unknown source" — which is the right
 * answer for a code this build has never heard of and the wrong one for a code
 * the server has been sending all along.
 *
 * Deduplicated because the two overlap: `MANUAL` means a human supplied the date
 * in both, and one key names it once.
 */
export const MARKABLE_SOURCES: readonly string[] = [
  ...new Set<string>([...BOUND_SOURCES, ...CUTOFF_SOURCES]),
];

export const BOUND_SOURCE_KEY_PREFIX = 'admin.publications.boundSource.';

/** The fallback code, for a value outside the vocabulary it was read against. */
export const UNKNOWN_BOUND_SOURCE = 'UNKNOWN';

export function boundSourceKey(source: string | undefined): string {
  return (
    BOUND_SOURCE_KEY_PREFIX +
    (source !== undefined && MARKABLE_SOURCES.includes(source) ? source : UNKNOWN_BOUND_SOURCE)
  );
}

/**
 * The sources that record an instant somebody actually observed, and so go unmarked.
 *
 * Both spellings of "stamped at a release": the interval bounds' `STAMPED` and
 * the cut-off's `STAMPED_AT_PUBLISH`. `STAMPED_MANUAL_TIME` is NOT one of them —
 * the stamp is real, but the instant was chosen rather than witnessed, and that
 * is exactly what a reader deciding whether to trust the date needs told.
 */
const UNMARKED_SOURCES: ReadonlySet<string> = new Set(['STAMPED', 'STAMPED_AT_PUBLISH']);

/**
 * Whether a bound is worth marking.
 *
 * A stamped bound carries no marker: it is the ordinary case, and marking every
 * date would make the mark mean nothing. An absent source is not marked either —
 * nothing is being claimed about it.
 */
export function isMarkedBound(source: string | undefined): boolean {
  return source !== undefined && !UNMARKED_SOURCES.has(source);
}

/**
 * Where the cut-off shown for an issue came from.
 *
 * The issue says so itself where it can: `cutoffSource` is the server's own
 * record of how the instant was arrived at, and it distinguishes cases nothing
 * here could reconstruct — a stamp at a chosen time from a stamp at the release,
 * one recovery stage from another. It is believed whenever it is present.
 *
 * The three answers below are the fallback for a response that does not carry it,
 * in priority order, because the cut-off is not one stored field: a reconstructed
 * one was recovered from the archive whatever else the row says; a stamped one is
 * a real release stamp; and with neither, the value on screen fell back to the end
 * of the content period, so it inherits that bound's provenance.
 */
export function cutoffBoundSource(
  issue: Pick<
    PublicationIssue,
    'cutoffReconstructed' | 'cutoffSource' | 'cutoffStampedAt' | 'intervalToSource'
  >,
): string | undefined {
  if (issue.cutoffSource != null && issue.cutoffSource !== '') {
    return issue.cutoffSource;
  }
  if (issue.cutoffReconstructed) {
    return 'RECOVERED';
  }
  if (issue.cutoffStampedAt != null) {
    return 'STAMPED';
  }
  return issue.intervalToSource;
}

/**
 * How far apart the cut-off and the release may sit before both are worth showing.
 *
 * A publish stamps the cut-off and then does the work, so the two normally differ
 * by seconds and printing both would be two spellings of one moment. A minute is
 * comfortably above that and comfortably below the case this exists for: a
 * release run deliberately later than the instant membership was frozen.
 */
export const PUBLISHED_AT_TOLERANCE_MS = 60_000;

/**
 * Whether the release moment says something the cut-off does not.
 *
 * A published issue with no stamped cut-off counts as apart: there is no recorded
 * instant for the release to coincide with, so hiding it would hide the only
 * thing actually known about when the issue went out.
 */
export function publishedApartFromCutoff(
  issue: Pick<PublicationIssue, 'publishedAt' | 'cutoffStampedAt'>,
): boolean {
  const publishedAt = issue.publishedAt;
  if (publishedAt == null) {
    return false;
  }
  const cutoff = issue.cutoffStampedAt;
  return cutoff == null || Math.abs(publishedAt - cutoff) > PUBLISHED_AT_TOLERANCE_MS;
}
