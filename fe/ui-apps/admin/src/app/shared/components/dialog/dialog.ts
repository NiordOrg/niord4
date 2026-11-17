import {
  Component,
  contentChild,
  DestroyRef,
  effect,
  inject,
  input,
  model,
  output,
  TemplateRef,
  ViewContainerRef,
  viewChild,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import { Overlay, OverlayRef } from '@angular/cdk/overlay';
import { TemplatePortal } from '@angular/cdk/portal';
import { TranslocoPipe } from '@jsverse/transloco';

import { DiscardConfirmService } from '../../services/discard-confirm.service';

import { Icon } from '../../icons/icon';

let dialogSeq = 0;

/**
 * Shared centered-modal shell on the CDK overlay. Its inputs: `[visible]`
 * (two-way), `[header]` (rendered VERBATIM — consumers pass already-translated or
 * computed strings), `[modal]` `[closable]` `[closeOnEscape]` `[dismissableMask]`
 * `[style]` (the width mechanism, applied inline to the panel) `[panelClass]`, an
 * optional `[dirty]` unsaved-changes gate, and `#header` / `#footer` projection
 * templates. Emits `(opened)`/`(closed)`.
 *
 * CDK overlay: global centered position (fixed — never repositions), block
 * scroll, focus trap with auto-capture + restore (CdkTrapFocus autoCapture), Esc
 * + optional backdrop-click close. Portals to the body-level overlay container,
 * so projected content still resolves via `ownerDocument` queries in tests.
 *
 * A footer/header rendered under `@if` must use `<ng-container ngProjectAs>` or
 * a ref var so `contentChild` resolves it; today all dialog slots are unconditional.
 */
@Component({
  selector: 'app-dialog',
  imports: [CdkTrapFocus, Icon, NgTemplateOutlet, TranslocoPipe],
  templateUrl: './dialog.html',
  host: { class: 'contents', '(window:beforeunload)': 'onBeforeUnload($event)' },
})
export class Dialog {
  private readonly overlay = inject(Overlay);
  private readonly vcr = inject(ViewContainerRef);
  private readonly discardConfirm = inject(DiscardConfirmService);

  /** Two-way visibility — supplies `[visible]` + `(visibleChange)`. */
  readonly visible = model(false);
  /** Header title — rendered as-is (NOT translated by the shell). */
  readonly header = input('');
  readonly modal = input(true);
  readonly closable = input(true);
  readonly closeOnEscape = input(true);
  /** Backdrop click closes (default off — modal). */
  readonly dismissableMask = input(false);
  /** The width mechanism: inline styles on the panel, e.g. `{ width, maxWidth }`. */
  readonly style = input<Record<string, string> | null>(null);
  /**
   * Top offset of the (horizontally-centered) panel. Default `'5vh'`; near-fullscreen
   * modals (e.g. the geometry editor at
   * `height: calc(100vh - 2rem)`) pass a small value like `'1rem'` so they don't
   * overflow the bottom.
   */
  readonly top = input('5vh');
  /** Extra classes merged onto the portalled panel. */
  readonly panelClass = input('');
  /** Optional unsaved-changes gate — mirrors the drawer's. */
  readonly dirty = input(false);
  readonly opened = output<void>();
  readonly closed = output<void>();

  protected readonly titleId = `app-dialog-title-${dialogSeq++}`;
  private readonly panel = viewChild.required<TemplateRef<unknown>>('panel');
  protected readonly headerTpl = contentChild<TemplateRef<unknown>>('header');
  protected readonly footerTpl = contentChild<TemplateRef<unknown>>('footer');

  private overlayRef: OverlayRef | null = null;

  constructor() {
    effect(() => {
      if (this.visible() && !this.overlayRef) this.openOverlay();
      else if (!this.visible() && this.overlayRef) this.closeOverlay();
    });
    inject(DestroyRef).onDestroy(() => this.overlayRef?.dispose());
  }

  private openOverlay(): void {
    this.overlayRef = this.overlay.create({
      // Anchored near the top rather than vertically centered, so tall dialogs grow
      // downward from a stable position instead of recentering.
      positionStrategy: this.overlay.position().global().centerHorizontally().top(this.top()),
      scrollStrategy: this.overlay.scrollStrategies.block(),
      hasBackdrop: this.modal(),
      backdropClass: 'dialog-backdrop',
      panelClass: 'dialog-pane',
      disposeOnNavigation: true,
    });
    this.overlayRef.attach(new TemplatePortal(this.panel(), this.vcr));
    this.overlayRef.backdropClick().subscribe(() => {
      if (this.dismissableMask()) this.requestClose();
    });
    this.overlayRef.keydownEvents().subscribe((event) => {
      if (event.key === 'Escape' && this.closeOnEscape()) {
        event.stopPropagation();
        this.requestClose();
      }
    });
    this.opened.emit();
  }

  private closeOverlay(): void {
    this.overlayRef?.dispose();
    this.overlayRef = null;
    this.closed.emit();
  }

  protected async requestClose(): Promise<void> {
    // App discard-confirm dialog — the same surface as the drawer/route-guard gates.
    if (this.dirty() && !(await this.discardConfirm.confirmDiscard())) {
      return;
    }
    this.visible.set(false);
  }

  /**
   * Block a page reload / tab close while an open dialog holds unsaved changes —
   * matching the drawer's gate, so a `[dirty]` dialog (e.g. edit-as-text) is
   * protected against browser-level unload, not just in-app close.
   */
  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.visible() && this.dirty()) {
      event.preventDefault();
    }
  }
}
