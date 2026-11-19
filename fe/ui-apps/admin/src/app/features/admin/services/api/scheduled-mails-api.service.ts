import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/**
 * A mail's state in the queue. HELD: when the sender job handled it, the mail filter
 * (`mailValidRecipients`) kept it from reaching all of its recipients: the filter was LOG, so
 * nothing left the server, or a list that redirected a recipient to its first listed address.
 */
export type ScheduledMailStatus = 'PENDING' | 'SENT' | 'HELD' | 'ERROR';

export interface ScheduledMailRecipient {
  readonly recipientType: 'TO' | 'CC' | 'BCC';
  readonly address: string;
}

export interface ScheduledMail {
  readonly id: number;
  readonly created?: number;
  readonly sendDate?: number;
  readonly status: string;
  readonly sender: string;
  readonly subject: string;
  readonly recipients: readonly ScheduledMailRecipient[];
  readonly contents?: string;
  readonly lastError?: string;
  readonly attempts?: number;
  /** Epoch millis of the last Send again; absent when the mail was never sent again. */
  readonly sentAgainAt?: number;
  /** The user name of the system administrator who last sent the mail again. */
  readonly sentAgainBy?: string;
}

/** The most mails one Send again request may name (the backend refuses more with a 400). */
export const SEND_AGAIN_MAX = 500;

/** The statuses a mail can be sent again from; a PENDING mail is on its way already. */
export type SendAgainStatus = Extract<ScheduledMailStatus, 'HELD' | 'SENT' | 'ERROR'>;

/**
 * One mail to send again, with the status and `sentAgainAt` the page showed: the backend refuses
 * the mail as CHANGED when either differs from the row it locks, so a stale page, a double click or
 * a second administrator never sends a mail twice.
 */
export interface SendAgainItem {
  readonly id: number;
  readonly status: SendAgainStatus;
  /** Epoch millis, or null when the mail was never sent again. */
  readonly sentAgainAt: number | null;
}

/**
 * Why the backend left a mail as it was: GONE, deleted since the list was read; CHANGED, its status
 * or `sentAgainAt` is no longer what the page showed; RECORD, a failure record, not a mail; FILTER,
 * a SENT mail while the mail filter is a list that would redirect one of its recipients (it would
 * end HELD, and a later Send again of the held mails would deliver it to them a second time).
 */
export type SendAgainRefusalReason = 'GONE' | 'CHANGED' | 'RECORD' | 'FILTER';

export interface SendAgainRefusal {
  readonly id: number;
  readonly reason: SendAgainRefusalReason;
}

export interface SendAgainResult {
  /** The ids put back to PENDING; the sender job sends them within a minute or two. */
  readonly queued: readonly number[];
  readonly refused: readonly SendAgainRefusal[];
}

interface SendAgainResultVo {
  queued?: number[] | null;
  refused?: SendAgainRefusal[] | null;
}

export interface ScheduledMailSearchParams {
  readonly recipient: string;
  readonly sender: string;
  readonly subject: string;
  /** '' = all statuses (the param is omitted from the request). */
  readonly status: '' | ScheduledMailStatus;
  /** Epoch millis, already snapped to 00:00:00 / 23:59:59 by the caller. */
  readonly fromDate?: number;
  readonly toDate?: number;
}

export interface ScheduledMailSearchResult {
  readonly data: readonly ScheduledMail[];
  readonly size: number;
  readonly total: number;
}

interface ScheduledMailVo extends Omit<ScheduledMail, 'recipients'> {
  recipients?: ScheduledMailRecipient[] | null;
}

interface SearchResultVo {
  data?: ScheduledMailVo[] | null;
  size?: number | null;
  total?: number | null;
}

const fromVo = (vo: ScheduledMailVo): ScheduledMail => ({
  ...vo,
  recipients: vo.recipients ?? [],
});

/**
 * Scheduled-mails (mail queue) search, details and Send again. Wire contract: the backend
 * page param is 0-BASED; text params and status are emitted only when
 * non-empty; dates go as epoch millis `from`/`to`.
 */
@Injectable({ providedIn: 'root' })
export class ScheduledMailsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  search(
    params: ScheduledMailSearchParams,
    page: number,
    maxSize: number,
  ): Observable<ScheduledMailSearchResult> {
    let httpParams = new HttpParams().set('maxSize', maxSize).set('page', page);
    if (params.recipient.length > 0) {
      httpParams = httpParams.set('recipient', params.recipient);
    }
    if (params.sender.length > 0) {
      httpParams = httpParams.set('sender', params.sender);
    }
    if (params.subject.length > 0) {
      httpParams = httpParams.set('subject', params.subject);
    }
    if (params.status.length > 0) {
      httpParams = httpParams.set('status', params.status);
    }
    if (params.fromDate !== undefined) {
      httpParams = httpParams.set('from', params.fromDate);
    }
    if (params.toDate !== undefined) {
      httpParams = httpParams.set('to', params.toDate);
    }
    return this.http
      .get<SearchResultVo>(`${this.apiBaseUrl}/scheduled-mails/search`, { params: httpParams })
      .pipe(
        map((vo) => ({
          data: (vo.data ?? []).map(fromVo),
          size: vo.size ?? 0,
          total: vo.total ?? 0,
        })),
      );
  }

  getMailDetails(id: number): Observable<ScheduledMail> {
    return this.http
      .get<ScheduledMailVo>(`${this.apiBaseUrl}/scheduled-mails/scheduled-mail/${id}`)
      .pipe(map(fromVo));
  }

  /**
   * Puts the given mails back to PENDING (`POST /rest/scheduled-mails/send-again`, sysadmin only).
   * The backend refuses the whole request with a 409 while the mail filter is LOG or empty, and with
   * a 400 for an empty list, more than {@link SEND_AGAIN_MAX} mails, a duplicate id or a status it
   * cannot send again from; both carry a problem document whose `detail` says why. Otherwise each
   * mail is queued or refused on its own, and a refusal leaves the others queued.
   */
  sendAgain(items: readonly SendAgainItem[]): Observable<SendAgainResult> {
    return this.http
      .post<SendAgainResultVo | null>(`${this.apiBaseUrl}/scheduled-mails/send-again`, {
        mails: items.map(({ id, status, sentAgainAt }) => ({ id, status, sentAgainAt })),
      })
      .pipe(
        map((vo) => ({
          queued: vo?.queued ?? [],
          refused: vo?.refused ?? [],
        })),
      );
  }
}
