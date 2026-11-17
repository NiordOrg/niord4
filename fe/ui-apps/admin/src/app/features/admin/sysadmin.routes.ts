import { buildAdminAreaRoutes } from './area-routes';
import { SYSADMIN_AREA } from './section-descriptors';
import { hierarchicalAdminUnsavedGuard } from './shell/hierarchical-admin-unsaved-changes.guard';

export default buildAdminAreaRoutes(SYSADMIN_AREA, {
  series: () => import('./sections/sysadmin/series/series-page').then((m) => m.SeriesPage),
  domains: () => import('./sections/sysadmin/domains/domains-page').then((m) => m.DomainsPage),
  promulgation: () =>
    import('./sections/sysadmin/promulgation/promulgation-types-page').then(
      (m) => m.PromulgationTypesPage,
    ),
  categories: {
    loadComponent: () =>
      import('./sections/sysadmin/categories/categories-page').then((m) => m.CategoriesPage),
    canDeactivate: [hierarchicalAdminUnsavedGuard],
  },
  schedules: () =>
    import('./sections/sysadmin/schedules/firing-schedules-page').then(
      (m) => m.FiringSchedulesPage,
    ),
  'niord-integration': () =>
    import('./sections/sysadmin/niord-integration/niord-integrations-page').then(
      (m) => m.NiordIntegrationsPage,
    ),
  dictionaries: () =>
    import('./sections/sysadmin/dictionaries/dictionaries-page').then((m) => m.DictionariesPage),
  'param-types': () =>
    import('./sections/sysadmin/param-types/param-types-page').then((m) => m.ParamTypesPage),
  'script-resources': () =>
    import('./sections/sysadmin/script-resources/script-resources-page').then(
      (m) => m.ScriptResourcesPage,
    ),
  secom: () => import('./sections/sysadmin/secom/secom-page').then((m) => m.SecomPage),
  mails: () => import('./sections/sysadmin/mails/mails-page').then((m) => m.MailsPage),
  reports: () =>
    import('./sections/sysadmin/reports/print-reports-page').then((m) => m.PrintReportsPage),
  settings: () => import('./sections/sysadmin/settings/settings-page').then((m) => m.SettingsPage),
  batch: {
    loadComponent: () => import('./sections/sysadmin/batch/batch-page').then((m) => m.BatchPage),
    // Optional `:batchName` pre-selects a job type on entry (read once).
    paramSegment: 'batchName',
  },
});
