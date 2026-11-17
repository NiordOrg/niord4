import {
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { form, FormField, maxLength, minLength, required } from '@angular/forms/signals';
import { TranslocoPipe, TranslocoService } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { Icon } from '../../../../../shared/icons/icon';
import type {
  AmendRequest,
  PublishCheck,
} from '../../../services/api/publication-series-api.service';
import { warningKey } from './publication-errors';
import { checkDetailText } from './publication-status';

/** The bounds the server enforces on an amend reason, restated so the form refuses first. */
const REASON_MIN = 3;
const REASON_MAX = 512;

/**
 * Take the same decision again, at the same address.
 *
 * An amend is the one action on this screen that changes something people have
 * already read, and everything about the dialog follows from that.
 *
 * **There is no cut-off question.** The instant the content was decided at is the
 * one thing an amend must not move: it is what the issue before this one was
 * bounded by, and re-taking it would silently re-decide that issue's membership
 * too. So the members are resolved again against the issue's OWN cut-off, and the
 * public id, the link and the window come out unchanged.
 *
 * **The reason is mandatory.** A replaced document with no recorded cause leaves
 * the next reader of the history with the fact and none of the why.
 *
 * **The warnings are the publish dialog's, again.** The server re-resolves at
 * amend time exactly as it does at publish time, so a warning can appear between
 * the checklist and the attempt — which is why submitting is not gated on ticking
 * them, and why a refusal re-lists what is still owed without losing the reason
 * that was typed.
 */
@Component({
  selector: 'app-publication-amend-dialog',
  imports: [Dialog, FormField, Icon, TranslocoPipe],
  templateUrl: './amend-dialog.html',
  host: { class: 'contents' },
})
export class PublicationAmendDialog {
  private readonly localeFormat = inject(LocaleFormatService);
  private readonly transloco = inject(TranslocoService);

  readonly visible = model(false);

  /** The checklist rows an admin may take responsibility for. */
  readonly warnings = input<readonly PublishCheck[]>([]);

  /** What the server refused the last attempt over. Empty until it does. */
  readonly unacknowledged = input<readonly string[]>([]);

  readonly working = input(false);

  /** A refused attempt, already mapped to a translation key by the caller. */
  readonly errorKey = input<string | undefined>(undefined);

  readonly submitted = output<AmendRequest>();

  private readonly acknowledged = signal<ReadonlySet<string>>(new Set());

  private readonly reasonModel = signal<{ reason: string }>({ reason: '' });
  protected readonly amendForm = form(this.reasonModel, (path) => {
    required(path.reason);
    minLength(path.reason, REASON_MIN);
    maxLength(path.reason, REASON_MAX);
  });

  constructor() {
    // Seeded when the dialog OPENS, not per change: re-applying the defaults
    // while somebody is typing would undo the answer they came here to give.
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          this.reasonModel.set({ reason: '' });
          this.amendForm().reset();
          this.acknowledged.set(new Set());
        });
      }
    });
  }

  // Measured on the TRIMMED text, which is what is submitted and what the server
  // counts. Every read is unconditional: a short-circuited `||` would leave the
  // later signals untracked on the pass that took the first branch.
  protected readonly submitDisabled = computed(() => {
    const invalid = this.amendForm().invalid();
    const length = this.reasonModel().reason.trim().length;
    const working = this.working();
    return working || invalid || length < REASON_MIN || length > REASON_MAX;
  });

  /**
   * The warnings, in the vocabulary the amend gate compares.
   *
   * Read off the row's own `acknowledgeCode`, as the publish dialog does and for
   * the same reason: an amend re-resolves and re-renders behind the same gate,
   * so a rail row ticked here has to travel as the resolver's own code or the
   * refusal comes straight back naming the row that was just accepted. A row
   * whose two names coincide carries no `acknowledgeCode` and answers with its
   * own code.
   */
  protected readonly warningRows = computed(() =>
    this.warnings().map((row) => {
      const code = row.acknowledgeCode ?? row.code;
      return {
        code,
        labelKey: warningKey(code),
        // The answer, said in the reader's language: the server sends the code
        // and its values apart from its own English sentence, an instant arrives
        // as epoch milliseconds so it shows on this screen's clock, and a stored
        // code among the values is named the way the rest of the screen names it.
        detail: checkDetailText(
          row,
          (value) => this.localeFormat.formatDateTime(value),
          (key) => this.transloco.translate(key),
        ),
      };
    }),
  );

  protected readonly unacknowledgedRows = computed(() =>
    this.unacknowledged().map((code) => ({ code, labelKey: warningKey(code) })),
  );

  protected isAcknowledged(code: string): boolean {
    return this.acknowledged().has(code);
  }

  protected toggleAcknowledged(code: string): void {
    this.acknowledged.update((current) => {
      const next = new Set(current);
      if (!next.delete(code)) {
        next.add(code);
      }
      return next;
    });
  }

  protected submit(): void {
    if (this.submitDisabled()) {
      return;
    }
    this.submitted.emit({
      reason: this.reasonModel().reason.trim(),
      acknowledgedWarnings: [...this.acknowledged()],
    });
  }

  protected cancel(): void {
    this.visible.set(false);
  }
}
