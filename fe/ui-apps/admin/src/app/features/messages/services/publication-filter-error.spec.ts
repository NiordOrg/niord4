import { describe, expect, it } from 'vitest';

import { publicationFilterErrorDetail } from './publication-filter-error';

describe('publicationFilterErrorDetail', () => {
  /**
   * The refusal belongs to ONE chip.
   *
   * Left unreported it renders as an empty result — which reads as an answer
   * about the publication rather than as a failure to apply the filter.
   */
  it('reports the server message for a publication refusal', () => {
    const detail = publicationFilterErrorDetail({
      status: 400,
      error: {
        code: 'PUBLICATION_UNRESOLVABLE',
        message: 'EfS 27/2026 resolves to no message set',
      },
    });
    expect(detail).toBe('EfS 27/2026 resolves to no message set');
  });

  /** A bare code at least names the rule; describing nothing is worse. */
  it('falls back to the code when the refusal carries no message', () => {
    expect(
      publicationFilterErrorDetail({ status: 400, error: { code: 'PUBLICATION_NOT_FOUND' } }),
    ).toBe('PUBLICATION_NOT_FOUND');
  });

  /** Every other failure stays the list's, where the retry lives. */
  it('ignores a refusal that is not about a publication', () => {
    expect(
      publicationFilterErrorDetail({ status: 400, error: { code: 'AREA_UNRESOLVABLE' } }),
    ).toBeUndefined();
  });

  it('ignores a server error, which is not a filter the user can fix', () => {
    expect(
      publicationFilterErrorDetail({ status: 500, error: { code: 'PUBLICATION_UNRESOLVABLE' } }),
    ).toBeUndefined();
  });

  it('ignores a body that is not the coded envelope', () => {
    expect(publicationFilterErrorDetail({ status: 400, error: 'Bad Request' })).toBeUndefined();
    expect(publicationFilterErrorDetail(null)).toBeUndefined();
    expect(publicationFilterErrorDetail(undefined)).toBeUndefined();
  });
});
