import { Component, computed, input } from '@angular/core';

import { Icon } from '../../icons/icon';

/**
 * "Nothing here" placeholder — a centred title with an optional supporting line
 * and an optional glyph.
 *
 * `compact` is the variant for a small surface inside a page (a dashboard card,
 * a panel section) where the full-height placeholder would out-weigh the content
 * around it: it drops the glyph, halves the vertical room and steps the whole
 * block down one text tier, so the absence reads as a note rather than as a
 * screen of its own.
 */
@Component({
  selector: 'app-empty-state',
  imports: [Icon],
  templateUrl: './empty-state.html',
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly body = input<string | undefined>(undefined);
  /** The Lucide icon id to show (e.g. `lucideSearch`); omit for no icon. */
  readonly icon = input<string | undefined>(undefined);
  /** Dense variant for in-page surfaces: no glyph, tighter box, smaller type. */
  readonly compact = input(false);

  // A compact placeholder never draws a glyph — the icon is the part that turns
  // the block into a full-height illustration, which is what compact avoids.
  protected readonly glyph = computed(() => (this.compact() ? undefined : this.icon()));

  // Whole class strings, not fragments merged into a static `class`: Angular
  // replaces the static attribute the moment a `[class]` binding evaluates.
  protected readonly rootClass = computed(() =>
    this.compact()
      ? 'flex flex-col items-center gap-1 py-6 text-center'
      : 'flex flex-col items-center gap-2 py-12 text-center',
  );
  protected readonly titleClass = computed(() =>
    this.compact() ? 'text-sm text-fg-muted' : 'text-base text-fg',
  );
  protected readonly bodyClass = computed(() =>
    this.compact() ? 'text-xs text-fg-subtle' : 'text-sm text-fg-muted',
  );
}
