import { DOCUMENT, DestroyRef, Injectable, inject } from '@angular/core';
import { Subscription, finalize } from 'rxjs';

import { AuthFacade } from '../../../core/auth/facades/auth.facade';
import { BackgroundReadSkipped } from '../../../core/interceptors/background-read';
import { NotificationService } from '../../../core/notifications/notification.service';
import { MyMailsApiService, type MyMail } from './api/my-mails-api.service';

/** How often the user's own mails are looked at, at most. */
export const MY_MAILS_NOTICE_INTERVAL_MS = 5 * 60 * 1000;

/**
 * How often a look that is due is tried. A try costs nothing while the session's token is too old
 * for a background read: the read is refused before it leaves the browser.
 */
export const MY_MAILS_NOTICE_CHECK_MS = 30 * 1000;

/** The period each look covers, in days. */
const NOTICE_DAYS = 1;

/**
 * Tells the signed-in user when a mail they sent could not be sent after its last retry. The
 * messages and editor shells attach it while they are mounted; it looks at the user's mails of the
 * last day at most every five minutes, and only while one of the shells is mounted and the tab is
 * shown (deliberately no background-tab looks, as `BatchStatusService`). It raises one warning that
 * stays until it is closed, counting the failed mails it has not yet announced in this page load,
 * and keeps only the ids it announced, never the mails.
 *
 * Every look is a background read (`BACKGROUND_READ`): it never refreshes the session's token and
 * never ends the session, so an idle tab still ends its session at the realm's idle timeout, and a
 * look that fires while the network is down cannot drop the user to guest. A look the token is too
 * old for is tried again at the next check, which succeeds once the user's own work has refreshed
 * the token. A look that fails otherwise is silent: the next one, five minutes later, tries again.
 */
@Injectable({ providedIn: 'root' })
export class MyMailsNoticeService {
  private readonly api = inject(MyMailsApiService);
  private readonly auth = inject(AuthFacade);
  private readonly notifications = inject(NotificationService);
  private readonly document = inject(DOCUMENT);

  private readonly announced = new Set<number>();
  /** The shells mounted now. */
  private attached = 0;
  private timer: ReturnType<typeof setInterval> | undefined;
  /** When the last look was answered (or failed for good); undefined before the first. */
  private lastLook: number | undefined;
  private request: Subscription | undefined;
  private inFlight = false;

  private readonly onVisibilityChange = (): void => {
    if (this.document.hidden) {
      this.stopChecks();
    } else {
      this.beginChecks();
    }
  };

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /**
   * Looks while the calling shell is mounted: from now until `shellDestroyRef` is destroyed. Two
   * shells mounted at once share the looks.
   */
  start(shellDestroyRef: DestroyRef): void {
    this.attached++;
    shellDestroyRef.onDestroy(() => this.detach());
    if (this.attached === 1) {
      this.document.addEventListener('visibilitychange', this.onVisibilityChange);
      if (!this.document.hidden) {
        this.beginChecks();
      }
    }
  }

  private detach(): void {
    this.attached = Math.max(0, this.attached - 1);
    if (this.attached === 0) {
      this.stop();
    }
  }

  private stop(): void {
    this.stopChecks();
    this.document.removeEventListener('visibilitychange', this.onVisibilityChange);
    this.request?.unsubscribe();
    this.request = undefined;
    this.inFlight = false;
  }

  private beginChecks(): void {
    if (this.timer !== undefined) {
      return;
    }
    this.check();
    this.timer = setInterval(() => this.check(), MY_MAILS_NOTICE_CHECK_MS);
  }

  private stopChecks(): void {
    clearInterval(this.timer);
    this.timer = undefined;
  }

  private check(): void {
    const due =
      this.lastLook === undefined || Date.now() - this.lastLook >= MY_MAILS_NOTICE_INTERVAL_MS;
    // The read asks for the user role in the session domain.
    if (!due || this.inFlight || !this.auth.isAuthenticated() || !this.auth.hasDomainUserRole()) {
      return;
    }
    this.inFlight = true;
    this.request = this.api
      .mine(NOTICE_DAYS, { background: true })
      .pipe(finalize(() => (this.inFlight = false)))
      .subscribe({
        next: (mails) => {
          this.lastLook = Date.now();
          this.announce(mails);
        },
        error: (error: unknown) => {
          // A look the token was too old for stays due; any other failure waits for the next look.
          if (!(error instanceof BackgroundReadSkipped)) {
            this.lastLook = Date.now();
          }
        },
      });
  }

  private announce(mails: readonly MyMail[]): void {
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
  }
}
