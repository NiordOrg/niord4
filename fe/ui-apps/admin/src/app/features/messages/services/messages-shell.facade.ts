import { computed, inject, Injectable } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { filter, map, startWith } from 'rxjs';

export const MESSAGES_VIEW_MODES = ['table', 'grid', 'details', 'map', 'selected'] as const;
export type MessagesViewMode = (typeof MESSAGES_VIEW_MODES)[number];

const MAP_PAGE_SIZE = 1000;
const DEFAULT_PAGE_SIZE = 200;

@Injectable({ providedIn: 'root' })
export class MessagesShellFacade {
  private readonly router = inject(Router);

  readonly viewMode = toSignal(
    this.router.events.pipe(
      filter((e): e is NavigationEnd => e instanceof NavigationEnd),
      startWith(null),
      map(() => deriveViewMode(this.router.url)),
    ),
    { initialValue: deriveViewMode(this.router.url) },
  );

  readonly isMap = computed(() => this.viewMode() === 'map');
  readonly showFilter = computed(() => this.viewMode() !== 'selected');

  /**
   * Whether a message view that needs search results is currently mounted.
   *
   * This doubles as the list's route re-entry trigger, which is why it must go
   * false off the messages area rather than merely off the Selected tab: the
   * list facade is an app-lifetime singleton, so its rows outlive a trip to the
   * editor. Leaving flips this false and returning flips it back true — and
   * that flip is the only thing that re-runs the search when the filter itself
   * is unchanged, so a message created while away shows up on return.
   */
  readonly requiresSearch = computed(() => {
    const mode = this.viewMode();
    return mode !== undefined && mode !== 'selected';
  });
  readonly maxPageSize = computed(() => (this.isMap() ? MAP_PAGE_SIZE : DEFAULT_PAGE_SIZE));
}

export function deriveViewMode(url: string): MessagesViewMode | undefined {
  const path = url.split('?')[0].split('#')[0];
  const segment = path.split('/').filter(Boolean).at(-1);
  return MESSAGES_VIEW_MODES.find((mode) => mode === segment);
}
