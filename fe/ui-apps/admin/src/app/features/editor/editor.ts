import { Component, inject, type OnDestroy } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { MessageComparisonDialog } from '../messages/dialogs/message-comparison-dialog/message-comparison-dialog';
import { MessageCopyDialog } from '../messages/dialogs/message-copy-dialog/message-copy-dialog';
import { MessagePrintDialog } from '../messages/dialogs/message-print-dialog/message-print-dialog';
import { MessageDetailsDialog } from '../messages/message-details-dialog/message-details-dialog';
import { EditorActionMenu } from './components/editor-action-menu/editor-action-menu';
import { EditorCommentsStore } from './services/editor-comments.store';
import { EditorFacade } from './services/editor-facade';

/**
 * Editor shell: the persistent chrome (view-mode tabs + cog action menu) wrapped around the
 * routed edit / status / comments / history child views. The route provides `EditorFacade`, so the
 * shell and every child share one editor-scoped state instance.
 *
 * NOTE the route-`providers` injector outlives the visit (Angular ties it to the route CONFIG),
 * so the facade instance is NOT recreated on re-entry — the shell's destroy explicitly drops the
 * working state, otherwise a discarded draft edit would resurface the next time the same message
 * is opened.
 *
 * The save-gate spans the whole editor: unsaved edits survive tab switches, so the tab-close
 * `beforeunload` prompt is bound HERE (not on the edit form) to cover every tab. A half-typed comment
 * (kept on the route's `EditorCommentsStore`) asks too, and is dropped with the working state.
 *
 * Hosts the shared message-details dialog so the drafts widget can open a read-only preview
 * (seeded with the drafts list for prev/next) from inside the editor, and the print dialog that
 * the Actions menu's Print opens (`MessagePrintService.printMessage` only arms the root dialog
 * service; without a host here nothing is drawn).
 */
@Component({
  selector: 'app-editor',
  imports: [
    RouterOutlet,
    EditorActionMenu,
    MessageDetailsDialog,
    MessageComparisonDialog,
    MessageCopyDialog,
    MessagePrintDialog,
  ],
  template: `
    <app-editor-action-menu />
    <router-outlet />
    <app-message-details-dialog />
    <app-message-comparison-dialog />
    <app-message-copy-dialog />
    <app-message-print-dialog />
  `,
  // Centers the editor content at `max-w-screen-2xl` (the cap moved here off
  // `<main>` so admin/sysadmin can run full-width).
  host: {
    class: 'mx-auto flex w-full max-w-screen-2xl flex-1 flex-col',
    '(window:beforeunload)': 'onBeforeUnload($event)',
  },
})
export class EditorShell implements OnDestroy {
  private readonly facade = inject(EditorFacade);
  private readonly commentsStore = inject(EditorCommentsStore);

  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.facade.hasUnsavedChanges() || this.commentsStore.hasComposeDraft()) {
      event.preventDefault();
    }
  }

  ngOnDestroy(): void {
    this.facade.resetWorkingState();
    this.commentsStore.clearComposeDraft();
  }
}
