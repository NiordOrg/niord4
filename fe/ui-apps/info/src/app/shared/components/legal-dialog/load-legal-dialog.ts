import type { Type } from '@angular/core';

import type { LegalDialogComponent } from './legal-dialog.component';

/**
 * Fetches the legal dialog, for `openInfoDialog(injector, loadLegalDialog, { documentId })`.
 *
 * A country's footer opens it through the country API, which exports this loader rather than the
 * component: a static re-export would pull the dialog into every first visit, and this way it
 * stays a chunk of its own, fetched when a reader opens a legal document.
 */
export function loadLegalDialog(): Promise<Type<LegalDialogComponent>> {
  return import('./legal-dialog.component').then((m) => m.LegalDialogComponent);
}
