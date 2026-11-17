import { Publication } from './publication.model';
import { sortPublicationDescs, withCategoryHeadings } from './publication.util';

const BOTH_LANGUAGES: Publication = {
  publicationId: 'p1',
  category: {
    categoryId: 'cat-A',
    descs: [
      { lang: 'da', name: 'Efterretninger' },
      { lang: 'en', name: 'Notices' },
    ],
  },
  descs: [
    { lang: 'da', title: 'EfS uge 38', link: '/rest/repo/file/efs.pdf' },
    { lang: 'en', title: 'NtM week 38', link: '/rest/repo/file/ntm.pdf' },
  ],
};

describe('sortPublicationDescs', () => {
  it('puts the given language first in the publication and in its category', () => {
    const sorted = sortPublicationDescs(BOTH_LANGUAGES, 'en');
    expect(sorted.descs!.map((desc) => desc.lang)).toEqual(['en', 'da']);
    expect(sorted.category!.descs!.map((desc) => desc.lang)).toEqual(['en', 'da']);
  });

  it('leaves the fetched row as it came', () => {
    sortPublicationDescs(BOTH_LANGUAGES, 'en');
    expect(BOTH_LANGUAGES.descs![0].lang).toBe('da');
    expect(BOTH_LANGUAGES.category!.descs![0].lang).toBe('da');
  });

  // The backend answers a language a publication has no text in with the one it has: the row
  // keeps that text rather than losing its title.
  it('keeps the only language there is when it is not the one asked for', () => {
    const danishOnly: Publication = {
      publicationId: 'p2',
      descs: [{ lang: 'da', title: 'Skydeområder 2026' }],
    };
    expect(sortPublicationDescs(danishOnly, 'en').descs).toEqual([
      { lang: 'da', title: 'Skydeområder 2026' },
    ]);
  });

  it('adds no category to a publication that has none', () => {
    const sorted = sortPublicationDescs({ publicationId: 'p3' }, 'en');
    expect(sorted).toEqual({ publicationId: 'p3', descs: undefined });
    expect('category' in sorted).toBe(false);
  });
});

describe('withCategoryHeadings', () => {
  it('heads the first publication of each category run', () => {
    const rows = withCategoryHeadings([
      { publicationId: 'p1', category: { categoryId: 'a' } },
      { publicationId: 'p2', category: { categoryId: 'a' } },
      { publicationId: 'p3', category: { categoryId: 'b' } },
    ]);
    expect(rows.map((row) => row.heading?.categoryId)).toEqual(['a', undefined, 'b']);
  });
});
