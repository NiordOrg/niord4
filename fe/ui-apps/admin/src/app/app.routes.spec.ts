import { describe, expect, it } from 'vitest';

import {
  ADMIN_AREA_ROLES,
  EDITOR_AREA_ROLES,
  EDITOR_VIEW_ROLES,
  SYSADMIN_AREA_ROLES,
} from './core/auth/area-roles';
import { roleGuard } from './core/auth/guards/role.guard';
import { routes } from './app.routes';

function routeByPath(path: string) {
  return routes.find((r) => r.path === path);
}

describe('app root routes', () => {
  it('ends with the wildcard fallback that redirects unknown URLs to home', () => {
    const last = routes[routes.length - 1];
    expect(last.path).toBe('**');
    expect(last.redirectTo).toBe('');
  });

  it("mounts the country's own routes lazily, pathless and just before the wildcard", async () => {
    const country = routes[routes.length - 2];
    expect(country.path).toBe('');
    expect(country.canMatch).toBeUndefined();
    expect(country.component).toBeUndefined();
    expect(country.loadComponent).toBeUndefined();
    // The base build's default file has none.
    expect(await country.loadChildren?.()).toEqual([]);
  });

  describe('role-gated areas', () => {
    const cases = [
      { path: 'schedule', roles: EDITOR_AREA_ROLES },
      { path: 'editor', roles: EDITOR_VIEW_ROLES },
      { path: 'admin', roles: ADMIN_AREA_ROLES },
      { path: 'sysadmin', roles: SYSADMIN_AREA_ROLES },
    ] as const;

    for (const { path, roles } of cases) {
      it(`${path} is guarded by roleGuard with its area roles`, () => {
        const route = routeByPath(path);
        expect(route?.canMatch).toEqual([roleGuard]);
        // Same constant reference the guard reads from data.rolesRequired.
        expect(route?.data?.['rolesRequired']).toBe(roles);
      });
    }
  });

  it('leaves the home and messages routes ungated (no canMatch)', () => {
    expect(routeByPath('')?.canMatch).toBeUndefined();
    expect(routeByPath('messages')?.canMatch).toBeUndefined();
  });

  it('registers the dev-only geometry-editor sandbox route in this (non-production) build', () => {
    // `environment.production` is false in unit/dev builds, so `devRoutes` is populated.
    // The production exclusion is a build-time ternary in app.routes.ts keyed off
    // `environment.production` (true in environment.production.ts and environment.test.ts);
    // it is fixed at module-load time and cannot be flipped inside a unit test, so this only
    // asserts the dev branch is present here.
    expect(routeByPath('dev/geo-editor')).toBeDefined();
  });
});
