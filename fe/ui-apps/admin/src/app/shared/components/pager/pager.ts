import { Component, computed, input, output } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../icons/icon';

/**
 * Presentational prev/next pager. Pager STATE stays with the caller (e.g. the
 * niord `createClientPager`): bind `[first]`/`[rows]`/`[total]` and apply
 * `(pageChange)` (the new first-row index). Self-hides when there is only one
 * page. The page count is rendered language-neutral (`n / m`); only the controls
 * carry aria labels.
 */
@Component({
  selector: 'app-pager',
  imports: [Icon, TranslocoPipe],
  template: `
    @if (pageCount() > 1) {
      <nav
        class="pager"
        [class.mt-0]="inCard()"
        [class.w-full]="inCard()"
        [attr.aria-label]="'shared.pager.label' | transloco"
      >
        @if (pageCount() > 2) {
          <button
            type="button"
            class="pager-btn"
            [disabled]="!canPrev()"
            (click)="firstPage()"
            [attr.aria-label]="'shared.pager.first' | transloco"
          >
            <app-icon name="lucideChevronsLeft" />
          </button>
        }
        <button
          type="button"
          class="pager-btn"
          [disabled]="!canPrev()"
          (click)="prev()"
          [attr.aria-label]="'shared.pager.previous' | transloco"
        >
          <app-icon name="lucideChevronLeft" />
        </button>
        <span class="pager-status">{{ page() }} / {{ pageCount() }}</span>
        <button
          type="button"
          class="pager-btn"
          [disabled]="!canNext()"
          (click)="next()"
          [attr.aria-label]="'shared.pager.next' | transloco"
        >
          <app-icon name="lucideChevronRight" />
        </button>
        @if (pageCount() > 2) {
          <button
            type="button"
            class="pager-btn"
            [disabled]="!canNext()"
            (click)="lastPage()"
            [attr.aria-label]="'shared.pager.last' | transloco"
          >
            <app-icon name="lucideChevronsRight" />
          </button>
        }
      </nav>
    }
  `,
  host: { class: 'contents' },
})
export class Pager {
  /** Index of the first row on the current page. */
  readonly first = input(0);
  /** Page size. */
  readonly rows = input.required<number>();
  /** Total row count. */
  readonly total = input(0);
  /**
   * Rendered inside a card's own foot rather than free under a list. The card
   * supplies the separation, so the standalone top margin comes off, and the row
   * stretches so its controls stay centred on the card whatever container it
   * lands in.
   */
  readonly inCard = input(false);
  /** Emits the new first-row index. */
  readonly pageChange = output<number>();

  protected readonly page = computed(() => Math.floor(this.first() / Math.max(1, this.rows())) + 1);
  protected readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.total() / Math.max(1, this.rows()))),
  );
  protected readonly canPrev = computed(() => this.first() > 0);
  protected readonly canNext = computed(() => this.first() + this.rows() < this.total());

  protected prev(): void {
    if (this.canPrev()) this.pageChange.emit(Math.max(0, this.first() - this.rows()));
  }

  protected next(): void {
    if (this.canNext()) this.pageChange.emit(this.first() + this.rows());
  }

  protected firstPage(): void {
    if (this.canPrev()) this.pageChange.emit(0);
  }

  protected lastPage(): void {
    if (this.canNext()) this.pageChange.emit((this.pageCount() - 1) * this.rows());
  }
}
