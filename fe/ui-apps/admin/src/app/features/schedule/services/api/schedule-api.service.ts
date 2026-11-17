import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import type { FiringRunResult } from '../../../../shared/models/firing-run-result.model';
import type { FiringAreaPeriods } from '../../models/firing-area-periods.model';

/**
 * Wraps the existing `/firing-schedules/*` REST surface for the Firing Area
 * Schedule page. The three endpoints already exist on the backend. The
 * `/firing-schedule/*` CRUD on the same resource belongs to the sysadmin config
 * area, not here.
 */
@Injectable({ providedIn: 'root' })
export class ScheduleApiService {
  private readonly http = inject(HttpClient);
  private readonly lang = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /**
   * All firing areas + their periods for the given day. `query` (area-name
   * filter) is sent only when non-empty; each selected area subtree rides as a
   * repeated `area` param; `lang` is always sent (drives the returned area
   * descs); `inactive` defaults false and is not sent.
   */
  searchFiringAreaPeriods(params: {
    date: number;
    query?: string;
    areaIds?: readonly number[];
    language?: string;
  }): Observable<readonly FiringAreaPeriods[]> {
    let httpParams = this.lang.langParams(params.language).set('date', String(params.date));
    if (params.query && params.query.length > 0) {
      httpParams = httpParams.set('query', params.query);
    }
    for (const id of params.areaIds ?? []) {
      httpParams = httpParams.append('area', String(id));
    }
    return this.http.get<readonly FiringAreaPeriods[]>(
      `${this.apiBaseUrl}/firing-schedules/search-firing-area-periods`,
      { params: httpParams },
    );
  }

  /** Persist one area's firing periods for the given date; returns the saved record. */
  updateFiringAreaPeriods(
    firingAreaPeriods: FiringAreaPeriods,
    date: number,
    language?: string,
  ): Observable<FiringAreaPeriods> {
    const params = this.lang.langParams(language).set('date', String(date));
    return this.http.put<FiringAreaPeriods>(
      `${this.apiBaseUrl}/firing-schedules/firing-area-periods`,
      firingAreaPeriods,
      { params },
    );
  }

  /**
   * Regenerate the firing-exercise messages from the active schedules (the server also runs it
   * hourly). Answers when the run has finished, with the number of schedules, areas and
   * cancellations it could not do; the server runs one schedule at a time, so a press during
   * another run waits for it.
   */
  updateFiringExercises(): Observable<FiringRunResult> {
    return this.http.put<FiringRunResult>(
      `${this.apiBaseUrl}/firing-schedules/update-firing-exercises`,
      null,
    );
  }
}
