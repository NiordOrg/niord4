import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { APP_CONFIG } from '../../../../core/config/app-config.token';

/** The mail itself, sent in the body of `POST /message-mail/send`. */
export interface MessageMailBody {
  readonly mailTo: readonly string[];
  readonly mailSubject: string;
  readonly mailMessage: string;
}

/**
 * Sends the message mail with `POST /message-mail/send`: the recipients, the subject and the text
 * travel in a JSON body, so a long text is never refused for the length of the address and none of
 * it reaches an access log; the selection (`tag`) and `lang` stay in the query. The deprecated GET
 * form that carried everything in the query is no longer called. The endpoint returns a plain-text
 * status body, so we read as text and discard — using the default `'json'` response type would
 * surface a parse error as a fake failure even when the HTTP 200 was real.
 */
@Injectable({ providedIn: 'root' })
export class MessageMailApiService {
  private readonly http = inject(HttpClient);
  private readonly apiBaseUrl = inject(APP_CONFIG).apiBaseUrl;

  send(params: HttpParams, body: MessageMailBody): Observable<void> {
    return this.http
      .post(`${this.apiBaseUrl}/message-mail/send`, body, { params, responseType: 'text' })
      .pipe(map(() => undefined));
  }
}
