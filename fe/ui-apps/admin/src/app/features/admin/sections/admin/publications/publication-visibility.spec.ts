import { describe, expect, it } from 'vitest';

import en from '../../../../../../../../../languages/en/i18n/admin/en.json';
import {
  oneOffStatus,
  oneOffStatusClass,
  oneOffStatusKey,
  publicationVisibility,
  type PublicationVisibilityFacts,
} from './publication-visibility';

/** A formatter that shows what was handed to it, so the params can be asserted on. */
const fmt = (value: number): string => `d${value}`;

const NOW = Date.UTC(2026, 8, 9, 12, 0);
const LAST_YEAR = Date.UTC(2025, 0, 1);
const NEXT_YEAR = Date.UTC(2027, 0, 1);

function facts(over: Partial<PublicationVisibilityFacts> = {}): PublicationVisibilityFacts {
  return { issueStatus: 'PUBLISHED', now: NOW, ...over };
}

/**
 * The three facts, and the one line they add up to.
 *
 * There is no "active". A publication's public standing is a status, a period
 * and its category's own publish flag, and every one of them is invisible from
 * the other two — so the rules that fold them into a sentence live here, once,
 * and every screen that says it reads the same answer as the public site.
 */
describe('publicationVisibility', () => {
  describe('the category comes first', () => {
    /**
     * No status and no period can put a publication onto a site that never lists
     * its category, so the flag is asked before anything else — including for a
     * publication that is published, in period, and would otherwise read as live.
     */
    it('names an unpublished category ahead of a published, in-period publication', () => {
      const line = publicationVisibility(
        facts({ categoryPublish: false, categoryName: 'Internal', publicFrom: LAST_YEAR }),
        fmt,
      );

      expect(line.state).toBe('NOT_PUBLIC_CATEGORY');
      expect(line.key).toBe('admin.publications.visibility.notPublicCategory');
      expect(line.compactKey).toBe('admin.publications.visibility.compact.notPublicCategory');
      expect(line.params).toEqual({ category: 'Internal' });
    });

    /** And ahead of a withdrawn one, whose category is still the reason it can never be listed. */
    it('names the category over a withdrawn publication', () => {
      const line = publicationVisibility(
        facts({ issueStatus: 'RETIRED', categoryPublish: false, categoryName: 'Internal' }),
        fmt,
      );

      expect(line.state).toBe('NOT_PUBLIC_CATEGORY');
    });

    /**
     * A sentence with an empty pair of brackets in it names nothing. The category
     * has its own key for the case where the name did not travel.
     */
    it('drops the category clause when no name came with the flag', () => {
      const line = publicationVisibility(facts({ categoryPublish: false }), fmt);

      expect(line.key).toBe('admin.publications.visibility.notPublicCategoryUnnamed');
      expect(line.params).toEqual({});
    });

    it('treats a blank category name as no name at all', () => {
      const line = publicationVisibility(
        facts({ categoryPublish: false, categoryName: '   ' }),
        fmt,
      );

      expect(line.key).toBe('admin.publications.visibility.notPublicCategoryUnnamed');
    });

    /**
     * ABSENT IS NOT FALSE.
     *
     * A server that does not report the flag has said nothing about the category,
     * and reading its silence as "not public" would announce a publication that
     * is on the site as hidden. The line falls back to the status and the period,
     * with no category clause in it.
     */
    it('says nothing about a category the server did not report on', () => {
      const line = publicationVisibility(facts({ publicFrom: LAST_YEAR }), fmt);

      expect(line.state).toBe('SINCE');
      expect(line.key).toBe('admin.publications.visibility.since');
    });

    /** A category the site DOES list adds nothing to the line either. */
    it('says nothing about a published category', () => {
      const line = publicationVisibility(
        facts({ categoryPublish: true, categoryName: 'DEMA publications', publicFrom: LAST_YEAR }),
        fmt,
      );

      expect(line.state).toBe('SINCE');
      expect(line.params).toEqual({ from: 'd' + LAST_YEAR });
    });
  });

  describe('withdrawn', () => {
    /**
     * Gone from the public site, history included — while the file stays at its
     * link so the messages that cite it keep resolving. The period it used to
     * have says nothing about that, so the line does not print one.
     */
    it('is withdrawn whatever the period says', () => {
      const line = publicationVisibility(
        facts({ issueStatus: 'RETIRED', publicFrom: LAST_YEAR, publicTo: NEXT_YEAR }),
        fmt,
      );

      expect(line.state).toBe('WITHDRAWN');
      expect(line.key).toBe('admin.publications.visibility.withdrawn');
      expect(line.compactKey).toBe('admin.publications.visibility.compact.withdrawn');
      expect(line.params).toEqual({});
    });

    /** The period start still travels, because the table orders on it. */
    it('carries the period start for ordering', () => {
      const line = publicationVisibility(
        facts({ issueStatus: 'RETIRED', publicFrom: LAST_YEAR }),
        fmt,
      );

      expect(line.from).toBe(LAST_YEAR);
    });
  });

  describe('a publication nobody has released yet', () => {
    it('is not published yet', () => {
      const line = publicationVisibility(facts({ issueStatus: 'OPEN' }), fmt);

      expect(line.state).toBe('DRAFT');
      expect(line.key).toBe('admin.publications.visibility.draft');
      expect(line.params).toEqual({});
      expect(line.from).toBeUndefined();
    });

    /**
     * A period set before the release is the whole point of being able to set one
     * on a draft: the publication is prepared now and goes public on the day.
     */
    it('names the prepared start beside the draft', () => {
      const line = publicationVisibility(
        facts({ issueStatus: 'OPEN', publicFrom: NEXT_YEAR }),
        fmt,
      );

      expect(line.state).toBe('DRAFT_PREPARED');
      expect(line.key).toBe('admin.publications.visibility.draftPrepared');
      expect(line.params).toEqual({ from: 'd' + NEXT_YEAR });
      expect(line.from).toBe(NEXT_YEAR);
    });

    /** A start already past is still a draft: nothing is public until it is released. */
    it('stays a draft with a start in the past', () => {
      const line = publicationVisibility(
        facts({ issueStatus: 'OPEN', publicFrom: LAST_YEAR }),
        fmt,
      );

      expect(line.state).toBe('DRAFT_PREPARED');
    });

    /**
     * A status the server did not send falls through to the period.
     *
     * Deliberately not read as a draft: a publication whose status did not travel
     * is one this screen knows nothing about, and calling it unreleased would put
     * "not published yet" over a document that has been on the public site for
     * years. The period is then the whole of the answer.
     */
    it('falls through to the period when no status came with the publication', () => {
      expect(publicationVisibility(facts({ issueStatus: undefined }), fmt).state).toBe('PUBLIC');
      expect(
        publicationVisibility(facts({ issueStatus: undefined, publicFrom: LAST_YEAR }), fmt).state,
      ).toBe('SINCE');
      expect(oneOffStatus('SOMETHING_ELSE')).toBe('UNKNOWN');
    });
  });

  describe('a published publication, and its period', () => {
    it('is public since the start it was given', () => {
      const line = publicationVisibility(facts({ publicFrom: LAST_YEAR }), fmt);

      expect(line.state).toBe('SINCE');
      expect(line.key).toBe('admin.publications.visibility.since');
      expect(line.compactKey).toBe('admin.publications.visibility.compact.since');
      expect(line.params).toEqual({ from: 'd' + LAST_YEAR });
    });

    /** A start still to come: prepared, released, and due on a set day. */
    it('is scheduled while the start is in the future', () => {
      const line = publicationVisibility(
        facts({ publicFrom: NEXT_YEAR, publicTo: NEXT_YEAR + 1 }),
        fmt,
      );

      expect(line.state).toBe('SCHEDULED');
      expect(line.key).toBe('admin.publications.visibility.scheduled');
      expect(line.params).toEqual({ from: 'd' + NEXT_YEAR });
    });

    it('is bounded while both ends stand around now', () => {
      const line = publicationVisibility(
        facts({ publicFrom: LAST_YEAR, publicTo: NEXT_YEAR }),
        fmt,
      );

      expect(line.state).toBe('BOUNDED');
      expect(line.key).toBe('admin.publications.visibility.bounded');
      expect(line.params).toEqual({ from: 'd' + LAST_YEAR, to: 'd' + NEXT_YEAR });
    });

    /**
     * A period that has run out. Not "not public": the document is still at its
     * link and the messages that cite it still resolve — it is simply no longer
     * something a reader browses to.
     */
    it('is historic once the end has passed', () => {
      const line = publicationVisibility(
        facts({ publicFrom: LAST_YEAR, publicTo: LAST_YEAR + 1 }),
        fmt,
      );

      expect(line.state).toBe('HISTORIC');
      expect(line.key).toBe('admin.publications.visibility.historic');
      expect(line.params).toEqual({ from: 'd' + LAST_YEAR, to: 'd' + (LAST_YEAR + 1) });
    });

    /**
     * The end is EXCLUSIVE, which is the server's own reading.
     *
     * The public site lists a publication while its end is still in the future,
     * so the instant the end names is already outside the period. The difference
     * is one millisecond wide, and a screen that read it the other way round
     * would call a publication current at the exact instant the server had
     * stopped listing it — and would disagree with the transition value the
     * editor carries, which uses the server's form.
     */
    it('is historic at the instant the period ends', () => {
      const line = publicationVisibility(facts({ publicFrom: LAST_YEAR, publicTo: NOW }), fmt);

      expect(line.state).toBe('HISTORIC');
    });

    /** One millisecond earlier it is still current. */
    it('is bounded up to the millisecond before the end', () => {
      const line = publicationVisibility(facts({ publicFrom: LAST_YEAR, publicTo: NOW + 1 }), fmt);

      expect(line.state).toBe('BOUNDED');
    });

    /** And a start exactly at the clock is no longer in the future. */
    it('is public since the instant the period opens', () => {
      const line = publicationVisibility(facts({ publicFrom: NOW }), fmt);

      expect(line.state).toBe('SINCE');
    });

    /**
     * `null` is the open-ended period the estate's other one-offs carry, and it
     * says the same thing as a field the server left out.
     */
    it('reads a null end as no end', () => {
      const line = publicationVisibility(facts({ publicFrom: LAST_YEAR, publicTo: null }), fmt);

      expect(line.state).toBe('SINCE');
    });

    it('reads a null start as no start', () => {
      const line = publicationVisibility(facts({ publicFrom: null }), fmt);

      expect(line.state).toBe('PUBLIC');
      expect(line.key).toBe('admin.publications.visibility.publicNow');
      expect(line.from).toBeUndefined();
    });

    /**
     * Published with no start at all — a server that does not report the field, or
     * a release that never stamped one. The publication IS on the site, and
     * saying so without a date is the honest half of the answer.
     */
    it('says a published publication is public when no start came with it', () => {
      const line = publicationVisibility(facts(), fmt);

      expect(line.state).toBe('PUBLIC');
      expect(line.params).toEqual({});
    });

    /** An end that has passed still ends it, start or no start. */
    it('is historic on a passed end with no start', () => {
      const line = publicationVisibility(facts({ publicTo: LAST_YEAR }), fmt);

      expect(line.state).toBe('HISTORIC');
      expect(line.key).toBe('admin.publications.visibility.historicNoStart');
      expect(line.params).toEqual({ to: 'd' + LAST_YEAR });
    });
  });

  /** Every branch offers the table the same key space, under `compact.`. */
  it('offers a compact key beside every sentence', () => {
    for (const one of EVERY_BRANCH) {
      const line = publicationVisibility(one, fmt);
      expect(line.compactKey).toBe(
        line.key.replace(
          'admin.publications.visibility.',
          'admin.publications.visibility.compact.',
        ),
      );
    }
  });
});

