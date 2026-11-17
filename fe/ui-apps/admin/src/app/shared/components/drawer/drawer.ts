import {
  Component,
  computed,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  TemplateRef,
  untracked,
  ViewContainerRef,
  viewChild,
} from '@angular/core';
import { CdkTrapFocus } from '@angular/cdk/a11y';
import { Overlay, OverlayRef } from '@angular/cdk/overlay';
import { TemplatePortal } from '@angular/cdk/portal';
import { TranslocoPipe } from '@jsverse/transloco';

import { DiscardConfirmService } from '../../services/discard-confirm.service';

import { DrawerGateService } from './drawer-gate.service';
import { Icon } from '../../icons/icon';

export type DrawerSize = 'default' | 'wide' | 'xwide';

/** Standard width tiers, clamped so the drawer never exceeds the viewport. */
const DRAWER_WIDTHS: Record<DrawerSize, string> = {
  default: 'min(520px, 100vw)',
  wide: 'min(820px, 100vw)',
  xwide: 'min(1100px, 100vw)',
};

/**
 * From `md` (Tailwind's 48rem) up the pane leaves a strip of dimmed page beside
 * it so a tap on the backdrop can dismiss; below it the pane is the viewport.
 * Applied through `updateSize` because the CDK writes the pane's size as inline
 * styles, which no stylesheet rule can beat.
 */
const PANE_CAP_QUERY = '(min-width: 48rem)';
const paneCapFor = (mdUp: boolean): string => (mdUp ? '90vw' : '100vw');

/** Fallback (ms) that disposes the overlay if the slide-out `transitionend`
 *  never arrives (e.g. reduced-motion or a detached pane). */
const CLOSE_ANIMATION_FALLBACK_MS = 450;

/** `focusVisible` (Chromium/Firefox) is not yet in TypeScript's FocusOptions. */
interface FocusVisibleOptions extends FocusOptions {
  focusVisible?: boolean;
}

/**
 * Shared right-drawer wrapper — drawers in this app are only built through
 * this component. It supplies the mechanism (a CDK overlay: modal backdrop,
 * scroll-lock, focus trap, slide-in) and the chrome (header bar with title +
 * ✕, scrollable body, optional `[drawerFooter]` slot), and owns every close
 * path.
 *
 * The parent owns visibility: bind `[visible]`, react to `(closed)`. The
 * drawer never flips its own state. `(hidden)` follows later, when the pane has
 * finished sliding out — the signal a parent waits for before destroying the
 * component the drawer lives in.
 *
 * Close paths all funnel through `requestClose()` so the unsaved-changes gate
 * runs once per intent:
 * - ✕ / footer Cancel buttons call it directly.
 * - Escape arrives on `overlayRef.keydownEvents()`. CDK's keyboard dispatcher
 *   routes Escape to the TOPMOST overlay only, so a popover/menu/dialog opened
 *   above the drawer (all CDK overlays now) consumes it first — no stand-down
 *   bookkeeping is needed. A `defaultPrevented` Escape (an inner widget closed
 *   its own popup) is ignored.
 * - Backdrop clicks arrive on `overlayRef.backdropClick()`. Drag-safety is
 *   inherent: the panel overlays the backdrop, so press-inside/release-outside
 *   never lands a backdrop click.
 *
 * Usage notes: a footer rendered under `@if` must use
 * `<ng-container ngProjectAs="[drawerFooter]">` or it lands in the body slot.
 * DialogHostService-driven drawers bind `[visible]="svc.visible()"
 * (closed)="svc.dismiss()"`. The inner root carries role="dialog" + aria-modal
 * deliberately — a modal drawer IS a dialog to assistive tech.
 */
@Component({
  selector: 'app-drawer',
  imports: [CdkTrapFocus, Icon, TranslocoPipe],
  templateUrl: './drawer.html',
  host: {
    class: 'contents',
    '(window:beforeunload)': 'onBeforeUnload($event)',
  },
})
export class Drawer {
  private readonly overlay = inject(Overlay);
  private readonly vcr = inject(ViewContainerRef);
  private readonly discardConfirm = inject(DiscardConfirmService);
  private readonly gate = inject(DrawerGateService);

