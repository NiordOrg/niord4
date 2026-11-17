import type { BadgeClass } from './publication-status';

/**
 * Where a one-off publication stands, as its screens name it.
 *
 * The issue's own status, relabelled: an OPEN issue is a publication nobody has
 * released yet, and RETIRED is one that has been taken off the public site. The
 * stored words are the database's, and `Open` over a publication that is being
 * prepared reads as a state somebody left half-finished rather than as a draft.
 */
export type OneOffStatus = 'DRAFT' | 'PUBLISHED' | 'WITHDRAWN' | 'UNKNOWN';

/** The issue status, read as the publication's own. */
export function oneOffStatus(issueStatus: string | undefined): OneOffStatus {
  switch (issueStatus) {
    case 'OPEN':
      return 'DRAFT';
    case 'PUBLISHED':
      return 'PUBLISHED';
    case 'RETIRED':
      return 'WITHDRAWN';
    default:
      return 'UNKNOWN';
  }
}

export function oneOffStatusKey(status: OneOffStatus): string {
  return `admin.publications.oneOff.status.${status}`;
}

/**
 * The tint the status badge wears.
 *
 * The same three colours the issue lifecycle already uses, because it IS that
 * lifecycle: a publication being prepared wears the "open for work" blue, a
 * released one the published green, and a withdrawn one the cancelled tint that
 * means "taken back" everywhere else in the application.
 */
export function oneOffStatusClass(status: OneOffStatus): BadgeClass {
  switch (status) {
    case 'DRAFT':
      return 'is-issue-open';
    case 'PUBLISHED':
      return 'is-issue-published';
    case 'WITHDRAWN':
      return 'is-cancelled';
    default:
      return '';
  }
}

/**
 * The three independent facts a publication's public standing is made of.
 *
 * Never a single boolean. "Active" folded a status, a period and a category flag
 * into one word, and every one of them is invisible from the other two: an admin
 * told that a publication "is not active" could not tell whether it had been
 * withdrawn, was still being prepared, sat in a category the public site never
 * lists, or was simply not due to appear until next January.
 */
export interface PublicationVisibilityFacts {
  /** The issue's stored status — see {@link oneOffStatus}. */
  readonly issueStatus?: string;
  /** Epoch ms. When the publication goes, or went, onto the public site. */
  readonly publicFrom?: number | null;
  /** Epoch ms, or absent for a period with no end. */
  readonly publicTo?: number | null;
  /**
   * Whether the publication's CATEGORY is one the public site lists at all.
   *
   * TRI-STATE. `undefined` is a server that does not report the flag, and is NOT
   * a category that is unpublished: read as `false` it would announce a
   * publication that is on the site as hidden. Absent means the answer comes
   * from the status and the period alone, with nothing said about the category.
   */
  readonly categoryPublish?: boolean;
  /** What the category is called, for the one sentence that names it. */
  readonly categoryName?: string;
  /** The clock the period is read against — see `nowTick`. */
  readonly now: number;
}

/**
 * Which of the answers below the facts add up to.
 *
 * Exposed beside the key so a caller can react to the answer without matching on
 * a translation key: the detail card offers a different action for a withdrawn
 * publication than for a published one, and reading that off a string would put
 * the rules in two places.
 */
export type PublicationVisibilityState =
  | 'NOT_PUBLIC_CATEGORY'
  | 'WITHDRAWN'
  | 'DRAFT'
  | 'DRAFT_PREPARED'
  | 'SCHEDULED'
  | 'HISTORIC'
  | 'BOUNDED'
  | 'SINCE'
  | 'PUBLIC';

export interface PublicationVisibility {
  readonly state: PublicationVisibilityState;
  /** The sentence a page prints. */
  readonly key: string;
  /** The same answer as a table cell reads it: the dates, without the sentence. */
  readonly compactKey: string;
  /** What both keys interpolate — dates already rendered in the reader's locale. */
  readonly params: Readonly<Record<string, string>>;
  /** The start of the public period, for ordering. `undefined` where there is none. */
  readonly from?: number;
}

