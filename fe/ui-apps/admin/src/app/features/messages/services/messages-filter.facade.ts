import {
  computed,
  effect,
  untracked,
  inject,
  Injectable,
  signal,
  Signal,
  WritableSignal,
} from '@angular/core';
import { translateSignal } from '@jsverse/transloco';
import type { MenuItem } from '../../../shared/models/menu-item.model';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../core/domain/models/domain.model';
import {
  emptyEnabledMap,
  emptySliceValues,
  FILTER_NAMES,
  type CommentsValue,
  type FilterName,
  type MapExtent,
  type MessagesFilterParams,
  type SliceValueOf,
  type SortBy,
  type SortOrder,
} from '../models/messages-filter-params.model';
import type { MessageStatus } from '../models/message.model';
import { PUBLISHED_MESSAGE_STATUSES } from '../models/message.model';
import { MAP_EXTENT_TOKEN } from '../tokens/map-extent.token';
import { MapExtentService } from './map-extent.service';
import { MessagesShellFacade } from './messages-shell.facade';
import {
  defaultParams,
  fromQuery,
  toQuery,
  type DeserializerContext,
  type SerializerContext,
} from './messages-filter-params.serializer';

type SliceSignalMap = {
  [K in FilterName]: WritableSignal<SliceValueOf<K>>;
};

/** A filter-type toggle row; `chosen` marks the filter as enabled (gets a dot). */
export interface FilterToggleMenuItem extends MenuItem {
  readonly chosen: boolean;
}

/**
 * Per-filter-type icons for the filter menu. Reuses the icon each type already
 * carries elsewhere — the admin sections (domains/series/publications/
 * promulgation/categories/charts/areas) and the filter panels (area/category/
 * tag) — so the same concept reads the same everywhere.
 */
const FILTER_ICONS: Record<Exclude<FilterName, 'aton'>, string> = {
  domain: 'lucideGlobe',
  messageSeries: 'lucideList',
  text: 'lucideSearch',
  type: 'lucideFlag',
  status: 'lucideInfo',
  tag: 'lucideTags',
  publication: 'lucideBook',
  promulgationType: 'lucideSend',
  user: 'lucideUsers',
  comments: 'lucideMessageSquare',
  reference: 'lucideLink',
  chart: 'lucideMap',
  area: 'lucideMapPin',
  category: 'lucideFolder',
  date: 'lucideCalendar',
};

@Injectable({ providedIn: 'root' })
export class MessagesFilterFacade {
  private readonly auth = inject(AuthFacade);
  private readonly domain = inject(DomainFacade);
  private readonly shell = inject(MessagesShellFacade);
  private readonly mapExtent: Signal<MapExtent | undefined> = inject(MAP_EXTENT_TOKEN);
  private readonly mapExtentService = inject(MapExtentService);

  private readonly enabledMap = signal<Record<FilterName, boolean>>(emptyEnabledMap());

  private readonly slices: SliceSignalMap = (() => {
    const initial = emptySliceValues();
    const result = {} as SliceSignalMap;
    for (const name of FILTER_NAMES) {
      // The cast is type-safe because emptySliceValues returns SliceValueOf<typeof name> per key.
      (result as Record<FilterName, WritableSignal<unknown>>)[name] = signal(initial[name]);
    }
    return result;
  })();

  readonly sortBy = signal<SortBy>('AREA');
  readonly sortOrder = signal<SortOrder>('ASC');

  private readonly enabledSignals = new Map<FilterName, Signal<boolean>>();
  private readonly sliceReadSignals = new Map<FilterName, Signal<unknown>>();

  readonly effectiveStatusFilter = computed<readonly MessageStatus[]>(() => {
    const stored = this.slices.status().statuses;
    if (!this.domain.searchPublicMessages()) {
      return stored;
    }
    return stored.filter((status) => PUBLISHED_MESSAGE_STATUSES.has(status));
  });

  readonly effectiveCommentsValue = computed<CommentsValue>(() => {
    const stored = this.slices.comments().value;
    if (this.auth.isAuthenticated()) {
      return stored;
    }
    if (stored === 'OWN' || stored === 'OWN_UNACK') {
      return '';
    }
    return stored;
  });

