/**
 * Source-compose logic for the "Add sources" dialog. Builds, per configured model
 * language, the per-source text that gets appended to a message's `desc.source` field.
 *
 * Rules:
 * - each item renders its abbreviation, or its name when the abbreviation is empty;
 * - items are joined with `", "`, except the last which is joined with the localized
 *   "and" word (only when there is more than one item);
 * - a source's date is rendered day + full month; the year is omitted per item and
 *   appended once at the end IFF every selected source has a date and they all share
 *   the same year; otherwise each dated item carries its own day + month + year.
 *
 * The util stays translation-free: the caller supplies the localized "and" word and
 * the BCP-47 locale per language.
 *
 * A source date is an instant, and the calendar day it prints as depends on the zone it
 * is read in — 23:30 UTC on the 5th is the 6th in Copenhagen. The caller therefore names
 * the zone the text is written for; the day and the shared year are both resolved in it.
 */

import { getZonedDateParts } from './zoned-date';

export interface SourceTextDesc {
  readonly lang: string;
  readonly name?: string | null;
  readonly abbreviation?: string | null;
}

export interface SelectedSource {
  readonly descs: readonly SourceTextDesc[];
  /** Optional source date (a `Date` from the date picker, or a parseable value). */
  readonly date?: Date | string | number | null;
}

export interface ComposeSourceLanguage {
  /** Desc language code, e.g. `'en'` — keys the returned map. */
  readonly lang: string;
  /** BCP-47 locale for date formatting, e.g. `'en-GB'` / `'da'`. */
  readonly locale: string;
  /** Localized "and" joiner for the final item, e.g. `'and'` / `'og'`. */
  readonly and: string;
}

/** Options every language shares — chiefly the zone the source dates are read in. */
export interface ComposeSourceOptions {
  /**
   * IANA zone the source dates are read in. Mandatory: the day a source prints as is a fact
   * about a zone, and the browser's own is not an answer — a caller without one refuses to
   * compose rather than naming a day nobody chose.
   */
  readonly timeZone: string;
}

const formatterCache = new Map<string, Intl.DateTimeFormat>();

function dateFormatter(
  locale: string,
  includeYear: boolean,
  timeZone: string,
): Intl.DateTimeFormat {
  const key = `${locale}|${includeYear}|${timeZone}`;
  let fmt = formatterCache.get(key);
  if (!fmt) {
    fmt = new Intl.DateTimeFormat(locale, {
      day: 'numeric',
      month: 'long',
      year: includeYear ? 'numeric' : undefined,
      timeZone,
    });
    formatterCache.set(key, fmt);
  }
  return fmt;
}

function toValidDate(value: Date | string | number | null | undefined): Date | undefined {
  if (value === null || value === undefined || value === '') {
    return undefined;
  }
  const date = value instanceof Date ? value : new Date(value);
  return Number.isNaN(date.getTime()) ? undefined : date;
}

function descForLanguage(
  descs: readonly SourceTextDesc[],
  lang: string,
): SourceTextDesc | undefined {
  return descs.find((desc) => desc.lang === lang);
}

/**
 * The year shared by every selected source *as read in `timeZone`*, or `null` when a
 * source has no date or the years differ. Reading it in another zone could pick the
 * neighbouring year for a date within hours of New Year.
 */
function getCommonYear(sources: readonly SelectedSource[], timeZone: string): number | null {
  let commonYear: number | null = null;
  for (const source of sources) {
    const date = toValidDate(source.date);
    if (!date) {
      return null;
    }
    const year = getZonedDateParts(date, timeZone).year;
    if (commonYear !== null && year !== commonYear) {
      return null;
    }
    commonYear = year;
  }
  return commonYear;
}

function composeForLanguage(
  sources: readonly SelectedSource[],
  language: ComposeSourceLanguage,
  commonYear: number | null,
  timeZone: string,
): string {
  const includeYear = commonYear === null;
  const count = sources.length;
  let text = '';

  sources.forEach((source, index) => {
    const desc = descForLanguage(source.descs, language.lang);
    if (!desc) {
      return;
    }
    const abbreviation = desc.abbreviation ?? '';
    const label = abbreviation.length > 0 ? abbreviation : (desc.name ?? '');

    const isLastOfMany = count > 1 && index === count - 1;
    const delimiter = isLastOfMany ? ` ${language.and} ` : ', ';
    if (text.length > 0) {
      text += delimiter;
    }
    text += label;

    const date = toValidDate(source.date);
    if (date) {
      text += ` ${dateFormatter(language.locale, includeYear, timeZone).format(date)}`;
    }
  });

  if (commonYear !== null) {
    text += ` ${commonYear}`;
  }
  return text;
}

/**
 * Compose the per-language source text for a selection of sources.
 *
 * @param options names the zone the source dates are read in — see {@link ComposeSourceOptions}.
 * @returns a `{ [lang]: text }` map with one entry per configured language.
 */
export function composeSourceText(
  sources: readonly SelectedSource[],
  languages: readonly ComposeSourceLanguage[],
  options: ComposeSourceOptions,
): Record<string, string> {
  const { timeZone } = options;
  const commonYear = getCommonYear(sources, timeZone);
  const result: Record<string, string> = {};
  for (const language of languages) {
    result[language.lang] = composeForLanguage(sources, language, commonYear, timeZone);
  }
  return result;
}
