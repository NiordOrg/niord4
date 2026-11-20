import { Routes } from '@angular/router';

import {
  ADMIN_AREA_ROLES,
  EDITOR_AREA_ROLES,
  EDITOR_VIEW_ROLES,
  SYSADMIN_AREA_ROLES,
} from './core/auth/area-roles';
import { roleGuard } from './core/auth/guards/role.guard';
import { baseTopLevelPaths, guardedCountryRoutes } from './core/country/country-routes';
import { legacyHashRedirectGuard } from './core/guards/legacy-hash-redirect.guard';
import { environment } from '../environments/environment';

// Dev-only routes (excluded from production builds via `environment.production`, which
// is `true` in `environment.production.ts` and `environment.test.ts`, the deployed builds).
// Hosts the geometry-editor sandbox so
// the shared geometry editor can be exercised standalone (its e2e regression net).
const devRoutes: Routes = environment.production
  ? []
  : [
      {
        path: 'dev/geo-editor',
        loadComponent: () =>
          import('./features/editor/views/geometry-editor-sandbox/geometry-editor-sandbox').then(
            (m) => m.GeometryEditorSandbox,
          ),
      },
    ];

export const routes: Routes = [
  ...devRoutes,
  {
    path: '',
    // Lazy-load so the OpenLayers map stack HomePage pulls in is split into its own
    // chunk instead of the initial bundle (consistent with the other feature routes).
    loadComponent: () =>
      import('./features/home/components/home-page/home-page').then((m) => m.HomePage),
    // Forwards the `/#/message/{id}` link that older mails and tweets carry to
    // /messages?messageId=…
    canActivate: [legacyHashRedirectGuard],
    data: { help: { section: 'home' } },
  },
  {
    path: 'messages',
    loadChildren: () => import('./features/messages/messages.routes'),
  },
  {
    // The Firing Area Schedule area. Role-gated like editor/admin; the nav item
    // is additionally gated on the `firingSchedule` domain flag (MenuService).
    path: 'schedule',
    loadChildren: () => import('./features/schedule/schedule.routes'),
    canMatch: [roleGuard],
    data: { rolesRequired: EDITOR_AREA_ROLES },
    title: 'menu.schedule',
  },
  {
    path: 'editor',
    loadChildren: () => import('./features/editor/editor.routes'),
    canMatch: [roleGuard],
    // Shell read access includes the plain `user` role (case officers proof-read/comment);
    // mutating actions stay gated on EDITOR_AREA_ROLES / `isEditor`.
    data: { rolesRequired: EDITOR_VIEW_ROLES },
    title: 'menu.editor',
  },
  {
    path: 'admin',
    loadChildren: () => import('./features/admin/admin.routes'),
    canMatch: [roleGuard],
    data: { rolesRequired: ADMIN_AREA_ROLES },
    title: 'menu.admin',
  },
  {
    path: 'sysadmin',
    loadChildren: () => import('./features/admin/sysadmin.routes'),
    canMatch: [roleGuard],
    data: { rolesRequired: SYSADMIN_AREA_ROLES },
    title: 'menu.sysadmin',
  },
  // The country's own pages, lazily loaded and after every base route; a country route on a base
  // path (or dev) is left out, so none of them shadows a base path, also when a base route's
  // canMatch turns the URL away. A URL that no route matches fetches this chunk once before the
  // redirect below.
  {
    path: '',
    loadChildren: () =>
      import('./country-specific/default/country.routes').then((m) =>
        guardedCountryRoutes(m.default, baseTopLevelPaths(routes)),
      ),
  },
  // Unknown URLs fall through to home. This also covers the /sysadmin/integration
  // deep link, which is deliberately not implemented.
  { path: '**', redirectTo: '' },
];
