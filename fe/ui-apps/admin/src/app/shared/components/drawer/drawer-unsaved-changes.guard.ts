import { afterNextRender, inject } from '@angular/core';
import type { CanDeactivateFn } from '@angular/router';

import { DiscardConfirmService } from '../../services/discard-confirm.service';
import { DrawerGateService } from './drawer-gate.service';

/**
 * Blocks in-app navigation away from a route while an open drawer has unsaved
 * changes. Attach to any route that hosts change-gated drawers.
 *
 * Prompts through the app discard-confirm dialog (the router awaits the promise) —
 * the same surface as the drawer's own close gate; only the `beforeunload`
 * refresh/tab-close leg stays on the native mechanism.
 *
 * A dirty registry is read again after the next render before it asks. A page
 * that closes its drawer and navigates in one turn — a series editor's Save
 * that lands on the new series — has cleared the drawer's `visible` only in its
 * own signals; the drawer hears it, and leaves the registry, when the page is
 * next rendered. Asking at once would offer to discard what was just saved.
 */
export const drawerUnsavedChangesGuard: CanDeactivateFn<unknown> = () => {
  const gate = inject(DrawerGateService);
  if (!gate.anyDirty()) {
    return true;
  }
  const discardConfirm = inject(DiscardConfirmService);
  return new Promise<boolean>((resolve) => {
    afterNextRender(() => resolve(gate.anyDirty() ? discardConfirm.confirmDiscard() : true));
  });
};
