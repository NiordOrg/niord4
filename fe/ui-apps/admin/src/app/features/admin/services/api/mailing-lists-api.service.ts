import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import type {
  MailingList as MailingListRecord,
  MailingListDesc,
  MailingListTrigger,
} from '../../sections/admin/mailing-lists/mailing-list.model';
import type { Contact } from './contacts-api.service';
import type { User } from './users-api.service';

export interface MailingList {
  /** The string PK — immutable; the update-status path id. */
  readonly mailingListId: string;
  /** Lang-resolved by the `?lang=` search (the membership lookups carry none). */
  readonly name?: string;
  readonly description?: string;
}

interface MailingListDescVo {
  lang?: string | null;
  name?: string | null;
  description?: string | null;
}

interface MailingListVo {
  mailingListId: string;
  active?: boolean | null;
  recipientNo?: number | null;
  descs?: MailingListDescVo[] | null;
  triggers?: unknown[] | null;
}

const fromVo = (vo: MailingListVo): MailingList => ({
  mailingListId: vo.mailingListId,
  name: vo.descs?.[0]?.name ?? undefined,
  description: vo.descs?.[0]?.description ?? undefined,
});

/** The full admin-view mapper: keeps active/recipientNo/descs and (on details) triggers. */
const fromDetailVo = (vo: MailingListVo): MailingListRecord => ({
  mailingListId: vo.mailingListId,
  active: vo.active ?? false,
  recipientNo: vo.recipientNo ?? undefined,
  descs: (vo.descs ?? []).map(
    (desc): MailingListDesc => ({
      lang: desc.lang ?? '',
      name: desc.name ?? '',
      description: desc.description ?? '',
    }),
  ),
  triggers: (vo.triggers ?? undefined) as readonly MailingListTrigger[] | undefined,
});

/** The recipient partition the `/users` and `/contacts` GETs return. */
export interface RecipientPartition<T> {
  readonly selectedRecipients: readonly T[];
  readonly availableRecipients: readonly T[];
}

/** One rendered mail from a test-execute run. */
export interface TestExecutionMail {
  readonly recipients?: readonly { readonly address?: string }[];
  readonly subject?: string;
  readonly contents?: string;
}

/**
 * Mailing-list read + membership surface — the slice the Users & Contacts page
 * consumes. The Mailing Lists admin section later GROWS this service with its
 * CRUD/recipients/triggers methods (same home, never relocated).
 *
 * Contract: the all-lists search carries `?lang=`
 * (the descs are localized server-side — a language switch must re-resolve);
 * the `?username=`/`?contactEmail=` membership lookups carry NO lang (only the
 * ids matter); `update-status` PUTs the FULL user/contact record plus the
 * desired `recipient` flag and returns a boolean.
 */
