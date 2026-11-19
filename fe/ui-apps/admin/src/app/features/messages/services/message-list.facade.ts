import { computed, DestroyRef, effect, inject, Injectable, signal } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import {
  catchError,
  debounceTime,
  distinctUntilChanged,
  filter,
  map,
  merge,
  of,
  Subject,
  switchMap,
  tap,
} from 'rxjs';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { MessagesApiService } from './api/messages-api.service';
import { MessageSelectionService } from './message-selection.service';
import { MessagesFilterFacade } from './messages-filter.facade';
import { MessagesShellFacade } from './messages-shell.facade';
import { publicationFilterErrorDetail } from './publication-filter-error';
import { topAreaWithinLevels } from '../../../shared/utils/group-by-top-area';
import { NO_AREA_SENTINEL_ID } from '../models/message.model';
import type { Message, MessageArea } from '../models/message.model';
import type { MessageSearchResult } from '../models/message-search-result.model';

const SEARCH_DEBOUNCE_MS = 100;
const MAX_AREA_LEVELS = 2;

interface SearchRequest {
  readonly params: string;
  readonly page: number;
  readonly maxSize: number;
  readonly viewModeMap: boolean;
  readonly append: boolean;
}

export interface GroupedMessageEntry {
  readonly message: Message;
  readonly areaHeading?: MessageArea;
}

@Injectable({ providedIn: 'root' })
export class MessageListFacade {
  private readonly api = inject(MessagesApiService);
  private readonly filterFacade = inject(MessagesFilterFacade);
  private readonly shell = inject(MessagesShellFacade);
  private readonly selection = inject(MessageSelectionService);
  private readonly language = inject(LanguageService);
  private readonly destroyRef = inject(DestroyRef);

  private readonly _messages = signal<readonly Message[]>([]);
  private readonly _total = signal(0);
  private readonly _page = signal(0);
  private readonly _loading = signal(false);
  private readonly _error = signal<unknown | null>(null);
  private readonly _indexIncomplete = signal(false);

  readonly messages = this._messages.asReadonly();
  readonly total = this._total.asReadonly();
  readonly page = this._page.asReadonly();
  readonly loading = this._loading.asReadonly();
  readonly error = this._error.asReadonly();
  readonly hasMore = computed(() => this._messages().length < this._total());

  /**
   * Whether the last answer was a free-text search the backend ran while its search index was still
   * being filled after a restart, so messages may be missing from the list. Each answer sets it
   * anew; a failed search leaves it as the rows on screen left it.
   */
  readonly indexIncomplete = this._indexIncomplete.asReadonly();

  /**
   * What to say under the publication chip when the search was refused for it.
   *
   * A publication the backend cannot resolve to a message set comes back as a
   * `400`, and that refusal belongs to ONE filter rather than to the list. Left
   * as a generic list error it reads as "search is broken"; left unsaid it reads
   * as "this publication contains nothing" — an answer, and the wrong one.
   */
  readonly publicationFilterError = computed(() => publicationFilterErrorDetail(this._error()));

  readonly groupedMessages = computed<readonly GroupedMessageEntry[]>(() => {
    const list = this._messages();
    if (this.filterFacade.sortBy() !== 'AREA') {
      return list.map((message) => ({ message }));
    }
    const result: GroupedMessageEntry[] = [];
    let lastTopAreaId: number | undefined;
    for (const message of list) {
      const top = this.topArea(message);
      if (top) {
        if (lastTopAreaId !== top.id) {
          lastTopAreaId = top.id;
          result.push({ message, areaHeading: top });
          continue;
        }
      } else if (lastTopAreaId !== NO_AREA_SENTINEL_ID) {
        lastTopAreaId = NO_AREA_SENTINEL_ID;
        result.push({
          message,
          areaHeading: { id: NO_AREA_SENTINEL_ID } as MessageArea,
        });
        continue;
      }
      result.push({ message });
    }
    return result;
  });

  private topArea(message: Message): MessageArea | undefined {
    const first = message.areas?.[0];
    return first ? topAreaWithinLevels(first, MAX_AREA_LEVELS) : undefined;
  }

  private readonly loadMore$ = new Subject<void>();
  private readonly retry$ = new Subject<void>();
  private readonly refresh$ = new Subject<void>();

  private readonly triggerKey = computed(() => ({
    params: this.filterFacade.requestParams(),
    requiresSearch: this.shell.requiresSearch(),
    isMap: this.shell.isMap(),
    lang: this.language.selectedLanguage(),
  }));

