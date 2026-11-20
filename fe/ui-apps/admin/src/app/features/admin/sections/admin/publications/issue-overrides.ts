import type {
  PublicationIssue,
  PublicationSeries,
} from '../../../services/api/publication-series-api.service';
import { weeksNamed } from './issue-number';

/**
 * What one edition says differently from its series, worked out in one place.
 *
 * Two surfaces ask the same question and must not answer it differently: the
 * drawer marks the fields somebody is editing, and the issue screen marks the
 * facts a reader is looking at. Both read these functions, so a field that is
 * marked in one is marked in the other, and the counts under them cannot drift.
 *
 * Pure on purpose — no injection, no translation, no signals. The rule is data.
 */

/** The four numbers an issue is written under, as text, because that is how they print. */
export interface IssueNumbering {
  readonly week: string;
  /**
   * The cut-off's second week, always.
   *
   * A span nobody would write as a range — `36+37` — is written into
   * {@link week}, which is free text, so a two-week issue states its span once
   * rather than in two values that could disagree.
   */
  readonly weekTo: string;
  readonly year: string;
  readonly edition: string;
}

/**
 * The edition a create writes, and what a form offers where there is none.
 *
 * A DISPLAY default only. Nothing derives it: an issue that records no edition
 * records no edition, and {@link derivedNumbering} says so.
 */
export const DEFAULT_EDITION = '1';

/** The longest a name may be, matching the column that stores it. */
export const NAME_MAX_LENGTH = 255;

/** The longest a printed label or an edition may be, matching the column that stores it. */
export const NUMBERING_LABEL_MAX_LENGTH = 64;

/** The longest a hand-set file name may be. */
export const FILE_NAME_MAX_LENGTH = 128;

/**
 * The numbers as the cut-off derived them — never what anybody typed.
 *
 * These are the values everything that COMPUTES reads: the ordering, the
 * timeline, gap detection. They are the comparison a label is judged against,
 * which is the only reason a screen needs them as strings.
 */
export function derivedNumbering(
  issue: Pick<PublicationIssue, 'week' | 'weekTo' | 'year' | 'edition'>,
): IssueNumbering {
  return {
    week: numberText(issue.week),
    weekTo: numberText(issue.weekTo),
    year: numberText(issue.year),
    // Exactly what the issue records, and `''` where it records nothing. NOT
    // defaulted to the first edition: the server expands `${edition}` from the
    // edition READ AS A NUMBER, so an issue carrying none expands it to nothing
    // — and a screen that quietly supplied a `1` here would compute a name the
    // server would never write and then mark the issue tailored for differing
    // from it.
    edition: issue.edition?.trim() ?? '',
  };
}

/**
 * The numbers as they PRINT — the label where there is one, the derived value
 * otherwise.
 *
 * The one resolution every name, file name and report heading goes through, so
 * a week written `36+37` reaches all three of them and nothing else. The second
 * week is not among the labels: it is the cut-off's own number wherever it is
 * read, and a span written any other way is written into the week.
 */
export function printedNumbering(
  issue: Pick<PublicationIssue, 'week' | 'weekTo' | 'year' | 'edition' | 'weekLabel' | 'yearLabel'>,
): IssueNumbering {
  const derived = derivedNumbering(issue);
  return {
    week: issue.weekLabel?.trim() || derived.week,
    weekTo: derived.weekTo,
    year: issue.yearLabel?.trim() || derived.year,
    edition: derived.edition,
  };
}

/** Every token a name or file-name pattern is written in. */
const TOKEN = /\$\{([^}]*)\}/g;

/**
 * A `+` written straight between the first week and the closing week
 * (`${week}+${weekTo}`, either variant of each, spaces allowed around it): the
 * pair form, which prints as a range for an issue of three weeks or more.
 */
const WEEK_PAIR_JOIN = /(\$\{week(?:-2-digits)?\}\s*)\+(?=\s*\$\{weekTo(?:-2-digits)?\})/g;

/**
 * The pattern with the pair's `+` written as a hyphen where the issue is named
 * for three weeks or more: `36-38`, and across a year's end `52-2` beside the
 * later year. A pair (`36+37`) and a single week expand as written. The same
 * rule as the server's (`IssueNaming.expand`), decided by the derived numbers.
 */