/**
 * One set of facts per branch of the rule, so a key that goes missing is caught
 * rather than discovered on a screen as its own key path.
 */
const EVERY_BRANCH: readonly PublicationVisibilityFacts[] = [
  facts({ categoryPublish: false, categoryName: 'Internal' }),
  facts({ categoryPublish: false }),
  facts({ issueStatus: 'RETIRED' }),
  facts({ issueStatus: 'OPEN' }),
  facts({ issueStatus: 'OPEN', publicFrom: NEXT_YEAR }),
  facts({ publicFrom: NEXT_YEAR }),
  facts({ publicFrom: LAST_YEAR, publicTo: NEXT_YEAR }),
  facts({ publicFrom: LAST_YEAR, publicTo: LAST_YEAR + 1 }),
  facts({ publicFrom: LAST_YEAR }),
  facts({ publicTo: LAST_YEAR }),
  facts(),
];

type TranslationNode = string | { readonly [key: string]: TranslationNode };

function labelIn(bundle: object, key: string): string | undefined {
  let node: TranslationNode | undefined = bundle as TranslationNode;
  for (const part of key.split('.')) {
    if (typeof node !== 'object' || node === null) return undefined;
    node = node[part];
  }
  return typeof node === 'string' ? node : undefined;
}

/**
 * Every key this module can produce has a sentence in both bundles.
 *
 * The rule builds its keys rather than mapping them, which is what keeps the two
 * wordings — the sentence and the table cell — from drifting apart. The price is
 * that a missing bundle entry renders as its own key path on the screen, so the
 * whole key space is walked here instead.
 */