  /** Parent-owned visibility; the drawer only requests closes via `closed`. */
  readonly visible = input<boolean>(false);
  /** Header title as an i18n key — the drawer owns the translation. */
  readonly header = input<string>('');
  /** Width tier: 'default' (520px), 'wide' (820px) or 'xwide' (1100px), viewport-clamped. */
  readonly size = input<DrawerSize>('default');
  /** Unsaved-changes flag — gates every close intent, refresh and navigation. */
  readonly dirty = input<boolean>(false);
  /**
   * Where the caret lands when the drawer opens.
   *
   * `'close'` (the default) puts it on the ✕, which is right for a drawer that
   * is read first — a detail panel, a picker, anything whose body is not a form.
   * `'first'` puts it in the first writable field instead, for an editor whose
   * whole purpose is the typing: landing on the ✕ there costs a Tab before the
   * first keystroke and makes the first key a reader presses the one that
   * closes the drawer. Falls back to the ✕ when the body has no field.
   */
  readonly initialFocus = input<'close' | 'first'>('close');
  /** Emitted when a close intent passes the gate. The parent flips `visible`. */
  readonly closed = output<void>();
  /**
   * Emitted once the pane has finished sliding out and the overlay is gone.
   *
   * The pair reads as two steps of one close: `closed` is the drawer ASKING to
   * be closed — the parent answers by clearing `visible` — and `hidden` says it
   * is off screen, so a parent that only mounts the drawer's host while it is
   * needed can destroy that host without cutting the animation short. Emitted
   * from both ends of the slide-out (the `transitionend` and the fallback
   * timer), exactly once, and never when a reopen cancels the close.
   */
  readonly hidden = output<void>();

  private readonly panel = viewChild.required<TemplateRef<unknown>>('panel');

  protected readonly width = computed(() => DRAWER_WIDTHS[this.size()]);

  /** The viewport-band cap on the pane (see PANE_CAP_QUERY); 100vw where there is no window. */
  private readonly maxWidth = signal(paneCapFor(false));

  /** Live overlay while the drawer is open (kept during the slide-out too). */
  private overlayRef: OverlayRef | null = null;
  /** Cancels a pending slide-out (reopen mid-animation reuses the overlay). */
  private cancelCloseAnimation: (() => void) | null = null;

  /** Element that held focus before the drawer opened; restored on close. */
  private restoreFocusTo: HTMLElement | null = null;
  /** Whether the opener showed a focus ring — i.e. the drawer was opened by keyboard. */
  private openerHadFocusRing = false;

  constructor() {
    // Mirror the open+dirty state into the gate registry so
    // drawerUnsavedChangesGuard can block in-app navigation — the one close
    // path the drawer itself cannot intercept.
    effect(() => this.gate.setDirty(this, this.visible() && this.dirty()));

    // Capture/restore the opener's focus (+ ring state) around the trap. Kept
    // hand-rolled: CDK's auto-capture cannot round-trip the focus-visible ring.
    effect(() => {
      if (this.visible()) {
        // Opening edge: capture the trigger before the focus trap moves focus.
        const active = document.activeElement;
        this.restoreFocusTo = active instanceof HTMLElement ? active : null;
        this.openerHadFocusRing = this.restoreFocusTo?.matches(':focus-visible') ?? false;
      } else {
        // Closing edge (and a no-op first run). isConnected guards against
        // the opener having been removed meanwhile (e.g. a list re-render).
        // The ring state is forced rather than left to the browser heuristic:
        // closing with Esc counts as recent keyboard activity, which would
        // paint a focus ring on a mouse-opened row. The opener's own ring
        // state at open time is what round-trips.
        if (this.restoreFocusTo?.isConnected) {
          const options: FocusVisibleOptions = { focusVisible: this.openerHadFocusRing };
          this.restoreFocusTo.focus(options);
        }
        this.restoreFocusTo = null;
      }
    });

    // Drive the overlay off `visible()` only — untracked so a live `[size]`
    // change (below) never re-triggers open()/close().
    effect(() => {
      if (this.visible()) untracked(() => this.open());
      else untracked(() => this.close());
    });

    // Keep the width reactive while open ([size] can change mid-life, and so can
    // the viewport band that decides the cap).
    effect(() => {
      const width = this.width();
      const maxWidth = this.maxWidth();
      this.overlayRef?.updateSize({ width, maxWidth, height: '100%' });
    });

    // The cap follows the viewport: `matchMedia` fires on rotation and resize.
    const query = typeof window !== 'undefined' ? window.matchMedia?.(PANE_CAP_QUERY) : undefined;
    if (query) {
      this.maxWidth.set(paneCapFor(query.matches));
      const onChange = (e: MediaQueryListEvent) => this.maxWidth.set(paneCapFor(e.matches));
      query.addEventListener('change', onChange);
      inject(DestroyRef).onDestroy(() => query.removeEventListener('change', onChange));
    }

    inject(DestroyRef).onDestroy(() => {
      this.cancelCloseAnimation?.();
      this.overlayRef?.dispose();
      this.overlayRef = null;
      this.gate.remove(this);
    });
  }

