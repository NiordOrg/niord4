import type { Routes } from '@angular/router';
import { EDITOR_AREA_ROLES, roleGuard } from '@niord/admin/api';

/** The example country's own page, the harbour notes, for the editor area's roles. */
const routes: Routes = [
  {
    path: 'harbour',
    canMatch: [roleGuard],
    data: { rolesRequired: EDITOR_AREA_ROLES },
    title: 'country.harbour.title',
    loadComponent: () => import('./harbour/harbour-page').then((m) => m.HarbourPage),
  },
];

export default routes;
