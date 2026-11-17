import { countryRouteProblems } from '@niord/admin/api/testing';
import { describe, expect, it } from 'vitest';

import { COUNTRY_MENU_ENTRIES } from './country.menu';
import routes from './country.routes';
import { HarbourPage } from './harbour/harbour-page';

describe('the example country routes', () => {
  it('guards every page, so the application mounts all of them', () => {
    expect(countryRouteProblems(routes)).toEqual([]);
  });

  it('loads the harbour page lazily', async () => {
    const harbour = routes.find((route) => route.path === 'harbour');
    expect(harbour?.component).toBeUndefined();
    expect(await harbour?.loadComponent?.()).toBe(HarbourPage);
  });

  it('points every menu entry at one of its pages, for the roles that may open it', () => {
    for (const entry of COUNTRY_MENU_ENTRIES) {
      const route = routes.find((candidate) => candidate.path === entry.routerLink);
      expect(route).toBeDefined();
      expect(entry.roles).toEqual(route?.data?.['rolesRequired']);
    }
  });
});
