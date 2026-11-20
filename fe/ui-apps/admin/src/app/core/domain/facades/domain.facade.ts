import { computed, inject, Injectable, Signal } from '@angular/core';
import { Router, type ActivatedRouteSnapshot } from '@angular/router';

import { AuthFacade } from '../../auth/facades/auth.facade';
import type {
  Domain,
  DomainCapabilities,
  DomainMapDefaults,
  DomainMessageSortField,
  DomainSortOrder,
} from '../models/domain.model';
import { parseMessageSortOrder } from '../models/domain.model';
import { DomainService } from '../services/domain.service';

@Injectable({ providedIn: 'root' })
export class DomainFacade {
  private readonly service = inject(DomainService);
  private readonly auth = inject(AuthFacade);
  private readonly router = inject(Router);

  readonly availableDomains = this.service.availableDomains;
  readonly sessionDomain = this.service.sessionDomain;
  readonly searchDomain = this.service.searchDomain;
  /** The start-up read of the domains failed: the shell says so and offers a reload. */
  readonly bootstrapFailed = computed(() => this.service.bootstrapFailed());

  readonly searchDomainEffective = computed<Domain | undefined>(
    () => this.searchDomain() ?? this.sessionDomain(),
  );

  readonly editableDomains = computed<readonly Domain[]>(() => {
    const available = this.availableDomains();
    // A domain is editable iff the user holds ANY role on that domain's Keycloak
    // client — deliberately no realm-wide fast-path and no restriction to
    // edit-capable roles.
    return available.filter((domain) => this.auth.hasRolesFor(domain.domainId));
  });

  /**
   * Only the public statuses can be found: the backend drops the others unless the caller holds
   * its `user` role in the session domain (a client role there or on the base client) and
   * searches that domain (`MessageSearchRestService`). Signed in alone is not enough, so this
   * reads `hasDomainUserRole`, not `hasRole('user')`.
   */
  readonly searchPublicMessages = computed<boolean>(() => {
    const userRole = this.auth.hasDomainUserRole();
    const sessionId = this.sessionDomain()?.domainId;
    const searchId = this.searchDomain()?.domainId ?? sessionId;
    return !userRole || searchId !== sessionId;
  });

  private readonly sortOrderParsed = computed(() =>
    parseMessageSortOrder(this.sessionDomain()?.messageSortOrder),
  );
  readonly defaultSortBy: Signal<DomainMessageSortField> = computed(
    () => this.sortOrderParsed().sortBy,
  );
  readonly defaultSortOrder: Signal<DomainSortOrder> = computed(
    () => this.sortOrderParsed().sortOrder,
  );

  readonly mapDefaults = computed<DomainMapDefaults>(() => {
    const d = this.sessionDomain();
    const lat = d?.lat;
    const lon = d?.lon;
    return {
      center: lat !== undefined && lon !== undefined ? ([lon, lat] as const) : undefined,
      zoom: d?.zoomLevel,
    };
  });

  readonly capabilities = computed<DomainCapabilities>(() => {
    const d = this.sessionDomain();
    return {
      atons: !!d?.atons,
      templates: !!d?.templates,
      publish: !!d?.publish,
      firingSchedule: !!d?.firingSchedule,
    };
  });

  readonly domainMessageSeriesIds = computed<readonly string[]>(
    () => this.sessionDomain()?.messageSeries?.map((s) => s.seriesId) ?? [],
  );

  private readonly mainTypeSignals = new Map<'NW' | 'NM', Signal<boolean>>();
  private readonly typeSignals = new Map<string, Signal<boolean>>();
  private readonly nwExclusiveSignals = new Map<boolean, Signal<boolean>>();
  private readonly nmExclusiveSignals = new Map<boolean, Signal<boolean>>();
  private readonly canEditSignals = new Map<string, Signal<boolean>>();

  supportsMainType(mainType: 'NW' | 'NM'): Signal<boolean> {
    const cached = this.mainTypeSignals.get(mainType);
    if (cached) {
      return cached;
    }
    const created = computed(() => {
      const domain = this.searchDomainEffective();
      if (!domain) {
        return true;
      }
      const series = domain.messageSeries;
      if (!series || series.length === 0) {
        return false;
      }
      return series.some((s) => s.mainType === mainType);
    });
    this.mainTypeSignals.set(mainType, created);
    return created;
  }

