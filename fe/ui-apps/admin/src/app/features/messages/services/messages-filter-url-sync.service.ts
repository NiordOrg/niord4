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

// Two filters' backend keys mean something else on the address, so the address
// carries them under keys of their own, while the backend request, saved filters,
// print and export (all built from `facade.requestParams`) keep the backend keys:
// - the reference filter's `messageId` is also the details dialog's deep-link key;
//   on the address it is `refMessageId`, so typing in the reference filter does
//   NOT open the dialog;
// - the Domain filter's `domain` is also the session-domain deep link the
//   application reads when it starts; on the address it is `searchDomain`, so a
//   bookmark with the Domain filter searches that domain and does not make it the
//   current one. An inbound `domain` is that deep link, already used at start-up,
//   and never a filter.
const URL_KEY_BY_API_KEY: Readonly<Record<string, string>> = {
  messageId: 'refMessageId',
  domain: 'searchDomain',
};
const API_KEY_BY_URL_KEY: Readonly<Record<string, string>> = Object.fromEntries(
  Object.entries(URL_KEY_BY_API_KEY).map(([apiKey, urlKey]) => [urlKey, apiKey]),
);

/** The address key a filter parameter is written under. */
export function filterUrlKey(apiKey: string): string {
  return Object.hasOwn(URL_KEY_BY_API_KEY, apiKey) ? URL_KEY_BY_API_KEY[apiKey] : apiKey;
}

/**
 * The filter parameter an address key is read as, or `undefined` for a key on the address
 * that is not a filter (the dialog's `messageId`, the session-domain `domain`).
 */
export function filterApiKey(urlKey: string): string | undefined {
  if ((NON_FILTER_QUERY_PARAMS as readonly string[]).includes(urlKey)) return undefined;
  if (Object.hasOwn(URL_KEY_BY_API_KEY, urlKey)) return undefined;
  return Object.hasOwn(API_KEY_BY_URL_KEY, urlKey) ? API_KEY_BY_URL_KEY[urlKey] : urlKey;
}

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
        const apiKey = filterApiKey(key);
        if (apiKey === undefined) continue;
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
        // The filter's keys as the address carries them (see `filterUrlKey`).
        const queryParams = this.queryToParamMap(query);
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
      result[filterUrlKey(key)] = values.length === 1 ? values[0] : values;
    }
    return result;
  }
}
