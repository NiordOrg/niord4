import { HttpClient, HttpContext, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';
import { BACKGROUND_READ } from '../../../../core/interceptors/background-read';

export type MyMailStatus = 'PENDING' | 'SENT' | 'ERROR';

export interface MyMailRecipient {
  readonly recipientType: 'TO' | 'CC' | 'BCC';
  readonly address: string;
}

/** One mail the signed-in user queued (the mail dialog or a comment's mail), without its text. */
export interface MyMail {
  readonly id: number;
  /** Epoch millis. */
  readonly created?: number;
  /** Epoch millis: when the mail is, or was, due to be sent. */
  readonly sendDate?: number;
  readonly status: MyMailStatus;
  readonly subject: string;
  readonly recipients: readonly MyMailRecipient[];
  readonly attempts: number;
  /** The mail server's message of the last failed attempt. */
  readonly lastError?: string;
}

/** The wire shape: the backend leaves out an empty recipient list and a zero it never set. */
export interface MyMailVo extends Omit<MyMail, 'recipients' | 'attempts'> {
  readonly recipients?: readonly MyMailRecipient[] | null;
  readonly attempts?: number | null;
}

export const fromMyMailVo = (vo: MyMailVo): MyMail => ({
  ...vo,
  recipients: vo.recipients ?? [],
  attempts: vo.attempts ?? 0,
});

/**
 * The signed-in user's own queued mails (`GET /rest/scheduled-mails/mine`, the `user` role): the
 * mails of the last `days` days, newest first, so the sender can see whether a mail was sent. It
 * lives in the messages feature, which owns the dialog and the notice; the sysadmin Mail Queue
 * has its own service.
 */
@Injectable({ providedIn: 'root' })
export class MyMailsApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  /**
   * `background`: the read is made by the application on its own (the failed-mail notice), so it
   * neither refreshes the session's token nor ends the session (see `BACKGROUND_READ`).
   */
  mine(days: number, options: { background?: boolean } = {}): Observable<readonly MyMail[]> {
    const params = new HttpParams().set('days', days);
    const context = new HttpContext().set(BACKGROUND_READ, options.background ?? false);
    return this.http
      .get<
        readonly MyMailVo[] | null
      >(`${this.apiBaseUrl}/scheduled-mails/mine`, { params, context })
      .pipe(map((list) => (list ?? []).map(fromMyMailVo)));
  }
}
