import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { boundSourceKey, isMarkedBound } from './publication-bounds';

/** The three things a reader has to be able to tell apart at a glance. */
export const BOUND_SOURCE_SHORT_KEY_PREFIX = 'admin.publications.boundSourceShort.';

/**
 * The short word for a provenance, from either vocabulary.
 *
 * Three kinds rather than thirteen codes: standing beside a date in a table
 * cell, what a reader needs is whether the value was computed, read back out of
 * the archive, or typed — the stage a recovery came from is the sentence behind
 * it, not the label. `NOT_RELEASED` is nominal because that is what the date
 * actually is: nobody released the issue, so the cut-off shown is the period's
 * nominal close.
 */
const SHORT_KINDS: Readonly<Record<string, 'NOMINAL' | 'RECOVERED' | 'MANUAL'>> = {
  NOMINAL: 'NOMINAL',
  NOMINAL_CLOSE: 'NOMINAL',
  NOT_RELEASED: 'NOMINAL',
  RECOVERED: 'RECOVERED',
  RECOVERED_FROM_UPDATED: 'RECOVERED',
  RECOVERED_FROM_NEXT_TAG: 'RECOVERED',
  RECOVERED_FROM_COVER: 'RECOVERED',
  PUBLIC_WINDOW: 'RECOVERED',
  MANUAL: 'MANUAL',
  STAMPED_MANUAL_TIME: 'MANUAL',
};

/**
 * The key for that short word, falling back to a neutral "derived".
 *
 * A code this build has never heard of still has to say that the date was not
 * witnessed — dropping the word would let an unrecognised source pass for a
 * release stamp, and printing the raw identifier beside a date reads as a
 * provenance rather than as an unknown one.
 */
export function boundSourceShortKey(source: string | undefined): string {
  return (
    BOUND_SOURCE_SHORT_KEY_PREFIX +
    (source === undefined ? 'UNKNOWN' : (SHORT_KINDS[source] ?? 'UNKNOWN'))
  );
}

/**
 * A muted word beside a date that was not stamped at a release.
 *
 * Renders NOTHING for a stamped bound, which is the ordinary case: a caveat on
 * every date would mean nothing, and the point is to make the derived ones read
 * as derived. A word rather than a glyph, because the question a reader is
 * asking of the cell — may I treat this date as evidence — is answered by
 * "reconstructed" and not by an icon they have to hover to decode. The full
 * sentence, naming the stage the value came from, stays in the `title` and on
 * the accessible name.
 */
@Component({
  selector: 'app-bound-source-marker',
  imports: [TranslocoPipe],
  template: `
    @if (marked()) {
      <span
        class="ml-1 text-xs whitespace-nowrap text-fg-subtle"
        [attr.data-bound-source]="source()"
        [attr.title]="key() | transloco"
        >{{ shortKey() | transloco }}</span
      >
      <!--
        The sentence behind the word, for a reader who cannot hover for the
        title. It says which stage the value came from, which is the part that
        decides whether the date is evidence.
      -->
      <span class="sr-only">{{ key() | transloco }}</span>
    }
  `,
  host: { class: 'contents' },
})
export class BoundSourceMarker {
  /** The bound's provenance. `STAMPED` and absent both render nothing. */
  readonly source = input<string | undefined>(undefined);

  protected readonly marked = computed(() => isMarkedBound(this.source()));
  protected readonly key = computed(() => boundSourceKey(this.source()));
  protected readonly shortKey = computed(() => boundSourceShortKey(this.source()));
}
