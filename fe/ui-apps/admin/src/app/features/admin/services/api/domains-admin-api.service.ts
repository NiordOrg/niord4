import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { LangHttpHelper } from '../../../../shared/api/lang-http.helper';
import type { AdminDomain } from '../../models/admin-domain.model';

/**
 * Domain CRUD + Keycloak provisioning (the sysadmin catalog — distinct from the
 * core `DomainsApiService.lookup()`, which feeds the active-only session
 * catalog and must never back the admin table). Endpoint contract, fixed by
 * the backend:
 * - the list fetch passes `inactive=true` (the admin table shows ALL domains)
 *   and `lang` (area/category descs are sorted server-side); the backend's
 *   `keycloakState` default (true) stays in effect so `inKeycloak` is computed,
 *   unless the caller opts out — see {@link DomainsAdminApiService.getAll};
 * - create POSTs to the trailing-slash collection path `/domains/domain/`;
 * - the PUT/DELETE path id is the URL-encoded `domainId`; the PUT path id is
 *   derived from the body's, so the two match by construction (the backend
 *   400s a mismatch — the id is immutable);
 * - the Keycloak provisioning POST returns no body — the new state is learned
 *   only by re-fetching the list;
 * - the export deliberately includes inactive domains, so the backup never
 *   silently drops rows.
 */
@Injectable({ providedIn: 'root' })
export class DomainsAdminApiService {
  private readonly http = inject(HttpClient);
  private readonly langHelper = inject(LangHttpHelper);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /**
   * The whole catalogue, inactive rows included.
   *
   * `keycloakState: false` is for a caller that only needs a domain's id, name
   * and settings. The server's default is to compute `inKeycloak` per domain,
   * and it does that by asking Keycloak once per row — a third of a second on a
   * catalogue of this size, paid by every screen that reads the list for a
   * picker it will render as names. Only the screen that ADMINISTERS the
   * Keycloak clients has anything to show for it, so only that screen pays.
   */
  getAll(language?: string, options: { keycloakState?: boolean } = {}): Observable<AdminDomain[]> {
    let params = this.langHelper.langParams(language).set('inactive', true);
    if (options.keycloakState === false) {
      params = params.set('keycloakState', false);
    }
    return this.http.get<AdminDomain[]>(`${this.apiBaseUrl}/domains/all`, { params });
  }

  create(domain: AdminDomain): Observable<AdminDomain> {
    return this.http.post<AdminDomain>(`${this.apiBaseUrl}/domains/domain/`, domain);
  }

  update(domain: AdminDomain): Observable<AdminDomain> {
    return this.http.put<AdminDomain>(this.domainUrl(domain.domainId), domain);
  }

  delete(domainId: string): Observable<unknown> {
    return this.http.delete(this.domainUrl(domainId));
  }

  /** Provisions the domain's Keycloak client (void response — re-fetch the list to learn the state). */
  createInKeycloak(domain: AdminDomain): Observable<void> {
    return this.http.post<void>(`${this.apiBaseUrl}/domains/keycloak`, domain);
  }

  /** The full-catalog JSON export — includes inactive domains so the backup is complete. */
  exportUrl(): string {
    return `${this.apiBaseUrl}/domains/all?inactive=true`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/domains/upload-domains`;
  }

  private domainUrl(domainId: string): string {
    return `${this.apiBaseUrl}/domains/domain/${encodeURIComponent(domainId)}`;
  }
}