function joined(pattern: string, derived: IssueNumbering): string {
  const weeks = weeksOf(derived);
  if (weeks === undefined || weeks < 3) {
    return pattern;
  }
  return pattern.replace(WEEK_PAIR_JOIN, '$1-');
}

/** How many weeks the derived numbers name, or `undefined` where there is no week. */
function weeksOf(derived: IssueNumbering): number | undefined {
  const week = Number(derived.week);
  if (derived.week === '' || !Number.isFinite(week)) {
    return undefined;
  }
  const weekTo = derived.weekTo === '' ? undefined : Number(derived.weekTo);
  const year = derived.year === '' ? undefined : Number(derived.year);
  return weeksNamed(week, weekTo, year);
}

/**
 * The pattern as the issue's weeks write it: {@link singleWeek} for an issue of
 * one week, {@link joined} for one of three weeks or more, and as written for a
 * pair.
 */
function written(pattern: string, derived: IssueNumbering): string {
  return weeksOf(derived) === 1 ? singleWeek(pattern) : joined(pattern, derived);
}

/** The brackets a single week never leaves half, each as its opener and its closer. */
const BRACKETS: readonly (readonly [string, string])[] = [
  ['(', ')'],
  ['[', ']'],
];

const isFirstWeek = (token: string) => token === 'week' || token === 'week-2-digits';
const isClosingWeek = (token: string) => token === 'weekTo' || token === 'weekTo-2-digits';

/**
 * The pattern as a single week writes it: without its closing week, so a
 * single week is `Uge 36, 2026` under `Uge ${week}+${weekTo}, ${year}` and
 * under `Uge ${week} og ${weekTo}, ${year}` alike.
 *
 * The server's rule (`IssueNaming.singleWeek`), written to match it: each
 * closing-week token goes with the text that joins it to the first-week token
 * next to it (the one before, else the one after); a bracket that text opens or
 * closes goes whole, one that holds the first week stays, and where the bracket
 * closes or opens only beyond another token it stays with only the text inside
 * it gone; a closing-week token with no first-week token next to it prints as
 * the first week, in its own variant. The tokens are taken from left to right.
 */
function singleWeek(pattern: string): string {
  let p = pattern;
  for (;;) {
    const tokens = [...p.matchAll(TOKEN)].map((m) => ({
      name: m[1].trim(),
      start: m.index,
      end: m.index + m[0].length,
    }));
    const c = tokens.findIndex((t) => isClosingWeek(t.name));
    if (c < 0) {
      return p;
    }
    const closing = tokens[c];
    const before = tokens[c - 1];
    const after = tokens[c + 1];
    if (before && isFirstWeek(before.name)) {
      p = dropAfterFirstWeek(p, before.end, closing.start, closing.end, after?.start ?? p.length);
    } else if (after && isFirstWeek(after.name)) {
      p = dropBeforeFirstWeek(p, before?.end ?? 0, closing.start, closing.end, after.start);
    } else {
      const first = closing.name === 'weekTo' ? '${week}' : '${week-2-digits}';
      p = p.slice(0, closing.start) + first + p.slice(closing.end);
    }
  }
}

/** The closing week at [cs, ce) and the text from the first week's end to it, brackets whole. */
function dropAfterFirstWeek(
  p: string,
  from: number,
  cs: number,
  ce: number,
  limit: number,
): string {
  // A bracket the joiner closes without opening it holds the first week, and stays.
  let start = from;
  for (const [opener, closer] of BRACKETS) {
    let open = 0;
    for (let i = from; i < cs; i++) {
      if (p[i] === opener) {
        open++;
      } else if (p[i] === closer) {
        if (open > 0) {
          open--;
        } else {
          start = Math.max(start, i + 1);
        }
      }
    }
  }
  // A bracket the rest of the joiner opens goes up to where the text after the closing week closes it.
  let end = ce;
  let keepTo = start;
  let closed = true;
  for (const [opener, closer] of BRACKETS) {
    const opened: number[] = [];
    for (let i = start; i < cs; i++) {
      if (p[i] === opener) {
        opened.push(i);
      } else if (p[i] === closer && opened.length > 0) {
        opened.pop();
      }
    }
    if (opened.length === 0) {
      continue;
    }
    keepTo = Math.max(keepTo, opened[opened.length - 1] + 1);
    const at = closingAfter(p, ce, limit, opener, closer, opened.length);
    if (at < 0) {
      closed = false;
    } else {
      end = Math.max(end, at);
    }
  }
  return closed ? p.slice(0, start) + p.slice(end) : p.slice(0, keepTo) + p.slice(ce);
}

