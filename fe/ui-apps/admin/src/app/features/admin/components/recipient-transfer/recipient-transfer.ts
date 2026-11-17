import { Component, computed, input, model, signal } from '@angular/core';
import { TranslocoPipe } from '@jsverse/transloco';

import { Icon } from '../../../../shared/icons/icon';

/** A normalized transfer row — the caller maps its raw entity (User / Contact) to this shape. */
export interface TransferItem {
  /** Stable identity (email is unique for both users and contacts). */
  readonly key: string;
  readonly email: string;
  /** Display name — the caller resolves it per entity shape (user first+last vs contact name). */
  readonly name: string;
}

export interface RecipientSelection {
  readonly selected: readonly TransferItem[];
  readonly available: readonly TransferItem[];
}

const byEmail = (a: TransferItem, b: TransferItem): number =>
  a.email.toLowerCase().localeCompare(b.email.toLowerCase());

const matches = (item: TransferItem, term: string): boolean =>
  item.email.toLowerCase().includes(term) || item.name.toLowerCase().includes(term);

/**
 * Dual-list transfer: a Selected pane and an Available pane, each with a per-side text filter and a
 * bulk button. Rows move one at a time (add / remove) or in bulk. The bulk buttons are
 * **filter-aware** — Add all / Remove all deliberately act on the currently-filtered subset and show
 * its count, never the whole list. Both panes stay email-sorted. A pure value component
 * (`[value]`/`(valueChange)`), never a form.
 */
@Component({
  selector: 'app-recipient-transfer',
  imports: [Icon, TranslocoPipe],
  templateUrl: './recipient-transfer.html',
  host: { class: 'contents' },
})
export class RecipientTransfer {
  readonly value = model.required<RecipientSelection>();
  readonly disabled = input(false);

  protected readonly selectedFilter = signal('');
  protected readonly availableFilter = signal('');

  protected readonly filteredSelected = computed(() =>
    this.filter(this.value().selected, this.selectedFilter()),
  );
  protected readonly filteredAvailable = computed(() =>
    this.filter(this.value().available, this.availableFilter()),
  );

  private filter(items: readonly TransferItem[], term: string): TransferItem[] {
    const needle = term.trim().toLowerCase();
    const matched = needle ? items.filter((item) => matches(item, needle)) : [...items];
    return matched.sort(byEmail);
  }

  protected add(item: TransferItem): void {
    if (this.disabled()) return;
    const current = this.value();
    this.value.set({
      selected: [...current.selected, item].sort(byEmail),
      available: current.available.filter((row) => row.key !== item.key),
    });
  }

  protected remove(item: TransferItem): void {
    if (this.disabled()) return;
    const current = this.value();
    this.value.set({
      selected: current.selected.filter((row) => row.key !== item.key),
      available: [...current.available, item].sort(byEmail),
    });
  }

  /** Add every currently-FILTERED available row (not the whole set). */
  protected addAllFiltered(): void {
    if (this.disabled()) return;
    const moving = new Set(this.filteredAvailable().map((item) => item.key));
    if (moving.size === 0) return;
    const current = this.value();
    this.value.set({
      selected: [
        ...current.selected,
        ...current.available.filter((row) => moving.has(row.key)),
      ].sort(byEmail),
      available: current.available.filter((row) => !moving.has(row.key)),
    });
  }

  /** Remove every currently-FILTERED selected row (not the whole set). */
  protected removeAllFiltered(): void {
    if (this.disabled()) return;
    const moving = new Set(this.filteredSelected().map((item) => item.key));
    if (moving.size === 0) return;
    const current = this.value();
    this.value.set({
      selected: current.selected.filter((row) => !moving.has(row.key)),
      available: [
        ...current.available,
        ...current.selected.filter((row) => moving.has(row.key)),
      ].sort(byEmail),
    });
  }
}
