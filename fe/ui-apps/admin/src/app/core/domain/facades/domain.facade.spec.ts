import { TestBed } from '@angular/core/testing';
import { Component, signal } from '@angular/core';
import { provideRouter, Router, type Routes } from '@angular/router';
import { describe, expect, it } from 'vitest';

import { AuthFacade } from '../../auth/facades/auth.facade';
import type { Domain } from '../models/domain.model';
import { DomainFacade } from './domain.facade';
import { DomainService } from '../services/domain.service';

interface FakeAuth {
  isAuthenticated: ReturnType<typeof signal<boolean>>;
  hasRoleResult: ReturnType<typeof signal<boolean>>;
  hasRole: () => ReturnType<typeof signal<boolean>>;
  /** The backend's `user` role in the session domain. */
  hasDomainUserRole: ReturnType<typeof signal<boolean>>;
  hasRolesFor: (domainId: string) => boolean;
}

function makeAuth(opts?: {
  isAuthenticated?: boolean;
  /** Domain ids the user holds ANY client role on (`hasRolesFor`). */
  rolesFor?: readonly string[];
}): FakeAuth {
  const isAuthenticated = signal(opts?.isAuthenticated ?? false);
  const hasRoleResult = signal(opts?.isAuthenticated ?? false);
  const hasDomainUserRole = signal(opts?.isAuthenticated ?? false);
  const rolesFor = new Set(opts?.rolesFor ?? []);
  return {
    isAuthenticated,
    hasRoleResult,
    hasRole: () => hasRoleResult,
    hasDomainUserRole,
    hasRolesFor: (domainId) => rolesFor.has(domainId),
  };
}

interface FakeDomainService {
  availableDomains: ReturnType<typeof signal<readonly Domain[]>>;
  sessionDomain: ReturnType<typeof signal<Domain | undefined>>;
  searchDomain: ReturnType<typeof signal<Domain | undefined>>;
  setSessionDomain: (d: Domain | undefined) => void;
  setSearchDomain: (d: Domain | undefined) => void;
  resolveById: (id: string | undefined) => Domain | undefined;
}

function makeService(initial: {
  available?: readonly Domain[];
  session?: Domain;
  search?: Domain;
}): FakeDomainService {
  const availableDomains = signal<readonly Domain[]>(initial.available ?? []);
  const sessionDomain = signal<Domain | undefined>(initial.session);
  const searchDomain = signal<Domain | undefined>(initial.search);
  return {
    availableDomains,
    sessionDomain,
    searchDomain,
    setSessionDomain: (d) => sessionDomain.set(d),
    setSearchDomain: (d) => searchDomain.set(d),
    resolveById: (id) => availableDomains().find((d) => d.domainId === id),
  };
}

function configure(service: FakeDomainService, auth: FakeAuth): DomainFacade {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      { provide: DomainService, useValue: service },
      { provide: AuthFacade, useValue: auth },
    ],
  });
  return TestBed.inject(DomainFacade);
}

const seriesNwAll: Domain = {
  domainId: 'nw',
  messageSeries: [{ seriesId: 's1', mainType: 'NW' }],
};
const seriesNmAll: Domain = {
  domainId: 'nm',
  messageSeries: [{ seriesId: 's2', mainType: 'NM' }],
};
const seriesBoth: Domain = {
  domainId: 'mix',
  messageSeries: [
    { seriesId: 's1', mainType: 'NW' },
    { seriesId: 's2', mainType: 'NM' },
  ],
};
const noSeries: Domain = { domainId: 'empty', messageSeries: [] };
const explicitTypes: Domain = {
  domainId: 'explicit',
  messageSeries: [{ seriesId: 's1', mainType: 'NW', types: ['LOCAL_WARNING'] }],
};