/** The closing week at [cs, ce) and the text from it to the first week's start, brackets whole. */
function dropBeforeFirstWeek(p: string, limit: number, cs: number, ce: number, to: number): string {
  // A bracket the joiner opens without closing it holds the first week, and stays.
  let end = to;
  for (const [opener, closer] of BRACKETS) {
    let depth = 0;
    for (let i = to - 1; i >= ce; i--) {
      if (p[i] === closer) {
        depth++;
      } else if (p[i] === opener) {
        if (depth > 0) {
          depth--;
        } else {
          end = Math.min(end, i);
        }
      }
    }
  }
  // A bracket the rest of the joiner closes goes back to where the text before the closing week opens it.
  let start = cs;
  let keepFrom = end;
  let opened = true;
  for (const [opener, closer] of BRACKETS) {
    let open = 0;
    let unopened = 0;
    let firstUnopened = -1;
    for (let i = ce; i < end; i++) {
      if (p[i] === opener) {
        open++;
      } else if (p[i] === closer) {
        if (open > 0) {
          open--;
        } else {
          unopened++;
          if (firstUnopened < 0) {
            firstUnopened = i;
          }
        }
      }
    }
    if (unopened === 0) {
      continue;
    }
    keepFrom = Math.min(keepFrom, firstUnopened);
    const at = openingBefore(p, cs, limit, opener, closer, unopened);
    if (at < 0) {
      opened = false;
    } else {
      start = Math.min(start, at);
    }
  }
  return opened ? p.slice(0, start) + p.slice(end) : p.slice(0, cs) + p.slice(keepFrom);
}

/** Just after the closer that closes `open` brackets, searching [from, limit), or -1. */
function closingAfter(
  p: string,
  from: number,
  limit: number,
  opener: string,
  closer: string,
  open: number,
): number {
  for (let i = from; i < limit; i++) {
    if (p[i] === opener) {
      open++;
    } else if (p[i] === closer && --open === 0) {
      return i + 1;
    }
  }
  return -1;
}

/** The opener that opens `unopened` brackets, searching back from `from` to `limit`, or -1. */
function openingBefore(
  p: string,
  from: number,
  limit: number,
  opener: string,
  closer: string,
  unopened: number,
): number {
  for (let i = from - 1; i >= limit; i--) {
    if (p[i] === closer) {
      unopened++;
    } else if (p[i] === opener && --unopened === 0) {
      return i;
    }
  }
  return -1;
}

/**
 * What a series' pattern would call this issue — or `undefined` where it cannot
 * say.
 *
 * `undefined` is a real answer and the callers depend on it: a pattern with a
 * token this build cannot resolve, or one whose value the issue does not carry
 * (an issue with no numeric edition under `${edition}`), expands to a string
 * that is not what the server would produce. Comparing against such a partial
 * expansion would mark every such issue as tailored, and offering it as "follow
 * the series again" would write a name with a hole in it. So a pattern that
 * cannot be expanded exactly is not expanded at all, and the surfaces above
 * simply say nothing about that field.
 *
 * A single week's closing week is not such a value: the pattern drops it with
 * its joiner first ({@link singleWeek}), as the server does, so
 * `Uge ${week}+${weekTo}, ${year}` suggests `Uge 36, 2026`.
 */
export function expandIssuePattern(
  pattern: string | undefined,
  printed: IssueNumbering,
  derived: IssueNumbering,
): string | undefined {
  const source = pattern?.trim();
  if (!source) {
    return undefined;
  }
  const values = numberingTokens(printed, derived);
  let resolvable = true;
  const expanded = written(source, derived).replace(TOKEN, (_match, token: string) => {
    const name = token.trim();
    // Asked of the map's OWN keys: a token naming something every object carries
    // — `${constructor}` — would otherwise resolve to a value that is not a
    // string at all and reach the name as one.
    const value = Object.hasOwn(values, name) ? values[name] : '';
    if (value === '') {
      resolvable = false;
      return '';
    }
    return value;
  });
  return resolvable ? expanded : undefined;
}

/**
 * What a series' file-name pattern would call this issue's document, with the
 * `.pdf` the server always writes.
 *
 * The server adds `.pdf` to every file name it writes, whatever the pattern says
 * (`IssueFileNaming.withPdf`), and a series may be saved with a pattern that
 * leaves it out. Compared against the raw expansion, every stored name of such a
 * series would differ by its extension and mark the issue tailored.
 */