  constructor() {
    // Clear list/total/page synchronously on isMap flip. The triggerKey pipeline below
    // also reacts to isMap, but its debounce would leave stale rows visible for ~100ms
    // during the transition; this effect avoids that flash. The pipeline still owns the refetch.
    let prevIsMap = this.shell.isMap();
    effect(() => {
      const isMap = this.shell.isMap();
      if (isMap !== prevIsMap) {
        prevIsMap = isMap;
        this._messages.set([]);
        this._total.set(0);
        this._page.set(0);
        this._indexIncomplete.set(false);
      }
    });

    const filterDriven$ = toObservable(this.triggerKey).pipe(
      debounceTime(SEARCH_DEBOUNCE_MS),
      distinctUntilChanged(
        (a, b) =>
          a.params === b.params &&
          a.requiresSearch === b.requiresSearch &&
          a.isMap === b.isMap &&
          a.lang === b.lang,
      ),
      // This filter MUST stay DOWNSTREAM of distinctUntilChanged. `requiresSearch` goes
      // false while no message view is mounted, and that key still has to reach the
      // comparator so the flip back to true on re-entry registers as a change — that is
      // what refetches a list whose filter never changed. Filtering first would remember
      // only the last searching key and silently serve stale rows on return.
      filter((k) => k.requiresSearch),
      tap(() => this._page.set(0)),
      map(
        (k): SearchRequest => ({
          params: k.params,
          page: 0,
          maxSize: this.shell.maxPageSize(),
          viewModeMap: k.isMap,
          append: false,
        }),
      ),
    );

    const loadMoreDriven$ = this.loadMore$.pipe(
      map(
        (): SearchRequest => ({
          params: this.filterFacade.requestParams(),
          page: this._page() + 1,
          maxSize: this.shell.maxPageSize(),
          viewModeMap: this.shell.isMap(),
          append: true,
        }),
      ),
      tap((req) => this._page.set(req.page)),
    );

    const retryDriven$ = this.retry$.pipe(
      map(
        (): SearchRequest => ({
          params: this.filterFacade.requestParams(),
          page: this._page(),
          maxSize: this.shell.maxPageSize(),
          viewModeMap: this.shell.isMap(),
          append: this._page() > 0,
        }),
      ),
    );

    // Force a refetch of the current search (page 0), e.g. after a bulk mutation.
    const refreshDriven$ = this.refresh$.pipe(
      map(
        (): SearchRequest => ({
          params: this.filterFacade.requestParams(),
          page: 0,
          maxSize: this.shell.maxPageSize(),
          viewModeMap: this.shell.isMap(),
          append: false,
        }),
      ),
      tap(() => this._page.set(0)),
    );

    merge(filterDriven$, loadMoreDriven$, retryDriven$, refreshDriven$)
      .pipe(
        tap(() => {
          this._loading.set(true);
          this._error.set(null);
        }),
        switchMap((req) =>
          this.api
            .searchMessages(req.params, req.page, req.maxSize, {
              viewModeMap: req.viewModeMap,
              includeNoPos: req.viewModeMap,
            })
            .pipe(
              map((result) => ({ kind: 'success' as const, req, result })),
              catchError((err) => of({ kind: 'error' as const, req, err })),
            ),
        ),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe((outcome) => this.applyOutcome(outcome));
  }

  /**
   * Load the next page and append its rows. No-op while loading, when the full list is
   * already loaded, or while an error is pending: a failed page must be retried (via
   * `retry()`), never skipped, so the loaded range never has a gap.
   */
  loadMore(): void {
    if (this._loading() || !this.hasMore() || this._error() !== null) {
      return;
    }
    this.loadMore$.next();
  }

  retry(): void {
    if (this._loading() || this._error() === null) {
      return;
    }
    this.retry$.next();
  }

  /** Force a refetch of the current search (e.g. after a bulk status update). */
  refresh(): void {
    if (this._loading()) {
      return;
    }
    this.refresh$.next();
  }

  private applyOutcome(
    outcome:
      | { kind: 'success'; req: SearchRequest; result: MessageSearchResult }
      | { kind: 'error'; req: SearchRequest; err: unknown },
  ): void {
    this._loading.set(false);
    if (outcome.kind === 'error') {
      this._error.set(outcome.err);
      return;
    }
    const { req, result } = outcome;
    const rows = [...result.data];
    if (req.append) {
      this._messages.update((prev) => [...prev, ...rows]);
    } else {
      this._messages.set(rows);
    }
    this._total.set(result.total);
    this._indexIncomplete.set(result.indexIncomplete === true);
    this.selection.refreshSnapshotsFromSearchResults(rows);
  }
}
