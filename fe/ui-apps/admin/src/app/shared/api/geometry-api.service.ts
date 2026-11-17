import { HttpClient, HttpParams } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import type { Observable } from 'rxjs';

import { APP_CONFIG } from '../../core/config/app-config.token';
import type { GeometryFeatureCollection } from '../models/geometry.model';

/**
 * The geometry parse / format endpoints used by the editor's import + edit-as-text dialogs.
 * These are backend REST endpoints:
 *
 * - `POST /messages/parse-geometry` `{ geometryText }` → a GeoJSON FeatureCollection
 * - `POST /messages/parse-utm`      `{ geometryText }` → a GeoJSON FeatureCollection
 * - `POST /messages/format-geometry?lang=<l>` (body = FeatureCollection) → plain text
 *
 * `format-geometry` returns `text/plain`, so it is read with `responseType: 'text'`.
 */
@Injectable({ providedIn: 'root' })
export class GeometryApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** Parses a subset of GeoJSON-as-plain-text into a FeatureCollection. */
  parseGeometry(geometryText: string): Observable<GeometryFeatureCollection> {
    return this.http.post<GeometryFeatureCollection>(`${this.apiBaseUrl}/messages/parse-geometry`, {
      geometryText,
    });
  }

  /** Parses a list of UTM coordinates (zone + easting + northing) into a FeatureCollection. */
  parseUtm(geometryText: string): Observable<GeometryFeatureCollection> {
    return this.http.post<GeometryFeatureCollection>(`${this.apiBaseUrl}/messages/parse-utm`, {
      geometryText,
    });
  }

  /** Formats a FeatureCollection as plain text in the given language (defaults backend-side). */
  formatGeometry(
    featureCollection: GeometryFeatureCollection,
    language?: string,
  ): Observable<string> {
    let params = new HttpParams();
    if (language) {
      params = params.set('lang', language);
    }
    return this.http.post(`${this.apiBaseUrl}/messages/format-geometry`, featureCollection, {
      params,
      responseType: 'text',
    });
  }
}