@Injectable({ providedIn: 'root' })
export class MailingListsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  getAll(language: string): Observable<MailingList[]> {
    const params = new HttpParams().set('lang', language);
    return this.http
      .get<MailingListVo[]>(`${this.apiBaseUrl}/mailing-lists/search`, { params })
      .pipe(map((vos) => vos.map(fromVo)));
  }

  /** Membership lookup keyed by USERNAME (never the keycloakId). */
  getForUser(username: string): Observable<MailingList[]> {
    const params = new HttpParams().set('username', username);
    return this.http
      .get<MailingListVo[]>(`${this.apiBaseUrl}/mailing-lists/search`, { params })
      .pipe(map((vos) => vos.map(fromVo)));
  }

  /** Membership lookup keyed by the contact's EMAIL (never the numeric id). */
  getForContact(contactEmail: string): Observable<MailingList[]> {
    const params = new HttpParams().set('contactEmail', contactEmail);
    return this.http
      .get<MailingListVo[]>(`${this.apiBaseUrl}/mailing-lists/search`, { params })
      .pipe(map((vos) => vos.map(fromVo)));
  }

  updateUserStatus(mailingListId: string, user: User, recipient: boolean): Observable<boolean> {
    return this.http.put<boolean>(
      `${this.apiBaseUrl}/mailing-lists/mailing-list/${encodeURIComponent(mailingListId)}/update-status`,
      { user, recipient },
    );
  }

  updateContactStatus(
    mailingListId: string,
    contact: Contact,
    recipient: boolean,
  ): Observable<boolean> {
    return this.http.put<boolean>(
      `${this.apiBaseUrl}/mailing-lists/mailing-list/${encodeURIComponent(mailingListId)}/update-status`,
      { contact, recipient },
    );
  }

  // ── Admin section: full CRUD + recipients + triggers + reports ────────────────
  // The admin list uses the SAME `/search` endpoint as the membership lookups but keeps the full
  // row (active / recipientNo / descs). Optional `name` drives the list search box.

  getAllDetailed(language: string, name?: string): Observable<MailingListRecord[]> {
    let params = new HttpParams().set('lang', language);
    if (name) {
      params = params.set('name', name);
    }
    return this.http
      .get<MailingListVo[]>(`${this.apiBaseUrl}/mailing-lists/search`, { params })
      .pipe(map((vos) => vos.map(fromDetailVo)));
  }

  getDetails(mailingListId: string): Observable<MailingListRecord> {
    return this.http
      .get<MailingListVo>(
        `${this.apiBaseUrl}/mailing-lists/mailing-list/${encodeURIComponent(mailingListId)}`,
      )
      .pipe(map(fromDetailVo));
  }

  /** Trailing slash is literal — the backend routes create only on `/mailing-list/`. */
  create(mailingList: MailingListRecord): Observable<unknown> {
    return this.http.post(`${this.apiBaseUrl}/mailing-lists/mailing-list/`, mailingList);
  }

  update(mailingList: MailingListRecord): Observable<unknown> {
    return this.http.put(
      `${this.apiBaseUrl}/mailing-lists/mailing-list/${encodeURIComponent(mailingList.mailingListId)}`,
      mailingList,
    );
  }

  remove(mailingListId: string): Observable<unknown> {
    return this.http.delete(
      `${this.apiBaseUrl}/mailing-lists/mailing-list/${encodeURIComponent(mailingListId)}`,
    );
  }

  getRecipientUsers(mailingListId: string): Observable<RecipientPartition<User>> {
    return this.http.get<RecipientPartition<User>>(
      `${this.apiBaseUrl}/mailing-lists/mailing-list/${encodeURIComponent(mailingListId)}/users`,
    );
  }

  updateRecipientUsers(mailingListId: string, users: readonly User[]): Observable<unknown> {
    return this.http.put(
      `${this.apiBaseUrl}/mailing-lists/mailing-list/${encodeURIComponent(mailingListId)}/users`,
      users,
    );
  }

  getRecipientContacts(mailingListId: string): Observable<RecipientPartition<Contact>> {
    return this.http.get<RecipientPartition<Contact>>(
      `${this.apiBaseUrl}/mailing-lists/mailing-list/${encodeURIComponent(mailingListId)}/contacts`,
    );
  }

  updateRecipientContacts(
    mailingListId: string,
    contacts: readonly Contact[],
  ): Observable<unknown> {
    return this.http.put(
      `${this.apiBaseUrl}/mailing-lists/mailing-list/${encodeURIComponent(mailingListId)}/contacts`,
      contacts,
    );
  }

  /**
   * Test-execute one trigger. The body is the WHOLE current (possibly unsaved) mailing list, and
   * the trigger is addressed by its POSITIONAL index — the caller must read the index at click
   * time (reorder/delete shifts indices).
   */
  testExecuteTrigger(
    mailingList: MailingListRecord,
    triggerIndex: number,
    messageId?: string,
  ): Observable<TestExecutionMail[]> {
    let params = new HttpParams().set('triggerIndex', String(triggerIndex));
    if (messageId) {
      params = params.set('messageId', messageId);
    }
    return this.http.post<TestExecutionMail[]>(
      `${this.apiBaseUrl}/mailing-lists/test-execute-trigger`,
      mailingList,
      { params },
    );
  }

  exportUrl(): string {
    return `${this.apiBaseUrl}/mailing-lists/export`;
  }

  uploadUrl(): string {
    return `${this.apiBaseUrl}/mailing-lists/upload-mailing-lists`;
  }
}