describe('the visibility bundle', () => {
  const keys = [
    ...new Set(
      EVERY_BRANCH.flatMap((one) => {
        const line = publicationVisibility(one, fmt);
        return [line.key, line.compactKey];
      }),
    ),
    ...(['DRAFT', 'PUBLISHED', 'WITHDRAWN', 'UNKNOWN'] as const).map(oneOffStatusKey),
  ];

  it('has an English sentence for every one of them', () => {
    const missing = keys.filter((key) => labelIn(en, key) === undefined);
    expect(missing, `keys missing in en.json:\n${missing.join('\n')}`).toEqual([]);
  });
});

/**
 * The status, relabelled.
 *
 * The stored word is the database's: `OPEN` over a publication being prepared
 * reads as something somebody left half-done, and `RETIRED` says nothing about
 * a document that is still at its link.
 */
describe('oneOffStatus', () => {
  it('reads the issue status as the publication is talked about', () => {
    expect(oneOffStatus('OPEN')).toBe('DRAFT');
    expect(oneOffStatus('PUBLISHED')).toBe('PUBLISHED');
    expect(oneOffStatus('RETIRED')).toBe('WITHDRAWN');
    expect(oneOffStatus(undefined)).toBe('UNKNOWN');
  });

  it('names each status with a key of its own', () => {
    expect(oneOffStatusKey('DRAFT')).toBe('admin.publications.oneOff.status.DRAFT');
    expect(oneOffStatusKey('WITHDRAWN')).toBe('admin.publications.oneOff.status.WITHDRAWN');
  });

  /** The issue lifecycle's own three tints, because it is that lifecycle. */
  it('wears the issue lifecycle tints', () => {
    expect(oneOffStatusClass('DRAFT')).toBe('is-issue-open');
    expect(oneOffStatusClass('PUBLISHED')).toBe('is-issue-published');
    expect(oneOffStatusClass('WITHDRAWN')).toBe('is-cancelled');
    expect(oneOffStatusClass('UNKNOWN')).toBe('');
  });
});
