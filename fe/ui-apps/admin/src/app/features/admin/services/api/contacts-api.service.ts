import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

export interface Contact {
  /** Numeric PK — absent on add; the update/delete path id. */
  readonly id?: number;
  /** Required — also the mailing-list membership key (never the numeric id). */
  readonly email: string;
  readonly name?: string;
  readonly language?: string;
}

export interface ContactSearchResult {
  readonly data: readonly Contact[];
  readonly size: number;
  readonly total: number;
}

interface ContactVo {
  id?: number | null;
  email: string;
  name?: string | null;
  language?: string | null;
}

interface ContactSearchResultVo {
  data?: ContactVo[] | null;
  size?: number | null;
  total?: number | null;
}

const fromVo = (vo: ContactVo): Contact => ({
  id: vo.id ?? undefined,
  email: vo.email,
  name: vo.name ?? undefined,
  language: vo.language ?? undefined,
});

/**
 * Contacts CRUD (server-paged). Contract:
 * - the search page is 1-based in the UI and 0-BASED ON THE WIRE (this adapter
 *   owns the conversion);
 * - the `name` param is OMITTED when empty (never sent blank);
 * - the PUT path id is the numeric `id` and must equal the body's;
 * - import-emails POSTs the raw pasted text and returns the backend's
 *   PLAIN-TEXT result string ("Imported N new contacts in M ms");
 * - the export endpoint is PermitAll with a programmatic ADMIN check — it
 *   needs the role-scoped ticket (`TicketedDownloadService.openTicketed`).
 */
@Injectable({ providedIn: 'root' })
export class ContactsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  search(name: string, page: number, maxSize: number): Observable<ContactSearchResult> {
    let params = new HttpParams().set('maxSize', maxSize).set('page', page - 1);
    if (name.length > 0) {
      params = params.set('name', name);
    }
    return this.http
      .get<ContactSearchResultVo>(`${this.apiBaseUrl}/contacts/search`, { params })
      .pipe(
        map((vo) => ({
          data: (vo.data ?? []).map(fromVo),
          size: vo.size ?? 0,
          total: vo.total ?? 0,
        })),
      );
  }

  create(contact: Contact): Observable<unknown> {
    return this.http.post(`${this.apiBaseUrl}/contacts/contact/`, contact);
  }

  update(contact: Contact): Observable<unknown> {
    return this.http.put(`${this.apiBaseUrl}/contacts/contact/${contact.id}`, contact);
  }

  delete(id: number): Observable<unknown> {
    return this.http.delete(`${this.apiBaseUrl}/contacts/contact/${id}`);
  }

  /** Returns the backend's plain-text result string. */
  importEmails(emails: string): Observable<string> {
    return this.http.post(
      `${this.apiBaseUrl}/contacts/import-emails`,
      { emails },
      { responseType: 'text' },
    );
  }

  exportUrl(): string {
    return `${this.apiBaseUrl}/contacts/export`;
  }
}
