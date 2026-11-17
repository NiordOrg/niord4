/**
 * How the list filters on this section compare what was typed with what is on
 * screen.
 *
 * Two rules, both of them about not making the reader spell a name exactly.
 *
 * A publication is named in every language the series carries, and the reader
 * is not necessarily reading the one they remember the name in — the archive
 * holds series named only in Danish, and a filter that looked at the active
 * language alone would report them as missing. So EVERY name a row has is a
 * candidate, not just the one being rendered.
 *
 * And the comparison folds case and accents: `normalizeFilterText` decomposes,
 * drops the combining marks and lowercases, so "Å" and "A" are one letter to
 * this filter and typing either finds both. It is deliberately a fold rather
 * than a transliteration — æ and ø carry no combining mark and stay themselves,
 * which is what a Danish reader typing them expects.
 */

/** The form both a name and a query are compared in. */
export function normalizeFilterText(value: string): string {
  return value
    .trim()
    .normalize('NFD')
    .replace(/\p{Diacritic}/gu, '')
    .toLocaleLowerCase();
}

/**
 * Whether any of a row's names contains the query.
 *
 * A blank query matches everything: an empty filter box is the absence of a
 * filter, not a filter nothing satisfies.
 */
export function matchesFilter(
  candidates: Iterable<string | undefined | null>,
  query: string,
): boolean {
  const needle = normalizeFilterText(query);
  if (needle === '') {
    return true;
  }
  for (const candidate of candidates) {
    if (candidate && normalizeFilterText(candidate).includes(needle)) {
      return true;
    }
  }
  return false;
}

/** Every name a localized entity has, in whatever languages it carries. */
export function localizedNames(
  descs: readonly { readonly name?: string }[] | undefined,
): readonly (string | undefined)[] {
  return (descs ?? []).map((desc) => desc.name);
}
