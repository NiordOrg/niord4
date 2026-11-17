import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { APP_CONFIG } from '../../config/app-config.token';

/**
 * Role scope for a one-time ticket. Omitted, the ticket names the user and the domain but carries
 * no role, so it passes no role check: an endpoint that asks for a role treats its bearer as a
 * guest, or refuses it.
 */
export type TicketRole = 'user' | 'admin' | 'sysadmin';

/**
 * One-time auth-ticket fetcher. The backend issues short-lived single-use
 * tokens that we embed in URLs (PDF download, ZIP/JSON export) so the
 * browser can fetch resources without sending the bearer token.
 *
 * Deliberately NO caching — every request fetches a fresh ticket, since each
 * is single-use. The optional role param adds the backend's `?role=` scoping:
 * the backend issues such a ticket only to a caller holding that role, and the
 * ticket then passes that role's checks.
 */
@Injectable({ providedIn: 'root' })
export class AuthTicketService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  fetch(role?: TicketRole): Observable<string> {
    return this.http.get(`${this.apiBaseUrl}/tickets/ticket`, {
      responseType: 'text',
      ...(role ? { params: { role } } : {}),
    });
  }
}
