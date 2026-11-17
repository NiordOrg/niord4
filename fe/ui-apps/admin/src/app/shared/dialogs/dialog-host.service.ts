import { signal } from '@angular/core';
import { Observable, Subject, take } from 'rxjs';

/**
 * Base for the host-component dialog pattern. The Messages route mounts a
 * single component per dialog kind; that component reads `visible` for its
 * `<app-dialog [(visible)]>` binding, and the orchestrating caller subscribes
 * to the observable returned by `open(...)` to receive the user's result (or
 * `undefined` on dismissal).
 *
 * Subclasses:
 *   - Call `armOpen()` before flipping any other per-open state, then return
 *     `this.result$.pipe(take(1))`.
 *   - Call `submit(value)` on user confirmation; emits `value` and hides.
 *   - Call `dismiss()` on cancel / X / Escape; emits `undefined` and hides.
 *
 * Each open() call uses a fresh Subject so subscribers from a prior call
 * cannot accidentally receive a later open's result.
 */
export abstract class DialogHostService<TResult> {
  readonly visible = signal(false);

  protected result$ = new Subject<TResult | undefined>();

  /** Arm a new pipeline and reveal the dialog. Subclasses call this from their typed open(...). */
  protected armOpen(): Observable<TResult | undefined> {
    // If a prior open is still live (re-entrant open without submit/dismiss),
    // resolve it as a dismissal before replacing — otherwise the prior caller
    // sits on a Subject that will never emit.
    if (!this.result$.closed) {
      this.result$.next(undefined);
      this.result$.complete();
    }
    this.result$ = new Subject<TResult | undefined>();
    this.visible.set(true);
    return this.result$.pipe(take(1));
  }

  /** Emit a user-submitted value and hide the dialog. */
  submit(value: TResult): void {
    this.visible.set(false);
    this.result$.next(value);
    this.result$.complete();
  }

  /** Emit undefined (user cancelled / escaped / closed) and hide. No-op when already hidden. */
  dismiss(): void {
    if (!this.visible()) return;
    this.visible.set(false);
    this.result$.next(undefined);
    this.result$.complete();
  }

  /**
   * Binds directly to `<app-dialog (visibleChange)>` — dismisses when the dialog
   * tries to flip visibility to false (X / ESC / outside click).
   */
  onVisibleChange(visible: boolean): void {
    if (!visible) this.dismiss();
  }
}
