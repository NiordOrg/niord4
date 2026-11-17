import { computed, Injectable, signal } from '@angular/core';

/**
 * Tracks which open drawers currently hold unsaved changes, so route-level
 * guards can block in-app navigation away from a page hosting a dirty drawer
 * (the drawer itself covers Esc/✕/mask and refresh — a route change is the
 * one close path it cannot see).
 *
 * Drawers register via `setDirty`, keyed by component instance; guards only
 * read the aggregate `anyDirty`.
 */
@Injectable({ providedIn: 'root' })
export class DrawerGateService {
  private readonly dirtyDrawers = signal<ReadonlySet<object>>(new Set());

  readonly anyDirty = computed(() => this.dirtyDrawers().size > 0);

  setDirty(drawer: object, dirty: boolean): void {
    this.dirtyDrawers.update((current) => {
      if (current.has(drawer) === dirty) return current;
      const next = new Set(current);
      if (dirty) {
        next.add(drawer);
      } else {
        next.delete(drawer);
      }
      return next;
    });
  }

  remove(drawer: object): void {
    this.setDirty(drawer, false);
  }
}
