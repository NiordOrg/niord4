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
import { TranslocoPipe } from '@jsverse/transloco';

import type { Domain } from '../../../../../core/domain/models/domain.model';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { PublicationTransferTargets } from './transfer-owner';

/** The bounds the server enforces, restated so the form refuses before the request does. */
const REASON_MIN = 3;
const REASON_MAX = 512;

/** What the caller sends when the dialog is submitted. */
export interface TransferOwnerDecision {
  readonly domainId: string;
  readonly reason: string;
}

/**
 * Move a publication to another desk.
 *
 * The owner decides three things at once — where the publication is listed, who
 * may write it, and which timezone its cut-offs are read in — so this is not a
 * field on a form: it is an action with a reason, in the same shape as retiring
 * a series or withdrawing an issue.
 *
 * THE PICKER IS THE GATE. It offers the domains the caller administers, minus
 * the one that owns the publication already, and the caller is expected not to
 * open the dialog at all when that list is empty. The server checks both sides
 * again and names which one is missing, which is why a refusal here shows the
 * server's own sentence beneath the translated one.
 */
@Component({
  selector: 'app-publication-transfer-dialog',
  imports: [Dialog, FormField, TranslocoPipe],
  templateUrl: './transfer-owner-dialog.html',
  host: { class: 'contents' },
})
export class PublicationTransferDialog {
  private readonly targets = inject(PublicationTransferTargets);

  readonly visible = model(false);

  /** The domain that owns the publication now. Excluded from the picker. */
  readonly ownerId = input<string | undefined>(undefined);

  readonly working = input(false);

  /** A refusal, already mapped to a translation key by the caller. */
  readonly errorKey = input<string | undefined>(undefined);

  /**
   * The server's own sentence beneath the mapped one.
   *
   * Shown verbatim for the one refusal that carries what no key can: which of
   * the two domains the caller is not an admin in.
   */
  readonly errorDetail = input<string | undefined>(undefined);

  readonly submitted = output<TransferOwnerDecision>();

  /** The desks on the table, resolved from the catalogue and the caller's grants. */
  protected readonly domains = computed<readonly Domain[]>(() =>
    this.targets.targetsFor(this.ownerId()),
  );

  protected readonly value = signal<{ domainId: string; reason: string }>({
    domainId: '',
    reason: '',
  });

  protected readonly transferForm = form(this.value, (path) => {
    required(path.domainId);
    required(path.reason);
    minLength(path.reason, REASON_MIN);
    maxLength(path.reason, REASON_MAX);
  });

  constructor() {
    // Cleared when the dialog OPENS. A target and a reason left over from the
    // last transfer would otherwise be submitted against a different publication
    // by whoever pressed the button without reading the box.
    effect(() => {
      if (this.visible()) {
        untracked(() => {
          this.value.set({ domainId: '', reason: '' });
          this.transferForm().reset();
        });
      }
    });
  }

  protected setDomain(event: Event): void {
    const domainId = (event.target as HTMLSelectElement).value;
    this.value.update((current) => ({ ...current, domainId }));
  }

  // The bounds are checked on the TRIMMED reason, which is what is submitted and
  // what the server measures. Every read happens unconditionally: a
  // short-circuited `||` would leave the later signals untracked on the pass
  // that took the first branch.
  protected readonly submitDisabled = computed(() => {
    const invalid = this.transferForm().invalid();
    const value = this.value();
    const length = value.reason.trim().length;
    const working = this.working();
    return (
      working || invalid || value.domainId === '' || length < REASON_MIN || length > REASON_MAX
    );
  });

  protected submit(): void {
    if (this.submitDisabled()) {
      return;
    }
    const value = this.value();
    this.submitted.emit({ domainId: value.domainId, reason: value.reason.trim() });
  }

  protected cancel(): void {
    this.visible.set(false);
  }
}
