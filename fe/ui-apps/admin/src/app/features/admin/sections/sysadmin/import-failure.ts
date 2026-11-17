import { HttpErrorResponse } from '@angular/common/http';

import { problemDetail } from '../../../../shared/api/problem-detail';

// What the two sysadmin import dialogs (Script resources, Dictionaries) read from the backend's answers.

/** How the backend's answer begins when the import job started (`AbstractBatchableRestService`). */
export const JOB_STARTED = "Started batch job '";

/** A failed preview or import: the translated sentence, and the backend's reason for a 4xx. */
export interface ImportFailure {
  readonly message: string;
  /** The backend's own text, shown as it sends it; `undefined` for 5xx, status 0 or no text. */
  readonly reason?: string;
}

/**
 * The reason a 4xx carries: a problem document's `detail` (parsed, or the text of a text-typed
 * request), else a plain-text body. An HTML error page or an empty body gives none.
 */
export function backendReason(error: unknown): string | undefined {
  const detail = problemDetail(error);
  if (detail !== undefined) {
    return detail;
  }
  const body: unknown = error instanceof HttpErrorResponse ? error.error : undefined;
  const text = typeof body === 'string' ? body.trim() : '';
  return text !== '' && !text.startsWith('{') && !text.startsWith('<') ? text : undefined;
}
