/**
 * Whether a failed message search failed because of the publication filter.
 *
 * A publication that cannot be resolved to a set of messages is refused with a
 * `400` naming the reason — and the refusal is about ONE chip, not about the
 * list. Reported as a list-level failure it reads as "search is broken"; not
 * reported at all it reads as "this publication contains nothing", which is the
 * worse of the two because it looks like an answer.
 *
 * Matched on the code's prefix rather than an enumeration of codes: the
 * catalogue names several publication refusals and will name more, and a filter
 * that stops explaining itself the day a code is added is a filter that will
 * silently go back to looking empty.
 *
 * Pure on purpose — the caller owns the translation.
 */
interface SearchErrorResponse {
  readonly status?: number;
  readonly error?: { readonly code?: string; readonly message?: string } | string | null;
}

const PUBLICATION_CODE_PREFIX = 'PUBLICATION';

/**
 * The detail to show under the publication chip, or `undefined` when the failure
 * was not the publication filter's.
 */
export function publicationFilterErrorDetail(response: unknown): string | undefined {
  const failure = response as SearchErrorResponse | undefined | null;
  if (!failure || failure.status !== 400) {
    return undefined;
  }
  const body = failure.error;
  if (!body || typeof body === 'string') {
    return undefined;
  }
  if (!body.code?.startsWith(PUBLICATION_CODE_PREFIX)) {
    return undefined;
  }
  // The message where the server wrote one, the code otherwise: an admin acts on
  // "the publication has no resolvable message set", and a bare code at least
  // names the rule rather than describing nothing.
  return body.message?.trim() || body.code;
}
