import { createNiordRoutes } from './create-niord-routes';
import { LayoutComponent } from '../shared/components/layout/layout.component';
import bundleBaseline from '../../../bundle-baseline.json';

describe('createNiordRoutes()', () => {
  const routes = createNiordRoutes();

  it('should return a Routes array', () => {
    expect(Array.isArray(routes)).toBe(true);
    expect(routes.length).toBeGreaterThan(0);
  });

  it('should redirect root to messages/details', () => {
    const root = routes.find((r) => r.path === '' && r.redirectTo);
    expect(root).toBeDefined();
    expect(root!.redirectTo).toBe('messages/details');
    expect(root!.pathMatch).toBe('full');
  });

  it('should have print route outside LayoutComponent', () => {
    const print = routes.find((r) => r.path === 'messages/print');
    expect(print).toBeDefined();
    expect(print!.component).toBeUndefined();
    expect(print!.loadComponent).toBeDefined();
  });

  it('should wrap messages under LayoutComponent', () => {
    const layout = routes.find((r) => r.component === LayoutComponent);
    expect(layout).toBeDefined();
    const messagesChild = layout!.children!.find((c) => c.path === 'messages');
    expect(messagesChild).toBeDefined();
    expect(messagesChild!.loadChildren).toBeDefined();
  });

  it('should wrap publications under LayoutComponent', () => {
    const layout = routes.find((r) => r.component === LayoutComponent);
    const pubChild = layout!.children!.find((c) => c.path === 'publications');
    expect(pubChild).toBeDefined();
    expect(pubChild!.loadComponent).toBeDefined();
  });

  it('should have wildcard redirect to messages/details', () => {
    const wildcard = routes.find((r) => r.path === '**');
    expect(wildcard).toBeDefined();
    expect(wildcard!.redirectTo).toBe('messages/details');
  });

  // The bundle gate measures the route named in the baseline; a redirect that lands elsewhere
  // would leave the real first visit unmeasured while the gate stayed green.
  it('should land the root and every unknown path on the route the bundle gate measures', () => {
    const root = routes.find((r) => r.path === '' && r.redirectTo);
    const wildcard = routes.find((r) => r.path === '**');
    expect(`/${root!.redirectTo}`).toBe(bundleBaseline.landingRoute);
    expect(`/${wildcard!.redirectTo}`).toBe(bundleBaseline.landingRoute);
  });

  // The teaser lies outside the layout; above it, the router never fetches the country's pages
  // on the way to an embedded teaser.
  it('should match the teaser before the layout is tried', () => {
    const teaser = routes.findIndex((r) => r.path === 'teaser');
    const layout = routes.findIndex((r) => r.component === LayoutComponent);
    expect(teaser).toBeGreaterThan(-1);
    expect(routes[teaser].loadComponent).toBeDefined();
    expect(teaser).toBeLessThan(layout);
  });

  it('should leave the layout to the base routes when no country routes are given', () => {
    const layout = routes.find((r) => r.component === LayoutComponent);
    expect(layout!.children!.map((c) => c.path)).toEqual(['messages', 'publications']);
  });

  describe('with country routes', () => {
    const countryRoutes = () => Promise.resolve([]);
    const withCountry = createNiordRoutes(countryRoutes);

    // Last, so every base address is matched first and none of them waits for the country's chunk.
    it('should load them lazily as the last child of the layout', () => {
      const layout = withCountry.find((r) => r.component === LayoutComponent);
      const children = layout!.children!;
      expect(children.map((c) => c.path)).toEqual(['messages', 'publications', '']);
      expect(children.at(-1)).toEqual({ path: '', loadChildren: countryRoutes });
    });

    it('should keep the landing redirect, the print route, the teaser and the wildcard', () => {
      const outside = (table: typeof routes) =>
        table
          .filter((r) => r.component !== LayoutComponent)
          .map((r) => [r.path, r.redirectTo, r.title, Boolean(r.loadComponent)]);
      expect(outside(withCountry)).toEqual(outside(routes));
      expect(withCountry.at(0)).toEqual({
        path: '',
        redirectTo: 'messages/details',
        pathMatch: 'full',
      });
      expect(withCountry.at(-1)).toEqual({ path: '**', redirectTo: 'messages/details' });
    });
  });
});
