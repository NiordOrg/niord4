import { DestroyRef, Injectable, inject } from '@angular/core';
import { Subscription, catchError, filter, of, switchMap, timer } from 'rxjs';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { NotificationService } from '../../../core/notifications/notification.service';
import { MyMailsApiService } from './api/my-mails-api.service';

/** How often the user's own mails are looked at. */
export const MY_MAILS_NOTICE_INTERVAL_MS = 5 * 60 * 1000;

/** The period each look covers, in days. */
const NOTICE_DAYS = 1;

/**
 * Tells the signed-in user when a mail they sent could not be sent after its last retry. Started by
 * the messages and editor shells; it looks at the user's mails of the last day at once and then
 * every five minutes, and raises one warning that stays until it is closed, counting the failed
 * mails it has not yet announced in this page load. It keeps only the ids it announced, never the
 * mails. A look that fails is silent: the next one tries again.
 */
@Injectable({ providedIn: 'root' })
export class MyMailsNoticeService {
  private readonly api = inject(MyMailsApiService);
  private readonly auth = inject(AuthFacade);
  private readonly notifications = inject(NotificationService);

  private readonly announced = new Set<number>();
  private subscription: Subscription | undefined;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.subscription?.unsubscribe());
  }

  /** Starts the looks; a second call does nothing. */
  start(): void {
    if (this.subscription) {
      return;
    }
    this.subscription = timer(0, MY_MAILS_NOTICE_INTERVAL_MS)
      .pipe(
        // The read asks for the user role in the session domain.
        filter(() => this.auth.isAuthenticated() && this.auth.hasDomainUserRole()),
        switchMap(() => this.api.mine(NOTICE_DAYS).pipe(catchError(() => of(null)))),
      )
      .subscribe((mails) => {
        if (mails === null) {
          return;
        }
        const failed = mails
          .filter((mail) => mail.status === 'ERROR' && !this.announced.has(mail.id))
          .map((mail) => mail.id);
        if (failed.length === 0) {
          return;
        }
        failed.forEach((id) => this.announced.add(id));
        this.notifications.notify({
          severity: 'warn',
          summaryKey: 'messages.myMails.failedNotice',
          summaryParams: { count: failed.length },
          detailKey: 'messages.myMails.failedNoticeDetail',
          life: 0,
        });
      });
  }
}
