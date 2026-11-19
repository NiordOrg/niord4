import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/**
 * A mail's state in the queue. HELD: the sender job handled it while the mail filter
 * (`mailValidRecipients`) was LOG, so nothing left the server.
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
 * Scheduled-mails (mail queue) search + details. Wire contract: the backend
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
}