const KEY_PREFIX = 'admin.publications.visibility.';

/**
 * What a publication's public standing amounts to, in one line.
 *
 * ONE derivation for every screen that says it. The dashboard column, the
 * publication's own card and anything that comes after them are answering the
 * same question about the same three facts, and a second implementation of these
 * rules is a second answer that can disagree with the public site.
 *
 * The order of the branches is the model's own, and it is not an implementation
 * detail: a category the public site does not list is the FIRST question,
 * because no status and no period can put a publication onto a site that never
 * lists its category. Withdrawn comes next — it is gone from the site whatever
 * its period says — then a publication nobody has released, and only then the
 * period of one that is out.
 *
 * Dates are rendered by the caller's own formatter rather than here: everything
 * that reads as a date/time goes through the locale service, and a pure rule
 * module has no business holding one.
 */
export function publicationVisibility(
  facts: PublicationVisibilityFacts,
  formatDate: (value: number) => string,
): PublicationVisibility {
  const from = facts.publicFrom ?? undefined;
  const to = facts.publicTo ?? undefined;
  const status = oneOffStatus(facts.issueStatus);

  const line = (
    state: PublicationVisibilityState,
    name: string,
    params: Readonly<Record<string, string>> = {},
  ): PublicationVisibility => ({
    state,
    key: KEY_PREFIX + name,
    compactKey: `${KEY_PREFIX}compact.${name}`,
    params,
    ...(from === undefined ? {} : { from }),
  });

  // A category the site does not list. Said with the category's name where there
  // is one: "not public" alone leaves the reader looking for a switch to flip,
  // and the thing to change is the category rather than the publication.
  if (facts.categoryPublish === false) {
    const category = facts.categoryName?.trim();
    return category
      ? line('NOT_PUBLIC_CATEGORY', 'notPublicCategory', { category })
      : line('NOT_PUBLIC_CATEGORY', 'notPublicCategoryUnnamed');
  }

  if (status === 'WITHDRAWN') {
    return line('WITHDRAWN', 'withdrawn');
  }

  // Not released yet — and a period may already have been prepared for it, which
  // is the whole point of being able to set one on a publication that is not out.
  if (status === 'DRAFT') {
    return from === undefined
      ? line('DRAFT', 'draft')
      : line('DRAFT_PREPARED', 'draftPrepared', { from: formatDate(from) });
  }

  // Published, and the period decides what to say about it. A start still to
  // come is the prepared publication going public on a set day; a period that
  // has run out is a document somebody can still cite but nobody browses to.
  if (from !== undefined && from > facts.now) {
    return line('SCHEDULED', 'scheduled', { from: formatDate(from) });
  }
  if (from !== undefined && to !== undefined) {
    return closed(to, facts.now)
      ? line('HISTORIC', 'historic', { from: formatDate(from), to: formatDate(to) })
      : line('BOUNDED', 'bounded', { from: formatDate(from), to: formatDate(to) });
  }
  if (from !== undefined) {
    return line('SINCE', 'since', { from: formatDate(from) });
  }
  // Published with no start at all. A server that does not report the field yet,
  // or one that never stamped it: the publication IS on the site, and saying so
  // without a date is the honest half of the answer.
  return to !== undefined && closed(to, facts.now)
    ? line('HISTORIC', 'historicNoStart', { to: formatDate(to) })
    : line('PUBLIC', 'publicNow');
}

/**
 * Whether a period that has an end has reached it.
 *
 * The END IS EXCLUSIVE, which is the server's own reading: a publication is on
 * the public site while `publicTo` is still in the future, so the instant the
 * end names is already outside the period. Stated once, because the difference
 * is one millisecond wide and a screen that read it the other way would call a
 * publication current at the exact instant the server had stopped listing it.
 */
function closed(to: number, now: number): boolean {
  return to <= now;
}
