import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable, of } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import type { MessageChart, MessageGeometryFeatureCollection } from '../../models/message.model';
import type { ChartRef } from '../../models/messages-filter-params.model';

/**
 * A chart computed from the message geometry. Beyond the stored {@link MessageChart} it carries the
 * wire's `active` / `scale`, kept so the chart list can sort by scale and render the active flag.
 */
export interface ComputedMessageChart extends MessageChart {
  readonly active?: boolean;
  readonly scale?: number;
}

/** Prune an `intersecting-charts` element to the subset the editor stores. */
export function toComputedMessageChart(chart: ComputedMessageChart): ComputedMessageChart {
  return {
    chartNumber: chart.chartNumber,
    internationalNumber: chart.internationalNumber,
    active: chart.active,
    scale: chart.scale,
  };
}

@Injectable({ providedIn: 'root' })
export class ChartsApiService {
  private readonly http = inject(HttpClient);
  private readonly lang = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  search(
    query: string,
    options?: { language?: string; limit?: number; includeGeometry?: boolean },
  ): Observable<ChartRef[]> {
    if (!query || query.length === 0) {
      return of([]);
    }
    // Always send `geometry` (defaulting to 'false'). Omitting it lets the
    // backend include the full chart geometry, which is large and unused by
    // the messages filter.
    const params = this.lang
      .langParams(options?.language)
      .set('name', query)
      .set('geometry', options?.includeGeometry ? 'true' : 'false')
      .set('limit', String(options?.limit ?? 10));
    return this.http.get<ChartRef[]>(`${this.apiBaseUrl}/charts/search`, { params });
  }

  getByChartNumbers(
    chartNumbers: readonly string[],
    options?: { language?: string; includeGeometry?: boolean },
  ): Observable<ChartRef[]> {
    return this.lang.getByIdsCsv<ChartRef>('/charts/search', chartNumbers, {
      language: options?.language,
      extraParams: { geometry: options?.includeGeometry ? 'true' : 'false' },
    });
  }

  /**
   * Charts whose geometry intersects the message geometry. POSTs the
   * combined message `FeatureCollection` — the caller includes `affected` features — and prunes each
   * returned chart to the `{ chartNumber, internationalNumber, active, scale }` subset the editor
   * stores: `POST /rest/charts/intersecting-charts` (no query params).
   */
  intersectingCharts(
    featureCollection: MessageGeometryFeatureCollection,
  ): Observable<ComputedMessageChart[]> {
    return this.http
      .post<
        ComputedMessageChart[]
      >(`${this.apiBaseUrl}/charts/intersecting-charts`, featureCollection)
      .pipe(map((charts) => charts.map(toComputedMessageChart)));
  }
}
