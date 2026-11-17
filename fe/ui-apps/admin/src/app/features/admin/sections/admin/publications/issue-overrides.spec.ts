import { describe, expect, it } from 'vitest';

import type {
  PublicationIssue,
  PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import {
  anyOverridden,
  derivedNumbering,
  expandFileNamePattern,
  expandIssuePattern,
  issueValues,
  numberingFields,
  overrideMarks,
  paramText,
  printedNumbering,
  seriesDefaults,
  withPdf,
} from './issue-overrides';

const SERIES = {
  seriesId: 'weekly-ntm',
  status: 'ACTIVE',
  kind: 'SCHEDULED',
  cadence: 'WEEKLY',
  contentMode: 'GENERATED_FROM_QUERY',
  domainId: 'niord-nm',
  languages: ['da', 'en'],
  reportId: 'nm-tp-report',
  reportParams: { overskrift: 'Aktive P&T', volume: 133 },
  descs: [
    {
      lang: 'da',
      name: 'Aktive P&T',
      nameSuggestionPattern: 'Aktive P&T uge ${week} - ${year}',
      fileNamePattern: 'EfS-PT-Uge-${week}-${year}.pdf',
    },
    {
      lang: 'en',
      name: 'Active P&T',
      nameSuggestionPattern: 'Active P&T Week ${week} - ${year}',
      fileNamePattern: 'NtM-PT-Week-${week}-${year}.pdf',
    },
  ],
} as unknown as PublicationSeries;

/** An issue that says exactly what its series says. */
const FOLLOWING = {
  publicId: 'i-1',
  seriesId: 'weekly-ntm',
  status: 'OPEN',
  week: 38,
  year: 2026,
  descs: [
    { lang: 'da', name: 'Aktive P&T uge 38 - 2026' },
    { lang: 'en', name: 'Active P&T Week 38 - 2026' },
  ],
} as unknown as PublicationIssue;

const LANGUAGES = ['da', 'en'];

function marksFor(issue: PublicationIssue) {
  return overrideMarks(issueValues(issue), seriesDefaults(SERIES), LANGUAGES);
}

/** A pattern expanded for one issue, exactly as both surfaces expand it. */
function expand(pattern: string | undefined, issue: PublicationIssue) {
  return expandIssuePattern(pattern, printedNumbering(issue), derivedNumbering(issue));
}

describe('the printed numbering', () => {
  it('prints the derived numbers where nobody wrote a label', () => {
    expect(printedNumbering(FOLLOWING)).toEqual({
      week: '38',
      weekTo: '',
      year: '2026',
      edition: '',
    });
  });

  /**
   * The edition is read, never invented. A screen that supplied "the first one"
   * here would expand `${edition}` to a `1` the server does not write, and would
   * then report every such issue as tailored for not matching it.
   */
  it('reports the edition an issue records, and nothing where it records none', () => {
    expect(printedNumbering({ ...FOLLOWING, edition: '2' }).edition).toBe('2');
    expect(printedNumbering({ ...FOLLOWING, edition: '  ' }).edition).toBe('');
  });

  /**
   * The whole point of the labels: a double issue is written `36+37`, which no
   * numeric column can hold and no number check would accept.
   */
  it('prints a label in place of the number it stands for', () => {
    const doubled = { ...FOLLOWING, week: 36, weekTo: 37, weekLabel: '36+37' };
    expect(printedNumbering(doubled).week).toBe('36+37');
    // The derived numbers are untouched — everything that COMPUTES reads those.
    expect(derivedNumbering(doubled).week).toBe('36');
    expect(derivedNumbering(doubled).weekTo).toBe('37');
  });

  it('treats a blank label as no label at all', () => {
    expect(printedNumbering({ ...FOLLOWING, weekLabel: '   ' }).week).toBe('38');
  });

  /**
   * A two-week issue states its span ONCE, in the week. The second week has no
   * label of its own, so it stays the cut-off's number wherever it is read and
   * cannot end up disagreeing with the span written beside it.
   */
  it('keeps the second week derived, whatever the week is written as', () => {
    const doubled = { ...FOLLOWING, week: 36, weekTo: 37, weekLabel: '36+37' } as PublicationIssue;
    expect(printedNumbering(doubled).weekTo).toBe('37');
    expect(expand('${week} (${weekTo})', doubled)).toBe('36+37 (37)');
    expect(expand('${week-2-digits}-${weekTo-2-digits}', doubled)).toBe('36+37-37');
  });

  /** An older server sends none of the three, and the derived numbers still print. */
  it('reads an issue from a server that has never heard of labels', () => {
    expect(printedNumbering({ week: 12, year: 2025 } as PublicationIssue)).toEqual({
      week: '12',
      weekTo: '',
      year: '2025',
      edition: '',
    });
  });
});

describe('expanding a series pattern', () => {
  it('resolves the tokens an issue carries', () => {
    expect(expand('EfS uge ${week} - ${year}', FOLLOWING)).toBe('EfS uge 38 - 2026');
  });

  it('carries a typed label straight into the suggestion', () => {
    const doubled = { ...FOLLOWING, week: 36, weekTo: 37, weekLabel: '36+37' };
    expect(expand('EfS uge ${week} - ${year}', doubled)).toBe('EfS uge 36+37 - 2026');
  });

  /**
   * A pattern this build cannot reproduce exactly says nothing rather than
   * something wrong: comparing against a half-expanded string would mark every
   * such issue as tailored, and offering it as "follow the series again" would
   * write a name with a hole in it.
   */
  it('says nothing about a pattern whose tokens it cannot resolve', () => {
    expect(expand('EfS ${month}/${year}', FOLLOWING)).toBeUndefined();
    expect(expand('EfS ${day-2-digits}.${month-2-digits}', FOLLOWING)).toBeUndefined();
    // weekTo is a real token, but this issue does not run to a second week.
    expect(expand('EfS ${week}-${weekTo} ${year}', FOLLOWING)).toBeUndefined();
    // Not a token at all, however much it looks like one to an object.
    expect(expand('EfS ${constructor}', FOLLOWING)).toBeUndefined();
  });

  it('says nothing about a series that names no pattern', () => {
    expect(expand(undefined, FOLLOWING)).toBeUndefined();
    expect(expand('  ', FOLLOWING)).toBeUndefined();
  });
});

/**
 * The one table both expansions are pinned to.
 *
 * This expansion and the server's are the same rule written twice, and every
 * disagreement between them shows on screen as a field marked tailored that is
 * not — plus a "follow the series again" that would write a name the server
 * would never produce. The rows below are the cases the two implementations can
 * drift on; the server side asserts the same rows against its own expansion, so
 * a token added on either side fails on the other.
 *
 * Each row: the pattern, the issue's derived numbers, what was typed over them,
 * and the one string both sides must produce (`undefined` = no suggestion).
 */
const SHARED_VECTORS: readonly {
  readonly what: string;
  readonly pattern: string;
  readonly issue: Partial<PublicationIssue>;
  readonly expected: string | undefined;
}[] = [
  { what: 'plain week and year', pattern: '${week}-${year}', issue: {}, expected: '38-2026' },
  {
    what: 'a padded week pads to two digits',
    pattern: 'nm-w${week-2-digits}-${year}',
    issue: { week: 7 },
    expected: 'nm-w07-2026',
  },
  {
    what: 'a padded week under a label is the label, verbatim',
    pattern: 'nm-w${week-2-digits}-${year}',
    issue: { week: 7, weekLabel: '7+8' },
    expected: 'nm-w7+8-2026',
  },
  {
    what: 'a two-digit year is the last two digits',
    pattern: 'EfS-${week}-${year-2-digits}',
    issue: {},
    expected: 'EfS-38-26',
  },
  {
    what: 'a two-digit year under a label is the label, verbatim — a label has no two-digit form',
    pattern: 'EfS-${week}-${year-2-digits}',
    issue: { yearLabel: '2026/27' },
    expected: 'EfS-38-2026/27',
  },
  {
    what: 'a closing week, padded',
    pattern: '${week-2-digits}-${weekTo-2-digits}-${year}',
    issue: { week: 8, weekTo: 9 },
    expected: '08-09-2026',
  },
  {
    what: 'a numeric edition expands',
    pattern: 'EfS-A-${edition}-${year}',
    issue: { edition: '2' },
    expected: 'EfS-A-2-2026',
  },
  {
    what: 'a free-text edition is not a number, so the pattern cannot be reproduced',
    pattern: 'EfS-A-${edition}-${year}',
    issue: { edition: '2b' },
    expected: undefined,
  },
  {
    what: 'an issue carrying no edition at all',
    pattern: 'EfS-A-${edition}-${year}',
    issue: {},
    expected: undefined,
  },
];

/**
 * A file name is always a PDF. The server adds `.pdf` to every file name it
 * writes, whatever the pattern says (`IssueFileNaming.withPdf`), and a series
 * may be saved with a pattern that leaves it out.
 */
describe('expanding a file-name pattern', () => {
  it('adds .pdf to a pattern written without it, as the server does', () => {
    expect(
      expandFileNamePattern(
        'nm-w${week}-${year}',
        printedNumbering(FOLLOWING),
        derivedNumbering(FOLLOWING),
      ),
    ).toBe('nm-w38-2026.pdf');
  });

  it('adds nothing to a pattern that already ends in .pdf, in any case', () => {
    const printed = printedNumbering(FOLLOWING);
    const derived = derivedNumbering(FOLLOWING);
    expect(expandFileNamePattern('nm-w${week}-${year}.pdf', printed, derived)).toBe(
      'nm-w38-2026.pdf',
    );
    expect(expandFileNamePattern('NM-W${week}-${year}.PDF', printed, derived)).toBe(
      'NM-W38-2026.PDF',
    );
  });

  it('still says nothing where the pattern cannot be expanded', () => {
    const printed = printedNumbering(FOLLOWING);
    const derived = derivedNumbering(FOLLOWING);
    expect(expandFileNamePattern('nm-${month}', printed, derived)).toBeUndefined();
    expect(expandFileNamePattern(undefined, printed, derived)).toBeUndefined();
  });

  it('leaves an empty name empty', () => {
    expect(withPdf('')).toBe('');
    expect(withPdf(undefined)).toBeUndefined();
    expect(withPdf('a.Pdf')).toBe('a.Pdf');
    expect(withPdf('a.pdf.txt')).toBe('a.pdf.txt.pdf');
  });
});

describe('the expansion vector both implementations answer', () => {
  for (const vector of SHARED_VECTORS) {
    it(vector.what, () => {
      const issue = { ...FOLLOWING, ...vector.issue } as PublicationIssue;
      expect(expand(vector.pattern, issue)).toBe(vector.expected);
    });
  }
});

describe('the numbering a series prints', () => {
  /** The series shape the rule reads, and nothing else. */
  function fields(cadence: string, numberingScheme?: string) {
    return numberingFields({ cadence, numberingScheme } as unknown as PublicationSeries);
  }

  it('prints a week where the series numbers by week', () => {
    expect(fields('WEEKLY')).toEqual({ week: true, year: true });
    expect(fields('WEEKLY', 'ISO_WEEK_YEAR')).toEqual({ week: true, year: true });
    // A scheme that names a week on a series with no calendar of its own.
    expect(fields('NONE', 'ISO_WEEK_YEAR')).toEqual({ week: true, year: true });
  });

  it('prints no week for a yearly compilation', () => {
    expect(fields('YEARLY', 'YEAR_EDITION')).toEqual({ week: false, year: true });
    expect(fields('MONTHLY', 'MONTH_YEAR')).toEqual({ week: false, year: true });
  });

  /**
   * A series that numbers its issues at all carries a year: an edition sequence
   * is read within one, and it is the one number a reader dates a document by.
   */
  it('prints a year for anything that numbers its issues', () => {
    expect(fields('NONE', 'EDITION_SEQUENCE')).toEqual({ week: false, year: true });
  });

  it('prints the edition alone where the series numbers nothing', () => {
    expect(fields('NONE', 'NONE')).toEqual({ week: false, year: false });
    // No scheme named at all, which is how the estate's unnumbered rows read.
    expect(fields('NONE')).toEqual({ week: false, year: false });
  });

  /**
   * The scheme wins over the cadence. A weekly series whose scheme says NONE has
   * chosen not to number its issues, and the cadence must not overrule that
   * choice by offering a week box anyway.
   */
  it('lets a scheme of NONE overrule a weekly cadence', () => {
    expect(fields('WEEKLY', 'NONE')).toEqual({ week: false, year: false });
  });

  it('prints everything while the series is still unknown', () => {
    expect(numberingFields(undefined)).toEqual({ week: true, year: true });
  });
});

describe('what one edition says differently from its series', () => {
  it('marks nothing on an issue that follows the series in every group', () => {
    const marks = marksFor(FOLLOWING);
    expect(marks.count).toBe(0);
    expect(marks.factCount).toBe(0);
    expect(anyOverridden(marks)).toBe(false);
  });

  it('marks the name where it is not what the pattern would produce', () => {
    const renamed = {
      ...FOLLOWING,
      descs: [
        { lang: 'da', name: 'Aktive P&T uge 38 og 39 - 2026' },
        { lang: 'en', name: 'Active P&T Week 38 - 2026' },
      ],
    } as unknown as PublicationIssue;
    const marks = marksFor(renamed);
    expect(marks.name).toBe(true);
    expect(marks.nameLangs).toEqual(['da']);
    expect(marks.count).toBe(1);
  });

  it('marks the numbering on a label, and never on the edition', () => {
    expect(marksFor({ ...FOLLOWING, weekLabel: '38+39' }).numbering).toBe(true);
    // An issue that came out twice is a fact about the issue, not a deviation
    // from the series — the section holds the box, the mark is not about it.
    expect(marksFor({ ...FOLLOWING, edition: '2' }).numbering).toBe(false);
    expect(marksFor({ ...FOLLOWING, edition: '2' }).count).toBe(0);
  });

  it('marks a file name against the pattern, per language', () => {
    const renamed = {
      ...FOLLOWING,
      descs: [
        { lang: 'da', name: 'Aktive P&T uge 38 - 2026', fileName: 'EfS-PT-Uge-38-2026.pdf' },
        { lang: 'en', name: 'Active P&T Week 38 - 2026', fileName: 'Something-else.pdf' },
      ],
    } as unknown as PublicationIssue;
    const marks = marksFor(renamed);
    expect(marks.fileNameLangs).toEqual(['en']);
  });

  /**
   * A series whose file-name pattern leaves out `.pdf`. The server writes every
   * file name with it, so the published issue's stored name ends in `.pdf`, and
   * that is the name the series gives it, not a tailored one.
   */
  describe('under a file-name pattern written without .pdf', () => {
    const NO_EXTENSION = {
      ...SERIES,
      descs: [
        { lang: 'da', name: 'Aktive P&T', fileNamePattern: 'EfS-PT-Uge-${week}-${year}' },
        { lang: 'en', name: 'Active P&T', fileNamePattern: 'NtM-PT-Week-${week}-${year}.PDF' },
      ],
    } as unknown as PublicationSeries;

    function published(daFile: string, enFile: string): PublicationIssue {
      return {
        ...FOLLOWING,
        status: 'PUBLISHED',
        descs: [
          { lang: 'da', name: 'Aktive P&T uge 38 - 2026', fileName: daFile },
          { lang: 'en', name: 'Active P&T Week 38 - 2026', fileName: enFile },
        ],
      } as unknown as PublicationIssue;
    }

    it('does not mark the stored name the server wrote with .pdf added', () => {
      const issue = published('EfS-PT-Uge-38-2026.pdf', 'NtM-PT-Week-38-2026.PDF');
      const marks = overrideMarks(issueValues(issue), seriesDefaults(NO_EXTENSION), LANGUAGES);
      expect(marks.fileName).toBe(false);
      expect(marks.fileNameLangs).toEqual([]);
      expect(anyOverridden(marks)).toBe(false);
    });

    it('still marks a stored name that really differs', () => {
      const issue = published('EfS-Saerudgave.pdf', 'NtM-PT-Week-38-2026.PDF');
      const marks = overrideMarks(issueValues(issue), seriesDefaults(NO_EXTENSION), LANGUAGES);
      expect(marks.fileNameLangs).toEqual(['da']);
      expect(anyOverridden(marks)).toBe(true);
    });

    /**
     * A box typed without `.pdf` is the name the server will store with it. The
     * server adds a lower-case `.pdf`, so under the pattern ending in `.PDF` the
     * same text typed without an extension is a different stored name.
     */
    it('reads a name typed without .pdf as the name the server stores', () => {
      const issue = published('EfS-PT-Uge-38-2026', 'NtM-PT-Week-38-2026');
      const marks = overrideMarks(issueValues(issue), seriesDefaults(NO_EXTENSION), LANGUAGES);
      expect(marks.fileNameLangs).toEqual(['en']);
    });
  });

  /**
   * The one case the comparison has no opinion on: a pattern that cannot be
   * expanded. The server's own flag answers there, and an ABSENT flag is read as
   * "follows the series" — which is what a backend built before per-issue file
   * names sends for every issue in the estate.
   */
  it('falls back to the server flag where the pattern cannot be expanded', () => {
    const noPattern = {
      ...SERIES,
      descs: [
        { lang: 'da', name: 'Aktive P&T' },
        { lang: 'en', name: 'Active P&T' },
      ],
    } as unknown as PublicationSeries;
    const issue = {
      ...FOLLOWING,
      descs: [
        { lang: 'da', name: 'x', fileName: 'a.pdf', fileNameOverridden: true },
        { lang: 'en', name: 'y', fileName: 'b.pdf' },
      ],
    } as unknown as PublicationIssue;
    const marks = overrideMarks(issueValues(issue), seriesDefaults(noPattern), LANGUAGES);
    expect(marks.fileNameLangs).toEqual(['da']);
  });

  /** The name answers the same way, from the flag the server sets when one is typed. */
  it('falls back to the server flag for a name the pattern cannot judge', () => {
    const noPattern = {
      ...SERIES,
      descs: [
        { lang: 'da', name: 'Aktive P&T' },
        { lang: 'en', name: 'Active P&T' },
      ],
    } as unknown as PublicationSeries;
    const issue = {
      ...FOLLOWING,
      descs: [
        { lang: 'da', name: 'Noget andet', nameOverridden: true },
        { lang: 'en', name: 'Active P&T Week 38 - 2026' },
      ],
    } as unknown as PublicationIssue;
    const marks = overrideMarks(issueValues(issue), seriesDefaults(noPattern), LANGUAGES);
    expect(marks.nameLangs).toEqual(['da']);
  });

  /**
   * The drawer asks the same question about text that is still being typed: the
   * boxes hold the expansion of a label nobody has saved yet, while the derived
   * numbers are still the cut-off's. Judged against the label — which is what
   * will print — nothing here is tailored except the numbering itself.
   */
  it('reads a box that has caught up with an unsaved label as following the series', () => {
    const marks = overrideMarks(
      {
        names: { da: 'Aktive P&T uge 36+37 - 2026', en: 'Active P&T Week 36+37 - 2026' },
        fileNames: { da: 'EfS-PT-Uge-36+37-2026.pdf', en: 'NtM-PT-Week-36+37-2026.pdf' },
        fileNameFlags: {},
        nameFlags: {},
        numbering: { week: '36+37', weekTo: '37', year: '2026', edition: '' },
        derived: { week: '36', weekTo: '37', year: '2026', edition: '' },
        reportId: '',
        reportParams: {},
      },
      seriesDefaults(SERIES),
      LANGUAGES,
    );
    expect(marks.name).toBe(false);
    expect(marks.fileName).toBe(false);
    expect(marks.numbering).toBe(true);
    expect(marks.count).toBe(1);
  });

  /**
   * A PIN is tailoring whatever it says. The server stops re-deriving a pinned
   * name, so an issue that renumbers keeps it — which is invisible for exactly
   * as long as the pinned text and the pattern render the same string, and is
   * the moment the mark is most worth having.
   */
  it('marks a pinned name that agrees with the pattern word for word', () => {
    const pinned = {
      ...FOLLOWING,
      descs: [
        { lang: 'da', name: 'Aktive P&T uge 38 - 2026', nameOverridden: true },
        { lang: 'en', name: 'Active P&T Week 38 - 2026' },
      ],
    } as unknown as PublicationIssue;
    const marks = marksFor(pinned);
    expect(marks.nameLangs).toEqual(['da']);
    expect(marks.count).toBe(1);
  });

  it('marks the report only where the issue names a different one', () => {
    expect(marksFor({ ...FOLLOWING, reportId: 'nm-tp-report' }).report).toBe(false);
    expect(marksFor({ ...FOLLOWING, reportId: 'nm-annex' }).report).toBe(true);
  });

  it('names the parameters this issue answers differently', () => {
    const tailored = {
      ...FOLLOWING,
      reportParams: { overskrift: 'Aktive P&T uge 38', volume: 133 },
    } as unknown as PublicationIssue;
    const marks = marksFor(tailored);
    // `volume` repeats the series' value, so it is not an override of anything.
    expect(marks.paramKeys).toEqual(['overskrift']);
    // Parameters are not one of the four marked SECTIONS — the table's own
    // columns already say, row by row, what the series says and what this issue
    // says — but they ARE separate facts on the issue screen.
    expect(marks.count).toBe(0);
    expect(marks.factCount).toBe(1);
    expect(anyOverridden(marks)).toBe(true);
  });

  /**
   * The two counts are different questions and must not be collapsed: the drawer
   * names the sections a reader would have to open, and the screen names the
   * facts it has printed.
   */
  it('counts sections for the drawer and facts for the screen', () => {
    const tailored = {
      ...FOLLOWING,
      weekLabel: '36+37',
      week: 36,
      weekTo: 37,
      descs: [
        { lang: 'da', name: 'Aktive P&T uge 36+37 - 2026', fileName: 'EfS-PT-Uge-36+37-2026.pdf' },
        {
          lang: 'en',
          name: 'Active P&T Week 36+37 - 2026',
          fileName: 'NtM-PT-Week-36+37-2026.pdf',
        },
      ],
      reportParams: { overskrift: 'Aktive P&T uge 36+37' },
    } as unknown as PublicationIssue;
    const marks = marksFor(tailored);
    // Numbering; the names still agree with the pattern once the label flows
    // through it, and so do the file names.
    expect(marks.name).toBe(false);
    expect(marks.fileName).toBe(false);
    expect(marks.numbering).toBe(true);
    expect(marks.count).toBe(1);
    expect(marks.factCount).toBe(2);
  });

  /**
   * A label the series does not print cannot be tailored.
   *
   * A yearly compilation derives a week from its cut-off like every other issue,
   * and a stale week label on one — imported, or left behind when the series was
   * renumbered — would otherwise mark it as tailored over text no document
   * carries and no box offers.
   */
  it('ignores a label the series never prints', () => {
    const yearly = {
      ...SERIES,
      cadence: 'YEARLY',
      numberingScheme: 'YEAR_EDITION',
    } as unknown as PublicationSeries;
    const issue = { ...FOLLOWING, weekLabel: '36+37' } as PublicationIssue;

    expect(overrideMarks(issueValues(issue), seriesDefaults(yearly), LANGUAGES).numbering).toBe(
      false,
    );
    // The same issue under the weekly series that does print a week.
    expect(overrideMarks(issueValues(issue), seriesDefaults(SERIES), LANGUAGES).numbering).toBe(
      true,
    );
  });

  it('still marks the year a series without a week prints', () => {
    const yearly = {
      ...SERIES,
      cadence: 'YEARLY',
      numberingScheme: 'YEAR_EDITION',
    } as unknown as PublicationSeries;
    const issue = { ...FOLLOWING, yearLabel: 'MMXXVI' } as PublicationIssue;

    expect(overrideMarks(issueValues(issue), seriesDefaults(yearly), LANGUAGES).numbering).toBe(
      true,
    );
  });

  /** With no series there is nothing to compare against, so nothing is claimed. */
  it('claims nothing while the series is still unknown', () => {
    const marks = overrideMarks(issueValues(FOLLOWING), seriesDefaults(undefined), LANGUAGES);
    expect(marks.count).toBe(0);
    expect(marks.factCount).toBe(0);
  });
});

describe('report parameters as text', () => {
  it('renders every value the wire can carry as the string a cell shows', () => {
    expect(paramText({ a: 1, b: 'x', c: null, d: undefined, e: true })).toEqual({
      a: '1',
      b: 'x',
      c: '',
      d: '',
      e: 'true',
    });
  });

  it('reads an absent map as no parameters', () => {
    expect(paramText(undefined)).toEqual({});
  });
});

describe('the series defaults', () => {
  it('reads the patterns and the report off the series', () => {
    const defaults = seriesDefaults(SERIES);
    expect(defaults.reportId).toBe('nm-tp-report');
    expect(defaults.namePatterns['da']).toBe('Aktive P&T uge ${week} - ${year}');
    expect(defaults.fileNamePatterns['en']).toBe('NtM-PT-Week-${week}-${year}.pdf');
    expect(defaults.reportParams).toEqual({ overskrift: 'Aktive P&T', volume: '133' });
  });

  it('degrades to nothing rather than to a wrong comparison', () => {
    expect(seriesDefaults(undefined)).toEqual({
      reportId: '',
      reportParams: {},
      namePatterns: {},
      fileNamePatterns: {},
      // Every label offered: a screen with no series has nothing to compare
      // against, and hiding a box on a guess is worse than offering one.
      numbering: { week: true, year: true },
    });
  });
});
