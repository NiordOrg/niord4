import { inject, Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type { BasePromulgation, Message, PromulgationType } from '../../models/message.model';

/**
 * Adapter for the editor's promulgation generate / type-lookup / reformat endpoints. Distinct from
 * the admin `promulgation-types-api.service.ts` (which manages the type catalog) — this is the
 * per-message channel flow. All imperative fetch-on-action `HttpClient` calls; no backend change.
 *
 * Endpoint-root trap (pinned in the specs): generate + promulgation-type live under the PLURAL
 * `promulgations` root, while reformat lives under the SINGULAR `promulgation` root with a doubled
 * service segment (`/promulgation/{serviceId}/reformat-{serviceId}`).
 */
@Injectable({ providedIn: 'root' })
export class PromulgationsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /**
   * Generate a channel promulgation from the FULL working message. The backend renders the
   * per-service text and returns ONE promulgation VO (with its `@class`); the caller deep-merges it
   * back into the matching entry by `typeId`.
   */
  generatePromulgation(typeId: string, message: Message): Observable<BasePromulgation> {
    return this.http.post<BasePromulgation>(
      `${this.apiBaseUrl}/promulgations/generate/${encodeURIComponent(typeId)}`,
      message,
    );
  }

  /**
   * The promulgation type descriptor. Its `messageTypes` drive the message-type auto-deduce when a
   * channel is first enabled with the message type unset.
   */
  getPromulgationType(typeId: string): Observable<PromulgationType> {
    return this.http.get<PromulgationType>(
      `${this.apiBaseUrl}/promulgations/promulgation-type/${encodeURIComponent(typeId)}`,
    );
  }

  /**
   * Reformat a single promulgation's body text (NAVTEX/SafetyNET Format buttons). SINGULAR
   * `promulgation` root with the doubled service segment. Returns a promulgation whose `text` is the
   * only field the caller consumes.
   */
  reformat(serviceId: string, promulgation: BasePromulgation): Observable<BasePromulgation> {
    const service = encodeURIComponent(serviceId);
    return this.http.put<BasePromulgation>(
      `${this.apiBaseUrl}/promulgation/${service}/reformat-${service}`,
      promulgation,
    );
  }
}
