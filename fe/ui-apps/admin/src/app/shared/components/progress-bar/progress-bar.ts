import { Component, input } from '@angular/core';

@Component({
  selector: 'app-progress-bar',
  templateUrl: './progress-bar.html',
  host: {
    // Default: transparent wrapper — the @if collapses to nothing when not visible, so the host
    // would otherwise sit as a 0×0 element in the parent layout and trigger phantom `gap`.
    // `contents` removes it from layout. See ui-standards.md §6.
    // `overlay` mode: pin the bar to the top of the nearest positioned ancestor (which must be
    // `relative`) so it appears WITHOUT reflowing the content below — no push-down flicker on
    // every filter refetch. Out of flow, so still no phantom gap.
    '[class]': "overlay() ? 'pointer-events-none absolute inset-x-0 top-0 z-10' : 'contents'",
  },
})
export class ProgressBar {
  readonly visible = input.required<boolean>();
  readonly mode = input<'indeterminate' | 'determinate'>('indeterminate');
  readonly value = input<number | undefined>(undefined);
  /** Render as a top-pinned overlay (no reflow) instead of an in-flow strip. Parent must be `relative`. */
  readonly overlay = input<boolean>(false);
}
