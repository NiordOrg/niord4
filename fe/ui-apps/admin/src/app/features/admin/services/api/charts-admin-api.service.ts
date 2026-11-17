import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import type { Geometry } from 'geojson';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

export interface Chart {
  /** The string primary key — immutable on edit (the PUT/DELETE path id). */
  readonly chartNumber: string;
  readonly internationalNumber?: number;
  readonly name?: string;
  readonly scale?: number;
  readonly horizontalDatum?: string;
  readonly active: boolean;
  /** The chart's coverage extent — a single bare Geometry on the wire. */
  readonly geometry?: Geometry;
}

interface ChartVo {
  chartNumber: string;
  internationalNumber?: number | null;
  name?: string | null;
  scale?: number | null;
  horizontalDatum?: string | null;
  active?: boolean | null;
  geometry?: Geometry | null;
}

const fromVo = (vo: ChartVo): Chart => ({
  chartNumber: vo.chartNumber,
  internationalNumber: vo.internationalNumber ?? undefined,
  name: vo.name ?? undefined,
  scale: vo.scale ?? undefined,
  horizontalDatum: vo.horizontalDatum ?? undefined,
  active: vo.active ?? false,
  geometry: vo.geometry ?? undefined,
});

/**
 * Charts CRUD (the admin catalog — distinct from the messages read-side
 * `ChartsApiService` chart-filter search). Endpoint contract:
 * - the list `GET /charts/all` carries no `?lang=` (charts are not localized)
 *   and its payload includes each chart's `geometry` (the overview map needs it);
 * - the PUT/DELETE path id is the URL-encoded `chartNumber` and must equal the
 *   body's (the backend 400s a mismatch);
 * - the export URL is the bare PermitAll list endpoint — no ticket;
 * - `geometry` stays a bare GeoJSON `Geometry` on the wire (the editor-side
 *   FeatureCollection wrapping is the chart editor's concern, never the adapter's).
 */
@Injectable({ providedIn: 'root' })
export class ChartsAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getAll(): Observable<Chart[]> {
    return this.http
      .get<ChartVo[]>(`${this.apiBaseUrl}/charts/all`)
      .pipe(map((vos) => vos.map(fromVo)));
  }

  create(chart: Chart): Observable<unknown> {
    return this.http.post(`${this.apiBaseUrl}/charts/chart/`, chart);
  }

  update(chart: Chart): Observable<unknown> {
    return this.http.put(
      `${this.apiBaseUrl}/charts/chart/${encodeURIComponent(chart.chartNumber)}`,
      chart,
    );
  }

  delete(chartNumber: string): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/charts/chart/${encodeURIComponent(chartNumber)}`);
  }

  exportUrl(): string {
    return `${this.apiBaseUrl}/charts/all`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/charts/upload-charts`;
  }
}
