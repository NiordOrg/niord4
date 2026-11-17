import { describe, expect, it } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import {
  boundSourceKey,
  cutoffBoundSource,
  isMarkedBound,
  MARKABLE_SOURCES,
  publishedApartFromCutoff,
  PUBLISHED_AT_TOLERANCE_MS,
  UNKNOWN_BOUND_SOURCE,
} from './publication-bounds';

/**
 * The provenance of an issue's dates, and when two of them are worth separating.
 *
 * Both halves are quiet failures. A missing bound-source label renders as the key
 * path in the middle of a date; a mis-set tolerance either prints one instant
 * twice or hides the fact that a release ran hours after membership was frozen.
 */

type TranslationNode = string | { readonly [key: string]: TranslationNode };

function labelIn(bundle: object, key: string): string | undefined {
  let node: TranslationNode | undefined = bundle as TranslationNode;
  for (const segment of key.split('.')) {
    if (node === undefined || typeof node === 'string') {
      return undefined;
    }
    node = node[segment];
  }
  return typeof node === 'string' ? node : undefined;
}

describe('bound source labels', () => {
  const keys = [...MARKABLE_SOURCES, UNKNOWN_BOUND_SOURCE].map(boundSourceKey);

  it('has an English label for every bound source', () => {
    const missing = keys.filter((key) => labelIn(en, key) === undefined);
    expect(missing, `keys missing in en.json:\n${missing.join('\n')}`).toEqual([]);
  });

  /** A value outside the vocabulary is named as unknown, never rendered raw. */
  it('falls back rather than building a key from an unexpected value', () => {
    expect(boundSourceKey('IMPORTED')).toBe(
      `admin.publications.boundSource.${UNKNOWN_BOUND_SOURCE}`,
    );
    expect(boundSourceKey(undefined)).toBe(
      `admin.publications.boundSource.${UNKNOWN_BOUND_SOURCE}`,
    );
  });
});

describe('which bounds are marked', () => {
  /** The ordinary case carries no mark: a mark on every date would mean nothing. */
  it('leaves a stamped bound unmarked, in either vocabulary', () => {
    expect(isMarkedBound('STAMPED')).toBe(false);
    expect(isMarkedBound('STAMPED_AT_PUBLISH')).toBe(false);
  });

  it('marks the three derived sources', () => {
    expect(isMarkedBound('NOMINAL')).toBe(true);
    expect(isMarkedBound('RECOVERED')).toBe(true);
    expect(isMarkedBound('MANUAL')).toBe(true);
  });

  /**
   * Every answer an imported issue can carry is marked, whatever it recovered from.
   *
   * None of them witnessed a release in this application: a stage that read a
   * row's last edit or the next issue's tag reconstructed the instant, and a
   * nominal close or a window boundary is not an instant anybody pressed anything
   * at. `NOT_RELEASED` is marked for the opposite reason — there is no cut-off at
   * all, so the date beside it is the period end wearing the wrong name.
   */
  it('marks every recovered and never-released cut-off', () => {
    expect(isMarkedBound('RECOVERED_FROM_UPDATED')).toBe(true);
    expect(isMarkedBound('RECOVERED_FROM_NEXT_TAG')).toBe(true);
    expect(isMarkedBound('RECOVERED_FROM_COVER')).toBe(true);
    expect(isMarkedBound('NOMINAL_CLOSE')).toBe(true);
    expect(isMarkedBound('PUBLIC_WINDOW')).toBe(true);
    expect(isMarkedBound('NOT_RELEASED')).toBe(true);
  });

  /**
   * A chosen instant IS marked, although it was really stamped.
   *
   * The stamp is genuine; what makes it worth showing is that the content closes
   * somewhere other than where the release happened — which on an annual list is
   * the difference between a year of content and a filing date.
   */
  it('marks a cut-off the admin chose the instant for', () => {
    expect(isMarkedBound('STAMPED_MANUAL_TIME')).toBe(true);
  });

  /** Nothing is claimed about an absent source, so nothing is marked. */
  it('leaves an absent source unmarked', () => {
    expect(isMarkedBound(undefined)).toBe(false);
  });
});

