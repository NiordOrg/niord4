import { HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, switchMap, tap } from 'rxjs';

import { LanguageService } from '../../../core/main-shell/services/language.service';
import { NotificationService } from '../../../core/notifications/notification.service';
import { problemDetail } from '../../../shared/api/problem-detail';
import { MessageMailDialogService } from '../dialogs/message-mail-dialog/message-mail-dialog.service';
import type { MailRequest } from '../dialogs/message-mail-dialog/message-mail-dialog.service';
import { MessageMailApiService } from './api/message-mail-api.service';
import { TagsApiService } from './api/tags-api.service';

/**
 * Bulk-mail orchestrator. Opens the dialog with the supplied messageIds and
 * hands it the send: on Send the dialog creates a temp tag, posts the mail and
 * surfaces a "queued" or error toast. The dialog closes only when the send
 * succeeds; on a failure it stays open with what was typed, and the toast names
 * the backend's refusal when the answer carries one (an address that is no mail
 * address). The backend only queues the mail (a pending `ScheduledMail`,
 * answered "Message mail scheduled"); the mail job sends it later, and a mail
 * that fails is listed in the sysadmin's Mail Queue, so the toast never says
 * the mail was sent.
 *
 * The recipients, subject and text travel in the body of
 * `POST /rest/message-mail/send`; only the temp tag and the language are in the
 * query, so none of the mail reaches an access log.
 */
@Injectable({ providedIn: 'root' })
export class MessageMailService {
  private readonly dialog = inject(MessageMailDialogService);
  private readonly tagsApi = inject(TagsApiService);
  private readonly mailApi = inject(MessageMailApiService);
  private readonly language = inject(LanguageService);
  private readonly notifications = inject(NotificationService);

  /**
   * @param indexIncomplete the list the messages were chosen from was answered while the search
   *   index was being rebuilt; the dialog then shows the list's warning before the mail is sent
   */
  mailSelection(messageIds: readonly string[], indexIncomplete = false): Observable<void> {
    return this.dialog
      .open(messageIds, (request) => this.send(messageIds, request), indexIncomplete)
      .pipe(map(() => undefined));
  }

  /** Emits once when the mail was queued; errors (after its toast) when it was not. */
  private send(messageIds: readonly string[], request: MailRequest): Observable<void> {
    return this.tagsApi.createTempMessageTag(messageIds).pipe(
      switchMap((tempTag) =>
        this.mailApi.send(this.queryParams(tempTag.tagId, request), {
          mailTo: request.emailAddresses,
          mailSubject: request.mailSubject,
          mailMessage: request.mailMessage,
        }),
      ),
      tap({
        next: () => this.notifications.success('messages.mail.queued'),
        error: (error: unknown) => {
          const detail = problemDetail(error);
          if (detail === undefined) {
            this.notifications.error('messages.mail.error');
          } else {
            this.notifications.error('messages.mail.refused', { detail });
          }
        },
      }),
      map(() => undefined),
    );
  }

  private queryParams(tempTagId: string, request: MailRequest): HttpParams {
    return new HttpParams()
      .set('tag', tempTagId)
      .set('lang', request.language ?? this.language.selectedLanguage());
  }
}
