import { DestroyRef, ErrorHandler, Injector, Type } from '@angular/core';
import type { DialogConfig, DialogRef } from '@angular/cdk/dialog';

/**
 * The chrome the CDK owns around every dialog of this site: the scrim, the pane class the
 * `.dialog-panel` sizes itself in, and the width the panels have always had. It is passed at
 * each open rather than provided under `DEFAULT_DIALOG_CONFIG`, because a configuration
 * provided under that token REPLACES the CDK's own defaults instead of extending them —
 * `role="dialog"`, the focus capture and restore, and closing on Escape and on navigation
 * would all have to be restated here to survive.
 */
const INFO_DIALOG_CONFIG = {
  backdropClass: 'dialog-backdrop',
  panelClass: 'dialog-pane',
  width: 'min(56.25rem, 92vw)',
  maxWidth: 'min(56.25rem, 92vw)',
  ariaModal: true,
} satisfies Partial<DialogConfig>;

/**
 * The open that is fetching its dialog. Nothing on the site opens a dialog over another, and
 * on a slow link a second click on a row while the first is still loading would otherwise
 * stack a second, identical dialog on top of it.
 */
let pending: Promise<unknown> | undefined;

/**
 * Opens one of the site's dialogs: fetches the CDK's overlay machinery and the dialog component
 * itself, then hands the component the data its opener has to give it. Resolves to the opened
 * dialog, or to `undefined` when nothing was opened.
 *
 * Both are fetched rather than imported, because neither belongs in what a visit transfers
 * before a reader clicks, and nothing is open until one does. A static `Dialog` injection is
 * enough to put the overlay, the portal and the focus trap into the chunk of whichever view
 * injects it, and the message list's chunk is part of every first visit: the list is where the
 * site's root lands. `Dialog` is root-provided, so the injector any component already carries
 * can supply it once the module has arrived.
 *
 * The fetch takes as long as the link does, and three things can happen meanwhile. A request
 * made while another is loading is dropped. An opener that is gone by the time the fetch lands
 * — the reader followed a link to another page — opens nothing: the dialog would stand over a
 * page it has nothing to do with. And a fetch that fails is reported and opens nothing; the
 * next request fetches again. The page is not reloaded for it: on a failing link a reload can
 * swap a page the reader can still read for the browser's offline page.
 */
export function openInfoDialog<C>(
  injector: Injector,
  loadComponent: () => Promise<Type<C>>,
  data: unknown,
): Promise<DialogRef<unknown, C> | undefined> {
  if (pending) return Promise.resolve(undefined);
  const opener = injector.get(DestroyRef, null);
  const open = (async () => {
    try {
      const [{ Dialog }, component] = await Promise.all([
        import('@angular/cdk/dialog'),
        loadComponent(),
      ]);
      if (opener?.destroyed) return undefined;
      return injector.get(Dialog).open(component, { ...INFO_DIALOG_CONFIG, data });
    } catch (error) {
      injector.get(ErrorHandler).handleError(error);
      return undefined;
    } finally {
      pending = undefined;
    }
  })();
  pending = open;
  return open;
}
