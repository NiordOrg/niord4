import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import {
  type ActivatedRouteSnapshot,
  type CanDeactivateFn,
  provideRouter,
  Router,
  type RouterStateSnapshot,
} from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { describe, expect, it, vi } from 'vitest';

import { Drawer } from '../../shared/components/drawer/drawer';
import { DrawerGateService } from '../../shared/components/drawer/drawer-gate.service';
import { drawerUnsavedChangesGuard } from '../../shared/components/drawer/drawer-unsaved-changes.guard';
import { provideNiordIcons } from '../../shared/icons/niord-icons';
import { type ConfirmService } from '../../shared/services/confirm.service';
import { DiscardConfirmService } from '../../shared/services/discard-confirm.service';
import { provideTestTransloco } from '../../shared/testing/transloco-testing';
import { buildAdminAreaRoutes } from './area-routes';
import { ADMIN_AREA, SYSADMIN_AREA } from './section-descriptors';
import { AdminSectionStub } from './shell/admin-section-stub';
import { hierarchicalAdminUnsavedGuard } from './shell/hierarchical-admin-unsaved-changes.guard';

/** A stand-in discard dialog that answers `confirmResult`. */
function provideConfirm(confirmResult: boolean) {
  const confirmDiscard = vi.fn(() => Promise.resolve(confirmResult));
  TestBed.configureTestingModule({
    providers: [{ provide: DiscardConfirmService, useValue: { confirmDiscard } }],
  });
  return { confirmDiscard };
}

/** Runs one leave guard of a route in the test injector, as the router would. */
async function runLeaveGuard(guard: unknown, component: unknown): Promise<unknown> {
  return TestBed.runInInjectionContext(() =>
    (guard as CanDeactivateFn<unknown>)(
      component,
      {} as ActivatedRouteSnapshot,
      {} as RouterStateSnapshot,
      {} as RouterStateSnapshot,
    ),
  );
}

/** A section page with its own unsaved state and an open, changed drawer. */
@Component({
  imports: [Drawer],
  template: `<app-drawer [visible]="true" [dirty]="true" header="x"><p>x</p></app-drawer>`,
})
class BothChangedPage {
  hasUnsavedChanges(): boolean {
    return true;
  }
}

@Component({ template: '' })
class NextPage {}

/**
 * The page under the guards `buildAdminAreaRoutes` gives a section with the
 * hierarchical pages' own guard, and the real discard dialog's one slot.
 */
async function setupBothChanged() {
  const [adminRoot] = buildAdminAreaRoutes(ADMIN_AREA, {
    areas: {
      loadComponent: () => Promise.resolve(BothChangedPage),
      canDeactivate: [hierarchicalAdminUnsavedGuard],
    },
  });
  const canDeactivate = adminRoot.children?.find((c) => c.path === 'areas')?.canDeactivate;
  TestBed.configureTestingModule({
    providers: [
      provideTestTransloco(),
      provideNiordIcons(),
      provideRouter([
        { path: 'page', component: BothChangedPage, canDeactivate },
        { path: 'next', component: NextPage },
      ]),
    ],
  });
  const harness = await RouterTestingHarness.create('/page');
  await harness.fixture.whenStable();
  expect(TestBed.inject(DrawerGateService).anyDirty()).toBe(true);
  const bus = TestBed.inject(DiscardConfirmService).bus;
  const asked = vi.spyOn(bus, 'confirmDestructive');
  return { router: TestBed.inject(Router), bus, asked };
}

/** Answers the dialog on screen, as the confirm view does. */
function answer(bus: ConfirmService, yes: boolean): void {
  const shown = bus.active();
  expect(shown).toBeDefined();
  bus.dismiss();
  if (yes) {
    shown?.accept();
  } else {
    shown?.reject?.();
  }
}