  supportsType(type: string): Signal<boolean> {
    const cached = this.typeSignals.get(type);
    if (cached) {
      return cached;
    }
    const created = computed(() => {
      const domain = this.searchDomainEffective();
      if (!domain) {
        return true;
      }
      const series = domain.messageSeries;
      if (!series || series.length === 0) {
        return false;
      }
      return series.some((s) => !s.types || s.types.length === 0 || s.types.includes(type));
    });
    this.typeSignals.set(type, created);
    return created;
  }

  supportsNw(exclusively = false): Signal<boolean> {
    const cached = this.nwExclusiveSignals.get(exclusively);
    if (cached) {
      return cached;
    }
    const created = computed(() => {
      const supportsNw = this.supportsMainType('NW')();
      if (!supportsNw) {
        return false;
      }
      if (!exclusively) {
        return true;
      }
      return !this.supportsMainType('NM')();
    });
    this.nwExclusiveSignals.set(exclusively, created);
    return created;
  }

  supportsNm(exclusively = false): Signal<boolean> {
    const cached = this.nmExclusiveSignals.get(exclusively);
    if (cached) {
      return cached;
    }
    const created = computed(() => {
      const supportsNm = this.supportsMainType('NM')();
      if (!supportsNm) {
        return false;
      }
      if (!exclusively) {
        return true;
      }
      return !this.supportsMainType('NW')();
    });
    this.nmExclusiveSignals.set(exclusively, created);
    return created;
  }

  canEditMessageSeries(seriesId: string): Signal<boolean> {
    const cached = this.canEditSignals.get(seriesId);
    if (cached) {
      return cached;
    }
    const created = computed(
      () => !!this.sessionDomain()?.messageSeries?.some((s) => s.seriesId === seriesId),
    );
    this.canEditSignals.set(seriesId, created);
    return created;
  }

  /**
   * Take a desk into the session and reload the application under it.
   *
   * The reload is deliberate: it forces a fresh bootstrap, so every domain-scoped
   * piece of state (filter slices, selection, paged results, per-domain
   * catalogues) starts clean under the new desk.
   *
   * WHERE IT LANDS IS THE ROUTE'S OWN ANSWER. Most addresses mean the same thing
   * at either desk — a list, a search, a form — so the default is the address the
   * reader is on, QUERY STRING INCLUDED: a screen that carries the reader's place
   * in the URL is asking to come back up where they were, and dropping half of
   * that address lands them somewhere they did not choose. What the reload exists
   * to clear lives in memory, never in the address.
   *
   * A page ADDRESSED INSIDE ONE DESK is the exception. Its address names a record
   * that only the owning desk may show, so re-mounting it under the desk just
   * chosen puts the reader on a page whose only possible answer is a refusal —
   * the desk change, which they asked for, reads as a page that broke. Such a
   * route declares where a desk change sends them instead:
   *
   *     data: { onDeskChange: '/admin/publications' }
   *
   * read off the deepest activated route, a child's declaration winning over its
   * parent's. Nothing here knows which pages those are, or why.
   *
   * `keepAddress` overrides any declaration, for the one caller that means the
   * address it is on: a refusal offering "switch to the owning desk" is switching
   * TO the desk that can finally render this very page.
   */
  selectSessionDomain(domainId: string | undefined, options?: { keepAddress?: boolean }): void {
    this.service.setSessionDomain(this.service.resolveById(domainId));
    const declared = options?.keepAddress ? undefined : this.deskChangeTarget();
    this.navigateTo(declared ?? window.location.pathname + window.location.search);
  }

  /** The `onDeskChange` address declared by the deepest route that declares one. */
  private deskChangeTarget(): string | undefined {
    let route: ActivatedRouteSnapshot | null = this.router.routerState.snapshot.root;
    let target: string | undefined;
    while (route) {
      const declared: unknown = route.data?.['onDeskChange'];
      if (typeof declared === 'string' && declared.length > 0) {
        target = declared;
      }
      route = route.firstChild;
    }
    return target;
  }

  /**
   * The window-location boundary, so a spec can watch where the reload goes.
   *
   * Overridden in a subclass by the spec that asserts the address survives the
   * switch — assigning to `location.href` in a test navigates the runner.
   */
  protected navigateTo(url: string): void {
    window.location.href = url;
  }

  setSearchDomain(domain: Domain | undefined): void {
    this.service.setSearchDomain(domain);
  }

  resolveById(domainId: string | undefined): Domain | undefined {
    return this.service.resolveById(domainId);
  }
}
