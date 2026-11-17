import { Injectable, computed, inject, signal } from '@angular/core';
import { httpResource } from '@angular/common/http';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of, startWith, switchMap, tap } from 'rxjs';

import { APP_CONFIG } from '../../../../../core/config/app-config.token';
import {
  ScheduledMailSearchParams,
  ScheduledMailSearchResult,
  ScheduledMailsApiService,
} from '../../../services/api/scheduled-mails-api.service';
import { ADMIN_PAGE_SIZE } from '../../../utils/kit-constants';
import { clampPageToTotal } from '../../../utils/server-page-clamp';

const EMPTY_RESULT: ScheduledMailSearchResult = { data: [], size: 0, total: 0 };

const EMPTY_PARAMS: ScheduledMailSearchParams = {
  recipient: '',
  sender: '',
  subject: '',
  status: '',
};

interface MailsState {
  readonly result: ScheduledMailSearchResult;
  readonly loading: boolean;
  readonly error: boolean;
}

const LOADING: MailsState = { result: EMPTY_RESULT, loading: true, error: false };

/**
 * Server-paged mail-queue search. Every param change resets to the first page
 * and refires; page changes refire alone.
 *
 * Page-overflow rule: when a response's total puts the current page past the
 * new last page (the queue shrank between fetches), clamp to the last page
 * and fire exactly ONE corrective refetch — the corrective response is in
 * range by construction, so no loop is possible.
 */
@Injectable()
export class ScheduledMailsFacade {
  private readonly api = inject(ScheduledMailsApiService);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  readonly params = signal<ScheduledMailSearchParams>(EMPTY_PARAMS);
  private readonly page = signal(0);

  readonly first = computed(() => this.page() * ADMIN_PAGE_SIZE);
  readonly rows = ADMIN_PAGE_SIZE;

  // Days before mails are purged from the queue (the TTL note above the list).
  private readonly ttlSetting = httpResource<{ value?: string | number | null } | null>(
    () => `${this.apiBaseUrl}/settings/setting/mailDeleteAfterDays`,
  );

  // Non-positive backend values mean "never delete", so the note is suppressed
  // (undefined) for them — as are a loading or failed fetch. value() throws in the
  // resource's error state, so the read is guarded by hasValue().
  readonly ttlDays = computed<string | undefined>(() => {
    if (!this.ttlSetting.hasValue()) {
      return undefined;
    }
    const days = Number(this.ttlSetting.value()?.value);
    return Number.isFinite(days) && days > 0 ? String(days) : undefined;
  });

  private readonly refreshTick = signal(0);
  private readonly trigger = computed(() => ({
    params: this.params(),
    page: this.page(),
    tick: this.refreshTick(),
  }));

  private readonly state = toSignal(
    toObservable(this.trigger).pipe(
      switchMap(({ params, page }) =>
        this.api.search(params, page, ADMIN_PAGE_SIZE).pipe(
          tap((result) => {
            const clamped = clampPageToTotal(page, ADMIN_PAGE_SIZE, result.total);
            if (clamped !== null) {
              this.page.set(clamped);
            }
          }),
          map((result): MailsState => ({ result, loading: false, error: false })),
          catchError(() => of<MailsState>({ result: EMPTY_RESULT, loading: false, error: true })),
          startWith(LOADING),
        ),
      ),
    ),
    { initialValue: LOADING },
  );

  readonly result = computed(() => this.state().result);
  readonly loading = computed(() => this.state().loading);
  readonly error = computed(() => this.state().error);

  /** Any filter change lands here: merge + reset to the first page. */
  setParams(partial: Partial<ScheduledMailSearchParams>): void {
    this.params.update((params) => ({ ...params, ...partial }));
    this.page.set(0);
  }

  setFirst(first: number): void {
    this.page.set(Math.floor(first / ADMIN_PAGE_SIZE));
  }

  refresh(): void {
    this.refreshTick.update((tick) => tick + 1);
  }
}
