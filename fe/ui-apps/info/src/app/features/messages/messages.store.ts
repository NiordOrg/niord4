import { computed, inject, Injectable, linkedSignal, signal, Signal } from '@angular/core';
import { HttpClient, httpResource } from '@angular/common/http';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { catchError, forkJoin, map, of } from 'rxjs';

import { AppConfigService } from '../../core/services/app-config.service';
import { LanguageService } from '../../core/services/language.service';
import { MessageService } from '../../core/services/message.service';
import { PreferencesService } from '../../core/services/preferences.service';
import { Area, Message } from '../../core/models/message.model';
import { computeAreaHeadings, filterByAreaIds, sortDescs } from '../../core/models/message.util';
import { settledValue } from '../../shared/utils/settled-value';
import { messageListPdfUrl } from './message-pdf-url';

/** Which of the two main types the filter bar currently asks for. */
export interface MainTypeFilter {
  readonly NW: boolean;
  readonly NM: boolean;
}

/**
 * A filter asks for at least one of the two. The public API reads no main type as both, so a
 * filter with neither would list every message under two chips that read as off; the chip that
 * is left cannot be turned off, and a filter remembered with neither — which the previous site
 * let a reader store — is read as the both it has always shown.
 */
function atLeastOne(types: MainTypeFilter): MainTypeFilter {
  return types.NW || types.NM ? types : { NW: true, NM: true };
}

/**
 * The state of the `/messages` route: the filter the reader set, the areas and messages that
 * follow from it, and the groupings its three views render.
 *
 * It is listed in `MessagesComponent`'s own `providers`, not in the application's and not in the
 * route's: the list is created with that component and destroyed with it, so nothing can read it
 * after the reader has left `/messages`. The three views render inside the outlet that component
 * hosts, so they share one instance and one fetch instead of re-reading on every tab.
 */
@Injectable()
export class MessagesStore {
  private readonly http = inject(HttpClient);
  private readonly api = inject(MessageService);
  private readonly appConfig = inject(AppConfigService);
  private readonly language = inject(LanguageService).language;
  private readonly preferences = inject(PreferencesService);

  // ---- the publication being browsed ------------------------------------------------------

  /**
   * The publication the reader is browsing: `?publicationId=`, the address the Browse link on the
   * publications page opens. Followed on the route rather than read once, so the menu's link to
   * the plain list, which carries no query, brings the current list back on the same page.
   *
   * A publication is a scope of its own — the messages it was published with — so while one is
   * named the read asks for it and for nothing the filter bar chooses: no main type, no root
   * area, no active-now. A reader whose last filter was warnings only would otherwise open a
   * notices publication on an empty page. The bar leaves those chips out while it is set;
   * ticking a sub-area still narrows the rows on screen.
   */
  readonly publicationId: Signal<string | undefined> = toSignal(
    inject(ActivatedRoute).queryParamMap.pipe(
      map((params) => params.get('publicationId') || undefined),
    ),
    { requireSync: true },
  );

  // ---- the filter ------------------------------------------------------------------------

  private readonly selectedMainTypes = signal<MainTypeFilter>(
    atLeastOne(this.preferences.mainTypes()),
  );
  readonly mainTypes: Signal<MainTypeFilter> = this.selectedMainTypes;

  private readonly activeNowOnly = signal(false);
  readonly activeNow: Signal<boolean> = this.activeNowOnly;

  // ---- the root areas ----------------------------------------------------------------------

  /**
   * The country's root areas, each carrying the map framing its build-time spec holds. The specs
   * never change, so this reads once; a fan-out over N of them needs `forkJoin` inside the
   * resource, which is what makes this the one `rxResource` in the application.
   *
   * Each area is read on its own account: one that does not answer — a passing server error, or
   * an area the backend no longer carries — is left out, and the others are offered, rather
   * than the whole site saying the list did not load. Only when none answers has the read failed.
   */
  private readonly areaRootsResource = rxResource({
    params: () => this.appConfig.rootAreaSpecs(),
    stream: ({ params }) =>
      params.length === 0
        ? of<Area[]>([])
        : forkJoin(
            params.map((spec) =>
              this.http.get<Area>(`/rest/public/v1/area/${encodeURIComponent(spec.areaId)}`).pipe(
                map((area): Area | undefined => ({
                  ...area,
                  latitude: spec.latitude,
                  longitude: spec.longitude,
                  zoomLevel: spec.zoomLevel,
                })),
                catchError((error: unknown) => {
                  console.warn(
                    `Root area ${spec.areaId} could not be read; it is not offered.`,
                    error,
                  );
                  return of(undefined);
                }),
              ),
            ),
          ).pipe(
            map((areas) => {
              const read = areas.filter((area): area is Area => area !== undefined);
              if (read.length === 0) throw new Error('None of the root areas could be read.');
              return read;
            }),
          ),
    defaultValue: [] as Area[],
  });

