import { DestroyRef, effect, inject, Injectable, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { debounceTime, distinctUntilChanged, skip } from 'rxjs';

import { DomainFacade } from '../../../core/domain/facades/domain.facade';
import { MESSAGE_ID_QUERY_PARAM } from './message-details-dialog.service';
import { MessagesFilterFacade } from './messages-filter.facade';

const URL_WRITE_DEBOUNCE_MS = 200;

// Query-param keys that share the URL with filter state but do NOT belong to
// the filter facade. The URL ↔ facade sync skips these on both directions —
// inbound so opening the dialog doesn't trigger a single-message refetch that
// wipes the user's scroll position, and outbound so a filter change doesn't
// drop the open dialog by clearing the `messageId` param along with the
// filter set.
const NON_FILTER_QUERY_PARAMS = [MESSAGE_ID_QUERY_PARAM] as const;

// The reference filter's backend API key is `messageId`, which collides with
// the dialog's deep-link key (also `messageId`). On the URL we rename it to
// `refMessageId` so typing in the reference filter does NOT open the dialog,
// while the backend request (built from `facade.requestParams`) keeps the
// `messageId` key the search endpoint expects.
const REFERENCE_FILTER_API_KEY = 'messageId';
const REFERENCE_FILTER_URL_KEY = 'refMessageId';

@Injectable({ providedIn: 'root' })
export class MessagesFilterUrlSync {
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly facade = inject(MessagesFilterFacade);
  private readonly domain = inject(DomainFacade);
  private readonly destroyRef = inject(DestroyRef);

  private readonly pendingDomainId = signal<string | undefined>(undefined);
  // Snapshot of the query string that matches the current URL. Facade emissions
  // equal to it are echoes of an inbound URL apply and must not be re-navigated.
  private lastAppliedQuery: string | undefined;

  constructor() {
    this.subscribeUrlToFacade();
    this.subscribeFacadeToUrl();
    this.deferDomainResolution();
  }

  private subscribeUrlToFacade(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((map) => {
      const search = new URLSearchParams();
      for (const key of map.keys) {
        if ((NON_FILTER_QUERY_PARAMS as readonly string[]).includes(key)) continue;
        const apiKey = key === REFERENCE_FILTER_URL_KEY ? REFERENCE_FILTER_API_KEY : key;
        for (const value of map.getAll(key)) {
          search.append(apiKey, value);
        }
      }
      // Skip the echo of our own outbound navigation. The URL only carries
      // data-bearing filters, so re-applying it would disable any filter that
      // is enabled-but-empty in the UI (e.g. just opened from the dropdown,
      // not yet filled in) and make its panel vanish.
      if (this.canonicalize(search) === this.lastAppliedQuery) {
        return;
      }
      const result = this.facade.applyQueryParams(search);
      this.pendingDomainId.set(result.pendingDomainId);
      this.lastAppliedQuery = untracked(this.facade.requestParams);
    });
  }

  private canonicalize(search: URLSearchParams): string {
    const pairs = Array.from(search.entries());
    pairs.sort(([a], [b]) => a.localeCompare(b));
    return pairs
      .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
      .join('&');
  }

  private subscribeFacadeToUrl(): void {
    toObservable(this.facade.requestParams)
      .pipe(
        skip(1),
        debounceTime(URL_WRITE_DEBOUNCE_MS),
        distinctUntilChanged(),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((query) => {
        if (query === this.lastAppliedQuery) {
          return;
        }
        this.lastAppliedQuery = query;
        const queryParams = this.queryToParamMap(query);
        // Rename the reference filter's API key to its distinct URL key so it
        // doesn't collide with the dialog's `messageId` deep-link param.
        if (REFERENCE_FILTER_API_KEY in queryParams) {
          queryParams[REFERENCE_FILTER_URL_KEY] = queryParams[REFERENCE_FILTER_API_KEY];
          delete queryParams[REFERENCE_FILTER_API_KEY];
        }
        // Carry non-filter params (e.g. `messageId` from the details dialog)
        // across the 'replace' navigation so an open dialog isn't dismissed by
        // an unrelated filter change.
        const current = this.route.snapshot.queryParamMap;
        for (const key of NON_FILTER_QUERY_PARAMS) {
          const value = current.get(key);
          if (value !== null) {
            queryParams[key] = value;
          }
        }
        this.router.navigate([], {
          relativeTo: this.route,
          queryParams,
          queryParamsHandling: 'replace',
          replaceUrl: true,
        });
      });
  }

  private deferDomainResolution(): void {
    effect(() => {
      const pending = this.pendingDomainId();
      if (!pending) {
        return;
      }
      const domains = this.domain.availableDomains();
      if (domains.length === 0) {
        return;
      }
      // The resolution consumes the pending id — untracked so the effect doesn't
      // read-and-write the same signal (it re-settles via the `!pending` guard
      // either way, but the signal-first guideline is: don't track your own writes).
      untracked(() => {
        const resolved = this.domain.resolveById(pending);
        this.pendingDomainId.set(undefined);
        if (resolved) {
          this.facade.setSliceValue('domain', { domain: resolved });
          this.facade.setEnabled('domain', true);
        }
      });
    });
  }

  private queryToParamMap(query: string): Record<string, string | string[]> {
    if (!query || query.length === 0) {
      return {};
    }
    const search = new URLSearchParams(query);
    const result: Record<string, string | string[]> = {};
    for (const key of new Set(Array.from(search.keys()))) {
      const values = search.getAll(key);
      result[key] = values.length === 1 ? values[0] : values;
    }
    return result;
  }
}
