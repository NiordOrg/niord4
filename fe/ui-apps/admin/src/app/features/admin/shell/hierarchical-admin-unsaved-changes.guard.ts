import { inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';

import { DiscardConfirmService } from '../../../shared/services/discard-confirm.service';

/** A hierarchical-admin page whose inline editor can hold unsaved changes. */
export interface UnsavedChangesHost {
  hasUnsavedChanges(): boolean;
}

/**
 * Shared CanDeactivate guard for the master-detail admin sections (Areas, Categories): blocks leaving
 * the section (rail / menu navigation, back button) while the inline editor has unsaved changes,
 * prompting through the app discard-confirm dialog (the router awaits the promise). The companion
 * `beforeunload` reload/tab-close prompt lives on each page's host binding (native mechanism).
 */
export const hierarchicalAdminUnsavedGuard: CanDeactivateFn<UnsavedChangesHost> = (component) => {
  if (!component.hasUnsavedChanges()) {
    return true;
  }
  return inject(DiscardConfirmService).confirmDiscard();
};
