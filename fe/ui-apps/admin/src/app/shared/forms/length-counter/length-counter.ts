import { Component, computed, input } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

/** The error kind a form's length rule reports, read to tell it from the field's other rules. */
export const TOO_LONG = 'tooLong';

/**
 * How many characters the database counts in a text.
 *
 * PostgreSQL measures a `character varying(n)` in characters (code points), and a
 * JavaScript string's `length` counts UTF-16 units, so a character outside the
 * basic plane (an emoji, some CJK) would count twice there. Counting code points
 * gives the number the column is measured against.
 */
export function textLength(text: string | null | undefined): number {
  return Array.from(text ?? '').length;
}

/** Whether a text is longer than the column it is saved into. */
export function tooLong(text: string | null | undefined, limit: number): boolean {
  return textLength(text) > limit;
}

/**
 * A form rule's answer for one text: the length error, or none.
 *
 * Meant for a Signal Forms `validate()` rule, never for the schema's own
 * `maxLength()`: `[formField]` reflects that one onto a native input as a
 * `maxlength` attribute, and the browser then cuts a longer paste to the limit
 * without a word. With this rule the whole text stays in the field, the counter
 * under it says how much is too much, and Save waits until it fits.
 */
export function lengthError(
  text: string | null | undefined,
  limit: number,
): { kind: typeof TOO_LONG } | null {
  return tooLong(text, limit) ? { kind: TOO_LONG } : null;
}

/** The same answer for per-language texts kept as one map: any language too long. */
export function lengthErrorAny(
  texts: Readonly<Record<string, string | null | undefined>>,
  limit: number,
): { kind: typeof TOO_LONG } | null {
  return Object.values(texts).some((text) => tooLong(text, limit)) ? { kind: TOO_LONG } : null;
}

/**
 * "n of max" under a text input whose column has a limit (ERR7).
 *
 * The limit is kept by the form, not by the input: the editor holds Save back
 * with a {@link lengthError} rule while a text is too long, and this line says
 * by how much. Nothing is cut, so a pasted text that does not fit is still all
 * there to be shortened where the writer chooses.
 *
 * Shown at all times, so the limit is known before it is met. Over the limit
 * the line turns red and becomes an alert, so a screen reader hears it once
 * when it appears. A native input points `aria-describedby` at `counterId`;
 * `language` (a human label) names the row when the counters sit under a group
 * of per-language inputs rather than under each one.
 */
@Component({
  selector: 'app-length-counter',
  imports: [TranslocoPipe],
  template: `
    @if (excess() > 0) {
      <p [id]="counterId()" class="text-right text-xs text-danger tabular-nums" role="alert">
        {{ textKey() | transloco: params() }}
      </p>
    } @else {
      <p [id]="counterId()" class="text-right text-xs text-fg-muted tabular-nums">
        {{ textKey() | transloco: params() }}
      </p>
    }
  `,
  host: { class: 'contents' },
})
export class LengthCounter {
  /** The text as typed. */
  readonly text = input<string | null | undefined>('');
  /** The column's limit, in characters. */
  readonly limit = input.required<number>();
  /** The line's id, for the input's `aria-describedby`. */
  readonly counterId = input<string | undefined>(undefined);
  /** The language the line counts for, as a human label; empty for a line under its own input. */
  readonly language = input<string | undefined>(undefined);

  protected readonly count = computed(() => textLength(this.text()));
  protected readonly excess = computed(() => Math.max(0, this.count() - this.limit()));

  protected readonly textKey = computed(() => {
    const over = this.excess() > 0;
    if (this.language()) {
      return over ? 'shared.forms.lengthCounter.tooLongIn' : 'shared.forms.lengthCounter.countIn';
    }
    return over ? 'shared.forms.lengthCounter.tooLong' : 'shared.forms.lengthCounter.count';
  });

  protected readonly params = computed(() => ({
    count: this.count(),
    max: this.limit(),
    excess: this.excess(),
    language: this.language() ?? '',
  }));
}
