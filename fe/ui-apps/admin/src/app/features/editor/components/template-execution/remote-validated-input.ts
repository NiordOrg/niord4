import {
  Component,
  DestroyRef,
  effect,
  inject,
  input,
  output,
  signal,
  untracked,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { catchError, debounceTime, map, of, Subject, switchMap } from 'rxjs';

import { Icon } from '../../../../shared/icons/icon';
import { TemplatesApiService } from '../../services/api/templates-api.service';

type ValidationState = 'idle' | 'valid' | 'invalid';

/**
 * A text input that validates a `light_character` / `call_sign` template parameter server-side as
 * you type: debounced POST → `{ value, valid }`. Shows a valid /
 * invalid affordance and commits the backend-normalized value (e.g. an uppercased call sign).
 */
@Component({
  selector: 'app-remote-validated-input',
  imports: [Icon],
  template: `
    <div class="relative">
      <input
        type="text"
        class="text-input w-full pr-7"
        [value]="text()"
        [disabled]="disabled()"
        (input)="onInput($any($event.target).value)"
        [style.border-color]="state() === 'invalid' ? 'var(--color-danger)' : null"
        [attr.aria-label]="ariaLabel()"
        [attr.aria-required]="ariaRequired() || null"
        [attr.aria-invalid]="state() === 'invalid'"
      />
      @if (state() === 'valid') {
        <app-icon
          name="lucideCheck"
          class="absolute top-1/2 right-2 -translate-y-1/2 text-fg-subtle"
        />
      } @else if (state() === 'invalid') {
        <app-icon
          name="lucideTriangleAlert"
          class="absolute top-1/2 right-2 -translate-y-1/2 text-danger"
        />
      }
    </div>
  `,
  host: { class: 'block' },
})
export class RemoteValidatedInput {
  private readonly api = inject(TemplatesApiService);

  readonly value = input<unknown>(undefined);
  readonly kind = input.required<'light-character' | 'call-sign'>();
  readonly ariaLabel = input('');
  /** Marks the input as one that must be filled in (a mandatory template parameter). */
  readonly ariaRequired = input(false);
  readonly disabled = input(false);
  readonly valueChange = output<string | undefined>();

  protected readonly text = signal('');
  protected readonly state = signal<ValidationState>('idle');

  private readonly input$ = new Subject<string>();

  constructor() {
    // Mirror the bound value into the field. Beyond the initial seed this matters when the host
    // `@for (… ; track $index)` reuses this instance for a different row (a marking deleted / the
    // list reordered): without re-syncing, the input would keep showing the previous row's value.
    // `text` is read untracked so the sync fires only on an external `value()` change, never on the
    // user's own keystrokes — and the equality guard leaves an in-flight edit (whose echo round-trips
    // back as the same value) untouched while resetting the valid/invalid glyph on a genuine rebind.
    effect(() => {
      const value = this.value();
      const next = typeof value === 'string' ? value : '';
      if (next !== untracked(this.text)) {
        this.text.set(next);
        this.state.set('idle');
      }
    });

    this.input$
      .pipe(
        debounceTime(300),
        switchMap((raw) =>
          raw.trim() === ''
            ? of({ value: '', valid: true, empty: true })
            : this.api.validate(this.kind(), raw).pipe(
                map((response) => ({ ...response, empty: false })),
                catchError(() => of({ value: raw, valid: false, empty: false })),
              ),
        ),
        takeUntilDestroyed(inject(DestroyRef)),
      )
      .subscribe((result) => {
        if (result.empty) {
          this.state.set('idle');
          this.valueChange.emit(undefined);
          return;
        }
        this.state.set(result.valid ? 'valid' : 'invalid');
        // Apply the backend-normalized value (e.g. an uppercased call sign).
        if (result.value !== this.text()) this.text.set(result.value);
        this.valueChange.emit(result.value || undefined);
      });
  }

  protected onInput(raw: string): void {
    this.text.set(raw);
    this.input$.next(raw);
  }
}