/** Lets a few renders and timers pass while a navigation may still wait on a dialog. */
async function settleFrames(): Promise<void> {
  for (let i = 0; i < 5; i++) {
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

describe('buildAdminAreaRoutes', () => {
  const [root] = buildAdminAreaRoutes(SYSADMIN_AREA);
  const children = root.children ?? [];

  it('redirects the bare area URL to the overview', () => {
    expect(children[0]).toMatchObject({ path: '', pathMatch: 'full', redirectTo: 'overview' });
  });

  it('the overview route carries the area help section (manual root)', () => {
    const overview = children.find((c) => c.path === 'overview');
    expect(overview?.data?.['help']).toEqual({ section: SYSADMIN_AREA.overviewHelpSection });
  });

  it('registers every section at its final URL as a coming-soon stub by default', () => {
    for (const section of children.filter((c) => c.path && c.path !== 'overview')) {
      expect(section.component).toBe(AdminSectionStub);
      expect(section.data?.['sectionLabelKey']).toBeTruthy();
    }
  });

  it('every registered section route carries help data, except SECOM, which has no manual anchor', () => {
    const withoutHelp = children
      .filter((c) => c.path && c.path !== 'overview' && !c.data?.['help'])
      .map((c) => c.path);
    expect(withoutHelp).toEqual(['secom']);
  });

  it('an override replaces the stub with a lazy component while keeping the route data', () => {
    const load = () => Promise.resolve(class {});
    const [adminRoot] = buildAdminAreaRoutes(ADMIN_AREA, { sources: load });
    const sources = adminRoot.children?.find((c) => c.path === 'sources');
    expect(sources?.loadComponent).toBe(load);
    expect(sources?.component).toBeUndefined();
    expect(sources?.data?.['sectionLabelKey']).toBe('admin.sections.sources.label');
  });

  it('an object override with a paramSegment registers BOTH the section and a param child on the same component', () => {
    const load = () => Promise.resolve(class {});
    const [sysRoot] = buildAdminAreaRoutes(SYSADMIN_AREA, {
      batch: { loadComponent: load, paramSegment: 'batchName' },
    });
    const kids = sysRoot.children ?? [];
    const base = kids.find((c) => c.path === 'batch');
    const paramChild = kids.find((c) => c.path === 'batch/:batchName');
    expect(base?.loadComponent).toBe(load);
    expect(paramChild?.loadComponent).toBe(load);
    // Both carry the section label data so the rail/help stay consistent.
    expect(base?.data?.['sectionLabelKey']).toBe('admin.sections.batch.label');
    expect(paramChild?.data?.['sectionLabelKey']).toBe('admin.sections.batch.label');
  });

  /**
   * A sub-route is a sibling PAGE inside a section, not the section's own
   * component under a param.
   *
   * ORDER IS THE POINT. `section/:param` matches any single segment, so a
   * sub-route whose first segment is a literal has to be registered above it —
   * otherwise `publications/one-off/x` never reaches its own component, and the
   * only symptom is a page that renders the wrong screen.
   */
  it('registers a sub-route above the param child, carrying the section data', () => {
    const load = () => Promise.resolve(class {});
    const sub = () => Promise.resolve(class {});
    const [adminRoot] = buildAdminAreaRoutes(ADMIN_AREA, {
      publications: {
        loadComponent: load,
        paramSegment: 'seriesId',
        subRoutes: [{ path: 'one-off/:seriesId', loadComponent: sub }],
      },
    });
    const kids = adminRoot.children ?? [];
    const paths = kids.map((c) => c.path);

    const subRoute = kids.find((c) => c.path === 'publications/one-off/:seriesId');
    expect(subRoute?.loadComponent).toBe(sub);
    expect(subRoute?.data?.['sectionLabelKey']).toBe('admin.sections.publications.label');
    expect(subRoute?.data?.['help']).toEqual({ section: 'publications' });

    expect(paths.indexOf('publications/one-off/:seriesId')).toBeLessThan(
      paths.indexOf('publications/:seriesId'),
    );
  });

  /** The publications section registers the one-off page for real. */
  it('registers the one-off detail page under publications', async () => {
    const routes = (await import('./admin.routes')).default;
    const kids = routes[0].children ?? [];
    const paths = kids.map((c) => c.path);

    expect(paths).toContain('publications/one-off/:seriesId');
    expect(paths.indexOf('publications/one-off/:seriesId')).toBeLessThan(
      paths.indexOf('publications/:seriesId'),
    );
  });

  it('puts paramData on the param child alone, merged over the section data', () => {
    const load = () => Promise.resolve(class {});
    const [sysRoot] = buildAdminAreaRoutes(SYSADMIN_AREA, {
      batch: { loadComponent: load, paramSegment: 'batchName', paramData: { onDeskChange: '/x' } },
    });
    const kids = sysRoot.children ?? [];

    expect(kids.find((c) => c.path === 'batch/:batchName')?.data).toMatchObject({
      onDeskChange: '/x',
      sectionLabelKey: 'admin.sections.batch.label',
    });
    expect(kids.find((c) => c.path === 'batch')?.data?.['onDeskChange']).toBeUndefined();
  });

  it('merges a sub-route own data over the section data', () => {
    const load = () => Promise.resolve(class {});
    const sub = () => Promise.resolve(class {});
    const [adminRoot] = buildAdminAreaRoutes(ADMIN_AREA, {
      publications: {
        loadComponent: load,
        subRoutes: [
          { path: 'one-off/:seriesId', loadComponent: sub, data: { onDeskChange: '/x' } },
        ],
      },
    });
    const subRoute = (adminRoot.children ?? []).find(
      (c) => c.path === 'publications/one-off/:seriesId',
    );

    expect(subRoute?.data).toMatchObject({
      onDeskChange: '/x',
      sectionLabelKey: 'admin.sections.publications.label',
    });
  });

  /**
   * A desk change leaves a publication page that is addressed inside one desk.
   *
   * The three detail screens live on two addresses — the series page hosts the
   * issue page under a query param — and both name the section's front page.
   * The section's own route is the list, which every desk can read, so it says
   * nothing and the reader stays on it.
   */
  it('declares the desk-change target on the publication detail addresses only', async () => {
    const routes = (await import('./admin.routes')).default;
    const kids = routes[0].children ?? [];
    const target = (path: string) =>
      kids.find((c) => c.path === path)?.data?.['onDeskChange'] as string | undefined;

    expect(target('publications/:seriesId')).toBe('/admin/publications');
    expect(target('publications/one-off/:seriesId')).toBe('/admin/publications');
    expect(target('publications')).toBeUndefined();
  });

  /**
   * A changed drawer on an admin or sysadmin page asks before the page is left.
   *
   * The drawer's own close gate covers Cancel, X and Escape; a route change is
   * the one way out it cannot see. The shell route would not do: moving from one
   * section to the next deactivates only the section routes, so each of them
   * carries the guard itself -- the section, its sub-pages and its param child.
   */
  describe('the drawer guard', () => {
    const load = () => Promise.resolve(class {});
    const sub = () => Promise.resolve(class {});

    it('is on every section route of both areas, the coming-soon stubs included', () => {
      for (const area of [ADMIN_AREA, SYSADMIN_AREA]) {
        const [areaRoot] = buildAdminAreaRoutes(area);
        const sections = (areaRoot.children ?? []).filter((c) => c.path && c.path !== 'overview');
        expect(sections.length).toBe(area.sections.length);
        for (const section of sections) {
          expect(section.canDeactivate).toEqual([drawerUnsavedChangesGuard]);
        }
      }
    });

    it('is on the sub-pages and the param child too, the section own guard combined behind it', async () => {
      const ownGuard = vi.fn(() => false);
      const [adminRoot] = buildAdminAreaRoutes(ADMIN_AREA, {
        publications: {
          loadComponent: load,
          paramSegment: 'seriesId',
          subRoutes: [{ path: 'one-off/:seriesId', loadComponent: sub }],
          canDeactivate: [ownGuard],
        },
      });
      const kids = adminRoot.children ?? [];
      const guards = (path: string) => kids.find((c) => c.path === path)?.canDeactivate ?? [];

      // One combined guard on the section and its param child, not the array.
      expect(guards('publications')).toHaveLength(1);
      expect(guards('publications')[0]).not.toBe(drawerUnsavedChangesGuard);
      expect(guards('publications/:seriesId')).toEqual(guards('publications'));
      // A sub-page is its own component, so the section's own guard is not its.
      expect(guards('publications/one-off/:seriesId')).toEqual([drawerUnsavedChangesGuard]);

      // With no changed drawer, the combined guard gives the own guard's answer.
      const { confirmDiscard } = provideConfirm(true);
      await expect(runLeaveGuard(guards('publications')[0], {})).resolves.toBe(false);
      expect(ownGuard).toHaveBeenCalledTimes(1);
      expect(confirmDiscard).not.toHaveBeenCalled();
    });

    it('keeps the hierarchical pages own guard behind it', async () => {
      const admin = (await import('./admin.routes')).default[0].children ?? [];
      const sysadmin = (await import('./sysadmin.routes')).default[0].children ?? [];
      const areas = admin.find((c) => c.path === 'areas')?.canDeactivate ?? [];
      const categories = sysadmin.find((c) => c.path === 'categories')?.canDeactivate ?? [];

      expect(areas).toHaveLength(1);
      expect(categories).toHaveLength(1);
      expect(admin.find((c) => c.path === 'publications/one-off/:seriesId')?.canDeactivate).toEqual(
        [drawerUnsavedChangesGuard],
      );

      const { confirmDiscard } = provideConfirm(false);
      const unsaved = { hasUnsavedChanges: () => true };
      const saved = { hasUnsavedChanges: () => false };
      await expect(runLeaveGuard(areas[0], unsaved)).resolves.toBe(false);
      await expect(runLeaveGuard(categories[0], unsaved)).resolves.toBe(false);
      expect(confirmDiscard).toHaveBeenCalledTimes(2);
      await expect(runLeaveGuard(areas[0], saved)).resolves.toBe(true);
      expect(confirmDiscard).toHaveBeenCalledTimes(2);
    });
  });

  /**
   * A section page with its own guard AND a changed drawer. The router calls
   * every guard of a route's array at once, and the discard dialog has one slot:
   * side by side, the second ask replaced the first, whose answer never came, and
   * Yes on the one dialog shown left the navigation hanging on the page. The
   * drawer guard asks first, and the page's own guard only after its Yes.
   */
  describe('with a changed drawer and the page own unsaved state', () => {
    it('asks for the drawer, then for the page, and Yes to both lands on the next page', async () => {
      const { router, bus, asked } = await setupBothChanged();

      void router.navigate(['/next']);
      await vi.waitFor(() => expect(asked).toHaveBeenCalledTimes(1));
      await settleFrames();
      expect(asked).toHaveBeenCalledTimes(1);
      answer(bus, true);

      await vi.waitFor(() => expect(asked).toHaveBeenCalledTimes(2));
      answer(bus, true);

      await vi.waitFor(() => expect(router.url).toBe('/next'));
    });

    it('stays on the page without a second dialog on No to the drawer', async () => {
      const { router, bus, asked } = await setupBothChanged();

      void router.navigate(['/next']);
      await vi.waitFor(() => expect(asked).toHaveBeenCalledTimes(1));
      answer(bus, false);
      await settleFrames();

      expect(asked).toHaveBeenCalledTimes(1);
      expect(bus.active()).toBeUndefined();
      expect(router.url).toBe('/page');
    });

    it('stays on the page on Yes to the drawer and No to the page', async () => {
      const { router, bus, asked } = await setupBothChanged();

      void router.navigate(['/next']);
      await vi.waitFor(() => expect(asked).toHaveBeenCalledTimes(1));
      answer(bus, true);
      await vi.waitFor(() => expect(asked).toHaveBeenCalledTimes(2));
      answer(bus, false);
      await settleFrames();

      expect(router.url).toBe('/page');
    });
  });

  describe('visibility-gated niord-integration section', () => {
    it('registers the route unconditionally (the gate hides only the menu, not the route)', () => {
      const route = children.find((c) => c.path === 'niord-integration');
      expect(route).toBeDefined();
      expect(route?.data?.['help']).toEqual({ section: 'integration' });
    });

    it('carries the gated section in data.area (the rail/overview gate it at runtime)', () => {
      const sectionPaths = ((root.data?.['area'] as typeof SYSADMIN_AREA).sections ?? []).map(
        (s) => s.path,
      );
      expect(sectionPaths).toContain('niord-integration');
    });
  });
});