export function expandFileNamePattern(
  pattern: string | undefined,
  printed: IssueNumbering,
  derived: IssueNumbering,
): string | undefined {
  return withPdf(expandIssuePattern(pattern, printed, derived));
}

/**
 * A file name as the server stores it: `.pdf` added unless the name already
 * ends in it, in any case (`IssueFileNaming.withPdf`). An empty name stays empty,
 * because the server reads a blank name as no name rather than as `.pdf`.
 */
export function withPdf(name: string): string;
export function withPdf(name: string | undefined): string | undefined;
export function withPdf(name: string | undefined): string | undefined {
  if (name === undefined || name === '') {
    return name;
  }
  return name.toLowerCase().endsWith('.pdf') ? name : `${name}.pdf`;
}

/**
 * What each token expands to — the same answer the server's expansion gives.
 *
 * The two expansions are one rule implemented twice, and every disagreement
 * between them shows up as a field marked tailored that is not, so the rules
 * below are written to match rather than to be reasonable on their own:
 *
 * - A LABEL is laid over its derived number in BOTH the plain and the padded
 *   slot. `36+37` has no two-digit form, and padding is a formatting of a number
 *   — which a label is not, whatever it looks like.
 * - The second week is the derived number in both of its slots. Nothing writes a
 *   label over it, so there is nothing for a label to displace.
 * - Padding otherwise is to two digits, from the derived number.
 * - The edition expands only where it IS a number. A free-text edition — `2b` —
 *   expands to nothing, because the number is what a pattern asks for.
 * - The month and day variants are absent: they come from the cut-off date
 *   rather than from the numbering, so a pattern using one is a pattern this
 *   screen cannot reproduce, and {@link expandIssuePattern} then says "no
 *   suggestion" rather than guessing at a name.
 *
 * A label is recognised as a printed value that differs from the derived one,
 * which is the same test that decides whether a label is STORED at all: a box
 * typed back to the derived number clears the label rather than recording it.
 */
export function numberingTokens(
  printed: IssueNumbering,
  derived: IssueNumbering,
): Readonly<Record<string, string>> {
  const labelled = (value: string, derivedValue: string, padded: string) =>
    value !== derivedValue ? value : padded;
  return {
    week: printed.week,
    'week-2-digits': labelled(printed.week, derived.week, pad2(derived.week)),
    weekTo: derived.weekTo,
    'weekTo-2-digits': pad2(derived.weekTo),
    year: printed.year,
    'year-2-digits': labelled(printed.year, derived.year, twoDigitYear(derived.year)),
    edition: /^\d+$/.test(printed.edition) ? printed.edition : '',
  };
}

/**
 * Which of the printed labels a series actually puts on a document.
 *
 * The edition is absent because every publication carries one: an issue that
 * came out twice has a second edition whatever else it is numbered by.
 */
export interface NumberingFields {
  readonly week: boolean;
  readonly year: boolean;
}

/**
 * Which numbering labels this series PRINTS — the one place that decides it.
 *
 * An issue derives a week, a second week and a year from its cut-off whether or
 * not the publication is numbered by any of them: a yearly compilation closes in
 * some ISO week too, and that number names nothing about the document. A box
 * offering a label for a value nothing prints invites somebody to write text
 * that never appears, and a mark saying the issue is tailored would then be
 * about a label no reader can see.
 *
 * An unknown series prints everything: a screen still loading its series has
 * nothing to compare against, and hiding a box on a guess is worse than offering
 * one that turns out not to apply.
 */
export function numberingFields(
  series: Pick<PublicationSeries, 'cadence' | 'numberingScheme'> | undefined,
): NumberingFields {
  if (!series) {
    return { week: true, year: true };
  }
  const scheme = series.numberingScheme;
  // The scheme wins over the cadence, as it does everywhere the series numbers
  // its issues: a scheme of NONE on a weekly series is a deliberate choice not
  // to number them, and a week box would offer to label a number nobody prints.
  if (scheme === 'NONE') {
    return { week: false, year: false };
  }
  return {
    week: series.cadence === 'WEEKLY' || scheme === 'ISO_WEEK_YEAR',
    // A series with neither a calendar nor a scheme numbers its issues by
    // edition alone, and a year beside that edition would be the cut-off's own
    // year rather than anything the publication states.
    year: series.cadence !== 'NONE' || scheme !== undefined,
  };
}