describe('DomainFacade', () => {
  describe('searchDomainEffective', () => {
    it('falls back to sessionDomain when searchDomain is undefined', () => {
      const facade = configure(makeService({ session: seriesNwAll }), makeAuth());
      expect(facade.searchDomainEffective()).toEqual(seriesNwAll);
    });

    it('uses searchDomain when set', () => {
      const facade = configure(
        makeService({ session: seriesNwAll, search: seriesNmAll }),
        makeAuth(),
      );
      expect(facade.searchDomainEffective()).toEqual(seriesNmAll);
    });
  });

  describe('supportsMainType', () => {
    it('is permissive when no domain is selected', () => {
      const facade = configure(makeService({}), makeAuth());
      expect(facade.supportsMainType('NW')()).toBe(true);
      expect(facade.supportsMainType('NM')()).toBe(true);
    });

    it('matches the domain mainType when set', () => {
      const facade = configure(makeService({ session: seriesNwAll }), makeAuth());
      expect(facade.supportsMainType('NW')()).toBe(true);
      expect(facade.supportsMainType('NM')()).toBe(false);
    });

    it('returns false when domain has empty messageSeries', () => {
      const facade = configure(makeService({ session: noSeries }), makeAuth());
      expect(facade.supportsMainType('NW')()).toBe(false);
    });

    it('returns the same Signal reference on repeated calls', () => {
      const facade = configure(makeService({}), makeAuth());
      expect(facade.supportsMainType('NW')).toBe(facade.supportsMainType('NW'));
    });
  });

  describe('supportsType', () => {
    it('is permissive when no domain is selected', () => {
      const facade = configure(makeService({}), makeAuth());
      expect(facade.supportsType('LOCAL_WARNING')()).toBe(true);
    });

    it('returns false when domain has empty messageSeries', () => {
      const facade = configure(makeService({ session: noSeries }), makeAuth());
      expect(facade.supportsType('LOCAL_WARNING')()).toBe(false);
    });

    it('is permissive when series has undefined or empty types', () => {
      const facade = configure(makeService({ session: seriesNwAll }), makeAuth());
      expect(facade.supportsType('LOCAL_WARNING')()).toBe(true);
      expect(facade.supportsType('PERMANENT_NOTICE')()).toBe(true);
    });

    it('respects explicit types list', () => {
      const facade = configure(makeService({ session: explicitTypes }), makeAuth());
      expect(facade.supportsType('LOCAL_WARNING')()).toBe(true);
      expect(facade.supportsType('COASTAL_WARNING')()).toBe(false);
    });
  });

  describe('supportsNw / supportsNm exclusively', () => {
    it('exclusively NW only when NM is not supported', () => {
      const facade = configure(makeService({ session: seriesBoth }), makeAuth());
      expect(facade.supportsNw(true)()).toBe(false);
      expect(facade.supportsNw(false)()).toBe(true);
    });

    it('exclusively NW true when only NW is supported', () => {
      const facade = configure(makeService({ session: seriesNwAll }), makeAuth());
      expect(facade.supportsNw(true)()).toBe(true);
    });
  });

  describe('searchPublicMessages', () => {
    const cases: [boolean, boolean, boolean][] = [
      [false, true, true],
      [false, false, true],
      [true, true, false],
      [true, false, true],
    ];
    it.each(cases)(
      'domainUserRole=%s sameDomain=%s -> public=%s',
      (domainUserRole, sameDomain, expected) => {
        const session = sameDomain ? seriesNwAll : { domainId: 'a' };
        const search = sameDomain ? seriesNwAll : { domainId: 'b' };
        const auth = makeAuth({ isAuthenticated: true });
        auth.hasDomainUserRole.set(domainUserRole);
        const facade = configure(makeService({ session, search }), auth);
        expect(facade.searchPublicMessages()).toBe(expected);
      },
    );

    it('is true for a signed-in user with no role in the session domain', () => {
      // Signed in, so hasRole('user') answers true; the backend still finds public messages only.
      const auth = makeAuth({ isAuthenticated: true });
      auth.hasDomainUserRole.set(false);
      const facade = configure(makeService({ session: seriesNwAll }), auth);
      expect(auth.hasRole()()).toBe(true);
      expect(facade.searchPublicMessages()).toBe(true);
    });

    it('is true when no user role and no domains set', () => {
      const auth = makeAuth();
      const facade = configure(makeService({}), auth);
      expect(facade.searchPublicMessages()).toBe(true);
    });
  });

  describe('default sort', () => {
    it('parses messageSortOrder from session domain', () => {
      const facade = configure(
        makeService({ session: { domainId: 'd', messageSortOrder: 'ID DESC' } }),
        makeAuth(),
      );
      expect(facade.defaultSortBy()).toBe('ID');
      expect(facade.defaultSortOrder()).toBe('DESC');
    });

    it('falls back to AREA ASC when undefined', () => {
      const facade = configure(makeService({}), makeAuth());
      expect(facade.defaultSortBy()).toBe('AREA');
      expect(facade.defaultSortOrder()).toBe('ASC');
    });
  });

  describe('mapDefaults', () => {
    it('returns center+zoom when set on session domain', () => {
      const facade = configure(
        makeService({ session: { domainId: 'd', lat: 56, lon: 11, zoomLevel: 6 } }),
        makeAuth(),
      );
      expect(facade.mapDefaults()).toEqual({ center: [11, 56], zoom: 6 });
    });

    it('returns undefined center when lat/lon missing', () => {
      const facade = configure(makeService({ session: { domainId: 'd' } }), makeAuth());
      expect(facade.mapDefaults().center).toBeUndefined();
    });
  });

  describe('capabilities', () => {
    it('mirrors session domain flags', () => {
      const facade = configure(
        makeService({
          session: {
            domainId: 'd',
            atons: true,
            templates: false,
            publish: true,
            firingSchedule: false,
          },
        }),
        makeAuth(),
      );
      expect(facade.capabilities()).toEqual({
        atons: true,
        templates: false,
        publish: true,
        firingSchedule: false,
      });
    });

    it('returns all-false when no session domain', () => {
      const facade = configure(makeService({}), makeAuth());
      expect(facade.capabilities()).toEqual({
        atons: false,
        templates: false,
        publish: false,
        firingSchedule: false,
      });
    });
  });

  describe('editableDomains', () => {
    it('returns every domain the user holds a role on', () => {
      const facade = configure(
        makeService({ available: [seriesNwAll, seriesNmAll] }),
        makeAuth({
          isAuthenticated: true,
          rolesFor: [seriesNwAll.domainId, seriesNmAll.domainId],
        }),
      );
      expect(facade.editableDomains()).toEqual([seriesNwAll, seriesNmAll]);
    });

    it('keeps only the domains the user has a client role on', () => {
      const facade = configure(
        makeService({ available: [seriesNwAll, seriesNmAll] }),
        makeAuth({
          isAuthenticated: true,
          // A role on the first domain only.
          rolesFor: [seriesNwAll.domainId],
        }),
      );
      expect(facade.editableDomains()).toEqual([seriesNwAll]);
    });

    it('returns empty when user has no roles', () => {
      const facade = configure(makeService({ available: [seriesNwAll, seriesNmAll] }), makeAuth());
      expect(facade.editableDomains()).toEqual([]);
    });
  });

  /**
   * Applying a desk keeps the address the reader is on — the query string too —
   * unless the route itself says otherwise.
   *
   * A screen that carries state in the URL is asking to come back up where the
   * reader was: an archive filter, a saved message search. Dropping half of that
   * address lands them somewhere they did not choose, and what the reload exists
   * to clear is state held in memory, none of which lives in the address. A page
   * addressed inside ONE desk is the exception it declares for itself, through
   * `data.onDeskChange`: its address names a record the new desk may not show.
   */
  describe('selectSessionDomain', () => {
    /** The window-location boundary, so the runner is never navigated. */
    class TestDomainFacade extends DomainFacade {
      url: string | undefined;
      override navigateTo(url: string): void {
        this.url = url;
      }
    }

    @Component({ template: '' })
    class Blank {}

    const routes: Routes = [
      // A list every desk can read: a desk change stays on it.
      { path: 'admin/publications', component: Blank },
      // One desk's record, declaring where a desk change leaves it instead.
      {
        path: 'admin/publications/:seriesId',
        component: Blank,
        data: { onDeskChange: '/admin/publications' },
      },
    ];

    async function switchTo(
      domainId: string | undefined,
      address: string,
      options?: { keepAddress?: boolean },
    ) {
      const service = makeService({ available: [seriesNwAll, seriesNmAll], session: seriesNwAll });
      TestBed.configureTestingModule({
        providers: [
          provideRouter(routes),
          { provide: DomainService, useValue: service },
          { provide: AuthFacade, useValue: makeAuth() },
          { provide: DomainFacade, useClass: TestDomainFacade },
        ],
      });
      const facade = TestBed.inject(DomainFacade) as TestDomainFacade;
      const before = window.location.href;
      try {
        // Both halves of the reader's position: the router's activated route,
        // which carries the declaration, and the address itself, which is what
        // an undeclared reload keeps.
        await TestBed.inject(Router).navigateByUrl(address);
        window.history.replaceState({}, '', address);
        facade.selectSessionDomain(domainId, options);
      } finally {
        window.history.replaceState({}, '', before);
      }
      return facade;
    }

    it('reloads onto the same address, query string and all', async () => {
      const facade = await switchTo('nm', '/admin/publications?status=OPEN&q=weekly');

      expect(facade.url).toBe('/admin/publications?status=OPEN&q=weekly');
    });

    it('reloads onto a bare path unchanged', async () => {
      const facade = await switchTo('nm', '/admin/publications');

      expect(facade.url).toBe('/admin/publications');
    });

    it('reloads onto the address the route declares for a desk change', async () => {
      const facade = await switchTo('nm', '/admin/publications/weekly-ntm?issue=i-1');

      expect(facade.url).toBe('/admin/publications');
    });

    it('keeps the address when the caller asks for it, declaration or not', async () => {
      const facade = await switchTo('nm', '/admin/publications/weekly-ntm?issue=i-1', {
        keepAddress: true,
      });

      expect(facade.url).toBe('/admin/publications/weekly-ntm?issue=i-1');
    });

    it('takes the new domain into session', async () => {
      const facade = await switchTo('nm', '/admin/publications');

      expect(facade.sessionDomain()).toEqual(seriesNmAll);
    });
  });

  describe('canEditMessageSeries', () => {
    it('is true when seriesId is in session domain series', () => {
      const facade = configure(makeService({ session: seriesNwAll }), makeAuth());
      expect(facade.canEditMessageSeries('s1')()).toBe(true);
      expect(facade.canEditMessageSeries('missing')()).toBe(false);
    });
  });

  describe('domainMessageSeriesIds', () => {
    it('returns ids from session domain', () => {
      const facade = configure(makeService({ session: seriesBoth }), makeAuth());
      expect(facade.domainMessageSeriesIds()).toEqual(['s1', 's2']);
    });

    it('returns empty when no session domain', () => {
      const facade = configure(makeService({}), makeAuth());
      expect(facade.domainMessageSeriesIds()).toEqual([]);
    });
  });
});
