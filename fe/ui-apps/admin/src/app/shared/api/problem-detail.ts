import { HttpErrorResponse } from '@angular/common/http';

/**
 * Pulls the human-readable `detail` out of an RFC 9457 problem document.
 *
 * The backend renders every REST error as `application/problem+json`
 * (`spring.mvc.problemdetails.enabled`), and `detail` is the only field that
 * names the actual cause — "Unknown dataset <uuid>", "MIR returned 401". The
 * rest (type/title/status/instance) is boilerplate the operator already knows,
 * so dropping `detail` on the floor and rendering Angular's generic
 * "Http failure response for …: 400 Bad Request" loses the whole message.
 *
 * TWO body shapes have to be handled, because Angular parses the body according
 * to the REQUEST's `responseType`, not the response's content type:
 *
 * - a JSON request (the renewal POST) leaves `error` an already-parsed OBJECT;
 * - a TEXT request (the GML fetch, which must not JSON-parse a GML body) leaves
 *   `error` the raw problem-document STRING.
 *
 * Handling only the first is why a deleted-since-listed dataset used to show a
 * generic transport message instead of "Unknown dataset …".
 *
 * Returns `undefined` when there is no usable detail, so each caller picks its
 * own fallback rather than rendering an empty string.
 */
export function problemDetail(err: unknown): string | undefined {
  if (!(err instanceof HttpErrorResponse)) {
    return undefined;
  }
  const body: unknown = err.error;
  if (typeof body !== 'string') {
    return detailOf(body);
  }
  // A text-typed request hands back the problem document unparsed.
  const trimmed = body.trim();
  if (!trimmed.startsWith('{')) {
    return undefined;
  }
  try {
    return detailOf(JSON.parse(trimmed));
  } catch {
    // Not JSON after all — the caller falls back to the raw body.
    return undefined;
  }
}

function detailOf(body: unknown): string | undefined {
  if (typeof body !== 'object' || body === null || !('detail' in body)) {
    return undefined;
  }
  const detail: unknown = (body as { detail?: unknown }).detail;
  return typeof detail === 'string' && detail !== '' ? detail : undefined;
}
