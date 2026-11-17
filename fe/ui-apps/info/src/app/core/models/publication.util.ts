import { Publication, PublicationCategory } from './publication.model';

/** One row of the publication table: the publication, and the heading printed above it. */
export interface PublicationRow {
  readonly publication: Publication;
  /** Set on the first publication of each category run, which is where the heading is drawn. */
  readonly heading?: PublicationCategory;
}

/** A copy of `descs` with the given language's entry first and the others in the order they came. */
function languageFirst<D extends { readonly lang: string }>(
  descs: readonly D[] | undefined,
  lang: string,
): readonly D[] | undefined {
  if (!descs || descs.length <= 1) return descs;
  return [...descs].sort((a, b) => (a.lang === lang ? -1 : b.lang === lang ? 1 : 0));
}

/**
 * The publication with its own descriptions and its category's ordered so the given language
 * comes first, which is the one the table reads. The list is asked for in that language, and a
 * publication with no text in it arrives in a language it has instead, so this is what keeps the
 * reader's language first whenever more than one comes back. A copy: the fetched row stays as it
 * came.
 */
export function sortPublicationDescs(publication: Publication, lang: string): Publication {
  const category = publication.category;
  return {
    ...publication,
    descs: languageFirst(publication.descs, lang),
    ...(category ? { category: { ...category, descs: languageFirst(category.descs, lang) } } : {}),
  };
}

/**
 * Marks where each run of one category starts. The categories arrive in order, so a heading
 * belongs wherever the category differs from the row before it.
 */
export function withCategoryHeadings(publications: readonly Publication[]): PublicationRow[] {
  let lastCategoryId: string | undefined;
  return publications.map((publication) => {
    const category = publication.category;
    if (category && category.categoryId !== lastCategoryId) {
      lastCategoryId = category.categoryId;
      return { publication, heading: category };
    }
    return { publication };
  });
}
