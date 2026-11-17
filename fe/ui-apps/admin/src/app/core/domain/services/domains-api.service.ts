import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../config/app-config.token';
import { LangHttpHelper } from '../../../shared/api/lang-http.helper';
import type { Domain } from '../models/domain.model';

@Injectable({ providedIn: 'root' })
export class DomainsApiService {
  private readonly http = inject(HttpClient);
  private readonly lang = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  lookup(options?: { language?: string }): Observable<Domain[]> {
    // keycloakState=false skips the backend's per-domain Keycloak client lookup
    // (its default is true) — the session catalog never shows Keycloak state. The
    // sysadmin domains table, which DOES need it, uses its own DomainsAdminApiService
    // fetch, never this one.
    return this.http.get<Domain[]>(`${this.apiBaseUrl}/domains/all`, {
      params: this.lang.langParams(options?.language).set('keycloakState', false),
    });
  }
}
