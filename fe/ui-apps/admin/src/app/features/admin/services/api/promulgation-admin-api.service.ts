import { HttpClient, type HttpResourceRequest } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import type {
  AdminPromulgationType,
  NavtexTransmitter,
  PromulgationServiceRef,
  SafetyNetArea,
  TwitterSettings,
} from '../../sections/sysadmin/promulgation/promulgation-admin.model';

/**
 * Promulgation-type catalog administration: services registry + type CRUD +
 * export/import, and the three per-service nested surfaces (NAVTEX
 * transmitters, SafetyNET areas, Twitter settings). Distinct from the messages
 * `PromulgationsApiService` (per-message generate/reformat) and
 * `PromulgationTypesApiService` (public type search). Endpoint contract, fixed
 * by the backend:
 * - creates POST to trailing-slash collection paths (`…/promulgation-type/`,
 *   `…/transmitter/`, `…/area/`, `…/twitter-settings/`) — literal backend
 *   paths; never "normalize" them or create breaks;
 * - the catalog endpoints live under the PLURAL `promulgations` root while all
 *   nested per-service endpoints live under the SINGULAR `promulgation` root
 *   (the same root trap as the editor-side reformat endpoints);
 * - `typeId` and transmitter/area `name` are user-entered free-text PKs — every
 *   path segment is URL-encoded, and each PUT path id is derived from the body
 *   so path and body match by construction (the backend 400s a mismatch);
 * - every nested body carries the `promulgationType.typeId` parent ref, which
 *   the backend also checks against the URL's `{typeId}` (400 on mismatch);
 * - transmitter/area list GETs take `?lang=` for lang-resolved area descs,
 *   defaulting to the current UI language at call time;
 * - the Twitter settings GET legitimately returns a null body while the type
 *   has no settings yet (create-on-demand) — typed honestly as `| null`.
 */
@Injectable({ providedIn: 'root' })
export class PromulgationAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly langHelper = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /** The registered promulgation services (the backend's service registry; a country's backend may add its own). */
  getServices(): Observable<PromulgationServiceRef[]> {
    return this.http.get<PromulgationServiceRef[]>(this.servicesRequest().url);
  }

  /**
   * The same services load as `getServices`, as a request descriptor for
   * reactive `httpResource` consumers (the list page facade) — the endpoint
   * stays defined once.
   */
  servicesRequest(): HttpResourceRequest {
    return { url: `${this.apiBaseUrl}/promulgations/promulgation-services/all` };
  }

  /** The full type catalog. Returns complete detail VOs — edit copies a list row without a refetch. */
  getTypes(): Observable<AdminPromulgationType[]> {
    return this.http.get<AdminPromulgationType[]>(this.typesRequest().url);
  }

  /**
   * The same catalog load as `getTypes`, as a request descriptor for reactive
   * `httpResource` consumers (the list page facade) — the endpoint stays
   * defined once.
   */
  typesRequest(): HttpResourceRequest {
    return { url: `${this.apiBaseUrl}/promulgations/promulgation-types/all` };
  }

  createType(type: AdminPromulgationType): Observable<AdminPromulgationType> {
    return this.http.post<AdminPromulgationType>(
      `${this.apiBaseUrl}/promulgations/promulgation-type/`,
      type,
    );
  }

  updateType(type: AdminPromulgationType): Observable<AdminPromulgationType> {
    return this.http.put<AdminPromulgationType>(this.typeUrl(type.typeId), type);
  }

  deleteType(typeId: string): Observable<unknown> {
    return this.http.delete(this.typeUrl(typeId));
  }

  /** The catalog JSON export (the list endpoint) — downloaded with a fresh sysadmin ticket appended. */
  exportUrl(): string {
    return this.typesRequest().url;
  }

  /** Multipart `.json` upload target — runs the promulgation-type import batch job. */
  uploadUrl(): string {
    return `${this.apiBaseUrl}/promulgations/upload-promulgation-types`;
  }

  getTransmitters(typeId: string, language?: string): Observable<NavtexTransmitter[]> {
    return this.http.get<NavtexTransmitter[]>(`${this.transmittersUrl(typeId)}/all`, {
      params: this.langHelper.langParams(language),
    });
  }

  createTransmitter(typeId: string, transmitter: NavtexTransmitter): Observable<NavtexTransmitter> {
    return this.http.post<NavtexTransmitter>(
      `${this.transmittersUrl(typeId)}/transmitter/`,
      transmitter,
    );
  }

  updateTransmitter(typeId: string, transmitter: NavtexTransmitter): Observable<NavtexTransmitter> {
    return this.http.put<NavtexTransmitter>(
      `${this.transmittersUrl(typeId)}/transmitter/${encodeURIComponent(transmitter.name)}`,
      transmitter,
    );
  }

  deleteTransmitter(typeId: string, name: string): Observable<unknown> {
    return this.http.delete(
      `${this.transmittersUrl(typeId)}/transmitter/${encodeURIComponent(name)}`,
    );
  }

  getSafetyNetAreas(typeId: string, language?: string): Observable<SafetyNetArea[]> {
    return this.http.get<SafetyNetArea[]>(`${this.safetyNetAreasUrl(typeId)}/all`, {
      params: this.langHelper.langParams(language),
    });
  }

  createSafetyNetArea(typeId: string, area: SafetyNetArea): Observable<SafetyNetArea> {
    return this.http.post<SafetyNetArea>(`${this.safetyNetAreasUrl(typeId)}/area/`, area);
  }

  updateSafetyNetArea(typeId: string, area: SafetyNetArea): Observable<SafetyNetArea> {
    return this.http.put<SafetyNetArea>(
      `${this.safetyNetAreasUrl(typeId)}/area/${encodeURIComponent(area.name)}`,
      area,
    );
  }

  deleteSafetyNetArea(typeId: string, name: string): Observable<unknown> {
    return this.http.delete(`${this.safetyNetAreasUrl(typeId)}/area/${encodeURIComponent(name)}`);
  }

  /** A `null` body is a VALID response — the type has no settings yet (create-on-demand). */
  getTwitterSettings(typeId: string): Observable<TwitterSettings | null> {
    return this.http.get<TwitterSettings | null>(this.twitterSettingsUrl(typeId));
  }

  createTwitterSettings(settings: TwitterSettings): Observable<TwitterSettings> {
    return this.http.post<TwitterSettings>(
      `${this.apiBaseUrl}/promulgation/twitter-settings/`,
      settings,
    );
  }

  updateTwitterSettings(settings: TwitterSettings): Observable<TwitterSettings> {
    return this.http.put<TwitterSettings>(
      this.twitterSettingsUrl(settings.promulgationType.typeId),
      settings,
    );
  }

  private typeUrl(typeId: string): string {
    return `${this.apiBaseUrl}/promulgations/promulgation-type/${encodeURIComponent(typeId)}`;
  }

  private transmittersUrl(typeId: string): string {
    return `${this.apiBaseUrl}/promulgation/navtex/transmitters/${encodeURIComponent(typeId)}`;
  }

  private safetyNetAreasUrl(typeId: string): string {
    return `${this.apiBaseUrl}/promulgation/safetynet/areas/${encodeURIComponent(typeId)}`;
  }

  private twitterSettingsUrl(typeId: string): string {
    return `${this.apiBaseUrl}/promulgation/twitter-settings/${encodeURIComponent(typeId)}`;
  }
}
