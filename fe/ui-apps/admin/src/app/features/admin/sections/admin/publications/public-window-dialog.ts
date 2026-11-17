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
import { form, FormField } from '@angular/forms/signals';
import { TranslocoPipe } from '@jsverse/transloco';

import { LocaleFormatService } from '../../../../../core/main-shell/services/locale-format.service';
import { Dialog } from '../../../../../shared/components/dialog/dialog';
import { DateTimeField } from '../../../../../shared/forms/date-picker/date-time-field';
import { nowTick } from '../../../../../shared/utils/now-tick';

/** Both ends of the period, as the endpoint takes them: epoch ms, or no bound. */
export interface PublicWindowDecision {
  readonly publicFrom: number | null;
  readonly publicTo: number | null;
}

/**
 * The public period of a one-off publication, edited as one thing.
 *
 * BOTH ENDS, IN ONE DIALOG. The period is a single decision — "from when, until
 * when" — and splitting it into two actions produced the state nobody could
 * express: a publication whose end had passed could only be "re-opened" by
 * clearing the end, so the start stayed whatever the release had stamped and a
 * document that was going out again next month had no way to say so.
 *
 * A start in the FUTURE is allowed and is the point of the dialog as much as the
 * end is: a publication can be prepared, released, and appear on the public site
 * on a set day. It is called out while it is being typed, because a date that
 * has not arrived yet looks exactly like a date somebody mistyped.
 *
 * The dialog decides nothing and writes nothing: it emits the period, and the
 * caller sends it.
 */
@Component({
  selector: 'app-publication-public-window-dialog',
  imports: [DateTimeField, Dialog, FormField, TranslocoPipe],
  templateUrl: './public-window-dialog.html',
  host: { class: 'contents' },
})
export class PublicWindowDialog {
  readonly visible = model(false);

  /** The period as it stands. Epoch ms; `null`/absent for a bound that is not set. */
  readonly publicFrom = input<number | null | undefined>(undefined);
  readonly publicTo = input<number | null | undefined>(undefined);

  /**
   * Whether the start has to be filled in.
   *
   * True for a publication that is out: the server refuses a published one with
   * no start, so the form refuses it first. A publication still being prepared
   * may have no start at all — that is the ordinary shape of a draft.
   */
  readonly startRequired = input(false);

  readonly working = input(false);

  /** A refusal, already mapped to a translation key by the caller. */
  readonly errorKey = input<string | undefined>(undefined);

  readonly submitted = output<PublicWindowDecision>();

  private readonly locale = inject(LocaleFormatService);

  /** Read from a moving clock: "in the future" is answered against now, not against first render. */
  private readonly now = nowTick();

  private readonly period = signal<{ from: Date | null; to: Date | null }>({
    from: null,
    to: null,
  });
  protected readonly periodForm = form(this.period);

  constructor() {
    // Seeded when the dialog OPENS, from the period the publication actually has.
    // A period left over from the last publication would be submitted against
    // this one by whoever pressed the button without reading the fields.
    effect(() => {
      if (!this.visible()) {
        return;
      }
      // The period is read UNTRACKED: opening is what seeds the fields, and a
      // refetch landing behind the dialog must not rewrite the dates somebody is
      // in the middle of typing.
      untracked(() => {
        const from = this.publicFrom();
        const to = this.publicTo();
        this.period.set({
          from: from === null || from === undefined ? null : new Date(from),
          to: to === null || to === undefined ? null : new Date(to),
        });
        this.periodForm().reset();
      });
    });
  }

  protected readonly from = computed(() => this.period().from?.getTime() ?? null);
  protected readonly to = computed(() => this.period().to?.getTime() ?? null);

  /** The start is missing on a publication that is not allowed to be without one. */
  protected readonly startMissing = computed(() => this.startRequired() && this.from() === null);

  /**
   * A period that runs backwards.
   *
   * Refused here as well as by the server, because the two dates are on the
   * screen together: an admin who has just typed them should not have to send
   * them to find out that they are the wrong way round.
   */
  protected readonly inverted = computed(() => {
    const from = this.from();
    const to = this.to();
    return from !== null && to !== null && from > to;
  });

  /** A start that has not arrived yet: the publication goes public on the day. */
  protected readonly scheduled = computed(() => {
    const from = this.from();
    return from !== null && from > this.now();
  });

  /** The date in that hint, in the reader's own locale. */
  protected readonly scheduledFrom = computed(() => {
    const from = this.from();
    return from === null ? '' : this.locale.formatDateTime(new Date(from));
  });

  // Every read is unconditional so none is lost to a short circuit.
  protected readonly submitDisabled = computed(() => {
    const working = this.working();
    const missing = this.startMissing();
    const inverted = this.inverted();
    const invalid = this.periodForm().invalid();
    return working || missing || inverted || invalid;
  });

  protected submit(): void {
    if (this.submitDisabled()) {
      return;
    }
    this.submitted.emit({ publicFrom: this.from(), publicTo: this.to() });
  }

  protected cancel(): void {
    this.visible.set(false);
  }
}
