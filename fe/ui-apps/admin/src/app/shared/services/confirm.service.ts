import { Injectable, signal } from '@angular/core';

/** Shared options for an intent-styled confirm. Labels, header and details are optional. */
export interface ConfirmOptions {
  /** Header title (already translated). */
  readonly header?: string;
  /** Body message (already translated). */
  readonly message: string;
  /**
   * Lines shown as a list under the message (already translated), e.g. what else a delete
   * affects. Omitted or empty, no list renders.
   */
  readonly details?: readonly string[];
  /** Accept button label (already translated); omit for the shared default. */
  readonly acceptLabel?: string;
  /** Reject button label (already translated); omit for the shared default. */
  readonly rejectLabel?: string;
  /** Runs when the user confirms. */
  readonly accept: () => void;
  /** Runs when the user rejects (reject button / Esc) — for promise-shaped callers. */
  readonly reject?: () => void;
}

/** The confirm currently awaiting a decision, plus its intent (drives styling). */
export interface ActiveConfirm extends ConfirmOptions {
  readonly intent: 'destructive' | 'neutral';
}

/**
 * Signal request-bus for confirm dialogs. `confirmDestructive` (red accept, for
 * delete / irreversible removals) and `confirmNeutral` (primary accept, for
 * resets / benign confirmations) publish an `ActiveConfirm`; the mounted
 * `<app-confirm>` renders it and calls `dismiss()` on accept/reject. DOM-free, so
 * button styling lives in ONE place (the `intent` → `.btn-danger`/`.btn-primary`
 * mapping in the view) and the service stays trivially unit-testable.
 *
 * NOT `providedIn: 'root'`: `<app-confirm>` injects the NEAREST `ConfirmService`,
 * so a component that re-provides its own (and mounts its own `<app-confirm>`)
 * renders confirms through its scoped instance; components without a re-provide
 * bind the root service and its root `<app-confirm>`.
 */
@Injectable()
export class ConfirmService {
  private readonly _active = signal<ActiveConfirm | undefined>(undefined);
  /** The pending confirm — the mounted `<app-confirm>` renders it. */
  readonly active = this._active.asReadonly();

  /** Delete / irreversible action — red accept button. */
  confirmDestructive(options: ConfirmOptions): void {
    this._active.set({ ...options, intent: 'destructive' });
  }

  /** Benign confirmation or reset — primary accept button. */
  confirmNeutral(options: ConfirmOptions): void {
    this._active.set({ ...options, intent: 'neutral' });
  }

  /** Clear the pending confirm (accept/reject handled by the view). */
  dismiss(): void {
    this._active.set(undefined);
  }
}
