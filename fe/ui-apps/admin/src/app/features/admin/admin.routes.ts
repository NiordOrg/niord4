import { buildAdminAreaRoutes } from './area-routes';
import { ADMIN_AREA } from './section-descriptors';
import { hierarchicalAdminUnsavedGuard } from './shell/hierarchical-admin-unsaved-changes.guard';

/**
 * Where a desk change leaves a publication page that is addressed inside a desk.
 *
 * A series, an issue or a one-off is one desk's record, so its address means
 * nothing at the desk just chosen; the section's front page does. The refusal
 * panel stays for arrivals — a link followed from elsewhere. See
 * `DomainFacade.selectSessionDomain` for the contract this data key belongs to.
 */
const PUBLICATIONS_FRONT_PAGE = { onDeskChange: '/admin/publications' };

export default buildAdminAreaRoutes(ADMIN_AREA, {
  areas: {
    loadComponent: () => import('./sections/admin/areas/areas-page').then((m) => m.AreasPage),
    canDeactivate: [hierarchicalAdminUnsavedGuard],
  },
  charts: () => import('./sections/admin/charts/charts-page').then((m) => m.ChartsPage),
  sources: () => import('./sections/admin/sources/sources-page').then((m) => m.SourcesPage),
  users: () =>
    import('./sections/admin/users/users-contacts-page').then((m) => m.UsersContactsPage),
  publications: {
    loadComponent: () =>
      import('./sections/admin/publications/publications-page').then((m) => m.PublicationsPage),
    paramSegment: 'seriesId',
    // The series page, and the issue page that opens inside it, are one desk's
    // record; the section's own route is the list, which every desk can read.
    paramData: PUBLICATIONS_FRONT_PAGE,
    // A one-off is not a series with issues, so it does not open in the series
    // screen: it has a document, a public listing and a history, and that is a
    // page of its own under the same section.
    subRoutes: [
      {
        path: 'one-off/:seriesId',
        loadComponent: () =>
          import('./sections/admin/publications/one-off-detail').then((m) => m.OneOffDetail),
        data: PUBLICATIONS_FRONT_PAGE,
      },
    ],
  },
  'publication-categories': () =>
    import('./sections/admin/publication-categories/publication-categories-page').then(
      (m) => m.PublicationCategoriesPage,
    ),
  'mailing-lists': () =>
    import('./sections/admin/mailing-lists/mailing-lists-page').then((m) => m.MailingListsPage),
});
