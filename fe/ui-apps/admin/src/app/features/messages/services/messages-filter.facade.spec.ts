import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { describe, expect, it } from 'vitest';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import type { Domain } from '../../../core/domain/models/domain.model';
import { MAP_EXTENT_TOKEN } from '../tokens/map-extent.token';
import { MessagesFilterFacade } from './messages-filter.facade';
import { MessagesShellFacade } from './messages-shell.facade';
import { provideTestTransloco } from '../../../shared/testing/transloco-testing';

function setup(opts?: { available?: readonly Domain[] }): {
  facade: MessagesFilterFacade;
  searchDomain: ReturnType<typeof signal<Domain | undefined>>;
} {
  const available = opts?.available ?? [];

  const auth = {
    isAuthenticated: signal(false),
    permissions: signal({ editor: false, admin: false, sysadmin: false }),
  };

  const searchDomain = signal<Domain | undefined>(undefined);
  const domain = {
    searchDomain,
    searchPublicMessages: signal(false),
    defaultSortBy: signal('AREA' as const),
    defaultSortOrder: signal('ASC' as const),
    resolveById: (id: string | undefined) => available.find((d) => d.domainId === id),
    canEditMessageSeries: () => signal(false),
    setSearchDomain: (d: Domain | undefined) => searchDomain.set(d),
  };

  const shell = {
    isMap: signal(false),
    viewMode: signal('table' as const),
    showFilter: signal(true),
    requiresSearch: signal(true),
    maxPageSize: signal(200),
  };

  const mapExtent = signal(undefined);

  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      { provide: AuthFacade, useValue: auth },
      { provide: DomainFacade, useValue: domain },
      { provide: MessagesShellFacade, useValue: shell },
      { provide: MAP_EXTENT_TOKEN, useValue: mapExtent },
    ],
  });

  return { facade: TestBed.inject(MessagesFilterFacade), searchDomain };
}

describe('MessagesFilterFacade.toggleSort', () => {
  it('same field flips ASC to DESC', () => {
    const { facade } = setup();
    facade.setSortBy('AREA');
    facade.setSortOrder('ASC');

    facade.toggleSort('AREA');

    expect(facade.sortBy()).toBe('AREA');
    expect(facade.sortOrder()).toBe('DESC');
  });

  it('same field flips DESC to ASC', () => {
    const { facade } = setup();
    facade.setSortBy('AREA');
    facade.setSortOrder('DESC');

    facade.toggleSort('AREA');

    expect(facade.sortOrder()).toBe('ASC');
  });

  it('different field sets sortBy to the new field and order to ASC', () => {
    const { facade } = setup();
    facade.setSortBy('AREA');
    facade.setSortOrder('DESC');

    facade.toggleSort('ID');

    expect(facade.sortBy()).toBe('ID');
    expect(facade.sortOrder()).toBe('ASC');
  });

  it('switching from a non-default field resets to ASC', () => {
    const { facade } = setup();
    facade.setSortBy('ID');
    facade.setSortOrder('DESC');

    facade.toggleSort('PUBLISH_DATE');

    expect(facade.sortBy()).toBe('PUBLISH_DATE');
    expect(facade.sortOrder()).toBe('ASC');
  });
});

describe('MessagesFilterFacade domain -> searchDomain sync', () => {
  const nw: Domain = { domainId: 'nw', messageSeries: [{ seriesId: 's1', mainType: 'NW' }] };

  it('mirrors the enabled domain slice into searchDomain', () => {
    const { facade, searchDomain } = setup();
    facade.setSliceValue('domain', { domain: nw });
    facade.setEnabled('domain', true);
    TestBed.tick();
    expect(searchDomain()).toEqual(nw);
  });

  it('clears searchDomain when the domain filter is cleared (clearFilter)', () => {
    const { facade, searchDomain } = setup();
    facade.setSliceValue('domain', { domain: nw });
    facade.setEnabled('domain', true);
    TestBed.tick();
    facade.clearFilter('domain');
    TestBed.tick();
    expect(searchDomain()).toBeUndefined();
  });

  it('clears searchDomain on clearAll (regression: was left stale, forcing public-only statuses)', () => {
    const { facade, searchDomain } = setup();
    facade.setSliceValue('domain', { domain: nw });
    facade.setEnabled('domain', true);
    TestBed.tick();
    facade.clearAll();
    TestBed.tick();
    expect(searchDomain()).toBeUndefined();
  });

  it('clears searchDomain when the domain filter is disabled (regression)', () => {
    const { facade, searchDomain } = setup();
    facade.setSliceValue('domain', { domain: nw });
    facade.setEnabled('domain', true);
    TestBed.tick();
    facade.setEnabled('domain', false);
    TestBed.tick();
    expect(searchDomain()).toBeUndefined();
  });

  it('syncs searchDomain when a domain is restored from the URL (regression: deferDomainResolution/assignParams)', () => {
    const { facade, searchDomain } = setup({ available: [nw] });
    facade.applyQueryParams('domain=nw');
    TestBed.tick();
    expect(searchDomain()).toEqual(nw);
  });

  it('clears searchDomain when a later URL apply drops the domain param (regression: back-and-forth)', () => {
    const { facade, searchDomain } = setup({ available: [nw] });
    facade.applyQueryParams('domain=nw');
    TestBed.tick();
    expect(searchDomain()).toEqual(nw);
    facade.applyQueryParams('');
    TestBed.tick();
    expect(searchDomain()).toBeUndefined();
  });
});
