import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { EditorFacade } from '../services/editor-facade';
import { TemplateMessageStore } from '../services/template-message-store';

/** The template execution page (the page being left while the guards run is `Router.url`). */
const EXECUTION_URL = /^\/editor\/execute-template(?:[/?;#]|$)/;

/**
 * CanActivate guard for the transient template `edit` route (a create hand-off with no URL id).
 * The Start-screen create buttons and the template-execution view seed {@link TemplateMessageStore}
 * synchronously just before navigating here, so a legitimate entry always finds it populated. A
 * page reload / deep-link re-bootstraps the app with an empty store, which would otherwise surface
 * a "No template message" error panel with a dead Retry; redirect those to the Editor Start screen
 * instead (mirrors the execute-template view's missing-seed → /editor behaviour). Peeks the readonly
 * signal — the EditForm still consumes the seed on mount.
 *
 * A return through the history also finds the store empty, because the edit form used the seed
 * up. Back from the execution page to the draft that "Run template…" was opened on would then hide
 * the draft on Start: when the page being left is the execution page and the working copy is a
 * message the database does not hold, re-seed the store from it (as the execution view's own
 * Cancel does) and let the draft open again. Any other page that reaches here with an unstored
 * working copy has already asked about replacing it (`unsavedChangesGuard`, a replacing URL), so
 * that copy is dropped before the redirect, which would otherwise ask a second time on the way to
 * Start. A reload, or a Forward after a discard, finds the facade empty and goes to Start.
 */
export const templateSeedGuard: CanActivateFn = () => {
  const store = inject(TemplateMessageStore);
  if (store.template() !== undefined) {
    return true;
  }
  const router = inject(Router);
  const facade = inject(EditorFacade);
  const working = facade.message();
  if (working && !working.created && facade.hasUnsavedChanges()) {
    if (EXECUTION_URL.test(router.url)) {
      store.set(working);
      return true;
    }
    facade.resetWorkingState();
  }
  return router.parseUrl('/editor');
};