/** Two digits, as the server pads them — and nothing at all for nothing at all. */
function pad2(value: string): string {
  return value === '' ? '' : value.padStart(2, '0');
}

function twoDigitYear(value: string): string {
  return value === '' ? '' : pad2(String(Number(value) % 100));
}

function numberText(value: number | undefined): string {
  return value === undefined || value === null ? '' : String(value);
}

// ------------------------------------------------------------------- the marks

/** What the series says, reduced to the four things an issue can differ from. */
export interface SeriesDefaults {
  /** `''` where the series has no report. */
  readonly reportId: string;
  readonly reportParams: Readonly<Record<string, string>>;
  /** Per language, and absent for a language the series names no pattern for. */
  readonly namePatterns: Readonly<Record<string, string>>;
  readonly fileNamePatterns: Readonly<Record<string, string>>;
  /** Which printed labels this series uses — see {@link numberingFields}. */
  readonly numbering: NumberingFields;
}

/**
 * The issue's own values, as text, whether they were read or are being typed.
 *
 * The drawer feeds its drafts through the same shape the screen feeds the stored
 * issue through, which is what makes a mark appear the moment somebody types
 * rather than one refetch later.
 */
export interface IssueValues {
  readonly names: Readonly<Record<string, string>>;
  readonly fileNames: Readonly<Record<string, string>>;
  /** The server's own answer, where it sends one. Absent reads as "follows the series". */
  readonly fileNameFlags: Readonly<Record<string, boolean>>;
  /** The same, for the name. Absent reads as "the series' pattern names it". */
  readonly nameFlags: Readonly<Record<string, boolean>>;
  readonly numbering: IssueNumbering;
  readonly derived: IssueNumbering;
  /** `''` follows the series. */
  readonly reportId: string;
  readonly reportParams: Readonly<Record<string, string>>;
}

export interface OverrideMarks {
  readonly name: boolean;
  readonly fileName: boolean;
  readonly numbering: boolean;
  readonly report: boolean;
  /** The languages whose name differs, so a per-language mark can be placed. */
  readonly nameLangs: readonly string[];
  readonly fileNameLangs: readonly string[];
  /** The parameters this issue answers differently from its series. */
  readonly paramKeys: readonly string[];
  /**
   * How many of the drawer's four marked SECTIONS carry a mark.
   *
   * The parameters are not one of them. They are a table with its own per-row
   * showing of what the series says beside what this issue says, so a section
   * mark above it would say a second time what every row already says — and the
   * count under the drawer title names the sections a reader would have to open.
   */
  readonly count: number;
  /**
   * How many separate FACTS differ, across every language and every parameter.
   *
   * A different number from `count` on purpose, and it answers a different
   * question: whether ANYTHING about this issue is tailored. That is what the
   * mark beside the status asks, and what the drawer's reset has to be able to
   * act on. `count` names the sections a reader would have to open.
   */
  readonly factCount: number;
}

/** Whether anything at all is tailored — the one question the header badge asks. */
export function anyOverridden(marks: OverrideMarks): boolean {
  return marks.factCount > 0;
}

/**
 * Which of the four groups this issue answers differently from its series.
 *
 * The names and file names are judged by EXPANDING the series' pattern and
 * comparing, because that is the only reading that is true while somebody is
 * typing — a stored flag answers for the last save, not for the box. The flag
 * is read beside it rather than instead of it: a PINNED field is one the server
 * stops re-deriving, so it is tailored even where the pinned text is exactly
 * what the pattern renders today, and it is the flag alone that answers where
 * the pattern cannot be expanded at all.
 *
 * The numbering is judged on the labels alone, and only on the ones the series
 * prints. The edition is part of the same section but is not derived from the
 * cut-off, so it is not something the issue says DIFFERENTLY — an issue that
 * came out twice has a second edition, and that is a fact about it rather than a
 * deviation from its series.
 */
