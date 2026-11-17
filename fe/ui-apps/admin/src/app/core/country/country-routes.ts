import type { Route, Routes } from '@angular/router';

import { roleGuard } from '../auth/guards/role.guard';

/**
 * The first path segments of the base product's top-level routes, and `dev`, whose routes a
 * production build leaves out: the paths no country route may take. A country route on one of
 * them would match whenever the base route does not — a base route's `canMatch` that returns
 * false hands the URL to the next route, as `roleGuard` does for an anonymous reader while the
 * login redirect is in flight — so it could show its page in the base route's place.
 */
export function baseTopLevelPaths(routes: Routes): string[] {
  const first = routes
    .map((route) => route.path?.split('/')[0] ?? '')
    .filter((path) => path !== '' && path !== '**');
  return [...new Set(['dev', ...first])];
}

/**
 * Why each top-level country route would be unguarded or would take a base path, one line per
 * entry. An entry passes when it is a redirect, is marked `data: { public: true }`, or has
 * `roleGuard` in its `canMatch` together with a non-empty `data.rolesRequired` (the guard lets
 * everyone through when that list is empty), and when the first segment of its path is none of
 * `basePaths` (baseTopLevelPaths; the application hands in its own).
 */
export function countryRouteProblems(routes: Routes, basePaths: readonly string[] = []): string[] {
  return routes
    .map((route) => problemOf(route, basePaths))
    .filter((problem): problem is string => problem !== undefined);
}

/**
 * The country routes the application mounts: those that are guarded, public or a redirect, on a
 * path that is not the base product's (`basePaths`). Any other is left out and reported, so a
 * forgotten guard closes the page instead of opening it, and a base path is never shadowed; the
 * URL then falls through to the application's wildcard like any unknown one.
 */
export function guardedCountryRoutes(routes: Routes, basePaths: readonly string[] = []): Routes {
  const kept: Route[] = [];
  for (const route of routes) {
    const problem = problemOf(route, basePaths);
    if (problem === undefined) kept.push(route);
    else console.error(problem);
  }
  return kept;
}

function isGuarded(route: Route): boolean {
  if (route.redirectTo !== undefined) return true;
  if (route.data?.['public'] === true) return true;
  const roles: unknown = route.data?.['rolesRequired'];
  return (route.canMatch ?? []).includes(roleGuard) && Array.isArray(roles) && roles.length > 0;
}

function problemOf(route: Route, basePaths: readonly string[]): string | undefined {
  const path = route.path ?? '';
  const first = path.split('/')[0];
  if (basePaths.includes(first))
    return `country route "${path}" is left out: "${first}" is the base product's path, which a country route never takes`;
  if (!isGuarded(route))
    return `country route "${path}" is left out: it needs canMatch: [roleGuard] with a non-empty data.rolesRequired, data: { public: true }, or a redirectTo`;
  return undefined;
}