  private open(): void {
    // Reopening during a slide-out: cancel the pending detach, reuse the pane.
    this.cancelCloseAnimation?.();
    this.cancelCloseAnimation = null;

    if (!this.overlayRef) {
      const ref = this.overlay.create({
        positionStrategy: this.overlay.position().global().top('0').right('0'),
        scrollStrategy: this.overlay.scrollStrategies.block(),
        hasBackdrop: true,
        backdropClass: 'niord-drawer-backdrop',
        // Insert already open; `is-open` holds the resting position (translateX(0))
        // and drives the CSS slide-OUT on close.
        panelClass: ['niord-drawer-panel', 'is-open'],
        disposeOnNavigation: false,
      });
      ref.attach(new TemplatePortal(this.panel(), this.vcr));
      ref.updateSize({ width: this.width(), maxWidth: this.maxWidth(), height: '100%' });
      // Slide IN with an explicit animation, run SYNCHRONOUSLY here — before the
      // pane's first paint — so the entrance is deterministic. A pure-CSS entrance
      // isn't reliable through a CDK overlay: CDK applies `panelClass` (incl.
      // is-open) as a mutation AFTER DOM insertion, so `@starting-style` never
      // fires, and a bare rAF lands a frame late (the pane flashes fully open
      // first). The Web Animations API is paint-timing-independent; `is-open`
      // holds the end state after it. Optional-chained for DOMs without WAAPI
      // (unit-test env) — they just skip the slide.
      ref.overlayElement.animate?.(
        [{ transform: 'translateX(100%)' }, { transform: 'translateX(0)' }],
        { duration: 300, easing: 'ease' },
      );
      ref.backdropClick().subscribe(() => this.requestClose());
      ref.keydownEvents().subscribe((event) => {
        if (event.key !== 'Escape') return;
        // A widget inside the drawer consumed this Esc (e.g. a datepicker
        // closing its calendar). Topmost dispatch already keeps a stacked
        // CDK overlay from ever leaking Escape down here.
        if (event.defaultPrevented) return;
        this.requestClose();
      });
      this.overlayRef = ref;
    } else {
      // Reused pane (reopen mid slide-out): it's already painted, so re-adding
      // is-open lets the CSS transition animate from its current position.
      this.overlayRef.addPanelClass('is-open');
    }

    const ref = this.overlayRef;
    // Restore the dim if a prior slide-out had begun to fade it out.
    ref.backdropElement?.classList.add('cdk-overlay-backdrop-showing');
    // Land focus inside the panel so the trap and Esc work immediately. The
    // ring state is forced to match the opener's: a plain script focus() reads
    // as :focus-visible in Chromium, which would hand a spurious ring back to a
    // mouse-opened row on close.
    queueMicrotask(() => this.landInitialFocus());
  }

  /**
   * The ✕, or the first field of the body.
   *
   * Fields only — an input, a textarea or a select. A button is not where a
   * writer starts, and the first focusable node of a tabbed editor is a tab
   * rather than the name they came to type.
   */
  private landInitialFocus(): void {
    const pane = this.overlayRef?.overlayElement;
    if (!pane) return;
    const field =
      this.initialFocus() === 'first'
        ? pane.querySelector<HTMLElement>(
            '[data-drawer-body] input:not([type="hidden"]):not([type="file"]):not([disabled]),' +
              ' [data-drawer-body] textarea:not([disabled]),' +
              ' [data-drawer-body] select:not([disabled])',
          )
        : null;
    const target = field ?? pane.querySelector<HTMLElement>('header .icon-button');
    const options: FocusVisibleOptions = { focusVisible: this.openerHadFocusRing };
    target?.focus(options);
  }

  private close(): void {
    const ref = this.overlayRef;
    if (!ref || this.cancelCloseAnimation) return; // not open, or already closing

    const pane = ref.overlayElement;
    ref.removePanelClass('is-open'); // slide the panel back out
    ref.backdropElement?.classList.remove('cdk-overlay-backdrop-showing'); // fade dim in parallel

    let finished = false;
    const finish = (): void => {
      if (finished) return;
      finished = true;
      pane.removeEventListener('transitionend', onTransitionEnd);
      clearTimeout(timer);
      this.cancelCloseAnimation = null;
      this.overlayRef = null;
      ref.dispose();
      // Last, and only on this path: the pane is off screen and disposed, so a
      // listener is free to tear down whatever hosts this drawer. A reopen
      // mid-slide runs `cancelCloseAnimation` instead and never gets here.
      this.hidden.emit();
    };
    const onTransitionEnd = (event: TransitionEvent): void => {
      if (event.target === pane && event.propertyName === 'transform') finish();
    };
    pane.addEventListener('transitionend', onTransitionEnd);
    const timer = setTimeout(finish, CLOSE_ANIMATION_FALLBACK_MS);

    // Reopen-mid-close neutralizes this pending detach and leaves the pane
    // attached; destroy calls it before disposing to stop the dangling timer.
    this.cancelCloseAnimation = () => {
      finished = true;
      pane.removeEventListener('transitionend', onTransitionEnd);
      clearTimeout(timer);
    };
  }

  protected onBeforeUnload(event: BeforeUnloadEvent): void {
    if (this.visible() && this.dirty()) {
      event.preventDefault();
    }
  }

  // Public: footer Cancel buttons route through here too, so every close
  // intent passes the same unsaved-changes gate.
  async requestClose(): Promise<void> {
    // App discard-confirm dialog — the same surface as the route guards; only the
    // beforeunload refresh/tab-close leg stays on the native mechanism.
    if (this.dirty() && !(await this.discardConfirm.confirmDiscard())) {
      return;
    }
    this.closed.emit();
  }
}
