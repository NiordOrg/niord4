import type { Routes } from '@angular/router';

/**
 * The country's own pages, loaded lazily under the application's router after every base route.
 * Every top-level entry declares `canMatch: [roleGuard]` with a non-empty `data.rolesRequired`, or
 * `data: { public: true }`, or is a redirect; core/country/country-routes.ts drops any other.
 */
const routes: Routes = [];

export default routes;
