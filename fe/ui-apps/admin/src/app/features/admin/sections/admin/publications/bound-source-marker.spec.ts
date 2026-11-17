import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { TranslocoService } from '@jsverse/transloco';
import { describe, expect, it } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import { provideNiordIcons } from '../../../../../shared/icons/niord-icons';
import { provideTestTransloco } from '../../../../../shared/testing/transloco-testing';
import { BoundSourceMarker, boundSourceShortKey } from './bound-source-marker';
import { MARKABLE_SOURCES, UNKNOWN_BOUND_SOURCE } from './publication-bounds';

const source = signal<string | undefined>(undefined);

@Component({
  imports: [BoundSourceMarker],
  template: `<app-bound-source-marker [source]="source()" />`,
})
class Host {
  protected readonly source = source;
}

type TranslationNode = string | { [key: string]: TranslationNode };

function labelIn(bundle: object, key: string): string | undefined {
  let node: TranslationNode | undefined = bundle as TranslationNode;
  for (const segment of key.split('.')) {
    if (typeof node !== 'object' || node === null) {
      return undefined;
    }
    node = node[segment];
  }
  return typeof node === 'string' ? node : undefined;
}

/**
 * One fixture, re-rendered per source, and the WORD rather than the sentence.
 *
 * The mark is two nodes: the visible short word, and the full sentence for a
 * reader who cannot hover for the title. Re-rendering rather than rebuilding
 * lets one test walk several sources, which is the comparison most of them are
 * making.
 */
function setup() {
  TestBed.configureTestingModule({
    providers: [provideTestTransloco(), provideNiordIcons()],
  });
  TestBed.inject(TranslocoService).setTranslation(
    {
      'admin.publications.boundSource.NOMINAL': 'Nominal, from the cadence',
      'admin.publications.boundSource.STAMPED_MANUAL_TIME': 'Stamped at a chosen time',
      'admin.publications.boundSource.RECOVERED_FROM_NEXT_TAG': 'Reconstructed from the next issue',
      'admin.publications.boundSource.NOT_RELEASED': 'Never released here',
      'admin.publications.boundSource.UNKNOWN': 'Unknown source',
      'admin.publications.boundSourceShort.NOMINAL': 'nominal',
      'admin.publications.boundSourceShort.RECOVERED': 'reconstructed',
      'admin.publications.boundSourceShort.MANUAL': 'chosen',
      'admin.publications.boundSourceShort.UNKNOWN': 'derived',
    },
    'en',
  );

  const fixture = TestBed.createComponent(Host);
  const element = fixture.nativeElement as HTMLElement;
  const mark = (value: string | undefined) => {
    source.set(value);
    fixture.detectChanges();
    return element.querySelector<HTMLElement>('span[data-bound-source]');
  };
  return Object.assign(mark, { element });
}

describe('BoundSourceMarker', () => {
  /**
   * The ordinary case is silence, in both vocabularies.
   *
   * A date stamped when the release ran is what the screen is mostly made of, and
   * a mark on every one of them would mean nothing. An absent source is silent
   * for a different reason: nothing is being claimed about it.
   */
  it('renders nothing for a date stamped at its release, or none at all', () => {
    const mark = setup();

    expect(mark('STAMPED')).toBeNull();
    expect(mark('STAMPED_AT_PUBLISH')).toBeNull();
    expect(mark(undefined)).toBeNull();
  });

  /**
   * A word, not a glyph — and the sentence behind it stays reachable.
   *
   * The question a reader is asking of the cell is whether the date is evidence,
   * and "nominal" answers it where an icon they have to hover to decode does
   * not. The stage the value came from is the title, and is repeated for a
   * reader who cannot hover.
   */
  it('names a derived interval bound in one word, with the sentence behind it', () => {
    const mark = setup();
    const found = mark('NOMINAL');

    expect(found?.textContent?.trim()).toBe('nominal');
    expect(found?.getAttribute('title')).toBe('Nominal, from the cadence');
    expect(mark.element.querySelector('.sr-only')?.textContent).toBe('Nominal, from the cadence');
  });

  /**
   * A chosen instant IS marked, although it really was stamped.
   *
   * The stamp is genuine; what makes it worth showing is that the content closed
   * somewhere other than where the release happened, which on an annual list is
   * the difference between a year of content and a filing date.
   */
  it('marks a cut-off the admin chose the instant for', () => {
    const found = setup()('STAMPED_MANUAL_TIME');

    expect(found?.textContent?.trim()).toBe('chosen');
    expect(found?.getAttribute('title')).toBe('Stamped at a chosen time');
  });

  /**
   * Every recovery stage folds into ONE word, and keeps its own sentence.
   *
   * Standing beside a date in a table cell, "reconstructed" is what a reader
   * acts on; which stage it was recovered from is the detail behind it.
   */
  it('folds the recovery stages into one word', () => {
    const mark = setup();

    const recovered = mark('RECOVERED_FROM_NEXT_TAG');
    expect(recovered?.textContent?.trim()).toBe('reconstructed');
    expect(recovered?.getAttribute('title')).toBe('Reconstructed from the next issue');

    // Nobody released the issue, so the date shown is the period's nominal close.
    const never = mark('NOT_RELEASED');
    expect(never?.textContent?.trim()).toBe('nominal');
    expect(never?.getAttribute('title')).toBe('Never released here');
  });

  /**
   * A code this build has never heard of is still marked as derived.
   *
   * Dropping the word would let an unrecognised source pass for a release stamp,
   * and printing the raw identifier beside a date reads as a provenance.
   */
  it('marks an unrecognised source as derived rather than printing the code', () => {
    const found = setup()('RECOVERED_FROM_SOMETHING_NEW');

    expect(found?.textContent?.trim()).toBe('derived');
    expect(found?.getAttribute('title')).toBe('Unknown source');
  });
});

/**
 * The short vocabulary is closed, and every value in it has a label.
 *
 * A source the mapping does not know falls back to "derived", so the key space
 * is four entries however long the wire vocabulary grows — but all four have to
 * exist, or a table cell renders a key path beside a date.
 */
describe('boundSourceShortKey', () => {
  it('has a label for every source a marker can be handed', () => {
    const keys = [...MARKABLE_SOURCES, UNKNOWN_BOUND_SOURCE, 'SOMETHING_NEW'].map(
      boundSourceShortKey,
    );
    const missing = [...new Set(keys)].filter((key) => labelIn(en, key) === undefined);

    expect(missing, `keys missing in en.json:\n${missing.join('\n')}`).toEqual([]);
  });

  it('falls back to the derived label for a code it does not know', () => {
    expect(boundSourceShortKey('SOMETHING_NEW')).toBe(
      'admin.publications.boundSourceShort.UNKNOWN',
    );
    expect(boundSourceShortKey(undefined)).toBe('admin.publications.boundSourceShort.UNKNOWN');
  });
});
