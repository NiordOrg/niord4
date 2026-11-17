import { Directive, computed, inject } from '@angular/core';

import type { FilterName, SliceValueOf } from '../../models/messages-filter-params.model';
import { MessagesFacade } from '../../services/messages-facade.service';

@Directive()
export abstract class EntityFilterPanelBase<K extends FilterName, TItem, TId> {
  protected readonly messages = inject(MessagesFacade);

  protected abstract readonly sliceName: K;
  protected abstract extractValue(slice: SliceValueOf<K>): readonly TItem[];
  protected abstract idOf(item: TItem): TId;
  protected abstract toSliceValue(items: readonly TItem[]): SliceValueOf<K>;

  readonly value = computed(() => this.extractValue(this.messages.filter.slice(this.sliceName)()));
  readonly initialIds = computed(() => this.value().map((item) => this.idOf(item)));

  onChange(items: readonly TItem[]): void {
    // The field's `value` is a Signal Forms model, so a one-way `[value]` re-push emits
    // `valueChange` too; skip when the content is unchanged so re-applying the same slice
    // (and the resulting echo) is a no-op.
    const current = this.value();
    if (
      current.length === items.length &&
      current.every((item, i) => this.idOf(item) === this.idOf(items[i]))
    ) {
      return;
    }
    this.messages.filter.setSliceValue(this.sliceName, this.toSliceValue(items));
  }

  /**
   * Unconditional slice write for the background id→entity hydration write-back.
   * Unlike {@link onChange} it skips the id-only echo guard: restoring an id-only
   * stub keeps the same ids and only *adds* resolved content (names/descs), so the
   * guard would read the hydrated result as an unchanged echo and drop it, leaving
   * the chips stuck on `#id`. The tree/tag panels never bind a `[value]` model, so
   * there is no echo to suppress here anyway.
   */
  protected writeValue(items: readonly TItem[]): void {
    this.messages.filter.setSliceValue(this.sliceName, this.toSliceValue(items));
  }

  clear(): void {
    this.messages.filter.clearFilter(this.sliceName);
  }
}
