import type { CanMatchFn, Routes } from '@angular/router';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { EDITOR_AREA_ROLES } from '../auth/area-roles';
import { roleGuard } from '../auth/guards/role.guard';
import { baseTopLevelPaths, countryRouteProblems, guardedCountryRoutes } from './country-routes';

class Page {}

const otherGuard: CanMatchFn = () => true;

const guarded = {
  path: 'harbour',
  canMatch: [roleGuard],
  data: { rolesRequired: EDITOR_AREA_ROLES },
  component: Page,
};
const publicPage = { path: 'about', data: { public: true }, component: Page };
const redirect = { path: 'old-harbour', redirectTo: 'harbour' };

describe('country routes', () => {
  afterEach(() => vi.restoreAllMocks());

  it('keeps an entry guarded by roleGuard with its roles, a public one and a redirect', () => {
    const routes: Routes = [guarded, publicPage, redirect];
    expect(countryRouteProblems(routes)).toEqual([]);
    expect(guardedCountryRoutes(routes)).toEqual(routes);
  });

  it('drops and reports an entry with no guard', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const routes: Routes = [guarded, { path: 'open', component: Page }];
    expect(countryRouteProblems(routes)).toEqual([expect.stringContaining('"open"')]);
    expect(guardedCountryRoutes(routes)).toEqual([guarded]);
    expect(error).toHaveBeenCalledWith(expect.stringContaining('"open"'));
  });

  it('drops roleGuard with an empty or missing rolesRequired, which lets everyone through', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const routes: Routes = [
      { path: 'empty', canMatch: [roleGuard], data: { rolesRequired: [] }, component: Page },
      { path: 'missing', canMatch: [roleGuard], component: Page },
    ];
    expect(countryRouteProblems(routes)).toHaveLength(2);
    expect(guardedCountryRoutes(routes)).toEqual([]);
  });

  it("drops an entry on a base product path, public or guarded, which would show when the base route's canMatch turns the URL away", () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const base = baseTopLevelPaths([
      { path: '', component: Page },
      { path: 'messages', component: Page },
      { path: 'admin', canMatch: [roleGuard], component: Page },
      { path: 'dev/geo-editor', component: Page },
      { path: '**', redirectTo: '' },
    ]);
    expect(base).toEqual(['dev', 'messages', 'admin']);
    const shadow = { path: 'admin', data: { public: true }, component: Page };
    const nested = { ...guarded, path: 'messages/harbour' };
    const dev = { path: 'dev', data: { public: true }, component: Page };
    const routes: Routes = [guarded, shadow, nested, dev];
    expect(countryRouteProblems(routes, base)).toEqual([
      expect.stringContaining('"admin" is the base product\'s path'),
      expect.stringContaining('"messages" is the base product\'s path'),
      expect.stringContaining('"dev" is the base product\'s path'),
    ]);
    expect(guardedCountryRoutes(routes, base)).toEqual([guarded]);
    expect(error).toHaveBeenCalledTimes(3);
    // Without the base paths handed in, only the guards are checked, as before.
    expect(countryRouteProblems([shadow])).toEqual([]);
  });

  it('drops an entry whose canMatch is another function, roles or not', () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const routes: Routes = [
      {
        path: 'other',
        canMatch: [otherGuard],
        data: { rolesRequired: EDITOR_AREA_ROLES },
        component: Page,
      },
    ];
    expect(countryRouteProblems(routes)).toEqual([expect.stringContaining('"other"')]);
    expect(guardedCountryRoutes(routes)).toEqual([]);
  });
});
