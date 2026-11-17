import { inject } from '@angular/core';
import { CanDeactivateFn } from '@angular/router';

import { DiscardConfirmService } from '../../../shared/services/discard-confirm.service';
import { EditorCommentsStore } from '../services/editor-comments.store';
import { EditorFacade } from '../services/editor-facade';

/** Editor routes whose activation REPLACES the working copy (edit / copy / template loads). */
const REPLACING_URL = /^\/editor\/(edit|copy)(\/|$)/;

/** The editor's Start screen: the bare `/editor`, with or without a query or fragment. */
const START_URL = /^\/editor\/?(?:[?#;]|$)/;

/** The `:id` of an `/editor/edit/:id` URL, or undefined for copy / template / non-edit URLs. */
function editUrlId(url: string): string | undefined {
  return /^\/editor\/edit\/([^/?;#]+)/.exec(url)?.[1];
}

/**
 * The Start-screen rule, shared by {@link unsavedChangesGuard} and `templateExecutionGuard` so the
 * two cannot drift apart. A stored message's working copy stays reachable from Start (opening the
 * same id again dedupes onto it), so going there with its edits needs no prompt. A message the
 * database does not hold (no `created`: a copy, a new message, a template's result) cannot be
 * reached again from Start — nothing links back to it — so going there asks first, the browser's
 * Back included; on Yes the working state is dropped, so neither the leave guard nor a later
 * Forward asks again for work that is gone.
 *
 * Returns the prompt's answer, or undefined when the rule does not decide (the next URL is not
 * Start, nothing is unsaved, or the message is stored) and the calling guard goes on. Call it
 * inside the guard's injection context.
 */
export function confirmStartWithUnstoredMessage(
  facade: EditorFacade,
  nextUrl: string,
): Promise<boolean> | undefined {
  if (!START_URL.test(nextUrl) || !facade.hasUnsavedChanges() || facade.message()?.created) {
    return undefined;
  }
  return inject(DiscardConfirmService)
    .confirmDiscard()
    .then((discard) => {
      if (discard) {
        facade.resetWorkingState();
      }
      return discard;
    });
}

/**
 * The replacing rule, shared by {@link unsavedChangesGuard} and `templateExecutionGuard` so the
 * two cannot drift apart: whether going to `nextUrl` replaces the working copy. An edit-family
 * route (`/editor/edit*`, `/editor/copy*`) loads afresh — a copy always fetches a new copy — except
 * the Edit tab of the SAME message, whose same-id load dedupes onto the live working copy, edits
 * intact. It says nothing about unsaved changes; the calling guard checks those.
 */
export function replacesWorkingCopy(facade: EditorFacade, nextUrl: string): boolean {
  if (!REPLACING_URL.test(nextUrl)) {
    return false;
  }
  const nextId = editUrlId(nextUrl);
  return nextId === undefined || nextId !== facade.message()?.id;
}

/**
 * Child guard on the editor's child routes: blocks navigating to an edit-family route while
 * there are genuine user edits — that navigation replaces the shared working copy. Switching to
 * the status / comments / history tabs keeps the working copy alive, so it passes silently;
 * leaving the editor entirely is {@link editorExitGuard}'s job (returning true here avoids a
 * double prompt). Keyed on `hasUnsavedChanges`: a touched edit whose content actually differs, or
 * a loaded message the database does not hold (a copy, a new message, a template's result), so
 * reverting an edit on a stored message does not nag, but leaving an unsaved copy asks.
 * Programmatic copy/template/reload navigation is allow-listed via `EditorFacade.bypassGuardOnce()`.
 *
 * The Start screen follows {@link confirmStartWithUnstoredMessage}: with a stored message's edits
 * it passes silently, with a message the database does not hold it asks first.
 *
 * A half-typed comment on the Comments tab (`EditorCommentsStore.hasComposeDraft`) counts like an
 * edit: it survives tab switches and the Start screen, and is dropped by a navigation that replaces
 * the working copy (the store keys it on the message id), so that one asks first.
 *
 * Prompts through the app discard-confirm dialog (the router awaits the promise); only the
 * companion `beforeunload` tab-close prompt stays on the native mechanism, which is all the
 * browser permits there.
 */
export const unsavedChangesGuard: CanDeactivateFn<unknown> = (
  _component,
  _currentRoute,
  _currentState,
  nextState,
) => {
  const facade = inject(EditorFacade);
  if (facade.consumeGuardBypass()) {
    return true;
  }
  if (!facade.hasUnsavedChanges() && !inject(EditorCommentsStore).hasComposeDraft()) {
    return true;
  }
  const toStart = confirmStartWithUnstoredMessage(facade, nextState.url);
  if (toStart) {
    return toStart;
  }
  if (!replacesWorkingCopy(facade, nextState.url)) {
    return true;
  }
  return inject(DiscardConfirmService).confirmDiscard();
};

/**
 * Parent guard on the editor route itself: blocks leaving the editor entirely while there are
 * genuine unsaved changes — the shell teardown drops the working copy — or while a half-typed comment
 * is held, which the teardown drops too. Tab switches never deactivate the parent route, so this
 * cannot fire inside the editor; the child routes carry {@link unsavedChangesGuard} for the
 * intra-editor navigations that replace the working copy.
 */
export const editorExitGuard: CanDeactivateFn<unknown> = () => {
  const facade = inject(EditorFacade);
  if (!facade.hasUnsavedChanges() && !inject(EditorCommentsStore).hasComposeDraft()) {
    return true;
  }
  return inject(DiscardConfirmService).confirmDiscard();
};
