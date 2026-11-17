import { Component, computed, input } from '@angular/core';

/**
 * Shared surface card: a rounded, bordered panel with a header band (label +
 * optional right-aligned actions) over a projected body. The common frame behind
 * the user-editor group panels, the geometry-editor overlay, and the
 * message-editor sections.
 *
 * The band is sentence-case and the same weight and size as every other panel
 * band in the app, so a card standing beside an `.admin-panel` reads as the same
 * kind of surface rather than as a second style of card.
 *
 * ```html
 * <app-card [header]="'…' | transloco">
 *   <button cardActions class="btn-secondary">Join</button>
 *   … body …
 * </app-card>
 * ```
 *
 * `header` is rendered verbatim (the consumer translates); the band renders only
 * when it is set (actions live in it). `[padded]="false"` for bodies whose rows
 * run edge-to-edge. `[fill]="true"` turns the card into a flex column whose body
 * grows + scrolls to fill a height-constrained parent (a map overlay, a drawer);
 * `sectionClass` adds presentation classes to the outer `<section>` (shadow,
 * pointer-events, a grow hint), so a floating panel reuses the same frame.
 */
@Component({
  selector: 'app-card',
  template: `
    <section [class]="sectionClasses()">
      @if (header()) {
        <header
          class="flex min-h-10 items-center justify-between gap-2 border-b border-surface-200 bg-header-band px-4 py-2 dark:border-surface-700 dark:bg-surface-800"
        >
          <span class="text-sm font-semibold text-fg">{{ header() }}</span>
          <span class="flex items-center gap-2 empty:hidden">
            <ng-content select="[cardActions]" />
          </span>
        </header>
      }
      <div [class]="bodyClasses()">
        <ng-content />
      </div>
    </section>
  `,
  // `contents` (not a box): the `<section>` becomes the effective grid/flex item,
  // so a `fill` card's section is the direct flex child of a height-constrained
  // parent (e.g. the geometry-editor's map-overlay column) and can shrink/scroll.
  host: { class: 'contents' },
})
export class Card {
  /** Header label (rendered verbatim; translate at the call site). */
  readonly header = input<string>('');
  /** Body padding — set false for edge-to-edge rows (form sections, list items). */
  readonly padded = input(true);
  /** Flex-column card whose body grows + scrolls to fill a height-constrained parent. */
  readonly fill = input(false);
  /** Extra classes merged onto the outer `<section>` (shadow, pointer-events, `flex-1`, …). */
  readonly sectionClass = input<string>('');

  protected readonly sectionClasses = computed(() => {
    const base =
      'overflow-hidden rounded-lg border border-surface-200 bg-surface-0 dark:border-surface-700 dark:bg-surface-900';
    const fill = this.fill() ? ' flex min-h-0 flex-col' : '';
    const extra = this.sectionClass() ? ' ' + this.sectionClass() : '';
    return base + fill + extra;
  });

  protected readonly bodyClasses = computed(() => {
    const pad = this.padded() ? 'p-4' : '';
    const fill = this.fill() ? ' min-h-0 flex-1 overflow-auto' : '';
    return (pad + fill).trim();
  });
}
