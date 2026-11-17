import {
  Component,
  computed,
  effect,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { form, FormField, maxLength, minLength, required } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { Dialog } from '../../../../../shared/components/dialog/dialog';

/** The bounds the server enforces, restated so the form refuses before the request does. */
const REASON_MIN = 3;
const REASON_MAX = 512;

/**
 * "Why are you doing this?", asked before a change somebody will have to review.
 *
 * One component for every such question — withdrawing an issue, retiring a
 * series, excluding a message, withdrawing a curation decision — because the
 * question is the same and so are the bounds (3–512, server-enforced). What
 * differs is the CONSEQUENCE, which is why the explanatory copy is an input
 * rather than a constant: withdrawing an issue takes it off the public list but
 * leaves its file resolvable, while retiring a series stops new issues and
 * touches nothing already released. Both are surprising, in opposite directions,
 * and a dialog that says neither leaves the admin to find out afterwards.
 *
 * The reason is not decoration: it is the only part of a decision that survives
 * into the history for whoever reads it next.
 */
@Component({
  selector: 'app-publication-reason-dialog',
  imports: [Dialog, FormField, TranslocoPipe],
  templateUrl: './reason-dialog.html',
  host: { class: 'contents' },
})
export class PublicationReasonDialog {
  readonly visible = model(false);

  /** Translation key for the dialog title. */
  readonly headerKey = input.required<string>();

  /** Translation key for the copy explaining what the action actually does. */
  readonly effectKey = input.required<string>();

  /** Translation key for the accept button. */
  readonly confirmKey = input.required<string>();

  readonly working = input(false);

  /** A refusal, already mapped to a translation key by the caller. */
  readonly errorKey = input<string | undefined>(undefined);

  /**
   * The server's own sentence beneath the mapped message.
   *
   * Shown verbatim because some refusals carry the part no key can: which uids
   * could not be resolved, and there may be several of them.
   */
  readonly errorDetail = input<string | undefined>(undefined);

  readonly submitted = output<string>();

  private readonly reasonModel = signal<{ reason: string }>({ reason: '' });
  protected readonly reasonForm = form(this.reasonModel, (path) => {
    required(path.reason);
    minLength(path.reason, REASON_MIN);
    maxLength(path.reason, REASON_MAX);
  });

  constructor() {
    // Cleared when the dialog OPENS. A reason left over from the last withdrawal
    // would be submitted against a different subject by whoever pressed the
    // button without reading the box.
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          this.reasonModel.set({ reason: '' });
          this.reasonForm().reset();
        });
      }
    });
  }

  // The bounds are checked on the TRIMMED text, which is what is submitted and
  // what the server measures: three spaces and a letter is one character of
  // reason, not four. The form's own validators keep the inline messages; this
  // is what decides whether the button is live. All reads happen
  // unconditionally: a short-circuited `||` would leave the later signals
  // untracked on the pass that took the first branch.
  protected readonly submitDisabled = computed(() => {
    const invalid = this.reasonForm().invalid();
    const length = this.reasonModel().reason.trim().length;
    const working = this.working();
    return working || invalid || length < REASON_MIN || length > REASON_MAX;
  });

  protected submit(): void {
    if (this.submitDisabled()) {
      return;
    }
    this.submitted.emit(this.reasonModel().reason.trim());
  }

  protected cancel(): void {
    this.visible.set(false);
  }
}
