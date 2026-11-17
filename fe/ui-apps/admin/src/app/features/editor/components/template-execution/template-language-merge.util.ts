import type { SystemCategory } from '../../../messages/models/template.model';

/** Combine several desc lists into one, keeping the FIRST occurrence of each `lang`. */
function mergeDescsByLang<T extends { readonly lang?: string }>(
  lists: readonly (readonly T[] | undefined)[],
): T[] {
  const seen = new Set<string>();
  const out: T[] = [];
  for (const list of lists) {
    for (const desc of list ?? []) {
      const key = desc.lang ?? '';
      if (!seen.has(key)) {
        seen.add(key);
        out.push(desc);
      }
    }
  }
  return out;
}

/**
 * Merge the per-language `getTemplateDetails` responses into one list whose category + template-param
 * descs carry EVERY language.
 *
 * `GET /categories/search/{ids}?lang=` filters each category's (and each param's) descs to a SINGLE
 * language, so a chosen template's labels wouldn't re-localize on a language switch (they'd fall back
 * to the fetched language's desc). Fetching once per configured language and combining the descs
 * lets the execution card re-localize its labels client-side with no re-fetch — and without
 * disturbing the user's entered param values.
 *
 * The structure (params, base fields, positions, example id) is language-invariant, so it is taken
 * from the FIRST response; only the `descs` are combined. Categories are matched by `id` and params
 * by `paramId`, so a differing order across responses is safe.
 */
export function mergeTemplateLanguages(
  perLanguage: readonly (readonly SystemCategory[])[],
): SystemCategory[] {
  const primary = perLanguage[0] ?? [];
  return primary.map((cat) => {
    const variants = perLanguage
      .map((cats) => cats.find((c) => c.id === cat.id))
      .filter((c): c is SystemCategory => c !== undefined);
    return {
      ...cat,
      descs: mergeDescsByLang(variants.map((v) => v.descs)),
      templateParams: (cat.templateParams ?? []).map((param) => ({
        ...param,
        descs: mergeDescsByLang(
          variants.map((v) => v.templateParams?.find((p) => p.paramId === param.paramId)?.descs),
        ),
      })),
    };
  });
}
