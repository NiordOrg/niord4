import { Component, computed, input } from '@angular/core';

/**
 * A language's flag, or its code where the build ships no flag for the language: a language
 * the deployment uses as content need not be one the build names, and an image the build does
 * not ship would render broken. The call site sizes, rounds and places the element with its own
 * classes, which hold for the code as for the image.
 *
 * Where the code is already written beside the flag, `codeFallback` is false and a flagless
 * language renders nothing.
 */
@Component({
  selector: 'app-language-flag',
  host: {
    class: 'inline-flex shrink-0 items-center justify-center overflow-hidden',
    '[style.display]': "empty() ? 'none' : null",
  },
  template: `
    @if (flagPath(); as src) {
      <img class="h-full w-full object-cover" [src]="src" [alt]="alt()" />
    } @else if (codeFallback()) {
      @if (alt()) {
        <span class="sr-only">{{ alt() }}</span>
      }
      <span class="text-[0.625rem] leading-none font-bold text-fg-muted" aria-hidden="true">{{
        code()
      }}</span>
    }
  `,
})
export class LanguageFlag {
  /** The language code, e.g. `da`. */
  readonly lang = input.required<string>();
  /** The flag the build ships for the language; absent when it ships none. */
  readonly flagPath = input<string | undefined>(undefined);
  /** The accessible name; empty where the language is named beside the flag. */
  readonly alt = input('');
  /** Whether a flagless language shows its code (false where the code is already beside it). */
  readonly codeFallback = input(true);

  protected readonly code = computed(() => this.lang().toUpperCase());
  protected readonly empty = computed(() => !this.flagPath() && !this.codeFallback());
}