  /** `value()` throws in the error state, so every consumer goes through `hasValue()`. */
  readonly areaRoots = computed<Area[]>(() =>
    this.areaRootsResource.hasValue() ? this.areaRootsResource.value() : [],
  );

  /**
   * The chosen root area: the one the reader last picked if it is still offered, otherwise the
   * remembered one, otherwise the first. `linkedSignal` re-seeds it when the list arrives without
   * discarding a choice made after that.
   */
  private readonly selectedRootArea = linkedSignal<Area[], Area | undefined>({
    source: this.areaRoots,
    computation: (roots, previous) => {
      const kept = previous?.value && roots.find((area) => area.id === previous.value?.id);
      if (kept) return kept;
      // The backend's area key is a number and what a reader's last choice was written as is a
      // string, so the remembered area is matched as text; an MRN is accepted too, because a
      // deployment names its root areas by MRN.
      const saved = this.preferences.rootAreaId();
      return roots.find((area) => String(area.id) === saved || area.mrn === saved) ?? roots[0];
    },
  });

  readonly rootArea: Signal<Area | undefined> = this.selectedRootArea;

  // ---- the message list ---------------------------------------------------------------------

  /**
   * Keyed on the language and on either the publication being browsed or the three things the
   * filter bar's read depends on — root area, main types and active-now — and nothing else. A
   * country build waits for its root area: until one resolves the request function returns
   * `undefined`, the resource stays idle and no request is made. A build with no root areas has
   * none to wait for and reads the whole data set, and a publication needs no root area at all.
   */
  private readonly listResource = httpResource<Message[]>(
    () => {
      const common = {
        // `lang` is the one name the public API reads the language by.
        lang: this.language(),
        // The public API rewrites repository links to the backend's own host unless this is
        // off; the container that serves this site proxies root-relative paths only.
        externalize: 'false',
      };
      const publicationId = this.publicationId();
      if (publicationId) {
        return {
          url: '/rest/public/v1/messages',
          params: { ...common, publication: publicationId },
        };
      }
      const rootArea = this.selectedRootArea();
      if (!rootArea && this.appConfig.rootAreaSpecs().length > 0) return undefined;
      const types = this.selectedMainTypes();
      const mainType = [...(types.NW ? ['NW'] : []), ...(types.NM ? ['NM'] : [])];
      return {
        url: '/rest/public/v1/messages',
        params: {
          ...common,
          ...(this.activeNowOnly() ? { active: 'true' } : {}),
          ...(mainType.length > 0 ? { mainType } : {}),
          ...(rootArea ? { areaId: rootArea.id } : {}),
        },
      };
    },
    { defaultValue: [], parse: (raw) => this.api.parseList(raw) },
  );

  /**
   * What the views render: the rows a reader is looking at stay up until the next read settles,
   * and a failed read clears them — the state the views have a line of their own for.
   */
  private readonly settled = settledValue<Message[]>(this.listResource, []);

  /** Descriptions ordered so the active language comes first — a rendering concern, not a fetch. */
  private readonly ordered = computed<Message[]>(() => {
    const language = this.language();
    return this.settled().map((message) => sortDescs(message, language));
  });

  /**
   * A read that did not arrive. The three views say so in a line of their own: an empty page
   * otherwise reads as "there is nothing to report in this area", and at sea the two have
   * opposite meanings.
   */
  readonly failed = computed(
    () => this.areaRootsResource.status() === 'error' || this.listResource.status() === 'error',
  );

  /**
   * A read is on its way: the root areas, the list, or — in the moment between the two — a list
   * that is waiting for its root area. The views say so, because on a slow link the first visit
   * is seconds of nothing, and an empty page reads as "no warnings in this area".
   */
  readonly loading = computed(() => {
    if (this.failed()) return false;
    if (this.areaRootsResource.isLoading() || this.listResource.isLoading()) return true;
    return this.listResource.status() === 'idle' && this.appConfig.rootAreaSpecs().length > 0;
  });

