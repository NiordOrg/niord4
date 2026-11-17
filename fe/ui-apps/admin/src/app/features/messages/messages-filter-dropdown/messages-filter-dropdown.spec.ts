import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it, vi } from 'vitest';
import type { MenuItem } from '../../../shared/models/menu-item.model';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import type { NamedFilter } from '../models/named-filter.model';
import { MessagesFacade } from '../services/messages-facade.service';
import { NamedFiltersFacade } from '../services/named-filters.facade';
import { MessagesFilterDropdown } from './messages-filter-dropdown';
import { ConfirmService } from '../../../shared/services/confirm.service';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';

interface NamedFilterMenuItem extends MenuItem {
  readonly extra?: { readonly onDelete: () => void };
}

function setup(opts: {
  isAuth?: boolean;
  filters?: NamedFilter[];
  loading?: boolean;
  hasLoaded?: boolean;
  loadFailed?: boolean;
}) {
  const auth = { isAuthenticated: signal(opts.isAuth ?? true) };

  const apply = vi.fn();
  const confirmRemove = vi.fn();
  const namedFiltersFacade = {
    namedFilters: signal<readonly NamedFilter[]>(opts.filters ?? []),
    loading: signal(opts.loading ?? false),
    hasLoaded: signal(opts.hasLoaded ?? true),
    loadFailed: signal(opts.loadFailed ?? false),
    apply,
    confirmRemove,
    load: vi.fn(),
  };

  const clearAll = vi.fn();
  const messages = {
    filter: {
      filterToggleItems: signal<MenuItem[]>([]),
      enabledFilterCount: signal(0),
      clearAll,
    },
  };

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: AuthFacade, useValue: auth },
      { provide: NamedFiltersFacade, useValue: namedFiltersFacade },
      { provide: MessagesFacade, useValue: messages },
      // The component mounts the root-bound <app-confirm>, which injects ConfirmService.
      ConfirmService,
    ],
  });

  const fixture = TestBed.createComponent(MessagesFilterDropdown);
  return { component: fixture.componentInstance, apply, confirmRemove };
}

function namedItems(items: MenuItem[]): MenuItem[] {
  // Named-filter rows carry the delete seam; the trailing clearAll/saveNamed do not.
  return items.filter(
    (i) =>
      !!(i as NamedFilterMenuItem).extra?.onDelete ||
      i.styleClass === 'named-filter-loading' ||
      i.styleClass === 'named-filter-error',
  );
}

describe('MessagesFilterDropdown named-filter items', () => {
  it('renders no named items when anonymous', () => {
    const { component } = setup({
      isAuth: false,
      filters: [{ id: 1, name: 'Foo', parameters: '' } as NamedFilter],
    });
    expect(namedItems(component.items())).toEqual([]);
  });

  it('shows a disabled loading placeholder while loading with nothing loaded yet', () => {
    const { component } = setup({ loading: true, hasLoaded: false });
    const named = namedItems(component.items());
    expect(named).toHaveLength(1);
    expect(named[0].disabled).toBe(true);
    expect(named[0].styleClass).toBe('named-filter-loading');
  });

  it('maps loaded filters to items whose command applies and whose onDelete confirms-remove', () => {
    const filter = { id: 7, name: 'Foo', parameters: 'tag=x' } as NamedFilter;
    const { component, apply, confirmRemove } = setup({ filters: [filter] });
    const named = namedItems(component.items()) as NamedFilterMenuItem[];
    expect(named.map((i) => i.label)).toEqual(['Foo']);

    named[0].command?.({});
    expect(apply).toHaveBeenCalledWith(filter);

    named[0].extra?.onDelete();
    expect(confirmRemove).toHaveBeenCalledWith(filter);
  });

  // L-043: a failed load read as "no saved filters". It now shows the load error as a disabled row.
  it('shows the load error as a disabled item when the saved filters failed to load', () => {
    const { component } = setup({ loadFailed: true, hasLoaded: false });
    const named = namedItems(component.items());
    expect(named).toHaveLength(1);
    expect(named[0].label).toBe('messages.namedFilter.error.load');
    expect(named[0].disabled).toBe(true);
    expect(named[0].styleClass).toBe('named-filter-error');
    expect(named[0].icon).toBe('lucideTriangleAlert');
    expect(named[0].command).toBeUndefined();
  });

  it('shows no error item when the load did not fail and there are no saved filters', () => {
    const { component } = setup({ filters: [] });
    expect(namedItems(component.items())).toEqual([]);
  });

  it('shows no error item to an anonymous user', () => {
    const { component } = setup({ isAuth: false, loadFailed: true });
    expect(namedItems(component.items())).toEqual([]);
  });
});
