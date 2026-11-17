import { Injector, inject, type ProviderToken, runInInjectionContext } from '@angular/core';
import { CanDeactivateFn, GuardResult, MaybeAsync, Routes } from '@angular/router';
import { firstValueFrom, isObservable } from 'rxjs';

import { drawerUnsavedChangesGuard } from '../../shared/components/drawer/drawer-unsaved-changes.guard';
import { AdminOverviewPage } from './overview/admin-overview-page';
import { AdminAreaDescriptor } from './section-descriptors';
import { AdminAreaShell } from './shell/admin-area-shell';
import { AdminSectionStub } from './shell/admin-section-stub';

type LoadComponent = Routes[number]['loadComponent'];

/**
 * A page under a section that is NOT the section's own component.
 *
 * `path` is appended to the section path (`'one-off/:seriesId'` under
 * `publications` gives `publications/one-off/:seriesId`), and the route carries
 * the section's `data`, so the rail stays highlighted and the help anchor still
 * resolves. Distinct from `paramSegment`, which points a trailing param at the
 * SAME component; this is for a sibling screen that is its own component but
 * still lives inside the section.
 */
export interface AdminSectionSubRoute {
  readonly path: string;
  readonly loadComponent: LoadComponent;
  /** Route data merged over the section's own — see {@link AdminSectionOverride.paramData}. */
  readonly data?: Readonly<Record<string, unknown>>;
}

/**
 * A section override: either a bare lazy-component loader, or that loader plus
 * an optional `paramSegment`. When `paramSegment` is set, an additional child
 * route `${path}/:${paramSegment}` resolves to the SAME component, so a deep
 * link with the trailing param pre-selects in-page (read once by the component).
 * The rail `routerLinkActive` keeps matching any `${path}/*`.
 */
export interface AdminSectionOverride {
  readonly loadComponent: LoadComponent;
  readonly paramSegment?: string;
  /**
   * Route data for the trailing-param child ALONE, merged over the section's own.
   *
   * The section route and its param child render the same component but are not
   * the same address — one is the section, the other is one record inside it — so
   * anything that is true of the record page only (`onDeskChange`, say) is
   * declared here rather than on the section.
   */
  readonly paramData?: Readonly<Record<string, unknown>>;
  /** Extra pages under the section, each its own component. See {@link AdminSectionSubRoute}. */
  readonly subRoutes?: readonly AdminSectionSubRoute[];
  /**
   * Route CanDeactivate guard(s) — e.g. an unsaved-changes prompt for a section with an inline editor.
   * They run after `drawerUnsavedChangesGuard`, which every route of every section carries anyway,
   * and in turn: each is called only when the guard before it allowed the navigation, so no two
   * discard dialogs are asked for at once.
   */
  readonly canDeactivate?: Routes[number]['canDeactivate'];
}

type SectionOverride = LoadComponent | AdminSectionOverride;

function normalize(override: SectionOverride | undefined): AdminSectionOverride | undefined {
  if (!override) {
    return undefined;
  }
  return typeof override === 'function' ? { loadComponent: override } : override;
}

type LeaveGuards = NonNullable<Routes[number]['canDeactivate']>;
type LeaveGuardArgs = Parameters<CanDeactivateFn<unknown>>;

/**
 * The leave guards of a section route: `drawerUnsavedChangesGuard`, then the
 * override's own guards, ONE AFTER THE OTHER.
 *
 * Not as a plain array: the router calls every guard of an array at once, and
 * its order decides only whose answer counts first. Two guards that both ask
 * would both open the discard dialog, which has one slot -- the second replaces
 * the first, whose answer never comes, and the navigation hangs with no dialog
 * on screen. So, when there are own guards, one guard runs them in turn: the
 * next is called only when the one before answered `true`; any other answer
 * (No, a redirect) is the route's answer.
 */
function drawerGuardThen(own: LeaveGuards | undefined): LeaveGuards {
  if (!own?.length) {
    return [drawerUnsavedChangesGuard];
  }
  const inTurn: CanDeactivateFn<unknown> = async (...args) => {
    // The route's injector, read before the first await: each guard runs in it,
    // as the router would run it.
    const injector = inject(Injector);
    for (const guard of [drawerUnsavedChangesGuard, ...own]) {
      const answer = await settle(
        runInInjectionContext(injector, () => callGuard(guard, injector, args)),
      );
      if (answer !== true) {
        return answer;
      }
    }
    return true;
  };
  return [inTurn];
}

