import { projectDesc } from '../../../shared/utils/project-desc';

export interface LocalizedDesc {
  readonly lang: string;
}

/**
 * Orders existing descs in configured-list order, then unconfigured languages
 * in original order — WITHOUT padding. Used for display surfaces that render
 * only the descs the server returned. Per-language surfaces always follow the
 * configured language list; the UI language never reorders them.
 */
export function orderDescs<T extends LocalizedDesc>(
  descs: readonly T[],
  languages: readonly string[],
): T[] {
  const rank = new Map(languages.map((lang, index) => [lang, index]));
  return [...descs].sort(
    (a, b) => (rank.get(a.lang) ?? languages.length) - (rank.get(b.lang) ?? languages.length),
  );
}

/**
 * Pads a localized-desc list so every configured model language has an entry,
 * in configured-list order. Works for any number of configured languages.
 *
 * Data-integrity rule: descs in languages OUTSIDE the configured list are
 * preserved at the end in their original order — server data is never dropped
 * by padding, and must round-trip through edit/save unchanged.
 */
export function padAndOrderDescs<T extends LocalizedDesc>(
  descs: readonly T[],
  languages: readonly string[],
  createEmpty: (lang: string) => T,
): T[] {
  const byLang = new Map(descs.map((desc) => [desc.lang, desc]));
  const configured = languages.map((lang) => byLang.get(lang) ?? createEmpty(lang));
  const known = new Set(languages);
  const unknown = descs.filter((desc) => !known.has(desc.lang));
  return [...configured, ...unknown];
}

/**
 * The single desc to DISPLAY for the current UI language: the current
 * language's entry when present, else the configured default language,
 * else the first desc at all. For one-value summary cells (a table's
 * name/value column) — NOT for per-language iteration, which must use
 * {@link orderDescs} / {@link padAndOrderDescs} so the rendered order never
 * follows the UI language.
 * Returns a new display projection; object identity must not be used to locate an editable row.
 */
export function currentDesc<T extends LocalizedDesc>(
  descs: readonly T[],
  languages: readonly string[],
  currentLang: string,
  defaultLanguage: string = languages[0],
): T | undefined {
  return projectDesc(descs, currentLang, defaultLanguage);
}
