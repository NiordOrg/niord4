import type { Routes } from '@angular/router';

/**
 * Firing Area Schedule route tree. Role-gating (editor/admin/sysadmin) lives on
 * the parent `schedule` entry in `app.routes.ts` (canMatch + rolesRequired); the
 * `firingSchedule` domain gate is handled in the view + the nav-item visibility.
 */
const routes: Routes = [
  {
    path: '',
    loadComponent: () => import('./schedule-view').then((m) => m.ScheduleView),
    data: { help: { section: 'firing-exercise-schedule' } },
  },
];

export default routes;