describe('where the cut-off came from', () => {
  /**
   * The server's own answer outranks every derivation below it.
   *
   * `cutoffSource` separates cases nothing here could: a stamp at an instant
   * somebody chose from a stamp at the release, one recovery stage from another.
   * Deriving over the top of it would throw that away and report the coarser
   * answer the flags happen to imply.
   */
  it('believes the recorded source whatever the flags would have derived', () => {
    expect(
      cutoffBoundSource({
        cutoffSource: 'STAMPED_MANUAL_TIME',
        cutoffStampedAt: 1000,
        intervalToSource: 'NOMINAL',
      }),
    ).toBe('STAMPED_MANUAL_TIME');
  });

  /** A reconstructed issue names the stage it recovered from, not just "recovered". */
  it('reports the recovery stage on an imported issue', () => {
    expect(
      cutoffBoundSource({
        cutoffSource: 'RECOVERED_FROM_NEXT_TAG',
        cutoffReconstructed: true,
        cutoffStampedAt: 1000,
      }),
    ).toBe('RECOVERED_FROM_NEXT_TAG');
  });

  /** A value this build has never heard of is passed on, and named unknown downstream. */
  it('passes on a source outside the vocabulary rather than deriving one', () => {
    expect(cutoffBoundSource({ cutoffSource: 'RECOVERED_FROM_SOMETHING_NEW' })).toBe(
      'RECOVERED_FROM_SOMETHING_NEW',
    );
  });

  /** Reconstructed wins outright: it says the value was recovered, not observed. */
  it('reports a reconstructed cut-off as recovered whatever else is set', () => {
    expect(
      cutoffBoundSource({
        cutoffReconstructed: true,
        cutoffStampedAt: 1000,
        intervalToSource: 'NOMINAL',
      }),
    ).toBe('RECOVERED');
  });

  it('reports a real stamp as stamped', () => {
    expect(cutoffBoundSource({ cutoffStampedAt: 1000 })).toBe('STAMPED');
  });

  /** With no stamp the screen shows the period end, so it inherits that bound. */
  it('inherits the period end when there is no stamp', () => {
    expect(cutoffBoundSource({ intervalToSource: 'MANUAL' })).toBe('MANUAL');
    expect(cutoffBoundSource({})).toBeUndefined();
  });

  /** An empty string is a field the server did not fill, not a source. */
  it('derives when the recorded source is blank', () => {
    expect(cutoffBoundSource({ cutoffSource: '', cutoffStampedAt: 1000 })).toBe('STAMPED');
  });
});

describe('cut-off versus release moment', () => {
  /** Seconds apart is one moment spelled twice. */
  it('treats a release within the tolerance as the cut-off itself', () => {
    expect(publishedApartFromCutoff({ cutoffStampedAt: 1_000_000, publishedAt: 1_000_000 })).toBe(
      false,
    );
    expect(
      publishedApartFromCutoff({
        cutoffStampedAt: 1_000_000,
        publishedAt: 1_000_000 + PUBLISHED_AT_TOLERANCE_MS,
      }),
    ).toBe(false);
  });

  it('separates a release run later than the freeze', () => {
    expect(
      publishedApartFromCutoff({
        cutoffStampedAt: 1_000_000,
        publishedAt: 1_000_000 + PUBLISHED_AT_TOLERANCE_MS + 1,
      }),
    ).toBe(true);
  });

  /** Symmetric: a clock skew the other way is still a difference. */
  it('separates a release stamped before the cut-off', () => {
    expect(publishedApartFromCutoff({ cutoffStampedAt: 1_000_000, publishedAt: 500_000 })).toBe(
      true,
    );
  });

  /**
   * With no stamped cut-off the release moment is the only thing known.
   *
   * Hiding it there would hide the sole record of when the issue went out, which
   * is exactly the imported case this exists to make visible.
   */
  it('shows the release moment when there is no cut-off to coincide with', () => {
    expect(publishedApartFromCutoff({ publishedAt: 1_000_000 })).toBe(true);
  });

  /** An imported issue that nobody released here says nothing about a moment. */
  it('says nothing when the issue was never released here', () => {
    expect(publishedApartFromCutoff({ cutoffStampedAt: 1_000_000 })).toBe(false);
    expect(publishedApartFromCutoff({})).toBe(false);
  });
});
