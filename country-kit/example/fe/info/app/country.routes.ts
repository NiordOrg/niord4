import type { Routes } from '@angular/router';

/** The example country's own page on the public site: who runs the service, in its words. */
const routes: Routes = [
  {
    path: 'about',
    title: 'country.about.title',
    loadComponent: () => import('./about/about-page.component').then((m) => m.AboutPageComponent),
  },
];

export default routes;