/** Calls a guard the way the router does: a function, or a token whose instance has `canDeactivate`. */
function callGuard(
  guard: LeaveGuards[number],
  injector: Injector,
  args: LeaveGuardArgs,
): MaybeAsync<GuardResult> {
  const notFound = Symbol('not provided');
  const provided = injector.get<unknown>(guard as ProviderToken<unknown>, notFound);
  const target = provided === notFound ? guard : provided;
  if (hasCanDeactivate(target)) {
    return target.canDeactivate(...args);
  }
  return (target as CanDeactivateFn<unknown>)(...args);
}

function hasCanDeactivate(target: unknown): target is { canDeactivate: CanDeactivateFn<unknown> } {
  return (
    typeof target === 'object' &&
    target !== null &&
    typeof (target as { canDeactivate?: unknown }).canDeactivate === 'function'
  );
}

/** A guard's answer, once it has one (the first value of an observable, as the router takes it). */
function settle(result: MaybeAsync<GuardResult>): Promise<GuardResult> {
  return isObservable(result) ? firstValueFrom(result) : Promise.resolve(result);
}

/**
 * Projects an area descriptor into its route tree: shell + `'' → overview`
 * redirect + the overview page + one child route per section. Sections without
 * a shipped component register the shared coming-soon stub at their final URL,
 * so deep links and rail navigation are stable from day one.
 *
 * Every section's route is registered unconditionally — a deep link always
 * resolves. Runtime visibility (the rail entry + overview card) is handled
 * separately by the section-gate service, which reads each section's
 * `settingGate` key from the web settings (`/settings/web`); it affects only the
 * rail entry and overview card, never whether the route resolves.
 *
 * Later batches replace a section's stub via `loadComponent` overrides keyed by
 * the section path. An override may additionally carry a `paramSegment` to
 * register an optional trailing param child on the same component.
 */
export function buildAdminAreaRoutes(
  area: AdminAreaDescriptor,
  overrides: Readonly<Record<string, SectionOverride>> = {},
): Routes {
  return [
    {
      path: '',
      component: AdminAreaShell,
      data: { area },
      children: [
        { path: '', pathMatch: 'full', redirectTo: 'overview' },
        {
          path: 'overview',
          component: AdminOverviewPage,
          data: { help: { section: area.overviewHelpSection } },
        },
        ...area.sections.flatMap((section) => {
          const override = normalize(overrides[section.path]);
          const data = {
            sectionLabelKey: section.labelKey,
            ...(section.helpSection ? { help: { section: section.helpSection } } : {}),
          };
          // A changed drawer asks before ANY route of the section is left: the
          // rail, browser Back or a link out. On each section route, not on the
          // shell -- moving between sections deactivates only these. A page's
          // own guard (an inline editor's) runs after it, only once it said yes:
          // see drawerGuardThen.
          const sectionGuards = { canDeactivate: drawerGuardThen(override?.canDeactivate) };
          const base = {
            path: section.path,
            data,
            ...sectionGuards,
            ...(override
              ? { loadComponent: override.loadComponent }
              : { component: AdminSectionStub }),
          };
          // Sub-pages first, and that ORDER is the point: a sub-route's literal
          // first segment would otherwise be swallowed by the param child below
          // it, which matches any single segment. They carry the section's data
          // so the rail entry and the help anchor still resolve, but not its own
          // guard -- that belongs to the section's own component. The drawer
          // guard reads no component, so it holds on a sub-page as well.
          const subRoutes = (override?.subRoutes ?? []).map((sub) => ({
            path: `${section.path}/${sub.path}`,
            data: { ...data, ...sub.data },
            canDeactivate: [drawerUnsavedChangesGuard],
            loadComponent: sub.loadComponent,
          }));
          // An optional trailing-param child resolves to the same component, so
          // both `/area/section` and `/area/section/:param` render the page.
          if (override?.paramSegment) {
            return [
              base,
              ...subRoutes,
              {
                path: `${section.path}/:${override.paramSegment}`,
                data: { ...data, ...override.paramData },
                ...sectionGuards,
                loadComponent: override.loadComponent,
              },
            ];
          }
          return [base, ...subRoutes];
        }),
      ],
    },
  ];
}
