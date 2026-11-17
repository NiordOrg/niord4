import { LoadChildren, Routes } from '@angular/router';
import { LayoutComponent } from '../shared/components/layout/layout.component';

/**
 * The site's route table. `countryRoutes` loads the country's own pages, which become the last
 * child of the layout: every base address is matched before the router fetches them, and the
 * teaser, which lies outside the layout, is matched before the layout is tried at all.
 */
export function createNiordRoutes(countryRoutes?: LoadChildren): Routes {
  return [
    // Where a first visit lands, and so the route the first-visit bundle gate measures: moving
    // this redirect moves the gate, and `landingRoute` in bundle-baseline.json moves with it.
    { path: '', redirectTo: 'messages/details', pathMatch: 'full' },
    {
      path: 'messages/print',
      title: 'MENU_PRINT',
      loadComponent: () =>
        import('../features/messages/components/message-print-view/message-print-view.component').then(
          (m) => m.MessagePrintViewComponent,
        ),
    },
    {
      path: 'teaser',
      loadComponent: () =>
        import('../features/teaser/teaser.component').then((m) => m.TeaserComponent),
    },
    {
      path: '',
      component: LayoutComponent,
      children: [
        {
          path: 'messages',
          loadChildren: () =>
            import('../features/messages/messages.routes').then((m) => m.messagesRoutes),
        },
        {
          path: 'publications',
          title: 'MENU_DOWNLOADS',
          loadComponent: () =>
            import('../features/publications/publications.component').then(
              (m) => m.PublicationsComponent,
            ),
        },
        // An address no base route matches fetches the country's chunk once before the
        // wildcard below moves it on.
        ...(countryRoutes ? [{ path: '', loadChildren: countryRoutes }] : []),
      ],
    },
    { path: '**', redirectTo: 'messages/details' },
  ];
}
