import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/**
 * The area-sort mutations on `/rest/messages` (distinct from the Areas admin CRUD on
 * `/rest/areas`). `changeAreaSortOrder` repositions one message within its area relative to its two
 * new neighbours (a null neighbour = the very top/bottom) — the backend 400s if `id` is null OR both
 * neighbours are null, so callers guard the edges. `recomputeAreaSortOrder` re-derives every
 * published message's area order from each area's configured `messageSorting` strategy (the Areas
 * page's "Re-index published messages" action; `@RolesAllowed(ADMIN)`).
 */
@Injectable({ providedIn: 'root' })
export class MessagesAreaSortApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** Reposition a message within its area (EDITOR). `afterId`/`beforeId` null at the list edges. */
  changeAreaSortOrder(
    id: string,
    afterId: string | null,
    beforeId: string | null,
  ): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/messages/change-area-sort-order`, {
      id,
      afterId,
      beforeId,
    });
  }

  /** Re-index published messages' area sort from each area's strategy (ADMIN; Areas page cog). */
  recomputeAreaSortOrder(): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/messages/recompute-area-sort-order`, {});
  }
}
