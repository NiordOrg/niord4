import { Component, effect, input, model, signal, untracked } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../../shared/icons/icon';

/** How long the box waits after the last keystroke before the list moves. */
export const FILTER_DEBOUNCE_MS = 250;

/**
 * The filter box over a publication list.
 *
 * Two values, not one, and the split is what makes it usable. `draft` is what
 * the reader is typing and updates on every keystroke; `value` is what the list
 * is filtered by and follows a quarter of a second later. Filtering on the
 * keystroke re-renders a five-hundred-row archive per character and re-writes
 * the address bar with it, which is what makes a list feel like it is fighting
 * the person typing into it.
 *
 * `value` is a model, so a host can seed it from the address bar and be told
 * when it settles. An external write is mirrored back into the draft — the box
 * has to show what a deep link filtered by.
 */
@Component({
  selector: 'app-publication-list-filter',
  imports: [Icon, TranslocoPipe],
  templateUrl: './publication-list-filter.html',
  host: { class: 'block' },
})
export class PublicationListFilter {
  /** Translation key for the placeholder, which doubles as the accessible name. */
  readonly placeholderKey = input.required<string>();

  /** The settled filter. Two-way bound: seeded by the host, reported back debounced. */
  readonly value = model<string>('');

  /** What is in the box right now, ahead of the debounce. */
  protected readonly draft = signal('');

  constructor() {
    // A value the host set — a deep link, or a reset — is not something this
    // box typed, so it is shown rather than debounced back at the host.
    effect(() => {
      const settled = this.value();
      if (settled !== untracked(this.draft)) {
        this.draft.set(settled);
      }
    });

    effect((onCleanup) => {
      const draft = this.draft();
      // Nothing to report while the draft already IS the settled value: without
      // this the mirror above and the timer below would hand the same string
      // back and forth.
      if (draft === untracked(this.value)) {
        return;
      }
      const handle = setTimeout(() => this.value.set(draft), FILTER_DEBOUNCE_MS);
      onCleanup(() => clearTimeout(handle));
    });
  }

  protected onInput(value: string): void {
    this.draft.set(value);
  }

  /**
   * Empty the box, now rather than in a quarter of a second.
   *
   * A clear is a decision, not a keystroke: waiting for the debounce would leave
   * the reader looking at a filtered list beside an empty box.
   */
  protected clear(): void {
    this.draft.set('');
    this.value.set('');
  }
}
