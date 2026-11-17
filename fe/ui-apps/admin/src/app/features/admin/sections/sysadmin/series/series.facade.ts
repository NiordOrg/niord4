import { httpResource } from '@angular/common/http';
import { Injectable, computed, inject } from '@angular/core';

import type { AdminMessageSeries } from '../../../models/admin-message-series.model';
import { MessageSeriesApiService } from '../../../../messages/services/api/message-series-api.service';

/**
 * Page-scoped list state for Message Series. The payload is the full
 * cross-domain catalog with the server-computed current-year next numbers
 * (`?messageNumbers=true`); nothing on it is localized, so there is no
 * `?lang=` and no language reactivity.
 */
@Injectable()
export class SeriesFacade {
  private readonly api = inject(MessageSeriesApiService);

  // value() throws in the resource's error state — always read via hasValue().
  private readonly resource = httpResource<readonly AdminMessageSeries[]>(
    () => this.api.allRequest(),
    { defaultValue: [] },
  );

  readonly entries = computed<readonly AdminMessageSeries[]>(() =>
    this.resource.hasValue() ? this.resource.value() : [],
  );
  readonly loading = this.resource.isLoading;
  readonly error = computed(() => this.resource.status() === 'error');

  /** Refetch the list — after every successful mutation, and for error retry. */
  refresh(): void {
    this.resource.reload();
  }
}