  readonly snapshotParams: Signal<MessagesFilterParams> = computed(() => ({
    enabled: this.enabledMap(),
    domain: this.slices.domain(),
    messageSeries: this.slices.messageSeries(),
    text: this.slices.text(),
    type: this.slices.type(),
    status: this.slices.status(),
    tag: this.slices.tag(),
    publication: this.slices.publication(),
    promulgationType: this.slices.promulgationType(),
    user: this.slices.user(),
    comments: this.slices.comments(),
    reference: this.slices.reference(),
    aton: this.slices.aton(),
    chart: this.slices.chart(),
    area: this.slices.area(),
    category: this.slices.category(),
    date: this.slices.date(),
    sortBy: this.sortBy(),
    sortOrder: this.sortOrder(),
  }));

  readonly requestParams: Signal<string> = computed(() =>
    toQuery(this.snapshotParams(), this.serializerContext()),
  );

  private serializerContext(): SerializerContext {
    return {
      defaultSortBy: this.domain.defaultSortBy(),
      defaultSortOrder: this.domain.defaultSortOrder(),
      mapExtent: this.mapExtent(),
      isMap: this.shell.isMap(),
      searchPublicMessages: this.domain.searchPublicMessages(),
      isAuthenticated: this.auth.isAuthenticated(),
    };
  }

  private deserializerContext(): DeserializerContext {
    return {
      isAuthenticated: this.auth.isAuthenticated(),
      resolveDomain: (id: string): Domain | undefined => this.domain.resolveById(id),
    };
  }

  private readonly toggleLabels: Record<Exclude<FilterName, 'aton'>, Signal<string>> = {
    domain: translateSignal('menu.filter.domain'),
    messageSeries: translateSignal('menu.filter.messageSeries'),
    text: translateSignal('menu.filter.text'),
    type: translateSignal('menu.filter.type'),
    status: translateSignal('menu.filter.status'),
    tag: translateSignal('menu.filter.tag'),
    publication: translateSignal('menu.filter.publication'),
    promulgationType: translateSignal('menu.filter.promulgationType'),
    user: translateSignal('menu.filter.user'),
    comments: translateSignal('menu.filter.comments'),
    reference: translateSignal('menu.filter.reference'),
    chart: translateSignal('menu.filter.chart'),
    area: translateSignal('menu.filter.area'),
    category: translateSignal('menu.filter.category'),
    date: translateSignal('menu.filter.date'),
  };

  /**
   * The filter whose panel should take the caret, set when that filter is switched on from the
   * menu. Panels built around a single text field consume it on appearing and clear it; panels
   * that are pick-lists ignore it.
   */
  readonly panelFocusRequest = signal<FilterName | undefined>(undefined);

  readonly filterToggleItems: Signal<readonly FilterToggleMenuItem[]> = computed(() => {
    const enabled = this.enabledMap();
    const isAuth = this.auth.isAuthenticated();
    const items: FilterToggleMenuItem[] = [];
    for (const name of FILTER_NAMES) {
      // AtoN visible UI is deferred to a future AtoN feature. The `aton`
      // slot stays in FILTER_NAMES + model/serializer for opaque URL round-trip.
      if (name === 'aton') {
        continue;
      }
      if (name === 'user' && !isAuth) {
        continue;
      }
      items.push({
        label: this.toggleLabels[name](),
        icon: FILTER_ICONS[name],
        chosen: enabled[name],
        command: () => {
          const on = !this.enabledMap()[name];
          this.setEnabled(name, on);
          // Switching a filter ON from the menu is a request to use it right away, so its
          // panel may take the caret. Restoring the same filter from a URL or a named filter
          // must NOT — hence the request rather than "focus whenever the panel appears".
          if (on) {
            this.panelFocusRequest.set(name);
          }
        },
      });
    }
    return items;
  });

  /** Count of enabled (chosen) filter types — shown as the menu trigger's badge. */
  readonly enabledFilterCount: Signal<number> = computed(() => {
    const enabled = this.enabledMap();
    const isAuth = this.auth.isAuthenticated();
    let count = 0;
    for (const name of FILTER_NAMES) {
      if (name === 'aton') {
        continue;
      }
      if (name === 'user' && !isAuth) {
        continue;
      }
      if (enabled[name]) {
        count++;
      }
    }
    return count;
  });

