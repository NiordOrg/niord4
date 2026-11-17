import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  TemplateRef,
  ViewContainerRef,
  viewChild,
} from '@angular/core';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import { Overlay, OverlayRef } from '@angular/cdk/overlay';
import { TemplatePortal } from '@angular/cdk/portal';
import { translateSignal, TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';
import { ConfirmService } from '../../services/confirm.service';

let confirmSeq = 0;

/**
 * The confirm surface — a centered modal `alertdialog` on `cdk/overlay` that
 * renders whatever the nearest `ConfirmService` publishes via `active()`. Mount
 * one `<app-confirm />` wherever a `ConfirmService` is provided (root, or a
 * component-scoped re-provide). The accept callback flows through the service;
 * this component takes no `@Output`.
 *
 * Backdrop click is intentionally inert (safer for destructive confirms); Esc
 * rejects. Focus is trapped + auto-captured, and restored to the opener on close.
 */
@Component({
  selector: 'app-confirm',
  imports: [CdkTrapFocus, Icon, TranslocoPipe],
  templateUrl: './confirm.html',
  host: { class: 'contents' },
})
export class Confirm {
  private readonly confirm = inject(ConfirmService);
  private readonly overlay = inject(Overlay);
  private readonly vcr = inject(ViewContainerRef);

  /** Panel width (a couple of hosts want it slightly wider). */
  readonly width = input('24rem');

  protected readonly req = computed(() => this.confirm.active());

  /** Ids of the message and the details list, which describe the dialog to a screen reader. */
  private readonly seq = confirmSeq++;
  protected readonly bodyId = `app-confirm-body-${this.seq}`;
  protected readonly detailsId = `app-confirm-details-${this.seq}`;
  private readonly panel = viewChild.required<TemplateRef<unknown>>('panel');

  private readonly defaultAccept = translateSignal('shared.confirm.accept');
  private readonly defaultReject = translateSignal('shared.confirm.reject');
  protected readonly acceptLabel = computed(() => this.req()?.acceptLabel ?? this.defaultAccept());
  protected readonly rejectLabel = computed(() => this.req()?.rejectLabel ?? this.defaultReject());

  private overlayRef: OverlayRef | null = null;
  private restoreFocusTo: HTMLElement | null = null;

  constructor() {
    effect(() => {
      if (this.req() && !this.overlayRef) this.openOverlay();
      else if (!this.req() && this.overlayRef) this.closeOverlay();
    });
    inject(DestroyRef).onDestroy(() => this.overlayRef?.dispose());
  }

  private openOverlay(): void {
    const active = document.activeElement;
    this.restoreFocusTo = active instanceof HTMLElement ? active : null;
    this.overlayRef = this.overlay.create({
      positionStrategy: this.overlay.position().global().centerHorizontally().centerVertically(),
      scrollStrategy: this.overlay.scrollStrategies.block(),
      hasBackdrop: true,
      backdropClass: 'confirm-backdrop',
      panelClass: 'confirm-pane',
    });
    this.overlayRef.attach(new TemplatePortal(this.panel(), this.vcr));
    this.overlayRef.keydownEvents().subscribe((event) => {
      if (event.key === 'Escape') {
        event.stopPropagation();
        this.reject();
      }
    });
    // Backdrop click is intentionally NOT wired — a destructive confirm should
    // not dismiss on an accidental outside click.
  }

  private closeOverlay(): void {
    this.overlayRef?.dispose();
    this.overlayRef = null;
    if (this.restoreFocusTo?.isConnected) this.restoreFocusTo.focus();
    this.restoreFocusTo = null;
  }

  protected accept(): void {
    const cb = this.req()?.accept;
    // Clear BEFORE running the callback so a confirm chained inside it can open.
    this.confirm.dismiss();
    cb?.();
  }

  protected reject(): void {
    const cb = this.req()?.reject;
    // Mirror accept(): clear BEFORE the callback so a chained confirm can open.
    this.confirm.dismiss();
    cb?.();
  }
}
