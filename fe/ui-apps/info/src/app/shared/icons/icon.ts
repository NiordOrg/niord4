import { Component, input } from '@angular/core';
import { NgIcon } from '@ng-icons/core';

/**
 * Inline SVG icon (Lucide via `@ng-icons`). `name` is the `@ng-icons/lucide` export
 * identifier — e.g. `lucidePrinter`. Decorative by default (`aria-hidden`): an icon-only
 * control keeps its `aria-label` on the interactive parent. Size defaults to `1em` so the
 * glyph scales with the surrounding font-size.
 */
@Component({
  selector: 'app-icon',
  imports: [NgIcon],
  template: `<ng-icon [name]="name()" [size]="size()" aria-hidden="true" />`,
  host: { class: 'inline-flex items-center' },
})
export class Icon {
  /** `@ng-icons/lucide` export identifier, e.g. `lucidePrinter`. */
  readonly name = input.required<string>();
  /** CSS size of the glyph; defaults to `1em` (scales with font-size). */
  readonly size = input('1em');
}