  // ---- the groupings the three views render --------------------------------------------------

  /** Message id → the area heading printed above it. */
  readonly areaHeadings = computed(() => computeAreaHeadings(this.ordered()));

  /** The sub-areas the filter bar offers for the current result set. */
  readonly subAreas = computed<Area[]>(() =>
    [...this.areaHeadings().values()].filter((area) => area.parent),
  );

  /**
   * Which sub-areas are ticked. Held here rather than on the areas themselves — those come off
   * the wire — and re-seeded to "none" whenever the offered set changes, so a tick never outlives
   * the list it was made on.
   */
  private readonly selectedSubAreaIds = linkedSignal<Area[], ReadonlySet<number>>({
    source: this.subAreas,
    computation: () => new Set<number>(),
  });

  readonly subAreaSelection: Signal<ReadonlySet<number>> = this.selectedSubAreaIds;

  /** What the three views render: the fetched list narrowed by the ticked sub-areas. */
  readonly messages = computed<readonly Message[]>(() =>
    filterByAreaIds(this.ordered(), this.selectedSubAreaIds()),
  );

  /** The navigation list the details dialog steps through. */
  readonly messageIds = computed<string[]>(() => this.messages().map((message) => message.id));

  // ---- what the filter bar calls ---------------------------------------------------------------

  selectRootArea(area: Area): void {
    this.selectedRootArea.set(area);
    this.preferences.setRootAreaId(String(area.id));
  }

  /** A filter with neither main type is refused: see `atLeastOne`. */
  setMainTypes(types: MainTypeFilter): void {
    if (!types.NW && !types.NM) return;
    this.selectedMainTypes.set(types);
    this.preferences.setMainTypes(types);
  }

  toggleActiveNow(): void {
    this.activeNowOnly.update((value) => !value);
  }

  toggleSubArea(area: Area): void {
    this.selectedSubAreaIds.update((selected) => {
      const next = new Set(selected);
      if (!next.delete(area.id)) next.add(area.id);
      return next;
    });
  }

  /**
   * The address of the PDF of this list, rendered by the backend.
   *
   * The areas are the ticked sub-areas when there are any and the root area otherwise, never
   * both: the endpoint matches a message against the union of the areas it is given, so adding
   * the root back would undo every tick. Active-now is not carried — the report endpoint has no
   * such parameter, and neither has the read that fills this list.
   *
   * While a publication is browsed the address names it in place of the main types and the root
   * area, as the read does; the ticked sub-areas and the series narrow it as they do any list.
   *
   * Only ever asked for while `canPrintList` is open: an address built with no series at all is
   * a request for every published series, not for the list the reader is looking at.
   */
  printPdfUrl(): string {
    const publicationId = this.publicationId();
    const types = this.selectedMainTypes();
    const subAreaIds = [...this.selectedSubAreaIds()];
    const rootArea = publicationId ? undefined : this.selectedRootArea();
    return messageListPdfUrl({
      language: this.language(),
      publicationId,
      mainTypes: publicationId ? [] : [...(types.NW ? ['NW'] : []), ...(types.NM ? ['NM'] : [])],
      areaIds:
        subAreaIds.length > 0 ? subAreaIds.map(String) : rootArea ? [String(rootArea.id)] : [],
      messageSeriesIds: this.printedSeriesIds(),
    });
  }

  /**
   * The message series the rows on screen were numbered in. The report endpoint applies no
   * restriction of its own to an anonymous caller, so this is what keeps the sheet to the
   * messages this site publishes.
   */
  private readonly printedSeriesIds = computed<string[]>(() => [
    ...new Set(
      this.messages()
        .map((message) => message.messageSeries?.seriesId)
        .filter((seriesId): seriesId is string => Boolean(seriesId)),
    ),
  ]);

  /**
   * Whether there is a list to print at all.
   *
   * The report endpoint reads an absent `messageSeries` as every published series, so a sheet
   * asked for without one carries notices this site does not list — which is what a list that
   * has not arrived, came back empty or failed would produce. The gate is the read itself: the
   * resource must have **settled on the current filter**, because the rows stay on screen while
   * the next read is in flight and rows from the previous area are not this filter's scope.
   */
  readonly canPrintList = computed<boolean>(() => {
    const status = this.listResource.status();
    return (status === 'resolved' || status === 'local') && this.printedSeriesIds().length > 0;
  });
}