export function overrideMarks(
  values: IssueValues,
  series: SeriesDefaults,
  languages: readonly string[],
): OverrideMarks {
  const nameLangs = languages.filter((lang) => {
    const suggestion = expandIssuePattern(
      series.namePatterns[lang],
      values.numbering,
      values.derived,
    );
    const current = values.names[lang]?.trim() ?? '';
    if (suggestion === undefined) {
      return values.nameFlags[lang] === true;
    }
    return values.nameFlags[lang] === true || (current !== '' && current !== suggestion);
  });

  // Both sides as the server stores them: with `.pdf`, which it adds to the
  // pattern's expansion and to a name typed without it alike.
  const fileNameLangs = languages.filter((lang) => {
    const current = withPdf(values.fileNames[lang]?.trim() ?? '');
    const suggestion = expandFileNamePattern(
      series.fileNamePatterns[lang],
      values.numbering,
      values.derived,
    );
    if (suggestion === undefined) {
      return values.fileNameFlags[lang] === true;
    }
    return values.fileNameFlags[lang] === true || (current !== '' && current !== suggestion);
  });

  // Only a label the series PRINTS can be tailored. A yearly compilation derives
  // a week from its cut-off like everything else, and a stale label left on one
  // — imported, or set before the series was renumbered — would otherwise mark
  // an issue as tailored over text no document carries.
  const fields = series.numbering;
  const numbering =
    (fields.week && values.numbering.week !== values.derived.week) ||
    (fields.year && values.numbering.year !== values.derived.year);

  const report = values.reportId !== '' && values.reportId !== series.reportId;

  const paramKeys = Object.keys(values.reportParams).filter(
    (key) => values.reportParams[key] !== (series.reportParams[key] ?? ''),
  );

  const marks = {
    name: nameLangs.length > 0,
    fileName: fileNameLangs.length > 0,
    numbering,
    report,
    nameLangs,
    fileNameLangs,
    paramKeys,
  };
  const count = [marks.name, marks.fileName, marks.numbering, marks.report].filter(Boolean).length;
  const factCount =
    fileNameLangs.length +
    nameLangs.length +
    (numbering ? 1 : 0) +
    (report ? 1 : 0) +
    paramKeys.length;
  return { ...marks, count, factCount };
}

// ------------------------------------------------------- reading off the wire

/**
 * The series' four defaults, read off whatever the screen was handed.
 *
 * Degrades to "nothing to compare against" rather than to a wrong comparison: a
 * screen with no series yet, or one whose series names no patterns, marks
 * nothing at all — which is what a reader should see while a fact is still
 * loading.
 */
export function seriesDefaults(series: PublicationSeries | undefined): SeriesDefaults {
  const namePatterns: Record<string, string> = {};
  const fileNamePatterns: Record<string, string> = {};
  for (const desc of series?.descs ?? []) {
    if (desc.nameSuggestionPattern) {
      namePatterns[desc.lang] = desc.nameSuggestionPattern;
    }
    if (desc.fileNamePattern) {
      fileNamePatterns[desc.lang] = desc.fileNamePattern;
    }
  }
  return {
    reportId: series?.reportId?.trim() ?? '',
    reportParams: paramText(series?.reportParams),
    namePatterns,
    fileNamePatterns,
    numbering: numberingFields(series),
  };
}

/** The issue's stored values in the shape the marks are computed from. */
export function issueValues(issue: PublicationIssue): IssueValues {
  const names: Record<string, string> = {};
  const fileNames: Record<string, string> = {};
  const fileNameFlags: Record<string, boolean> = {};
  const nameFlags: Record<string, boolean> = {};
  for (const desc of issue.descs ?? []) {
    names[desc.lang] = desc.name ?? '';
    fileNames[desc.lang] = desc.fileName ?? '';
    fileNameFlags[desc.lang] = desc.fileNameOverridden === true;
    nameFlags[desc.lang] = desc.nameOverridden === true;
  }
  return {
    names,
    fileNames,
    fileNameFlags,
    nameFlags,
    numbering: printedNumbering(issue),
    derived: derivedNumbering(issue),
    reportId: issue.reportId?.trim() ?? '',
    reportParams: paramText(issue.reportParams),
  };
}

/**
 * Report parameters as text, which is what both a table cell and a comparison
 * want.
 *
 * The wire carries `unknown` values because a report parameter is free-form; a
 * screen renders them and an override is judged on what is rendered, so they are
 * flattened once here rather than at four call sites that would each pick a
 * different `String(...)`.
 */
export function paramText(
  params: Readonly<Record<string, unknown>> | undefined,
): Readonly<Record<string, string>> {
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(params ?? {})) {
    out[key] = value === undefined || value === null ? '' : String(value);
  }
  return out;
}
