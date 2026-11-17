import { inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';

import { DiscardConfirmService } from '../../../shared/services/discard-confirm.service';
import { EditorFacade } from '../services/editor-facade';
import { confirmStartWithUnstoredMessage, replacesWorkingCopy } from './unsaved-changes.guard';

/** The minimal surface the guard needs from the execution view (avoids a component import). */
interface HasLocalDirtiness {
  isDirty(): boolean;
}

/** Whether a target URL stays inside the editor shell (so the parent `editorExitGuard` won't fire). */
const STAYS_IN_EDITOR = /^\/editor(?:[/?;#]|$)/;

/**
 * The bare template `edit` route (no id). From this page it is Back to the draft the page was
 * opened from, which `templateSeedGuard` re-seeds from the working copy, so it replaces nothing.
 */
const TEMPLATE_EDIT_URL = /^\/editor\/edit\/?(?:[?#;]|$)/;

/**
 * CanDeactivate guard for the `execute-template` route. The execution view edits a LOCAL working
 * copy (the shared facade's `userTouched` is never tripped in this mode), so the guard keys on the
 * view's own dirtiness instead. Execute and the view's own Cancel allow-list themselves via
 * {@link EditorFacade.bypassGuardOnce} (they run their own confirm); this guard covers the remaining
 * nav-away paths (browser back, a menu link) with the shared discard confirm.
 *
 * Leaving the editor entirely also deactivates the parent route, whose `editorExitGuard` prompts on
 * the shared facade's dirtiness. To avoid a double prompt we defer that case to the parent — but only
 * when the parent will actually prompt (the facade is dirty, e.g. entered from the in-editor "Run
 * template…" launcher with pending edits). When the facade is clean (the Start-screen entry) the
 * parent stays silent, so this guard must still handle the local dirtiness itself.
 *
 * The Start screen follows the same rule as on the other editor pages
 * ({@link confirmStartWithUnstoredMessage}): Back from a new message made by Execute lands here, the
 * view finds its seed used and goes to Start, and with a message the database does not hold that
 * move asks first. One prompt covers the view's own changes as well.
 *
 * A route that replaces the working copy follows the rule of the other editor pages too
 * ({@link replacesWorkingCopy}): "Run template…" is offered on a copy as well, and Back to
 * `/editor/copy/:id` fetches a fresh copy, so with unsaved work in the facade that move asks first
 * (one prompt, again covering the view's own changes). The bare `/editor/edit` passes: it is the
 * draft the page was opened from, re-seeded by `templateSeedGuard`.
 */
export const templateExecutionGuard: CanDeactivateFn<HasLocalDirtiness> = (
  component,
  _currentRoute,
  _currentState,
  nextState,
) => {
  const facade = inject(EditorFacade);
  if (facade.consumeGuardBypass()) {
    return true;
  }
  const toStart = confirmStartWithUnstoredMessage(facade, nextState.url);
  if (toStart) {
    return toStart;
  }
  if (
    facade.hasUnsavedChanges() &&
    !TEMPLATE_EDIT_URL.test(nextState.url) &&
    replacesWorkingCopy(facade, nextState.url)
  ) {
    return inject(DiscardConfirmService).confirmDiscard();
  }
  const leavingEditor = !STAYS_IN_EDITOR.test(nextState.url);
  if (leavingEditor && facade.hasUnsavedChanges()) {
    return true; // editorExitGuard (parent) prompts once for both
  }
  if (!component.isDirty()) {
    return true;
  }
  return inject(DiscardConfirmService).confirmDiscard();
};
