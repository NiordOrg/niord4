import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/**
 * A source→target message-series mapping row. `sourceSeriesId` names a series on
 * the REMOTE Niord instance (free text, may be `''`); `targetSeriesId` names a
 * local series id. Both are sent verbatim — no blank-row pruning.
 */
export interface MessageSeriesMapping {
  readonly sourceSeriesId: string;
  readonly targetSeriesId?: string;
}

export interface NiordIntegration {
  readonly id?: number;
  readonly url: string;
  readonly active: boolean;
  readonly assignNewUids: boolean;
  readonly createBaseData: boolean;
  readonly messageSeriesMappings: readonly MessageSeriesMapping[];
}

interface MessageSeriesMappingVo {
  sourceSeriesId?: string | null;
  targetSeriesId?: string | null;
}

interface NiordIntegrationVo {
  id?: number | null;
  url?: string | null;
  active?: boolean | null;
  assignNewUids?: boolean | null;
  createBaseData?: boolean | null;
  messageSeriesMappings?: MessageSeriesMappingVo[] | null;
}

const mappingFromVo = (vo: MessageSeriesMappingVo): MessageSeriesMapping => ({
  sourceSeriesId: vo.sourceSeriesId ?? '',
  targetSeriesId: vo.targetSeriesId ?? undefined,
});

const fromVo = (vo: NiordIntegrationVo): NiordIntegration => ({
  id: vo.id ?? undefined,
  url: vo.url ?? '',
  active: vo.active ?? false,
  assignNewUids: vo.assignNewUids ?? false,
  createBaseData: vo.createBaseData ?? false,
  messageSeriesMappings: (vo.messageSeriesMappings ?? []).map(mappingFromVo),
});

/**
 * Save payload: the whole record is sent VERBATIM — every mapping row survives,
 * including blank/trailing rows and a row with an empty source + a chosen target
 * (`{ sourceSeriesId: '', targetSeriesId: 'X' }`). No client-side pruning. An
 * unset target is sent as `null`.
 */
const toVo = (integration: NiordIntegration): NiordIntegrationVo => ({
  id: integration.id,
  url: integration.url,
  active: integration.active,
  assignNewUids: integration.assignNewUids,
  createBaseData: integration.createBaseData,
  messageSeriesMappings: integration.messageSeriesMappings.map((mapping) => ({
    sourceSeriesId: mapping.sourceSeriesId,
    targetSeriesId: mapping.targetSeriesId ?? null,
  })),
});

/**
 * Niord-integration CRUD + the per-row execute (server-to-server pull) action.
 * Endpoint contract:
 * - PUT/DELETE/`execute/{id}` path id must equal the body id, derived from the
 *   same model id;
 * - POST carries no path id;
 * - the execute action is a bodyless PUT (no result payload).
 */
@Injectable({ providedIn: 'root' })
export class NiordIntegrationsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getAll(): Observable<NiordIntegration[]> {
    return this.http
      .get<NiordIntegrationVo[]>(`${this.apiBaseUrl}/niord-integrations/all`)
      .pipe(map((integrations) => integrations.map(fromVo)));
  }

  create(integration: NiordIntegration): Observable<NiordIntegration> {
    return this.http
      .post<NiordIntegrationVo>(
        `${this.apiBaseUrl}/niord-integrations/niord-integration/`,
        toVo(integration),
      )
      .pipe(map(fromVo));
  }

  update(integration: NiordIntegration): Observable<NiordIntegration> {
    return this.http
      .put<NiordIntegrationVo>(
        `${this.apiBaseUrl}/niord-integrations/niord-integration/${integration.id}`,
        toVo(integration),
      )
      .pipe(map(fromVo));
  }

  delete(id: number): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/niord-integrations/niord-integration/${id}`);
  }

  /** Fires the backend pull job for one integration (bodyless PUT, no result payload). */
  execute(id: number): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/niord-integrations/execute/${id}`, null);
  }
}
