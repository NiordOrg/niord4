import type { Routes } from '@angular/router';

/**
 * The country's own pages. They are loaded lazily, inside the site's layout and after every base
 * route, so no base address waits for them and none is shadowed by them. Every one is public, as
 * the whole site is: nobody signs in here. The base product has none.
 */
const routes: Routes = [];

export default routes;