  constructor() {
    effect(() => {
      if (!this.auth.isAuthenticated()) {
        const current = this.slices.user();
        if (current.username !== undefined || current.userType !== undefined) {
          // untracked: clearFilter writes the user slice this effect just read —
          // the emptied-slice guard terminates the loop either way, but the write
          // itself must not be tracked (signal-first guideline).
          untracked(() => this.clearFilter('user'));
        }
      }
    });

    // Mirror the domain filter slice into DomainService.searchDomain — the single source
    // of truth the type and status filter options derive from (DomainFacade.searchDomainEffective
    // and .searchPublicMessages, the latter also gating effectiveStatusFilter). searchDomain was
    // previously hand-synced only from DomainFilterPanel, so every other path that mutates the
    // domain slice (URL restore via deferDomainResolution / assignParams, clearAll, named-filter
    // apply, setEnabled('domain', false)) left it stale — showing the wrong domain's message types
    // and forcing the public-only status set for a logged-in user. This single watcher fires for
    // every writer of the slice, so the two can never desync regardless of which path changed the
    // filter.
    effect(() => {
      const domain = this.enabled('domain')() ? this.slices.domain().domain : undefined;
      this.domain.setSearchDomain(domain);
    });
  }

  enabled(name: FilterName): Signal<boolean> {
    const cached = this.enabledSignals.get(name);
    if (cached) {
      return cached;
    }
    const created = computed(() => this.enabledMap()[name]);
    this.enabledSignals.set(name, created);
    return created;
  }

  slice<K extends FilterName>(name: K): Signal<SliceValueOf<K>> {
    const cached = this.sliceReadSignals.get(name);
    if (cached) {
      return cached as Signal<SliceValueOf<K>>;
    }
    const created = computed(() => this.slices[name]() as SliceValueOf<K>);
    this.sliceReadSignals.set(name, created);
    return created;
  }

  setEnabled(name: FilterName, on: boolean): void {
    const current = this.enabledMap()[name];
    if (current === on) {
      return;
    }
    if (!on) {
      this.resetSliceValue(name);
    }
    this.enabledMap.update((prev) => ({ ...prev, [name]: on }));
  }

  setSliceValue<K extends FilterName>(name: K, value: SliceValueOf<K>): void {
    (this.slices[name] as WritableSignal<SliceValueOf<K>>).set(value);
  }

  clearFilter(name: FilterName): void {
    this.resetSliceValue(name);
    if (this.enabledMap()[name]) {
      this.enabledMap.update((prev) => ({ ...prev, [name]: false }));
    }
  }

  clearAll(): void {
    for (const name of FILTER_NAMES) {
      this.resetSliceValue(name);
    }
    this.enabledMap.set(emptyEnabledMap());
  }

  applyQueryParams(query: URLSearchParams | string): { pendingDomainId?: string } {
    const result = fromQuery(
      query,
      defaultParams({
        sortBy: this.domain.defaultSortBy(),
        sortOrder: this.domain.defaultSortOrder(),
      }),
      this.deserializerContext(),
    );
    this.assignParams(result.params);
    if (result.mapExtent !== undefined) {
      this.mapExtentService.setFromUrl(result.mapExtent);
    }
    return { pendingDomainId: result.pendingDomainId };
  }

  applyQueryString(rawQuery: string): { pendingDomainId?: string } {
    return this.applyQueryParams(rawQuery);
  }

  setSortBy(value: SortBy): void {
    this.sortBy.set(value);
  }

  setSortOrder(value: SortOrder): void {
    this.sortOrder.set(value);
  }

  toggleSort(field: SortBy): void {
    if (this.sortBy() === field) {
      this.sortOrder.set(this.sortOrder() === 'ASC' ? 'DESC' : 'ASC');
    } else {
      this.sortBy.set(field);
      this.sortOrder.set('ASC');
    }
  }

  private resetSliceValue(name: FilterName): void {
    const empty = emptySliceValues();
    (this.slices[name] as WritableSignal<unknown>).set(empty[name]);
  }

  private assignParams(params: MessagesFilterParams): void {
    for (const name of FILTER_NAMES) {
      (this.slices[name] as WritableSignal<unknown>).set(params[name]);
    }
    this.enabledMap.set({ ...params.enabled });
    this.sortBy.set(params.sortBy);
    this.sortOrder.set(params.sortOrder);
  }
}
